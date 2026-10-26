package ni.edu.uam.campusfood.auth;

import ni.edu.uam.campusfood.audit.AuditService;
import ni.edu.uam.campusfood.notification.NotificationService;
import ni.edu.uam.campusfood.security.DatabaseLoginThrottleService;
import ni.edu.uam.campusfood.user.Role;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Recuperación de contraseña por correo: enlace de un solo uso, 30 minutos, sin revelar si la cuenta existe. */
@Service
public class PasswordResetService {
    static final Duration VALIDITY = Duration.ofMinutes(30);

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final NotificationService notifications;
    private final DatabaseLoginThrottleService throttles;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final String publicUrl;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                NotificationService notifications, DatabaseLoginThrottleService throttles,
                                PasswordEncoder passwords, AuditService audit,
                                @Value("${app.public-url:http://localhost:8765}") String publicUrl) {
        this.users = users;
        this.tokens = tokens;
        this.notifications = notifications;
        this.throttles = throttles;
        this.passwords = passwords;
        this.audit = audit;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    /** Siempre termina igual para quien llama: no se distingue una cuenta existente de una que no existe. */
    @Transactional
    public void request(String email) {
        String identity = "reset:" + email.trim().toLowerCase();
        throttles.checkAccount(identity);
        throttles.recordFailure(identity);
        users.findByEmailIgnoreCase(email.trim())
                .filter(User::isActive)
                .filter(user -> user.getPasswordHash() != null && !user.isAnonymized())
                .ifPresent(user -> {
                    byte[] raw = new byte[32];
                    random.nextBytes(raw);
                    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
                    tokens.deleteUnusedByUserId(user.getId());
                    tokens.save(new PasswordResetToken(user.getId(), sha256(token), Instant.now().plus(VALIDITY)));
                    notifications.passwordReset(user.getEmail(), user.getFullName(),
                            publicUrl + "/?reset=" + token, VALIDITY.toMinutes());
                    audit.record(user.getId(), "REQUEST_PASSWORD_RESET", "USER", user.getId(), null);
                });
    }

    @Transactional
    public void reset(String token, String newPassword) {
        PasswordResetToken stored = tokens.findByTokenHash(sha256(token.trim()))
                .filter(t -> t.isUsable(Instant.now()))
                .orElseThrow(() -> new AuthException(AuthException.Kind.MFA_INVALID,
                        "El enlace no es válido o ya venció. Pide uno nuevo."));
        User user = users.findByIdForUpdate(stored.getUserId()).filter(User::isActive)
                .orElseThrow(() -> new AuthException(AuthException.Kind.MFA_INVALID,
                        "El enlace no es válido o ya venció. Pide uno nuevo."));
        if (user.getRole() != Role.CLIENTE && newPassword.length() < 12) {
            throw new IllegalArgumentException("La contraseña del personal debe tener al menos 12 caracteres.");
        }
        user.changePassword(passwords.encode(newPassword));
        users.save(user);
        stored.markUsed();
        tokens.save(stored);
        tokens.deleteUnusedByUserId(user.getId());
        audit.record(user.getId(), "RESET_PASSWORD", "USER", user.getId(), "por enlace de correo");
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
