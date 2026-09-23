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
    void resolvesStableAndExperimentalImplementationsIndependently() {
        RoutingAlgorithm stable = algorithm(RoutingAlgorithmProfile.STABLE);
        RoutingAlgorithm experimental = algorithm(RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL);
        RoutingAlgorithmRegistry registry = new RoutingAlgorithmRegistry(List.of(stable, experimental));

        assertThat(registry.require(RoutingAlgorithmProfile.STABLE)).isSameAs(stable);
        assertThat(registry.require(RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL)).isSameAs(experimental);
    }

    @Test
    void refusesToStartWithoutEveryDeclaredProfile() {
        RoutingAlgorithm stable = algorithm(RoutingAlgorithmProfile.STABLE);

        assertThatThrownBy(() -> new RoutingAlgorithmRegistry(List.of(stable)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expert_experimental");
    }

    private RoutingAlgorithm algorithm(RoutingAlgorithmProfile profile) {
        RoutingAlgorithm algorithm = mock(RoutingAlgorithm.class);
        when(algorithm.profile()).thenReturn(profile);
        return algorithm;
    }
}
