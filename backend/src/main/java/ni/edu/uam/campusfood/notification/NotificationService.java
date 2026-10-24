package ni.edu.uam.campusfood.notification;

import ni.edu.uam.campusfood.admission.ExamEnrollment;
import ni.edu.uam.campusfood.admission.ExamSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Redacta y encola los avisos a los aspirantes. Regla de oro: ningun mensaje incluye puntaje,
 * percentil, CI ni el motivo de una anulacion.
 */
@Service
public class NotificationService {
    private static final ZoneId MANAGUA = ZoneId.of("America/Managua");
    private static final DateTimeFormatter WHEN = DateTimeFormatter
            .ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-NI")).withZone(MANAGUA);

    private final NotificationOutboxRepository outbox;
    private final String publicUrl;

    public NotificationService(NotificationOutboxRepository outbox,
                               @Value("${app.public-url:http://localhost:8765}") String publicUrl) {
        this.outbox = outbox;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    @Transactional
    public void invitation(ExamEnrollment enrollment, String accessCode, int defaultMinutes) {
        ExamSession session = enrollment.getSession();
        int minutes = (session.getTimeLimitMinutes() != null ? session.getTimeLimitMinutes() : defaultMinutes)
                + enrollment.getExtraMinutes();
        String extra = enrollment.getExtraMinutes() > 0 ? " (incluye " + enrollment.getExtraMinutes() + " minutos adicionales)" : "";
        String instructions = session.getInstructions() == null || session.getInstructions().isBlank() ? ""
                : "\nIndicaciones de Admisión: " + session.getInstructions().trim() + "\n";
        queue("INVITATION", enrollment.getEmail(), "Invitación a la evaluación de admisión: " + session.getName(), """
                %s

                La Universidad Americana (UAM) le invita a rendir la evaluación de admisión "%s".

                Apertura: %s
                Cierre: %s
                Duración: %d minutos%s

                Su código de acceso (de un solo uso, no lo comparta): %s

                Cómo ingresar:
                1. Entre a %s
                2. Inicie sesión con su cuenta UAM o cree una cuenta externa.
                3. Escriba su código de acceso y lea y acepte el aviso de privacidad.
                4. Comience la evaluación dentro de la ventana indicada. Busque un lugar tranquilo y con buena conexión.
                %s
                Si no esperaba este mensaje, puede ignorarlo.

                Admisión UAM
                """.formatted(greeting(enrollment.getFullName()), session.getName(), when(session.getOpensAt()),
                when(session.getClosesAt()), minutes, extra, formatCode(accessCode), publicUrl, instructions));
    }

    @Transactional
    public void reminder(ExamEnrollment enrollment) {
        ExamSession session = enrollment.getSession();
        queue("REMINDER", enrollment.getEmail(), "Recordatorio: su evaluación de admisión es pronto", """
                %s

                Le recordamos que la evaluación de admisión "%s" abre el %s y cierra el %s.
                Use el código de acceso que le enviamos en la invitación. Si no lo encuentra, escriba a Admisión.

                Entre a %s

                Admisión UAM
                """.formatted(greeting(enrollment.getFullName()), session.getName(), when(session.getOpensAt()),
                when(session.getClosesAt()), publicUrl));
    }

    @Transactional
    public void receipt(String email, String fullName, Long attemptId, Instant submittedAt) {
        queue("RECEIPT", email, "Constancia de entrega de su evaluación", """
                %s

                Recibimos su evaluación el %s. Comprobante N.º %06d.
                El equipo de Admisión revisará su evaluación y se comunicará con usted por los medios oficiales
                de la universidad. No necesita hacer nada más.

                Admisión UAM
                """.formatted(greeting(fullName), when(submittedAt), attemptId));
    }

    @Transactional
    public void invalidated(String email, String fullName) {
        queue("INVALIDATED", email, "Información sobre su evaluación de admisión", """
                %s

                Su aplicación de la evaluación fue anulada por motivos administrativos. Esto no es una sanción.
                Admisión se comunicará con usted para indicarle los pasos a seguir. Si tiene dudas, escriba a Admisión.

                Admisión UAM
                """.formatted(greeting(fullName)));
    }

    private void queue(String type, String to, String subject, String body) {
        outbox.save(new NotificationOutbox(type, to, subject, body));
    }

    private static String greeting(String name) {
        return name == null || name.isBlank() ? "Estimado/a aspirante:" : "Estimado/a " + name.trim() + ":";
    }

    static String when(Instant instant) {
        return WHEN.format(instant);
    }

    /** ABCDEFGHJKLM -> ABCD-EFGH-JKLM, para leerlo y copiarlo sin errores. */
    static String formatCode(String code) {
        return code.replaceAll("(.{4})(?=.)", "$1-");
    }
}
