package gg.shifu.agent.log;

import gg.shifu.agent.api.ApiModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sistema de logs do agente. Cada mensagem vai para 3 lugares:
 * 1. Console do servidor (via SLF4J, o mesmo logger do Minecraft)
 * 2. Arquivo logs/shifu-agent/agent-AAAA-MM-DD.log (um por dia)
 * 3. Uma fila que é enviada para a API e aparece no painel da Shifu
 */
public final class AgentLogger {
    public enum Category {
        INFO,
        NOT_CONFIGURED,      // falta apiKey/serverId no shifu-agent.json (não é erro)
        PURCHASE_RECEIVED,   // compra recebida
        DELIVERED,           // compra entregue
        DELIVERY_FAILED,     // entrega falhou
        ERROR,               // erro genérico
        CONNECTION_FAILURE,  // perdeu conexão com a API
        RETRY                // tentativa de reenvio
    }

    private static final Logger CONSOLE = LoggerFactory.getLogger("ShifuAgent");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Se a API ficar fora do ar, não deixamos a fila crescer para sempre. */
    private static final int MAX_REMOTE_QUEUE = 500;

    private final Path directory;
    private final Queue<ApiModels.RemoteLogEntry> remoteQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger remoteSize = new AtomicInteger();

    public AgentLogger(Path directory) {
        this.directory = directory;
    }

    public void info(String message) {
        log(Category.INFO, message);
    }

    public void error(String message) {
        log(Category.ERROR, message);
    }

    public void log(Category category, String message) {
        String level = switch (category) {
            case ERROR, DELIVERY_FAILED -> "ERROR";
            case CONNECTION_FAILURE, RETRY -> "WARN";
            default -> "INFO";
        };

        // Category.INFO não vira etiqueta: o console já mostra o nível, e sairia "[INFO] [INFO] mensagem"
        String tag = category == Category.INFO ? "" : "[" + category + "] ";

        switch (level) {
            case "ERROR" -> CONSOLE.error("{}{}", tag, message);
            case "WARN" -> CONSOLE.warn("{}{}", tag, message);
            default -> CONSOLE.info("{}{}", tag, message);
        }

        writeToFile(LocalDateTime.now().format(TIME) + " [" + level + "] " + tag + message);

        if (remoteSize.get() < MAX_REMOTE_QUEUE) {
            remoteQueue.add(new ApiModels.RemoteLogEntry(level, category.name(), message, System.currentTimeMillis()));
            remoteSize.incrementAndGet();
        }
    }

    /** Retira até {@code max} logs da fila para enviar à API. */
    public List<ApiModels.RemoteLogEntry> drainRemote(int max) {
        List<ApiModels.RemoteLogEntry> batch = new ArrayList<>();
        ApiModels.RemoteLogEntry entry;
        while (batch.size() < max && (entry = remoteQueue.poll()) != null) {
            batch.add(entry);
            remoteSize.decrementAndGet();
        }
        return batch;
    }

    private synchronized void writeToFile(String line) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("agent-" + LocalDate.now() + ".log");
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            CONSOLE.warn("Não consegui gravar o log do Shifu Agent: {}", e.getMessage());
        }
    }
}
