package ni.edu.uam.campusfood.dev;

import jakarta.servlet.http.HttpServletRequest;
import ni.edu.uam.campusfood.admission.EnrollmentStatus;
import ni.edu.uam.campusfood.admission.ExamEnrollment;
import ni.edu.uam.campusfood.admission.ExamEnrollmentRepository;
import ni.edu.uam.campusfood.admission.ExamSession;
import ni.edu.uam.campusfood.admission.ExamSessionRepository;
import ni.edu.uam.campusfood.admission.SessionState;
import ni.edu.uam.campusfood.assessment.AssessmentAttemptRepository;
import ni.edu.uam.campusfood.assessment.AssessmentEventRepository;
import ni.edu.uam.campusfood.assessment.AssessmentResponseRepository;
import ni.edu.uam.campusfood.auth.dto.AuthFlowResponse;
import ni.edu.uam.campusfood.auth.dto.UserDto;
import ni.edu.uam.campusfood.privacy.PrivacyService;
import ni.edu.uam.campusfood.security.JwtAuthFilter;
import ni.edu.uam.campusfood.security.JwtService;
import ni.edu.uam.campusfood.user.Role;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Acceso de control de calidad exclusivamente local. Esta clase no existe en perfiles
 * distintos de {@code dev}; no elimina ni altera las validaciones de produccion.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/qa")
public class LocalQaController {
    private static final String QA_PASSWORD = "local-qa-only-not-a-production-secret";
    private static final String WEIL = "weil-form-a";

    private final UserRepository users;
    private final ExamSessionRepository sessions;
    private final ExamEnrollmentRepository enrollments;
    private final AssessmentAttemptRepository attempts;
    private final AssessmentResponseRepository responses;
    private final AssessmentEventRepository events;
    private final PrivacyService privacy;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final boolean secureCookie;

    public LocalQaController(UserRepository users, ExamSessionRepository sessions,
                             ExamEnrollmentRepository enrollments, AssessmentAttemptRepository attempts,
                             AssessmentResponseRepository responses, AssessmentEventRepository events, PrivacyService privacy,
                             PasswordEncoder passwordEncoder,
                             JwtService jwt,
                             @org.springframework.beans.factory.annotation.Value("${app.session-cookie.secure:false}")
                             boolean secureCookie) {
        this.users = users;
        this.sessions = sessions;
        this.enrollments = enrollments;
        this.attempts = attempts;
        this.responses = responses;
        this.events = events;
        this.privacy = privacy;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login/{scenario}")
    @Transactional
    public ResponseEntity<AuthFlowResponse> login(@PathVariable String scenario, HttpServletRequest request) {
        requireLoopback(request);
        User user = switch (scenario) {
            case "aspirante" -> qaUser("qa.aspirante@local.test", "Aspirante QA", Role.CLIENTE);
            case "staff" -> qaUser("qa.personal@local.test", "Personal QA", Role.STAFF);
            case "admin" -> qaUser("qa.admin@local.test", "Administración QA", Role.ADMIN);
            default -> throw new IllegalArgumentException("Escenario QA no reconocido.");
        };
        if (user.getRole() == Role.CLIENTE) {
            privacy.accept(user.getId(), privacy.currentVersion());
            attempts.findFirstByUserIdAndTestIdAndStatusOrderByStartedAtDesc(user.getId(), WEIL,
                    ni.edu.uam.campusfood.assessment.AttemptStatus.IN_PROGRESS).ifPresent(attempt -> attempt.releaseLease());
            ensureOpenEnrollment(user);
        }

        JwtService.IssuedToken token = jwt.issue(user);
        AuthFlowResponse response = AuthFlowResponse.authenticated(token.token(), token.expiresInSeconds(), UserDto.from(user));
        ResponseCookie cookie = ResponseCookie.from(JwtAuthFilter.SESSION_COOKIE, token.token())
                .httpOnly(true)
                .secure(secureCookie || request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(token.expiresInSeconds())
                .build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(response);
    }

    /** Reinicia exclusivamente el caso ficticio del aspirante para repetir una prueba local desde cero. */
    @PostMapping("/reset/aspirante")
    @Transactional
    public ResponseEntity<Void> resetApplicant(HttpServletRequest request) {
        requireLoopback(request);
        User applicant = qaUser("qa.aspirante@local.test", "Aspirante QA", Role.CLIENTE);
        attempts.findByUserId(applicant.getId()).stream()
                .filter(attempt -> WEIL.equals(attempt.getTestId()))
                .forEach(attempt -> {
                    responses.deleteByAttemptId(attempt.getId());
                    events.deleteByAttemptId(attempt.getId());
                    attempts.delete(attempt);
                });
        attempts.flush();
        ensureOpenEnrollment(applicant);
        return ResponseEntity.noContent().build();
    }

    /** Defensa adicional: aunque el servidor escuche en otra interfaz, el acceso QA solo atiende al propio equipo. */
    private static void requireLoopback(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        boolean loopback = "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
        if (!loopback) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND);
    }

    private User qaUser(String email, String fullName, Role role) {
        return users.findByEmailIgnoreCase(email).orElseGet(() -> {
            User created = role == Role.CLIENTE
                    ? User.manual(fullName, email, passwordEncoder.encode(QA_PASSWORD), "QA-LOCAL")
                    : User.staff(fullName, email, passwordEncoder.encode(QA_PASSWORD), role);
            if (role != Role.CLIENTE) created.enableMfa();
            return users.save(created);
        });
    }

    /** Entrega una habilitacion nueva solo a la identidad ficticia de QA para poder repetir el flujo. */
    private void ensureOpenEnrollment(User user) {
        boolean hasFreshEnrollment = enrollments.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), EnrollmentStatus.ENABLED)
                .stream()
                .anyMatch(enrollment -> WEIL.equals(enrollment.getSession().getTestId())
                        && enrollment.getSession().stateAt(Instant.now()) == SessionState.OPEN
                        && !attempts.existsByEnrollmentId(enrollment.getId()));
        if (hasFreshEnrollment) return;

        ExamSession session = sessions.findByTestIdOrderByOpensAtDesc(WEIL).stream()
                .filter(candidate -> candidate.stateAt(Instant.now()) == SessionState.OPEN)
                .findFirst()
                .orElseGet(() -> sessions.save(new ExamSession("QA local · Test de Weil", WEIL,
                        Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(30, ChronoUnit.DAYS),
                        null, "Convocatoria local para pruebas.", user.getId())));

        ExamEnrollment enrollment = new ExamEnrollment(session, user.getEmail(), user.getFullName(), 0,
                "Datos ficticios de QA local.", "UN", 20, "qa-local-no-access-code", user.getId());
        enrollment.claimBy(user);
        enrollments.save(enrollment);
    }
}
