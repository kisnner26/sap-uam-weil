package ni.edu.uam.campusfood.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** Registro de solo insercion; la aplicacion no expone operaciones de edicion o borrado. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "actor_name", nullable = false, length = 160)
    private String actorName;
    @Column(nullable = false, length = 64)
    private String action;
    @Column(name = "target_type", nullable = false, length = 48)
    private String targetType;
    @Column(name = "target_id", length = 96)
    private String targetId;
    @Column(length = 500)
    private String detail;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEvent() {}

    public AuditEvent(Long actorId, String actorName, String action, String targetType,
                      String targetId, String detail) {
        this.actorId = actorId;
        this.actorName = actorName;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
    }

    @PrePersist void onCreate() { this.occurredAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
