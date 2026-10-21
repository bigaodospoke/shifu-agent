package gg.shifu.agent.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import gg.shifu.agent.config.AgentConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Cliente HTTP da API da Shifu.
 *
 * Usa o HttpClient que já vem no Java 21 (sem bibliotecas extras).
 * IMPORTANTE: todos os métodos BLOQUEIAM até a resposta chegar, então nunca chame
 * na thread principal do servidor (travaria o jogo). O AgentService usa uma thread própria.
 */
public final class ShifuApiClient {
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final AgentConfig config;
    private final String userAgent;

    public ShifuApiClient(AgentConfig config, String agentVersion) {
        this.config = config;
        this.userAgent = "ShifuAgent/" + agentVersion + " (Fabric)";
    }

    public ApiModels.HeartbeatResponse heartbeat(ApiModels.HeartbeatRequest body) throws ApiException {
        return send("POST", "/api/agent/v1/heartbeat", body, ApiModels.HeartbeatResponse.class);
    }

    public ApiModels.QueueResponse fetchQueue(int limit) throws ApiException {
        return send("GET", "/api/agent/v1/queue?limit=" + limit, null, ApiModels.QueueResponse.class);
    }

    public void acknowledge(String deliveryId, ApiModels.AckRequest body) throws ApiException {
        String id = URLEncoder.encode(deliveryId, StandardCharsets.UTF_8);
        send("POST", "/api/agent/v1/deliveries/" + id, body, ApiModels.OkResponse.class);
    }

    public void sendLogs(List<ApiModels.RemoteLogEntry> entries) throws ApiException {
        send("POST", "/api/agent/v1/logs", new ApiModels.LogBatch(entries), ApiModels.OkResponse.class);
    }

    private <T> T send(String method, String path, Object body, Class<T> responseType) throws ApiException {
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path));
        } catch (IllegalArgumentException e) {
            throw new ApiException(0, "apiUrl inválida no shifu-agent.json: " + config.apiUrl, 0);
        }

        request.timeout(Duration.ofSeconds(15))
                // A API Key vai no cabeçalho Authorization, nunca na URL (URLs aparecem em logs)
                .header("Authorization", "Bearer " + config.apiKey)
                .header("X-Shifu-Server-Id", config.serverId)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");

        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw ApiException.network(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.network(e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            try {
                return GSON.fromJson(response.body(), responseType);
            } catch (JsonParseException e) {
                throw new ApiException(status, "a API respondeu algo que não é JSON válido", 0);
            }
        }

        long retryAfter = response.headers().firstValue("Retry-After")
                .map(ShifuApiClient::parseLong)
                .orElse(0L);
        throw new ApiException(status, "HTTP " + status + ": " + extractError(response.body()), retryAfter);
    }

    /** A API responde erros como {"error": "mensagem"}; pega só a mensagem. */
    private static String extractError(String body) {
        try {
            JsonObject json = GSON.fromJson(body, JsonObject.class);
            if (json != null && json.has("error")) {
                return json.get("error").getAsString();
            }
        } catch (RuntimeException ignored) {
            // corpo não era JSON: cai no retorno abaixo
        }
        return body == null || body.isBlank() ? "sem detalhes" : body.substring(0, Math.min(body.length(), 200));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
