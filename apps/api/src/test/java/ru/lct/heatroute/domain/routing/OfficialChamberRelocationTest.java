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
            assertThat(new ExpertChamberRouteValidator().validate(improved.getNodes(), improved.getEdges())).isEmpty();
            assertThat(ExpertRouteBendRules.validate(improved.getNodes(), improved.getEdges())).isEmpty();
            if (depth) assertThat(improved.getEdges()).allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isTrue());
            assertThat(baseline.getTotalLengthM()).isEqualByComparingTo("80");
        }
    }

    @Test
    void legalLateWinnerDoesNotRequireEngineeringRepair() {
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", p(0, 0), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", p(40, 0), false, false, 0, null));
        List<RouteEdge> edges = List.of(edge("late-edge", "root", "demand:one",
                List.of(c(0, 0), c(0, 20), c(40, 20), c(40, 0)), 1));
        List<RouteConnection> connections = List.of(new RouteConnection("one", "one", BigDecimal.ONE, "connected", null));
        List<OfficialRoutePlanner.Demand> demands = List.of(
                new OfficialRoutePlanner.Demand("one", "one", c(40, 0), BigDecimal.ONE, null));
        for (boolean depth : List.of(false, true)) {
            OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(List.of());
            OfficialRunParameters parameters = new OfficialRunParameters(null, null, depth);
            RouteVariant lateWinner = planner.withEngineeringAssessment(planner.finish("cheapest", "cheapest",
                    new OfficialRoutePlanner.VariantDraft(nodes, edges, connections), List.of(), parameters, false, environment));
            assertThat(lateWinner.isValid()).isTrue();
            assertThat(new EngineeringRouteEvaluator().evaluate(lateWinner.getEdges()).invalidAngleCount()).isZero();
            RouteVariant repair = ReflectionTestUtils.invokeMethod(planner, "repairLateEconomicWinner",
                    List.of(lateWinner), demands, List.of(), parameters, false, environment);
            assertThat(repair).isNull();

            List<RouteVariant> roles = planner.relocateSelectedVariants(List.of(lateWinner), demands,
                    List.of(), parameters, false, environment);

            assertThat(roles).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
            assertThat(roles).allSatisfy(role -> {
                assertThat(role.isValid()).isTrue();
                assertThat(role.getEngineeringIssues()).isEmpty();
                assertThat(role.getTotalLengthM()).isEqualByComparingTo(lateWinner.getTotalLengthM());
                assertThat(role.getEdges()).usingRecursiveComparison().isEqualTo(lateWinner.getEdges());
                assertThat(role.getConnectedDemandCount()).isEqualTo(1);
                assertThat(role.getEconomics().getCalculatedCost()).isEqualByComparingTo(lateWinner.getEconomics().getCalculatedCost());
                assertThat(new OfficialRouteValidator(rules).validate(role.getNodes(), role.getEdges(), List.of())).isEmpty();
                if (depth) assertThat(role.getEdges()).allSatisfy(e -> {
                    assertThat(e.getDepthProfile().isComplete()).isTrue();
                    assertThat(e.getDepthProfile().getIssues()).isEmpty();
                });
            });
            assertThat(new EngineeringRouteEvaluator().evaluate(lateWinner.getEdges()).invalidAngleCount()).isZero();
            assertThat(lateWinner.getTotalLengthM()).isGreaterThan(new BigDecimal("40"));
        }
    }

    @Test
    void legalObstacleDetourIsNotReplacedByUnnecessaryRepair() {
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
                        List.of(c(0, 0), c(0, 10), c(40, 10), c(40, 0)), 1)), connections), features, parameters, false, environment));
        assertThat(original.isValid()).isTrue();
        assertThat(original.getEngineeringIssues()).isEmpty();
        OfficialRoutePlanner.VariantDraft repaired = ReflectionTestUtils.invokeMethod(planner,
                "regularizeEngineeringDraft", new OfficialRoutePlanner.VariantDraft(original.getNodes(),
                        original.getEdges(), original.getConnections()), demands, environment, false);
        RouteVariant uncheckedAlternative = planner.withEngineeringAssessment(planner.finish("probe", "engineering",
                repaired, features, parameters, false, environment));
        assertThat(uncheckedAlternative.isValid()).isTrue();
        assertThat(uncheckedAlternative.getEngineeringIssues()).isEmpty();
        assertThat(uncheckedAlternative.getEdges()).usingRecursiveComparison().isEqualTo(original.getEdges());
        assertThat(uncheckedAlternative.getTotalLengthM()).isEqualByComparingTo(original.getTotalLengthM());
        assertThat(uncheckedAlternative.getEconomics().getCalculatedCost()).isEqualByComparingTo(original.getEconomics().getCalculatedCost());

        List<RouteVariant> result = planner.relocateSelectedVariants(List.of(original), demands,
                features, parameters, false, environment);

        assertThat(result).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
        assertThat(result).allSatisfy(variant -> {
            assertThat(variant.isValid()).isTrue();
            assertThat(variant.getEngineeringIssues()).isEmpty();
            assertThat(variant.getEdges()).usingRecursiveComparison().isEqualTo(original.getEdges());
            assertThat(variant.getTotalLengthM()).isEqualByComparingTo(original.getTotalLengthM());
            assertThat(variant.getEconomics().getCalculatedCost()).isEqualByComparingTo(original.getEconomics().getCalculatedCost());
        });
    }

    @Test
    void legalLateEconomicCandidateDoesNotDisplaceOriginalChamberRelocationOpportunities() {
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        // Большой существующий ДУ делает вариант с новой корневой камерой дороже двух готовых врезок.
        List<ImportedOfficialFeature> features = List.of(new ImportedOfficialFeature("support", "heat_network",
                new ObjectMapper().createObjectNode().put("diameter", 1000),
                new GeometryFactory().createLineString(new Coordinate[] {c(-20, 0), c(-20, 20)})));
        OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(features);
        OfficialRunParameters parameters = new OfficialRunParameters(null, null, true);
        List<RouteConnection> connections = List.of(
                new RouteConnection("one", "one", BigDecimal.ONE, "connected", null),
                new RouteConnection("two", "two", BigDecimal.ONE, "connected", null));
        List<OfficialRoutePlanner.Demand> demands = List.of(
                new OfficialRoutePlanner.Demand("one", "one", c(20, 10), BigDecimal.ONE, null),
                new OfficialRoutePlanner.Demand("two", "two", c(0, -20), BigDecimal.ONE, null));
        List<RouteNode> commonDemands = List.of(
                new RouteNode("demand:one", "demand_connection", p(20, 10), false, false, 0, null),
                new RouteNode("demand:two", "demand_connection", p(0, -20), false, false, 0, null));
        RouteVariant baseline = planner.withEngineeringAssessment(planner.finish("balanced", "engineering",
                new OfficialRoutePlanner.VariantDraft(List.of(
                        new RouteNode("root", "new_tie_in_chamber", p(-20, 10), true, true, 2, "support"),
                        new RouteNode("j", "new_branch_chamber", p(0, 0), true, false, 0, null),
                        commonDemands.get(0), commonDemands.get(1)), List.of(
                        edge("a", "root", "j", List.of(c(-20, 10), c(-2, 10), c(-2, 0), c(0, 0)), 2),
                        edge("b", "j", "demand:one", List.of(c(0, 0), c(2, 0), c(2, 10), c(20, 10)), 1),
                        edge("c", "j", "demand:two", List.of(c(0, 0), c(0, -20)), 1)), connections),
                features, parameters, false, environment));
        RouteVariant late = planner.withEngineeringAssessment(planner.finish("cheapest", "cheapest",
                new OfficialRoutePlanner.VariantDraft(List.of(
                        new RouteNode("root-one", "existing_chamber_tie_in", p(-17, 10), true, true, 2, "support-one"),
                        new RouteNode("root-two", "existing_chamber_tie_in", p(0, -57.5), true, true, 2, "support-two"),
                        commonDemands.get(0), commonDemands.get(1)), List.of(
                        edge("late-a", "root-one", "demand:one", List.of(c(-17, 10), c(20, 10)), 1),
                        edge("late-b", "root-two", "demand:two", List.of(c(0, -57.5), c(0, -20)), 1)), connections),
                features, parameters, false, environment));
        assertThat(baseline.isValid()).isTrue();
        assertThat(baseline.getTotalLengthM()).isEqualByComparingTo("80");
        assertThat(late.isValid()).isTrue();
        assertThat(late.getEngineeringIssues()).isEmpty();
        assertThat(late.getEconomics().getCalculatedCost()).isLessThan(baseline.getEconomics().getCalculatedCost());
        RouteVariant originalRelocation = planner.relocateFinishedChambers(baseline, demands,
                features, parameters, false, environment);
        assertThat(originalRelocation.getTotalLengthM()).isEqualByComparingTo("70");
        RouteVariant lateRepair = ReflectionTestUtils.invokeMethod(planner, "repairLateEconomicWinner",
                List.of(late), demands, features, parameters, false, environment);
        assertThat(lateRepair).isNull();
        RouteVariant shortest = new FinishedRouteVariantSelector().select(List.of(baseline), true).stream()
                .filter(v -> "shortest".equals(v.getId())).findFirst().orElseThrow();

        List<RouteVariant> result = planner.relocateSelectedVariants(List.of(baseline, shortest, late), demands,
                features, parameters, false, environment);

        assertThat(result).filteredOn(v -> "shortest".equals(v.getId())).singleElement().satisfies(v -> {
            assertThat(v.getTotalLengthM()).isEqualByComparingTo("70");
            assertThat(v.getConnectedDemandCount()).isEqualTo(2);
            assertThat(v.getEngineeringIssues()).isEmpty();
            assertThat(new OfficialRouteValidator(rules).validate(v.getNodes(), v.getEdges(), features)).isEmpty();
            assertThat(v.getEdges()).allSatisfy(e -> {
                assertThat(e.getDepthProfile().isComplete()).isTrue();
                assertThat(e.getDepthProfile().getIssues()).isEmpty();
            });
        });
        assertThat(baseline.getTotalLengthM()).isEqualByComparingTo("80");
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

    @Test
    void relocatedChamberOnTheBuildingNormalUsesTheDirectCheckedApproach() {
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        ImportedOfficialFeature home = new ImportedOfficialFeature("home", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "oks"),
                new GeometryFactory().createPolygon(new Coordinate[] {
                        c(0, 0), c(20, 0), c(20, 20), c(0, 20), c(0, 0)}));
        List<ImportedOfficialFeature> features = List.of(home);
        OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(features);
        Coordinate connection = c(1, 10), chamber = c(-10, 10);
        OfficialRouteGeometryRules.NormalEgress egress = rules.normalEgressCandidates(
                features, 50, connection, chamber,
                RoutePlannerTuning.stable().getEngineeringEgressExtraM()).stream()
                .findFirst().orElseThrow();
        OfficialRoutePlanner.Demand demand = new OfficialRoutePlanner.Demand(
                "one", "one", connection, BigDecimal.ONE, egress);

        RoutePath path = ReflectionTestUtils.invokeMethod(
                planner, "directTerminalApproach", demand, chamber, 50, environment);

        assertThat(path).isNotNull();
        assertThat(path.coordinates().get(0).distance(connection)).isLessThan(0.002);
        assertThat(path.coordinates().get(path.coordinates().size() - 1).distance(chamber)).isLessThan(0.002);
        RouteEdge checked = edge("direct", "demand:one", "chamber", path.coordinates(), 1);
        assertThat(new EngineeringRouteEvaluator().evaluate(List.of(checked)).bendCount()).isZero();
        assertThat(ExpertRouteBendRules.validate(List.of(
                new RouteNode("demand:one", "demand_connection", p(1, 10), false, false, 0, null),
                new RouteNode("chamber", "new_branch_chamber", p(-10, 10), true, false, 0, null)),
                List.of(checked))).isEmpty();
    }

    @Test
    void bendReductionAcceptsOnlySubHalfDegreeDigitizedJunctionDrift() {
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        EngineeringRouteEvaluator evaluator = new EngineeringRouteEvaluator();
        List<RouteEdge> beforeEdges = List.of(
                edge("left", "j", "left-end", List.of(c(0, 0), c(-10, 0)), 1),
                edge("right", "j", "right-end", List.of(c(0, 0), c(10, 0)), 1),
                edge("branch", "j", "branch-end", List.of(c(0, 0), c(0, 10)), 1),
                edge("detour", "x", "y", List.of(c(100, 0), c(110, 0), c(110, 10), c(120, 10)), 1));
        EngineeringRouteEvaluator.Evaluation before = evaluator.evaluate(beforeEdges);

        assertThat(improvesJunctionGeometry(planner, before, evaluator.evaluate(
                straightenedWithJunctionAngle(89.8)))).isTrue();
        assertThat(improvesJunctionGeometry(planner, before, evaluator.evaluate(
                straightenedWithJunctionAngle(89.7)))).isFalse();
    }

    private boolean improvesJunctionGeometry(OfficialRoutePlanner planner,
            EngineeringRouteEvaluator.Evaluation before, EngineeringRouteEvaluator.Evaluation after) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                planner, "improvesJunctionGeometry", before, after));
    }

    private List<RouteEdge> straightenedWithJunctionAngle(double angleDegrees) {
        double angle = Math.toRadians(angleDegrees);
        return List.of(
                edge("left", "j", "left-end", List.of(c(0, 0), c(-10, 0)), 1),
                edge("right", "j", "right-end", List.of(c(0, 0), c(10, 0)), 1),
                edge("branch", "j", "branch-end", List.of(
                        c(0, 0), c(10 * Math.cos(angle), 10 * Math.sin(angle))), 1),
                edge("detour", "x", "y", List.of(c(100, 0), c(120, 0)), 1));
    }

    private RouteEdge edge(String id, String from, String to, List<Coordinate> points, int flow) {
        return new RouteEdge(id, from, to, rules.line(points).getLength(),
                points.stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()),
                rules.sections(rules.line(points), List.of()), BigDecimal.valueOf(flow), 50);
    }
    private static Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6100000 + y); }
    private static RouteCoordinate p(double x, double y) { return new RouteCoordinate(500000 + x, 6100000 + y); }
}
