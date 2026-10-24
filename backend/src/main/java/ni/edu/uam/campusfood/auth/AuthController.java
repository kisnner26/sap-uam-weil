package ni.edu.uam.campusfood.auth;

import jakarta.validation.Valid;
import ni.edu.uam.campusfood.auth.dto.AuthFlowResponse;
import ni.edu.uam.campusfood.auth.dto.LoginRequest;
import ni.edu.uam.campusfood.auth.dto.MoodleLoginRequest;
import ni.edu.uam.campusfood.auth.dto.RegisterRequest;
import ni.edu.uam.campusfood.auth.dto.UserDto;
import ni.edu.uam.campusfood.auth.dto.MfaVerifyRequest;
import ni.edu.uam.campusfood.security.JwtAuthFilter;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository users;
    private final boolean secureCookie;

    public AuthController(AuthService authService, UserRepository users,
                          @org.springframework.beans.factory.annotation.Value("${app.session-cookie.secure:false}")
                          boolean secureCookie) {
        this.authService = authService;
        this.users = users;
        this.secureCookie = secureCookie;
    }

    /** Login con cuenta UAM (Moodle). Body: { cif, password }. */
    @PostMapping("/moodle-login")
    public ResponseEntity<AuthFlowResponse> moodleLogin(@Valid @RequestBody MoodleLoginRequest req,
                                                    HttpServletRequest request) {
        return withSessionCookie(authService.moodleLogin(req), request, HttpStatus.OK);
    }

    /** Registro manual de externos. Body: { fullName, email, password, cedula }. */
    @PostMapping("/register")
    public ResponseEntity<AuthFlowResponse> register(@Valid @RequestBody RegisterRequest req,
                                                 HttpServletRequest request) {
        return withSessionCookie(authService.register(req), request, HttpStatus.CREATED);
    }

    /** Login manual con correo + contrasena. */
    @PostMapping("/login")
    public ResponseEntity<AuthFlowResponse> login(@Valid @RequestBody LoginRequest req,
                                              HttpServletRequest request) {
        return withSessionCookie(authService.login(req), request, HttpStatus.OK);
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<AuthFlowResponse> verifyMfa(@Valid @RequestBody MfaVerifyRequest req,
                                                      HttpServletRequest request) {
        return withSessionCookie(authService.verifyMfa(req), request, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthFlowResponse> refresh(Authentication authentication,
                                                    HttpServletRequest request) {
        return withSessionCookie(authService.refresh(Long.valueOf(authentication.getName())), request, HttpStatus.OK);
    }

    /** Invalida en el servidor todos los tokens actuales y borra la cookie. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            authService.logout(Long.valueOf(authentication.getName()));
        }
        ResponseCookie cookie = ResponseCookie.from(JwtAuthFilter.SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookie || request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    /** Devuelve el usuario autenticado a partir del JWT (Authorization: Bearer). */
    @GetMapping("/me")
    public UserDto me(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        User user = users.findById(userId)
                .orElseThrow(() -> new AuthException(AuthException.Kind.BAD_CREDENTIALS, "Sesion invalida"));
        return UserDto.from(user);
    }

    private ResponseEntity<AuthFlowResponse> withSessionCookie(AuthFlowResponse response,
                                                           HttpServletRequest request,
                                                           HttpStatus status) {
        if (response.mfaRequired()) return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        ResponseCookie cookie = ResponseCookie.from(JwtAuthFilter.SESSION_COOKIE, response.token())
                .httpOnly(true)
                .secure(secureCookie || request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(response.expiresIn())
                .build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }
}
