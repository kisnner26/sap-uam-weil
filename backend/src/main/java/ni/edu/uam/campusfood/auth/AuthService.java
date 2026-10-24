package ni.edu.uam.campusfood.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import ni.edu.uam.campusfood.auth.dto.AuthFlowResponse;
import ni.edu.uam.campusfood.auth.dto.LoginRequest;
import ni.edu.uam.campusfood.auth.dto.MoodleLoginRequest;
import ni.edu.uam.campusfood.auth.dto.RegisterRequest;
import ni.edu.uam.campusfood.auth.dto.MfaVerifyRequest;
import ni.edu.uam.campusfood.auth.dto.UserDto;
import ni.edu.uam.campusfood.moodle.MoodleClient;
import ni.edu.uam.campusfood.moodle.MoodleProfile;
import ni.edu.uam.campusfood.moodle.MoodleException;
import ni.edu.uam.campusfood.security.DatabaseLoginThrottleService;
import ni.edu.uam.campusfood.security.JwtService;
import ni.edu.uam.campusfood.security.MfaSecretCipher;
import ni.edu.uam.campusfood.security.TotpService;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final MoodleClient moodleClient;
    private final UserRepository users;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final MfaSecretCipher mfaCipher;
    private final TotpService totp;
    private final DatabaseLoginThrottleService throttles;

    public AuthService(MoodleClient moodleClient,
                       UserRepository users,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       MfaSecretCipher mfaCipher,
                       TotpService totp,
                       DatabaseLoginThrottleService throttles) {
        this.moodleClient = moodleClient;
        this.users = users;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.mfaCipher = mfaCipher;
        this.totp = totp;
        this.throttles = throttles;
    }

    /**
     * Login con Moodle UAM. La contrasena solo se pasa al cliente Moodle y nunca
     * se persiste. El token de Moodle vive dentro de MoodleClient y se descarta.
     */
    @Transactional
    public AuthFlowResponse moodleLogin(MoodleLoginRequest req) {
        String identity = "moodle:" + req.cif();
        throttles.checkAccount(identity);
        MoodleProfile profile;
        try {
            profile = moodleClient.authenticate(req.cif(), req.password());
        } catch (MoodleException ex) {
            if (ex.getKind() == MoodleException.Kind.INVALID_CREDENTIALS) throttles.recordFailure(identity);
            throw ex;
        }

        // a) buscar por moodleId  b) crear si no existe  c) sincronizar perfil
        User user = users.findByMoodleId(profile.userId())
                .map(existing -> {
                    existing.syncMoodleProfile(profile.fullName(), profile.email(), profile.pictureUrl(), profile.career());
                    return existing;
                })
                .orElseGet(() -> {
                    log.info("Creando usuario Moodle nuevo (moodleId={})", profile.userId());
                    return User.fromMoodle(profile.userId(), profile.fullName(),
                            profile.email(), profile.pictureUrl(), profile.career());
                });

        user = users.save(user);
        AuthFlowResponse result = completePrimaryFactor(user);
        throttles.recordSuccess(identity);
        return result;
    }

    /** Registro manual de externos (no estudiantes): correo + contrasena + cedula. */
    @Transactional
    public AuthFlowResponse register(RegisterRequest req) {
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw new AuthException(AuthException.Kind.EMAIL_TAKEN,
                    "Ya existe una cuenta con ese correo");
        }
        User user = User.manual(
                req.fullName().trim(),
                req.email().trim().toLowerCase(),
                passwordEncoder.encode(req.password()),
                req.cedula() == null ? null : req.cedula().trim());
        user = users.save(user);
        return issueFor(user);
    }

    /** Login manual con correo + contrasena. */
    @Transactional
    public AuthFlowResponse login(LoginRequest req) {
        String identity = "manual:" + req.email();
        throttles.checkAccount(identity);
        User user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(u -> u.getPasswordHash() != null)
                .orElse(null);

        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throttles.recordFailure(identity);
            throw new AuthException(AuthException.Kind.BAD_CREDENTIALS,
                    "Correo o contrasena incorrectos");
        }
        AuthFlowResponse result = completePrimaryFactor(user);
        throttles.recordSuccess(identity);
        return result;
    }

    @Transactional
    public AuthFlowResponse verifyMfa(MfaVerifyRequest req) {
        Claims claims;
        try {
            claims = jwtService.parseMfaChallenge(req.challengeToken());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new AuthException(AuthException.Kind.MFA_INVALID, "El codigo o el desafio ya no es valido");
        }
        User user = users.findByIdForUpdate(Long.valueOf(claims.getSubject()))
                .filter(User::isActive)
                .filter(candidate -> candidate.getSessionVersion()
                        == claims.get("sessionVersion", Number.class).longValue())
                .orElseThrow(() -> new AuthException(AuthException.Kind.MFA_INVALID, "El desafio ya no es valido"));
        String identity = "mfa:" + user.getId();
        throttles.checkAccount(identity);
        if (user.getMfaSecret() == null
                || !totp.verify(mfaCipher.decrypt(user.getMfaSecret()), req.code(), java.time.Instant.now())) {
            throttles.recordFailure(identity);
            throw new AuthException(AuthException.Kind.MFA_INVALID, "Codigo de verificacion incorrecto");
        }
        if (!user.isMfaEnabled()) user.enableMfa();
        users.save(user);
        throttles.recordSuccess(identity);
        return issueFor(user);
    }

    @Transactional
    public AuthFlowResponse refresh(Long userId) {
        User user = users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new AuthException(AuthException.Kind.BAD_CREDENTIALS, "Sesion invalida"));
        return issueFor(user);
    }

    @Transactional
    public void logout(Long userId) {
        users.findByIdForUpdate(userId).ifPresent(user -> {
            user.revokeSessions();
            users.save(user);
        });
    }

    private AuthFlowResponse completePrimaryFactor(User user) {
        if (!user.isActive()) {
            throw new AuthException(AuthException.Kind.DISABLED, "Esta cuenta esta desactivada");
        }
        if (user.getRole() == ni.edu.uam.campusfood.user.Role.CLIENTE) return issueFor(user);

        String plainSecret = null;
        if (user.getMfaSecret() == null) {
            plainSecret = totp.newSecret();
            user.prepareMfa(mfaCipher.encrypt(plainSecret));
            users.save(user);
        } else if (!user.isMfaEnabled()) {
            plainSecret = mfaCipher.decrypt(user.getMfaSecret());
        }
        String uri = plainSecret == null ? null : "otpauth://totp/SAP-UAM:"
                + url(user.getEmail()) + "?secret=" + plainSecret + "&issuer=SAP-UAM";
        return AuthFlowResponse.challenge(jwtService.issueMfaChallenge(user).token(),
                !user.isMfaEnabled(), plainSecret, uri);
    }

    private AuthFlowResponse issueFor(User user) {
        JwtService.IssuedToken issued = jwtService.issue(user);
        return AuthFlowResponse.authenticated(issued.token(), issued.expiresInSeconds(), UserDto.from(user));
    }

    private String url(String value) {
        return java.net.URLEncoder.encode(value == null ? "personal" : value,
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
