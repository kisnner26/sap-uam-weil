package ni.edu.uam.campusfood.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion del JWT propio de la app (NO el token de Moodle).
 * Se enlaza con el bloque app.jwt.* de application.yml.
 */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** Secreto HMAC para firmar el token. Minimo 32 bytes. */
    private String secret;

    /** Vigencia del token en milisegundos. */
    private long expirationMs = 86_400_000L; // 24h

    private String issuer = "sap-uam-weill";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public void setExpirationMs(long expirationMs) {
        this.expirationMs = expirationMs;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
