package ni.edu.uam.campusfood.assessment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringRegistryTest {

    @Test
    void resuelveLaEstrategiaPorIdDelTest() {
        WeilScoringStrategy weil = new WeilScoringStrategy();
        ScoringRegistry registry = new ScoringRegistry(List.of(weil));

        assertThat(registry.forTest("weil-form-a")).isSameAs(weil);
    }

    @Test
    void unTestSinEstrategiaNoSePuedeCalificar() {
        ScoringRegistry registry = new ScoringRegistry(List.of(new WeilScoringStrategy()));

        assertThatThrownBy(() -> registry.forTest("thurstone-intereses-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thurstone-intereses-v1");
    }

    @Test
    void rechazaDosEstrategiasParaElMismoTest() {
        List<ScoringStrategy> duplicated = List.of(new WeilScoringStrategy(), new WeilScoringStrategy());

        assertThatThrownBy(() -> new ScoringRegistry(duplicated)).isInstanceOf(IllegalStateException.class);
    }
}
