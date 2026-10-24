package ni.edu.uam.campusfood.security;

public class RateLimitException extends RuntimeException {
    private final long retryAfterSeconds;
    public RateLimitException(long retryAfterSeconds) {
        super("Demasiados intentos. Espera unos minutos antes de volver a intentar.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
