package ni.edu.uam.campusfood.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import ni.edu.uam.campusfood.user.UserRepository;

/**
 * Lee el JWT del header Authorization: Bearer ... y, si es valido, coloca la
 * autenticacion en el SecurityContext. Las rutas publicas (login/registro) no
 * requieren token; ahi simplemente no se establece autenticacion.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE = "sap_uam_session";

    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtService.parseSession(token);
                long sessionVersion = claims.get("sessionVersion", Number.class).longValue();
                var user = users.findById(Long.valueOf(claims.getSubject()))
                        .filter(candidate -> candidate.isActive()
                                && candidate.getSessionVersion() == sessionVersion)
                        .orElseThrow(() -> new JwtException("Sesion revocada"));
                if (user.getRole() != ni.edu.uam.campusfood.user.Role.CLIENTE
                        && !Boolean.TRUE.equals(claims.get("mfa", Boolean.class))) {
                    throw new JwtException("Segundo factor requerido");
                }
                if (user.isMustChangePassword() && !isPasswordChangeAllowed(request)) {
                    SecurityContextHolder.clearContext();
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Debes cambiar tu contraseña temporal antes de continuar.\",\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
                    return;
                }
                String role = user.getRole().name();
                var authority = new SimpleGrantedAuthority("ROLE_" + role);
                var authentication = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(), null, List.of(authority));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // Token invalido o expirado: seguimos sin autenticar; los
                // endpoints protegidos responderan 401 por la config de seguridad.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /** Con la contraseña temporal solo se puede cambiarla, ver el propio perfil o salir. */
    private static boolean isPasswordChangeAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/auth/password".equals(path) || "/api/auth/logout".equals(path) || "/api/auth/me".equals(path);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
