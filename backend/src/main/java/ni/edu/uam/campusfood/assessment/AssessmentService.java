package ni.edu.uam.campusfood.assessment;

import ni.edu.uam.campusfood.admission.AdmissionService;
import ni.edu.uam.campusfood.admission.ExamEnrollment;
import ni.edu.uam.campusfood.notification.NotificationService;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AssessmentService {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final AssessmentAttemptRepository attempts;
    private final AssessmentResponseRepository responses;
    private final UserRepository users;
    private final AssessmentEventRepository events;
    private final ScoringRegistry scoring;
    private final TestCatalog catalog;
    private final AssessmentResultRepository results;
    private final AdmissionService admission;
    private final NotificationService notifications;

    public AssessmentService(AssessmentAttemptRepository attempts,
                             AssessmentResponseRepository responses,
                             UserRepository users,
                             AssessmentEventRepository events,
                             ScoringRegistry scoring,
                             TestCatalog catalog,
                             AssessmentResultRepository results,
                             AdmissionService admission,
                             NotificationService notifications) {
        this.attempts = attempts;
        this.responses = responses;
        this.users = users;
        this.events = events;
        this.scoring = scoring;
        this.catalog = catalog;
        this.results = results;
        this.admission = admission;
        this.notifications = notifications;
    }

    @Transactional
    public AttemptView open(Long userId, String testId, int testVersion, String currentLeaseToken) {
        validateDefinition(testId, testVersion);
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new AssessmentConflictException("La sesión de usuario ya no existe."));
        Instant now = Instant.now();
        var active = attempts.findFirstByUserIdAndTestIdAndStatusOrderByStartedAtDesc(
                userId, testId, AttemptStatus.IN_PROGRESS);
        if (active.isPresent()) {
            AssessmentAttempt attempt = active.get();
            ensureWithinTime(attempt, now);
            if (attempt.leaseIsActive(now) && !attempt.leaseBelongsTo(currentLeaseToken)) {
                throw new AssessmentConflictException("Este intento ya está abierto en otra ventana o dispositivo.");
            }
            attempt.renewLease(newLeaseToken(), now.plus(LEASE_DURATION));
            // Se persiste antes de armar la vista: la versión devuelta debe ser la que quedó en la base.
            flushOptimistically(attempt);
            return detail(attempt, true);
        }
        ExamEnrollment enrollment = admission.reserveFor(userId, testId, now);
        AssessmentAttempt created = new AssessmentAttempt(user, testId, testVersion, newLeaseToken(),
                now.plus(LEASE_DURATION), now.plus(Duration.ofMinutes(admission.minutesFor(enrollment))));
        created.attachEnrollment(enrollment);
        return detail(attempts.save(created), true);
    }

    @Transactional(readOnly = true)
    public List<AttemptSummary> list(Long userId) {
        return attempts.findByUserIdOrderByStartedAtDesc(userId).stream().map(AttemptSummary::from).toList();
    }

    @Transactional(readOnly = true)
    public AttemptView get(Long userId, Long attemptId) {
        return detail(ownedAttempt(userId, attemptId), false);
    }

    @Transactional
    public AttemptView renew(Long userId, Long attemptId, String leaseToken, long expectedVersion) {
        AssessmentAttempt attempt = writableAttempt(userId, attemptId, leaseToken, expectedVersion);
        attempt.renewLease(leaseToken, Instant.now().plus(LEASE_DURATION));
        flushOptimistically(attempt);
        return detail(attempt, true);
    }

    @Transactional
    public AttemptView answer(Long userId, Long attemptId, int itemIndex, int option,
                              String leaseToken, long expectedVersion) {
        AssessmentAttempt attempt = writableAttempt(userId, attemptId, leaseToken, expectedVersion);
        validateAnswer(attempt, itemIndex, option);
        AssessmentResponse response = responses.findByAttemptIdAndItemIndex(attemptId, itemIndex)
                .orElseGet(() -> new AssessmentResponse(attempt, itemIndex, option));
        response.select(option);
        responses.save(response);
        attempt.touch();
        flushOptimistically(attempt);
        return detail(attempt, true);
    }

    @Transactional
    public AttemptView clearAnswer(Long userId, Long attemptId, int itemIndex,
                                   String leaseToken, long expectedVersion) {
        AssessmentAttempt attempt = writableAttempt(userId, attemptId, leaseToken, expectedVersion);
        if (!definition(attempt).acceptsItem(itemIndex)) {
            throw new IllegalArgumentException("El ítem no pertenece a la definición publicada.");
        }
        responses.deleteByAttemptIdAndItemIndex(attemptId, itemIndex);
        attempt.touch();
        flushOptimistically(attempt);
        return detail(attempt, true);
    }

    @Transactional
    public void recordEvent(Long userId, Long attemptId, String leaseToken, String type, String detail) {
        AssessmentAttempt attempt = ownedAttempt(userId, attemptId);
        Instant now = Instant.now();
        ensureWithinTime(attempt, now);
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS || !attempt.leaseBelongsTo(leaseToken)
                || !attempt.leaseIsActive(now)) {
            throw new AssessmentConflictException("La incidencia no pertenece a una sesión activa.");
        }
        events.save(new AssessmentEvent(attempt, type, detail == null ? "" : detail.strip()));
    }

    @Transactional(readOnly = true)
    public void authorizeMaterial(Long userId, Long attemptId, int itemIndex) {
        AssessmentAttempt attempt = ownedAttempt(userId, attemptId);
        ensureWithinTime(attempt, Instant.now());
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS || !definition(attempt).acceptsItem(itemIndex)) {
            throw new AssessmentConflictException("El material solicitado no está disponible.");
        }
        java.util.Set<Integer> answered = responses.findByAttemptIdOrderByItemIndex(attemptId).stream()
                .map(AssessmentResponse::getItemIndex).collect(java.util.stream.Collectors.toSet());
        int firstPending = 0;
        while (firstPending < definition(attempt).itemCount() && answered.contains(firstPending)) firstPending++;
        int exposedThrough = Math.min(definition(attempt).itemCount() - 1, firstPending + 1);
        if (itemIndex > exposedThrough) {
            throw new AssessmentConflictException("Este ítem todavía no está disponible.");
        }
    }

    @Transactional
    public AttemptView submit(Long userId, Long attemptId, String leaseToken, long expectedVersion,
                              List<Integer> submittedAnswers, Integer age, String normGroup) {
        validateProfile(age, normGroup);
        AssessmentAttempt attempt = writableAttempt(userId, attemptId, leaseToken, expectedVersion);
        if (submittedAnswers == null || submittedAnswers.size() != definition(attempt).itemCount()) {
            throw new AssessmentConflictException("El intento todavía tiene respuestas pendientes.");
        }
        for (int itemIndex = 0; itemIndex < submittedAnswers.size(); itemIndex++) {
            int option = submittedAnswers.get(itemIndex) == null ? 0 : submittedAnswers.get(itemIndex);
            validateAnswer(attempt, itemIndex, option);
            int currentIndex = itemIndex;
            AssessmentResponse response = responses.findByAttemptIdAndItemIndex(attemptId, itemIndex)
                    .orElseGet(() -> new AssessmentResponse(attempt, currentIndex, option));
            response.select(option);
            responses.save(response);
        }
        responses.flush();
        if (responses.countByAttemptId(attemptId) != definition(attempt).itemCount()) {
            throw new AssessmentConflictException("El intento todavía tiene respuestas pendientes.");
        }
        ScoringStrategy.Score score = strategy(attempt).score(submittedAnswers);
        ExamEnrollment enrollment = attempt.getEnrollment();
        // El grupo de baremo y la edad los fija Admision al habilitar: lo que mande el navegador se ignora.
        attempt.submit(score.directScore(), enrollment != null ? enrollment.getParticipantAge() : age,
                enrollment != null ? enrollment.getNormGroup() : normGroup);
        flushOptimistically(attempt);
        saveResult(attempt, score);
        queueReceipt(attempt);
        return detail(attempt, false);
    }

    /**
     * Cierra los intentos cuyo tiempo vencio sin que el aspirante los entregara, calificando lo que alcanzo
     * a responder (lo no respondido cuenta como incorrecto). Sin esto quedarian abiertos para siempre.
     */
    @Scheduled(fixedDelayString = "${app.assessment.sweep-ms:60000}", initialDelayString = "${app.assessment.sweep-ms:60000}")
    @Transactional
    public int closeExpired() {
        Instant now = Instant.now();
        int closed = 0;
        for (AssessmentAttempt attempt : attempts.findByStatusAndDeadlineAtBefore(AttemptStatus.IN_PROGRESS, now.minusSeconds(5))) {
            List<Integer> answers = new java.util.ArrayList<>(java.util.Collections.nCopies(definition(attempt).itemCount(), 0));
            for (AssessmentResponse response : responses.findByAttemptIdOrderByItemIndex(attempt.getId())) {
                answers.set(response.getItemIndex(), response.getSelectedOption());
            }
            ExamEnrollment enrollment = attempt.getEnrollment();
            ScoringStrategy.Score score = strategy(attempt).score(answers);
            attempt.autoClose(score.directScore(),
                    enrollment == null ? null : enrollment.getParticipantAge(),
                    enrollment == null ? null : enrollment.getNormGroup());
            attempts.save(attempt);
            saveResult(attempt, score);
            queueReceipt(attempt);
            closed += 1;
        }
        return closed;
    }

    private void queueReceipt(AssessmentAttempt attempt) {
        User user = attempt.getUser();
        if (user.getEmail() != null) {
            notifications.receipt(user.getEmail(), user.getFullName(), attempt.getId(), attempt.getSubmittedAt());
        }
    }

    private AssessmentAttempt writableAttempt(Long userId, Long attemptId, String leaseToken, long expectedVersion) {
        AssessmentAttempt attempt = ownedAttempt(userId, attemptId);
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new AssessmentConflictException("El intento ya fue entregado.");
        }
        ensureWithinTime(attempt, Instant.now());
        if (!attempt.leaseBelongsTo(leaseToken) || !attempt.leaseIsActive(Instant.now())) {
            throw new AssessmentConflictException("La sesión exclusiva venció o pertenece a otro dispositivo.",
                    AssessmentConflictException.Kind.LEASE_LOST);
        }
        if (attempt.getVersion() != expectedVersion) {
            throw new AssessmentConflictException("El intento cambió en otra sesión. Actualizá antes de guardar.",
                    AssessmentConflictException.Kind.STALE_VERSION);
        }
        return attempt;
    }

    private AssessmentAttempt ownedAttempt(Long userId, Long attemptId) {
        // Mismo mensaje si no existe o es de otro usuario: no revela que ids son validos.
        return attempts.findById(attemptId)
                .filter(attempt -> attempt.getUser().getId().equals(userId))
                .orElseThrow(() -> new AssessmentConflictException("Intento no encontrado.",
                        AssessmentConflictException.Kind.NOT_FOUND));
    }

    private void flushOptimistically(AssessmentAttempt attempt) {
        try {
            attempts.saveAndFlush(attempt);
        } catch (OptimisticLockingFailureException exception) {
            throw new AssessmentConflictException("El intento cambió en otra sesión. Actualizá antes de guardar.",
                    AssessmentConflictException.Kind.STALE_VERSION);
        }
    }

    /** Vista del propio aspirante: nunca incluye puntaje, percentil ni CI (eso es solo para el personal). */
    private AttemptView detail(AssessmentAttempt attempt, boolean exposeLease) {
        List<ResponseView> answerList = responses.findByAttemptIdOrderByItemIndex(attempt.getId()).stream()
                .map(ResponseView::from).toList();
        return AttemptView.from(attempt, answerList, exposeLease);
    }

    /** Intentos entregados con su puntaje, para el personal autorizado. */
    @Transactional(readOnly = true)
    public List<StaffAttemptView> listSubmitted(String testId) {
        return listSubmitted(testId, null, null, null);
    }

    /**
     * @param sessionId filtra por convocatoria (opcional)
     * @param query     texto que debe aparecer en el nombre o el correo (opcional)
     * @param status    "valid" o "invalid" (opcional: todos)
     */
    @Transactional(readOnly = true)
    public List<StaffAttemptView> listSubmitted(String testId, Long sessionId, String query, String status) {
        String needle = query == null ? "" : query.strip().toLowerCase();
        List<AssessmentAttempt> submitted = attempts.findSubmitted(testId, sessionId).stream()
                .filter(a -> needle.isEmpty() || contains(a.getUser().getFullName(), needle) || contains(a.getUser().getEmail(), needle))
                .filter(a -> status == null || status.isBlank() || "all".equals(status)
                        || ("invalid".equals(status) == a.isInvalidated()))
                .toList();
        if (submitted.isEmpty()) {
            return List.of();
        }
        var byAttempt = new java.util.HashMap<Long, List<Integer>>();
        var eventsByAttempt = new java.util.HashMap<Long, List<SecurityEventView>>();
        List<Long> attemptIds = submitted.stream().map(AssessmentAttempt::getId).toList();
        for (AssessmentResponse response : responses.findForAttempts(attemptIds)) {
            byAttempt.computeIfAbsent(response.ownerAttemptId(), id -> new java.util.ArrayList<>())
                    .add(response.getSelectedOption());
        }
        for (AssessmentEvent event : events.findForAttempts(attemptIds)) {
            eventsByAttempt.computeIfAbsent(event.ownerAttemptId(), id -> new java.util.ArrayList<>())
                    .add(SecurityEventView.from(event));
        }
        return submitted.stream().map(attempt -> {
            List<Integer> answers = byAttempt.getOrDefault(attempt.getId(), List.of());
            ScoringStrategy.Score score = answers.size() == definition(attempt).itemCount() ? strategy(attempt).score(answers) : null;
            ScoringStrategy.Interpretation interpretation = attempt.getDirectScore() == null ? null
                    : strategy(attempt).interpret(attempt.getDirectScore(), attempt.getParticipantAge(), attempt.getNormGroup());
            return StaffAttemptView.from(attempt, score == null ? List.of() : score.pageScores(), interpretation,
                    eventsByAttempt.getOrDefault(attempt.getId(), List.of()));
        }).toList();
    }

    /** Página de resultados con lo justo para la tabla; el detalle se pide aparte. */
    @Transactional(readOnly = true)
    public StaffAttemptPage searchSubmitted(String testId, Long sessionId, String query, String status, int page, int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        int safePage = Math.max(0, page);
        String needle = query == null || query.isBlank() ? null
                : "%" + query.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Boolean invalid = status == null || status.isBlank() || "all".equals(status) ? null : "invalid".equals(status);
        var result = attempts.searchSubmitted(testId, sessionId, needle, invalid,
                org.springframework.data.domain.PageRequest.of(safePage, safeSize,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "submittedAt")));
        List<Long> ids = result.getContent().stream().map(AssessmentAttempt::getId).toList();
        var incidents = new java.util.HashMap<Long, Long>();
        if (!ids.isEmpty()) {
            for (Object[] row : events.countForAttempts(ids)) incidents.put((Long) row[0], (Long) row[1]);
        }
        List<StaffAttemptRow> rows = result.getContent().stream().map(attempt -> {
            var interpretation = attempt.getDirectScore() == null ? null
                    : strategy(attempt).interpret(attempt.getDirectScore(), attempt.getParticipantAge(), attempt.getNormGroup());
            return StaffAttemptRow.from(attempt, interpretation, incidents.getOrDefault(attempt.getId(), 0L));
        }).toList();
        return new StaffAttemptPage(rows, result.getTotalElements(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public StaffSummary summary(String testId) {
        Object[] row = attempts.submittedTotals(testId).get(0);
        return new StaffSummary(((Number) row[0]).longValue(),
                row[1] == null ? null : (int) Math.round(((Number) row[1]).doubleValue()),
                row[2] == null ? null : ((Number) row[2]).intValue());
    }

    /** Detalle de un intento entregado: puntaje por lámina e incidencias. Solo personal autorizado. */
    @Transactional(readOnly = true)
    public StaffAttemptView submittedDetail(Long attemptId) {
        AssessmentAttempt attempt = attempts.findById(attemptId)
                .filter(a -> a.getStatus() == AttemptStatus.SUBMITTED)
                .orElseThrow(() -> new AssessmentConflictException("Intento no encontrado.",
                        AssessmentConflictException.Kind.NOT_FOUND));
        List<Integer> answers = responses.findForAttempts(List.of(attemptId)).stream()
                .map(AssessmentResponse::getSelectedOption).toList();
        ScoringStrategy.Score score = answers.size() == definition(attempt).itemCount() ? strategy(attempt).score(answers) : null;
        var interpretation = attempt.getDirectScore() == null ? null
                : strategy(attempt).interpret(attempt.getDirectScore(), attempt.getParticipantAge(), attempt.getNormGroup());
        List<SecurityEventView> eventViews = events.findForAttempts(List.of(attemptId)).stream()
                .map(SecurityEventView::from).toList();
        return StaffAttemptView.from(attempt, score == null ? List.of() : score.pageScores(), interpretation, eventViews);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private ScoringStrategy strategy(AssessmentAttempt attempt) {
        return scoring.forTest(attempt.getTestId());
    }

    public List<java.util.Map<String, Integer>> norms() {
        return scoring.forTest(catalog.defaultTest().id()).norms();
    }

    private static void ensureWithinTime(AssessmentAttempt attempt, Instant now) {
        if (!attempt.isWithinTime(now)) {
            throw new AssessmentConflictException("El tiempo de la prueba terminó. No se aceptaron más cambios.");
        }
    }

    private void validateDefinition(String testId, int testVersion) {
        if (catalog.get(testId).version() != testVersion) {
            throw new IllegalArgumentException("La versión del test no está publicada.");
        }
    }

    private static final java.util.Set<String> NORM_GROUPS = java.util.Set.of("7-16", "AN", "AD", "UN");

    private static void validateProfile(Integer age, String normGroup) {
        if (normGroup != null && !NORM_GROUPS.contains(normGroup)) {
            throw new IllegalArgumentException("El grupo de baremo no es válido.");
        }
        if ("7-16".equals(normGroup) && (age == null || age < 7 || age > 16)) {
            throw new IllegalArgumentException("La edad debe estar entre 7 y 16 años para ese grupo.");
        }
    }

    private void saveResult(AssessmentAttempt attempt, ScoringStrategy.Score score) {
        ScoringStrategy strategy = strategy(attempt);
        results.save(new AssessmentResult(attempt, strategy.scoringVersion(), strategy.scales(score), Instant.now()));
    }

    private TestDefinition definition(AssessmentAttempt attempt) {
        return catalog.get(attempt.getTestId());
    }

    private void validateAnswer(AssessmentAttempt attempt, int itemIndex, int option) {
        if (!definition(attempt).accepts(itemIndex, option)) {
            throw new IllegalArgumentException("La respuesta no pertenece a la definición publicada.");
        }
    }

    private static String newLeaseToken() {
        return UUID.randomUUID().toString();
    }

    public record AttemptSummary(Long id, String testId, int testVersion, AttemptStatus status,
                                 long version, Instant startedAt, Instant deadlineAt,
                                 Instant updatedAt, Instant submittedAt) {
        static AttemptSummary from(AssessmentAttempt attempt) {
            return new AttemptSummary(attempt.getId(), attempt.getTestId(), attempt.getTestVersion(),
                    attempt.getStatus(), attempt.getVersion(), attempt.getStartedAt(),
                    attempt.getDeadlineAt(), attempt.getUpdatedAt(), attempt.getSubmittedAt());
        }
    }

    public record ResponseView(int itemIndex, int selectedOption) {
        static ResponseView from(AssessmentResponse response) {
            return new ResponseView(response.getItemIndex(), response.getSelectedOption());
        }
    }

    public record SecurityEventView(String type, String detail, Instant occurredAt) {
        static SecurityEventView from(AssessmentEvent event) {
            return new SecurityEventView(event.getType(), event.getDetail(), event.getOccurredAt());
        }
    }

    public record AttemptView(Long id, String testId, int testVersion, AttemptStatus status,
                              long version, String leaseToken, Instant leaseExpiresAt,
                              Instant startedAt, Instant deadlineAt, long remainingSeconds,
                              Instant updatedAt, Instant submittedAt,
                              List<ResponseView> responses) {
        static AttemptView from(AssessmentAttempt attempt, List<ResponseView> responses, boolean exposeLease) {
            return new AttemptView(attempt.getId(), attempt.getTestId(), attempt.getTestVersion(),
                    attempt.getStatus(), attempt.getVersion(), exposeLease ? attempt.getLeaseToken() : null,
                    exposeLease ? attempt.getLeaseExpiresAt() : null, attempt.getStartedAt(), attempt.getDeadlineAt(),
                    Math.max(0, Duration.between(Instant.now(), attempt.getDeadlineAt()).getSeconds()), attempt.getUpdatedAt(),
                    attempt.getSubmittedAt(), responses);
        }
    }

    /** Fila del listado del personal: quien rindio, cuanto obtuvo y con que baremo comparar. */
    public record StaffSummary(long total, Integer average, Integer best) {
    }

    public record StaffAttemptPage(List<StaffAttemptRow> items, long total, int page, int size) {
    }

    public record StaffAttemptRow(Long id, String fullName, String email, String career, Long sessionId,
                                  String sessionName, Integer directScore, Integer percentile, Integer ci,
                                  String classification, long incidents, Instant submittedAt, boolean autoSubmitted,
                                  int extraMinutes, boolean invalidated, String invalidReason, boolean reviewed) {
        static StaffAttemptRow from(AssessmentAttempt attempt, ScoringStrategy.Interpretation interpretation, long incidents) {
            var user = attempt.getUser();
            ExamEnrollment enrollment = attempt.getEnrollment();
            return new StaffAttemptRow(attempt.getId(), user.getFullName(), user.getEmail(), user.getCareer(),
                    enrollment == null ? null : enrollment.getSession().getId(),
                    enrollment == null ? null : enrollment.getSession().getName(),
                    attempt.getDirectScore(),
                    interpretation == null ? null : interpretation.percentile(),
                    interpretation == null ? null : interpretation.ci(),
                    interpretation == null ? null : interpretation.classification(), incidents,
                    attempt.getSubmittedAt(), attempt.isAutoSubmitted(),
                    enrollment == null ? 0 : enrollment.getExtraMinutes(), attempt.isInvalidated(),
                    attempt.getInvalidReason(), attempt.getReviewedAt() != null);
        }
    }

    public record StaffAttemptView(Long id, String fullName, String email, String career, String testId,
                                   Instant startedAt, Instant submittedAt, long durationSeconds,
                                   Integer directScore, List<Integer> pageScores,
                                   Integer age, String normGroup, String normColumn,
                                   Integer percentile, Integer ci, String classification,
                                   List<SecurityEventView> securityEvents,
                                   Long sessionId, String sessionName, int extraMinutes, boolean autoSubmitted,
                                   boolean invalidated, String invalidReason, Instant invalidatedAt,
                                   boolean reviewed, Instant reviewedAt) {
        static StaffAttemptView from(AssessmentAttempt attempt, List<Integer> pageScores,
                                     ScoringStrategy.Interpretation interpretation,
                                     List<SecurityEventView> securityEvents) {
            var user = attempt.getUser();
            long seconds = attempt.getSubmittedAt() == null ? 0
                    : Math.max(0, Duration.between(attempt.getStartedAt(), attempt.getSubmittedAt()).getSeconds());
            ExamEnrollment enrollment = attempt.getEnrollment();
            return new StaffAttemptView(attempt.getId(), user.getFullName(), user.getEmail(), user.getCareer(),
                    attempt.getTestId(), attempt.getStartedAt(), attempt.getSubmittedAt(), seconds,
                    attempt.getDirectScore(), pageScores, attempt.getParticipantAge(), attempt.getNormGroup(),
                    interpretation == null ? null : interpretation.normColumn(),
                    interpretation == null ? null : interpretation.percentile(),
                    interpretation == null ? null : interpretation.ci(),
                    interpretation == null ? null : interpretation.classification(), securityEvents,
                    enrollment == null ? null : enrollment.getSession().getId(),
                    enrollment == null ? null : enrollment.getSession().getName(),
                    enrollment == null ? 0 : enrollment.getExtraMinutes(), attempt.isAutoSubmitted(),
                    attempt.isInvalidated(), attempt.getInvalidReason(), attempt.getInvalidatedAt(),
                    attempt.getReviewedAt() != null, attempt.getReviewedAt());
        }
    }
}
