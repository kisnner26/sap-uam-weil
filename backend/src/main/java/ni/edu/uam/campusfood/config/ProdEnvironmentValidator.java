package ni.edu.uam.campusfood.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Con el perfil prod, la aplicacion se niega a arrancar si falta alguna
 * variable obligatoria. Spring deja un {@code ${VARIABLE}} sin definir como
 * texto literal y sigue adelante, asi que sin esta validacion podria iniciar
 * con una configuracion invalida en silencio.
 */
public class ProdEnvironmentValidator implements EnvironmentPostProcessor, Ordered {

    private static final Map<String, String> REQUIRED = requiredProperties();

    private static Map<String, String> requiredProperties() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("app.jwt.secret", "APP_JWT_SECRET");
        required.put("app.cors.allowed-origins", "APP_CORS_ORIGINS");
        required.put("spring.datasource.url", "DB_URL");
        required.put("spring.datasource.username", "DB_USER");
        required.put("spring.datasource.password", "DB_PASSWORD");
        return required;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))) {
            return;
        }
        StringBuilder missing = new StringBuilder();
        REQUIRED.forEach((property, variable) -> {
            String value;
            try {
                value = environment.getProperty(property);
            } catch (IllegalArgumentException unresolvedPlaceholder) {
                value = null;
            }
            if (value == null || value.isBlank() || value.contains("${")) {
                missing.append(missing.isEmpty() ? "" : ", ").append(variable);
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Perfil prod: faltan variables de entorno obligatorias: " + missing + ".");
        }
    }

    /** Debe correr despues de que Spring cargue application.yml. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
