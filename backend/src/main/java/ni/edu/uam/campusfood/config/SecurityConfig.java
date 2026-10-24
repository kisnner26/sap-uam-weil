package ni.edu.uam.campusfood.config;

import ni.edu.uam.campusfood.security.AuthRateLimitFilter;
import ni.edu.uam.campusfood.security.JwtAuthFilter;
import ni.edu.uam.campusfood.security.DatabaseLoginThrottleService;
import ni.edu.uam.campusfood.security.RestAccessDeniedHandler;
import ni.edu.uam.campusfood.security.RestAuthEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Seguridad stateless basada en JWT propio. El frontend llama via CORS al
 * backend; el backend a su vez llama a Moodle server-to-server (sin CORS).
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RestAuthEntryPoint authEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final String allowedOrigins;
    private final Environment env;
    private final DatabaseLoginThrottleService loginThrottle;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          RestAuthEntryPoint authEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          DatabaseLoginThrottleService loginThrottle,
                          Environment env,
                          @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:}")
                          String allowedOrigins) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authEntryPoint = authEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.loginThrottle = loginThrottle;
        this.allowedOrigins = allowedOrigins;
        this.env = env;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        boolean devProfile = Arrays.asList(env.getActiveProfiles()).contains("dev");

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authEntryPoint).accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/api/auth/moodle-login",
                            "/api/auth/login",
                            "/api/auth/register",
                            "/api/auth/mfa/verify",
                            "/api/auth/logout",
                            "/api/privacy/notice",
                            "/actuator/health"
                    ).permitAll();
                    if (devProfile) {
                        // Consola H2 solo en desarrollo.
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    // Resultados con puntaje: solo personal y administracion.
                    auth.requestMatchers("/api/staff/**").hasAnyRole("STAFF", "ADMIN");
                    auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(authRateLimitFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        if (devProfile) {
            // La consola H2 se sirve en frames.
            http.headers(h -> h.frameOptions(fo -> fo.sameOrigin()));
        }

        return http.build();
    }

    /** No es un bean: asi Spring Boot no lo registra tambien como filtro de servlet. */
    private AuthRateLimitFilter authRateLimitFilter() {
        return new AuthRateLimitFilter(loginThrottle);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        }
        config.setAllowedMethods(List.of(
                HttpMethod.GET.name(), HttpMethod.POST.name(),
                HttpMethod.PUT.name(), HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
