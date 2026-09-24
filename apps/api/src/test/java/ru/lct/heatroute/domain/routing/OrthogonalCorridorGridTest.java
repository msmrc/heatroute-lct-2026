package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/** Синтетические кварталы: направления выводятся из фасадов, не из осей мира или конкретного датасета. */
class OrthogonalCorridorGridTest {
    private static final double GEOMETRY_TOLERANCE_M = 1e-6;
    private final GeometryFactory geometries = new GeometryFactory();

    @Test
    void derivesFacadeAxesAndPreservesGeometryAcrossQuadrantsAndTranslations() {
        Coordinate root = new Coordinate(0, 0);
        List<Coordinate> terminals = terminals();
        List<Geometry> buildings = buildings();
        OrthogonalCorridorGrid baseline = grid(root, terminals, buildings);
        assertThat(baseline.links()).isNotEmpty();
        for (double degrees : new double[] {13, 57, 101, 146, 203, 289}) {
            AffineTransformation transform = transform(degrees, 12500.25, -7300.75);
            List<Coordinate> movedTerminals = terminals.stream().map(p -> move(p, transform)).collect(Collectors.toList());
            List<Geometry> movedBuildings = buildings.stream().map(transform::transform).collect(Collectors.toList());
            Coordinate movedRoot = move(root, transform);
            OrthogonalCorridorGrid actual = grid(movedRoot, movedTerminals, movedBuildings);
            assertThat(actual.points().get(actual.rootIndex()).distance(movedRoot)).isLessThan(GEOMETRY_TOLERANCE_M);
            // atan2(4θ)/4 вправе переставлять/разворачивать оси: сравниваем геометрию, не индексы.
            assertThat(pointKeys(actual, degrees, movedRoot)).as("points at %s degrees", degrees)
                    .isEqualTo(pointKeys(baseline, 0, root));
            assertThat(linkKeys(actual, degrees, movedRoot)).as("links at %s degrees", degrees)
                    .isEqualTo(linkKeys(baseline, 0, root));
            for (int[] link : actual.links()) {
                Coordinate a = local(actual.points().get(link[0]), degrees, movedRoot);
                Coordinate b = local(actual.points().get(link[1]), degrees, movedRoot);
                assertThat(Math.min(Math.abs(a.x - b.x), Math.abs(a.y - b.y)))
                        .as("link follows a footprint axis at %s degrees", degrees).isLessThan(GEOMETRY_TOLERANCE_M);
                assertThat(a.distance(b)).isGreaterThan(0.01);
            }
        }
    }

    @Test
    void terminalAndFootprintPermutationDoesNotChangeTheGeometricGraph() {
        Coordinate root = new Coordinate(0, 0);
        List<Coordinate> terminals = terminals();
        List<Geometry> buildings = buildings();
        OrthogonalCorridorGrid expected = grid(root, terminals, buildings);
        Collections.reverse(terminals);
        Collections.reverse(buildings);
        OrthogonalCorridorGrid actual = grid(root, terminals, buildings);
        assertThat(pointKeys(actual, 0, root)).isEqualTo(pointKeys(expected, 0, root));
        assertThat(linkKeys(actual, 0, root)).isEqualTo(linkKeys(expected, 0, root));
    }

    @Test
    void everyReturnedPointAndLinkSatisfiesTheSuppliedPredicates() {
        Coordinate root = new Coordinate(0, 0);
        Geometry forbidden = rectangle(12, -8, 30, 22);
        Predicate<Coordinate> pointAllowed = p -> !forbidden.covers(geometries.createPoint(p));
        BiPredicate<Coordinate, Coordinate> edgeAllowed = (a, b) -> a.distance(b) > 0.01
                && !geometries.createLineString(new Coordinate[] {a, b}).intersects(forbidden);
        AtomicInteger checks = new AtomicInteger();
        OrthogonalCorridorGrid grid = OrthogonalCorridorGrid.build(root, terminals(), buildings(), 5,
                pointAllowed, (a, b) -> { checks.incrementAndGet(); return edgeAllowed.test(a, b); });
        assertThat(grid.links()).isNotEmpty();
        assertThat(checks.get()).isGreaterThanOrEqualTo(grid.links().size());
        for (int index = 0; index < grid.points().size(); index++) {
            if (index != grid.rootIndex()) assertThat(pointAllowed.test(grid.points().get(index))).isTrue();
        }
        for (int[] link : grid.links()) {
            assertThat(link).hasSize(2);
            assertThat(link[0]).isBetween(0, grid.points().size() - 1);
            assertThat(link[1]).isBetween(0, grid.points().size() - 1);
            assertThat(edgeAllowed.test(grid.points().get(link[0]), grid.points().get(link[1]))).isTrue();
        }
    }

    @Test
    void anchoringAForbiddenRootDoesNotCreateUncheckedIncidentEdges() {
        Coordinate root = new Coordinate(0, 0);
        Geometry forbidden = rectangle(-4, -4, 4, 4);
        OrthogonalCorridorGrid grid = OrthogonalCorridorGrid.build(root, terminals(), buildings(), 5,
                p -> !forbidden.covers(geometries.createPoint(p)),
                (a, b) -> !geometries.createLineString(new Coordinate[] {a, b}).intersects(forbidden));
        assertThat(grid.rootIndex()).isGreaterThanOrEqualTo(0);
        assertThat(grid.links()).noneMatch(link -> link[0] == grid.rootIndex() || link[1] == grid.rootIndex());
        assertThat(grid.portsNear(new Coordinate(20, 20), 8)).isEmpty();
    }

    @Test
    void excludesNearestFreePortInsideAnIsolatedCourtyard() {
        Geometry outer = rectangle(22, 22, 38, 38);
        Geometry courtyard = rectangle(26, 26, 34, 34);
        Geometry wall = outer.difference(courtyard);
        Coordinate root = new Coordinate(0, 0), requestedPort = new Coordinate(30, 30);
        OrthogonalCorridorGrid grid = OrthogonalCorridorGrid.build(root, List.of(requestedPort), List.of(wall), 5,
                p -> !wall.covers(geometries.createPoint(p)),
                (a, b) -> !geometries.createLineString(new Coordinate[] {a, b}).intersects(wall));
        assertThat(grid.points()).anyMatch(p -> p.distance(requestedPort) < GEOMETRY_TOLERANCE_M);
        Set<Integer> reachable = new TreeSet<>();
        reachable.add(grid.rootIndex());
        boolean changed;
        do {
            changed = false;
            for (int[] link : grid.links()) {
                if (reachable.contains(link[0])) changed |= reachable.add(link[1]);
                if (reachable.contains(link[1])) changed |= reachable.add(link[0]);
            }
        } while (changed);
        List<Integer> ports = grid.portsNear(requestedPort, grid.points().size());
        assertThat(ports).isNotEmpty().allSatisfy(index -> {
            assertThat(reachable).contains(index);
            assertThat(courtyard.covers(geometries.createPoint(grid.points().get(index)))).isFalse();
            assertThat(index).isNotEqualTo(grid.rootIndex());
        });
        assertThat(grid.portsNear(requestedPort, 1)).hasSize(1).containsExactly(ports.get(0));
    }

    @Test
    void capsEachAxisAt96CoordinatesWithoutDroppingTheRoot() {
        List<Coordinate> many = new ArrayList<>();
        for (int i = 1; i <= 240; i++) many.add(new Coordinate(i * 10.0, i * 13.0));
        OrthogonalCorridorGrid grid = grid(new Coordinate(0, 0), many, buildings());
        Set<Long> xs = new TreeSet<>(), ys = new TreeSet<>();
        grid.points().forEach(p -> { xs.add(Math.round(p.x * 1e6)); ys.add(Math.round(p.y * 1e6)); });
        assertThat(xs.size()).isEqualTo(96);
        assertThat(ys.size()).isEqualTo(96);
        assertThat(grid.points()).hasSizeLessThanOrEqualTo(96 * 96);
        assertThat(grid.points().get(grid.rootIndex()).distance(new Coordinate(0, 0))).isZero();
    }

    @Test
    void emptyTerminalSetProducesOnlyAnOwnedRootAndNoLinks() {
        Coordinate root = new Coordinate(5, 7);
        OrthogonalCorridorGrid grid = grid(root, List.of(), List.of());
        root.x = 999;
        assertThat(grid.points()).hasSize(1);
        assertThat(grid.points().get(grid.rootIndex()).distance(new Coordinate(5, 7))).isZero();
        assertThat(grid.links()).isEmpty();
        assertThat(grid.portsNear(new Coordinate(5, 7), 8)).isEmpty();
    }

    @Test
    void cancellationIsPropagatedWithoutClearingTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> grid(new Coordinate(0, 0), terminals(), buildings()))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsNonfiniteCoordinatesAndInvalidClearanceInsteadOfBuildingAnUnsafeGrid() {
        for (double clearance : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> OrthogonalCorridorGrid.build(new Coordinate(0, 0), terminals(), buildings(),
                    clearance, p -> true, (a, b) -> true)).isInstanceOf(IllegalArgumentException.class);
        }
        for (Coordinate invalid : List.of(new Coordinate(Double.NaN, 0), new Coordinate(0, Double.POSITIVE_INFINITY))) {
            assertThatThrownBy(() -> grid(invalid, terminals(), buildings())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> grid(new Coordinate(0, 0), List.of(invalid), buildings()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void requiresSafetyPredicatesEvenForAnEmptyTerminalSet() {
        assertThatThrownBy(() -> OrthogonalCorridorGrid.build(new Coordinate(0, 0), List.of(), List.of(), 5,
                null, (a, b) -> true)).isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        assertThatThrownBy(() -> OrthogonalCorridorGrid.build(new Coordinate(0, 0), List.of(), List.of(), 5,
                p -> true, null)).isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }

    @Test
    void networkBuilderKeepsTransitTurnsAsGeometryAndOnlyBranchesBecomeCameras() {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OrthogonalCorridorNetworkBuilder builder = new OrthogonalCorridorNetworkBuilder(router, new OfficialPipeCatalog());
        Map<String, Coordinate> demands = Map.of("a", new Coordinate(44, 23), "b", new Coordinate(-31, 42),
                "c", new Coordinate(24, -37));
        List<OrthogonalCorridorNetworkBuilder.Terminal> terminals = demands.entrySet().stream()
                .map(e -> new OrthogonalCorridorNetworkBuilder.Terminal(e.getKey(), e.getKey(), e.getValue(), BigDecimal.ONE))
                .collect(Collectors.toList());
        RouteNode root = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(0, 0), true, true, 2, null);
        // Отдалённый синтетический фасад задаёт ориентацию, но не пересекает свободную тестовую сеть.
        Geometry orientation = transform(26, 0, 0).transform(rectangle(300, 300, 340, 310));
        List<OrthogonalCorridorNetworkBuilder.Network> networks = builder.build(terminals, root, 2, List.of(orientation),
                new OfficialRoutingEnvironment(List.of(), rules), (id, port, diameter, avoid) -> {
                    Coordinate from = demands.get(id);
                    if (from.distance(port) <= 0.01) return null;
                    return new RoutePath(List.of(from, port), rules.sections(rules.line(List.of(from, port)), List.of()), from.distance(port));
                });
        assertThat(networks).isNotEmpty();
        for (OrthogonalCorridorNetworkBuilder.Network network : networks) {
            assertThat(network.connections()).hasSize(demands.size());
            assertThat(network.edges()).hasSize(network.nodes().size() - 1);
            Map<String, Integer> degree = new HashMap<>();
            for (RouteEdge edge : network.edges()) {
                degree.merge(edge.getUpstreamNodeId(), 1, Integer::sum);
                degree.merge(edge.getDownstreamNodeId(), 1, Integer::sum);
                List<Coordinate> points = edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
                assertThat(rules.lineAllowed(rules.line(points), List.of())).isTrue();
            }
            for (RouteNode node : network.nodes()) {
                if ("new_branch_chamber".equals(node.getNodeType())) assertThat(degree.get(node.getId())).isBetween(3, 4);
                if ("demand_connection".equals(node.getNodeType())) assertThat(degree.get(node.getId())).isEqualTo(1);
            }
            assertThat(degree.get(root.getId())).isLessThanOrEqualTo(2);
            assertThat(network.edges()).anyMatch(edge -> edge.getCoordinates().size() > 2);
        }
    }

    private OrthogonalCorridorGrid grid(Coordinate root, List<Coordinate> terminals, List<Geometry> footprints) {
        return OrthogonalCorridorGrid.build(root, terminals, footprints, 5, p -> true, (a, b) -> true);
    }

    private List<Coordinate> terminals() {
        return new ArrayList<>(List.of(new Coordinate(64, 27), new Coordinate(-33, 47), new Coordinate(21, -46)));
    }

    private List<Geometry> buildings() {
        return new ArrayList<>(List.of(rectangle(4, 6, 19, 14), rectangle(30, -19, 51, -4)));
    }

    private Geometry rectangle(double x1, double y1, double x2, double y2) {
        return geometries.createPolygon(new Coordinate[] {new Coordinate(x1, y1), new Coordinate(x2, y1),
            new Coordinate(x2, y2), new Coordinate(x1, y2), new Coordinate(x1, y1)});
    }

    private AffineTransformation transform(double degrees, double dx, double dy) {
        double angle = Math.toRadians(degrees), c = Math.cos(angle), s = Math.sin(angle);
        return new AffineTransformation(c, -s, dx, s, c, dy);
    }

    private Coordinate move(Coordinate point, AffineTransformation transform) {
        return transform.transform(point, new Coordinate());
    }

    private Coordinate local(Coordinate point, double degrees, Coordinate origin) {
        double angle = Math.toRadians(degrees), c = Math.cos(angle), s = Math.sin(angle);
        double x = point.x - origin.x, y = point.y - origin.y;
        return new Coordinate(c * x + s * y, -s * x + c * y);
    }

    private String key(Coordinate point) {
        return Math.round(point.x * 1e5) + ":" + Math.round(point.y * 1e5);
    }

    private Set<String> pointKeys(OrthogonalCorridorGrid grid, double degrees, Coordinate origin) {
        return grid.points().stream().map(p -> key(local(p, degrees, origin))).collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> linkKeys(OrthogonalCorridorGrid grid, double degrees, Coordinate origin) {
        Set<String> result = new TreeSet<>();
        for (int[] link : grid.links()) {
            String a = key(local(grid.points().get(link[0]), degrees, origin));
            String b = key(local(grid.points().get(link[1]), degrees, origin));
            result.add(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a);
        }
        return result;
    }
}
