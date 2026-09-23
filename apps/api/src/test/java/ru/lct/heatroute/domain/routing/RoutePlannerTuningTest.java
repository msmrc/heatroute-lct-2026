package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoutePlannerTuningTest {
    @Test
    void experimentalSearchBudgetIsSeparateAndStrictlyWiderThanStable() {
        RoutePlannerTuning stable = RoutePlannerTuning.stable();
        RoutePlannerTuning experimental = RoutePlannerTuning.expertExperimental();

        assertThat(stable.getAlgorithmVersion()).isEqualTo("global-tree-46");
        assertThat(experimental.getAlgorithmVersion()).isEqualTo("expert-tree-1");
        assertThat(experimental.getEngineeringZoneRadiusM())
                .isGreaterThan(stable.getEngineeringZoneRadiusM());
        assertThat(experimental.getMaximumEngineeringZoneDemands())
                .isGreaterThan(stable.getMaximumEngineeringZoneDemands());
        assertThat(experimental.getMaximumEngineeringZoneRebuilds())
                .isGreaterThan(stable.getMaximumEngineeringZoneRebuilds());
        assertThat(experimental.getMaximumGlobalEngineeringRepairs())
                .isGreaterThan(stable.getMaximumGlobalEngineeringRepairs());
    }
}
