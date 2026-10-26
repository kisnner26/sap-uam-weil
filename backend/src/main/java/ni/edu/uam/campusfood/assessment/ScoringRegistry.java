package ni.edu.uam.campusfood.assessment;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resuelve la estrategia de calificación de cada test por su id; un test sin estrategia no puede calificarse. */
@Component
public class ScoringRegistry {
    private final Map<String, ScoringStrategy> byTestId = new LinkedHashMap<>();

    public ScoringRegistry(List<ScoringStrategy> strategies) {
        for (ScoringStrategy strategy : strategies) {
            if (byTestId.putIfAbsent(strategy.testId(), strategy) != null) {
                throw new IllegalStateException("Hay dos estrategias de calificación para " + strategy.testId());
            }
        }
    }

    public ScoringStrategy forTest(String testId) {
        ScoringStrategy strategy = byTestId.get(testId);
        if (strategy == null) throw new IllegalArgumentException("Test sin estrategia de calificación: " + testId);
        return strategy;
    }
}
