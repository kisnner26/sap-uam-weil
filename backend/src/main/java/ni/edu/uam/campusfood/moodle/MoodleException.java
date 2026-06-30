package ni.edu.uam.campusfood.moodle;

/**
 * Errores de la integracion con Moodle, clasificados para mapear el HTTP correcto.
 *
 * INVALID_CREDENTIALS -> 401, el CIF/contrasena no son validos.
 * MAINTENANCE         -> 503, Moodle respondio HTML (mantenimiento/caida) en vez de JSON.
 * UNAVAILABLE         -> 503, no se pudo contactar a Moodle (timeout, red, 5xx).
 */
public class MoodleException extends RuntimeException {

    public enum Kind {
        INVALID_CREDENTIALS,
        MAINTENANCE,
        UNAVAILABLE
    }

    private final Kind kind;

    public MoodleException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public MoodleException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public static MoodleException invalidCredentials(String moodleMessage) {
        return new MoodleException(Kind.INVALID_CREDENTIALS,
                moodleMessage == null ? "Credenciales UAM invalidas" : moodleMessage);
    }

    public static MoodleException maintenance() {
        return new MoodleException(Kind.MAINTENANCE,
                "Plataforma UAM en mantenimiento, intenta mas tarde");
    }

    public static MoodleException unavailable(Throwable cause) {
        return new MoodleException(Kind.UNAVAILABLE,
                "Plataforma UAM en mantenimiento, intenta mas tarde", cause);
    }
}
