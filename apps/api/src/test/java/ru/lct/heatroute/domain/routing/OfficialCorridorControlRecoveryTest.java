package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Не теряем компактный полный черновик при отбрасывании коротких неустойчивых вводов. */
class OfficialCorridorControlRecoveryTest {
    @Test
    void preservesCompactInputDerivedDraftForFinalRegularization() throws Exception {
        List<ImportedOfficialFeature> features = new OfficialDatasetRoutingTest().loadOfficialFeatures();
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OfficialRoutingEnvironment environment = router.prepare(features);
        Map<String, ImportedOfficialFeature> demands = features.stream()
                .filter(f -> "oks_connection_point".equals(f.getObjectType()))
                .collect(Collectors.toMap(ImportedOfficialFeature::getFeatureId, f -> f));
        Coordinate center = new Coordinate(demands.values().stream()
                .mapToDouble(f -> f.getMetricGeometry().getCoordinate().x).average().orElseThrow(),
                demands.values().stream().mapToDouble(f -> f.getMetricGeometry().getCoordinate().y).average().orElseThrow());
        ImportedOfficialFeature target = features.stream().filter(f -> "heat_chamber".equals(f.getObjectType()))
                .min(Comparator.comparingDouble(f -> f.getMetricGeometry().getCoordinate().distance(center))).orElseThrow();
        int incident = (int) features.stream().filter(f -> "heat_network".equals(f.getObjectType()))
                .filter(f -> f.getMetricGeometry().distance(target.getMetricGeometry()) <= 0.01).count();
        Coordinate rootPoint = target.getMetricGeometry().getCoordinate();
        RouteNode root = new RouteNode("root:" + target.getFeatureId(), "existing_chamber_tie_in",
                new RouteCoordinate(rootPoint.x, rootPoint.y), true, true, incident, target.getFeatureId());
        List<OrthogonalCorridorNetworkBuilder.Terminal> terminals = demands.values().stream()
                .map(f -> new OrthogonalCorridorNetworkBuilder.Terminal(f.getFeatureId(), f.getFeatureId(),
                        f.getMetricGeometry().getCoordinate(), new BigDecimal(f.getAttributes().path("flow_tph").asText())))
                .collect(Collectors.toList());
        List<Geometry> buildings = features.stream().filter(rules::isBuildingFeature)
                .map(ImportedOfficialFeature::getMetricGeometry).collect(Collectors.toList());

        List<OrthogonalCorridorNetworkBuilder.Network> candidates = new OrthogonalCorridorNetworkBuilder(
                router, new OfficialPipeCatalog()).buildWithTerminalFrame(terminals, root, 4 - incident,
                buildings, environment, (id, port, diameter, avoidance) -> {
                    Coordinate point = demands.get(id).getMetricGeometry().getCoordinate();
                    OfficialRouteGeometryRules.NormalEgress egress = environment.normalEgressTowards(diameter,
                            point, port, RoutePlannerTuning.stable().getEngineeringEgressExtraM()).orElse(null);
                    RoutePath path = egress == null
                            ? router.find(point, port, diameter, environment, Set.of(), RoutePreference.ENGINEERING, avoidance)
                            : router.findAfter(egress.start(), egress.exit(), port, diameter,
                                    environment, Set.of(), RoutePreference.ENGINEERING, avoidance);
                    return path == null || egress == null ? path
                            : router.withCheckedTerminalPrefix(egress, path, diameter, environment, Set.of(), avoidance);
                });

        OfficialRouteValidator validator = new OfficialRouteValidator(rules);
        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(candidate.connections()).hasSize(demands.size());
            assertThat(candidate.connections()).allMatch(connection -> "connected".equals(connection.getStatus()));
            assertThat(validator.validate(candidate.nodes(), candidate.edges(), features)).isEmpty();
            // Порог этого fixture фиксирует прежний полный контроль, не универсальную норму камер.
            assertThat(candidate.nodes().stream().filter(node -> "new_branch_chamber".equals(node.getNodeType())).count())
                    .isLessThanOrEqualTo(13);
            assertThat(candidate.edges().stream().mapToDouble(edge -> edge.getLengthM().doubleValue()).sum())
                    .isLessThan(1860.0);
        });
        // Это gate черновика; экспертные правила, ДУ, глубину и экспорт проверяет полный dataset test.
    }
}
