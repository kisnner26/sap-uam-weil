package ni.edu.uam.campusfood.admin;

import ni.edu.uam.campusfood.audit.AuditService;
import ni.edu.uam.campusfood.notification.NotificationOutbox;
import ni.edu.uam.campusfood.notification.NotificationOutboxRepository;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Estado de los correos (solo ADMIN). El texto solo se muestra mientras sea simulado (desarrollo). */
@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationController {
    private final NotificationOutboxRepository outbox;
    private final AuditService audit;

    public AdminNotificationController(NotificationOutboxRepository outbox, AuditService audit) {
        this.outbox = outbox;
        this.audit = audit;
    }

    @GetMapping
    @Transactional
    public List<NotificationView> recent(Authentication authentication) {
        audit.record(Long.valueOf(authentication.getName()), "VIEW_NOTIFICATIONS", "OUTBOX", null, null);
        return outbox.findTop100ByOrderByCreatedAtDesc().stream().map(NotificationView::from).toList();
    }

    public record NotificationView(Long id, String type, String toEmail, String subject, String status,
                                   int attempts, String lastError, Instant createdAt, Instant sentAt, String body) {
        static NotificationView from(NotificationOutbox m) {
            boolean showBody = m.getStatus() == NotificationOutbox.Status.SIMULATED;
            return new NotificationView(m.getId(), m.getType(), m.getToEmail(), m.getSubject(), m.getStatus().name(),
                    m.getAttempts(), m.getLastError(), m.getCreatedAt(), m.getSentAt(), showBody ? m.getBody() : null);
        }
    }
}
