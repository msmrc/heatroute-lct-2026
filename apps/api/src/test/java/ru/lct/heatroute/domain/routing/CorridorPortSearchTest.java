package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/** Проверяет ограниченный повтор выбора портов, не подменяя геометрию вводов их хордами. */
class CorridorPortSearchTest {
    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    @Test
    void realSeededSolverReplacesOnlyTheConflictingLeafPortAndPreservesBothConsumers() {
        Fixture fixture = twoConsumers(true, true);
        String before = signature(fixture);
        // Хорды вводов параллельны, фактические ломаные касаются в (10,10).
        assertThat(line(fixture.points.get(1), fixture.points.get(4))
                .intersects(line(fixture.points.get(2), fixture.points.get(5)))).isFalse();
        assertThat(line(fixture.options.get(4).get(1)).intersects(line(fixture.options.get(5).get(2)))).isTrue();
        List<Set<String>> offered = new ArrayList<>();
        List<int[]> tree = solveReal(fixture, offered);
        assertThat(offered).hasSize(2);
        assertThat(offered.get(0)).contains("1:4", "3:4", "2:5");
        Set<String> removed = new TreeSet<>(offered.get(0));
        removed.removeAll(offered.get(1));
        assertThat(removed).containsExactly("1:4");
        assertThat(offered.get(1)).contains("3:4", "2:5");
        assertThat(edgeKeys(tree)).contains("3:4", "2:5").doesNotContain("1:4");
        assertAllConsumersAreLeavesAndGeometryIsCompatible(fixture, tree);
        assertThat(signature(fixture)).isEqualTo(before);
    }

    @Test
    void exclusionsAndWeightsStayAlignedWhenLinksAreReversedAndPermuted() {
        Fixture fixture = twoConsumers(true, true);
        Collections.reverse(fixture.links);
        Collections.reverse(fixture.lengths);
        for (int[] edge : fixture.links) { int from = edge[0]; edge[0] = edge[1]; edge[1] = from; }
        String before = signature(fixture);
        List<Set<String>> offered = new ArrayList<>();
        List<int[]> tree = solveReal(fixture, offered);
        assertThat(offered).hasSize(2);
        assertThat(offered.get(1)).contains("3:4", "2:5").doesNotContain("1:4");
        assertAllConsumersAreLeavesAndGeometryIsCompatible(fixture, tree);
        assertThat(signature(fixture)).isEqualTo(before);
    }

    @Test
    void anAlreadyCompatibleRealTreeNeedsNoRetry() {
        Fixture fixture = twoConsumers(false, true);
        List<Set<String>> offered = new ArrayList<>();
        List<int[]> tree = solveReal(fixture, offered);
        assertThat(offered).hasSize(1);
        assertAllConsumersAreLeavesAndGeometryIsCompatible(fixture, tree);
    }

    @Test
    void conflictingLastOptionsReturnNullWithoutRemovingAConsumerOrMutatingInputs() {
        Fixture fixture = twoConsumers(true, false);
        String before = signature(fixture);
        List<Set<String>> offered = new ArrayList<>();
        assertThat(solveReal(fixture, offered)).isNull();
        assertThat(offered).hasSize(1);
        assertThat(signature(fixture)).isEqualTo(before);
    }

    @Test
    void disconnectedRealGraphReturnsNullWithoutMutatingInputs() {
        Fixture fixture = twoConsumers(true, true);
        int isolatedLink = fixture.links.stream().map(CorridorPortSearchTest::key).collect(Collectors.toList()).indexOf("2:5");
        fixture.links.remove(isolatedLink);
        fixture.lengths.remove(isolatedLink);
        String before = signature(fixture);
        AtomicInteger calls = new AtomicInteger();
        assertThat(solve(fixture, (links, lengths) -> {
            calls.incrementAndGet();
            return realTree(fixture, links, lengths);
        })).isNull();
        assertThat(calls).hasValue(1);
        assertThat(signature(fixture)).isEqualTo(before);
    }

    @Test
    void eighthAttemptMaySucceedAfterSevenExactPortExclusions() {
        Fixture fixture = retryBudget(8);
        String before = signature(fixture);
        AtomicInteger calls = new AtomicInteger();
        List<int[]> tree = solve(fixture, chooseNextPort(fixture, calls));
        assertThat(calls).hasValue(8);
        assertThat(tree).isNotNull();
        assertThat(edgeKeys(tree)).contains(key(new int[] {8, fixture.gridSize}));
        assertAllConsumersAreLeavesAndGeometryIsCompatible(fixture, tree);
        assertThat(signature(fixture)).isEqualTo(before);
    }

    @Test
    void ninthSafeOptionIsNotAttemptedWhenEightConflictingTreesExhaustTheBudget() {
        Fixture fixture = retryBudget(9);
        String before = signature(fixture);
        AtomicInteger calls = new AtomicInteger();
        assertThat(solve(fixture, chooseNextPort(fixture, calls))).isNull();
        assertThat(calls).hasValue(8);
        assertThat(signature(fixture)).isEqualTo(before);
    }

    @Test
    void solverNullStopsImmediatelyWithoutConsumingTheRetryBudget() {
        Fixture fixture = twoConsumers(true, true);
        String before = signature(fixture);
        AtomicInteger calls = new AtomicInteger();
        assertThat(solve(fixture, (links, lengths) -> { calls.incrementAndGet(); return null; })).isNull();
        assertThat(calls).hasValue(1);
        assertThat(signature(fixture)).isEqualTo(before);
    }

    @Test
    void partialSolverTreeIsNotAcceptedAsAConnectedConsumerSet() {
        Fixture fixture = twoConsumers(false, true);
        assertThat(solve(fixture, (links, lengths) -> List.of(new int[] {0, 1}, new int[] {1, 4}))).isNull();
    }

    @Test
    void solverCannotAttachOneConsumerToTwoPorts() {
        Fixture fixture = twoConsumers(false, true);
        assertThat(solve(fixture, (links, lengths) -> List.of(new int[] {1, 4}, new int[] {4, 3}, new int[] {2, 5}))).isNull();
    }

    @Test
    void cancellationBeforeSearchSkipsSolverAndPreservesTheInterruptFlag() {
        Fixture fixture = twoConsumers(false, true);
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> solve(fixture, (links, lengths) -> { calls.incrementAndGet(); return null; }))
                    .isInstanceOf(CancellationException.class);
            assertThat(calls).hasValue(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationDuringSolverIsNotConvertedIntoAnOrdinaryNoSolution() {
        Fixture fixture = twoConsumers(false, true);
        try {
            assertThatThrownBy(() -> solve(fixture, (links, lengths) -> {
                Thread.currentThread().interrupt();
                return null;
            })).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationDuringSolverRejectsEvenAnOtherwiseValidReturnedTree() {
        Fixture fixture = twoConsumers(false, true);
        try {
            assertThatThrownBy(() -> solve(fixture, (links, lengths) -> {
                List<int[]> tree = realTree(fixture, links, lengths);
                Thread.currentThread().interrupt();
                return tree;
            })).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private List<int[]> solveReal(Fixture fixture, List<Set<String>> offered) {
        return solve(fixture, (links, lengths) -> {
            assertWeightsMatchOriginalEdges(fixture, links, lengths);
            for (int leaf : fixture.options.keySet()) {
                assertThat(links).as("remaining option for consumer %s", leaf).anyMatch(e -> e[0] == leaf || e[1] == leaf);
            }
            offered.add(edgeKeys(links));
            return realTree(fixture, links, lengths);
        });
    }

    private List<int[]> realTree(Fixture fixture, List<int[]> links, List<Double> lengths) {
        Map<Integer, Integer> reservations = new LinkedHashMap<>();
        fixture.options.keySet().forEach(leaf -> reservations.put(leaf, 3));
        return new CorridorTreeBuilder().buildWeightedFrom(fixture.points, links, lengths, 0, 2, reservations,
                Collections.min(reservations.keySet()), 0, 0);
    }

    private List<int[]> solve(Fixture fixture, BiFunction<List<int[]>, List<Double>, List<int[]>> solver) {
        return CorridorPortSearch.solve(fixture.points, fixture.links, fixture.lengths, fixture.gridSize, fixture.options, solver);
    }

    private BiFunction<List<int[]>, List<Double>, List<int[]>> chooseNextPort(Fixture fixture, AtomicInteger calls) {
        return (links, lengths) -> {
            int attempt = calls.incrementAndGet();
            assertWeightsMatchOriginalEdges(fixture, links, lengths);
            List<int[]> available = links.stream().filter(e -> Math.max(e[0], e[1]) == fixture.gridSize).collect(Collectors.toList());
            assertThat(available).hasSize(fixture.options.get(fixture.gridSize).size() - attempt + 1);
            int[] selected = available.get(0);
            int port = Math.min(selected[0], selected[1]);
            assertThat(port).isEqualTo(attempt);
            return List.of(new int[] {0, port}, selected.clone());
        };
    }

    private void assertWeightsMatchOriginalEdges(Fixture fixture, List<int[]> links, List<Double> lengths) {
        assertThat(links).hasSameSizeAs(lengths);
        Map<String, Double> original = new TreeMap<>();
        for (int i = 0; i < fixture.links.size(); i++) original.put(key(fixture.links.get(i)), fixture.lengths.get(i));
        for (int i = 0; i < links.size(); i++) assertThat(lengths.get(i)).as("weight for %s", key(links.get(i))).isEqualTo(original.get(key(links.get(i))));
    }

    private void assertAllConsumersAreLeavesAndGeometryIsCompatible(Fixture fixture, List<int[]> tree) {
        assertThat(tree).isNotNull();
        Map<Integer, RoutePath> paths = new LinkedHashMap<>();
        Map<Integer, Integer> attachments = new LinkedHashMap<>();
        List<LineString> sections = new ArrayList<>();
        Set<Integer> connected = new TreeSet<>();
        connected.add(0);
        boolean changed;
        do {
            changed = false;
            for (int[] edge : tree) {
                if (connected.contains(edge[0])) changed |= connected.add(edge[1]);
                if (connected.contains(edge[1])) changed |= connected.add(edge[0]);
            }
        } while (changed);
        assertThat(connected).containsAll(fixture.options.keySet());
        assertThat(tree).hasSize(connected.size() - 1);
        for (int[] edge : tree) {
            int leaf = Math.max(edge[0], edge[1]), port = Math.min(edge[0], edge[1]);
            if (leaf < fixture.gridSize) sections.add(line(fixture.points.get(edge[0]), fixture.points.get(edge[1])));
            else {
                assertThat(attachments.put(leaf, port)).as("consumer is a leaf, not a transit vertex").isNull();
                paths.put(leaf, fixture.options.get(leaf).get(port));
            }
        }
        assertThat(paths.keySet()).isEqualTo(fixture.options.keySet());
        assertThat(CorridorPortCompatibility.firstConflict(paths, attachments, sections)).isNull();
    }

    private Fixture twoConsumers(boolean crossing, boolean alternative) {
        List<Coordinate> points = List.of(new Coordinate(-10, 0), new Coordinate(0, 0), new Coordinate(0, 20),
                new Coordinate(-10, 20), new Coordinate(20, 0), new Coordinate(20, 20));
        RoutePath a = crossing ? path(0, 0, 10, 17.320508, 20, 11.547005) : path(0, 0, 20, 0);
        RoutePath b = crossing ? path(0, 20, 10, 2.679492, 20, 8.452995) : path(0, 20, 20, 20);
        RoutePath detour = path(-10, 20, -10, 30, 30, 30, 30, -10, 20, -10, 20, 0);
        Map<Integer, RoutePath> aOptions = new LinkedHashMap<>();
        aOptions.put(1, a);
        if (alternative) aOptions.put(3, detour);
        Map<Integer, Map<Integer, RoutePath>> options = new LinkedHashMap<>();
        options.put(4, aOptions); options.put(5, Map.of(2, b));
        List<int[]> links = new ArrayList<>(List.of(new int[] {1, 4}, new int[] {0, 3}, new int[] {2, 5},
                new int[] {3, 2}, new int[] {0, 1}));
        List<Double> lengths = new ArrayList<>(List.of(a.lengthM(), 20.0, b.lengthM(), 10.0, 10.0));
        if (alternative) { links.add(new int[] {4, 3}); lengths.add(detour.lengthM()); }
        return new Fixture(points, links, lengths, 4, options);
    }

    private Fixture retryBudget(int ports) {
        List<Coordinate> points = new ArrayList<>();
        points.add(new Coordinate(0, -10));
        for (int i = 1; i <= ports; i++) points.add(new Coordinate(i * 10, 0));
        int leaf = points.size();
        points.add(new Coordinate(ports * 10 + 20, 10));
        Map<Integer, RoutePath> options = new LinkedHashMap<>();
        List<int[]> links = new ArrayList<>();
        List<Double> lengths = new ArrayList<>();
        for (int i = 1; i <= ports; i++) {
            double x = points.get(i).x, endX = points.get(leaf).x;
            RoutePath stub = i == ports ? path(x, 0, x, 10, endX, 10)
                    : path(x, 0, x, 4, x + 4, 4, x + 4, 0, x, 0, x, 10, endX, 10);
            options.put(i, stub);
            links.add(new int[] {0, i}); lengths.add(points.get(0).distance(points.get(i)));
            links.add(new int[] {leaf, i}); lengths.add(stub.lengthM());
        }
        return new Fixture(points, links, lengths, leaf, Map.of(leaf, options));
    }

    private String signature(Fixture fixture) {
        StringBuilder result = new StringBuilder();
        fixture.points.forEach(point -> result.append(point.toString()));
        fixture.links.forEach(edge -> result.append(Arrays.toString(edge)));
        result.append(fixture.lengths).append(fixture.gridSize);
        new TreeMap<>(fixture.options).forEach((leaf, paths) -> {
            result.append(leaf);
            new TreeMap<>(paths).forEach((port, path) -> result.append(port).append(path.coordinates()).append(path.lengthM()));
        });
        return result.toString();
    }

    private RoutePath path(double... xy) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new Coordinate(xy[i], xy[i + 1]));
        return new RoutePath(points, List.of(), GEOMETRIES.createLineString(points.toArray(new Coordinate[0])).getLength());
    }

    private static LineString line(RoutePath path) { return GEOMETRIES.createLineString(path.coordinates().toArray(new Coordinate[0])); }
    private static LineString line(Coordinate a, Coordinate b) { return GEOMETRIES.createLineString(new Coordinate[] {a, b}); }
    private static String key(int[] edge) { return Math.min(edge[0], edge[1]) + ":" + Math.max(edge[0], edge[1]); }
    private Set<String> edgeKeys(List<int[]> edges) {
        assertThat(edges).isNotNull();
        return edges.stream().map(CorridorPortSearchTest::key).collect(Collectors.toCollection(TreeSet::new));
    }

    private static final class Fixture {
        final List<Coordinate> points;
        final List<int[]> links;
        final List<Double> lengths;
        final int gridSize;
        final Map<Integer, Map<Integer, RoutePath>> options;
        Fixture(List<Coordinate> points, List<int[]> links, List<Double> lengths, int gridSize,
                Map<Integer, Map<Integer, RoutePath>> options) {
            this.points = points; this.links = links; this.lengths = lengths; this.gridSize = gridSize; this.options = options;
        }
    }
}
