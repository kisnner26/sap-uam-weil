package ni.edu.uam.campusfood.assessment;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Definiciones publicadas, por id. Cada test registra la suya como bean `TestDefinition`. */
@Component
public class TestCatalog {
    private final Map<String, TestDefinition> byId = new LinkedHashMap<>();

    public TestCatalog(List<TestDefinition> definitions) {
        for (TestDefinition definition : definitions) {
            if (byId.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("Hay dos definiciones para " + definition.id());
            }
        }
        if (byId.isEmpty()) throw new IllegalStateException("No hay ningún test publicado.");
    }

    public TestDefinition get(String testId) {
        TestDefinition definition = byId.get(testId);
        if (definition == null) throw new IllegalArgumentException("La versión del test no está publicada.");
        return definition;
    }

    /** Test que se aplica en las convocatorias mientras no existan asignaciones por test. */
    public TestDefinition defaultTest() {
        return byId.values().iterator().next();
    }
}
