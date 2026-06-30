package ni.edu.uam.campusfood.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body de POST /api/auth/register (registro manual de externos: correo + cedula). */
public record RegisterRequest(

        @NotBlank(message = "El nombre completo es obligatorio")
        @Size(max = 160)
        String fullName,

        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "Correo invalido")
        @Size(max = 160)
        String email,

        @NotBlank(message = "La contrasena es obligatoria")
        @Size(min = 8, max = 72, message = "La contrasena debe tener entre 8 y 72 caracteres")
        String password,

        @Size(max = 32)
        String cedula
) {
}
