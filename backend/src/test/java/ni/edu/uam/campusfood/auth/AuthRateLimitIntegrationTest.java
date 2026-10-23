package ni.edu.uam.campusfood.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/** Tras agotar el cupo de intentos, el login responde 429 en vez de seguir probando contrasenas. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.security.auth-rate-limit.max-requests=3", "app.security.auth-rate-limit.window-seconds=300"})
class AuthRateLimitIntegrationTest {
    @Autowired TestRestTemplate http;

    private HttpEntity<String> badLogin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>("{\"email\":\"nadie@example.com\",\"password\":\"incorrecta\"}", headers);
    }

    @Test
    void elCuartoIntentoSeguidoRecibe429ConRetryAfter() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            var response = http.postForEntity("/api/auth/login", badLogin(), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        var blocked = http.postForEntity("/api/auth/login", badLogin(), String.class);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(blocked.getBody()).contains("Demasiados intentos");
    }
}
