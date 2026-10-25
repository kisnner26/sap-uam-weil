package ni.edu.uam.campusfood.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import ni.edu.uam.campusfood.admission.ExamEnrollment;
import ni.edu.uam.campusfood.user.User;

import java.time.Instant;

@Entity
@Table(name = "assessment_attempts")
public class AssessmentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.ManyToOne(optional = false, fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "test_id", nullable = false, length = 80)
    private String testId;

    @Column(name = "test_version", nullable = false)
    private int testVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AttemptStatus status;

    @Column(name = "lease_token", nullable = false, length = 64)
    private String leaseToken;

    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "deadline_at", nullable = false, updatable = false)
    private Instant deadlineAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "direct_score")
    private Integer directScore;

    /** Edad y grupo de baremo del aspirante: hacen falta para convertir el puntaje en percentil y CI. */
    @Column(name = "participant_age")
    private Integer participantAge;

    @Column(name = "norm_group", length = 8)
    private String normGroup;

    /** Habilitacion con la que se abrio el intento (convocatoria, minutos extra, grupo de baremo). */
    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "enrollment_id")
    private ExamEnrollment enrollment;

    /** El intento se cerro solo porque vencio el tiempo, sin que el aspirante lo entregara. */
    @Column(name = "auto_submitted", nullable = false)
    private boolean autoSubmitted;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "invalidated_by")
    private Long invalidatedBy;

    @Column(name = "invalid_reason", length = 500)
    private String invalidReason;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Version
    @Column(nullable = false)
    private long version;

    protected AssessmentAttempt() {
    }

    public AssessmentAttempt(User user, String testId, int testVersion, String leaseToken,
                             Instant leaseExpiresAt, Instant deadlineAt) {
        this.user = user;
        this.testId = testId;
        this.testVersion = testVersion;
        this.leaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt;
        this.deadlineAt = deadlineAt;
        this.status = AttemptStatus.IN_PROGRESS;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        startedAt = now;
        updatedAt = now;
    }

    public boolean leaseBelongsTo(String token) {
        return token != null && token.equals(leaseToken);
    }

    public boolean leaseIsActive(Instant now) {
        return leaseExpiresAt.isAfter(now);
    }

    public boolean isWithinTime(Instant now) {
        return now.isBefore(deadlineAt);
    }

    public void renewLease(String token, Instant expiresAt) {
        leaseToken = token;
        leaseExpiresAt = expiresAt;
        updatedAt = Instant.now();
    }

    /** Libera una exclusividad efimera sin alterar respuestas ni el estado del intento. */
    public void releaseLease() {
        leaseExpiresAt = Instant.EPOCH;
        updatedAt = Instant.now();
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public void attachEnrollment(ExamEnrollment enrollment) {
        this.enrollment = enrollment;
    }

    /** Cierre por vencimiento del tiempo: se califica lo que alcanzo a responder. */
    public void autoClose(int score, Integer age, String group) {
        submit(score, age, group);
        this.autoSubmitted = true;
        this.submittedAt = deadlineAt;
    }

    public void invalidate(Long actorId, String reason) {
        this.invalidatedAt = Instant.now();
        this.invalidatedBy = actorId;
        this.invalidReason = reason;
    }

    public void markReviewed(Long actorId) {
        this.reviewedAt = Instant.now();
        this.reviewedBy = actorId;
    }

    public boolean isInvalidated() {
        return invalidatedAt != null;
    }

    public void submit(int score, Integer age, String group) {
        status = AttemptStatus.SUBMITTED;
        directScore = score;
        participantAge = age;
        normGroup = group;
        submittedAt = Instant.now();
        leaseExpiresAt = submittedAt;
        updatedAt = submittedAt;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getTestId() { return testId; }
    public int getTestVersion() { return testVersion; }
    public AttemptStatus getStatus() { return status; }
    public String getLeaseToken() { return leaseToken; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Integer getDirectScore() { return directScore; }
    public ExamEnrollment getEnrollment() { return enrollment; }
    public boolean isAutoSubmitted() { return autoSubmitted; }
    public Instant getInvalidatedAt() { return invalidatedAt; }
    public Long getInvalidatedBy() { return invalidatedBy; }
    public String getInvalidReason() { return invalidReason; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Long getReviewedBy() { return reviewedBy; }
    public Integer getParticipantAge() { return participantAge; }
    public String getNormGroup() { return normGroup; }
    public long getVersion() { return version; }
}
