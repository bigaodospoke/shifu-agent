package gg.shifu.agent.delivery;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import gg.shifu.agent.api.ApiModels;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Executa comandos como se tivessem sido digitados no console do servidor.
 *
 * DEVE ser chamado na thread principal do servidor (o Minecraft não é thread-safe).
 * O DeliveryService garante isso usando o servidor como Executor.
 */
public final class CommandExecutor {
    private CommandExecutor() {
    }

    public static ApiModels.ActionResult run(MinecraftServer server, int index, String command) {
        CapturingSource output = new CapturingSource();
        boolean[] callbackCalled = {false};
        boolean[] succeeded = {false};

        // Fonte = console (nível de permissão máximo), mas com a saída capturada por nós
        // e um callback que diz se o comando deu certo.
        CommandSourceStack source = server.createCommandSourceStack()
                .withSource(output)
                .withCallback((success, result) -> {
                    callbackCalled[0] = true;
                    succeeded[0] = success;
                });

        // Primeiro só "lemos" o comando para detectar comando inexistente ou incompleto
        // antes de executar qualquer coisa.
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
        if (parse.getReader().canRead() || lastContext(parse).getCommand() == null) {
            String reason = parse.getExceptions().values().stream()
                    .findFirst()
                    .map(Exception::getMessage)
                    .orElse("comando desconhecido ou incompleto");
            return new ApiModels.ActionResult(index, command, false, "Comando inválido: " + reason);
        }

        server.getCommands().performPrefixedCommand(source, command);

        // Se o comando não avisou o resultado, consideramos sucesso (não houve exceção).
        boolean success = !callbackCalled[0] || succeeded[0];
        return new ApiModels.ActionResult(index, command, success, output.text());
    }

    /** Comandos como "execute ... run ..." encadeiam contextos; o que executa é o último. */
    private static CommandContextBuilder<CommandSourceStack> lastContext(ParseResults<CommandSourceStack> parse) {
        CommandContextBuilder<CommandSourceStack> context = parse.getContext();
        while (context.getChild() != null) {
            context = context.getChild();
        }
        return context;
    }

    /** Recebe as mensagens que o comando escreveria no console. */
    private static final class CapturingSource implements CommandSource {
        private static final int MAX_LENGTH = 1000;
        private final StringBuilder text = new StringBuilder();

        @Override
        public void sendSystemMessage(Component component) {
            if (text.length() >= MAX_LENGTH) {
                return;
            }
            if (!text.isEmpty()) {
                text.append(" | ");
            }
            text.append(component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            // false = não espalha "[Server: ...]" no chat dos operadores a cada entrega
            return false;
        }

        String text() {
            return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text.toString();
        }
    }
}
