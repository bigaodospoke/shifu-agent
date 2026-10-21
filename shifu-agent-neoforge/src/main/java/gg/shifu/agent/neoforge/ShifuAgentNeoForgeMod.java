package gg.shifu.agent.neoforge;

import gg.shifu.agent.command.ShifuCommand;
import gg.shifu.agent.config.AgentConfig;
import gg.shifu.agent.core.AgentService;
import gg.shifu.agent.log.AgentLogger;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;

/**
 * Ponto de entrada do mod no NeoForge.
 *
 * É a ÚNICA classe diferente da versão Fabric: todo o resto (API, configuração, fila de entregas,
 * comandos /shifu e logs) é o mesmo código, compilado a partir de ../shifu-agent/src/main/java.
 *
 * No NeoForge, o construtor da classe anotada com @Mod roda quando o jogo carrega os mods, e os
 * eventos do servidor são registrados no "event bus" do NeoForge.
 */
@Mod(ShifuAgentNeoForgeMod.MOD_ID)
public final class ShifuAgentNeoForgeMod {
    public static final String MOD_ID = "shifu_agent";

    private final AgentService service;

    public ShifuAgentNeoForgeMod(ModContainer modContainer) {
        // config/shifu-agent.json  -> configuração que o dono do servidor edita
        // config/shifu-agent/      -> dados internos (diário de entregas)
        // logs/shifu-agent/        -> logs legíveis por humanos
        Path configFile = FMLPaths.CONFIGDIR.get().resolve("shifu-agent.json");
        Path dataDir = FMLPaths.CONFIGDIR.get().resolve("shifu-agent");
        Path logDir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("shifu-agent");

        AgentLogger logger = new AgentLogger(logDir);
        AgentConfig config = AgentConfig.loadOrCreate(configFile, logger);
        this.service = new AgentService(config, logger, dataDir, modContainer.getModInfo().getVersion().toString());

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
    }

    /** Registra /shifu status | reconnect | sync | test */
    private void onRegisterCommands(RegisterCommandsEvent event) {
        ShifuCommand.register(event.getDispatcher(), service);
    }

    /** Só começamos a conversar com a API quando o servidor terminou de iniciar. */
    private void onServerStarted(ServerStartedEvent event) {
        service.start(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        service.stop();
    }

    /** Alguém entrou? Pode existir compra esperando essa pessoa ficar online. */
    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        service.requestSync();
    }
}
