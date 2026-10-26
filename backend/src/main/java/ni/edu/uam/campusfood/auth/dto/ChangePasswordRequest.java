package ni.edu.uam.campusfood.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(

        @NotBlank(message = "La contraseña actual es obligatoria")
        String currentPassword,

        @NotBlank(message = "La contraseña nueva es obligatoria")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        String newPassword
) {
}
