package ni.edu.uam.campusfood.assessment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ni.edu.uam.campusfood.admission.AdmissionService;
import ni.edu.uam.campusfood.audit.AuditService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Resultados de las evaluaciones, solo para personal (STAFF) y administracion (ADMIN).
 * El acceso lo restringe SecurityConfig; los aspirantes nunca reciben puntajes. Consultar, exportar,
 * revisar, anular y reaplicar quedan en el registro de auditoria.
 */
@RestController
@RequestMapping("/api/staff/attempts")
public class StaffAssessmentController {
    private final AssessmentService service;
    private final AdmissionService admission;
    private final AuditService audit;

    public StaffAssessmentController(AssessmentService service, AdmissionService admission, AuditService audit) {
        this.service = service;
        this.admission = admission;
        this.audit = audit;
    }

    @GetMapping
    public List<AssessmentService.StaffAttemptView> submitted(
            @RequestParam(defaultValue = "weil-form-a") String testId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        audit.record(actor(authentication), "VIEW_RESULTS", "TEST", testId, null);
        return service.listSubmitted(testId, sessionId, q, status);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "weil-form-a") String testId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        List<AssessmentService.StaffAttemptView> rows = service.listSubmitted(testId, sessionId, q, status);
        audit.record(actor(authentication), "EXPORT_RESULTS", "TEST", testId,
                rows.size() + " filas" + (sessionId == null ? "" : ", convocatoria " + sessionId));
        byte[] body = ResultsCsv.render(rows).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("resultados-" + LocalDate.now() + ".csv").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    @PostMapping("/{id}/review")
    public void review(@PathVariable Long id, Authentication authentication) {
        admission.markReviewed(actor(authentication), id);
    }

    @PostMapping("/{id}/invalidate")
    public void invalidate(@PathVariable Long id, @Valid @RequestBody InvalidateRequest request,
                           Authentication authentication) {
        admission.invalidate(actor(authentication), id, request.reason());
    }

    @PostMapping("/{id}/reapply")
    public AdmissionService.EnrollmentView reapply(@PathVariable Long id, @Valid @RequestBody ReapplyRequest request,
                                                   Authentication authentication) {
        return admission.reapply(actor(authentication), id, request.sessionId(), request.extraMinutes());
    }

    @GetMapping("/norms")
    public List<Map<String, Integer>> norms(Authentication authentication) {
        audit.record(actor(authentication), "VIEW_NORMS", "TEST", "weil-form-a", null);
        return service.norms();
    }

    private static Long actor(Authentication authentication) {
        return Long.valueOf(authentication.getName());
    }

    public record InvalidateRequest(@NotBlank @Size(min = 10, max = 500) String reason) {}

    public record ReapplyRequest(@NotNull Long sessionId, Integer extraMinutes) {}
}
