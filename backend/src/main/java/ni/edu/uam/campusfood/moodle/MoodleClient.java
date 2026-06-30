package ni.edu.uam.campusfood.moodle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Cliente server-to-server hacia el Moodle de la UAM.
 *
 * Hace las DOS unicas llamadas HTTP del flujo (desde el backend, sin CORS):
 *   1) login/token.php                -> obtiene el token de Moodle
 *   2) webservice/rest/server.php     -> core_webservice_get_site_info -> perfil
 *
 * Ni la contrasena ni el token de Moodle se persisten: el token solo se usa
 * para la segunda llamada y se descarta al terminar {@link #authenticate}.
 */
@Component
public class MoodleClient {

    private static final Logger log = LoggerFactory.getLogger(MoodleClient.class);

    private final RestClient restClient;
    private final MoodleProperties props;
    private final ObjectMapper objectMapper;

    public MoodleClient(RestClient moodleRestClient, MoodleProperties props, ObjectMapper objectMapper) {
        this.restClient = moodleRestClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Autentica al estudiante contra Moodle y devuelve su perfil.
     * La contrasena solo se usa aqui y nunca sale de este metodo.
     *
     * @throws MoodleException segun el tipo de fallo (credenciales / mantenimiento / caida)
     */
    public MoodleProfile authenticate(String cif, String password) {
        String token = fetchToken(cif, password);
        try {
            return fetchProfile(token);
        } finally {
            // El token de Moodle es de un solo uso aqui; ayudamos al GC a soltarlo.
            token = null;
        }
    }

    // ---- PASO 1: obtener token --------------------------------------------

    private String fetchToken(String cif, String password) {
        String uri = UriComponentsBuilder.fromPath("/login/token.php")
                .queryParam("username", cif)
                .queryParam("password", password)
                .queryParam("service", props.getService())
                .build()
                .toUriString();

        String body = get(uri);
        JsonNode json = parseJsonOrMaintenance(body);

        if (json.hasNonNull("token")) {
            return json.get("token").asText();
        }
        if (json.hasNonNull("error") || json.hasNonNull("errorcode")) {
            String errorcode = json.path("errorcode").asText("");
            String message = json.path("error").asText("Credenciales UAM invalidas");
            // invalidlogin y similares son fallo de credenciales; el resto tambien
            // se trata como login fallido para no filtrar detalles internos.
            log.info("Login Moodle rechazado (errorcode={})", errorcode);
            throw MoodleException.invalidCredentials("CIF o contrasena incorrectos");
        }
        // JSON inesperado: lo tratamos como plataforma no disponible.
        log.warn("Respuesta inesperada de token.php: {}", trim(body));
        throw MoodleException.maintenance();
    }

    // ---- PASO 2: obtener perfil -------------------------------------------

    private MoodleProfile fetchProfile(String token) {
        String uri = UriComponentsBuilder.fromPath("/webservice/rest/server.php")
                .queryParam("wstoken", token)
                .queryParam("wsfunction", "core_webservice_get_site_info")
                .queryParam("moodlewsrestformat", "json")
                .build()
                .toUriString();

        String body = get(uri);
        JsonNode json = parseJsonOrMaintenance(body);

        // Moodle devuelve {exception, errorcode, message} si el token expiro/invalido.
        if (json.hasNonNull("exception")) {
            log.warn("get_site_info devolvio excepcion: {}", json.path("errorcode").asText());
            throw MoodleException.invalidCredentials("No se pudo validar la sesion UAM");
        }

        if (!json.hasNonNull("userid")) {
            log.warn("get_site_info sin userid: {}", trim(body));
            throw MoodleException.maintenance();
        }

        return new MoodleProfile(
                json.get("userid").asInt(),
                json.path("fullname").asText(""),
                blankToNull(json.path("useremail").asText(null)),
                json.path("userpictureurl").asText(null),
                extractCareer(json)
        );
    }

    // ---- helpers ----------------------------------------------------------

    private String get(String uri) {
        try {
            return restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException e) {
            // Timeout / DNS / conexion rechazada.
            log.warn("No se pudo contactar a Moodle: {}", e.getMessage());
            throw MoodleException.unavailable(e);
        } catch (RuntimeException e) {
            // 5xx u otros fallos HTTP de Moodle.
            log.warn("Fallo HTTP llamando a Moodle: {}", e.getMessage());
            throw MoodleException.unavailable(e);
        }
    }

    /**
     * Convierte el cuerpo a JSON. Si Moodle respondio HTML (pagina de
     * mantenimiento/caida) o el cuerpo no es JSON parseable, lanza MAINTENANCE.
     */
    private JsonNode parseJsonOrMaintenance(String body) {
        if (body == null || looksLikeHtml(body)) {
            throw MoodleException.maintenance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw MoodleException.maintenance();
        }
    }

    private String extractCareer(JsonNode json) {
        String direct = firstText(json,
                "career", "carrera", "program", "programa", "major",
                "department", "institution", "profilefield_carrera",
                "profilefield_programa", "profilefield_career");
        if (hasText(direct)) {
            return direct;
        }
        String custom = careerFromFieldArray(json.path("customfields"));
        if (hasText(custom)) {
            return custom;
        }
        return careerFromFieldArray(json.path("profilefields"));
    }

    private String careerFromFieldArray(JsonNode fields) {
        if (!fields.isArray()) {
            return null;
        }
        for (JsonNode field : fields) {
            String label = firstText(field, "shortname", "name", "label");
            if (!isCareerLabel(label)) {
                continue;
            }
            String value = firstText(field, "value", "displayvalue", "rawvalue");
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode json, String... fields) {
        for (String field : fields) {
            String value = clean(json.path(field).asText(null));
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isCareerLabel(String label) {
        String value = clean(label);
        if (!hasText(value)) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains("carrera")
                || lower.contains("career")
                || lower.contains("programa")
                || lower.contains("program");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private boolean looksLikeHtml(String body) {
        String start = body.stripLeading();
        if (start.length() > 30) {
            start = start.substring(0, 30);
        }
        String lower = start.toLowerCase();
        return lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<");
    }

    private String trim(String body) {
        if (body == null) {
            return "null";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
