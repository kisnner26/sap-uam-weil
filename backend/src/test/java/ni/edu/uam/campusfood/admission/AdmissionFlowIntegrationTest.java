package ni.edu.uam.campusfood.admission;

import ni.edu.uam.campusfood.AdmissionFixtures;
import ni.edu.uam.campusfood.assessment.AssessmentAttemptRepository;
import ni.edu.uam.campusfood.assessment.AssessmentService;
import ni.edu.uam.campusfood.assessment.AttemptStatus;
import ni.edu.uam.campusfood.audit.AuditEventRepository;
import ni.edu.uam.campusfood.notification.NotificationOutbox;
import ni.edu.uam.campusfood.notification.NotificationOutboxRepository;
import ni.edu.uam.campusfood.notification.OutboxSender;
import ni.edu.uam.campusfood.privacy.PrivacyService;
import ni.edu.uam.campusfood.security.RateLimitException;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reglas del flujo de admision: quien puede rendir, cuando, cuantas veces, y que pasa si se anula. */
@SpringBootTest
class AdmissionFlowIntegrationTest {
    @Autowired AdmissionService admission;
    @Autowired AssessmentService assessment;
    @Autowired AssessmentAttemptRepository attempts;
    @Autowired ExamSessionRepository sessions;
    @Autowired ExamEnrollmentRepository enrollments;
    @Autowired NotificationOutboxRepository outbox;
    @Autowired OutboxSender sender;
    @Autowired PrivacyService privacy;
    @Autowired AuditEventRepository audit;
    @Autowired UserRepository users;
    @Autowired AdmissionFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    private User staff;
    private User applicant;

    @BeforeEach
    void prepare() {
        audit.deleteAll();
        fixtures.clean();
        staff = users.save(User.staff("Admision UAM", "admision@uam.edu.ni", "hash", ni.edu.uam.campusfood.user.Role.STAFF));
        applicant = users.save(User.manual("Ana López", "ana@example.com", "hash", "001"));
    }

    private ExamSession session(Duration opensIn, Duration closesIn) {
        Instant now = Instant.now();
        return admission.createSessionForTest(staff.getId(), "Convocatoria octubre", now.plus(opensIn), now.plus(closesIn));
    }

    private static String codeFrom(NotificationOutbox mail) {
        Matcher m = Pattern.compile("([A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4})").matcher(mail.getBody());
        assertThat(m.find()).as("el correo trae un codigo de acceso").isTrue();
        return m.group(1);
    }

    @Test
    void sinHabilitacionNoSePuedeAbrirUnIntento() {
        privacy.accept(applicant.getId(), privacy.currentVersion());

        assertThatThrownBy(() -> assessment.open(applicant.getId(), "weil-form-a", 1, null))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getCode()).isEqualTo("NO_ENROLLMENT");
    }

    @Test
    void sinAceptarElAvisoDePrivacidadNoSePuedeComenzar() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        var enrollment = enrollments.save(new ExamEnrollment(open, "ana@example.com", "Ana", 0, null, "UN", null, "h1", staff.getId()));
        enrollment.claimBy(applicant);
        enrollments.save(enrollment);

        assertThatThrownBy(() -> assessment.open(applicant.getId(), "weil-form-a", 1, null))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getCode()).isEqualTo("CONSENT_REQUIRED");
        assertThat(admission.availability(applicant.getId()).needsConsent()).isTrue();
    }

    @Test
    void laConvocatoriaSoloSeAbreDentroDeSuVentana() {
        for (Object[] caso : new Object[][] {
                {Duration.ofHours(3), Duration.ofHours(6), "UPCOMING"},
                {Duration.ofHours(-6), Duration.ofHours(-3), "CLOSED"}}) {
            fixtures.clean();
            staff = users.save(User.staff("Admision UAM", "admision@uam.edu.ni", "hash", ni.edu.uam.campusfood.user.Role.STAFF));
            User user = users.save(User.manual("Ana López", "ana@example.com", "hash", "001"));
            ExamSession window = session((Duration) caso[0], (Duration) caso[1]);
            fixtures.enable(user, window, 0, "UN", null);

            assertThat(admission.availability(user.getId()).state()).isEqualTo(caso[2]);
            assertThatThrownBy(() -> assessment.open(user.getId(), "weil-form-a", 1, null))
                    .isInstanceOf(AdmissionException.class)
                    .extracting(e -> ((AdmissionException) e).getCode()).isEqualTo(caso[2]);
        }
    }

    @Test
    void elPersonalHabilitaYElAspiranteCanjeaSuCodigoUnaSolaVez() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        var result = admission.enroll(staff.getId(), open.getId(), List.of(
                new AdmissionService.ApplicantInput("ANA@example.com ", "Ana López", 15, "Adecuación visual", "UN", null)));
        assertThat(result.created()).hasSize(1);
        NotificationOutbox invitation = outbox.findTop100ByOrderByCreatedAtDesc().get(0);
        assertThat(invitation.getToEmail()).isEqualTo("ana@example.com");
        assertThat(invitation.getBody()).contains("35 minutos").contains("15 minutos adicionales");
        String code = codeFrom(invitation);

        // otra persona no puede usar el codigo de Ana una vez que Ana lo canjeo
        privacy.accept(applicant.getId(), privacy.currentVersion());
        assertThat(admission.claim(applicant.getId(), code.toLowerCase()).state()).isEqualTo("OPEN");
        User intruder = users.save(User.manual("Otro Usuario", "otro@example.com", "hash", "009"));
        assertThatThrownBy(() -> admission.claim(intruder.getId(), code))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getCode()).isEqualTo("CODE_INVALID");

        var attempt = assessment.open(applicant.getId(), "weil-form-a", 1, null);
        assertThat(attempt.remainingSeconds()).isBetween(34L * 60 + 50, 35L * 60);
    }

    @Test
    void elCodigoGuardadoEsUnHashYNoSePuedeAdivinarEnRafaga() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        admission.enroll(staff.getId(), open.getId(), List.of(new AdmissionService.ApplicantInput("ana@example.com", null, null, null, null, null)));
        String code = codeFrom(outbox.findTop100ByOrderByCreatedAtDesc().get(0));
        String stored = jdbc.queryForObject("select access_code_hash from exam_enrollments", String.class);
        assertThat(stored).hasSize(64).doesNotContain(code.replace("-", ""));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> admission.claim(applicant.getId(), "AAAA-BBBB-CCCC")).isInstanceOf(AdmissionException.class);
        }
        assertThatThrownBy(() -> admission.claim(applicant.getId(), code)).isInstanceOf(RateLimitException.class);
    }

    @Test
    void unaHabilitacionPermiteUnSoloIntento() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        var enrollment = fixtures.enable(applicant, open, 0, "UN", 20);
        var opened = assessment.open(applicant.getId(), "weil-form-a", 1, null);
        assessment.submit(applicant.getId(), opened.id(), opened.leaseToken(), opened.version(), Collections.nCopies(60, 2), null, null);

        assertThat(admission.availability(applicant.getId()).state()).isEqualTo("SUBMITTED");
        assertThatThrownBy(() -> assessment.open(applicant.getId(), "weil-form-a", 1, null))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getCode()).isEqualTo("ALREADY_SUBMITTED");
        assertThatThrownBy(() -> admission.revoke(staff.getId(), enrollment.getId()))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getCode()).isEqualTo("ALREADY_USED");
    }

    @Test
    void elGrupoDeBaremoLoFijaLaHabilitacionNoElNavegador() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        fixtures.enable(applicant, open, 0, "7-16", 12);
        var opened = assessment.open(applicant.getId(), "weil-form-a", 1, null);

        assessment.submit(applicant.getId(), opened.id(), opened.leaseToken(), opened.version(),
                Collections.nCopies(60, 2), 30, "AD"); // el navegador intenta decir otra cosa

        var row = assessment.listSubmitted("weil-form-a").get(0);
        assertThat(row.normGroup()).isEqualTo("7-16");
        assertThat(row.age()).isEqualTo(12);
        assertThat(row.normColumn()).isEqualTo("12");
    }

    @Test
    void unIntentoVencidoSeCierraSoloYSeCalificaLoRespondido() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        fixtures.enable(applicant, open, 0, "UN", 20);
        var opened = assessment.open(applicant.getId(), "weil-form-a", 1, null);
        int[] key = {2, 8, 7, 4, 5, 6, 8, 4, 4, 7, 3, 2};
        for (int i = 0; i < 6; i++) {
            var current = attempts.findById(opened.id()).orElseThrow();
            assessment.answer(applicant.getId(), opened.id(), i, key[i], opened.leaseToken(), current.getVersion());
        }
        jdbc.update("update assessment_attempts set deadline_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)), opened.id());

        assertThat(assessment.closeExpired()).isEqualTo(1);

        var attempt = attempts.findById(opened.id()).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(attempt.isAutoSubmitted()).isTrue();
        assertThat(attempt.getDirectScore()).isEqualTo(6);
        assertThat(outbox.findTop100ByOrderByCreatedAtDesc()).anyMatch(m -> m.getType().equals("RECEIPT"));
        assertThat(assessment.closeExpired()).isZero();
    }

    @Test
    void anularExigeMotivoAvisaNeutralmenteYPermiteReaplicar() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        fixtures.enable(applicant, open, 0, "UN", 20);
        var opened = assessment.open(applicant.getId(), "weil-form-a", 1, null);
        assessment.submit(applicant.getId(), opened.id(), opened.leaseToken(), opened.version(), Collections.nCopies(60, 2), null, null);

        assertThatThrownBy(() -> admission.invalidate(staff.getId(), opened.id(), "corto"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admission.reapply(staff.getId(), opened.id(), open.getId(), null))
                .isInstanceOf(AdmissionException.class)
                .extracting(e -> ((AdmissionException) e).getCode()).isEqualTo("NOT_INVALIDATED");

        String reason = "Se detectó otra persona rindiendo la prueba en el mismo equipo";
        admission.invalidate(staff.getId(), opened.id(), reason);

        assertThat(admission.availability(applicant.getId()).state()).isEqualTo("INVALIDATED");
        NotificationOutbox notice = outbox.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(m -> m.getType().equals("INVALIDATED")).findFirst().orElseThrow();
        assertThat(notice.getBody()).doesNotContain("otra persona").doesNotContain("equipo");

        var reapplied = admission.reapply(staff.getId(), opened.id(), open.getId(), 10);
        assertThat(reapplied.progress()).isEqualTo("CLAIMED");
        assertThat(reapplied.reappliesAttemptId()).isEqualTo(opened.id());
        assertThat(admission.availability(applicant.getId()).state()).isEqualTo("OPEN");
        var second = assessment.open(applicant.getId(), "weil-form-a", 1, null);
        assertThat(second.id()).isNotEqualTo(opened.id());
        assertThat(audit.findAll()).extracting(e -> e.getAction()).contains("INVALIDATE_ATTEMPT", "REAPPLY");
    }

    @Test
    void ningunCorreoLlevaPuntajesNiPercentilNiCi() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        fixtures.enable(applicant, open, 0, "UN", 20);
        var opened = assessment.open(applicant.getId(), "weil-form-a", 1, null);
        assessment.submit(applicant.getId(), opened.id(), opened.leaseToken(), opened.version(), Collections.nCopies(60, 2), null, null);
        admission.enroll(staff.getId(), open.getId(), List.of(new AdmissionService.ApplicantInput("nuevo@example.com", "Nuevo", null, null, null, null)));
        admission.invalidate(staff.getId(), opened.id(), "Motivo administrativo de prueba suficiente");
        admission.sendReminders(Instant.now().minus(Duration.ofDays(1)), Duration.ofDays(2));

        List<NotificationOutbox> all = outbox.findAll();
        assertThat(all).extracting(NotificationOutbox::getType).contains("INVITATION", "RECEIPT", "INVALIDATED");
        assertThat(all).allSatisfy(mail -> assertThat(mail.getBody().toLowerCase())
                .doesNotContain("percentil").doesNotContain("puntaje").doesNotContain("coeficiente").doesNotContain(" ci "));
    }

    @Test
    void elRecordatorioSaleUnaSolaVezParaConvocatoriasProximas() {
        ExamSession soon = session(Duration.ofHours(10), Duration.ofHours(14));
        admission.enroll(staff.getId(), soon.getId(), List.of(new AdmissionService.ApplicantInput("ana@example.com", "Ana", null, null, null, null)));

        assertThat(admission.sendReminders(Instant.now(), Duration.ofHours(24))).isEqualTo(1);
        assertThat(admission.sendReminders(Instant.now(), Duration.ofHours(24))).isZero();
        assertThat(outbox.findAll()).filteredOn(m -> m.getType().equals("REMINDER")).hasSize(1);
    }

    @Test
    void laBandejaEnviaYLosCorreosSimuladosQuedanMarcados() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        admission.enroll(staff.getId(), open.getId(), List.of(new AdmissionService.ApplicantInput("ana@example.com", "Ana", null, null, null, null)));

        assertThat(sender.sendPending()).isEqualTo(1);

        assertThat(outbox.findAll()).allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(NotificationOutbox.Status.SIMULATED));
    }

    @Test
    void noSeDuplicaUnaHabilitacionNiSeAceptanCorreosInvalidos() {
        ExamSession open = session(Duration.ofHours(-1), Duration.ofHours(2));
        var first = admission.enroll(staff.getId(), open.getId(), List.of(
                new AdmissionService.ApplicantInput("ana@example.com", "Ana", null, null, null, null),
                new AdmissionService.ApplicantInput("ANA@example.com", "Ana", null, null, null, null),
                new AdmissionService.ApplicantInput("sin-arroba", "X", null, null, null, null),
                new AdmissionService.ApplicantInput("tiempo@example.com", "T", 500, null, null, null)));

        assertThat(first.created()).hasSize(1);
        assertThat(first.skipped()).hasSize(3);
        assertThat(admission.enroll(staff.getId(), open.getId(), List.of(
                new AdmissionService.ApplicantInput("ana@example.com", "Ana", null, null, null, null))).created()).isEmpty();
    }
}
