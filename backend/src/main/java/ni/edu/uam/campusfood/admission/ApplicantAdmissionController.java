package ni.edu.uam.campusfood.admission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lo que el aspirante necesita saber para comenzar: su convocatoria y el canje del codigo de acceso. */
@RestController
@RequestMapping("/api/applicant")
public class ApplicantAdmissionController {
    private final AdmissionService admission;

    public ApplicantAdmissionController(AdmissionService admission) {
        this.admission = admission;
    }

    @GetMapping("/availability")
    public AdmissionService.AvailabilityView availability(Authentication auth) {
        return admission.availability(Long.valueOf(auth.getName()));
    }

    @PostMapping("/claim")
    public AdmissionService.AvailabilityView claim(Authentication auth, @Valid @RequestBody ClaimRequest request) {
        return admission.claim(Long.valueOf(auth.getName()), request.code());
    }

    public record ClaimRequest(@NotBlank @Size(max = 40) String code) {}
}
