package ru.lct.heatroute.domain.routing;

import java.util.List;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/** Выполняет версионируемый расчёт общей сети. */
public interface RoutingAlgorithm {
    String version();

    OfficialCalculationResult plan(
            RoutingExecutionContext context,
            List<ImportedOfficialFeature> features,
            TopologyAnalysis topology,
            OfficialRunParameters parameters,
            RoutingFeatureSource source);
}
