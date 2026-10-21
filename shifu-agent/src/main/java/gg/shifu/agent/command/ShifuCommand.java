package gg.shifu.agent.command;

import com.mojang.brigadier.CommandDispatcher;
import gg.shifu.agent.core.AgentService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.concurrent.CompletionException;

/**
 * Comandos do agente (precisam de permissão de operador nível 3+):
 *
 * /shifu status     estado da conexão e contadores
 * /shifu reconnect  relê o shifu-agent.json e reconecta
 * /shifu sync       busca e entrega compras agora
 * /shifu test       testa a chave e mede a latência da API
 */
public final class ShifuCommand {
    private ShifuCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AgentService service) {
        dispatcher.register(Commands.literal("shifu")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource(), service)))
                .then(Commands.literal("reconnect").executes(ctx -> reconnect(ctx.getSource(), service)))
                .then(Commands.literal("sync").executes(ctx -> sync(ctx.getSource(), service)))
                .then(Commands.literal("test").executes(ctx -> test(ctx.getSource(), service))));
    }

    private static int status(CommandSourceStack source, AgentService service) {
        AgentService.Status s = service.status();
        ChatFormatting color = s.state() == AgentService.State.CONNECTED ? ChatFormatting.GREEN : ChatFormatting.RED;

        reply(source, Component.literal("Shifu Agent: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(s.state().name()).withStyle(color)));
        reply(source, line("Servidor na Shifu", s.serverName() == null ? "-" : s.serverName()));
        reply(source, line("Último heartbeat", ago(s.lastHeartbeatAt())));
        reply(source, line("Última sincronização", ago(s.lastSyncAt())));
        reply(source, line("Entregues / falhas (sessão)", s.delivered() + " / " + s.failed()));
        if (s.lastError() != null) {
            reply(source, line("Último erro", s.lastError()));
        }
        if (s.state() == AgentService.State.NOT_CONFIGURED) {
            reply(source, line("Configure em", s.configPath()));
        }
        return 1;
    }

    private static int reconnect(CommandSourceStack source, AgentService service) {
        reply(source, Component.literal("Reconectando à Shifu...").withStyle(ChatFormatting.GRAY));
        service.reconnect().whenComplete((state, error) -> {
            if (error != null) {
                replyLater(source, failure("Falha ao reconectar: " + rootMessage(error)));
            } else {
                replyLater(source, Component.literal("Estado: " + state).withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        });
        return 1;
    }

    private static int sync(CommandSourceStack source, AgentService service) {
        reply(source, Component.literal("Sincronizando compras...").withStyle(ChatFormatting.GRAY));
        service.syncNow().whenComplete((count, error) -> {
            if (error != null) {
                replyLater(source, failure("Falha ao sincronizar: " + rootMessage(error)));
            } else {
                replyLater(source, Component.literal(count + " entrega(s) processada(s).").withStyle(ChatFormatting.GREEN));
            }
        });
        return 1;
    }

    private static int test(CommandSourceStack source, AgentService service) {
        reply(source, Component.literal("Testando conexão com a API...").withStyle(ChatFormatting.GRAY));
        service.testConnection().whenComplete((millis, error) -> {
            if (error != null) {
                replyLater(source, failure("Teste falhou: " + rootMessage(error)));
            } else {
                replyLater(source, Component.literal("API OK em " + millis + " ms. Chave válida.").withStyle(ChatFormatting.GREEN));
            }
        });
        return 1;
    }

    // ---------------------------------------------------------------- helpers

    private static void reply(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    /** Respostas de tarefas assíncronas precisam voltar para a thread principal. */
    private static void replyLater(CommandSourceStack source, Component message) {
        source.getServer().execute(() -> reply(source, message));
    }

    private static Component line(String label, String value) {
        return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static Component failure(String message) {
        return Component.literal(message).withStyle(ChatFormatting.RED);
    }

    private static String ago(long timestamp) {
        if (timestamp <= 0) {
            return "nunca";
        }
        long seconds = Duration.ofMillis(System.currentTimeMillis() - timestamp).toSeconds();
        return seconds < 60 ? seconds + "s atrás" : (seconds / 60) + " min atrás";
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
