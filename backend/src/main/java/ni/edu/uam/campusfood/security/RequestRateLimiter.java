package ni.edu.uam.campusfood.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de ventana fija en memoria. Cada clave (por ejemplo la IP del
 * cliente) puede hacer {@code maxRequests} peticiones por ventana; al
 * agotarlas debe esperar a que la ventana termine.
 */
public class RequestRateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int maxRequests;
    private final Duration window;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RequestRateLimiter(int maxRequests, Duration window) {
        if (maxRequests < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Limite de peticiones invalido");
        }
        this.maxRequests = maxRequests;
        this.window = window;
    }

    /** Registra una peticion; devuelve false si la clave ya agoto su cupo. */
    public boolean tryAcquire(String key, Instant now) {
        if (buckets.size() > CLEANUP_THRESHOLD) {
            buckets.values().removeIf(bucket -> bucket.isExpired(now, window));
        }
        Bucket bucket = buckets.compute(key, (k, current) ->
                current == null || current.isExpired(now, window) ? new Bucket(now, 1) : current.increment());
        return bucket.count() <= maxRequests;
    }

    /** Segundos que faltan para que la clave pueda volver a intentar. */
    public long retryAfterSeconds(String key, Instant now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return 0;
        }
        long remaining = window.minus(Duration.between(bucket.start(), now)).toSeconds();
        return Math.max(1, remaining);
    }

    private record Bucket(Instant start, int count) {
        Bucket increment() {
            return new Bucket(start, count + 1);
        }

        boolean isExpired(Instant now, Duration window) {
            return !now.isBefore(start.plus(window));
        }
    }
}
