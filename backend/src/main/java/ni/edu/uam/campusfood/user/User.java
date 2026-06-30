package ni.edu.uam.campusfood.user;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Usuario del sistema de aplicacion Weill.
 *
 * Reglas de seguridad importantes:
 * - Para usuarios MOODLE NUNCA se guarda la contrasena institucional:
 *   {@link #passwordHash} queda null. La contrasena de Moodle solo vive en
 *   memoria durante la llamada de autenticacion y se descarta.
 * - Para usuarios MANUAL se guarda unicamente el hash bcrypt de su contrasena.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_users_moodle_id", columnNames = "moodle_id")
        }
)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "email", length = 160)
    private String email;

    /** Hash bcrypt. Null para usuarios autenticados con Moodle. */
    @Column(name = "password_hash", length = 256)
    private String passwordHash;

    /** Cedula del usuario, usada en el registro manual de externos. Opcional. */
    @Column(name = "cedula", length = 32)
    private String cedula;

    /** userid de Moodle. Null para usuarios manuales; unico para evitar duplicados. */
    @Column(name = "moodle_id")
    private Integer moodleId;

    /** URL de la foto de perfil (de Moodle o subida por el usuario). */
    @Column(name = "picture_url", length = 512)
    private String pictureUrl;

    /** Carrera/programa, si Moodle lo expone en el perfil. Nullable. */
    @Column(name = "career", length = 160)
    private String career;

    @Convert(converter = AuthProviderConverter.class)
    @Column(name = "auth_provider", nullable = false, length = 16)
    private AuthProvider authProvider;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role = Role.CLIENTE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // JPA
    }

    private User(Long id, String fullName, String email, String passwordHash, String cedula,
                 Integer moodleId, String pictureUrl, String career, AuthProvider authProvider, Role role) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.cedula = cedula;
        this.moodleId = moodleId;
        this.pictureUrl = pictureUrl;
        this.career = blankToNull(career);
        this.authProvider = authProvider;
        this.role = role;
    }

    /** Crea un usuario MOODLE a partir del perfil devuelto por get_site_info. */
    public static User fromMoodle(Integer moodleId, String fullName, String email, String pictureUrl, String career) {
        return new User(null, fullName, email, null, null, moodleId, pictureUrl, career,
                AuthProvider.MOODLE, Role.CLIENTE);
    }

    /** Crea un usuario MANUAL (externo) con su hash bcrypt ya calculado. */
    public static User manual(String fullName, String email, String passwordHash, String cedula) {
        return new User(null, fullName, email, passwordHash, cedula, null, null, null,
                AuthProvider.MANUAL, Role.CLIENTE);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Refresca los datos de perfil que pueden cambiar en Moodle. */
    public void syncMoodleProfile(String fullName, String email, String pictureUrl, String career) {
        this.fullName = fullName;
        this.email = email;
        this.pictureUrl = pictureUrl;
        String normalizedCareer = blankToNull(career);
        if (normalizedCareer != null) {
            this.career = normalizedCareer;
        }
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getCedula() {
        return cedula;
    }

    public Integer getMoodleId() {
        return moodleId;
    }

    public String getPictureUrl() {
        return pictureUrl;
    }

    public String getCareer() {
        return career;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? null : cleaned;
    }
}
