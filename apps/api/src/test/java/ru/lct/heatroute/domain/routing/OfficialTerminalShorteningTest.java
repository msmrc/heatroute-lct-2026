package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialTerminalShorteningTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test
    void shortensAlreadyCompliantInputsWithoutMovingCamerasOrLosingTheControl() {
        RegressionRoutePlannerFixture planner = new OfficialDatasetRoutingTest().planner();
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", p(-20, 0), true, true, 2, "support"),
                new RouteNode("j", "new_branch_chamber", p(0, 0), true, false, 0, null),
                new RouteNode("demand:one", "demand_connection", p(20, 0), false, false, 0, null),
                new RouteNode("demand:two", "demand_connection", p(0, -20), false, false, 0, null));
        List<RouteEdge> edges = List.of(
                edge("trunk", "root", "j", List.of(c(-20, 0), c(0, 0)), 2),
                edge("one", "j", "demand:one", List.of(c(0, 0), c(0, 10), c(8, 10), c(8, 0), c(20, 0)), 1),
                edge("two", "j", "demand:two", List.of(c(0, 0), c(0, -20)), 1));
        List<RouteConnection> connections = List.of(
                new RouteConnection("one", "one", BigDecimal.ONE, "connected", null),
                new RouteConnection("two", "two", BigDecimal.ONE, "connected", null));
        List<RegressionRoutePlannerFixture.Demand> demands = List.of(
                new RegressionRoutePlannerFixture.Demand("one", "one", c(20, 0), BigDecimal.ONE, null),
                new RegressionRoutePlannerFixture.Demand("two", "two", c(0, -20), BigDecimal.ONE, null));
        for (boolean depth : List.of(false, true)) {
            OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(List.of());
            OfficialRunParameters parameters = new OfficialRunParameters(null, null, depth);
            RouteVariant baseline = planner.withEngineeringAssessment(planner.finish("balanced", "engineering",
                    new RegressionRoutePlannerFixture.VariantDraft(nodes, edges, connections), List.of(), parameters, false, environment));
            assertThat(baseline.isValid()).isTrue();
            assertThat(baseline.getEngineeringIssues()).isEmpty();
            assertThat(baseline.getTotalLengthM()).isEqualByComparingTo("80");

            List<RouteVariant> roles = planner.shortenSelectedTerminals(List.of(baseline), demands,
                    List.of(), parameters, false, environment);

            assertThat(roles).allSatisfy(role -> assertThat(role.getTotalLengthM()).isEqualByComparingTo("60"));
            assertThat(roles).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
            assertThat(roles).allSatisfy(role -> {
                assertThat(role.getTotalLengthM()).isEqualByComparingTo("60");
                assertThat(role.getConnectedDemandCount()).isEqualTo(2);
                assertThat(role.getNodes()).usingRecursiveComparison().isEqualTo(baseline.getNodes());
                assertThat(role.getConnections()).usingRecursiveComparison().isEqualTo(baseline.getConnections());
                assertThat(role.getEconomics().getCalculatedCost()).isLessThan(baseline.getEconomics().getCalculatedCost());
                assertThat(role.getEngineeringIssues()).isEmpty();
                assertThat(new EngineeringRouteEvaluator().evaluate(role.getEdges()).bendCount()).isZero();
                assertThat(new OfficialRouteValidator(rules).validate(role.getNodes(), role.getEdges(), List.of())).isEmpty();
                if (depth) assertThat(role.getEdges()).allSatisfy(e -> {
                    assertThat(e.getDepthProfile().isComplete()).isTrue();
                    assertThat(e.getDepthProfile().getIssues()).isEmpty();
                });
            });
            assertThat(baseline.getTotalLengthM()).isEqualByComparingTo("80");
            assertThat(new EngineeringRouteEvaluator().evaluate(baseline.getEdges()).bendCount()).isEqualTo(3);
        }
    }

    @Test
    void shorterProposalCannotCutAcrossARealForbiddenObstacle() {
        RegressionRoutePlannerFixture planner = new OfficialDatasetRoutingTest().planner();
        List<ImportedOfficialFeature> features = List.of(new ImportedOfficialFeature("park", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "park"),
                new GeometryFactory().createPolygon(new Coordinate[] {
                        c(3.9, -0.1), c(4.1, -0.1), c(4.1, 0.1), c(3.9, 0.1), c(3.9, -0.1)})));
        OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(features);
        OfficialRunParameters parameters = new OfficialRunParameters(null, null, true);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", p(-20, 0), true, true, 2, "support"),
                new RouteNode("j", "new_branch_chamber", p(0, 0), true, false, 0, null),
                new RouteNode("demand:one", "demand_connection", p(20, 0), false, false, 0, null),
                new RouteNode("demand:two", "demand_connection", p(0, -20), false, false, 0, null));
        List<RouteEdge> edges = List.of(
                edge("trunk", "root", "j", List.of(c(-20, 0), c(0, 0)), 2),
                edge("one", "j", "demand:one", List.of(c(0, 0), c(0, 10), c(8, 10), c(8, 0), c(20, 0)), 1),
                edge("two", "j", "demand:two", List.of(c(0, 0), c(0, -20)), 1));
        List<RouteConnection> connections = List.of(
                new RouteConnection("one", "one", BigDecimal.ONE, "connected", null),
                new RouteConnection("two", "two", BigDecimal.ONE, "connected", null));
        RouteVariant baseline = planner.withEngineeringAssessment(planner.finish("balanced", "engineering",
                new RegressionRoutePlannerFixture.VariantDraft(nodes, edges, connections), features, parameters, false, environment));
        assertThat(baseline.isValid()).isTrue();
        assertThat(baseline.getEngineeringIssues()).isEmpty();
        assertThat(new OfficialRouteValidator(rules).validate(nodes,
                List.of(edges.get(0), edge("one", "j", "demand:one", List.of(c(0, 0), c(20, 0)), 1), edges.get(2)),
                features)).isNotEmpty();

        List<RouteVariant> roles = planner.shortenSelectedTerminals(List.of(baseline), List.of(
                new RegressionRoutePlannerFixture.Demand("one", "one", c(20, 0), BigDecimal.ONE, null),
                new RegressionRoutePlannerFixture.Demand("two", "two", c(0, -20), BigDecimal.ONE, null)),
                features, parameters, false, environment);

        assertThat(roles).isNotEmpty().allSatisfy(role -> {
            assertThat(role.isValid()).isTrue();
            assertThat(role.getEngineeringIssues()).isEmpty();
            assertThat(role.getTotalLengthM()).isGreaterThan(new BigDecimal("60"));
            assertThat(role.getTotalLengthM()).isLessThanOrEqualTo(baseline.getTotalLengthM());
            assertThat(role.getConnectedDemandCount()).isEqualTo(2);
            assertThat(new OfficialRouteValidator(rules).validate(role.getNodes(), role.getEdges(), features)).isEmpty();
            assertThat(role.getEdges()).allSatisfy(e -> {
                assertThat(e.getDepthProfile().isComplete()).isTrue();
                assertThat(e.getDepthProfile().getIssues()).isEmpty();
            });
        });
    }

    private RouteEdge edge(String id, String from, String to, List<Coordinate> points, int flow) {
        return new RouteEdge(id, from, to, rules.line(points).getLength(),
                points.stream().map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()),
                rules.sections(rules.line(points), List.of()), BigDecimal.valueOf(flow), 50);
    }
    private static Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6100000 + y); }
    private static RouteCoordinate p(double x, double y) { return new RouteCoordinate(500000 + x, 6100000 + y); }
}
