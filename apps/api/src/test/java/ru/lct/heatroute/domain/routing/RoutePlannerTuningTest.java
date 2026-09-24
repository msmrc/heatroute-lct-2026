package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoutePlannerTuningTest {
    @Test
    void legacyFactoryUsesThePrimaryVersionAndBudgets() {
        RoutePlannerTuning stable = RoutePlannerTuning.stable();
        RoutePlannerTuning experimental = RoutePlannerTuning.expertExperimental();

        assertThat(stable.getAlgorithmVersion()).isEqualTo(RoutePlannerTuning.STABLE_ALGORITHM_VERSION);
        assertThat(experimental.getAlgorithmVersion()).isEqualTo(stable.getAlgorithmVersion());
        assertThat(experimental.getEngineeringEgressExtraM())
                .isEqualTo(stable.getEngineeringEgressExtraM());
        assertThat(experimental.getEngineeringZoneRadiusM())
                .isEqualTo(stable.getEngineeringZoneRadiusM());
        assertThat(experimental.getMaximumEngineeringZoneDemands())
                .isEqualTo(stable.getMaximumEngineeringZoneDemands());
        assertThat(experimental.getMaximumEngineeringZoneRebuilds())
                .isEqualTo(stable.getMaximumEngineeringZoneRebuilds());
        assertThat(experimental.getMaximumGlobalEngineeringRepairs())
                .isEqualTo(stable.getMaximumGlobalEngineeringRepairs());
        assertThat(experimental.getMaximumEngineeringEgressCandidates())
                .isEqualTo(stable.getMaximumEngineeringEgressCandidates());
    }
}
