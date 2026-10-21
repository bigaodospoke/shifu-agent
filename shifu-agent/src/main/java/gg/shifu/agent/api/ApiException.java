package gg.shifu.agent.api;

/**
 * Erro ao conversar com a API.
 *
 * status = 0 significa erro de rede (sem resposta: API fora do ar, DNS, firewall...).
 * Os outros valores são códigos HTTP (401 = chave inválida, 429 = muitas requisições, 5xx = erro na API).
 */
public final class ApiException extends Exception {
    private final int status;
    private final long retryAfterSeconds;

    public ApiException(int status, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException network(Exception cause) {
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return new ApiException(0, "sem conexão com a API: " + reason, 0);
    }

    public int status() {
        return status;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isUnauthorized() {
        return status == 401 || status == 403;
    }
}
