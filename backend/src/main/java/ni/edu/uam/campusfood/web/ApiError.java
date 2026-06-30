package ni.edu.uam.campusfood.web;

import java.time.Instant;

/** Cuerpo de error uniforme para toda la API. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message
) {
    public static ApiError of(int status, String error, String message) {
        return new ApiError(Instant.now(), status, error, message);
    }
}
