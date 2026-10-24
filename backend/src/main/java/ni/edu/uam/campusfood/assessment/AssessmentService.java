package ni.edu.uam.campusfood.assessment;

import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AssessmentService {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final String WEIL_TEST_ID = "weil-form-a";
    private static final int WEIL_VERSION = 1;
    private static final int WEIL_ITEMS = 60;
    private static final int WEIL_OPTIONS = 8;

    private final AssessmentAttemptRepository attempts;
    private final AssessmentResponseRepository responses;
    private final UserRepository users;
    private final AssessmentEventRepository events;
    private final WeilScoringStrategy scoring;
    private final Duration testDuration;

    public AssessmentService(AssessmentAttemptRepository attempts,
                             AssessmentResponseRepository responses,
                             UserRepository users,
                             AssessmentEventRepository events,
                             WeilScoringStrategy scoring,
                             @Value("${app.assessment.weil-duration-seconds:1200}") long durationSeconds) {
        this.attempts = attempts;
        this.responses = responses;
        this.users = users;
        this.events = events;
        this.scoring = scoring;
        this.testDuration = Duration.ofSeconds(durationSeconds);
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
            return detail(attempt, true);
        }
        AssessmentAttempt created = attempts.save(new AssessmentAttempt(
                user, testId, testVersion, newLeaseToken(), now.plus(LEASE_DURATION), now.plus(testDuration)));
        return detail(created, true);
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
        validateAnswer(itemIndex, option);
        AssessmentAttempt attempt = writableAttempt(userId, attemptId, leaseToken, expectedVersion);
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
        if (itemIndex < 0 || itemIndex >= WEIL_ITEMS) {
            throw new IllegalArgumentException("El ítem no pertenece a la definición publicada.");
        }
        AssessmentAttempt attempt = writableAttempt(userId, attemptId, leaseToken, expectedVersion);
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
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS || itemIndex < 0 || itemIndex >= WEIL_ITEMS) {
            throw new AssessmentConflictException("El material solicitado no está disponible.");
        }
        java.util.Set<Integer> answered = responses.findByAttemptIdOrderByItemIndex(attemptId).stream()
                .map(AssessmentResponse::getItemIndex).collect(java.util.stream.Collectors.toSet());
        int firstPending = 0;
        while (firstPending < WEIL_ITEMS && answered.contains(firstPending)) firstPending++;
        int exposedThrough = Math.min(WEIL_ITEMS - 1, firstPending + 1);
        if (itemIndex > exposedThrough) {
            throw new AssessmentConflictException("Este ítem todavía no está disponible.");
        }
    }

    @Transactional
    public AttemptView submit(Long userId, Long attemptId, String leaseToken, long expectedVersion,
                              List<Integer> submittedAnswers, Integer age, String normGroup) {
        validateProfile(age, normGroup);
        AssessmentAttempt attempt = writableAttempt(userId, attemptId, leaseToken, expectedVersion);
        if (submittedAnswers == null || submittedAnswers.size() != WEIL_ITEMS) {
            throw new AssessmentConflictException("El intento todavía tiene respuestas pendientes.");
        }
        for (int itemIndex = 0; itemIndex < submittedAnswers.size(); itemIndex++) {
            int option = submittedAnswers.get(itemIndex) == null ? 0 : submittedAnswers.get(itemIndex);
            validateAnswer(itemIndex, option);
            int currentIndex = itemIndex;
            AssessmentResponse response = responses.findByAttemptIdAndItemIndex(attemptId, itemIndex)
                    .orElseGet(() -> new AssessmentResponse(attempt, currentIndex, option));
            response.select(option);
            responses.save(response);
        }
        responses.flush();
        if (responses.countByAttemptId(attemptId) != WEIL_ITEMS) {
            throw new AssessmentConflictException("El intento todavía tiene respuestas pendientes.");
        }
        WeilScoringStrategy.Score score = scoring.score(submittedAnswers);
        attempt.submit(score.directScore(), age, normGroup);
        flushOptimistically(attempt);
        return detail(attempt, true);
    }

    private AssessmentAttempt writableAttempt(Long userId, Long attemptId, String leaseToken, long expectedVersion) {
        AssessmentAttempt attempt = ownedAttempt(userId, attemptId);
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new AssessmentConflictException("El intento ya fue entregado.");
        }
        ensureWithinTime(attempt, Instant.now());
        if (attempt.getVersion() != expectedVersion) {
            throw new AssessmentConflictException("El intento cambió en otra sesión. Actualizá antes de guardar.");
        }
        if (!attempt.leaseBelongsTo(leaseToken) || !attempt.leaseIsActive(Instant.now())) {
            throw new AssessmentConflictException("La sesión exclusiva venció o pertenece a otro dispositivo.");
        }
        return attempt;
    }

    private AssessmentAttempt ownedAttempt(Long userId, Long attemptId) {
        // Mismo mensaje si no existe o es de otro usuario: no revela que ids son validos.
        return attempts.findById(attemptId)
                .filter(attempt -> attempt.getUser().getId().equals(userId))
                .orElseThrow(() -> new AssessmentConflictException("Intento no encontrado."));
    }

    private void flushOptimistically(AssessmentAttempt attempt) {
        try {
            attempts.saveAndFlush(attempt);
        } catch (OptimisticLockingFailureException exception) {
            throw new AssessmentConflictException("El intento cambió en otra sesión. Actualizá antes de guardar.");
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
        List<AssessmentAttempt> submitted = attempts.findByTestIdAndStatusOrderBySubmittedAtDesc(
                testId, AttemptStatus.SUBMITTED);
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
            WeilScoringStrategy.Score score = answers.size() == WEIL_ITEMS ? scoring.score(answers) : null;
            WeilScoringStrategy.Interpretation interpretation = attempt.getDirectScore() == null ? null
                    : scoring.interpret(attempt.getDirectScore(), attempt.getParticipantAge(), attempt.getNormGroup());
            return StaffAttemptView.from(attempt, score == null ? List.of() : score.pageScores(), interpretation,
                    eventsByAttempt.getOrDefault(attempt.getId(), List.of()));
        }).toList();
    }

    public List<java.util.Map<String, Integer>> norms() {
        return scoring.norms();
    }

    private static void ensureWithinTime(AssessmentAttempt attempt, Instant now) {
        if (!attempt.isWithinTime(now)) {
            throw new AssessmentConflictException("El tiempo de la prueba terminó. No se aceptaron más cambios.");
        }
    }

    private static void validateDefinition(String testId, int testVersion) {
        if (!WEIL_TEST_ID.equals(testId) || testVersion != WEIL_VERSION) {
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

    private static void validateAnswer(int itemIndex, int option) {
        if (itemIndex < 0 || itemIndex >= WEIL_ITEMS || option < 1 || option > WEIL_OPTIONS) {
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
    public record StaffAttemptView(Long id, String fullName, String email, String career, String testId,
                                   Instant startedAt, Instant submittedAt, long durationSeconds,
                                   Integer directScore, List<Integer> pageScores,
                                   Integer age, String normGroup, String normColumn,
                                   Integer percentile, Integer ci, String classification,
                                   List<SecurityEventView> securityEvents) {
        static StaffAttemptView from(AssessmentAttempt attempt, List<Integer> pageScores,
                                     WeilScoringStrategy.Interpretation interpretation,
                                     List<SecurityEventView> securityEvents) {
            var user = attempt.getUser();
            long seconds = attempt.getSubmittedAt() == null ? 0
                    : Math.max(0, Duration.between(attempt.getStartedAt(), attempt.getSubmittedAt()).getSeconds());
            return new StaffAttemptView(attempt.getId(), user.getFullName(), user.getEmail(), user.getCareer(),
                    attempt.getTestId(), attempt.getStartedAt(), attempt.getSubmittedAt(), seconds,
                    attempt.getDirectScore(), pageScores, attempt.getParticipantAge(), attempt.getNormGroup(),
                    interpretation == null ? null : interpretation.normColumn(),
                    interpretation == null ? null : interpretation.percentile(),
                    interpretation == null ? null : interpretation.ci(),
                    interpretation == null ? null : interpretation.classification(), securityEvents);
        }
    }
}
