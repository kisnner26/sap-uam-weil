package ni.edu.uam.campusfood.admission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** Convocatoria: una ventana de tiempo en la que los aspirantes habilitados pueden rendir el test. */
@Entity
@Table(name = "exam_sessions")
public class ExamSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "test_id", nullable = false, length = 80)
    private String testId;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    /** Minutos de la prueba; si es nulo se usa el valor por defecto del test. */
    @Column(name = "time_limit_minutes")
    private Integer timeLimitMinutes;

    @Column(length = 500)
    private String instructions;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExamSession() {
    }

    public ExamSession(String name, String testId, Instant opensAt, Instant closesAt,
                       Integer timeLimitMinutes, String instructions, Long createdBy) {
        this.name = name;
        this.testId = testId;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.timeLimitMinutes = timeLimitMinutes;
        this.instructions = instructions;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void update(String name, Instant opensAt, Instant closesAt, Integer timeLimitMinutes, String instructions) {
        this.name = name;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.timeLimitMinutes = timeLimitMinutes;
        this.instructions = instructions;
    }

    public void cancel() {
        if (cancelledAt == null) cancelledAt = Instant.now();
    }

    public SessionState stateAt(Instant now) {
        if (cancelledAt != null) return SessionState.CANCELLED;
        if (now.isBefore(opensAt)) return SessionState.UPCOMING;
        if (now.isAfter(closesAt)) return SessionState.CLOSED;
        return SessionState.OPEN;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getTestId() { return testId; }
    public Instant getOpensAt() { return opensAt; }
    public Instant getClosesAt() { return closesAt; }
    public Integer getTimeLimitMinutes() { return timeLimitMinutes; }
    public String getInstructions() { return instructions; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
