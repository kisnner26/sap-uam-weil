package ni.edu.uam.campusfood.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/** Limite compartido en BD: fijo por IP y progresivo por cuenta. */
@Service
public class DatabaseLoginThrottleService {
    private final LoginThrottleRepository repository;
    private final int ipMax;
    private final Duration ipWindow;

    public DatabaseLoginThrottleService(LoginThrottleRepository repository,
            @Value("${app.security.auth-rate-limit.max-requests:20}") int ipMax,
            @Value("${app.security.auth-rate-limit.window-seconds:300}") long windowSeconds) {
        this.repository = repository;
        this.ipMax = ipMax;
        this.ipWindow = Duration.ofSeconds(windowSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeIp(String address) {
        Instant now = Instant.now();
        LoginThrottle row = locked("ip:" + address, now);
        if (!now.isBefore(row.getWindowStartedAt().plus(ipWindow))) row.reset(now);
        if (row.getAttemptCount() >= ipMax) throw blocked(row.getWindowStartedAt().plus(ipWindow), now);
        row.increment(now);
        repository.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkAccount(String identity) {
        Instant now = Instant.now();
        repository.findForUpdate("account:" + normalize(identity)).ifPresent(row -> {
            if (row.getBlockedUntil() != null && now.isBefore(row.getBlockedUntil())) {
                throw blocked(row.getBlockedUntil(), now);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String identity) {
        Instant now = Instant.now();
        LoginThrottle row = locked("account:" + normalize(identity), now);
        if (!now.isBefore(row.getWindowStartedAt().plus(Duration.ofHours(24)))) row.reset(now);
        int failures = row.increment(now);
        Duration delay = failures >= 10 ? Duration.ofMinutes(15)
                : failures >= 7 ? Duration.ofMinutes(2)
                : failures >= 5 ? Duration.ofSeconds(30) : Duration.ZERO;
        if (!delay.isZero()) row.blockUntil(now.plus(delay), now);
        repository.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String identity) {
        repository.deleteById("account:" + normalize(identity));
    }

    private LoginThrottle locked(String key, Instant now) {
        return repository.findForUpdate(key).orElseGet(() -> repository.saveAndFlush(new LoginThrottle(key, now)));
    }

    private RateLimitException blocked(Instant until, Instant now) {
        return new RateLimitException(Duration.between(now, until).toSeconds());
    }

    private String normalize(String identity) {
        return identity.trim().toLowerCase();
    }
}
