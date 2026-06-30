package ni.edu.uam.campusfood.auth;

/** Errores de autenticacion/registro propios de la app (no Moodle). */
public class AuthException extends RuntimeException {

    public enum Kind {
        BAD_CREDENTIALS, // 401
        EMAIL_TAKEN      // 409
    }

    private final Kind kind;

    public AuthException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
