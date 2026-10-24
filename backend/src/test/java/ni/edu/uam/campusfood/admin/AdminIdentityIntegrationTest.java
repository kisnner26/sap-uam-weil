package ni.edu.uam.campusfood.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ni.edu.uam.campusfood.audit.AuditEventRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminIdentityIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired AuditEventRepository audit;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String adminToken;
    private String clientToken;
    private String staffToken;
    private Long adminId;

    @BeforeEach
    void prepare() {
        audit.deleteAll();
        users.deleteAll();
        User admin = User.staff("Administradora UAM", "admin@uam.edu.ni", passwords.encode("clave-admin-segura"), Role.ADMIN);
        admin.enableMfa();
        admin = users.save(admin);
        User staff = User.staff("Psicologa UAM", "psicologia@uam.edu.ni", passwords.encode("clave-staff-segura"), Role.STAFF);
        staff.enableMfa();
        staff = users.save(staff);
        User client = users.save(User.manual("Aspirante Uno", "aspirante@example.com", passwords.encode("clave-aspirante"), "001"));
        adminId = admin.getId();
        adminToken = jwt.issue(admin).token();
        staffToken = jwt.issue(staff).token();
        clientToken = jwt.issue(client).token();
    }

    @Test
    void losTresRolesReciben401Y403Correctos() {
        assertThat(get("/api/admin/users", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/admin/users", clientToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/admin/users", staffToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/admin/users", adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(get("/api/staff/attempts", clientToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/staff/attempts", staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/staff/attempts", adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void elAdminCreaPersonalYElPrimerAccesoActivaTotp() throws Exception {
        var created = exchange("/api/admin/users", HttpMethod.POST, adminToken, """
                {"fullName":"Nueva Psicologa","email":"nueva@uam.edu.ni",
                 "temporaryPassword":"temporal-segura-2026","role":"STAFF"}""");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var primary = exchange("/api/auth/login", HttpMethod.POST, null,
                "{\"email\":\"nueva@uam.edu.ni\",\"password\":\"temporal-segura-2026\"}");
        assertThat(primary.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode challenge = json.readTree(primary.getBody());
        assertThat(challenge.get("mfaRequired").asBoolean()).isTrue();
        assertThat(challenge.get("mfaEnrollmentRequired").asBoolean()).isTrue();
        assertThat(challenge.get("enrollmentSecret").asText()).isNotBlank();

        String code = totp(challenge.get("enrollmentSecret").asText(), Instant.now());
        String verifyBody = json.createObjectNode()
                .put("challengeToken", challenge.get("challengeToken").asText())
                .put("code", code).toString();
        var verified = exchange("/api/auth/mfa/verify", HttpMethod.POST, null, verifyBody);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode session = json.readTree(verified.getBody());
        assertThat(session.get("user").get("mfaEnabled").asBoolean()).isTrue();
        assertThat(get("/api/staff/attempts", session.get("token").asText()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void desactivarYCerrarSesionRevocaLosTokensYDejaAuditoria() throws Exception {
        var refresh = exchange("/api/auth/refresh", HttpMethod.POST, adminToken, null);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refreshed = json.readTree(refresh.getBody()).get("token").asText();

        var logout = exchange("/api/auth/logout", HttpMethod.POST, refreshed, null);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/admin/users", refreshed).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        User admin = users.findById(adminId).orElseThrow();
        admin.enableMfa();
        adminToken = jwt.issue(admin).token();
        JsonNode created = json.readTree(exchange("/api/admin/users", HttpMethod.POST, adminToken, """
                {"fullName":"Cuenta Desactivable","email":"baja@uam.edu.ni",
                 "temporaryPassword":"temporal-segura-2026","role":"STAFF"}""").getBody());
        long userId = created.get("id").asLong();
        User target = users.findById(userId).orElseThrow();
        target.enableMfa();
        users.save(target);
        String oldToken = jwt.issue(target).token();

        var disabled = exchange("/api/admin/users/" + userId, HttpMethod.PATCH, adminToken,
                "{\"role\":\"STAFF\",\"active\":false}");
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/staff/attempts", oldToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var log = get("/api/admin/audit", adminToken);
        assertThat(log.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(log.getBody()).contains("CREATE_STAFF", "CHANGE_STAFF_ACCESS", "VIEW_AUDIT");
    }

    @Test
    void cambiarElRolInvalidaLasSesionesAnteriores() {
        Long staffId = users.findByEmailIgnoreCase("psicologia@uam.edu.ni").orElseThrow().getId();

        var changed = exchange("/api/admin/users/" + staffId, HttpMethod.PATCH, adminToken,
                "{\"role\":\"ADMIN\",\"active\":true}");

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/staff/attempts", staffToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unAdministradorNoPuedeQuitarseSuPropioAccesoNiDegradarCuentasAAspirante() {
        String self = "/api/admin/users/" + adminId;
        assertThat(exchange(self, HttpMethod.PATCH, adminToken, "{\"role\":\"STAFF\",\"active\":true}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(self, HttpMethod.PATCH, adminToken, "{\"role\":\"ADMIN\",\"active\":false}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Long staffId = users.findByEmailIgnoreCase("psicologia@uam.edu.ni").orElseThrow().getId();
        assertThat(exchange("/api/admin/users/" + staffId, HttpMethod.PATCH, adminToken,
                "{\"role\":\"CLIENTE\",\"active\":true}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // la sesion del administrador sigue siendo valida despues de los intentos rechazados
        assertThat(get("/api/admin/users", adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void restablecerElSegundoFactorCierraSesionesYPideEnrolarDeNuevo() throws Exception {
        Long staffId = users.findByEmailIgnoreCase("psicologia@uam.edu.ni").orElseThrow().getId();

        var reset = exchange("/api/admin/users/" + staffId + "/mfa-reset", HttpMethod.POST, adminToken, null);

        assertThat(reset.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/staff/attempts", staffToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var login = exchange("/api/auth/login", HttpMethod.POST, null,
                "{\"email\":\"psicologia@uam.edu.ni\",\"password\":\"clave-staff-segura\"}");
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(json.readTree(login.getBody()).get("mfaEnrollmentRequired").asBoolean()).isTrue();
        assertThat(get("/api/admin/audit", adminToken).getBody()).contains("RESET_MFA");
    }

    @Test
    void elPersonalNoEntraConUnCodigoIncorrectoYElAspiranteNoNecesitaSegundoFactor() throws Exception {
        var primary = exchange("/api/auth/login", HttpMethod.POST, null,
                "{\"email\":\"psicologia@uam.edu.ni\",\"password\":\"clave-staff-segura\"}");
        assertThat(primary.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String challenge = json.readTree(primary.getBody()).get("challengeToken").asText();
        var wrong = exchange("/api/auth/mfa/verify", HttpMethod.POST, null,
                json.createObjectNode().put("challengeToken", challenge).put("code", "000000").toString());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var applicant = exchange("/api/auth/login", HttpMethod.POST, null,
                "{\"email\":\"aspirante@example.com\",\"password\":\"clave-aspirante\"}");
        assertThat(applicant.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(applicant.getBody()).get("mfaRequired").asBoolean()).isFalse();
    }

    private org.springframework.http.ResponseEntity<String> get(String path, String token) {
        return exchange(path, HttpMethod.GET, token, null);
    }

    private org.springframework.http.ResponseEntity<String> exchange(String path, HttpMethod method,
                                                                     String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String totp(String secret, Instant now) throws Exception {
        byte[] key = decodeBase32(secret);
        byte[] counter = ByteBuffer.allocate(8).putLong(now.getEpochSecond() / 30).array();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] digest = mac.doFinal(counter);
        int offset = digest[digest.length - 1] & 15;
        int binary = ((digest[offset] & 127) << 24) | ((digest[offset + 1] & 255) << 16)
                | ((digest[offset + 2] & 255) << 8) | (digest[offset + 3] & 255);
        return String.format("%06d", binary % 1_000_000);
    }

    private byte[] decodeBase32(String value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : value.toCharArray()) {
            int digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 255); bits -= 8; }
        }
        return out.toByteArray();
    }
}
