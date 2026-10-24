package ni.edu.uam.campusfood.admin;

import ni.edu.uam.campusfood.audit.AuditService;
import ni.edu.uam.campusfood.auth.AuthException;
import ni.edu.uam.campusfood.auth.dto.UserDto;
import ni.edu.uam.campusfood.user.Role;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminUserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public AdminUserService(UserRepository users, PasswordEncoder passwords, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<UserDto> listStaff() {
        return users.findAllByRoleInOrderByFullNameAsc(List.of(Role.STAFF, Role.ADMIN))
                .stream().map(UserDto::from).toList();
    }

    @Transactional
    public UserDto create(Long actorId, CreateStaffRequest request) {
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new AuthException(AuthException.Kind.EMAIL_TAKEN, "Ya existe una cuenta con ese correo");
        }
        User user = users.save(User.staff(clean(request.fullName()), request.email().trim().toLowerCase(),
                passwords.encode(request.temporaryPassword()), request.role()));
        audit.record(actorId, "CREATE_STAFF", "USER", user.getId(), "rol=" + user.getRole());
        return UserDto.from(user);
    }

    @Transactional
    public UserDto update(Long actorId, Long userId, UpdateStaffRequest request) {
        if (request.role() != Role.STAFF && request.role() != Role.ADMIN) {
            throw new IllegalArgumentException("El rol debe ser STAFF o ADMIN");
        }
        if (actorId.equals(userId) && (!request.active() || request.role() != Role.ADMIN)) {
            throw new IllegalArgumentException("No podes quitar tu propio acceso administrativo");
        }
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Cuenta de personal no encontrada"));
        if (user.getRole() == Role.CLIENTE) throw new IllegalArgumentException("La cuenta no pertenece al personal");
        user.changeAccess(request.role(), request.active());
        users.save(user);
        audit.record(actorId, "CHANGE_STAFF_ACCESS", "USER", userId,
                "rol=" + request.role() + ", activa=" + request.active());
        return UserDto.from(user);
    }

    @Transactional
    public void resetMfa(Long actorId, Long userId) {
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Cuenta de personal no encontrada"));
        if (user.getRole() == Role.CLIENTE) throw new IllegalArgumentException("La cuenta no pertenece al personal");
        user.resetMfa();
        users.save(user);
        audit.record(actorId, "RESET_MFA", "USER", userId, "nuevo enrolamiento requerido");
    }

    private String clean(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
