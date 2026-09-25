package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Сохраняет выбор A* при округлении цели и цепочках почти равных стоимостей. */
class OfficialObstacleRouterSearchPriorityTest {
    private static final Method PREFERENCE_FACTOR = privateMethod("preferenceFactor",
            Coordinate.class, Coordinate.class, Coordinate.class, Coordinate.class, RoutePreference.class);
    private static final Method BEND_PENALTY = privateMethod("bendPenalty",
            Coordinate.class, Coordinate.class, Coordinate.class, RoutePreference.class);
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void queuedGoalKeepsTheBaselinePathThroughFarDistractors() {
        List<Coordinate> nodes = new ArrayList<>(List.of(point(0, 0), point(10, 0), point(5, 0)));
        for (int index = 0; index < 24; index++) nodes.add(point(100 + index, 0));
        GraphRules graph = new GraphRules(nodes, false, new int[][] {{0, 2}, {2, 1}});
        ConstraintIndex constraints = rules.index(List.of());

        SearchOutcome actual = search(nodes, constraints, RoutePreference.SHORTEST, null, graph);

        assertThat(actual.path).containsExactly(nodes.get(0), nodes.get(2), nodes.get(1));
        assertThat(actual.path).containsExactlyElementsOf(reference(nodes, constraints,
                RoutePreference.SHORTEST, null, new GraphRules(nodes, false, new int[][] {{0, 2}, {2, 1}})).path);
        assertThat(actual.pairs).isEqualTo(graph.totalChecks());
        assertThat(actual.pairs).as("source75 visibility decisions are preserved").isEqualTo(75);
        assertThat(graph.checksFromToRange(2, 3, nodes.size())).isEqualTo(24);
    }

    @Test
    void repeatedToleranceUpdatesToAQueuedStatePreserveItsFinalPredecessor() {
        List<Coordinate> nodes = List.of(point(0, 0), point(100, 0), point(60, 0), point(80, 0),
                point(30, 3.7271774890266682), point(40, 3.6411182332759244),
                point(50, 2.729823683953149), point(50, 5));
        assertToleranceChain(nodes, 4);
    }

    @Test
    void firstDiscoveryAboveTheQueuedGoalStillMattersToLaterToleranceComparisons() {
        List<Coordinate> nodes = List.of(point(0, 0), point(100, 0), point(60, 0), point(80, 0),
                point(50, 2.7298236858644556), point(40, 3.6411182332759244),
                point(30, 3.727177486309829), point(50, 5));
        assertToleranceChain(nodes, 6);
    }

    private void assertToleranceChain(List<Coordinate> nodes, int winner) {
        int[][] edges = {{0, 4}, {4, 2}, {0, 5}, {5, 2}, {0, 6}, {6, 2}, {2, 3}, {3, 1}, {0, 7}, {7, 1}};
        ConstraintIndex constraints = rules.index(List.of());
        List<Coordinate> expected = reference(nodes, constraints, RoutePreference.LEFT, null,
                new GraphRules(nodes, false, edges)).path;
        assertThat(expected).containsExactly(nodes.get(0), nodes.get(winner), nodes.get(2), nodes.get(3), nodes.get(1));
        // Каждое обновление укладывается в 1e-9, но их сумма пересекает верхнюю границу цели.
        // Даже отложенное состояние влияет на сравнение последующих кандидатов и backtracking.
        assertThat(search(nodes, constraints, RoutePreference.LEFT, null, new GraphRules(nodes, false, edges)).path)
                .containsExactlyElementsOf(expected);
    }

    @Test
    void firstQueuedGoalCanBeImprovedByALaterPredecessor() {
        List<Coordinate> nodes = improvementNodes(0, 0, 0);
        GraphRules graph = improvementGraph(nodes);
        ConstraintIndex constraints = rules.index(List.of());
        ReferenceOutcome expected = reference(nodes, constraints, RoutePreference.SHORTEST, null,
                improvementGraph(nodes));

        assertThat(expected.goalCosts).hasSize(2);
        assertThat(expected.goalCosts.get(0)).isGreaterThan(expected.goalCosts.get(1));
        assertThat(search(nodes, constraints, RoutePreference.SHORTEST, null, graph).path)
                .containsExactly(nodes.get(0), nodes.get(3), nodes.get(1))
                .containsExactlyElementsOf(expected.path);
    }

    @Test
    void headingRoundedGoalUsesOriginalEndpointPriorityRatherThanGoalCost() {
        for (Coordinate offset : List.of(point(0, 0), point(400000, 6000000))) {
            List<Coordinate> nodes = improvementNodes(offset.x, offset.y, 0.00049);
            List<Coordinate> rounded = rounded(nodes);
            Coordinate previous = point(offset.x, offset.y - 10.1);
            ConstraintIndex constraints = rules.index(List.of());
            ReferenceOutcome expected = reference(nodes, constraints, RoutePreference.SHORTEST, previous,
                    improvementGraph(rounded));
            double goalHeuristic = heuristic(rounded.get(1), nodes.get(1));

            assertThat(goalHeuristic).isPositive();
            assertThat(expected.goalCosts).hasSize(2);
            assertThat(expected.goalCosts.get(0)).isGreaterThan(expected.goalCosts.get(1));
            assertThat(expected.goalCosts.get(1) + goalHeuristic)
                    .as("the winning goal priority exceeds the first goal COST")
                    .isGreaterThan(expected.goalCosts.get(0));
            SearchOutcome actual = search(nodes, constraints, RoutePreference.SHORTEST, previous,
                    improvementGraph(rounded));
            assertThat(actual.path).containsExactly(rounded.get(0), rounded.get(3), rounded.get(1))
                    .containsExactlyElementsOf(expected.path);
            System.out.printf(Locale.ROOT, "GOAL76 rounded offset=%s hGoal=%.12g firstCost=%.12g winningCost=%.12g pairs=%d%n",
                    offset, goalHeuristic, expected.goalCosts.get(0), expected.goalCosts.get(1), actual.pairs);
        }
    }

    @Test
    void goalRejectedBySpecialTurnCannotSupplyAnUpperBound() {
        List<Coordinate> nodes = List.of(point(0, 0), point(10, 0), point(9, 1), point(5, -5));
        GraphRules graph = improvementGraph(nodes);
        graph.rejectedGoalPredecessor = 2;
        SearchOutcome actual = search(nodes, rules.index(List.of()), RoutePreference.SHORTEST, null, graph);

        assertThat(graph.rejectedGoalTurns).isEqualTo(1);
        assertThat(actual.path).containsExactly(nodes.get(0), nodes.get(3), nodes.get(1));
    }

    @Test
    void equalPriorityCandidateStillReachesVisibilityAfterGoalWasQueued() {
        List<Coordinate> nodes = List.of(point(0, 0), point(10, 0), point(5, 0), point(7, 0));
        GraphRules graph = new GraphRules(nodes, false, new int[][] {{0, 2}, {2, 1}});

        SearchOutcome actual = search(nodes, rules.index(List.of()), RoutePreference.LEFT, null, graph);

        assertThat(actual.path).containsExactly(nodes.get(0), nodes.get(2), nodes.get(1));
        assertThat(0.985 * 5 + 0.985 * 2 + heuristic(nodes.get(3), nodes.get(1)))
                .isEqualTo(0.985 * 10);
        assertThat(graph.checks(2, 3)).as("equal-priority candidate is not pruned").isEqualTo(1);
    }

    @Test
    void goalPriorityToleranceRetainsCandidatesAtSmallAndLargeCostScales() {
        for (double goalCost : new double[] {10, 100000000}) {
            double tolerance = Math.max(1e-9, 8 * Math.ulp(goalCost));
            List<Coordinate> nodes = List.of(point(0, 0), point(goalCost, 0), point(goalCost / 2, 0),
                    point(goalCost + tolerance / 4, 0));
            double probePriority = goalCost / 2 + nodes.get(2).distance(nodes.get(3))
                    + heuristic(nodes.get(3), nodes.get(1));
            assertThat(probePriority).isGreaterThan(goalCost).isLessThanOrEqualTo(goalCost + tolerance);
            if (goalCost > 10) assertThat(probePriority - goalCost).isGreaterThan(1e-9);
            GraphRules graph = new GraphRules(nodes, false, new int[][] {{0, 2}, {2, 1}});

            SearchOutcome actual = search(nodes, rules.index(List.of()), RoutePreference.SHORTEST, null, graph);

            assertThat(actual.path).containsExactly(nodes.get(0), nodes.get(2), nodes.get(1));
            assertThat(graph.checks(2, 3)).as("cost %s / tolerance %s", goalCost, tolerance).isEqualTo(1);
        }
    }

    @Test
    void symmetricAndNearEqualDetoursKeepTheUnboundedSearchWinner() {
        for (double delta : new double[] {-1e-8, -1e-10, 0, 1e-10, 1e-8}) {
            List<Coordinate> nodes = List.of(point(0, 0), point(10, 0), point(5, 5 + delta), point(5, -5));
            for (RoutePreference preference : RoutePreference.values()) {
                ReferenceOutcome expected = reference(nodes, rules.index(List.of()), preference, null,
                        improvementGraph(nodes));
                assertThat(search(nodes, rules.index(List.of()), preference, null, improvementGraph(nodes)).path)
                        .as("delta %s / %s", delta, preference).containsExactlyElementsOf(expected.path);
                if (delta == 0 && preference == RoutePreference.SHORTEST) {
                    assertThat(expected.path).containsExactly(nodes.get(0), nodes.get(2), nodes.get(1));
                }
            }
        }
    }

    @Test
    void disconnectedAndTurnBlockedGraphsReturnNoGoal() {
        List<Coordinate> disconnected = List.of(point(0, 0), point(10, 0), point(3, 0), point(7, 0));
        List<Coordinate> turnBlocked = List.of(point(0, 0), point(9, 10), point(10, 0), point(9, 1));
        for (RoutePreference preference : RoutePreference.values()) {
            SearchOutcome noConnection = search(disconnected, rules.index(List.of()), preference, null,
                    new GraphRules(disconnected, false, new int[][] {{0, 2}, {3, 1}}));
            SearchOutcome noLegalTurn = search(turnBlocked, rules.index(List.of()), preference, null,
                    new GraphRules(turnBlocked, false, new int[][] {{0, 2}, {2, 3}, {3, 1}}));
            assertThat(noConnection.path).as(preference.name()).isEmpty();
            assertThat(noLegalTurn.path).as(preference.name()).isEmpty();
            assertThat(noLegalTurn.rejectedTurns).isPositive();
        }
    }

    @Test
    void directedVisibilityDoesNotInventAReverseConnection() throws Exception {
        // Дальний road включает направленный cache, не добавляя ограничений к тестовым поворотам.
        ConstraintIndex constraints = rules.index(rules.baseConstraints(List.of(feature("road",
                "POLYGON ((1000 1000, 1002 1000, 1002 1002, 1000 1002, 1000 1000))")), 100));
        assertThat(constraints.hasRoadCrossings()).isTrue();
        List<Coordinate> nodes = List.of(point(0, 0), point(10, 0), point(5, 0), point(20, 0));
        for (RoutePreference preference : RoutePreference.values()) {
            GraphRules forward = new GraphRules(nodes, true, new int[][] {{0, 2}, {2, 1}});
            assertThat(search(nodes, constraints, preference, null, forward).path)
                    .containsExactly(nodes.get(0), nodes.get(2), nodes.get(1));
            assertThat(forward.checks(2, 1)).isEqualTo(1);
            GraphRules reverseOnly = new GraphRules(nodes, true, new int[][] {{2, 0}, {1, 2}});
            assertThat(search(nodes, constraints, preference, null, reverseOnly).path).isEmpty();
        }
    }

    @Test
    void realObstacleGeometryKeepsTheExactUnboundedSearchPathForEveryPreference() throws Exception {
        ConstraintIndex constraints = rules.index(rules.baseConstraints(List.of(feature("park",
                "POLYGON ((4 -1, 6 -1, 6 1, 4 1, 4 -1))")), 100));
        List<Coordinate> nodes = List.of(point(0, 0), point(10, 0), point(3, 3), point(7, 3),
                point(3, -3), point(7, -3), point(50, 30), point(50, -30));
        for (RoutePreference preference : RoutePreference.values()) {
            ReferenceOutcome expected = reference(nodes, constraints, preference, null, rules);
            SearchOutcome actual = search(nodes, constraints, preference, null, rules);
            assertThat(expected.path).as(preference.name()).hasSizeGreaterThan(2);
            assertThat(actual.path).as(preference.name()).containsExactlyElementsOf(expected.path);
            assertThat(rules.lineAllowed(rules.line(actual.path), constraints)).isTrue();
            assertThat(OfficialRouteDeflectionRules.validatePolyline("goal-bound-geometry", actual.path.stream()
                    .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList())).getIssues()).isEmpty();
        }
    }

    private SearchOutcome search(List<Coordinate> nodes, ConstraintIndex constraints, RoutePreference preference,
            Coordinate previous, OfficialRouteGeometryRules visibilityRules) {
        Object result = ReflectionTestUtils.invokeMethod(new OfficialObstacleRouter(visibilityRules), "shortestPath",
                nodes, constraints, preference, nodes.get(0), nodes.get(1), previous);
        @SuppressWarnings("unchecked")
        List<Coordinate> path = (List<Coordinate>) ReflectionTestUtils.getField(result, "coordinates");
        return new SearchOutcome(path, (Long) ReflectionTestUtils.getField(result, "evaluatedPairCount"),
                (Long) ReflectionTestUtils.getField(result, "rejectedTurns"));
    }

    /** Независимый обход без верхней границы; сохраняет A*-приоритет и прежний порядок равенств. */
    private ReferenceOutcome reference(List<Coordinate> input, ConstraintIndex constraints, RoutePreference preference,
            Coordinate previous, OfficialRouteGeometryRules visibilityRules) {
        List<Coordinate> nodes = previous == null ? input : rounded(input);
        Coordinate start = input.get(0), end = input.get(1);
        Map<Long, Double> distance = new HashMap<>();
        Map<Long, Long> predecessor = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        Map<Long, Boolean> visibility = new HashMap<>();
        List<Double> goalCosts = new ArrayList<>();
        long startState = key(-1, 0);
        distance.put(startState, 0.0);
        PriorityQueue<ReferenceState> queue = new PriorityQueue<>(Comparator
                .comparingDouble((ReferenceState state) -> state.priority)
                .thenComparingDouble(state -> state.cost)
                .thenComparingInt(state -> state.node)
                .thenComparingInt(state -> state.previous));
        queue.add(new ReferenceState(-1, 0, 0, heuristic(nodes.get(0), end)));
        while (!queue.isEmpty()) {
            ReferenceState state = queue.poll();
            long currentState = key(state.previous, state.node);
            if (!visited.add(currentState)) continue;
            if (state.node == 1) {
                List<Coordinate> path = new ArrayList<>();
                for (long cursor = currentState; ; cursor = predecessor.get(cursor)) {
                    path.add(new Coordinate(nodes.get((int) cursor)));
                    if (cursor == startState) break;
                }
                Collections.reverse(path);
                return new ReferenceOutcome(path, goalCosts);
            }
            Coordinate current = nodes.get(state.node);
            Coordinate before = state.previous < 0 ? previous : nodes.get(state.previous);
            for (int next = 0; next < nodes.size(); next++) {
                if (next == state.node || next == state.previous) continue;
                Coordinate target = nodes.get(next);
                if (visibilityRules.pointInsideForbiddenClearance(target, constraints)) continue;
                if (before != null && !legalTurn(before, current, target)) continue;
                long edge = constraints.hasRoadCrossings() ? key(state.node, next)
                        : key(Math.min(state.node, next), Math.max(state.node, next));
                if (!visibility.computeIfAbsent(edge, ignored ->
                        visibilityRules.segmentAllowed(current, target, constraints))) continue;
                if (before != null && !visibilityRules.specialTurnAllowed(before, current, target, constraints)) continue;
                double candidate = state.cost + current.distance(target)
                        * invokeDouble(PREFERENCE_FACTOR, current, target, start, end, preference)
                        * (before == null ? 1 : invokeDouble(BEND_PENALTY, before, current, target, preference));
                long nextState = key(state.node, next);
                double currentBest = distance.getOrDefault(nextState, Double.POSITIVE_INFINITY);
                long priorState = predecessor.getOrDefault(nextState, -1L);
                if (candidate + 1e-9 < currentBest || Math.abs(candidate - currentBest) <= 1e-9
                        && (priorState < 0 || currentState < priorState)) {
                    distance.put(nextState, candidate);
                    predecessor.put(nextState, currentState);
                    queue.add(new ReferenceState(state.node, next, candidate, candidate + heuristic(target, end)));
                    if (next == 1) goalCosts.add(candidate);
                }
            }
        }
        return new ReferenceOutcome(List.of(), goalCosts);
    }

    private boolean legalTurn(Coordinate before, Coordinate at, Coordinate after) {
        return OfficialRouteDeflectionRules.validatePolyline("goal-bound-reference", List.of(
                new RouteCoordinate(before.x, before.y), new RouteCoordinate(at.x, at.y),
                new RouteCoordinate(after.x, after.y))).getIssues().isEmpty();
    }

    private static List<Coordinate> improvementNodes(double x, double y, double endOffset) {
        return List.of(point(x, y - 10), point(x, y + endOffset),
                point(x + 0.001, y - 0.05), point(x, y - 0.002));
    }

    private static GraphRules improvementGraph(List<Coordinate> nodes) {
        return new GraphRules(nodes, false, new int[][] {{0, 2}, {2, 1}, {0, 3}, {3, 1}});
    }

    private static List<Coordinate> rounded(List<Coordinate> nodes) {
        return nodes.stream().map(p -> new RouteCoordinate(p.x, p.y).toCoordinate()).collect(Collectors.toList());
    }

    private ImportedOfficialFeature feature(String type, String wkt) throws Exception {
        return new ImportedOfficialFeature("goal-bound-" + type, "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", type), new WKTReader().read(wkt));
    }

    private static double heuristic(Coordinate point, Coordinate end) { return 0.985 * point.distance(end); }
    private static Coordinate point(double x, double y) { return new Coordinate(x, y); }
    private static long key(int previous, int node) { return ((long) (previous + 1) << 32) | (node & 0xffffffffL); }

    private static Method privateMethod(String name, Class<?>... types) {
        try {
            Method method = OfficialObstacleRouter.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private double invokeDouble(Method method, Object... arguments) {
        try {
            return (double) method.invoke(router, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    /** Явные рёбра изолируют поиск; координаты сопоставляются после heading-округления. */
    private static final class GraphRules extends OfficialRouteGeometryRules {
        private final List<Coordinate> nodes;
        private final boolean directed;
        private final Set<Long> links = new HashSet<>();
        private final Map<Long, Integer> checks = new HashMap<>();
        private int rejectedGoalPredecessor = -1;
        private int rejectedGoalTurns;

        private GraphRules(List<Coordinate> nodes, boolean directed, int[][] edges) {
            super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
            this.nodes = nodes;
            this.directed = directed;
            for (int[] edge : edges) links.add(edgeKey(edge[0], edge[1]));
        }

        private long edgeKey(int from, int to) {
            return directed ? key(from, to) : key(Math.min(from, to), Math.max(from, to));
        }

        @Override boolean segmentAllowed(Coordinate start, Coordinate end, ConstraintIndex constraints) {
            int from = nodes.indexOf(start), to = nodes.indexOf(end);
            assertThat(from).as("known graph coordinate %s", start).isNotNegative();
            assertThat(to).as("known graph coordinate %s", end).isNotNegative();
            long edge = edgeKey(from, to);
            checks.merge(edge, 1, Integer::sum);
            return links.contains(edge);
        }

        @Override boolean specialTurnAllowed(Coordinate before, Coordinate at, Coordinate after,
                ConstraintIndex constraints) {
            if (rejectedGoalPredecessor >= 0 && nodes.indexOf(at) == rejectedGoalPredecessor
                    && nodes.indexOf(after) == 1) {
                rejectedGoalTurns++;
                return false;
            }
            return super.specialTurnAllowed(before, at, after, constraints);
        }

        private int checks(int from, int to) { return checks.getOrDefault(edgeKey(from, to), 0); }
        private long totalChecks() { return checks.values().stream().mapToLong(Integer::longValue).sum(); }
        private int checksFromToRange(int from, int begin, int end) {
            int total = 0;
            for (int to = begin; to < end; to++) total += checks(from, to);
            return total;
        }
    }

    private static final class SearchOutcome {
        private final List<Coordinate> path;
        private final long pairs;
        private final long rejectedTurns;
        private SearchOutcome(List<Coordinate> path, long pairs, long rejectedTurns) {
            this.path = path;
            this.pairs = pairs;
            this.rejectedTurns = rejectedTurns;
        }
    }

    private static final class ReferenceOutcome {
        private final List<Coordinate> path;
        private final List<Double> goalCosts;
        private ReferenceOutcome(List<Coordinate> path, List<Double> goalCosts) {
            this.path = path;
            this.goalCosts = goalCosts;
        }
    }

    private static final class ReferenceState {
        private final int previous;
        private final int node;
        private final double cost;
        private final double priority;
        private ReferenceState(int previous, int node, double cost, double priority) {
            this.previous = previous;
            this.node = node;
            this.cost = cost;
            this.priority = priority;
        }
    }
}
