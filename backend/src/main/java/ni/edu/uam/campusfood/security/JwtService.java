package ni.edu.uam.campusfood.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import ni.edu.uam.campusfood.user.User;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Genera y valida el JWT propio de la app. Este token representa la sesion
 * del usuario en SAP-UAM Weill; es independiente del token de Moodle, que nunca
 * se persiste ni se reutiliza.
 */
@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtService(JwtProperties props) {
        this.props = props;
        if (props.getSecret() == null || props.getSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret debe tener al menos 32 bytes. Definí APP_JWT_SECRET.");
        }
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** Emite un token firmado para el usuario dado. */
    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusMillis(props.getExpirationMs());

        String token = Jwts.builder()
                .issuer(props.getIssuer())
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim("role", user.getRole().name())
                .claim("provider", user.getAuthProvider().value())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();

        return new IssuedToken(token, exp, props.getExpirationMs() / 1000);
    }

    /** Valida la firma/vigencia y devuelve los claims, o lanza JwtException. */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(props.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public record IssuedToken(String token, Instant expiresAt, long expiresInSeconds) {
    }
}
