package ru.lct.heatroute.domain.routing;

/** Test-only configuration for the retired planner regression fixture. */
final class RoutePlannerTuning {
    static final String STABLE_ALGORITHM_VERSION = "global-tree-103";
    static final String EXPERIMENTAL_ALGORITHM_VERSION = STABLE_ALGORITHM_VERSION;

    private final String algorithmVersion;
    private final double engineeringEgressExtraM;
    private final int maximumGlobalEngineeringRepairs;
    private final int maximumEngineeringEgressCandidates;
    private final int maximumEngineeringZoneDemands;
    private final int maximumEngineeringZoneRebuilds;
    private final double engineeringZoneRadiusM;

    private RoutePlannerTuning(
            String algorithmVersion,
            double engineeringEgressExtraM,
            int maximumGlobalEngineeringRepairs,
            int maximumEngineeringEgressCandidates,
            int maximumEngineeringZoneDemands,
            int maximumEngineeringZoneRebuilds,
            double engineeringZoneRadiusM) {
        this.algorithmVersion = algorithmVersion;
        this.engineeringEgressExtraM = engineeringEgressExtraM;
        this.maximumGlobalEngineeringRepairs = maximumGlobalEngineeringRepairs;
        this.maximumEngineeringEgressCandidates = maximumEngineeringEgressCandidates;
        this.maximumEngineeringZoneDemands = maximumEngineeringZoneDemands;
        this.maximumEngineeringZoneRebuilds = maximumEngineeringZoneRebuilds;
        this.engineeringZoneRadiusM = engineeringZoneRadiusM;
    }

    static RoutePlannerTuning stable() {
        return new RoutePlannerTuning(
                STABLE_ALGORITHM_VERSION,
                HeatRouteEngineeringRules.ENGINEERING_EGRESS_EXTRA_M,
                8,
                5,
                4,
                2,
                120.0);
    }

    static RoutePlannerTuning expertExperimental() {
        return stable();
    }

    String getAlgorithmVersion() { return algorithmVersion; }
    double getEngineeringEgressExtraM() { return engineeringEgressExtraM; }
    int getMaximumGlobalEngineeringRepairs() { return maximumGlobalEngineeringRepairs; }
    int getMaximumEngineeringEgressCandidates() { return maximumEngineeringEgressCandidates; }
    int getMaximumEngineeringZoneDemands() { return maximumEngineeringZoneDemands; }
    int getMaximumEngineeringZoneRebuilds() { return maximumEngineeringZoneRebuilds; }
    double getEngineeringZoneRadiusM() { return engineeringZoneRadiusM; }
}
