package ni.edu.uam.campusfood.privacy;

import ni.edu.uam.campusfood.admission.AdmissionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Aviso de privacidad versionado y registro del consentimiento de cada persona. */
@Service
public class PrivacyService {
    private final ConsentRecordRepository consents;
    private final int currentVersion;
    private final String contactEmail;
    private final int retentionMonths;

    public PrivacyService(ConsentRecordRepository consents,
                          @Value("${app.privacy.version:1}") int currentVersion,
                          @Value("${app.privacy.contact-email:privacidad@uam.edu.ni}") String contactEmail,
                          @Value("${app.retention.months:24}") int retentionMonths) {
        this.consents = consents;
        this.currentVersion = currentVersion;
        this.contactEmail = contactEmail;
        this.retentionMonths = retentionMonths;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    /** En producción no se publica un aviso que todavía lleva el recuadro de borrador. */
    @jakarta.annotation.PostConstruct
    void rejectDraftNoticeInProd() {
        if (environment == null || !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))) return;
        if (load(currentVersion).contains("BORRADOR PARA REVISIÓN LEGAL")) {
            throw new IllegalStateException("El aviso de privacidad versión " + currentVersion
                    + " sigue marcado como borrador. Publica el texto aprobado por asesoría legal y sube app.privacy.version.");
        }
    }

    public Notice notice() {
        String text = load(currentVersion)
                .replace("{{RETENCION_MESES}}", String.valueOf(retentionMonths))
                .replace("{{CORREO_PRIVACIDAD}}", contactEmail);
        return new Notice(currentVersion, text, sha256(text), contactEmail);
    }

    @Transactional(readOnly = true)
    public boolean hasConsented(Long userId) {
        return consents.existsByUserIdAndPolicyVersion(userId, currentVersion);
    }

    @Transactional
    public void accept(Long userId, int version) {
        if (version != currentVersion) {
            throw new AdmissionException("PRIVACY_VERSION", "El aviso de privacidad cambió. Léelo de nuevo antes de aceptar.");
        }
        if (!consents.existsByUserIdAndPolicyVersion(userId, currentVersion)) {
            consents.save(new ConsentRecord(userId, currentVersion, notice().sha256()));
        }
    }

    public int currentVersion() {
        return currentVersion;
    }

    private String load(int version) {
        try (var in = new ClassPathResource("privacy/notice-v" + version + ".md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new IllegalStateException("No existe el texto del aviso de privacidad version " + version, missing);
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Notice(int version, String text, String sha256, String contactEmail) {
    }
}
