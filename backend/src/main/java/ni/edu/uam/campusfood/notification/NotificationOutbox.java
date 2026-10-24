package ni.edu.uam.campusfood.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Bandeja de salida: los correos se guardan primero y se envian despues, de modo que una caida del
 * servidor de correo no pierde mensajes ni bloquea a nadie. Ningun mensaje lleva puntajes.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutbox {
    public enum Status { PENDING, SENT, SIMULATED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(name = "to_email", nullable = false, length = 160)
    private String toEmail;

    @Column(nullable = false, length = 200)
    private String subject;

    /** Se borra despues de enviar: puede contener un codigo de acceso. */
    @Column(nullable = false, length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationOutbox() {
    }

    public NotificationOutbox(String type, String toEmail, String subject, String body) {
        this.type = type;
        this.toEmail = toEmail;
        this.subject = subject;
        this.body = body;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markSent(boolean simulated) {
        status = simulated ? Status.SIMULATED : Status.SENT;
        sentAt = Instant.now();
        lastError = null;
        if (!simulated) body = "[contenido eliminado tras el envio]";
    }

    public void markFailure(String error, int maxAttempts) {
        attempts += 1;
        lastError = error == null ? null : (error.length() > 300 ? error.substring(0, 300) : error);
        if (attempts >= maxAttempts) status = Status.FAILED;
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public String getToEmail() { return toEmail; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
