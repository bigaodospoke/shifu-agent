package gg.shifu.agent.api;

import java.util.List;

/**
 * "Contrato" entre o agente e a API da Shifu.
 *
 * Cada classe abaixo espelha um JSON enviado ou recebido. Se você mudar um campo aqui,
 * precisa mudar o mesmo campo no site (web/src/lib/agent/protocol.ts), senão os dois
 * lados deixam de se entender.
 */
public final class ApiModels {
    private ApiModels() {
    }

    /** POST /api/agent/v1/heartbeat — "estou vivo" enviado periodicamente. */
    public static final class HeartbeatRequest {
        public String agentVersion;
        public String minecraftVersion;
        public int onlinePlayers;
        public int maxPlayers;
    }

    public static final class HeartbeatResponse {
        public String serverName;
        public int pollIntervalSeconds;
    }

    /** GET /api/agent/v1/queue — entregas pendentes. */
    public static final class QueueResponse {
        public List<PendingDelivery> deliveries;
    }

    public static final class PendingDelivery {
        public String id;
        public String orderId;
        public String playerName;
        /** Se true, só entrega com o jogador online (itens precisam disso: vão para o inventário). */
        public boolean requireOnline;
        public List<DeliveryAction> actions;
    }

    /**
     * Uma ação dentro de uma entrega. Hoje toda ação é um comando de console,
     * então o agente funciona com qualquer mod ou plugin que tenha comando.
     */
    public static final class DeliveryAction {
        public static final String TYPE_COMMAND = "command";

        /** Tipo da ação. Tipos desconhecidos fazem a entrega falhar sem executar nada. */
        public String type;
        /** Comando já pronto, ex.: "lp user Steve parent add vip" */
        public String command;
    }

    /** POST /api/agent/v1/deliveries/{id} — resultado da entrega. */
    public static final class AckRequest {
        public static final String DELIVERED = "DELIVERED";
        public static final String FAILED = "FAILED";
        public static final String WAITING_PLAYER = "WAITING_PLAYER";

        public String status;
        public String error;
        public List<ActionResult> results;

        public AckRequest(String status, String error, List<ActionResult> results) {
            this.status = status;
            this.error = error;
            this.results = results;
        }
    }

    public static final class ActionResult {
        public int index;
        public String command;
        public boolean success;
        public String output;

        public ActionResult(int index, String command, boolean success, String output) {
            this.index = index;
            this.command = command;
            this.success = success;
            this.output = output;
        }
    }

    /** POST /api/agent/v1/logs — envia logs para aparecerem no painel. */
    public static final class LogBatch {
        public List<RemoteLogEntry> entries;

        public LogBatch(List<RemoteLogEntry> entries) {
            this.entries = entries;
        }
    }

    public static final class RemoteLogEntry {
        public String level;
        public String category;
        public String message;
        public long timestamp;

        public RemoteLogEntry(String level, String category, String message, long timestamp) {
            this.level = level;
            this.category = category;
            this.message = message;
            this.timestamp = timestamp;
        }
    }

    public static final class OkResponse {
        public boolean ok;
    }
}
