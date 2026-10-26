package ni.edu.uam.campusfood.privacy;

import ni.edu.uam.campusfood.admission.ExamEnrollment;
import ni.edu.uam.campusfood.admission.ExamEnrollmentRepository;
import ni.edu.uam.campusfood.assessment.AssessmentAttempt;
import ni.edu.uam.campusfood.assessment.AssessmentAttemptRepository;
import ni.edu.uam.campusfood.assessment.AssessmentEventRepository;
import ni.edu.uam.campusfood.assessment.ScoringRegistry;
import ni.edu.uam.campusfood.audit.AuditService;
import ni.edu.uam.campusfood.notification.NotificationOutbox;
import ni.edu.uam.campusfood.notification.NotificationOutboxRepository;
import ni.edu.uam.campusfood.user.Role;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Derechos del titular y retencion de datos: exportar lo que se guarda de una persona, anonimizarla
 * (se borra lo que la identifica y se conservan puntajes y tiempos para estadisticas) y hacerlo solo
 * al vencer el plazo de retencion. El personal y la administracion nunca se anonimizan automaticamente.
 */
@Service
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final UserRepository users;
    private final AssessmentAttemptRepository attempts;
    private final AssessmentEventRepository events;
    private final ExamEnrollmentRepository enrollments;
    private final ConsentRecordRepository consents;
    private final NotificationOutboxRepository outbox;
    private final ScoringRegistry scoring;
    private final AuditService audit;
    private final int months;
    private final boolean enabled;

    public RetentionService(UserRepository users, AssessmentAttemptRepository attempts, AssessmentEventRepository events,
                            ExamEnrollmentRepository enrollments, ConsentRecordRepository consents,
                            NotificationOutboxRepository outbox, ScoringRegistry scoring, AuditService audit,
                            @Value("${app.retention.months:24}") int months,
                            @Value("${app.retention.enabled:false}") boolean enabled) {
        this.users = users;
        this.attempts = attempts;
        this.events = events;
        this.enrollments = enrollments;
        this.consents = consents;
        this.outbox = outbox;
        this.scoring = scoring;
        this.audit = audit;
        this.months = months;
        this.enabled = enabled;
    }

    @Transactional(readOnly = true)
    public List<SubjectSummary> search(String query) {
        if (query == null || query.strip().length() < 3) throw new IllegalArgumentException("Escribe al menos 3 caracteres.");
        return users.searchApplicants(query.strip(), org.springframework.data.domain.PageRequest.of(0, 20)).stream()
                .map(u -> new SubjectSummary(u.getId(), u.getFullName(), u.getEmail(), u.isAnonymized())).toList();
    }

    /** Todo lo que el sistema guarda de una persona, para atender su solicitud de acceso. */
    @Transactional
    public SubjectExport export(Long actorId, Long userId) {
        User user = applicant(userId);
        audit.record(actorId, "EXPORT_SUBJECT", "USER", userId, null);
        List<Object> enrollmentRows = new ArrayList<>();
        for (ExamEnrollment e : enrollments.findByUserId(userId)) {
            enrollmentRows.add(java.util.Map.of("convocatoria", e.getSession().getName(), "minutosAdicionales", e.getExtraMinutes(),
                    "nota", String.valueOf(e.getNote()), "canjeadaEn", String.valueOf(e.getClaimedAt())));
        }
        List<Object> attemptRows = new ArrayList<>();
        for (AssessmentAttempt a : attempts.findByUserId(userId)) {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("intento", a.getId());
            row.put("inicio", a.getStartedAt());
            row.put("entrega", a.getSubmittedAt());
            row.put("puntajeDirecto", a.getDirectScore());
            if (a.getDirectScore() != null) {
                var i = scoring.forTest(a.getTestId()).interpret(a.getDirectScore(), a.getParticipantAge(), a.getNormGroup());
                row.put("percentil", i.percentile());
                row.put("ci", i.ci());
            }
            row.put("anulado", a.isInvalidated());
            attemptRows.add(row);
        }
        List<Object> consentRows = consents.findByUserIdOrderByAcceptedAtDesc(userId).stream()
                .map(c -> (Object) java.util.Map.of("version", c.getPolicyVersion(), "aceptadoEn", c.getAcceptedAt().toString())).toList();
        List<Object> mailRows = user.getEmail() == null ? List.of() : outbox.findByToEmailIgnoreCase(user.getEmail()).stream()
                .map(m -> (Object) java.util.Map.of("tipo", m.getType(), "asunto", m.getSubject(), "estado", m.getStatus().name())).toList();
        return new SubjectExport(java.util.Map.of("nombre", user.getFullName(), "correo", String.valueOf(user.getEmail()),
                "cedula", String.valueOf(user.getCedula()), "carrera", String.valueOf(user.getCareer()),
                "origen", user.getAuthProvider().value(), "anonimizado", user.isAnonymized()),
                consentRows, enrollmentRows, attemptRows, mailRows, Instant.now());
    }

    /** Borra lo que identifica a la persona (a solicitud suya). Conserva puntajes y tiempos sin nombre. */
    @Transactional
    public void anonymize(Long actorId, Long userId, String reason) {
        String clean = reason == null ? "" : reason.replaceAll("\\s+", " ").strip();
        if (clean.length() < 10) throw new IllegalArgumentException("Escribe el motivo (mínimo 10 caracteres).");
        User user = applicant(userId);
        if (user.isAnonymized()) return;
        doAnonymize(user);
        audit.record(actorId, "ANONYMIZE_USER", "USER", userId, "motivo: " + clean);
    }

    /** Anonimiza a quienes superaron el plazo de retencion y limpia correos viejos. Devuelve cuantas personas. */
    @Transactional
    public int sweep(Instant now) {
        Instant cutoff = now.atZone(ZoneId.of("America/Managua")).minusMonths(months).toInstant();
        int done = 0;
        for (User user : users.findRetentionCandidates(cutoff)) {
            Instant last = lastActivity(user);
            if (last.isBefore(cutoff)) {
                doAnonymize(user);
                audit.recordSystem("ANONYMIZE_USER", "USER", user.getId(), "retencion de " + months + " meses");
                done += 1;
            }
        }
        for (ExamEnrollment stale : enrollments.findByUserIsNullAndCreatedAtBefore(cutoff)) {
            if (!stale.getEmail().endsWith("@anonimizado.invalid")) {
                stale.scrub();
                audit.recordSystem("ANONYMIZE_ENROLLMENT", "ENROLLMENT", stale.getId(), "invitacion sin canjear vencida");
            }
        }
        long removed = outbox.deleteByCreatedAtBefore(now.minus(Duration.ofDays(90)));
        if (removed > 0) audit.recordSystem("PURGE_OUTBOX", "OUTBOX", null, removed + " correos de mas de 90 dias");
        return done;
    }

    @Scheduled(cron = "${app.retention.cron:0 30 2 * * *}")
    public void scheduledSweep() {
        if (!enabled) return;
        int done = sweep(Instant.now());
        log.info("Retencion de datos: {} personas anonimizadas", done);
    }

    private void doAnonymize(User user) {
        String oldEmail = user.getEmail();
        for (ExamEnrollment e : enrollments.findByUserId(user.getId())) e.scrub();
        if (oldEmail != null) outbox.deleteAll(outbox.findByToEmailIgnoreCase(oldEmail));
        user.anonymize();
        users.save(user);
    }

    private Instant lastActivity(User user) {
        Instant last = user.getCreatedAt();
        for (AssessmentAttempt a : attempts.findByUserId(user.getId())) {
            if (a.getUpdatedAt() != null && a.getUpdatedAt().isAfter(last)) last = a.getUpdatedAt();
        }
        for (ExamEnrollment e : enrollments.findByUserId(user.getId())) {
            if (e.getCreatedAt() != null && e.getCreatedAt().isAfter(last)) last = e.getCreatedAt();
        }
        return last;
    }

    private User applicant(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("La persona no existe."));
        if (user.getRole() != Role.CLIENTE) throw new IllegalArgumentException("Solo se gestionan datos de aspirantes.");
        return user;
    }

    public record SubjectSummary(Long id, String fullName, String email, boolean anonymized) {}

    public record SubjectExport(java.util.Map<String, Object> profile, List<Object> consents, List<Object> enrollments,
                                List<Object> attempts, List<Object> emails, Instant generatedAt) {}
}
