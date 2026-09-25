package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialChamberRelocationTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test
    void jointlyMovesCameraAndAllThreeApproachesRatherThanFreezingEachTerminalSegment() {
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(List.of());
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", p(-20, 10), true, true, 2, "support"),
                new RouteNode("j", "new_branch_chamber", p(0, 0), true, false, 0, null),
                new RouteNode("demand:one", "demand_connection", p(20, 10), false, false, 0, null),
                new RouteNode("demand:two", "demand_connection", p(0, -20), false, false, 0, null));
        List<RouteEdge> edges = List.of(
                edge("a", "root", "j", List.of(c(-20, 10), c(-2, 10), c(-2, 0), c(0, 0)), 2),
                edge("b", "j", "demand:one", List.of(c(0, 0), c(2, 0), c(2, 10), c(20, 10)), 1),
                edge("c", "j", "demand:two", List.of(c(0, 0), c(0, -20)), 1));
        List<RouteConnection> connections = List.of(
                new RouteConnection("one", "one", BigDecimal.ONE, "connected", null),
                new RouteConnection("two", "two", BigDecimal.ONE, "connected", null));
        for (boolean depth : List.of(false, true)) {
            OfficialRunParameters parameters = new OfficialRunParameters(null, null, depth);
            RouteVariant baseline = planner.withEngineeringAssessment(planner.finish("balanced", "engineering",
                    new OfficialRoutePlanner.VariantDraft(nodes, edges, connections), List.of(), parameters, false, environment));
            assertThat(baseline.isValid()).isTrue();
            assertThat(baseline.getTotalLengthM()).isEqualByComparingTo("80");
            List<RouteVariant> roles = planner.relocateSelectedVariants(List.of(baseline), List.of(
                    new OfficialRoutePlanner.Demand("one", "one", c(20, 10), BigDecimal.ONE, null),
                    new OfficialRoutePlanner.Demand("two", "two", c(0, -20), BigDecimal.ONE, null)),
                    List.of(), parameters, false, environment);
            assertThat(roles).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
            assertThat(roles).allSatisfy(role -> {
                assertThat(role.getTotalLengthM()).isEqualByComparingTo("70");
                assertThat(role.isValid()).isTrue();
            });
            RouteVariant improved = planner.relocateFinishedChambers(baseline, List.of(
                    new OfficialRoutePlanner.Demand("one", "one", c(20, 10), BigDecimal.ONE, null),
                    new OfficialRoutePlanner.Demand("two", "two", c(0, -20), BigDecimal.ONE, null)),
                    List.of(), parameters, false, environment);
            assertThat(improved.isValid()).isTrue();
            assertThat(improved.getTotalLengthM()).isEqualByComparingTo("70");
            assertThat(improved.getConnectedDemandCount()).isEqualTo(2);
            assertThat(improved.getNodes()).filteredOn(n -> n.getId().equals("j")).singleElement()
                    .satisfies(n -> assertThat(n.getCoordinate().toCoordinate()).isEqualTo(c(0, 10)));
            assertThat(improved.getNodes()).filteredOn(RouteNode::isRoot).singleElement()
                    .satisfies(n -> assertThat(n.getCoordinate().toCoordinate()).isEqualTo(c(-20, 10)));
            assertThat(improved.getNodes().stream().filter(RouteNode::isChamber).count()).isEqualTo(2);
            assertThat(improved.getConnections()).usingRecursiveComparison().isEqualTo(baseline.getConnections());
            assertThat(new EngineeringRouteEvaluator().evaluate(improved.getEdges()).bendCount()).isZero();
            assertThat(improved.getEconomics().getCalculatedCost()).isLessThan(baseline.getEconomics().getCalculatedCost());
            assertThat(new OfficialRouteValidator(rules).validate(improved.getNodes(), improved.getEdges(), List.of())).isEmpty();
            if (depth) assertThat(improved.getEdges()).allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isTrue());
            assertThat(baseline.getTotalLengthM()).isEqualByComparingTo("80");
        }
    }

    @Test
    void lateCheapestWinnerReceivesEngineeringRepairBeforeRoleSelection() {
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", p(0, 0), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", p(40, 0), false, false, 0, null));
        List<RouteEdge> edges = List.of(edge("late-edge", "root", "demand:one",
                List.of(c(0, 0), c(20, 1), c(40, 0)), 1));
        List<RouteConnection> connections = List.of(new RouteConnection("one", "one", BigDecimal.ONE, "connected", null));
        List<OfficialRoutePlanner.Demand> demands = List.of(
                new OfficialRoutePlanner.Demand("one", "one", c(40, 0), BigDecimal.ONE, null));
        for (boolean depth : List.of(false, true)) {
            OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(List.of());
            OfficialRunParameters parameters = new OfficialRunParameters(null, null, depth);
            RouteVariant lateWinner = planner.withEngineeringAssessment(planner.finish("cheapest", "cheapest",
                    new OfficialRoutePlanner.VariantDraft(nodes, edges, connections), List.of(), parameters, false, environment));
            assertThat(lateWinner.isValid()).isTrue();
            assertThat(new EngineeringRouteEvaluator().evaluate(lateWinner.getEdges()).invalidAngleCount()).isEqualTo(1);

            List<RouteVariant> roles = planner.relocateSelectedVariants(List.of(lateWinner), demands,
                    List.of(), parameters, false, environment);

            assertThat(roles).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
            assertThat(roles).allSatisfy(role -> {
                assertThat(role.isValid()).isTrue();
                assertThat(role.getEngineeringIssues()).isEmpty();
                assertThat(role.getTotalLengthM()).isEqualByComparingTo("40");
                assertThat(role.getConnectedDemandCount()).isEqualTo(1);
                assertThat(role.getEconomics().getCalculatedCost()).isLessThan(lateWinner.getEconomics().getCalculatedCost());
                assertThat(new OfficialRouteValidator(rules).validate(role.getNodes(), role.getEdges(), List.of())).isEmpty();
                if (depth) assertThat(role.getEdges()).allSatisfy(e -> {
                    assertThat(e.getDepthProfile().isComplete()).isTrue();
                    assertThat(e.getDepthProfile().getIssues()).isEmpty();
                });
            });
            assertThat(new EngineeringRouteEvaluator().evaluate(lateWinner.getEdges()).invalidAngleCount()).isEqualTo(1);
            assertThat(lateWinner.getTotalLengthM()).isGreaterThan(new BigDecimal("40"));
        }
    }

    @Test
    void longerAndMoreExpensiveLateRepairDoesNotReplaceTheOriginalEconomicRole() {
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        List<ImportedOfficialFeature> features = List.of(new ImportedOfficialFeature("park", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "park"),
                new GeometryFactory().createPolygon(new Coordinate[] {
                        c(18, -3), c(22, -3), c(22, 3), c(18, 3), c(18, -3)})));
        OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(features);
        OfficialRunParameters parameters = new OfficialRunParameters(null, null, true);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", p(0, 0), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", p(40, 0), false, false, 0, null));
        List<RouteConnection> connections = List.of(new RouteConnection("one", "one", BigDecimal.ONE, "connected", null));
        List<OfficialRoutePlanner.Demand> demands = List.of(
                new OfficialRoutePlanner.Demand("one", "one", c(40, 0), BigDecimal.ONE, null));
        RouteVariant original = planner.withEngineeringAssessment(planner.finish("cheapest", "cheapest",
                new OfficialRoutePlanner.VariantDraft(nodes, List.of(edge("edge", "root", "demand:one",
                        List.of(c(0, 0), c(20, 6), c(40, 0)), 1)), connections), features, parameters, false, environment));
        assertThat(original.isValid()).isTrue();
        assertThat(original.getEngineeringIssues()).isNotEmpty();
        OfficialRoutePlanner.VariantDraft repaired = ReflectionTestUtils.invokeMethod(planner,
                "regularizeEngineeringDraft", new OfficialRoutePlanner.VariantDraft(original.getNodes(),
                        original.getEdges(), original.getConnections()), demands, environment, false);
        RouteVariant uncheckedAlternative = planner.withEngineeringAssessment(planner.finish("probe", "engineering",
                repaired, features, parameters, false, environment));
        assertThat(uncheckedAlternative.isValid()).isTrue();
        assertThat(uncheckedAlternative.getEngineeringIssues()).isEmpty();
        assertThat(uncheckedAlternative.getTotalLengthM()).isGreaterThan(original.getTotalLengthM());
        assertThat(uncheckedAlternative.getEconomics().getCalculatedCost()).isGreaterThan(original.getEconomics().getCalculatedCost());

        List<RouteVariant> result = planner.relocateSelectedVariants(List.of(original), demands,
                features, parameters, false, environment);

        assertThat(result).singleElement().satisfies(variant ->
                assertThat(variant).usingRecursiveComparison().isEqualTo(original));
    }

    @Test
    void localTerminalAlternativesNeverInvokeGlobalFallback() {
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        AtomicInteger calls = new AtomicInteger();
        CorridorTerminalRouter terminals = new CorridorTerminalRouter(router, router.prepare(List.of()),
                (id, target, diameter, avoidance) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("No global search during a local relocation");
                }, 0);
        List<RoutePath> choices = terminals.localAlternatives("one", c(20, 10), c(0, 10), 50);
        assertThat(calls).hasValue(0);
        assertThat(choices).isNotEmpty().anyMatch(path -> Math.abs(path.lengthM() - 20) < 0.001);
    }

    private RouteEdge edge(String id, String from, String to, List<Coordinate> points, int flow) {
        return new RouteEdge(id, from, to, rules.line(points).getLength(),
                points.stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()),
                rules.sections(rules.line(points), List.of()), BigDecimal.valueOf(flow), 50);
    }
    private static Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6100000 + y); }
    private static RouteCoordinate p(double x, double y) { return new RouteCoordinate(500000 + x, 6100000 + y); }
}
