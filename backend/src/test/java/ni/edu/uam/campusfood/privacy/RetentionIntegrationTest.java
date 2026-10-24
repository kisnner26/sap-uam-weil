package ni.edu.uam.campusfood.privacy;

import ni.edu.uam.campusfood.AdmissionFixtures;
import ni.edu.uam.campusfood.admission.ExamEnrollment;
import ni.edu.uam.campusfood.admission.ExamEnrollmentRepository;
import ni.edu.uam.campusfood.assessment.AssessmentAttemptRepository;
import ni.edu.uam.campusfood.assessment.AssessmentService;
import ni.edu.uam.campusfood.audit.AuditEventRepository;
import ni.edu.uam.campusfood.notification.NotificationOutbox;
import ni.edu.uam.campusfood.notification.NotificationOutboxRepository;
import ni.edu.uam.campusfood.user.Role;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RetentionIntegrationTest {
    @Autowired RetentionService retention;
    @Autowired AssessmentService assessment;
    @Autowired AssessmentAttemptRepository attempts;
    @Autowired ExamEnrollmentRepository enrollments;
    @Autowired NotificationOutboxRepository outbox;
    @Autowired AuditEventRepository audit;
    @Autowired UserRepository users;
    @Autowired AdmissionFixtures fixtures;

    private User admin;
    private User ana;
    private Long attemptId;

    @BeforeEach
    void prepare() {
        audit.deleteAll();
        fixtures.clean();
        admin = users.save(User.staff("Administradora", "admin@uam.edu.ni", "hash", Role.ADMIN));
        ana = users.save(User.manual("Ana López", "ana@example.com", "hash", "001-010101-0001A"));
        fixtures.enable(ana, fixtures.openSession(admin.getId()), 0, "UN", 19);
        var opened = assessment.open(ana.getId(), "weil-form-a", 1, null);
        attemptId = opened.id();
        assessment.submit(ana.getId(), opened.id(), opened.leaseToken(), opened.version(), Collections.nCopies(60, 2), null, null);
        outbox.save(new NotificationOutbox("INVITATION", "ana@example.com", "Invitación", "texto"));
    }

    @Test
    void anonimizarBorraLoQueIdentificaYConservaLasEstadisticas() {
        retention.anonymize(admin.getId(), ana.getId(), "Solicitud de la titular recibida por correo");

        User after = users.findById(ana.getId()).orElseThrow();
        assertThat(after.getFullName()).startsWith("Persona anonimizada");
        assertThat(after.getEmail()).endsWith("@anonimizado.invalid");
        assertThat(after.getCedula()).isNull();
        assertThat(after.isActive()).isFalse();
        assertThat(after.isAnonymized()).isTrue();
        assertThat(users.findByEmailIgnoreCase("ana@example.com")).isEmpty();
        // los puntajes se conservan, sin nombre
        var attempt = attempts.findById(attemptId).orElseThrow();
        assertThat(attempt.getDirectScore()).isEqualTo(10);
        // los correos con su direccion se eliminan y la habilitacion queda borrada
        assertThat(outbox.findByToEmailIgnoreCase("ana@example.com")).isEmpty();
        assertThat(enrollments.findByUserId(ana.getId())).allSatisfy(e -> {
            assertThat(e.getEmail()).endsWith("@anonimizado.invalid");
            assertThat(e.getFullName()).isNull();
        });
        assertThat(audit.findAll()).extracting(e -> e.getAction()).contains("ANONYMIZE_USER");
    }

    @Test
    void exigeMotivoYSoloSeAnonimizanAspirantes() {
        assertThatThrownBy(() -> retention.anonymize(admin.getId(), ana.getId(), "corto"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retention.anonymize(admin.getId(), admin.getId(), "Intento de borrar una cuenta de administración"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unaCuentaMoodleAnonimizadaNoDejaSuIdentificadorDeMoodle() {
        User moodle = users.save(User.fromMoodle(4521, "Luis Pérez", "luis@uam.edu.ni", null, "Medicina"));

        retention.anonymize(admin.getId(), moodle.getId(), "Solicitud de eliminación de datos del titular");

        User after = users.findById(moodle.getId()).orElseThrow();
        assertThat(after.getMoodleId()).isNotEqualTo(4521).isNegative();
        assertThat(users.findByMoodleId(4521)).isEmpty();
    }

    @Test
    void elTitularRecibeSusDatosYQuedaAuditado() {
        var export = retention.export(admin.getId(), ana.getId());

        assertThat(export.profile()).containsEntry("nombre", "Ana López").containsEntry("correo", "ana@example.com");
        assertThat(export.attempts()).hasSize(1);
        assertThat(export.attempts().get(0).toString()).contains("puntajeDirecto=10");
        assertThat(export.consents()).hasSize(1);
        assertThat(export.emails()).hasSize(2); // la invitacion y la constancia de entrega
        assertThat(audit.findAll()).extracting(e -> e.getAction()).contains("EXPORT_SUBJECT");
    }

    @Test
    void elBarridoSoloAnonimizaAQuienSuperoElPlazoYNuncaAlPersonal() {
        ExamEnrollment stale = enrollments.save(new ExamEnrollment(fixtures.openSession(admin.getId()), "nuncallego@example.com",
                "No Llegó", 0, null, "UN", null, "hash-x", admin.getId()));

        assertThat(retention.sweep(Instant.now())).isZero();
        assertThat(users.findById(ana.getId()).orElseThrow().isAnonymized()).isFalse();

        Instant inThreeYears = Instant.now().plus(Duration.ofDays(3 * 365));
        assertThat(retention.sweep(inThreeYears)).isEqualTo(1);

        assertThat(users.findById(ana.getId()).orElseThrow().isAnonymized()).isTrue();
        assertThat(users.findById(admin.getId()).orElseThrow().isAnonymized()).isFalse();
        assertThat(enrollments.findById(stale.getId()).orElseThrow().getEmail()).endsWith("@anonimizado.invalid");
        assertThat(audit.findAll()).extracting(e -> e.getActorName()).contains("Sistema");
        assertThat(retention.sweep(inThreeYears)).isZero();
    }
}
