package ni.edu.uam.campusfood.auth.dto;

import ni.edu.uam.campusfood.user.User;

/** Vista publica del usuario que devuelve la API (sin datos sensibles). */
public record UserDto(
        Long id,
        String fullName,
        String email,
        String pictureUrl,
        String career,
        String role,
        String authProvider,
        Integer moodleId
) {
    public static UserDto from(User user) {
        return new UserDto(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPictureUrl(),
                user.getCareer(),
                user.getRole().name(),
                user.getAuthProvider().value(),
                user.getMoodleId()
        );
    }
}
