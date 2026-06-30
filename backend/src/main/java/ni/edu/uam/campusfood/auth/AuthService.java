package ni.edu.uam.campusfood.auth;

import ni.edu.uam.campusfood.auth.dto.AuthResponse;
import ni.edu.uam.campusfood.auth.dto.LoginRequest;
import ni.edu.uam.campusfood.auth.dto.MoodleLoginRequest;
import ni.edu.uam.campusfood.auth.dto.RegisterRequest;
import ni.edu.uam.campusfood.auth.dto.UserDto;
import ni.edu.uam.campusfood.moodle.MoodleClient;
import ni.edu.uam.campusfood.moodle.MoodleProfile;
import ni.edu.uam.campusfood.security.JwtService;
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

    public AuthService(MoodleClient moodleClient,
                       UserRepository users,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder) {
        this.moodleClient = moodleClient;
        this.users = users;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Login con Moodle UAM. La contrasena solo se pasa al cliente Moodle y nunca
     * se persiste. El token de Moodle vive dentro de MoodleClient y se descarta.
     */
    @Transactional
    public AuthResponse moodleLogin(MoodleLoginRequest req) {
        MoodleProfile profile = moodleClient.authenticate(req.cif(), req.password());

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
        return issueFor(user);
    }

    /** Registro manual de externos (no estudiantes): correo + contrasena + cedula. */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
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
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(u -> u.getPasswordHash() != null)
                .orElseThrow(() -> new AuthException(AuthException.Kind.BAD_CREDENTIALS,
                        "Correo o contrasena incorrectos"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new AuthException(AuthException.Kind.BAD_CREDENTIALS,
                    "Correo o contrasena incorrectos");
        }
        return issueFor(user);
    }

    private AuthResponse issueFor(User user) {
        JwtService.IssuedToken issued = jwtService.issue(user);
        return AuthResponse.of(issued.token(), issued.expiresInSeconds(), UserDto.from(user));
    }
}
