package ni.edu.uam.campusfood.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "assessment_events")
public class AssessmentEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.ManyToOne(optional = false, fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "attempt_id", nullable = false)
    private AssessmentAttempt attempt;

    @Column(nullable = false, length = 48)
    private String type;

    @Column(length = 240)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AssessmentEvent() {}

    public AssessmentEvent(AssessmentAttempt attempt, String type, String detail) {
        this.attempt = attempt;
        this.type = type;
        this.detail = detail;
    }

    @PrePersist
    void onCreate() { occurredAt = Instant.now(); }

    public Long ownerAttemptId() { return attempt.getId(); }
    public String getType() { return type; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
