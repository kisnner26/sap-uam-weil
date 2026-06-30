package ni.edu.uam.campusfood.auth.dto;

/** Respuesta de un login/registro exitoso: sesion propia + datos del usuario. */
public record AuthResponse(
        String token,
        String tokenType,
        long expiresIn,
        UserDto user
) {
    public static AuthResponse of(String token, long expiresInSeconds, UserDto user) {
        return new AuthResponse(token, "Bearer", expiresInSeconds, user);
    }
}
