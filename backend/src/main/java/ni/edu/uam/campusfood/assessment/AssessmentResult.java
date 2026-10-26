package ni.edu.uam.campusfood.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/** Resultado de un intento como lista de escalas, independiente del test que lo produjo. */
@Entity
@Table(name = "assessment_results")
public class AssessmentResult {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    @jakarta.persistence.OneToOne(optional = false, fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "attempt_id", nullable = false, unique = true)
    private AssessmentAttempt attempt;

    @Column(name = "scoring_version", nullable = false, length = 40)
    private String scoringVersion;

    @Column(name = "scales", nullable = false, columnDefinition = "TEXT")
    private String scales;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AssessmentResult() {
    }

    public AssessmentResult(AssessmentAttempt attempt, String scoringVersion,
                            List<ScoringStrategy.Scale> scales, Instant createdAt) {
        this.attempt = attempt;
        this.scoringVersion = scoringVersion;
        this.createdAt = createdAt;
        try {
            this.scales = JSON.writeValueAsString(scales);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el resultado.", e);
        }
    }

    public String getScoringVersion() { return scoringVersion; }

    public List<ScoringStrategy.Scale> getScales() {
        try {
            return JSON.readValue(scales, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("El resultado guardado no se pudo leer.", e);
        }
    }
}
