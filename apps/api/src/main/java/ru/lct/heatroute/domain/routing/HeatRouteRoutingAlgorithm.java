package ru.lct.heatroute.domain.routing;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/** Production adapter for the single HeatRoute calculation engine. */
@Component
public class HeatRouteRoutingAlgorithm implements RoutingAlgorithm {
    private final HeatRoutePlanner planner;

    public HeatRouteRoutingAlgorithm(HeatRoutePlanner planner) {
        this.planner = planner;
    }

    @Override
    public String version() {
        return HeatRoutePlanner.VERSION;
    }

    @Override
    public OfficialCalculationResult plan(
            RoutingExecutionContext context,
            List<ImportedOfficialFeature> features,
            TopologyAnalysis topology,
            OfficialRunParameters parameters,
            RoutingFeatureSource source) {
        HeatRoutePlanner.Execution execution = planner.execute(
                context, features, topology, parameters, source,
                HeatRoutePlanner.Settings.production());
        OfficialCalculationResult result = execution.getResult();
        if (execution.getOutcome() != AdaptiveCatalogNetworkSearch.Outcome.ACCEPTED
                || result == null) {
            throw new RoutePlanningIncompleteException(execution);
        }
        if (!version().equals(result.getAlgorithmVersion())) {
            throw new IllegalStateException("HeatRoute result version mismatch: "
                    + result.getAlgorithmVersion());
        }
        return result;
    }
}
