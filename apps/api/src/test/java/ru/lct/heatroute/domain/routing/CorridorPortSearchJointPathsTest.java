package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

/** Совместные геометрии одного порта и сохранение прежнего полного дерева. */
class CorridorPortSearchJointPathsTest {
    @Test
    void realTreeKeepsTheOnlyPortAndBothConsumersByChangingOneApproach() {
        List<Coordinate> points = List.of(new Coordinate(-10, 0), new Coordinate(),
                new Coordinate(0, 10), new Coordinate(10, 10));
        List<int[]> links = List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {1, 3});
        RoutePath north = path(0, 0, 0, 10), overlap = path(0, 0, 0, 10, 10, 10);
        RoutePath east = path(0, 0, 10, 0, 10, 10);
        Map<Integer, Map<Integer, RoutePath>> controls = Map.of(2, Map.of(1, north), 3, Map.of(1, overlap));
        List<Double> weights = List.of(10.0, 10.0, 20.0);
        assertThat(CorridorPortSearch.solve(points, links, weights, 2, controls,
                (allowed, lengths) -> tree(points, allowed, lengths, controls))).isNull();
        AtomicInteger calls = new AtomicInteger();
        List<CorridorPortSearch.Selection> result = CorridorPortSearch.solveWithPaths(points, links, weights, 2,
                controls, (leaf, port) -> leaf == 2 ? List.of(north) : List.of(overlap, east),
                (allowed, lengths) -> { calls.incrementAndGet(); return tree(points, allowed, lengths, controls); });
        assertThat(calls).hasValue(1);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).paths()).containsEntry(2, north).containsEntry(3, east);
        assertThat(result.get(0).tree()).allSatisfy(link -> assertThat(link).contains(1));
        assertThat(controls.get(3).get(1)).isSameAs(overlap);
    }

    @Test
    void officialDegreeTwoJoinRejectsShorterIndividuallyLegalStubInBothGridDirections() {
        for (boolean reverse : new boolean[] {false, true}) {
            List<Coordinate> points = List.of(new Coordinate(-10, 0), new Coordinate(), new Coordinate(-10, 10));
            List<int[]> links = List.of(reverse ? new int[] {1, 0} : new int[] {0, 1}, new int[] {1, 2});
            RoutePath shortPath = path(0, 0, -0.035, 10, -10, 10), north = path(0, 0, 0, 10, -10, 10);
            assertThat(OfficialRouteDeflectionRules.validatePolyline("stub", coordinates(shortPath)).getIssues()).isEmpty();
            Map<Integer, Map<Integer, RoutePath>> controls = Map.of(2, Map.of(1, shortPath));
            List<CorridorPortSearch.Selection> result = CorridorPortSearch.solveWithPaths(points, links,
                    List.of(10.0, shortPath.lengthM()), 2, controls, (leaf, port) -> List.of(shortPath, north),
                    (allowed, lengths) -> tree(points, allowed, lengths, controls));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).paths().get(2)).isSameAs(north);
            List<RouteCoordinate> joined = new ArrayList<>(List.of(new RouteCoordinate(-10, 0)));
            joined.addAll(coordinates(result.get(0).paths().get(2)));
            assertThat(OfficialRouteDeflectionRules.validatePolyline("joined", joined).getIssues()).isEmpty();
        }
    }

    @Test
    void equalLengthRepairPrefersActualBendsRatherThanTheNumberOfSamples() {
        RoutePath bad = path(0, 0, -0.035, 10, -10, 10);
        RoutePath detour = path(0, 0, 0, 2.25, -10, 2.25, -10, 10);
        RoutePath sampledElbow = path(0, 0, 0, 2.5, 0, 5, 0, 10, -5, 10, -10, 10);
        assertThat(sampledElbow.lengthM()).isEqualTo(detour.lengthM());
        assertThat(sampledElbow.coordinates().size()).isGreaterThan(detour.coordinates().size());
        CorridorPortSearch.Selection selected = CorridorPortSearch.assignPaths(
                List.of(new Coordinate(-10, 0), new Coordinate()),
                List.of(new int[] {0, 1}, new int[] {1, 2}), 2, Map.of(2, Map.of(1, bad)),
                (leaf, port) -> List.of(bad, detour, sampledElbow));
        assertThat(selected).isNotNull();
        assertThat(selected.paths().get(2)).isSameAs(sampledElbow);
    }

    @Test
    void retainsOldCompleteLongerPortTreeBeforeAnAdditionalSamePortRepair() {
        List<Coordinate> points = List.of(new Coordinate(-10, 0), new Coordinate(), new Coordinate(10, -10),
                new Coordinate(0, 10), new Coordinate(10, 10));
        List<int[]> links = List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {1, 3},
                new int[] {1, 4}, new int[] {2, 4});
        RoutePath north = path(0, 0, 0, 10), overlap = path(0, 0, 0, 10, 10, 10);
        RoutePath detour = path(10, -10, 20, -10, 20, 10, 10, 10), east = path(0, 0, 10, 0, 10, 10);
        Map<Integer, Map<Integer, RoutePath>> controls = Map.of(3, Map.of(1, north), 4, Map.of(1, overlap, 2, detour));
        List<CorridorPortSearch.Selection> result = CorridorPortSearch.solveWithPaths(points, links,
                List.of(10.0, Math.sqrt(200), 10.0, 20.0, 40.0), 3, controls,
                (leaf, port) -> leaf == 4 && port == 1 ? List.of(overlap, east) : List.of(controls.get(leaf).get(port)),
                (allowed, lengths) -> tree(points, allowed, lengths, controls));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).paths()).containsEntry(3, north).containsEntry(4, detour);
        assertThat(result.get(1).paths()).containsEntry(3, north).containsEntry(4, east);
        assertThat(result.get(0).tree()).anyMatch(link -> contains(link, 2, 4));
        assertThat(result.get(1).tree()).anyMatch(link -> contains(link, 1, 4));
    }

    @Test
    void compatibleControlDoesNotRequestAlternativesOrRunTheTreeSolverTwice() {
        RoutePath north = path(0, 0, 0, 10);
        AtomicInteger calls = new AtomicInteger();
        List<int[]> links = List.of(new int[] {0, 1}, new int[] {1, 2});
        List<CorridorPortSearch.Selection> result = CorridorPortSearch.solveWithPaths(
                List.of(new Coordinate(-10, 0), new Coordinate(), new Coordinate(0, 10)), links,
                List.of(10.0, 10.0), 2, Map.of(2, Map.of(1, north)),
                (leaf, port) -> { throw new AssertionError("Control must remain intact"); },
                (allowed, weights) -> { calls.incrementAndGet(); return links; });
        assertThat(calls).hasValue(1);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).paths()).containsEntry(2, north);
    }

    @Test
    void failedOrPartialAssignmentsNeverDropAConsumer() {
        RoutePath north = path(0, 0, 0, 10), overlap = path(0, 0, 0, 10, 10, 10);
        Map<Integer, Map<Integer, RoutePath>> controls = Map.of(2, Map.of(1, north), 3, Map.of(1, overlap));
        List<Coordinate> points = List.of(new Coordinate(-10, 0), new Coordinate());
        assertThat(CorridorPortSearch.assignPaths(points,
                List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {1, 3}), 2, controls,
                (leaf, port) -> List.of(controls.get(leaf).get(port)))).isNull();
        assertThat(CorridorPortSearch.assignPaths(points, List.of(new int[] {0, 1}, new int[] {1, 2}),
                2, controls, (leaf, port) -> { throw new AssertionError("Partial tree"); })).isNull();
    }

    @Test
    void interruptionWhileGeneratingAlternativesPropagatesAndPreservesTheFlag() {
        RoutePath bad = path(0, 0, -0.035, 10, -10, 10);
        try {
            assertThatThrownBy(() -> CorridorPortSearch.assignPaths(List.of(new Coordinate(-10, 0), new Coordinate()),
                    List.of(new int[] {0, 1}, new int[] {1, 2}), 2, Map.of(2, Map.of(1, bad)), (leaf, port) -> {
                        Thread.currentThread().interrupt(); return List.of(bad);
                    })).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private List<int[]> tree(List<Coordinate> points, List<int[]> links, List<Double> weights,
            Map<Integer, Map<Integer, RoutePath>> controls) {
        return new CorridorTreeBuilder().buildWeighted(points, links, weights, 0, 1,
                controls.keySet().stream().collect(Collectors.toMap(leaf -> leaf, leaf -> 3)), false, 0, 0);
    }

    private boolean contains(int[] edge, int a, int b) {
        return edge[0] == a && edge[1] == b || edge[0] == b && edge[1] == a;
    }

    private List<RouteCoordinate> coordinates(RoutePath path) {
        return path.coordinates().stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList());
    }

    private RoutePath path(double... xy) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new Coordinate(xy[i], xy[i + 1]));
        return new RoutePath(points, List.of(), new GeometryFactory().createLineString(points.toArray(new Coordinate[0])).getLength());
    }
}
