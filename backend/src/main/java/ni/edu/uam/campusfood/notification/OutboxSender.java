package ni.edu.uam.campusfood.notification;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Envia los correos pendientes. Con app.mail.mode=smtp usa el servidor configurado; con
 * app.mail.mode=log (solo desarrollo) los marca como simulados y deja el texto visible para el
 * administrador. En produccion el modo log no esta permitido (lo valida el arranque).
 */
@Component
public class OutboxSender {
    private static final Logger log = LoggerFactory.getLogger(OutboxSender.class);

    private final NotificationOutboxRepository outbox;
    private final ObjectProvider<JavaMailSender> mail;
    private final String mode;
    private final String from;
    private final int maxAttempts;

    public OutboxSender(NotificationOutboxRepository outbox, ObjectProvider<JavaMailSender> mail,
                        @Value("${app.mail.mode:log}") String mode,
                        @Value("${app.mail.from:admision@uam.edu.ni}") String from,
                        @Value("${app.mail.max-attempts:5}") int maxAttempts) {
        this.outbox = outbox;
        this.mail = mail;
        this.mode = mode;
        this.from = from;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-ms:30000}", initialDelayString = "${app.mail.poll-ms:30000}")
    @Transactional
    public int sendPending() {
        List<NotificationOutbox> pending = outbox.findByStatusOrderByCreatedAtAsc(
                NotificationOutbox.Status.PENDING, PageRequest.of(0, 25));
        int delivered = 0;
        for (NotificationOutbox message : pending) {
            try {
                if ("smtp".equalsIgnoreCase(mode)) {
                    deliver(message);
                    message.markSent(false);
                } else {
                    log.info("Correo simulado ({}) para {}: {}", message.getType(), message.getToEmail(), message.getSubject());
                    message.markSent(true);
                }
                delivered += 1;
            } catch (Exception failure) {
                log.warn("No se pudo enviar el correo {} a {}: {}", message.getId(), message.getToEmail(), failure.getMessage());
                message.markFailure(failure.getClass().getSimpleName() + ": " + failure.getMessage(), maxAttempts);
            }
        }
        return delivered;
    }

    private void deliver(NotificationOutbox message) throws Exception {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) throw new IllegalStateException("No hay servidor de correo configurado (spring.mail.host)");
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
        helper.setFrom(from);
        helper.setTo(message.getToEmail());
        helper.setSubject(message.getSubject());
        helper.setText(message.getBody(), false);
        sender.send(mime);
    }
}
