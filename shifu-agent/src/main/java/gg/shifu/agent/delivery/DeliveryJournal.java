package gg.shifu.agent.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import gg.shifu.agent.api.ApiModels;
import gg.shifu.agent.log.AgentLogger;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * "Diário" de entregas salvo em disco (config/shifu-agent/journal.json).
 *
 * Problema que ele resolve: o servidor pode cair entre "executei os comandos" e
 * "avisei a API". Sem o diário, na volta a API mandaria a mesma entrega de novo e o
 * jogador ganharia o VIP duas vezes. Com o diário:
 *
 * STARTED      -> começamos a executar; se o servidor cair aqui, NÃO repetimos
 *                 (marcamos como falha para o dono conferir manualmente).
 * COMPLETED    -> comandos executados; falta avisar a API. Na volta só avisamos.
 * ACKNOWLEDGED -> a API já sabe. Mantido por alguns dias e depois apagado.
 */
public final class DeliveryJournal {
    public enum Stage { STARTED, COMPLETED, ACKNOWLEDGED }

    public static final class Entry {
        public Stage stage;
        public boolean success;
        public List<ApiModels.ActionResult> results;
        public long updatedAt;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Entry>>() { }.getType();
    private static final long KEEP_ACKNOWLEDGED_MS = TimeUnit.DAYS.toMillis(7);

    private final Path file;
    private final AgentLogger logger;
    private Map<String, Entry> entries = new LinkedHashMap<>();

    public DeliveryJournal(Path dataDir, AgentLogger logger) {
        this.file = dataDir.resolve("journal.json");
        this.logger = logger;
        load();
    }

    public synchronized Entry get(String deliveryId) {
        return entries.get(deliveryId);
    }

    public synchronized void markStarted(String deliveryId) {
        put(deliveryId, Stage.STARTED, false, null);
    }

    public synchronized void markCompleted(String deliveryId, boolean success, List<ApiModels.ActionResult> results) {
        put(deliveryId, Stage.COMPLETED, success, results);
    }

    public synchronized void markAcknowledged(String deliveryId) {
        Entry entry = entries.get(deliveryId);
        if (entry != null) {
            put(deliveryId, Stage.ACKNOWLEDGED, entry.success, entry.results);
        }
    }

    private void put(String deliveryId, Stage stage, boolean success, List<ApiModels.ActionResult> results) {
        Entry entry = new Entry();
        entry.stage = stage;
        entry.success = success;
        entry.results = results;
        entry.updatedAt = System.currentTimeMillis();
        entries.put(deliveryId, entry);
        prune();
        save();
    }

    private void prune() {
        long limit = System.currentTimeMillis() - KEEP_ACKNOWLEDGED_MS;
        entries.values().removeIf(e -> e.stage == Stage.ACKNOWLEDGED && e.updatedAt < limit);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Entry> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                entries = loaded;
            }
        } catch (IOException | JsonParseException e) {
            logger.error("Diário de entregas corrompido (" + e.getMessage() + "). Começando um novo.");
        }
    }

    /** Grava num arquivo temporário e troca de uma vez, para nunca deixar o JSON pela metade. */
    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling("journal.json.tmp");
            Files.writeString(temp, GSON.toJson(entries, MAP_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Não consegui salvar o diário de entregas: " + e.getMessage());
        }
    }
}
