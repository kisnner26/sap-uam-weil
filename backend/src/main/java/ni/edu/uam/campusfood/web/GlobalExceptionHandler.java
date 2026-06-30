package ni.edu.uam.campusfood.web;

import ni.edu.uam.campusfood.auth.AuthException;
import ni.edu.uam.campusfood.moodle.MoodleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Errores de la integracion con Moodle. */
    @ExceptionHandler(MoodleException.class)
    public ResponseEntity<ApiError> handleMoodle(MoodleException ex) {
        HttpStatus status = switch (ex.getKind()) {
            case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;            // 401
            case MAINTENANCE, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE; // 503
        };
        return build(status, ex.getMessage());
    }

    /** Errores de autenticacion/registro propios. */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiError> handleAuth(AuthException ex) {
        HttpStatus status = switch (ex.getKind()) {
            case BAD_CREDENTIALS -> HttpStatus.UNAUTHORIZED; // 401
            case EMAIL_TAKEN -> HttpStatus.CONFLICT;         // 409
        };
        return build(status, ex.getMessage());
    }

    /** Validacion de los DTOs (@Valid). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, msg.isBlank() ? "Datos invalidos" : msg);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.getReasonPhrase(), message));
    }
}
