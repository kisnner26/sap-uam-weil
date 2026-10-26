package ni.edu.uam.campusfood.assessment;

public class AssessmentConflictException extends RuntimeException {
    /** Qué pasó, para que el cliente decida: reintentar, abandonar o avisar. */
    public enum Kind { CONFLICT, STALE_VERSION, LEASE_LOST, NOT_FOUND }

    private final Kind kind;

    public AssessmentConflictException(String message) {
        this(message, Kind.CONFLICT);
    }

    public AssessmentConflictException(String message, Kind kind) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
