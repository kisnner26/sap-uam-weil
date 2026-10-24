package ni.edu.uam.campusfood.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {
    List<NotificationOutbox> findByStatusOrderByCreatedAtAsc(NotificationOutbox.Status status, Pageable page);

    List<NotificationOutbox> findTop100ByOrderByCreatedAtDesc();

    long deleteByCreatedAtBefore(Instant cutoff);

    List<NotificationOutbox> findByToEmailIgnoreCase(String toEmail);
}
