package gg.shifu.agent.core;

import gg.shifu.agent.api.ApiException;
import gg.shifu.agent.api.ApiModels;
import gg.shifu.agent.api.ShifuApiClient;
import gg.shifu.agent.config.AgentConfig;
import gg.shifu.agent.delivery.DeliveryService;
import gg.shifu.agent.log.AgentLogger;
import gg.shifu.agent.log.AgentLogger.Category;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * O "coração" do agente: mantém a conexão com a API e roda o ciclo de entregas.
 *
 * Tudo roda numa única thread chamada "Shifu-Agent". Como é uma thread só, dois ciclos
 * nunca rodam ao mesmo tempo (sem risco de entregar a mesma compra em paralelo).
 *
 * Ciclo (a cada pollIntervalSeconds):
 *   heartbeat (se necessário) -> busca fila -> entrega -> envia logs
 *
 * Se a API falhar, espera cada vez mais antes de tentar de novo (backoff exponencial):
 * 15s, 30s, 60s, 120s... até 5 minutos. Isso é a "reconexão automática".
 */
public final class AgentService {
    public enum State { STOPPED, NOT_CONFIGURED, CONNECTING, CONNECTED, DISCONNECTED, UNAUTHORIZED }

    private static final long MAX_BACKOFF_SECONDS = 300;
    private static final long HEARTBEAT_EVERY_MS = 60_000;

    private final AgentLogger logger;
    private final DeliveryService deliveries;
    private final String agentVersion;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Shifu-Agent");
        thread.setDaemon(true); // não impede o servidor de desligar
        return thread;
    });

    // "volatile" = seguro para ler de outra thread (os comandos /shifu leem estes campos)
    private volatile AgentConfig config;
    private volatile ShifuApiClient api;
    private volatile MinecraftServer server;
    private volatile State state = State.STOPPED;
    private volatile String lastError;
    private volatile String remoteServerName;
    private volatile long lastHeartbeatAt;
    private volatile long lastSyncAt;
    private volatile int pollIntervalSeconds;

    /** Só é alterado dentro da thread Shifu-Agent. */
    private int consecutiveFailures;
    private ScheduledFuture<?> nextCycle;

    public AgentService(AgentConfig config, AgentLogger logger, Path dataDir, String agentVersion) {
        this.config = config;
        this.logger = logger;
        this.agentVersion = agentVersion;
        this.deliveries = new DeliveryService(dataDir, logger);
        this.api = new ShifuApiClient(config, agentVersion);
        this.pollIntervalSeconds = config.pollIntervalSeconds;
    }

    // ---------------------------------------------------------------- ciclo de vida

    public void start(MinecraftServer server) {
        this.server = server;
        if (!config.isComplete()) {
            state = State.NOT_CONFIGURED;
            logger.log(Category.NOT_CONFIGURED, "Shifu Agent não configurado. Edite " + config.file()
                    + " e use /shifu reconnect.");
            return;
        }
        state = State.CONNECTING;
        logger.info("Iniciando Shifu Agent " + agentVersion + " -> " + config.baseUrl());
        scheduleCycle(0);
    }

    public void stop() {
        state = State.STOPPED;
        synchronized (this) {
            if (nextCycle != null) {
                nextCycle.cancel(false);
            }
        }
        scheduler.shutdown();
        try {
            // Dá uma chance de terminar a entrega em andamento
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- ações dos comandos

    /** /shifu reconnect — relê o shifu-agent.json e reconecta do zero. */
    public CompletableFuture<State> reconnect() {
        return CompletableFuture.supplyAsync(() -> {
            config = config.reload(logger);
            api = new ShifuApiClient(config, agentVersion);
            pollIntervalSeconds = config.pollIntervalSeconds;
            consecutiveFailures = 0;
            lastHeartbeatAt = 0;
            if (!config.isComplete()) {
                state = State.NOT_CONFIGURED;
                return state;
            }
            state = State.CONNECTING;
            runCycleAndReschedule();
            return state;
        }, scheduler);
    }

    /** /shifu sync — busca e entrega agora, sem esperar o próximo ciclo. */
    public CompletableFuture<Integer> syncNow() {
        return CompletableFuture.supplyAsync(() -> {
            ensureConfigured();
            try {
                int handled = runCycle();
                consecutiveFailures = 0;
                scheduleCycle(pollIntervalSeconds);
                return handled;
            } catch (ApiException e) {
                scheduleCycle(handleFailure(e));
                throw new CompletionException(e);
            }
        }, scheduler);
    }

    /** /shifu test — mede quanto tempo a API leva para responder e valida a chave. */
    public CompletableFuture<Long> testConnection() {
        return CompletableFuture.supplyAsync(() -> {
            ensureConfigured();
            long start = System.nanoTime();
            try {
                sendHeartbeat();
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }, scheduler);
    }

    /** Pede um ciclo em breve (ex.: jogador entrou no servidor). */
    public void requestSync() {
        if (state == State.CONNECTED || state == State.DISCONNECTED) {
            scheduleCycle(2);
        }
    }

    public Status status() {
        return new Status(state, remoteServerName, lastHeartbeatAt, lastSyncAt, lastError,
                deliveries.deliveredCount(), deliveries.failedCount(),
                config.file() == null ? "config/shifu-agent.json" : config.file().toString());
    }

    public record Status(State state, String serverName, long lastHeartbeatAt, long lastSyncAt, String lastError,
                         long delivered, long failed, String configPath) {
    }

    // ---------------------------------------------------------------- internos (thread Shifu-Agent)

    private synchronized void scheduleCycle(long delaySeconds) {
        if (state == State.STOPPED || state == State.NOT_CONFIGURED || scheduler.isShutdown()) {
            return;
        }
        if (nextCycle != null) {
            nextCycle.cancel(false);
        }
        nextCycle = scheduler.schedule(this::runCycleAndReschedule, delaySeconds, TimeUnit.SECONDS);
    }

    private void runCycleAndReschedule() {
        long delay;
        try {
            runCycle();
            consecutiveFailures = 0;
            delay = pollIntervalSeconds;
        } catch (ApiException e) {
            delay = handleFailure(e);
        } catch (RuntimeException e) {
            // Nunca deixe uma exceção matar o ciclo: o agente pararia de entregar para sempre
            consecutiveFailures++;
            lastError = e.toString();
            logger.error("Erro inesperado no ciclo: " + e);
            delay = backoffSeconds(0);
        }
        scheduleCycle(delay);
    }

    private int runCycle() throws ApiException {
        MinecraftServer current = server;
        if (current == null) {
            return 0;
        }
        if (state != State.CONNECTED || System.currentTimeMillis() - lastHeartbeatAt > HEARTBEAT_EVERY_MS) {
            sendHeartbeat();
        }
        int handled = deliveries.processQueue(current, api);
        flushLogs();
        lastSyncAt = System.currentTimeMillis();
        return handled;
    }

    private void sendHeartbeat() throws ApiException {
        MinecraftServer current = server;
        ApiModels.HeartbeatRequest body = new ApiModels.HeartbeatRequest();
        body.agentVersion = agentVersion;
        body.minecraftVersion = current != null ? current.getServerVersion() : "?";
        body.onlinePlayers = current != null ? current.getPlayerCount() : 0;
        body.maxPlayers = current != null ? current.getMaxPlayers() : 0;

        ApiModels.HeartbeatResponse response = api.heartbeat(body);
        lastHeartbeatAt = System.currentTimeMillis();
        if (response != null) {
            remoteServerName = response.serverName;
            if (response.pollIntervalSeconds >= 5) {
                pollIntervalSeconds = Math.min(response.pollIntervalSeconds, 300);
            }
        }
        if (state != State.CONNECTED) {
            logger.info("Conectado à Shifu como \"" + remoteServerName + "\".");
        }
        state = State.CONNECTED;
        lastError = null;
    }

    private void flushLogs() {
        List<ApiModels.RemoteLogEntry> batch = logger.drainRemote(100);
        if (batch.isEmpty()) {
            return;
        }
        try {
            api.sendLogs(batch);
        } catch (ApiException e) {
            // Logs no painel são "bônus": se falhar, continuam no arquivo local
        }
    }

    private long handleFailure(ApiException e) {
        consecutiveFailures++;
        lastError = e.getMessage();

        if (e.isUnauthorized()) {
            if (state != State.UNAUTHORIZED) {
                logger.log(Category.CONNECTION_FAILURE, "A API recusou a chave (" + e.getMessage()
                        + "). Confira apiKey/serverId e use /shifu reconnect.");
            }
            state = State.UNAUTHORIZED;
            return MAX_BACKOFF_SECONDS;
        }

        State previous = state;
        state = State.DISCONNECTED;
        long delay = backoffSeconds(e.retryAfterSeconds());
        Category category = previous == State.CONNECTED ? Category.CONNECTION_FAILURE : Category.RETRY;
        logger.log(category, e.getMessage() + " — nova tentativa em " + delay + "s (tentativa "
                + consecutiveFailures + ")");
        return delay;
    }

    /** 15s, 30s, 60s... até 5 min, com uma variação aleatória para vários servidores não baterem juntos. */
    private long backoffSeconds(long retryAfter) {
        int exponent = Math.min(consecutiveFailures, 6);
        long base = Math.min(MAX_BACKOFF_SECONDS, (long) pollIntervalSeconds << exponent);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 5));
        return Math.max(retryAfter, Math.min(MAX_BACKOFF_SECONDS, base + jitter));
    }

    private void ensureConfigured() {
        if (!config.isComplete()) {
            throw new CompletionException(new IllegalStateException(
                    "Shifu Agent não configurado: preencha " + config.file()));
        }
        if (state == State.STOPPED) {
            throw new CompletionException(new IllegalStateException("o agente está desligado"));
        }
    }
}
