package ni.edu.uam.campusfood.assessment;

import ni.edu.uam.campusfood.audit.AuditService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Resultados de las evaluaciones, solo para personal (STAFF) y administracion (ADMIN).
 * El acceso lo restringe SecurityConfig; los aspirantes nunca reciben puntajes.
 */
@RestController
@RequestMapping("/api/staff/attempts")
public class StaffAssessmentController {
    private final AssessmentService service;
    private final AuditService audit;

    public StaffAssessmentController(AssessmentService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    public List<AssessmentService.StaffAttemptView> submitted(
            @RequestParam(defaultValue = "weil-form-a") String testId,
            Authentication authentication) {
        audit.record(Long.valueOf(authentication.getName()), "VIEW_RESULTS", "TEST", testId, null);
        return service.listSubmitted(testId);
    }

    @GetMapping("/norms")
    public List<Map<String, Integer>> norms(Authentication authentication) {
        audit.record(Long.valueOf(authentication.getName()), "VIEW_NORMS", "TEST", "weil-form-a", null);
        return service.norms();
    }
}
