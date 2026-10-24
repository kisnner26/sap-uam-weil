package ni.edu.uam.campusfood.admin;

import ni.edu.uam.campusfood.audit.AuditService;
import ni.edu.uam.campusfood.user.Role;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Crea la primera cuenta administradora sin editar la BD manualmente. */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final String email;
    private final String password;
    private final String name;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwords, AuditService audit,
            @Value("${app.bootstrap-admin.email:}") String email,
            @Value("${app.bootstrap-admin.password:}") String password,
            @Value("${app.bootstrap-admin.name:Administrador SAP-UAM}") String name) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRole(Role.ADMIN) || email.isBlank() || password.isBlank()) return;
        if (password.length() < 12) throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_PASSWORD requiere al menos 12 caracteres");
        User admin = users.save(User.staff(name.trim(), email.trim().toLowerCase(), passwords.encode(password), Role.ADMIN));
        audit.record(admin.getId(), "BOOTSTRAP_ADMIN", "USER", admin.getId(), "primera cuenta administrativa");
    }
}
