package ni.edu.uam.campusfood;

import ni.edu.uam.campusfood.admission.ExamEnrollment;
import ni.edu.uam.campusfood.admission.ExamEnrollmentRepository;
import ni.edu.uam.campusfood.admission.ExamSession;
import ni.edu.uam.campusfood.admission.ExamSessionRepository;
import ni.edu.uam.campusfood.assessment.AssessmentAttemptRepository;
import ni.edu.uam.campusfood.assessment.AssessmentEventRepository;
import ni.edu.uam.campusfood.assessment.AssessmentResponseRepository;
import ni.edu.uam.campusfood.notification.NotificationOutboxRepository;
import ni.edu.uam.campusfood.privacy.ConsentRecordRepository;
import ni.edu.uam.campusfood.privacy.PrivacyService;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Ayudante de pruebas: limpia la base en orden y deja a un aspirante habilitado y con consentimiento. */
@Component
public class AdmissionFixtures {
    private final AssessmentEventRepository events;
    private final AssessmentResponseRepository responses;
    private final AssessmentAttemptRepository attempts;
    private final ExamEnrollmentRepository enrollments;
    private final ExamSessionRepository sessions;
    private final ConsentRecordRepository consents;
    private final NotificationOutboxRepository outbox;
    private final UserRepository users;
    private final PrivacyService privacy;

    public AdmissionFixtures(AssessmentEventRepository events, AssessmentResponseRepository responses,
                             AssessmentAttemptRepository attempts, ExamEnrollmentRepository enrollments,
                             ExamSessionRepository sessions, ConsentRecordRepository consents,
                             NotificationOutboxRepository outbox, UserRepository users, PrivacyService privacy) {
        this.events = events;
        this.responses = responses;
        this.attempts = attempts;
        this.enrollments = enrollments;
        this.sessions = sessions;
        this.consents = consents;
        this.outbox = outbox;
        this.users = users;
        this.privacy = privacy;
    }

    public void clean() {
        events.deleteAll();
        responses.deleteAll();
        attempts.deleteAll();
        enrollments.deleteAll();
        sessions.deleteAll();
        consents.deleteAll();
        outbox.deleteAll();
        users.deleteAll();
    }

    /** Convocatoria abierta desde hace una hora y por dos horas mas. */
    public ExamSession openSession(Long actorId) {
        Instant now = Instant.now();
        return sessions.save(new ExamSession("Convocatoria de prueba", "weil-form-a",
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)), null, null, actorId));
    }

    /** Deja al usuario con consentimiento aceptado y una habilitacion ya canjeada en una convocatoria abierta. */
    public ExamEnrollment enable(User user) {
        return enable(user, openSession(user.getId()), 0, "UN", null);
    }

    public ExamEnrollment enable(User user, ExamSession session, int extraMinutes, String group, Integer age) {
        privacy.accept(user.getId(), privacy.currentVersion());
        ExamEnrollment enrollment = new ExamEnrollment(session, user.getEmail(), user.getFullName(), extraMinutes,
                null, group, age, "hash-" + System.nanoTime(), user.getId());
        enrollment.claimBy(user);
        return enrollments.save(enrollment);
    }
}
