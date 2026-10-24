package ni.edu.uam.campusfood.admission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import ni.edu.uam.campusfood.user.User;

import java.time.Instant;

/**
 * Habilitacion de un aspirante para una convocatoria. El correo sirve para invitarlo; lo que lo
 * autentica es el codigo de acceso (de un solo uso, guardado solo como hash), que prueba que es
 * dueno de ese correo. Una habilitacion permite un unico intento.
 */
@Entity
@Table(name = "exam_enrollments")
public class ExamEnrollment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ExamSession session;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(name = "full_name", length = 160)
    private String fullName;

    /** Minutos adicionales por adecuacion (discapacidad, necesidad especial). */
    @Column(name = "extra_minutes", nullable = false)
    private int extraMinutes;

    @Column(length = 300)
    private String note;

    /** Grupo de baremo con el que se interpreta el resultado; lo fija el personal, no el aspirante. */
    @Column(name = "norm_group", nullable = false, length = 8)
    private String normGroup = "UN";

    @Column(name = "participant_age")
    private Integer participantAge;

    @Column(name = "access_code_hash", nullable = false, length = 64)
    private String accessCodeHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EnrollmentStatus status = EnrollmentStatus.ENABLED;

    /** Intento anulado que esta habilitacion permite repetir (reaplicacion). */
    @Column(name = "reapplies_attempt_id")
    private Long reappliesAttemptId;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExamEnrollment() {
    }

    public ExamEnrollment(ExamSession session, String email, String fullName, int extraMinutes, String note,
                          String normGroup, Integer participantAge, String accessCodeHash, Long createdBy) {
        this.session = session;
        this.email = email;
        this.fullName = fullName;
        this.extraMinutes = extraMinutes;
        this.note = note;
        this.normGroup = normGroup;
        this.participantAge = participantAge;
        this.accessCodeHash = accessCodeHash;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isClaimed() {
        return user != null;
    }

    public void claimBy(User claimant) {
        this.user = claimant;
        this.claimedAt = Instant.now();
    }

    public void replaceCode(String newHash) {
        this.accessCodeHash = newHash;
        this.user = null;
        this.claimedAt = null;
        this.reminderSentAt = null;
    }

    public void revoke() {
        this.status = EnrollmentStatus.REVOKED;
    }

    public void adjust(int extraMinutes, String note, String normGroup, Integer participantAge) {
        this.extraMinutes = extraMinutes;
        this.note = note;
        this.normGroup = normGroup;
        this.participantAge = participantAge;
    }

    public void markReapplication(Long attemptId) {
        this.reappliesAttemptId = attemptId;
    }

    public void markReminderSent() {
        this.reminderSentAt = Instant.now();
    }

    public Long getId() { return id; }
    public ExamSession getSession() { return session; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public int getExtraMinutes() { return extraMinutes; }
    public String getNote() { return note; }
    public String getNormGroup() { return normGroup; }
    public Integer getParticipantAge() { return participantAge; }
    public String getAccessCodeHash() { return accessCodeHash; }
    public User getUser() { return user; }
    public Instant getClaimedAt() { return claimedAt; }
    public EnrollmentStatus getStatus() { return status; }
    public Long getReappliesAttemptId() { return reappliesAttemptId; }
    public Instant getReminderSentAt() { return reminderSentAt; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
