package ni.edu.uam.campusfood.config;

import ni.edu.uam.campusfood.SapUamApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * En produccion el secreto del JWT, los origenes CORS y la base de datos no
 * tienen valor por defecto: sin ellos la aplicacion debe negarse a arrancar,
 * en vez de iniciar con una clave publica.
 */
class ProdProfileFailFastTest {

    @Test
    void sinSecretoNiOrigenesElPerfilProdNoArranca() {
        assumeTrue(System.getenv("APP_JWT_SECRET") == null && System.getenv("APP_CORS_ORIGINS") == null
                && System.getenv("DB_URL") == null, "Las variables de produccion estan definidas en este entorno");

        assertThatThrownBy(() -> new SpringApplicationBuilder(SapUamApplication.class)
                .profiles("prod")
                .web(WebApplicationType.NONE)
                .run())
                .hasStackTraceContaining("faltan variables de entorno obligatorias")
                .hasStackTraceContaining("APP_JWT_SECRET");
    }
}
