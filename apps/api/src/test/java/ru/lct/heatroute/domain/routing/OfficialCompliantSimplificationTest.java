package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialCompliantSimplificationTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void boundedGeometryBudgetAlsoAppliesWhenTheFinishedNetworkIsShorter() {
        RegressionRoutePlannerFixture planner = planner(new OfficialObstacleRouter(rules));
        RouteVariant baseline = budgetVariant("100.000", "300000000.00");

        assertThat(planner.regularizationBudgetAllows(
                baseline, budgetVariant("99.075", "300024783.41"))).isTrue();
        assertThat(planner.regularizationBudgetAllows(
                baseline, budgetVariant("99.075", "301250000.01"))).isFalse();
        assertThat(planner.regularizationBudgetAllows(
                baseline, budgetVariant("150.001", "299000000.00"))).isFalse();
    }

    @Test
    void removesLegalDoglegWithoutMovingTerminalRaysOrNormalBuildingApproach() {
        List<ImportedOfficialFeature> features = List.of(
                new ImportedOfficialFeature("own-oks", "oks_existing", json.createObjectNode(),
                        new GeometryFactory().createPolygon(new Coordinate[] {
                                c(0, 0), c(20, 0), c(20, 20), c(0, 20), c(0, 0)})),
                new ImportedOfficialFeature("support", "heat_network",
                        json.createObjectNode().put("diameter", 50),
                        rules.line(List.of(c(-80, -40), c(-80, 60)))));
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OfficialRoutingEnvironment environment = router.prepare(features);
        Coordinate exit = environment.normalEgressTowards(50, c(1, 10), c(-80, 10)).orElseThrow().exit();
        List<Coordinate> coordinates = List.of(c(-80, 10), c(-60, 10), c(-60, -20),
                c(-40, -20), c(-40, 10), exit, c(1, 10));
        RouteEdge original = edge(coordinates);
        assertThat(new EngineeringRouteEvaluator().evaluate(List.of(original)).isCompliant()).isTrue();
        for (boolean depth : List.of(false, true)) {
            for (String strategy : List.of("engineering", "shortest", "cheapest")) {
                RouteVariant result = planner(router).finish("test", strategy,
                        new RegressionRoutePlannerFixture.VariantDraft(nodes(), List.of(original),
                                List.of(new RouteConnection("one", "one", BigDecimal.ONE, "connected", null))),
                        features, new OfficialRunParameters(null, null, depth).validated(), false, environment);
                assertThat(result.isValid()).isTrue();
                assertThat(result.getTotalLengthM()).isEqualByComparingTo("81.000");
                assertThat(result.getSizingIssues()).isEmpty();
                assertThat(result.getEconomics().isComplete()).isTrue();
                RouteEdge shortened = result.getEdges().get(0);
                assertThat(new EngineeringRouteEvaluator().evaluate(result.getEdges()).bendCount()).isZero();
                assertThat(OfficialRouteDeflectionRules.validate(result.getNodes(), result.getEdges())).isEmpty();
                assertThat(shortened.getCoordinates().get(0).toCoordinate()).isEqualTo(coordinates.get(0));
                assertThat(shortened.getCoordinates().get(1).toCoordinate()).isEqualTo(coordinates.get(1));
                List<RouteCoordinate> points = shortened.getCoordinates();
                assertThat(points.get(points.size() - 2).toCoordinate()).isEqualTo(exit);
                assertThat(points.get(points.size() - 1).toCoordinate()).isEqualTo(c(1, 10));
                if (depth) assertThat(shortened.getDepthProfile().isComplete()).isTrue();
            }
        }
        assertThat(original.getLengthM()).isEqualByComparingTo("141.000");
        assertThat(original.getCoordinates()).hasSize(7);
    }

    @Test
    void retainsLongerRouteWhenShortcutAddsMoreExpensiveSpecialConstruction() {
        List<Coordinate> coordinates = List.of(c(-200, 10), c(-180, 10), c(-180, 0),
                c(-20, 0), c(-20, 10), c(1, 10));
        ImportedOfficialFeature gas = new ImportedOfficialFeature("tram", "restriction",
                json.createObjectNode().put("restriction_type", "tram_tracks"),
                new GeometryFactory().createPolygon(new Coordinate[] {
                        c(-160, 8), c(-40, 8), c(-40, 12), c(-160, 12), c(-160, 8)}));
        List<ImportedOfficialFeature> features = List.of(gas);
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        RouteEdge original = edge(coordinates);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", point(-200, 10), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", point(1, 10), false, false, 0, null));
        RoutePath shortcut = new RetainedEndpointSimplifier().simplify(original, router, router.prepare(features),
                java.util.Set.of(), List.of());
        assertThat(shortcut).isNotNull();
        assertThat(shortcut.lengthM()).isLessThan(original.getLengthM().doubleValue());
        assertThat(shortcut.sections()).anySatisfy(section -> assertThat(section.getKind()).isEqualTo("special"));
        RouteEdge expensive = new RouteEdge("edge", "root", "demand:one", shortcut.lengthM(),
                shortcut.coordinates().stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()),
                shortcut.sections(), BigDecimal.ONE, 50);
        OfficialVariantEconomicsCalculator economics = new OfficialVariantEconomicsCalculator(pipes, new OfficialEconomics());
        assertThat(economics.marginalConnectionCost(List.of(expensive), List.of()))
                .isGreaterThan(economics.marginalConnectionCost(List.of(original), List.of()));

        RouteVariant result = planner(router).finish("test", "engineering",
                new RegressionRoutePlannerFixture.VariantDraft(nodes, List.of(original),
                        List.of(new RouteConnection("one", "one", BigDecimal.ONE, "connected", null))),
                features, OfficialRunParameters.defaults(), false, router.prepare(features));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getTotalLengthM()).isEqualByComparingTo("221.000");
        assertThat(result.getEdges().get(0).getCoordinates()).usingRecursiveComparison()
                .isEqualTo(original.getCoordinates());
    }

    @Test
    void shortcutCannotCrossASeparateExistingNewRouteBranch() {
        List<Coordinate> coordinates = List.of(c(-80, 10), c(-60, 10), c(-60, -20),
                c(-40, -20), c(-40, 10), c(1, 10));
        RouteEdge original = edge(coordinates);
        List<Coordinate> otherPoints = List.of(c(-50, 0), c(-50, 20));
        RouteEdge other = new RouteEdge("other", "other-root", "demand:two", 20,
                otherPoints.stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()),
                rules.sections(rules.line(otherPoints), List.of()), BigDecimal.ONE, 50);
        List<RouteNode> nodes = new java.util.ArrayList<>(nodes());
        nodes.add(new RouteNode("other-root", "existing_chamber_tie_in", point(-50, 0), true, true, 2, "other-support"));
        nodes.add(new RouteNode("demand:two", "demand_connection", point(-50, 20), false, false, 0, null));
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        RouteVariant result = planner(router).finish("test", "engineering",
                new RegressionRoutePlannerFixture.VariantDraft(nodes, List.of(original, other), List.of(
                        new RouteConnection("one", "one", BigDecimal.ONE, "connected", null),
                        new RouteConnection("two", "two", BigDecimal.ONE, "connected", null))),
                List.of(), OfficialRunParameters.defaults(), false, router.prepare(List.of()));
        assertThat(result.isValid()).isTrue();
        assertThat(new OfficialRouteValidator(rules).validate(result.getNodes(), result.getEdges(), List.of())).isEmpty();
        assertThat(result.getTotalLengthM()).isGreaterThan(new BigDecimal("101"));
        assertThat(result.getConnectedDemandCount()).isEqualTo(2);
    }

    @Test
    void shortcutCannotIntersectAnotherBranchOfTheSameRootAwayFromThatRoot() {
        RouteEdge original = edge(List.of(c(0, 0), c(10, 0), c(10, 10), c(30, 10), c(30, 0), c(40, 0)));
        List<Coordinate> otherPoints = List.of(c(0, 0), c(0, -10), c(20, -10), c(20, 5));
        RouteEdge other = new RouteEdge("other", "root", "demand:two", 45,
                otherPoints.stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()),
                rules.sections(rules.line(otherPoints), List.of()), BigDecimal.ONE, 50);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", point(0, 0), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", point(40, 0), false, false, 0, null),
                new RouteNode("demand:two", "demand_connection", point(20, 5), false, false, 0, null));
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        RouteVariant result = planner(router).finish("test", "engineering",
                new RegressionRoutePlannerFixture.VariantDraft(nodes, List.of(original, other), List.of(
                        new RouteConnection("one", "one", BigDecimal.ONE, "connected", null),
                        new RouteConnection("two", "two", BigDecimal.ONE, "connected", null))),
                List.of(), OfficialRunParameters.defaults(), false, router.prepare(List.of()));
        assertThat(result.isValid()).isTrue();
        assertThat(result.getTotalLengthM()).isEqualByComparingTo("105.000");
        assertThat(result.getEdges().get(0).getCoordinates()).usingRecursiveComparison()
                .isEqualTo(original.getCoordinates());
        assertThat(result.getConnectedDemandCount()).isEqualTo(2);
    }

    @Test
    void retainedNeighbourDirectionsRejectAnObtuseDirectionChangeAtTheSplice() {
        RouteEdge original = edge(List.of(c(0, 0), c(10, 0), c(10, 10), c(0, 10), c(-1, 0)));
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", point(0, 0), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", point(-1, 0), false, false, 0, null));
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        assertThat(new RetainedEndpointSimplifier().simplify(original, router, router.prepare(List.of()),
                java.util.Set.of(), List.of())).isNull();
        RouteVariant result = planner(router).finish("test", "engineering",
                new RegressionRoutePlannerFixture.VariantDraft(nodes, List.of(original),
                        List.of(new RouteConnection("one", "one", BigDecimal.ONE, "connected", null))),
                List.of(), OfficialRunParameters.defaults(), false, router.prepare(List.of()));
        assertThat(result.isValid()).isTrue();
        assertThat(result.getEdges().get(0).getCoordinates()).usingRecursiveComparison()
                .isEqualTo(original.getCoordinates());
        assertThat(OfficialRouteDeflectionRules.validate(result.getNodes(), result.getEdges())).isEmpty();
    }

    private List<RouteNode> nodes() {
        return List.of(
                new RouteNode("root", "existing_chamber_tie_in", point(-80, 10), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", point(1, 10), false, false, 0, null));
    }

    private RouteEdge edge(List<Coordinate> coordinates) {
        LineString line = rules.line(coordinates);
        return new RouteEdge("edge", "root", "demand:one", line.getLength(),
                coordinates.stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()),
                rules.sections(line, List.of()), BigDecimal.ONE, 50);
    }

    private RegressionRoutePlannerFixture planner(OfficialObstacleRouter router) {
        return new RegressionRoutePlannerFixture(new OfficialRouteValidator(rules), router, pipes,
                new OfficialNetworkSizer(pipes), new OfficialExistingNetworkReconstructor(pipes),
                new OfficialVariantEconomicsCalculator(pipes, new OfficialEconomics()),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
                        new OfficialDepthOptimizer(pipes, new OfficialEconomics()), new OfficialDepthProfileValidator(pipes)));
    }

    private RouteVariant budgetVariant(String length, String cost) {
        BigDecimal total = new BigDecimal(length);
        VariantEconomics economics = new VariantEconomics(true, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(cost),
                total, BigDecimal.ZERO, total, BigDecimal.ZERO, List.of());
        return new RouteVariant("budget", "engineering", List.of(), List.of(), List.of(), total,
                List.of(), List.of(), List.of(), null, economics, null);
    }

    private static Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6100000 + y); }
    private static RouteCoordinate point(double x, double y) { return new RouteCoordinate(500000 + x, 6100000 + y); }
}
