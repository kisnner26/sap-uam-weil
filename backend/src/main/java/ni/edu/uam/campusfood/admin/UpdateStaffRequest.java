package ni.edu.uam.campusfood.admin;

import jakarta.validation.constraints.NotNull;
import ni.edu.uam.campusfood.user.Role;

public record UpdateStaffRequest(@NotNull Role role, boolean active) {}
