package ni.edu.uam.campusfood.admin;

import jakarta.validation.Valid;
import ni.edu.uam.campusfood.audit.AuditService;
import ni.edu.uam.campusfood.auth.dto.UserDto;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminUserService staff;
    private final AuditService audit;

    public AdminController(AdminUserService staff, AuditService audit) {
        this.staff = staff;
        this.audit = audit;
    }

    @GetMapping("/users")
    public List<UserDto> users(Authentication auth) {
        audit.record(id(auth), "VIEW_STAFF", "USER_COLLECTION", null, null);
        return staff.listStaff();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@Valid @RequestBody CreateStaffRequest request, Authentication auth) {
        return staff.create(id(auth), request);
    }

    @PatchMapping("/users/{userId}")
    public UserDto update(@PathVariable Long userId, @Valid @RequestBody UpdateStaffRequest request,
                          Authentication auth) {
        return staff.update(id(auth), userId, request);
    }

    @PostMapping("/users/{userId}/mfa-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetMfa(@PathVariable Long userId, Authentication auth) {
        staff.resetMfa(id(auth), userId);
    }

    @GetMapping("/audit")
    public List<AuditService.AuditView> audit(Authentication auth) {
        audit.record(id(auth), "VIEW_AUDIT", "AUDIT_COLLECTION", null, null);
        return audit.recent();
    }

    private Long id(Authentication auth) { return Long.valueOf(auth.getName()); }
}
