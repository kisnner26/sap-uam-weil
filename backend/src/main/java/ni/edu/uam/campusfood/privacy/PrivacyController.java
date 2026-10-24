package ni.edu.uam.campusfood.privacy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/privacy")
public class PrivacyController {
    private final PrivacyService privacy;

    public PrivacyController(PrivacyService privacy) {
        this.privacy = privacy;
    }

    /** Publico: se puede leer antes de iniciar sesion. */
    @GetMapping("/notice")
    public PrivacyService.Notice notice() {
        return privacy.notice();
    }

    @PostMapping("/consent")
    public void accept(Authentication authentication, @Valid @RequestBody ConsentRequest request) {
        privacy.accept(Long.valueOf(authentication.getName()), request.version());
    }

    public record ConsentRequest(@Min(1) int version) {}
}
