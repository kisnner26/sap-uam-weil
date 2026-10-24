package ni.edu.uam.campusfood.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ni.edu.uam.campusfood.user.Role;

public record CreateStaffRequest(
        @NotBlank @Size(min = 5, max = 160) String fullName,
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Size(min = 12, max = 128) String temporaryPassword,
        @NotNull Role role
) {}
