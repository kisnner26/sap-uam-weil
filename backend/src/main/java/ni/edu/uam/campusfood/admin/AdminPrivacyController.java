package ni.edu.uam.campusfood.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ni.edu.uam.campusfood.privacy.RetentionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Solicitudes de los titulares de datos (acceso y eliminacion). Solo ADMIN; todo queda auditado. */
@RestController
@RequestMapping("/api/admin/privacy")
public class AdminPrivacyController {
    private final RetentionService retention;

    public AdminPrivacyController(RetentionService retention) {
        this.retention = retention;
    }

    @GetMapping("/subjects")
    public List<RetentionService.SubjectSummary> search(@RequestParam String q) {
        return retention.search(q);
    }

    @GetMapping("/subjects/{userId}/export")
    public RetentionService.SubjectExport export(@PathVariable Long userId, Authentication auth) {
        return retention.export(Long.valueOf(auth.getName()), userId);
    }

    @PostMapping("/subjects/{userId}/anonymize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonymize(@PathVariable Long userId, @Valid @RequestBody AnonymizeRequest request, Authentication auth) {
        retention.anonymize(Long.valueOf(auth.getName()), userId, request.reason());
    }

    public record AnonymizeRequest(@NotBlank @Size(min = 10, max = 300) String reason) {}
}
