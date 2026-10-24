package ni.edu.uam.campusfood.assessment;

import ni.edu.uam.campusfood.security.JwtService;
import ni.edu.uam.campusfood.user.User;
import ni.edu.uam.campusfood.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El puntaje solo lo ve el personal: el aspirante rinde y entrega sin recibir
 * puntaje, percentil ni CI, y el listado de resultados exige rol STAFF o ADMIN.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffResultsSecurityTest {
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired ni.edu.uam.campusfood.AdmissionFixtures fixtures;
    @Autowired AssessmentAttemptRepository attempts;
    @Autowired AssessmentResponseRepository responses;
    @Autowired AssessmentEventRepository events;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private String aspiranteToken;
    private String staffToken;
    private String adminToken;

    @BeforeEach
    void prepare() {
        fixtures.clean();
        User aspirante = users.save(User.manual("Ana López", "ana@example.com", "hash", "001"));
        fixtures.enable(aspirante, fixtures.openSession(aspirante.getId()), 0, "UN", 19);
        User staff = users.save(User.manual("Sofía Evaluadora", "sofia@example.com", "hash", "002"));
        User admin = users.save(User.manual("Raúl Admin", "raul@example.com", "hash", "003"));
        jdbc.update("update users set role = 'STAFF' where id = ?", staff.getId());
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.getId());
        staff = users.findById(staff.getId()).orElseThrow();
        admin = users.findById(admin.getId()).orElseThrow();
        staff.enableMfa();
        admin.enableMfa();
        users.save(staff);
        users.save(admin);
        aspiranteToken = jwt.issue(users.findById(aspirante.getId()).orElseThrow()).token();
        staffToken = jwt.issue(staff).token();
        adminToken = jwt.issue(admin).token();
    }

    private HttpEntity<Object> request(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    @SuppressWarnings("unchecked")
    @Test
    void elAspiranteEntregaSinRecibirPuntajeYElPersonalLoVe() {
        var opened = http.exchange("/api/attempts", HttpMethod.POST,
                request(aspiranteToken, Map.of("testId", "weil-form-a", "testVersion", 1)), Map.class).getBody();
        Number attemptId = (Number) opened.get("id");

        var event = http.exchange("/api/attempts/" + attemptId + "/events", HttpMethod.POST,
                request(aspiranteToken, Map.of(
                        "leaseToken", opened.get("leaseToken"),
                        "type", "visibility-hidden",
                        "detail", "La pestaña dejó de estar visible.")), Void.class);
        assertThat(event.getStatusCode().is2xxSuccessful()).isTrue();

        var submitted = http.exchange("/api/attempts/" + attemptId + "/submit", HttpMethod.POST,
                request(aspiranteToken, Map.of(
                        "leaseToken", opened.get("leaseToken"),
                        "expectedVersion", opened.get("version"),
                        "answers", Collections.nCopies(60, 2),
                        "age", 19, "normGroup", "UN")), String.class);

        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody()).contains("SUBMITTED")
                .doesNotContain("directScore", "pageScores", "percentile");

        var reread = http.exchange("/api/attempts/" + attemptId, HttpMethod.GET, request(aspiranteToken, null), String.class);
        assertThat(reread.getBody()).doesNotContain("directScore", "pageScores");

        for (String token : new String[] {staffToken, adminToken}) {
            var listing = http.exchange("/api/staff/attempts", HttpMethod.GET, request(token, null), String.class);
            assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(listing.getBody()).contains("Ana López", "\"directScore\":10", "\"normGroup\":\"UN\"", "\"age\":19",
                    "visibility-hidden", "La pestaña dejó de estar visible.");
        }
    }

    @Test
    void elMaterialSoloSeEntregaAutenticadoYDeFormaProgresiva() {
        var opened = http.exchange("/api/attempts", HttpMethod.POST,
                request(aspiranteToken, Map.of("testId", "weil-form-a", "testVersion", 1)), Map.class).getBody();
        Number attemptId = (Number) opened.get("id");

        var anonymous = http.exchange("/api/attempts/" + attemptId + "/material/0/base",
                HttpMethod.GET, request(null, null), byte[].class);
        var current = http.exchange("/api/attempts/" + attemptId + "/material/0/base",
                HttpMethod.GET, request(aspiranteToken, null), byte[].class);
        var future = http.exchange("/api/attempts/" + attemptId + "/material/2/base",
                HttpMethod.GET, request(aspiranteToken, null), String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current.getHeaders().getCacheControl()).contains("no-store");
        assertThat(current.getBody()).isNotEmpty();
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void elListadoDeResultadosExigeRolDePersonal() {
        var anonymous = http.exchange("/api/staff/attempts", HttpMethod.GET, request(null, null), String.class);
        var aspirante = http.exchange("/api/staff/attempts", HttpMethod.GET, request(aspiranteToken, null), String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(aspirante.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var norms = http.exchange("/api/staff/attempts/norms", HttpMethod.GET, request(staffToken, null), String.class);
        assertThat(norms.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(norms.getBody()).contains("\"p\":90", "\"UN\":57");
    }
}
