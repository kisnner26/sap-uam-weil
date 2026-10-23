package ni.edu.uam.campusfood.assessment;

import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.dao.OptimisticLockingFailureException;
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
    private static final int[] WEIL_ANSWER_PATTERN = {2, 8, 7, 4, 5, 6, 8, 4, 4, 7, 3, 2};

    private final AssessmentAttemptRepository attempts;
    private final AssessmentResponseRepository responses;
    private final UserRepository users;

    public AssessmentService(AssessmentAttemptRepository attempts,
                             AssessmentResponseRepository responses,
                             UserRepository users) {
        this.attempts = attempts;
        this.responses = responses;
        this.users = users;
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
            if (attempt.leaseIsActive(now) && !attempt.leaseBelongsTo(currentLeaseToken)) {
                throw new AssessmentConflictException("Este intento ya está abierto en otra ventana o dispositivo.");
            }
            attempt.renewLease(newLeaseToken(), now.plus(LEASE_DURATION));
            return detail(attempt, true);
        }
        AssessmentAttempt created = attempts.save(new AssessmentAttempt(
                user, testId, testVersion, newLeaseToken(), now.plus(LEASE_DURATION)));
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
        int directScore = scoreWeil(submittedAnswers).stream().mapToInt(Integer::intValue).sum();
        attempt.submit(directScore, age, normGroup);
        flushOptimistically(attempt);
        return detail(attempt, true);
    }

    private AssessmentAttempt writableAttempt(Long userId, Long attemptId, String leaseToken, long expectedVersion) {
        AssessmentAttempt attempt = ownedAttempt(userId, attemptId);
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new AssessmentConflictException("El intento ya fue entregado.");
        }
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
        for (AssessmentResponse response : responses.findForAttempts(submitted.stream().map(AssessmentAttempt::getId).toList())) {
            byAttempt.computeIfAbsent(response.ownerAttemptId(), id -> new java.util.ArrayList<>())
                    .add(response.getSelectedOption());
        }
        return submitted.stream().map(attempt -> {
            List<Integer> answers = byAttempt.getOrDefault(attempt.getId(), List.of());
            List<Integer> pageScores = answers.size() == WEIL_ITEMS ? scoreWeil(answers) : List.of();
            return StaffAttemptView.from(attempt, pageScores);
        }).toList();
    }

    private static List<Integer> scoreWeil(List<Integer> answers) {
        return java.util.stream.IntStream.range(0, 5)
                .map(page -> java.util.stream.IntStream.range(0, WEIL_ANSWER_PATTERN.length)
                        .map(local -> answers.get(page * WEIL_ANSWER_PATTERN.length + local) == WEIL_ANSWER_PATTERN[local] ? 1 : 0)
                        .sum())
                .boxed()
                .toList();
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
                                 long version, Instant startedAt, Instant updatedAt, Instant submittedAt) {
        static AttemptSummary from(AssessmentAttempt attempt) {
            return new AttemptSummary(attempt.getId(), attempt.getTestId(), attempt.getTestVersion(),
                    attempt.getStatus(), attempt.getVersion(), attempt.getStartedAt(),
                    attempt.getUpdatedAt(), attempt.getSubmittedAt());
        }
    }

    public record ResponseView(int itemIndex, int selectedOption) {
        static ResponseView from(AssessmentResponse response) {
            return new ResponseView(response.getItemIndex(), response.getSelectedOption());
        }
    }

    public record AttemptView(Long id, String testId, int testVersion, AttemptStatus status,
                              long version, String leaseToken, Instant leaseExpiresAt,
                              Instant startedAt, Instant updatedAt, Instant submittedAt,
                              List<ResponseView> responses) {
        static AttemptView from(AssessmentAttempt attempt, List<ResponseView> responses, boolean exposeLease) {
            return new AttemptView(attempt.getId(), attempt.getTestId(), attempt.getTestVersion(),
                    attempt.getStatus(), attempt.getVersion(), exposeLease ? attempt.getLeaseToken() : null,
                    exposeLease ? attempt.getLeaseExpiresAt() : null, attempt.getStartedAt(), attempt.getUpdatedAt(),
                    attempt.getSubmittedAt(), responses);
        }
    }

    /** Fila del listado del personal: quien rindio, cuanto obtuvo y con que baremo comparar. */
    public record StaffAttemptView(Long id, String fullName, String email, String career, String testId,
                                   Instant startedAt, Instant submittedAt, long durationSeconds,
                                   Integer directScore, List<Integer> pageScores,
                                   Integer age, String normGroup) {
        static StaffAttemptView from(AssessmentAttempt attempt, List<Integer> pageScores) {
            var user = attempt.getUser();
            long seconds = attempt.getSubmittedAt() == null ? 0
                    : Math.max(0, Duration.between(attempt.getStartedAt(), attempt.getSubmittedAt()).getSeconds());
            return new StaffAttemptView(attempt.getId(), user.getFullName(), user.getEmail(), user.getCareer(),
                    attempt.getTestId(), attempt.getStartedAt(), attempt.getSubmittedAt(), seconds,
                    attempt.getDirectScore(), pageScores, attempt.getParticipantAge(), attempt.getNormGroup());
        }
    }
}
