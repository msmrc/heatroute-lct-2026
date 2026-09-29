package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoutePlannerTuningTest {
    @Test
    void fixtureUsesTheExpectedVersionAndBudgets() {
        RoutePlannerTuning fixture = RoutePlannerTuning.fixture();

        assertThat(fixture.getAlgorithmVersion()).isEqualTo(RoutePlannerTuning.FIXTURE_ALGORITHM_VERSION);
        assertThat(fixture.getEngineeringEgressExtraM())
                .isEqualTo(HeatRouteEngineeringRules.ENGINEERING_EGRESS_EXTRA_M);
        assertThat(fixture.getEngineeringZoneRadiusM()).isEqualTo(120.0);
        assertThat(fixture.getMaximumEngineeringZoneDemands()).isEqualTo(4);
        assertThat(fixture.getMaximumEngineeringZoneRebuilds()).isEqualTo(2);
        assertThat(fixture.getMaximumGlobalEngineeringRepairs()).isEqualTo(8);
        assertThat(fixture.getMaximumEngineeringEgressCandidates()).isEqualTo(5);
    }
}
