package ru.lct.heatroute.domain.routing;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.run.RoutingAlgorithmProfile;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/**
 * Изолированный профиль для экспериментов с групповым инженерным поиском.
 * Он разделяет официальные валидаторы и экономику со stable, но имеет собственные бюджеты поиска.
 */
@Component
public class ExpertExperimentalRoutingAlgorithm implements RoutingAlgorithm {
    private final OfficialRoutePlanner planner;

    public ExpertExperimentalRoutingAlgorithm(
            OfficialRouteValidator validator,
            OfficialObstacleRouter obstacleRouter,
            OfficialPipeCatalog pipeCatalog,
            OfficialNetworkSizer networkSizer,
            OfficialExistingNetworkReconstructor reconstructor,
            OfficialVariantEconomicsCalculator economicsCalculator,
            OfficialDepthPlanner depthPlanner) {
        this.planner = new OfficialRoutePlanner(
                validator,
                obstacleRouter,
                pipeCatalog,
                networkSizer,
                reconstructor,
                economicsCalculator,
                depthPlanner,
                RoutePlannerTuning.expertExperimental());
    }

    @Override
    public RoutingAlgorithmProfile profile() {
        return RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL;
    }

    @Override
    public String version() {
        return RoutePlannerTuning.EXPERIMENTAL_ALGORITHM_VERSION;
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
