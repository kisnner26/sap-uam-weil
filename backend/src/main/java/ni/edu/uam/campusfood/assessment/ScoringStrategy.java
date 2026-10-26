package ni.edu.uam.campusfood.assessment;

import java.util.List;
import java.util.Map;

/** Calificación específica de un test; cada test publicado aporta una implementación registrada por su id. */
public interface ScoringStrategy {

    /** Identificador estable del test que califica, el mismo que guarda el intento (`test_id`). */
    String testId();

    /** Versión del algoritmo y baremos con que se calificó; el resultado guardado no se recalcula en silencio. */
    String scoringVersion();

    Score score(List<Integer> answers);

    /** Escalas del resultado. Por defecto una sola, el puntaje directo; los tests con perfil agregan las suyas. */
    default List<Scale> scales(Score score) {
        return List.of(new Scale("total", "Puntaje directo", score.directScore()));
    }

    Interpretation interpret(int directScore, Integer age, String group);

    /** Tabla de baremos del test, para quien necesite consultarla. */
    List<Map<String, Integer>> norms();

    record Scale(String code, String label, int raw) {}

    record Score(int directScore, List<Integer> pageScores) {}

    record Interpretation(String normColumn, int percentile, int ci, String classification) {}
}
