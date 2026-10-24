package ni.edu.uam.campusfood.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "login_throttles")
public class LoginThrottle {
    @Id @Column(name = "throttle_key", length = 200)
    private String key;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;
    @Column(name = "blocked_until")
    private Instant blockedUntil;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version private long version;

    protected LoginThrottle() {}
    LoginThrottle(String key, Instant now) {
        this.key = key;
        this.windowStartedAt = now;
        this.updatedAt = now;
    }
    public int getAttemptCount() { return attemptCount; }
    public Instant getWindowStartedAt() { return windowStartedAt; }
    public Instant getBlockedUntil() { return blockedUntil; }
    public void reset(Instant now) { attemptCount = 0; windowStartedAt = now; blockedUntil = null; updatedAt = now; }
    public int increment(Instant now) { attemptCount += 1; updatedAt = now; return attemptCount; }
    public void blockUntil(Instant value, Instant now) { blockedUntil = value; updatedAt = now; }
}
