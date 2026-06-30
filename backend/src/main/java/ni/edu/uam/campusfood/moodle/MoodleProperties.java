package ni.edu.uam.campusfood.moodle;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de la integracion con el Moodle de la UAM.
 * Se enlaza con el bloque app.moodle.* de application.yml.
 */
@ConfigurationProperties(prefix = "app.moodle")
public class MoodleProperties {

    /** Base del Moodle, ej: https://uamvirtual.uam.edu.ni/grado */
    private String baseUrl = "https://uamvirtual.uam.edu.ni/grado";

    /** Nombre del servicio web habilitado en Moodle. */
    private String service = "moodle_mobile_app";

    private int connectTimeoutMs = 5000;

    private int readTimeoutMs = 10000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
