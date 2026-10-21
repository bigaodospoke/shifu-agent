package gg.shifu.agent.config;

import gg.shifu.agent.log.AgentLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @TempDir
    Path dir;

    @Test
    void criaModeloQuandoOArquivoNaoExiste() throws IOException {
        Path file = dir.resolve("shifu-agent.json");
        AgentConfig config = AgentConfig.loadOrCreate(file, new AgentLogger(dir.resolve("logs")));

        assertTrue(Files.exists(file));
        assertFalse(config.isComplete());
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"apiKey\"") && json.contains("\"serverId\"") && json.contains("\"apiUrl\""));
    }

    @Test
    void leECorrigeValoresEditadosAMao() throws IOException {
        Path file = dir.resolve("shifu-agent.json");
        Files.writeString(file,
                "{\"apiKey\":\"  shf_live_abc  \",\"serverId\":\"srv1\",\"apiUrl\":\"https://shifu-plataforma.vercel.app///\",\"pollIntervalSeconds\":1}",
                StandardCharsets.UTF_8);

        AgentConfig config = AgentConfig.loadOrCreate(file, new AgentLogger(dir.resolve("logs")));

        assertEquals("shf_live_abc", config.apiKey);
        assertTrue(config.isComplete());
        assertEquals("https://shifu-plataforma.vercel.app", config.baseUrl());
        assertEquals(5, config.pollIntervalSeconds, "intervalo mínimo de 5s para não sobrecarregar a API");
    }
}
