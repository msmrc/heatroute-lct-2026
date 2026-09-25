package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class DepthChamberApproachesTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void detoursFiniteGasLinesWithoutLosingTheSharedChamberNormals() {
        List<ImportedOfficialFeature> features = List.of(gas("gas-a", -100, 5, 1, 5), gas("gas-b", 5, -100, 5, 1));
        Map<String, RouteNode> nodes = Map.of("root", node("root", -100, 0, true), "j", node("j", 0, 0, true),
                "a", node("a", 30, 20, false), "b", node("b", 20, 19.999, false));
        List<RouteEdge> edges = new ArrayList<>(List.of(edge("backbone", "root", "j", -100, 0, 0, 0),
                edge("a", "j", "a", 0, 0, 0, 10, 30, 20), edge("b", "j", "b", 0, 0, 10, 0, 20, 19.999)));
        for (int at = 1; at < edges.size(); at++) {
            RouteEdge original = edges.get(at);
            RoutePath route = router.findDepthDetourPreservingChambers(original, nodes, router.prepare(features), Set.of(),
                    Set.of("gas-a", "gas-b"), edges);
            assertThat(route).as("edge=%s", original.getId()).isNotNull();
            RouteEdge replacement = replacement(original, route);
            assertSafeApproaches(original, replacement, nodes);
            assertThat(new EngineeringRouteEvaluator().evaluate(List.of(replacement)).isCompliant()).isTrue();
            for (ImportedOfficialFeature feature : features) assertThat(rules.line(route.coordinates()).intersects(feature.getMetricGeometry())).isFalse();
            edges.set(at, replacement);
        }
        assertThat(new ExpertChamberRouteValidator().validate(new ArrayList<>(nodes.values()), edges)).isEmpty();
        assertThat(new OfficialRouteValidator(rules).validate(new ArrayList<>(nodes.values()), edges, features)).extracting(issue -> issue.getCode() + ":" + issue.getSubjectId()).isEmpty();
    }

    @Test void bothChamberEndsKeepTheirOriginalRaysAfterTheDepthDetour() {
        List<ImportedOfficialFeature> features = List.of(gas("gas", 10, -5, 10, 5));
        RouteEdge original = edge("edge", "first", "last", 0, 0, 20, 0);
        Map<String, RouteNode> nodes = Map.of("first", node("first", 0, 0, true), "last", node("last", 20, 0, true));
        RoutePath route = router.findDepthDetourPreservingChambers(original, nodes, router.prepare(features), Set.of(), Set.of("gas"), List.of());
        assertThat(route).isNotNull();
        assertSafeApproaches(original, replacement(original, route), nodes);
        assertThat(rules.line(route.coordinates()).intersects(features.get(0).getMetricGeometry())).isFalse();
    }

    @Test void rotatedAndReversedChamberDetoursPreserveBothNormals() {
        for (double angle : new double[] {0.37, Math.PI / 2, 2.1}) {
            for (boolean reversed : new boolean[] {false, true}) {
                Coordinate a = rotated(0, 0, angle), b = rotated(20, 0, angle);
                Coordinate from = reversed ? b : a, to = reversed ? a : b;
                RouteEdge original = coordinateEdge(from, to);
                Map<String, RouteNode> nodes = Map.of("first", coordinateNode("first", from, true),
                        "last", coordinateNode("last", to, true));
                ImportedOfficialFeature gas = new ImportedOfficialFeature("gas", "restriction",
                        mapper.createObjectNode().put("restriction_type", "gas_pipeline"),
                        rules.line(List.of(rotated(10, -5, angle), rotated(10, 5, angle))));
                RoutePath route = router.findDepthDetourPreservingChambers(original, nodes,
                        router.prepare(List.of(gas)), Set.of(), Set.of("gas"), List.of());
                assertThat(route).as("angle=%s reversed=%s", angle, reversed).isNotNull();
                assertSafeApproaches(original, replacement(original, route), nodes);
                assertThat(rules.line(route.coordinates()).intersects(gas.getMetricGeometry())).isFalse();
            }
        }
    }

    @Test void preservesOwnBuildingNormalWithoutExemptingTheOutsideDetour() {
        ImportedOfficialFeature own = new ImportedOfficialFeature("own", "restriction",
                mapper.createObjectNode().put("restriction_type", "oks"), new GeometryFactory().createPolygon(new Coordinate[] {
                        point(38, -5), point(42, -5), point(42, 5), point(38, 5), point(38, -5)}));
        List<ImportedOfficialFeature> features = List.of(own, gas("gas", 20, -5, 20, 5));
        RouteEdge original = coordinateEdge(point(0, 0), point(40, 0));
        Map<String, RouteNode> nodes = Map.of("first", coordinateNode("first", point(0, 0), true),
                "last", coordinateNode("last", point(40, 0), false));
        OfficialRoutingEnvironment environment = router.prepare(features);
        RoutePath route = router.findDepthDetourPreservingChambers(original, nodes,
                environment, Set.of(), Set.of("gas"), List.of());
        assertThat(route).isNotNull();
        assertSafeApproaches(original, replacement(original, route), nodes);
        var normal = environment.normalEgressTowards(50, point(40, 0), point(0, 0), RouteTraversal.REVERSED).orElseThrow();
        assertThat(route.coordinates().get(route.coordinates().size() - 2).distance(normal.exit())).isLessThan(0.001);
        assertThat(router.terminalRouteAllowed(route.coordinates(), 50, environment, Set.of(), List.of(), normal)).isTrue();
        assertThat(rules.line(route.coordinates()).intersects(features.get(1).getMetricGeometry())).isFalse();
    }

    @Test void cannotCrossAFailedUtilityThroughThePinnedCameraStub() {
        List<ImportedOfficialFeature> features = List.of(gas("gas", 1, -100, 1, 100));
        RouteEdge original = edge("edge", "first", "last", 0, 0, 20, 0);
        Map<String, RouteNode> nodes = Map.of("first", node("first", 0, 0, true), "last", node("last", 20, 0, true));
        assertThat(router.findDepthDetourPreservingChambers(original, nodes, router.prepare(features), Set.of(), Set.of("gas"), List.of())).isNull();
    }

    private void assertSafeApproaches(RouteEdge original, RouteEdge actual, Map<String, RouteNode> nodes) {
        var before = ExpertChamberGeometryRules.summarize(original.getCoordinates());
        var after = ExpertChamberGeometryRules.summarize(actual.getCoordinates());
        if (nodes.get(original.getUpstreamNodeId()).isChamber()) {
            assertThat(after.getFirstBendDistanceM()).isGreaterThanOrEqualTo(2);
            assertThat(ExpertChamberGeometryRules.straightDirections(before.getFirstDx(), before.getFirstDy(), after.getFirstDx(), after.getFirstDy())).isTrue();
        }
        if (nodes.get(original.getDownstreamNodeId()).isChamber()) {
            assertThat(after.getLastBendDistanceM()).isGreaterThanOrEqualTo(2);
            assertThat(ExpertChamberGeometryRules.straightDirections(before.getLastDx(), before.getLastDy(), after.getLastDx(), after.getLastDy())).isTrue();
        }
        assertThat(after.hasShortBendSpacing()).isFalse();
        assertThat(after.hasInvalidBendAngle()).isFalse();
    }

    private Coordinate rotated(double x, double y, double angle) {
        return point(x * Math.cos(angle) - y * Math.sin(angle), x * Math.sin(angle) + y * Math.cos(angle));
    }
    private RouteNode coordinateNode(String id, Coordinate point, boolean chamber) {
        return new RouteNode(id, chamber ? "new_chamber" : "demand_connection",
                new RouteCoordinate(point.x, point.y), chamber, false, 0, null);
    }
    private RouteEdge coordinateEdge(Coordinate from, Coordinate to) {
        return new RouteEdge("edge", "first", "last", from.distance(to),
                List.of(new RouteCoordinate(from.x, from.y), new RouteCoordinate(to.x, to.y)), List.of(), BigDecimal.ONE, 50);
    }
    private RouteEdge replacement(RouteEdge original, RoutePath path) {
        return new RouteEdge(original.getId(), original.getUpstreamNodeId(), original.getDownstreamNodeId(), path.lengthM(),
                path.coordinates().stream().map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()), path.sections(), BigDecimal.ONE, 50);
    }
    private ImportedOfficialFeature gas(String id, double x1, double y1, double x2, double y2) {
        return new ImportedOfficialFeature(id, "restriction", mapper.createObjectNode().put("restriction_type", "gas_pipeline"),
                rules.line(List.of(point(x1, y1), point(x2, y2))));
    }
    private RouteNode node(String id, double x, double y, boolean chamber) {
        return new RouteNode(id, chamber ? "new_chamber" : "demand_connection", new RouteCoordinate(point(x, y).x, point(x, y).y), chamber, id.equals("root"), 0, null);
    }
    private RouteEdge edge(String id, String from, String to, double... xy) {
        List<RouteCoordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new RouteCoordinate(point(xy[i], xy[i+1]).x, point(xy[i], xy[i+1]).y));
        double length = rules.line(points.stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList())).getLength();
        return new RouteEdge(id, from, to, length, points, List.of(), BigDecimal.ONE, 50);
    }
    private Coordinate point(double x, double y) { return new Coordinate(414000 + x, 6173000 + y); }
}
