package ni.edu.uam.campusfood.web;

import ni.edu.uam.campusfood.admission.AdmissionException;
import ni.edu.uam.campusfood.auth.AuthException;
import ni.edu.uam.campusfood.assessment.AssessmentConflictException;
import ni.edu.uam.campusfood.moodle.MoodleException;
import ni.edu.uam.campusfood.security.RateLimitException;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
            case BAD_CREDENTIALS, MFA_INVALID -> HttpStatus.UNAUTHORIZED; // 401
            case DISABLED -> HttpStatus.FORBIDDEN;           // 403
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

    /** Una regla del flujo de admision impide la operacion; el codigo deja a la interfaz decidir que mostrar. */
    @ExceptionHandler(AdmissionException.class)
    public ResponseEntity<ApiError> handleAdmission(AdmissionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage(), ex.getCode()));
    }

    @ExceptionHandler(AssessmentConflictException.class)
    public ResponseEntity<ApiError> handleAssessmentConflict(AssessmentConflictException ex) {
        HttpStatus status = ex.getKind() == AssessmentConflictException.Kind.NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        String code = ex.getKind() == AssessmentConflictException.Kind.CONFLICT ? null : ex.getKind().name();
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.getReasonPhrase(), ex.getMessage(), code));
    }

    /** Cuerpo ilegible o parámetro con formato inválido: 400 con texto propio, sin detalles de Java. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception ex) {
        String message = ex instanceof MissingServletRequestParameterException missing
                ? "Falta el parámetro «" + missing.getParameterName() + "»."
                : "La solicitud tiene un formato inválido.";
        return build(HttpStatus.BAD_REQUEST, message);
    }

    /** Último recurso: nunca devolver el texto de una excepción interna. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof org.springframework.web.ErrorResponse || ex instanceof org.springframework.security.access.AccessDeniedException
                || ex instanceof org.springframework.security.core.AuthenticationException) {
            throw ex;
        }
        LoggerFactory.getLogger(GlobalExceptionHandler.class).error("Error no controlado", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado. Intentá de nuevo en unos minutos.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiError.of(429, "Too Many Requests", ex.getMessage()));
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.getReasonPhrase(), message));
    }
}
