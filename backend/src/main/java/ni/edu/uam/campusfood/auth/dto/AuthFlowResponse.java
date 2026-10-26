package ni.edu.uam.campusfood.auth.dto;

/** Resultado del primer factor o de la verificacion MFA. */
public record AuthFlowResponse(
        String token,
        String tokenType,
        long expiresIn,
        UserDto user,
        boolean mfaRequired,
        boolean mfaEnrollmentRequired,
        String challengeToken,
        String enrollmentSecret,
        String otpauthUri,
        String qrSvg
) {
    public static AuthFlowResponse authenticated(String token, long expiresIn, UserDto user) {
        return new AuthFlowResponse(token, "Bearer", expiresIn, user, false, false, null, null, null, null);
    }

    public static AuthFlowResponse challenge(String token, boolean enrollment, String secret, String uri, String qrSvg) {
        return new AuthFlowResponse(null, null, 0, null, true, enrollment, token, secret, uri, qrSvg);
    }
}
