package ru.lct.heatroute.domain.routing;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.run.RoutingAlgorithmProfile;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/**
 * Production adapter for the only enabled routing engine.
 *
 * <p>The historical class/profile name is retained as an API compatibility boundary. All new
 * calculations execute the next-generation catalog/CP-SAT/exact-evaluator pipeline. There is no
 * fallback to {@link OfficialRoutePlanner}: an incomplete NextGen result fails explicitly so that
 * a run can never be presented as a NextGen calculation after being produced by the legacy solver.
 */
@Component
public class StableRoutingAlgorithm implements RoutingAlgorithm {
    private final NextGenerationRoutePlanner planner;

    public StableRoutingAlgorithm(NextGenerationRoutePlanner planner) {
        this.planner = planner;
    }

    @Override
    public RoutingAlgorithmProfile profile() {
        return RoutingAlgorithmProfile.STABLE;
    }

    @Override
    public String version() {
        return NextGenerationRoutePlanner.VERSION;
    }

    @Override
    public OfficialCalculationResult plan(
            RoutingExecutionContext context,
            List<ImportedOfficialFeature> features,
            TopologyAnalysis topology,
            OfficialRunParameters parameters,
            RoutingFeatureSource source) {
        NextGenerationRoutePlanner.Execution execution = planner.execute(
                context, features, topology, parameters, source,
                NextGenerationRoutePlanner.Settings.production());
        OfficialCalculationResult result = execution.getResult();
        if (execution.getOutcome() != AdaptiveCatalogNetworkSearch.Outcome.ACCEPTED
                || result == null) {
            throw new NextGenerationPlanningIncompleteException(execution);
        }
        if (!version().equals(result.getAlgorithmVersion())) {
            throw new IllegalStateException("Next-generation result version mismatch: "
                    + result.getAlgorithmVersion());
        }
        return result;
    }
}
