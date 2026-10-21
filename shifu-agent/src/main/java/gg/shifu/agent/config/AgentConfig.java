package gg.shifu.agent.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import gg.shifu.agent.log.AgentLogger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Representa o arquivo config/shifu-agent.json.
 *
 * O Gson (biblioteca de JSON que já vem com o Minecraft) transforma o JSON nesta classe
 * e vice-versa: cada campo público vira uma chave do JSON.
 */
public final class AgentConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Chave gerada no painel da Shifu (Servidores > API Keys). Nunca compartilhe. */
    public String apiKey = "";
    /** ID do servidor mostrado no painel da Shifu. */
    public String serverId = "";
    /** Endereço da plataforma (o painel da Shifu gera o JSON já com o valor certo). */
    public String apiUrl = "https://shifu-plataforma.vercel.app";
    /** De quantos em quantos segundos buscar compras (a API pode sugerir outro valor). */
    public int pollIntervalSeconds = 15;
    /** Mostra detalhes extras no console. */
    public boolean debug = false;

    // "transient" = o Gson ignora este campo (não vai para o JSON)
    private transient Path file;

    /** Lê o arquivo; se ele não existir, cria um modelo vazio para o dono preencher. */
    public static AgentConfig loadOrCreate(Path file, AgentLogger logger) {
        AgentConfig config = new AgentConfig();
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    AgentConfig loaded = GSON.fromJson(reader, AgentConfig.class);
                    if (loaded != null) {
                        config = loaded;
                    }
                }
            } else {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(config, writer);
                }
                logger.info("Criei " + file + ". Preencha apiKey, serverId e apiUrl e use /shifu reconnect.");
            }
        } catch (IOException | JsonParseException e) {
            logger.error("Não consegui ler " + file + ": " + e.getMessage());
        }
        config.file = file;
        config.sanitize();
        return config;
    }

    /** Relê o arquivo do disco (usado pelo /shifu reconnect). */
    public AgentConfig reload(AgentLogger logger) {
        return loadOrCreate(file, logger);
    }

    public boolean isComplete() {
        return !apiKey.isBlank() && !serverId.isBlank() && !apiUrl.isBlank();
    }

    /** apiUrl sem a barra no final, para montar URLs como baseUrl() + "/api/...". */
    public String baseUrl() {
        String url = apiUrl;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public Path file() {
        return file;
    }

    /** Corrige valores vazios ou absurdos vindos de um JSON editado à mão. */
    private void sanitize() {
        apiKey = apiKey == null ? "" : apiKey.trim();
        serverId = serverId == null ? "" : serverId.trim();
        apiUrl = apiUrl == null ? "" : apiUrl.trim();
        pollIntervalSeconds = Math.max(5, Math.min(300, pollIntervalSeconds));
    }
}
