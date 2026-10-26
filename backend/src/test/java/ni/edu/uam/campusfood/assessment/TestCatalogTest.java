package ni.edu.uam.campusfood.assessment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCatalogTest {
    private static final TestDefinition WEIL = new TestDefinition("weil-form-a", 1, 60, 8);

    @Test
    void validaItemsYOpcionesSegunLaDefinicion() {
        assertThat(WEIL.accepts(0, 1)).isTrue();
        assertThat(WEIL.accepts(59, 8)).isTrue();
        assertThat(WEIL.accepts(60, 1)).isFalse();
        assertThat(WEIL.accepts(-1, 1)).isFalse();
        assertThat(WEIL.accepts(0, 9)).isFalse();
        assertThat(WEIL.accepts(0, 0)).isFalse();
    }

    @Test
    void otroTestPuedeTenerOtraLongitudYEscala() {
        TestDefinition likert = new TestDefinition("allport-valores-v1", 1, 45, 4);
        TestCatalog catalog = new TestCatalog(List.of(WEIL, likert));

        assertThat(catalog.get("allport-valores-v1").accepts(44, 4)).isTrue();
        assertThat(catalog.get("allport-valores-v1").accepts(44, 5)).isFalse();
        assertThat(catalog.defaultTest()).isEqualTo(WEIL);
    }

    @Test
    void unTestNoPublicadoSeRechaza() {
        TestCatalog catalog = new TestCatalog(List.of(WEIL));

        assertThatThrownBy(() -> catalog.get("desconocido")).isInstanceOf(IllegalArgumentException.class);
    }
}
