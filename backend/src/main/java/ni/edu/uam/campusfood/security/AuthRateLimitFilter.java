package ni.edu.uam.campusfood.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

/**
 * Frena la fuerza bruta sobre los endpoints de acceso. Los de login reenvian
 * las credenciales a Moodle, asi que sin limite el backend serviria de
 * intermediario para probar contrasenas. Se aplica por IP de origen.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/auth/login", "/api/auth/moodle-login", "/api/auth/register");

    private static final String BODY = """
            {"status":429,"error":"Too Many Requests","message":"Demasiados intentos. Espera unos minutos antes de volver a intentar."}""";

    private final RequestRateLimiter limiter;

    public AuthRateLimitFilter(RequestRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !PROTECTED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Instant now = Instant.now();
        String key = request.getRemoteAddr();
        if (!limiter.tryAcquire(key, now)) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(limiter.retryAfterSeconds(key, now)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
