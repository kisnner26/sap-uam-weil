package ni.edu.uam.campusfood.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body de POST /api/auth/moodle-login. */
public record MoodleLoginRequest(

        @NotBlank(message = "El CIF es obligatorio")
        @Pattern(regexp = "\\d{4,12}", message = "El CIF debe ser numerico")
        String cif,

        @NotBlank(message = "La contrasena es obligatoria")
        String password
) {
}
