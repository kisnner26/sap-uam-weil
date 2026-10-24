package ni.edu.uam.campusfood.admission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Convocatorias y habilitacion de aspirantes. Acceso: STAFF y ADMIN (lo restringe SecurityConfig). */
@RestController
@RequestMapping("/api/staff")
public class AdmissionStaffController {
    private final AdmissionService admission;

    public AdmissionStaffController(AdmissionService admission) {
        this.admission = admission;
    }

    @GetMapping("/sessions")
    public List<AdmissionService.SessionView> sessions() {
        return admission.listSessions();
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public AdmissionService.SessionView create(Authentication auth, @Valid @RequestBody SessionRequest request) {
        return admission.createSession(actor(auth), request.toInput());
    }

    @PutMapping("/sessions/{id}")
    public AdmissionService.SessionView update(Authentication auth, @PathVariable Long id,
                                               @Valid @RequestBody SessionRequest request) {
        return admission.updateSession(actor(auth), id, request.toInput());
    }

    @PostMapping("/sessions/{id}/cancel")
    public AdmissionService.SessionView cancel(Authentication auth, @PathVariable Long id) {
        return admission.cancelSession(actor(auth), id);
    }

    @GetMapping("/sessions/{id}/enrollments")
    public List<AdmissionService.EnrollmentView> enrollments(@PathVariable Long id) {
        return admission.enrollmentsOf(id);
    }

    @PostMapping("/sessions/{id}/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public AdmissionService.EnrollResult enroll(Authentication auth, @PathVariable Long id,
                                                @Valid @RequestBody EnrollRequest request) {
        return admission.enroll(actor(auth), id, request.applicants());
    }

    @PatchMapping("/enrollments/{id}")
    public AdmissionService.EnrollmentView adjust(Authentication auth, @PathVariable Long id,
                                                  @Valid @RequestBody AdjustRequest request) {
        return admission.adjust(actor(auth), id, request.extraMinutes(), request.note(), request.normGroup(), request.age());
    }

    @DeleteMapping("/enrollments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication auth, @PathVariable Long id) {
        admission.revoke(actor(auth), id);
    }

    @PostMapping("/enrollments/{id}/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resend(Authentication auth, @PathVariable Long id) {
        admission.resendInvitation(actor(auth), id);
    }

    private static Long actor(Authentication auth) {
        return Long.valueOf(auth.getName());
    }

    public record SessionRequest(@NotBlank @Size(min = 3, max = 160) String name, @NotNull Instant opensAt,
                                 @NotNull Instant closesAt, Integer timeLimitMinutes,
                                 @Size(max = 500) String instructions) {
        AdmissionService.SessionInput toInput() {
            return new AdmissionService.SessionInput(name, opensAt, closesAt, timeLimitMinutes, instructions);
        }
    }

    public record EnrollRequest(@NotNull @Size(min = 1, max = 500) List<AdmissionService.ApplicantInput> applicants) {}

    public record AdjustRequest(int extraMinutes, @Size(max = 300) String note, String normGroup, Integer age) {}
}
