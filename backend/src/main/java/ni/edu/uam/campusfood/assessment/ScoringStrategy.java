package ni.edu.uam.campusfood.assessment;

import java.util.List;
import java.util.Map;

/** Calificación específica de un test; cada test publicado aporta una implementación registrada por su id. */
public interface ScoringStrategy {

    /** Identificador estable del test que califica, el mismo que guarda el intento (`test_id`). */
    String testId();

    Score score(List<Integer> answers);

    Interpretation interpret(int directScore, Integer age, String group);

    /** Tabla de baremos del test, para quien necesite consultarla. */
    List<Map<String, Integer>> norms();

    record Score(int directScore, List<Integer> pageScores) {}

    record Interpretation(String normColumn, int percentile, int ci, String classification) {}
}
