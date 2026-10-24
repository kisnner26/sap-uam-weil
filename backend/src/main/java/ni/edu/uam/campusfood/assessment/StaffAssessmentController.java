package ni.edu.uam.campusfood.assessment;

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

    public StaffAssessmentController(AssessmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<AssessmentService.StaffAttemptView> submitted(
            @RequestParam(defaultValue = "weil-form-a") String testId) {
        return service.listSubmitted(testId);
    }

    @GetMapping("/norms")
    public List<Map<String, Integer>> norms() {
        return service.norms();
    }
}
