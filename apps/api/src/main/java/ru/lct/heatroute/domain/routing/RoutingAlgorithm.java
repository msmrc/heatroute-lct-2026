package ru.lct.heatroute.domain.routing;

import java.util.List;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.run.RoutingAlgorithmProfile;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/** Выполняет один версионируемый профиль расчёта общей сети. */
public interface RoutingAlgorithm {
    RoutingAlgorithmProfile profile();

    String version();

    OfficialCalculationResult plan(
            RoutingExecutionContext context,
            List<ImportedOfficialFeature> features,
            TopologyAnalysis topology,
            OfficialRunParameters parameters,
            RoutingFeatureSource source);
}
