package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.run.RoutingAlgorithmProfile;

class RoutingAlgorithmRegistryTest {
    @Test
    void resolvesLegacyProfileToTheSamePrimaryImplementationAndVersion() {
        RoutingAlgorithm stable = algorithm(RoutingAlgorithmProfile.STABLE);
        when(stable.version()).thenReturn(NextGenerationRoutePlanner.VERSION);
        RoutingAlgorithmRegistry registry = new RoutingAlgorithmRegistry(List.of(stable));

        assertThat(registry.require(RoutingAlgorithmProfile.STABLE)).isSameAs(stable);
        assertThat(registry.require(RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL)).isSameAs(stable);
        assertThat(registry.require(RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL).version())
                .isEqualTo(NextGenerationRoutePlanner.VERSION);
    }

    @Test
    void refusesToStartWithoutThePrimaryImplementation() {
        assertThatThrownBy(() -> new RoutingAlgorithmRegistry(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stable");
    }

    @Test
    void refusesDuplicatePrimaryImplementations() {
        RoutingAlgorithm stable = algorithm(RoutingAlgorithmProfile.STABLE);

        assertThatThrownBy(() -> new RoutingAlgorithmRegistry(List.of(stable, stable)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void refusesReintroducingAnIndependentLegacyImplementation() {
        RoutingAlgorithm stable = algorithm(RoutingAlgorithmProfile.STABLE);
        RoutingAlgorithm experimental = algorithm(RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL);

        assertThatThrownBy(() -> new RoutingAlgorithmRegistry(List.of(stable, experimental)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only the stable");
    }

    @Test
    void queuedVersionMustMatchTheRegisteredEngine() {
        RoutingAlgorithm stable = algorithm(RoutingAlgorithmProfile.STABLE);
        when(stable.version()).thenReturn("global-tree-current");
        RoutingAlgorithmRegistry registry = new RoutingAlgorithmRegistry(List.of(stable));

        assertThat(registry.requireVersion(RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL,
                "global-tree-current")).isSameAs(stable);
        assertThatThrownBy(() -> registry.requireVersion(RoutingAlgorithmProfile.STABLE,
                "global-tree-retired"))
                .isInstanceOf(RoutingEngineVersionUnavailableException.class)
                .hasMessageContaining("unavailable");
    }

    private RoutingAlgorithm algorithm(RoutingAlgorithmProfile profile) {
        RoutingAlgorithm algorithm = mock(RoutingAlgorithm.class);
        when(algorithm.profile()).thenReturn(profile);
        return algorithm;
    }
}
