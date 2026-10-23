package ni.edu.uam.campusfood.assessment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/attempts")
public class AssessmentController {
    private final AssessmentService service;

    public AssessmentController(AssessmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<AssessmentService.AttemptSummary> list(Authentication authentication) {
        return service.list(userId(authentication));
    }

    @PostMapping
    public AssessmentService.AttemptView open(Authentication authentication,
                                              @Valid @RequestBody OpenAttemptRequest request) {
        return service.open(userId(authentication), request.testId(), request.testVersion(), request.leaseToken());
    }

    @GetMapping("/{attemptId}")
    public AssessmentService.AttemptView get(Authentication authentication, @PathVariable Long attemptId) {
        return service.get(userId(authentication), attemptId);
    }

    @PostMapping("/{attemptId}/lease")
    public AssessmentService.AttemptView renew(Authentication authentication, @PathVariable Long attemptId,
                                               @Valid @RequestBody LeaseRequest request) {
        return service.renew(userId(authentication), attemptId, request.leaseToken(), request.expectedVersion());
    }

    @PutMapping("/{attemptId}/responses/{itemIndex}")
    public AssessmentService.AttemptView answer(Authentication authentication, @PathVariable Long attemptId,
                                                @PathVariable int itemIndex,
                                                @Valid @RequestBody AnswerRequest request) {
        return service.answer(userId(authentication), attemptId, itemIndex, request.option(),
                request.leaseToken(), request.expectedVersion());
    }

    @PostMapping("/{attemptId}/submit")
    public AssessmentService.AttemptView submit(Authentication authentication, @PathVariable Long attemptId,
                                                @Valid @RequestBody SubmitRequest request) {
        return service.submit(userId(authentication), attemptId, request.leaseToken(),
                request.expectedVersion(), request.answers(), request.age(), request.normGroup());
    }

    private static Long userId(Authentication authentication) {
        return Long.valueOf(authentication.getName());
    }

    public record OpenAttemptRequest(@NotBlank String testId, @Min(1) int testVersion, String leaseToken) {}
    public record LeaseRequest(@NotBlank String leaseToken, @Min(0) long expectedVersion) {}
    public record AnswerRequest(@Min(1) @Max(8) int option, @NotBlank String leaseToken,
                                @Min(0) long expectedVersion) {}
    public record SubmitRequest(@NotBlank String leaseToken, @Min(0) long expectedVersion,
                                @jakarta.validation.constraints.Size(min = 60, max = 60)
                                List<@jakarta.validation.constraints.NotNull @Min(1) @Max(8) Integer> answers,
                                @Min(3) @Max(120) Integer age,
                                @jakarta.validation.constraints.Pattern(regexp = "^(7-16|AN|AD|UN)$") String normGroup) {}
}
