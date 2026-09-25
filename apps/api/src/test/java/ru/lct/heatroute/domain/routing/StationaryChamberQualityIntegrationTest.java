package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

/** Все опорные вершины дальше радиуса переноса; доводка должна работать без движения камеры. */
class StationaryChamberQualityIntegrationTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @ParameterizedTest
    @ValueSource(doubles = {0, 31, 90, 217})
    void repairsAnObliqueInputWhenNoRelocationCandidateExists(double angle) {
        var planner = new OfficialDatasetRoutingTest().planner();
        var evaluator = new EngineeringRouteEvaluator();
        for (boolean depth : List.of(false, true)) {
            var parameters = new OfficialRunParameters(null, null, depth);
            var env = new OfficialObstacleRouter(rules).prepare(List.of());
            var nodes = List.of(new RouteNode("root", "existing_chamber_tie_in", point(angle, -100, 0), true, true, 1, null, 100),
                    new RouteNode("j", "new_branch_chamber", point(angle, 0, 0), true, false, 0, null),
                    new RouteNode("demand:a", "demand_connection", point(angle, 88, 60), false, false, 0, "a"),
                    new RouteNode("demand:b", "demand_connection", point(angle, 0, 50), false, false, 0, "b"));
            // Техническое дробление прямой магистрали не должно отдавать ось длинному косому вводу.
            var edges = List.of(edge("backbone", "root", "j", angle, 2, -100, 0, -75, 0, -50, 0, 0, 0),
                    edge("a", "j", "demand:a", angle, 1, 0, 0, 98, 2, 98, 60, 88, 60),
                    edge("b", "j", "demand:b", angle, 1, 0, 0, 0, 50));
            var connections = List.of(new RouteConnection("a", "a", BigDecimal.ONE, "connected", null),
                    new RouteConnection("b", "b", BigDecimal.ONE, "connected", null));
            var demands = List.of(new OfficialRoutePlanner.Demand("a", "a", point(angle, 88, 60).toCoordinate(), BigDecimal.ONE, null),
                    new OfficialRoutePlanner.Demand("b", "b", point(angle, 0, 50).toCoordinate(), BigDecimal.ONE, null));
            var seed = planner.withEngineeringAssessment(planner.finish("balanced", "engineering",
                    new OfficialRoutePlanner.VariantDraft(nodes, edges, connections), List.of(), parameters, false, env,
                    TerminalApproachPolicy.PRESERVE_VALID));
            assertThat(seed.isValid()).isTrue();
            assertThat(evaluator.evaluate(seed.getEdges()).isCompliant()).isTrue();
            assertThat(evaluator.evaluate(seed.getEdges()).irregularJunctionAngleCount()).isEqualTo(2);
            assertThat(CorridorSupportedChamberRelocations.build(nodes.get(1), seed.getEdges(), Math.toRadians(angle),
                    java.util.Set.of("demand:a", "demand:b"), at -> true)).isEmpty();
            var roles = new FinishedRouteVariantSelector().select(List.of(seed), depth);
            var result = planner.improveSelectedChamberQuality(roles, demands, List.of(), parameters, false, env).get(0);
            assertThat(evaluator.evaluate(result.getEdges()).irregularJunctionAngleCount()).isZero();
            assertThat(evaluator.evaluate(result.getEdges()).preservesJunctionQualityOf(evaluator.evaluate(seed.getEdges()))).isTrue();
            assertThat(result.getNodes()).usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrderElementsOf(seed.getNodes());
            assertThat(result.getConnections()).usingRecursiveComparison().isEqualTo(seed.getConnections());
            var originalBackbone = seed.getEdges().stream().filter(e -> "backbone".equals(e.getId())).findFirst().orElseThrow();
            var repairedBackbone = result.getEdges().stream().filter(e -> "backbone".equals(e.getId())).findFirst().orElseThrow();
            var fixedLine = rules.line(originalBackbone.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList()));
            repairedBackbone.getCoordinates().forEach(p -> assertThat(fixedLine.distance(
                    new org.locationtech.jts.geom.GeometryFactory().createPoint(p.toCoordinate()))).isLessThanOrEqualTo(0.002));
            assertThat(result.getConnectedDemandCount()).isEqualTo(2);
            assertThat(result.getTotalLengthM()).isLessThan(seed.getTotalLengthM());
            assertThat(result.getEconomics().getCalculatedCost()).isLessThan(seed.getEconomics().getCalculatedCost());
            assertThat(new OfficialRouteValidator(rules).validate(result.getNodes(), result.getEdges(), List.of())).isEmpty();
            assertThat(new ExpertChamberRouteValidator().validate(result.getNodes(), result.getEdges())).isEmpty();
            if (depth) assertThat(result.getEdges()).allSatisfy(e -> {
                assertThat(e.getDepthProfile().isComplete()).isTrue();
                assertThat(e.getDepthProfile().getIssues()).isEmpty();
            });
        }
    }

    private RouteEdge edge(String id, String from, String to, double angle, int flow, double... xy) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(point(angle, xy[i], xy[i + 1]).toCoordinate());
        return new RouteEdge(id, from, to, rules.line(points).getLength(), points.stream().map(p -> new RouteCoordinate(p.x, p.y))
                .collect(Collectors.toList()), rules.sections(rules.line(points), List.of()), BigDecimal.valueOf(flow), 50);
    }
    private RouteCoordinate point(double degrees, double x, double y) {
        double a = Math.toRadians(degrees);
        return new RouteCoordinate(414000 + x * Math.cos(a) - y * Math.sin(a), 6173000 + x * Math.sin(a) + y * Math.cos(a));
    }
}
