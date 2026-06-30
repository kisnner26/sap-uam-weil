package ni.edu.uam.campusfood.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import ni.edu.uam.campusfood.user.UserRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el flujo /api/auth/moodle-login contra un Moodle simulado:
 * - login exitoso crea el usuario y devuelve JWT propio
 * - credenciales invalidas -> 401
 * - Moodle respondiendo HTML -> 503 (mantenimiento)
 *
 * Usa MockWebServer como base-url de Moodle (app.moodle.base-url se sobreescribe
 * dinamicamente apuntando al servidor mock).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MoodleLoginIntegrationTest {

    static final MockWebServer moodle = new MockWebServer();

    static {
        try {
            moodle.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void moodleProps(DynamicPropertyRegistry registry) {
        // Apunta el RestClient de Moodle al servidor simulado.
        registry.add("app.moodle.base-url",
                () -> moodle.url("/grado").toString().replaceAll("/$", ""));
    }

    @AfterAll
    static void stopMoodle() throws IOException {
        moodle.shutdown();
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserRepository users;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void cleanDb() {
        users.deleteAll();
    }

    @AfterEach
    void drain() throws InterruptedException {
        // Consume requests pendientes para que no contaminen el siguiente test.
        while (moodle.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
            // no-op
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> post(String path, String body) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url(path), new org.springframework.http.HttpEntity<>(body, headers), String.class);
    }

    @Test
    void loginExitoso_creaUsuarioYDevuelveJwt() throws Exception {
        moodle.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"token\":\"moodle-tok-123\"}"));
        moodle.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"userid\":4567,\"fullname\":\"Ana Lopez\",\"useremail\":\"ana@uam.edu.ni\",\"userpictureurl\":\"https://x/p.png\",\"profilefield_carrera\":\"Medicina General\"}"));

        ResponseEntity<String> resp = post("/api/auth/moodle-login",
                "{\"cif\":\"123456\",\"password\":\"secreta\"}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var json = mapper.readTree(resp.getBody());
        assertThat(json.get("token").asText()).isNotBlank();
        assertThat(json.get("user").get("moodleId").asInt()).isEqualTo(4567);
        assertThat(json.get("user").get("authProvider").asText()).isEqualTo("moodle");
        assertThat(json.get("user").get("career").asText()).isEqualTo("Medicina General");
        assertThat(resp.getHeaders().getFirst(org.springframework.http.HttpHeaders.SET_COOKIE))
                .contains("sap_uam_session=")
                .contains("HttpOnly");
        assertThat(users.findByMoodleId(4567)).isPresent();
        // La contrasena de Moodle NUNCA se persiste.
        assertThat(users.findByMoodleId(4567).get().getPasswordHash()).isNull();
    }

    @Test
    void credencialesInvalidas_devuelve401() {
        moodle.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Invalid login\",\"errorcode\":\"invalidlogin\"}"));

        ResponseEntity<String> resp = post("/api/auth/moodle-login",
                "{\"cif\":\"123456\",\"password\":\"mala\"}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(users.count()).isZero();
    }

    @Test
    void moodleResponseHtml_devuelve503Mantenimiento() {
        moodle.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html><body>Site maintenance</body></html>"));

        ResponseEntity<String> resp = post("/api/auth/moodle-login",
                "{\"cif\":\"123456\",\"password\":\"secreta\"}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).contains("mantenimiento");
    }
}
