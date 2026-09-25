package ru.lct.heatroute.domain.routing;

/**
 * Фиксирует версию и ограниченные поисковые бюджеты единственного основного планировщика.
 */
public final class RoutePlannerTuning {
    public static final String STABLE_ALGORITHM_VERSION = "global-tree-71";
    /** Совместимое имя константы; отдельного экспериментального алгоритма больше нет. */
    @Deprecated
    public static final String EXPERIMENTAL_ALGORITHM_VERSION = STABLE_ALGORITHM_VERSION;

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

    public static RoutePlannerTuning stable() {
        return new RoutePlannerTuning(
                STABLE_ALGORITHM_VERSION,
                60.0,
                8,
                5,
                4,
                2,
                120.0);
    }

    /** Старые вызовы используют основные бюджеты, без расширенного экспериментального поиска. */
    @Deprecated
    public static RoutePlannerTuning expertExperimental() {
        return stable();
    }

    public String getAlgorithmVersion() { return algorithmVersion; }
    public double getEngineeringEgressExtraM() { return engineeringEgressExtraM; }
    public int getMaximumGlobalEngineeringRepairs() { return maximumGlobalEngineeringRepairs; }
    public int getMaximumEngineeringEgressCandidates() { return maximumEngineeringEgressCandidates; }
    public int getMaximumEngineeringZoneDemands() { return maximumEngineeringZoneDemands; }
    public int getMaximumEngineeringZoneRebuilds() { return maximumEngineeringZoneRebuilds; }
    public double getEngineeringZoneRadiusM() { return engineeringZoneRadiusM; }
}
