package ni.edu.uam.campusfood.audit;

import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditService {
    private final AuditEventRepository events;
    private final UserRepository users;

    public AuditService(AuditEventRepository events, UserRepository users) {
        this.events = events;
        this.users = users;
    }

    @Transactional
    public void record(Long actorId, String action, String targetType, Object targetId, String detail) {
        String actorName = users.findById(actorId).map(user -> user.getFullName()).orElse("usuario eliminado");
        events.save(new AuditEvent(actorId, actorName, action, targetType,
                targetId == null ? null : String.valueOf(targetId), clean(detail)));
    }

    @Transactional(readOnly = true)
    public List<AuditView> recent() {
        return events.findTop200ByOrderByOccurredAtDesc().stream().map(AuditView::from).toList();
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }

    public record AuditView(Long id, Long actorId, String actorName, String action,
                            String targetType, String targetId, String detail,
                            java.time.Instant occurredAt) {
        static AuditView from(AuditEvent event) {
            return new AuditView(event.getId(), event.getActorId(), event.getActorName(), event.getAction(),
                    event.getTargetType(), event.getTargetId(), event.getDetail(), event.getOccurredAt());
        }
    }
}
