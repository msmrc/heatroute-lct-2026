package ru.lct.heatroute.domain.routing;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.run.RoutingAlgorithmProfile;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/** Запускает единственный основной планировщик с общей версией и поисковыми бюджетами. */
@Component
public class StableRoutingAlgorithm implements RoutingAlgorithm {
    private final OfficialRoutePlanner planner;

    public StableRoutingAlgorithm(OfficialRoutePlanner planner) {
        this.planner = planner;
    }

    @Override
    public RoutingAlgorithmProfile profile() {
        return RoutingAlgorithmProfile.STABLE;
    }

    @Override
    public String version() {
        return RoutePlannerTuning.STABLE_ALGORITHM_VERSION;
    }

    @Override
    public OfficialCalculationResult plan(
            List<ImportedOfficialFeature> features,
            TopologyAnalysis topology,
            OfficialRunParameters parameters,
            String inputProfile,
            RoutingFeatureSource source) {
        return planner.plan(features, topology, parameters, inputProfile, source);
    }
}
