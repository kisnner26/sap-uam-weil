package ni.edu.uam.campusfood.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** Prueba de que una persona acepto una version concreta del aviso de privacidad (con la huella de su texto). */
@Entity
@Table(name = "consent_records")
public class ConsentRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "policy_version", nullable = false)
    private int policyVersion;

    @Column(name = "notice_sha256", nullable = false, length = 64)
    private String noticeSha256;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    protected ConsentRecord() {
    }

    public ConsentRecord(Long userId, int policyVersion, String noticeSha256) {
        this.userId = userId;
        this.policyVersion = policyVersion;
        this.noticeSha256 = noticeSha256;
    }

    @PrePersist
    void onCreate() {
        acceptedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public int getPolicyVersion() { return policyVersion; }
    public String getNoticeSha256() { return noticeSha256; }
    public Instant getAcceptedAt() { return acceptedAt; }
}
