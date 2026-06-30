package ni.edu.uam.campusfood.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Body de POST /api/auth/login (login manual con correo + contrasena). */
public record LoginRequest(

        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "Correo invalido")
        String email,

        @NotBlank(message = "La contrasena es obligatoria")
        String password
) {
}
