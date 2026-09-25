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

/** Настоящие candidate → rebuild → sizing/depth/economics → selector, без подмены callback. */
class OfficialChamberQualityRefinementTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final EngineeringRouteEvaluator engineering = new EngineeringRouteEvaluator();

    @ParameterizedTest
    @ValueSource(doubles = {0, 31, 90})
    void separatesAlmostParallelInputsByMovingTheSharedChamberInBothDepthModes(double angle) {
        var planner = new OfficialDatasetRoutingTest().planner();
        for (boolean depth : List.of(false, true)) {
            var parameters = new OfficialRunParameters(null, null, depth);
            var environment = new OfficialObstacleRouter(rules).prepare(List.of());
            List<RouteNode> nodes = List.of(
                    new RouteNode("root", "existing_chamber_tie_in", point(angle, -100, 0), true, true, 1, null, 100),
                    new RouteNode("j", "new_branch_chamber", point(angle, 0, 0), true, false, 0, null),
                    new RouteNode("demand:a", "demand_connection", point(angle, 30, 20), false, false, 0, "a"),
                    new RouteNode("demand:b", "demand_connection", point(angle, 20, -0.001), false, false, 0, "b"));
            List<RouteEdge> edges = List.of(
                    edge("backbone", "root", "j", angle, 2, -100, 0, 0, 0),
                    edge("a", "j", "demand:a", angle, 1, 0, 0, 10, 0, 10, 20, 30, 20),
                    edge("b", "j", "demand:b", angle, 1, 0, 0, 20, -0.001));
            List<RouteConnection> connections = List.of(new RouteConnection("a", "a", BigDecimal.ONE, "connected", null),
                    new RouteConnection("b", "b", BigDecimal.ONE, "connected", null));
            List<OfficialRoutePlanner.Demand> demands = List.of(
                    new OfficialRoutePlanner.Demand("a", "a", point(angle, 30, 20).toCoordinate(), BigDecimal.ONE, null),
                    new OfficialRoutePlanner.Demand("b", "b", point(angle, 20, -0.001).toCoordinate(), BigDecimal.ONE, null));
            var seed = planner.withEngineeringAssessment(planner.finish("cheapest", "cheapest",
                    new OfficialRoutePlanner.VariantDraft(nodes, edges, connections), List.of(), parameters, false,
                    environment, TerminalApproachPolicy.PRESERVE_VALID));
            assertThat(seed.isValid()).isTrue();
            assertThat(seed.getEngineeringIssues()).isEmpty();
            assertThat(engineering.evaluate(seed.getEdges()).irregularJunctionAngleCount()).isPositive();
            var originalRoles = new FinishedRouteVariantSelector().select(List.of(seed), depth);
            var roles = planner.improveSelectedChamberQuality(originalRoles, demands, List.of(), parameters, false, environment);
            assertThat(roles).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
            RouteVariant result = roles.get(0);
            assertThat(engineering.evaluate(result.getEdges()).irregularJunctionAngleCount()).isZero();
            assertThat(result.getConnectedDemandCount()).isEqualTo(2);
            assertThat(result.getConnections()).usingRecursiveComparison().isEqualTo(seed.getConnections());
            assertThat(result.getTotalLengthM()).isLessThan(seed.getTotalLengthM());
            assertThat(result.getEconomics().getCalculatedCost()).isLessThan(seed.getEconomics().getCalculatedCost());
            assertThat(new OfficialRouteValidator(rules).validate(result.getNodes(), result.getEdges(), List.of())).isEmpty();
            assertThat(new ExpertChamberRouteValidator().validate(result.getNodes(), result.getEdges())).isEmpty();
            assertThat(result.getNodes()).filteredOn(RouteNode::isRoot).usingRecursiveFieldByFieldElementComparator()
                    .containsExactlyElementsOf(seed.getNodes().stream().filter(RouteNode::isRoot).collect(Collectors.toList()));
            if (depth) assertThat(result.getEdges()).allSatisfy(e -> {
                assertThat(e.getDepthProfile().isComplete()).isTrue();
                assertThat(e.getDepthProfile().getIssues()).isEmpty();
            });
            assertThat(engineering.evaluate(seed.getEdges()).irregularJunctionAngleCount()).isPositive();
            // Регулярный cheapest не должен отключать ремонт другой, ещё плохой роли balanced.
            var mixed = List.of(asRole(seed, "balanced"), asRole(result, "shortest"), asRole(result, "cheapest"));
            var second = planner.improveSelectedChamberQuality(mixed, demands, List.of(), parameters, false, environment);
            assertThat(engineering.evaluate(second.get(0).getEdges()).irregularJunctionAngleCount()).isZero();
        }
    }

    private RouteVariant asRole(RouteVariant source, String role) {
        return new RouteVariant(role, "balanced".equals(role) ? "engineering" : role,
                source.getNodes(), source.getEdges(), source.getConnections(), source.getTotalLengthM(),
                source.getValidationIssues(), source.getEngineeringIssues(), source.getSizingIssues(),
                source.getReconstruction(), source.getEconomics(), null);
    }

    private RouteEdge edge(String id, String from, String to, double angle, int flow, double... xy) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) coordinates.add(point(angle, xy[i], xy[i + 1]).toCoordinate());
        return new RouteEdge(id, from, to, rules.line(coordinates).getLength(),
                coordinates.stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()),
                rules.sections(rules.line(coordinates), List.of()), BigDecimal.valueOf(flow), 50);
    }

    private RouteCoordinate point(double degrees, double x, double y) {
        double angle = Math.toRadians(degrees);
        return new RouteCoordinate(414000 + x * Math.cos(angle) - y * Math.sin(angle),
                6173000 + x * Math.sin(angle) + y * Math.cos(angle));
    }
}
