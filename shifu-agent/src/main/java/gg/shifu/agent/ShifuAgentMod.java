package gg.shifu.agent;

import gg.shifu.agent.command.ShifuCommand;
import gg.shifu.agent.config.AgentConfig;
import gg.shifu.agent.core.AgentService;
import gg.shifu.agent.log.AgentLogger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Ponto de entrada do mod.
 *
 * O Fabric chama {@link #onInitialize()} uma única vez, enquanto carrega os mods.
 * Aqui só "ligamos os fios": carregamos a configuração e registramos eventos e comandos.
 * O trabalho de verdade (falar com a API e entregar compras) fica no {@link AgentService}.
 */
public final class ShifuAgentMod implements ModInitializer {
    public static final String MOD_ID = "shifu_agent";

    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();

        // config/shifu-agent.json  -> configuração que o dono do servidor edita
        // config/shifu-agent/      -> dados internos (diário de entregas)
        // logs/shifu-agent/        -> logs legíveis por humanos
        Path configFile = loader.getConfigDir().resolve("shifu-agent.json");
        Path dataDir = loader.getConfigDir().resolve("shifu-agent");
        Path logDir = loader.getGameDir().resolve("logs").resolve("shifu-agent");

        AgentLogger logger = new AgentLogger(logDir);
        AgentConfig config = AgentConfig.loadOrCreate(configFile, logger);
        AgentService service = new AgentService(config, logger, dataDir, version());

        // Registra /shifu status | reconnect | sync | test
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ShifuCommand.register(dispatcher, service));

        // Só começamos a conversar com a API quando o servidor terminou de iniciar.
        ServerLifecycleEvents.SERVER_STARTED.register(service::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> service.stop());

        // Alguém entrou? Pode existir compra esperando essa pessoa ficar online.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> service.requestSync());
    }

    /** Versão do mod lida do fabric.mod.json (preenchida pelo Gradle no build). */
    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
    }
}
