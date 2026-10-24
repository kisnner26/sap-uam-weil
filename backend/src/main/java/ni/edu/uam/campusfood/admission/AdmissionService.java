package ni.edu.uam.campusfood.admission;

import ni.edu.uam.campusfood.assessment.AssessmentAttempt;
import ni.edu.uam.campusfood.assessment.AssessmentAttemptRepository;
import ni.edu.uam.campusfood.assessment.AttemptStatus;
import ni.edu.uam.campusfood.audit.AuditService;
import ni.edu.uam.campusfood.notification.NotificationService;
import ni.edu.uam.campusfood.privacy.PrivacyService;
import ni.edu.uam.campusfood.security.DatabaseLoginThrottleService;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Flujo de admision: convocatorias, habilitacion de aspirantes con codigo de acceso, anulacion y
 * reaplicacion. Reglas clave: un aspirante solo rinde si esta habilitado en una convocatoria abierta,
 * una habilitacion permite un unico intento, y el codigo prueba que es dueno del correo invitado.
 */
@Service
public class AdmissionService {
    public static final String WEIL = "weil-form-a";
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 12;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> NORM_GROUPS = Set.of("7-16", "AN", "AD", "UN");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INVALID_CODE = "El código no es válido o ya no está disponible. Revisa que lo escribiste bien o pide uno nuevo a Admisión.";

    private final ExamSessionRepository sessions;
    private final ExamEnrollmentRepository enrollments;
    private final AssessmentAttemptRepository attempts;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;
    private final PrivacyService privacy;
    private final DatabaseLoginThrottleService throttles;
    private final int defaultMinutes;

    public AdmissionService(ExamSessionRepository sessions, ExamEnrollmentRepository enrollments,
                            AssessmentAttemptRepository attempts, UserRepository users, AuditService audit,
                            NotificationService notifications, PrivacyService privacy,
                            DatabaseLoginThrottleService throttles,
                            @Value("${app.assessment.weil-duration-seconds:1200}") long durationSeconds) {
        this.sessions = sessions;
        this.enrollments = enrollments;
        this.attempts = attempts;
        this.users = users;
        this.audit = audit;
        this.notifications = notifications;
        this.privacy = privacy;
        this.throttles = throttles;
        this.defaultMinutes = (int) Math.max(1, durationSeconds / 60);
    }

    // ------------------------------------------------------------------ convocatorias (personal)

    @Transactional
    public SessionView createSession(Long actorId, SessionInput input) {
        validate(input);
        ExamSession session = sessions.save(new ExamSession(input.name().strip(), WEIL, input.opensAt(),
                input.closesAt(), input.timeLimitMinutes(), blankToNull(input.instructions()), actorId));
        audit.record(actorId, "CREATE_SESSION", "SESSION", session.getId(), session.getName());
        return toView(session, List.of(), Map.of());
    }

    /** Atajo para pruebas: crea una convocatoria sin pasar por la validacion de formularios. */
    @Transactional
    public ExamSession createSessionForTest(Long actorId, String name, Instant opensAt, Instant closesAt) {
        return sessions.save(new ExamSession(name, WEIL, opensAt, closesAt, null, null, actorId));
    }

    @Transactional
    public SessionView updateSession(Long actorId, Long sessionId, SessionInput input) {
        validate(input);
        ExamSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("La convocatoria no existe."));
        if (session.getCancelledAt() != null) throw new AdmissionException("SESSION_CANCELLED", "La convocatoria está cancelada.");
        session.update(input.name().strip(), input.opensAt(), input.closesAt(), input.timeLimitMinutes(),
                blankToNull(input.instructions()));
        audit.record(actorId, "UPDATE_SESSION", "SESSION", sessionId, session.getName());
        return viewOf(session);
    }

    @Transactional
    public SessionView cancelSession(Long actorId, Long sessionId) {
        ExamSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("La convocatoria no existe."));
        session.cancel();
        audit.record(actorId, "CANCEL_SESSION", "SESSION", sessionId, session.getName());
        return viewOf(session);
    }

    @Transactional(readOnly = true)
    public List<SessionView> listSessions() {
        List<ExamSession> all = sessions.findByTestIdOrderByOpensAtDesc(WEIL);
        if (all.isEmpty()) return List.of();
        List<Long> ids = all.stream().map(ExamSession::getId).toList();
        List<ExamEnrollment> rows = enrollments.findBySessionIdIn(ids);
        Map<Long, AssessmentAttempt> byEnrollment = attemptsByEnrollment(rows);
        Map<Long, List<ExamEnrollment>> bySession = rows.stream().collect(Collectors.groupingBy(e -> e.getSession().getId()));
        return all.stream().map(s -> toView(s, bySession.getOrDefault(s.getId(), List.of()), byEnrollment)).toList();
    }

    // ------------------------------------------------------------------ habilitacion (personal)

    @Transactional
    public EnrollResult enroll(Long actorId, Long sessionId, List<ApplicantInput> applicants) {
        if (applicants == null || applicants.isEmpty()) throw new IllegalArgumentException("Agrega al menos un aspirante.");
        if (applicants.size() > 500) throw new IllegalArgumentException("Máximo 500 aspirantes por operación.");
        ExamSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("La convocatoria no existe."));
        ensureOpenForChanges(session);
        List<EnrollmentView> created = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (ApplicantInput input : applicants) {
            String email = input.email() == null ? "" : input.email().strip().toLowerCase();
            if (!EMAIL.matcher(email).matches() || email.length() > 160) {
                skipped.add(new Skipped(input.email(), "Correo inválido"));
                continue;
            }
            if (!seen.add(email) || !enrollments.findActiveByEmail(sessionId, email).isEmpty()) {
                skipped.add(new Skipped(email, "Ya está habilitado en esta convocatoria"));
                continue;
            }
            String group = input.normGroup() == null || input.normGroup().isBlank() ? "UN" : input.normGroup().strip();
            validateProfile(group, input.age());
            int extra = input.extraMinutes() == null ? 0 : input.extraMinutes();
            if (extra < 0 || extra > 120) {
                skipped.add(new Skipped(email, "Los minutos adicionales deben estar entre 0 y 120"));
                continue;
            }
            String code = newCode();
            ExamEnrollment enrollment = enrollments.save(new ExamEnrollment(session, email, cleanName(input.fullName()), extra,
                    truncate(blankToNull(input.note()), 300), group, input.age(), hash(code), actorId));
            notifications.invitation(enrollment, code, defaultMinutes);
            created.add(toEnrollmentView(enrollment, null));
        }
        audit.record(actorId, "ENROLL", "SESSION", sessionId, created.size() + " habilitados, " + skipped.size() + " omitidos");
        return new EnrollResult(created, skipped);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentView> enrollmentsOf(Long sessionId) {
        List<ExamEnrollment> rows = enrollments.findBySessionIdOrderByEmailAsc(sessionId);
        Map<Long, AssessmentAttempt> byEnrollment = attemptsByEnrollment(rows);
        return rows.stream().map(e -> toEnrollmentView(e, byEnrollment.get(e.getId()))).toList();
    }

    @Transactional
    public EnrollmentView adjust(Long actorId, Long enrollmentId, int extraMinutes, String note, String normGroup, Integer age) {
        ExamEnrollment enrollment = freshEnrollment(enrollmentId);
        if (extraMinutes < 0 || extraMinutes > 120) throw new IllegalArgumentException("Los minutos adicionales deben estar entre 0 y 120.");
        String group = normGroup == null || normGroup.isBlank() ? "UN" : normGroup.strip();
        validateProfile(group, age);
        enrollment.adjust(extraMinutes, truncate(blankToNull(note), 300), group, age);
        audit.record(actorId, "ADJUST_ENROLLMENT", "ENROLLMENT", enrollmentId,
                "minutos extra=" + extraMinutes + ", grupo=" + group);
        return toEnrollmentView(enrollment, null);
    }

    @Transactional
    public void revoke(Long actorId, Long enrollmentId) {
        ExamEnrollment enrollment = freshEnrollment(enrollmentId);
        enrollment.revoke();
        audit.record(actorId, "REVOKE_ENROLLMENT", "ENROLLMENT", enrollmentId, enrollment.getEmail());
    }

    @Transactional
    public void resendInvitation(Long actorId, Long enrollmentId) {
        ExamEnrollment enrollment = freshEnrollment(enrollmentId);
        ensureOpenForChanges(enrollment.getSession());
        String code = newCode();
        enrollment.replaceCode(hash(code));
        notifications.invitation(enrollment, code, defaultMinutes);
        audit.record(actorId, "RESEND_INVITATION", "ENROLLMENT", enrollmentId, enrollment.getEmail());
    }

    // ------------------------------------------------------------------ aspirante

    @Transactional
    public AvailabilityView claim(Long userId, String rawCode) {
        throttles.checkAccount("claim:" + userId);
        String code = normalizeCode(rawCode);
        ExamEnrollment enrollment = code.length() == CODE_LENGTH
                ? enrollments.findByAccessCodeHash(hash(code)).orElse(null) : null;
        boolean usable = enrollment != null && enrollment.getStatus() == EnrollmentStatus.ENABLED
                && (enrollment.getUser() == null || enrollment.getUser().getId().equals(userId));
        if (!usable) {
            throttles.recordFailure("claim:" + userId);
            throw new AdmissionException("CODE_INVALID", INVALID_CODE);
        }
        Instant now = Instant.now();
        SessionState state = enrollment.getSession().stateAt(now);
        boolean consumed = attempts.existsByEnrollmentId(enrollment.getId());
        if (state == SessionState.CANCELLED) throw new AdmissionException("SESSION_CANCELLED", "Esta convocatoria fue cancelada. Escribe a Admisión.");
        if (state == SessionState.CLOSED && !consumed) throw new AdmissionException("CLOSED", "La ventana de esta convocatoria ya cerró. Escribe a Admisión.");
        if (enrollment.getUser() == null) {
            enrollment.claimBy(users.getReferenceById(userId));
            audit.record(userId, "CLAIM_ENROLLMENT", "ENROLLMENT", enrollment.getId(), null);
        }
        throttles.recordSuccess("claim:" + userId);
        return availability(userId);
    }

    @Transactional(readOnly = true)
    public AvailabilityView availability(Long userId) {
        Instant now = Instant.now();
        boolean needsConsent = !privacy.hasConsented(userId);
        int version = privacy.currentVersion();
        var active = attempts.findFirstByUserIdAndTestIdAndStatusOrderByStartedAtDesc(userId, WEIL, AttemptStatus.IN_PROGRESS);
        if (active.isPresent() && active.get().isWithinTime(now)) {
            AssessmentAttempt attempt = active.get();
            ExamEnrollment enrollment = attempt.getEnrollment();
            return new AvailabilityView("IN_PROGRESS", enrollment == null ? null : enrollment.getSession().getName(),
                    enrollment == null ? null : enrollment.getSession().getOpensAt(),
                    enrollment == null ? null : enrollment.getSession().getClosesAt(),
                    enrollment == null ? null : minutesFor(enrollment), needsConsent, version, attempt.getId());
        }
        List<ExamEnrollment> mine = enrollments.findByUserIdAndStatusOrderByCreatedAtDesc(userId, EnrollmentStatus.ENABLED);
        Map<Long, AssessmentAttempt> byEnrollment = attemptsByEnrollment(mine);
        List<ExamEnrollment> fresh = mine.stream().filter(e -> !byEnrollment.containsKey(e.getId())).toList();
        var open = fresh.stream().filter(e -> e.getSession().stateAt(now) == SessionState.OPEN)
                .min(Comparator.comparing(e -> e.getSession().getClosesAt()));
        if (open.isPresent()) return view("OPEN", open.get(), needsConsent, version, null);
        var upcoming = fresh.stream().filter(e -> e.getSession().stateAt(now) == SessionState.UPCOMING)
                .min(Comparator.comparing(e -> e.getSession().getOpensAt()));
        if (upcoming.isPresent()) return view("UPCOMING", upcoming.get(), needsConsent, version, null);
        for (ExamEnrollment e : mine) {
            AssessmentAttempt attempt = byEnrollment.get(e.getId());
            if (attempt == null) continue;
            String state = attempt.isInvalidated() ? "INVALIDATED" : "SUBMITTED";
            return view(state, e, needsConsent, version, attempt.getId());
        }
        if (!fresh.isEmpty()) {
            ExamEnrollment latest = fresh.get(0);
            String state = latest.getSession().stateAt(now) == SessionState.CANCELLED ? "CANCELLED" : "CLOSED";
            return view(state, latest, needsConsent, version, null);
        }
        return new AvailabilityView("NO_ENROLLMENT", null, null, null, null, needsConsent, version, null);
    }

    /** Reserva la habilitacion con la que se abrira un intento nuevo, o explica por que no se puede. */
    @Transactional
    public ExamEnrollment reserveFor(Long userId, String testId, Instant now) {
        if (!privacy.hasConsented(userId)) {
            throw new AdmissionException("CONSENT_REQUIRED", "Antes de comenzar debes leer y aceptar el aviso de privacidad.");
        }
        List<ExamEnrollment> mine = enrollments.findByUserIdAndStatusOrderByCreatedAtDesc(userId, EnrollmentStatus.ENABLED);
        var usable = mine.stream()
                .filter(e -> e.getSession().getTestId().equals(testId))
                .filter(e -> e.getSession().stateAt(now) == SessionState.OPEN)
                .filter(e -> !attempts.existsByEnrollmentId(e.getId()))
                .min(Comparator.comparing(e -> e.getSession().getClosesAt()));
        if (usable.isPresent()) return usable.get();
        AvailabilityView view = availability(userId);
        throw switch (view.state()) {
            case "UPCOMING" -> new AdmissionException("UPCOMING", "Tu aplicación todavía no abre. Abre el " + when(view.opensAt()) + ".");
            case "CLOSED" -> new AdmissionException("CLOSED", "La ventana de tu aplicación ya cerró. Escribe a Admisión.");
            case "CANCELLED" -> new AdmissionException("SESSION_CANCELLED", "Tu convocatoria fue cancelada. Escribe a Admisión.");
            case "SUBMITTED" -> new AdmissionException("ALREADY_SUBMITTED", "Ya entregaste tu evaluación.");
            case "INVALIDATED" -> new AdmissionException("INVALIDATED", "Tu aplicación fue anulada por motivos administrativos. Admisión se comunicará contigo.");
            default -> new AdmissionException("NO_ENROLLMENT", "No tienes una aplicación habilitada. Ingresa tu código de acceso o consulta con Admisión.");
        };
    }

    public int minutesFor(ExamEnrollment enrollment) {
        Integer limit = enrollment.getSession().getTimeLimitMinutes();
        return (limit != null ? limit : defaultMinutes) + enrollment.getExtraMinutes();
    }

    // ------------------------------------------------------------------ resultados (personal)

    @Transactional
    public void markReviewed(Long actorId, Long attemptId) {
        AssessmentAttempt attempt = submittedAttempt(attemptId);
        attempt.markReviewed(actorId);
        audit.record(actorId, "REVIEW_ATTEMPT", "ATTEMPT", attemptId, null);
    }

    @Transactional
    public void invalidate(Long actorId, Long attemptId, String reason) {
        String clean = reason == null ? "" : reason.replaceAll("\\s+", " ").strip();
        if (clean.length() < 10 || clean.length() > 500) {
            throw new IllegalArgumentException("Escribe el motivo de la anulación (entre 10 y 500 caracteres).");
        }
        AssessmentAttempt attempt = submittedAttempt(attemptId);
        if (attempt.isInvalidated()) throw new AdmissionException("ALREADY_INVALIDATED", "El intento ya estaba anulado.");
        attempt.invalidate(actorId, clean);
        audit.record(actorId, "INVALIDATE_ATTEMPT", "ATTEMPT", attemptId, "motivo: " + clean);
        User user = attempt.getUser();
        if (user.getEmail() != null) notifications.invalidated(user.getEmail(), user.getFullName());
    }

    @Transactional
    public EnrollmentView reapply(Long actorId, Long attemptId, Long sessionId, Integer extraMinutes) {
        AssessmentAttempt attempt = submittedAttempt(attemptId);
        if (!attempt.isInvalidated()) {
            throw new AdmissionException("NOT_INVALIDATED", "Solo se puede reaplicar un intento que ya fue anulado.");
        }
        ExamSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("La convocatoria no existe."));
        ensureOpenForChanges(session);
        User user = attempt.getUser();
        if (user.getEmail() == null) throw new IllegalArgumentException("La cuenta del aspirante no tiene correo.");
        String email = user.getEmail().toLowerCase();
        boolean alreadyFresh = enrollments.findActiveByEmail(sessionId, email).stream()
                .anyMatch(e -> !attempts.existsByEnrollmentId(e.getId()));
        if (alreadyFresh) throw new AdmissionException("ALREADY_ENABLED", "Ya tiene una habilitación disponible en esa convocatoria.");
        ExamEnrollment previous = attempt.getEnrollment();
        int extra = extraMinutes != null ? extraMinutes : previous == null ? 0 : previous.getExtraMinutes();
        if (extra < 0 || extra > 120) throw new IllegalArgumentException("Los minutos adicionales deben estar entre 0 y 120.");
        String code = newCode();
        ExamEnrollment enrollment = new ExamEnrollment(session, email, user.getFullName(), extra,
                previous == null ? null : previous.getNote(), previous == null ? "UN" : previous.getNormGroup(),
                previous == null ? null : previous.getParticipantAge(), hash(code), actorId);
        enrollment.claimBy(user); // la identidad ya esta verificada: no necesita volver a canjear un codigo
        enrollment.markReapplication(attemptId);
        enrollments.save(enrollment);
        notifications.invitation(enrollment, code, defaultMinutes);
        audit.record(actorId, "REAPPLY", "ATTEMPT", attemptId, "nueva habilitacion " + enrollment.getId() + " en convocatoria " + sessionId);
        return toEnrollmentView(enrollment, null);
    }

    // ------------------------------------------------------------------ recordatorios

    /** Encola un recordatorio para quienes tienen una convocatoria que abre en las proximas horas. */
    @Transactional
    public int sendReminders(Instant now, Duration lookahead) {
        List<ExamEnrollment> due = enrollments.findNeedingReminder(now, now.plus(lookahead));
        for (ExamEnrollment enrollment : due) {
            if (attempts.existsByEnrollmentId(enrollment.getId())) continue;
            notifications.reminder(enrollment);
            enrollment.markReminderSent();
        }
        return due.size();
    }

    // ------------------------------------------------------------------ utilidades

    private AssessmentAttempt submittedAttempt(Long attemptId) {
        AssessmentAttempt attempt = attempts.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("El intento no existe."));
        if (attempt.getStatus() != AttemptStatus.SUBMITTED) {
            throw new AdmissionException("NOT_SUBMITTED", "El intento todavía no fue entregado.");
        }
        return attempt;
    }

    private ExamEnrollment freshEnrollment(Long enrollmentId) {
        ExamEnrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("La habilitación no existe."));
        if (attempts.existsByEnrollmentId(enrollmentId)) {
            throw new AdmissionException("ALREADY_USED", "Esa habilitación ya se usó: el aspirante tiene un intento.");
        }
        return enrollment;
    }

    private void ensureOpenForChanges(ExamSession session) {
        SessionState state = session.stateAt(Instant.now());
        if (state == SessionState.CANCELLED) throw new AdmissionException("SESSION_CANCELLED", "La convocatoria está cancelada.");
        if (state == SessionState.CLOSED) throw new AdmissionException("CLOSED", "La convocatoria ya cerró.");
    }

    private Map<Long, AssessmentAttempt> attemptsByEnrollment(Collection<ExamEnrollment> rows) {
        if (rows.isEmpty()) return Map.of();
        Map<Long, AssessmentAttempt> map = new HashMap<>();
        for (AssessmentAttempt attempt : attempts.findByEnrollmentIdIn(rows.stream().map(ExamEnrollment::getId).toList())) {
            map.put(attempt.getEnrollment().getId(), attempt);
        }
        return map;
    }

    private SessionView viewOf(ExamSession session) {
        List<ExamEnrollment> rows = enrollments.findBySessionIdIn(List.of(session.getId()));
        return toView(session, rows, attemptsByEnrollment(rows));
    }

    private SessionView toView(ExamSession s, List<ExamEnrollment> rows, Map<Long, AssessmentAttempt> byEnrollment) {
        long enabled = rows.stream().filter(e -> e.getStatus() == EnrollmentStatus.ENABLED).count();
        long claimed = rows.stream().filter(e -> e.getStatus() == EnrollmentStatus.ENABLED && e.isClaimed()).count();
        long started = rows.stream().filter(e -> byEnrollment.containsKey(e.getId())).count();
        long submitted = rows.stream().map(e -> byEnrollment.get(e.getId()))
                .filter(a -> a != null && a.getStatus() == AttemptStatus.SUBMITTED && !a.isInvalidated()).count();
        long invalidated = rows.stream().map(e -> byEnrollment.get(e.getId())).filter(a -> a != null && a.isInvalidated()).count();
        return new SessionView(s.getId(), s.getName(), s.getTestId(), s.getOpensAt(), s.getClosesAt(),
                s.getTimeLimitMinutes() != null ? s.getTimeLimitMinutes() : defaultMinutes, s.getTimeLimitMinutes() == null,
                s.getInstructions(), s.stateAt(Instant.now()).name(), enabled, claimed, started, submitted, invalidated);
    }

    private EnrollmentView toEnrollmentView(ExamEnrollment e, AssessmentAttempt attempt) {
        String progress;
        if (e.getStatus() == EnrollmentStatus.REVOKED) progress = "REVOKED";
        else if (attempt != null) progress = attempt.isInvalidated() ? "INVALIDATED"
                : attempt.getStatus() == AttemptStatus.SUBMITTED ? "SUBMITTED" : "IN_PROGRESS";
        else progress = e.isClaimed() ? "CLAIMED" : "INVITED";
        return new EnrollmentView(e.getId(), e.getEmail(), e.getFullName(), e.getExtraMinutes(), e.getNote(),
                e.getNormGroup(), e.getParticipantAge(), progress, e.getClaimedAt(),
                attempt == null ? null : attempt.getId(), e.getReappliesAttemptId());
    }

    private AvailabilityView view(String state, ExamEnrollment e, boolean needsConsent, int version, Long attemptId) {
        return new AvailabilityView(state, e.getSession().getName(), e.getSession().getOpensAt(),
                e.getSession().getClosesAt(), minutesFor(e), needsConsent, version, attemptId);
    }

    private static void validate(SessionInput input) {
        if (input.name() == null || input.name().strip().length() < 3 || input.name().strip().length() > 160) {
            throw new IllegalArgumentException("El nombre de la convocatoria debe tener entre 3 y 160 caracteres.");
        }
        if (input.opensAt() == null || input.closesAt() == null || !input.closesAt().isAfter(input.opensAt())) {
            throw new IllegalArgumentException("La fecha de cierre debe ser posterior a la de apertura.");
        }
        if (Duration.between(input.opensAt(), input.closesAt()).toDays() > 120) {
            throw new IllegalArgumentException("Una convocatoria no puede durar más de 120 días.");
        }
        if (input.timeLimitMinutes() != null && (input.timeLimitMinutes() < 5 || input.timeLimitMinutes() > 240)) {
            throw new IllegalArgumentException("El tiempo debe estar entre 5 y 240 minutos.");
        }
        if (input.instructions() != null && input.instructions().length() > 500) {
            throw new IllegalArgumentException("Las indicaciones no pueden pasar de 500 caracteres.");
        }
    }

    private static void validateProfile(String group, Integer age) {
        if (!NORM_GROUPS.contains(group)) throw new IllegalArgumentException("El grupo de baremo no es válido.");
        if ("7-16".equals(group) && (age == null || age < 7 || age > 16)) {
            throw new IllegalArgumentException("La edad debe estar entre 7 y 16 años para ese grupo.");
        }
    }

    static String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return code.toString();
    }

    static String normalizeCode(String raw) {
        return raw == null ? "" : raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String cleanName(String value) {
        String cleaned = blankToNull(value == null ? null : value.replaceAll("\\s+", " "));
        return truncate(cleaned, 160);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String when(Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("d/MM/yyyy HH:mm")
                .withZone(java.time.ZoneId.of("America/Managua")).format(instant) + " (hora de Managua)";
    }

    // ------------------------------------------------------------------ DTOs

    public record SessionInput(String name, Instant opensAt, Instant closesAt, Integer timeLimitMinutes, String instructions) {}

    public record ApplicantInput(String email, String fullName, Integer extraMinutes, String note, String normGroup, Integer age) {}

    public record SessionView(Long id, String name, String testId, Instant opensAt, Instant closesAt,
                              int timeLimitMinutes, boolean defaultTime, String instructions, String state,
                              long enrolled, long claimed, long started, long submitted, long invalidated) {}

    public record EnrollmentView(Long id, String email, String fullName, int extraMinutes, String note,
                                 String normGroup, Integer age, String progress, Instant claimedAt,
                                 Long attemptId, Long reappliesAttemptId) {}

    public record Skipped(String email, String reason) {}

    public record EnrollResult(List<EnrollmentView> created, List<Skipped> skipped) {}

    public record AvailabilityView(String state, String sessionName, Instant opensAt, Instant closesAt,
                                   Integer minutes, boolean needsConsent, int consentVersion, Long attemptId) {}
}
