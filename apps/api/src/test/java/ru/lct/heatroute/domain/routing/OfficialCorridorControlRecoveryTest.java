package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ExistingNetworkIncidence;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Не теряем компактный полный черновик; его сохранение не означает финального допуска камер. */
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
        RouteNode root = OfficialCorridorDatasetTest.existingRoot(target, new ExistingNetworkIncidence(features));
        List<OrthogonalCorridorNetworkBuilder.Terminal> terminals = demands.values().stream()
                .map(f -> new OrthogonalCorridorNetworkBuilder.Terminal(f.getFeatureId(), f.getFeatureId(),
                        f.getMetricGeometry().getCoordinate(), new BigDecimal(f.getAttributes().path("flow_tph").asText())))
                .collect(Collectors.toList());
        List<Geometry> buildings = features.stream().filter(rules::isBuildingFeature)
                .map(ImportedOfficialFeature::getMetricGeometry).collect(Collectors.toList());

        List<OrthogonalCorridorNetworkBuilder.Network> candidates = new OrthogonalCorridorNetworkBuilder(
                router, new OfficialPipeCatalog()).buildWithTerminalFrame(terminals, root, 4 - root.getBaseIncidentSections(),
                buildings, environment, (id, port, diameter, avoidance) -> OfficialCorridorDatasetTest.terminalRoute(
                        router, environment, demands.get(id).getMetricGeometry().getCoordinate(), port, diameter, avoidance));

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
        // Ближайшая камера может не иметь свободной нормали по фактическим лучам существующей сети.
        // Это gate черновика: финальные нормали, 2м, ДУ, глубину и экспорт проверяет полный dataset test.
    }
}
