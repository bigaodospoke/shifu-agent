package gg.shifu.agent.delivery;

import gg.shifu.agent.api.ApiException;
import gg.shifu.agent.api.ApiModels;
import gg.shifu.agent.api.ApiModels.AckRequest;
import gg.shifu.agent.api.ApiModels.DeliveryAction;
import gg.shifu.agent.api.ApiModels.PendingDelivery;
import gg.shifu.agent.api.ShifuApiClient;
import gg.shifu.agent.log.AgentLogger;
import gg.shifu.agent.log.AgentLogger.Category;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Busca entregas pendentes e executa cada uma.
 *
 * Roda na thread do agente. A única parte que vai para a thread principal do servidor
 * é a execução dos comandos (ver {@link #executeOnServerThread}).
 */
public final class DeliveryService {
    /** Nick válido do Minecraft Java: 3 a 16 caracteres, letras, números e _ */
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final int QUEUE_BATCH = 25;
    private static final int MAX_COMMAND_LENGTH = 1000;

    private final AgentLogger logger;
    private final DeliveryJournal journal;
    /** Entregas que já avisamos como "aguardando jogador" nesta sessão (evita spam na API). */
    private final Set<String> waitingNotified = ConcurrentHashMap.newKeySet();
    private final AtomicLong deliveredCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public DeliveryService(Path dataDir, AgentLogger logger) {
        this.logger = logger;
        this.journal = new DeliveryJournal(dataDir, logger);
    }

    /** @return quantas entregas vieram da API nesta rodada */
    public int processQueue(MinecraftServer server, ShifuApiClient api) throws ApiException {
        ApiModels.QueueResponse queue = api.fetchQueue(QUEUE_BATCH);
        if (queue == null || queue.deliveries == null) {
            return 0;
        }
        for (PendingDelivery delivery : queue.deliveries) {
            if (delivery != null && delivery.id != null) {
                handle(server, api, delivery);
            }
        }
        return queue.deliveries.size();
    }

    private void handle(MinecraftServer server, ShifuApiClient api, PendingDelivery delivery) throws ApiException {
        // 1) Já mexemos nessa entrega antes? (servidor reiniciou, API não recebeu o aviso...)
        DeliveryJournal.Entry previous = journal.get(delivery.id);
        if (previous != null) {
            resumeFromJournal(api, delivery, previous);
            return;
        }

        List<DeliveryAction> actions = delivery.actions == null ? List.of() : delivery.actions;
        logger.log(Category.PURCHASE_RECEIVED, "Pedido " + delivery.orderId + " para " + delivery.playerName
                + " (" + actions.size() + " ações)");

        // 2) Valida TUDO antes de executar QUALQUER comando (nada de entrega pela metade por dado ruim)
        if (delivery.playerName == null || !PLAYER_NAME.matcher(delivery.playerName).matches()) {
            fail(api, delivery, "Nick inválido: " + delivery.playerName, List.of());
            return;
        }
        List<String> commands;
        try {
            commands = resolveCommands(delivery, actions);
        } catch (IllegalArgumentException e) {
            fail(api, delivery, e.getMessage(), List.of());
            return;
        }

        // 3) Executa na thread principal do servidor e espera o resultado (no máximo 60s)
        Outcome outcome;
        try {
            outcome = CompletableFuture
                    .supplyAsync(() -> executeOnServerThread(server, delivery, commands), server)
                    .get(60, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            logger.log(Category.ERROR, "Entrega " + delivery.id + " não terminou: " + e.getMessage());
            return; // o diário diz STARTED (ou nada); na próxima rodada será tratada
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (outcome.waitingForPlayer) {
            if (waitingNotified.add(delivery.id)) {
                api.acknowledge(delivery.id, new AckRequest(AckRequest.WAITING_PLAYER, null, List.of()));
                logger.info("Pedido " + delivery.orderId + " aguardando " + delivery.playerName + " entrar no servidor.");
            }
            return;
        }

        // 4) Avisa a API e registra no log
        String status = outcome.success ? AckRequest.DELIVERED : AckRequest.FAILED;
        String error = outcome.success ? null : "Um ou mais comandos falharam";
        api.acknowledge(delivery.id, new AckRequest(status, error, outcome.results));
        journal.markAcknowledged(delivery.id);
        waitingNotified.remove(delivery.id);

        if (outcome.success) {
            deliveredCount.incrementAndGet();
            logger.log(Category.DELIVERED, "Pedido " + delivery.orderId + " entregue para " + delivery.playerName);
        } else {
            failedCount.incrementAndGet();
            logger.log(Category.DELIVERY_FAILED, "Pedido " + delivery.orderId + " falhou: " + summarize(outcome.results));
        }
    }

    /** Transforma as ações recebidas em comandos prontos, validando cada um. */
    private List<String> resolveCommands(PendingDelivery delivery, List<DeliveryAction> actions) {
        List<String> commands = new ArrayList<>();
        for (DeliveryAction action : actions) {
            String type = action == null || action.type == null ? "" : action.type;
            if (!DeliveryAction.TYPE_COMMAND.equals(type)) {
                throw new IllegalArgumentException("tipo de ação desconhecido: " + type + " (atualize o Shifu Agent)");
            }
            commands.add(sanitizeCommand(action.command, delivery.playerName));
        }
        return commands;
    }

    private static String sanitizeCommand(String command, String playerName) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("comando vazio");
        }
        String clean = command.trim();
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        // Quebra de linha permitiria "esconder" um segundo comando
        if (clean.contains("\n") || clean.contains("\r") || clean.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException("comando com caracteres ou tamanho não permitidos");
        }
        // O site já troca {player}; trocamos de novo por segurança (nick já foi validado)
        return clean.replace("{player}", playerName);
    }

    /** Roda na THREAD PRINCIPAL do servidor. */
    private Outcome executeOnServerThread(MinecraftServer server, PendingDelivery delivery, List<String> commands) {
        if (delivery.requireOnline && server.getPlayerList().getPlayerByName(delivery.playerName) == null) {
            return Outcome.waiting();
        }

        journal.markStarted(delivery.id);
        List<ApiModels.ActionResult> results = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            ApiModels.ActionResult result = CommandExecutor.run(server, i, commands.get(i));
            results.add(result);
            if (!result.success) {
                logger.log(Category.ERROR, "Comando falhou [" + result.command + "]: " + result.output);
            }
        }
        boolean success = results.stream().allMatch(r -> r.success);
        journal.markCompleted(delivery.id, success, results);
        return new Outcome(false, success, results);
    }

    private void resumeFromJournal(ShifuApiClient api, PendingDelivery delivery, DeliveryJournal.Entry previous)
            throws ApiException {
        if (previous.stage == DeliveryJournal.Stage.STARTED) {
            // Não sabemos quais comandos rodaram: repetir poderia duplicar o produto.
            String error = "Entrega interrompida (servidor parou no meio). Confira manualmente antes de reenviar.";
            api.acknowledge(delivery.id, new AckRequest(AckRequest.FAILED, error, List.of()));
            journal.markCompleted(delivery.id, false, List.of());
            journal.markAcknowledged(delivery.id);
            logger.log(Category.DELIVERY_FAILED, "Pedido " + delivery.orderId + ": " + error);
            return;
        }
        String status = previous.success ? AckRequest.DELIVERED : AckRequest.FAILED;
        api.acknowledge(delivery.id, new AckRequest(status, null, previous.results));
        journal.markAcknowledged(delivery.id);
        logger.log(Category.RETRY, "Reenviei à API o resultado do pedido " + delivery.orderId + " (" + status + ")");
    }

    private void fail(ShifuApiClient api, PendingDelivery delivery, String reason,
                      List<ApiModels.ActionResult> results) throws ApiException {
        failedCount.incrementAndGet();
        api.acknowledge(delivery.id, new AckRequest(AckRequest.FAILED, reason, results));
        logger.log(Category.DELIVERY_FAILED, "Pedido " + delivery.orderId + " recusado: " + reason);
    }

    private static String summarize(List<ApiModels.ActionResult> results) {
        return results.stream()
                .filter(r -> !r.success)
                .map(r -> r.command + " -> " + r.output)
                .reduce((a, b) -> a + "; " + b)
                .orElse("sem detalhes");
    }

    public long deliveredCount() {
        return deliveredCount.get();
    }

    public long failedCount() {
        return failedCount.get();
    }

    private record Outcome(boolean waitingForPlayer, boolean success, List<ApiModels.ActionResult> results) {
        static Outcome waiting() {
            return new Outcome(true, false, List.of());
        }
    }
}
