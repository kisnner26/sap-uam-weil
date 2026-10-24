package ni.edu.uam.campusfood.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ni.edu.uam.campusfood.AdmissionFixtures;
import ni.edu.uam.campusfood.audit.AuditEventRepository;
import ni.edu.uam.campusfood.notification.NotificationOutboxRepository;
import ni.edu.uam.campusfood.security.JwtService;
import ni.edu.uam.campusfood.user.Role;
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
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdmissionApiIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired AdmissionFixtures fixtures;
    @Autowired NotificationOutboxRepository outbox;
    @Autowired AuditEventRepository audit;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String staffToken;
    private String applicantToken;

    @BeforeEach
    void prepare() {
        audit.deleteAll();
        fixtures.clean();
        User staff = User.staff("Admision UAM", "admision@uam.edu.ni", "hash", Role.STAFF);
        staff.enableMfa();
        staff = users.save(staff);
        User applicant = users.save(User.manual("Ana López", "ana@example.com", "hash", "001"));
        staffToken = jwt.issue(staff).token();
        applicantToken = jwt.issue(applicant).token();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String sessionBody(Duration opensIn, Duration closesIn) {
        Instant now = Instant.now();
        return "{\"name\":\"Convocatoria de octubre\",\"opensAt\":\"" + now.plus(opensIn) + "\",\"closesAt\":\"" + now.plus(closesIn)
                + "\",\"timeLimitMinutes\":20,\"instructions\":\"Traer cédula\"}";
    }

    @Test
    void soloElPersonalGestionaConvocatoriasYHabilitaciones() throws Exception {
        assertThat(call(HttpMethod.GET, "/api/staff/sessions", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, "/api/staff/sessions", applicantToken, sessionBody(Duration.ZERO, Duration.ofHours(2)))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var created = call(HttpMethod.POST, "/api/staff/sessions", staffToken, sessionBody(Duration.ofHours(-1), Duration.ofHours(3)));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long sessionId = json.readTree(created.getBody()).get("id").asLong();
        assertThat(json.readTree(created.getBody()).get("state").asText()).isEqualTo("OPEN");

        var badWindow = call(HttpMethod.POST, "/api/staff/sessions", staffToken, sessionBody(Duration.ofHours(3), Duration.ofHours(1)));
        assertThat(badWindow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var enrolled = call(HttpMethod.POST, "/api/staff/sessions/" + sessionId + "/enrollments", staffToken,
                "{\"applicants\":[{\"email\":\"ana@example.com\",\"fullName\":\"Ana López\",\"extraMinutes\":10},{\"email\":\"mal\"}]}");
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode result = json.readTree(enrolled.getBody());
        assertThat(result.get("created")).hasSize(1);
        assertThat(result.get("skipped")).hasSize(1);
        assertThat(enrolled.getBody()).doesNotContain("accessCode");
        assertThat(call(HttpMethod.GET, "/api/staff/sessions/" + sessionId + "/enrollments", staffToken, null).getBody())
                .contains("INVITED").doesNotContainIgnoringCase("hash");
    }

    @Test
    void elAspiranteCanjeaSuCodigoAceptaElAvisoYPuedeComenzar() throws Exception {
        long sessionId = json.readTree(call(HttpMethod.POST, "/api/staff/sessions", staffToken,
                sessionBody(Duration.ofHours(-1), Duration.ofHours(3))).getBody()).get("id").asLong();
        call(HttpMethod.POST, "/api/staff/sessions/" + sessionId + "/enrollments", staffToken,
                "{\"applicants\":[{\"email\":\"ana@example.com\",\"fullName\":\"Ana López\"}]}");
        Matcher m = Pattern.compile("([A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4})").matcher(outbox.findAll().get(0).getBody());
        assertThat(m.find()).isTrue();

        assertThat(json.readTree(call(HttpMethod.GET, "/api/applicant/availability", applicantToken, null).getBody())
                .get("state").asText()).isEqualTo("NO_ENROLLMENT");

        var wrong = call(HttpMethod.POST, "/api/applicant/claim", applicantToken, "{\"code\":\"AAAA-AAAA-AAAA\"}");
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json.readTree(wrong.getBody()).get("code").asText()).isEqualTo("CODE_INVALID");

        var claimed = call(HttpMethod.POST, "/api/applicant/claim", applicantToken, "{\"code\":\"" + m.group(1) + "\"}");
        assertThat(claimed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode availability = json.readTree(claimed.getBody());
        assertThat(availability.get("state").asText()).isEqualTo("OPEN");
        assertThat(availability.get("needsConsent").asBoolean()).isTrue();

        var blocked = call(HttpMethod.POST, "/api/attempts", applicantToken, "{\"testId\":\"weil-form-a\",\"testVersion\":1}");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json.readTree(blocked.getBody()).get("code").asText()).isEqualTo("CONSENT_REQUIRED");

        var notice = call(HttpMethod.GET, "/api/privacy/notice", null, null); // publico
        assertThat(notice.getStatusCode()).isEqualTo(HttpStatus.OK);
        int version = json.readTree(notice.getBody()).get("version").asInt();
        assertThat(notice.getBody()).contains("BORRADOR PARA REVISI");
        assertThat(call(HttpMethod.POST, "/api/privacy/consent", applicantToken, "{\"version\":" + (version + 1) + "}")
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/privacy/consent", applicantToken, "{\"version\":" + version + "}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        var opened = call(HttpMethod.POST, "/api/attempts", applicantToken, "{\"testId\":\"weil-form-a\",\"testVersion\":1}");
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(opened.getBody()).get("remainingSeconds").asLong()).isBetween(1190L, 1200L);
    }

    @Test
    void exportarResultadosExigeRolYQuedaAuditado() {
        assertThat(call(HttpMethod.GET, "/api/staff/attempts/export", applicantToken, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        var export = call(HttpMethod.GET, "/api/staff/attempts/export", staffToken, null);

        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(export.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains("resultados-");
        assertThat(export.getBody()).startsWith("﻿\"Id\"");
        assertThat(audit.findAll()).extracting(e -> e.getAction()).contains("EXPORT_RESULTS");
    }

    @Test
    void anularYReaplicarRequierenPersonalYUnMotivoReal() {
        assertThat(call(HttpMethod.POST, "/api/staff/attempts/1/invalidate", applicantToken, "{\"reason\":\"intento de anulacion indebido\"}")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/staff/attempts/1/invalidate", staffToken, "{\"reason\":\"corto\"}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
