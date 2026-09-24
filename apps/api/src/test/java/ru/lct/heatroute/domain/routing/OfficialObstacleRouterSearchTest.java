package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Сверяет оптимизацию поиска с прежним обходом, включая точный выбор при равной стоимости. */
class OfficialObstacleRouterSearchTest {
    private static final Method PREFERENCE_FACTOR = privateMethod("preferenceFactor",
            Coordinate.class, Coordinate.class, Coordinate.class, Coordinate.class, RoutePreference.class);
    private static final Method BEND_PENALTY = privateMethod("bendPenalty",
            List.class, int.class, int.class, int.class, RoutePreference.class);
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void findsTheLegalAlternativeInsteadOfReturningTheCheaperObtuseTurn() {
        List<Coordinate> nodes = List.of(new Coordinate(0, 0), new Coordinate(9, 10),
                new Coordinate(10, 0), new Coordinate(9, 1), new Coordinate(10, 10));
        for (RoutePreference preference : RoutePreference.values()) {
            ExplicitGraphRules graph = new ExplicitGraphRules(nodes,
                    new int[][] {{0, 2}, {2, 3}, {3, 1}, {2, 4}, {4, 1}});
            List<Coordinate> actual = search(new OfficialObstacleRouter(graph), nodes, rules.index(List.of()), preference);
            assertThat(actual).as(preference.name()).containsExactly(nodes.get(0), nodes.get(2), nodes.get(4), nodes.get(1));
            Object result = ReflectionTestUtils.invokeMethod(new OfficialObstacleRouter(graph), "shortestPath",
                    nodes, rules.index(List.of()), preference, nodes.get(0), nodes.get(1));
            assertThat((Long) ReflectionTestUtils.getField(result, "rejectedTurns")).isPositive();
        }
    }

    @Test
    void anObtuseTurnIsNotAConnectionWhenNoLegalAlternativeExists() {
        List<Coordinate> nodes = List.of(new Coordinate(0, 0), new Coordinate(9, 10),
                new Coordinate(10, 0), new Coordinate(9, 1));
        ExplicitGraphRules graph = new ExplicitGraphRules(nodes, new int[][] {{0, 2}, {2, 3}, {3, 1}});
        assertThat(search(new OfficialObstacleRouter(graph), nodes, rules.index(List.of()), RoutePreference.SHORTEST)).isEmpty();
    }

    @Test
    void detectsAnIsolatedDestinationBeforeExploringItsDenseDisconnectedNeighbourhood() {
        List<Coordinate> nodes = new ArrayList<>(List.of(new Coordinate(0, 0), new Coordinate(20, 10)));
        for (int index = 2; index < 40; index++) nodes.add(new Coordinate(index, 0));
        List<int[]> links = new ArrayList<>();
        for (int from = 0; from < nodes.size(); from++) {
            for (int to = from + 1; to < nodes.size(); to++) {
                if (from != 1 && to != 1) links.add(new int[] {from, to});
            }
        }
        ExplicitGraphRules graph = new ExplicitGraphRules(nodes, links.toArray(new int[0][]));
        assertThat(search(new OfficialObstacleRouter(graph), nodes, rules.index(List.of()), RoutePreference.SHORTEST)).isEmpty();
        assertThat(graph.checks.values().stream().mapToInt(Integer::intValue).sum())
                .as("only the isolated endpoint's possible neighbours need checking").isLessThanOrEqualTo(nodes.size() - 1);
    }

    @Test
    void detectsASmallDisconnectedTargetComponentWithoutExploringTheLargeStartComponent() {
        List<Coordinate> nodes = new ArrayList<>(List.of(new Coordinate(0, 0),
                new Coordinate(20, 10), new Coordinate(21, 10)));
        for (int index = 3; index < 50; index++) nodes.add(new Coordinate(index, 0));
        List<int[]> links = new ArrayList<>();
        links.add(new int[] {1, 2});
        for (int from = 0; from < nodes.size(); from++) {
            for (int to = from + 1; to < nodes.size(); to++) {
                if (from != 1 && from != 2 && to != 1 && to != 2) links.add(new int[] {from, to});
            }
        }
        ExplicitGraphRules graph = new ExplicitGraphRules(nodes, links.toArray(new int[0][]));
        assertThat(search(new OfficialObstacleRouter(graph), nodes, rules.index(List.of()), RoutePreference.SHORTEST)).isEmpty();
        assertThat(graph.checks.values().stream().mapToInt(Integer::intValue).sum())
                .as("two-vertex target component is sufficient to prove disconnection")
                .isLessThanOrEqualTo(2 * (nodes.size() - 1));
    }

    @Test
    void matchesLegalDijkstraReferenceForEveryPreferenceOnSeededVisibilityGraphs() throws Exception {
        List<ImportedOfficialFeature> obstacles = List.of(
                park("first", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))"),
                park("second", "POLYGON ((15 8, 25 8, 25 20, 15 20, 15 8))"));
        ConstraintIndex constraints = rules.index(rules.baseConstraints(obstacles, 100));
        Random random = new Random(6171362L);
        for (int fixture = 0; fixture < 12; fixture++) {
            List<Coordinate> nodes = new ArrayList<>(List.of(
                    new Coordinate(0, 0), new Coordinate(100, 0),
                    new Coordinate(38, -12), new Coordinate(62, -12),
                    new Coordinate(38, 12), new Coordinate(62, 12)));
            for (int index = 0; index < 20; index++) {
                nodes.add(new Coordinate(random.nextDouble() * 100, random.nextDouble() * 80 - 40));
            }
            for (RoutePreference preference : RoutePreference.values()) {
                assertLegalOptimal(nodes, constraints, preference, rules, "fixture " + fixture);
            }
        }
    }

    @Test
    void matchesLegalDijkstraOnSeededSparseGraphsAndPreservesReachability() {
        Random random = new Random(526171362L);
        for (int fixture = 0; fixture < 100; fixture++) {
            List<Coordinate> nodes = new ArrayList<>();
            for (int index = 0; index < 9; index++) {
                nodes.add(new Coordinate(random.nextDouble() * 100, random.nextDouble() * 100));
            }
            List<int[]> edges = new ArrayList<>();
            for (int from = 0; from < nodes.size(); from++) {
                for (int to = from + 1; to < nodes.size(); to++) {
                    if (random.nextDouble() < 0.35) edges.add(new int[] {from, to});
                }
            }
            ExplicitGraphRules graph = new ExplicitGraphRules(nodes, edges.toArray(new int[0][]));
            for (RoutePreference preference : RoutePreference.values()) {
                assertLegalOptimal(nodes, rules.index(List.of()), preference, graph, "sparse " + fixture);
            }
        }
    }

    private void assertLegalOptimal(List<Coordinate> nodes, ConstraintIndex constraints,
            RoutePreference preference, OfficialRouteGeometryRules graph, String label) {
        List<Coordinate> expected = referenceSearch(nodes, constraints, preference, graph, true);
        List<Coordinate> actual = search(new OfficialObstacleRouter(graph), nodes, constraints, preference);
        assertThat(actual.isEmpty()).as(label + " / " + preference).isEqualTo(expected.isEmpty());
        if (expected.isEmpty()) return;
        assertThat(pathCost(actual, nodes, preference)).as(label + " / " + preference)
                .isCloseTo(pathCost(expected, nodes, preference), org.assertj.core.data.Offset.offset(1e-8));
        for (int index = 1; index + 1 < actual.size(); index++) {
            assertThat(legalTurn(actual.get(index - 1), actual.get(index), actual.get(index + 1)))
                    .as(label + " / " + preference).isTrue();
        }
    }

    private double pathCost(List<Coordinate> path, List<Coordinate> nodes, RoutePreference preference) {
        double result = 0;
        for (int index = 1; index < path.size(); index++) {
            int previous = index > 1 ? nodes.indexOf(path.get(index - 2)) : -1;
            Coordinate current = path.get(index - 1), next = path.get(index);
            result += current.distance(next)
                    * invokeDouble(PREFERENCE_FACTOR, current, next, nodes.get(0), nodes.get(1), preference)
                    * invokeDouble(BEND_PENALTY, nodes, previous, nodes.indexOf(current), nodes.indexOf(next), preference);
        }
        return result;
    }

    private boolean legalTurn(Coordinate before, Coordinate at, Coordinate after) {
        return OfficialRouteDeflectionRules.validatePolyline("reference-transition", List.of(
                new RouteCoordinate(before.x, before.y), new RouteCoordinate(at.x, at.y),
                new RouteCoordinate(after.x, after.y))).getIssues().isEmpty();
    }

    @Test
    void retainsDeterministicWinnerForEqualCostSymmetricDetours() throws Exception {
        List<Coordinate> nodes = List.of(
                new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(5, 5), new Coordinate(5, -5));
        ConstraintIndex constraints = rules.index(rules.baseConstraints(List.of(
                park("middle", "POLYGON ((4 -1, 6 -1, 6 1, 4 1, 4 -1))")), 100));

        List<Coordinate> expected = originalSearch(nodes, constraints, RoutePreference.SHORTEST);

        assertThat(expected).containsExactly(nodes.get(0), nodes.get(2), nodes.get(1));
        for (int repeat = 0; repeat < 5; repeat++) {
            assertThat(search(nodes, constraints, RoutePreference.SHORTEST))
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void preservesPredecessorTieBreakingAroundTheCostTolerance() throws Exception {
        ConstraintIndex constraints = rules.index(rules.baseConstraints(List.of(
                park("middle", "POLYGON ((4 -1, 6 -1, 6 1, 4 1, 4 -1))")), 100));
        for (double delta : new double[] {-1e-8, -1e-10, 0, 1e-10, 1e-8}) {
            List<Coordinate> nodes = List.of(new Coordinate(0, 0), new Coordinate(10, 0),
                    new Coordinate(5, 5 + delta), new Coordinate(5, -5),
                    new Coordinate(2, 4), new Coordinate(8, 4),
                    new Coordinate(2, -4), new Coordinate(8, -4));
            for (RoutePreference preference : RoutePreference.values()) {
                assertThat(search(nodes, constraints, preference))
                        .as("delta %s / %s", delta, preference)
                        .containsExactlyElementsOf(originalSearch(nodes, constraints, preference));
            }
        }
    }

    @Test
    void boundsDenseStorageAndRetainsSparseStateSemanticsWithoutIntegerOverflow() {
        assertThat(OfficialObstacleRouter.SearchStates.create(1023).getClass().getSimpleName())
                .isEqualTo("DenseSearchStates");
        assertThat(OfficialObstacleRouter.SearchStates.create(1024).getClass().getSimpleName())
                .isEqualTo("SparseSearchStates");
        for (int nodeCount : new int[] {3, 1023, 1024, Integer.MAX_VALUE}) {
            OfficialObstacleRouter.SearchStates states = OfficialObstacleRouter.SearchStates.create(nodeCount);
            long state = key(nodeCount - 1, nodeCount - 1);
            assertThat(states.distance(state)).isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(states.predecessor(state)).isEqualTo(-1L);
            states.improve(state, 42.0, key(-1, 0));
            assertThat(states.distance(state)).isEqualTo(42.0);
            assertThat(states.predecessor(state)).isEqualTo(key(-1, 0));
            assertThat(states.visit(state)).isTrue();
            assertThat(states.visit(state)).isFalse();
            states.improve(state, 41.0, key(0, 1));
            assertThat(states.distance(state)).isEqualTo(41.0);
            assertThat(states.predecessor(state)).isEqualTo(key(0, 1));
            // Прежний алгоритм сохраняет visited при улучшении предшественника.
            assertThat(states.visit(state)).isFalse();
        }
    }

    @Test
    void denseAndSparseSearchKeepTheSameDirectWinner() {
        for (int nodeCount : new int[] {1023, 1024}) {
            List<Coordinate> nodes = new ArrayList<>(List.of(new Coordinate(0, 0), new Coordinate(10, 0)));
            for (int index = 2; index < nodeCount; index++) {
                nodes.add(new Coordinate(1000 + index, 1000));
            }
            assertThat(search(nodes, rules.index(List.of()), RoutePreference.SHORTEST))
                    .containsExactly(nodes.get(0), nodes.get(1));
        }
    }

    @Test
    void returnsNoPathWhenVisibilityGraphIsDisconnected() throws Exception {
        List<Coordinate> nodes = List.of(new Coordinate(0, 0), new Coordinate(10, 0));
        ConstraintIndex constraints = rules.index(rules.baseConstraints(List.of(
                park("middle", "POLYGON ((4 -1, 6 -1, 6 1, 4 1, 4 -1))")), 100));

        assertThat(search(nodes, constraints, RoutePreference.SHORTEST)).isEmpty();
        assertThat(originalSearch(nodes, constraints, RoutePreference.SHORTEST)).isEmpty();
    }

    @Test
    void emptyAndSingleNodeGraphsHaveNoPath() {
        Coordinate endpoint = new Coordinate(0, 0);
        ConstraintIndex constraints = rules.index(List.of());
        for (List<Coordinate> nodes : List.of(Collections.<Coordinate>emptyList(), List.of(endpoint))) {
            Object result = ReflectionTestUtils.invokeMethod(router, "shortestPath",
                    nodes, constraints, RoutePreference.SHORTEST, endpoint, endpoint);
            assertThat((List<?>) ReflectionTestUtils.getField(result, "coordinates")).isEmpty();
            assertThat(ReflectionTestUtils.getField(result, "evaluatedPairCount")).isEqualTo(0L);
        }
    }

    @Test
    void deduplicatedCoincidentEndpointsYieldNoRoute() {
        Coordinate endpoint = new Coordinate(0, 0);
        assertThat(router.find(endpoint, new Coordinate(endpoint), 100,
                Collections.<ImportedOfficialFeature>emptyList(), Set.of(), RoutePreference.SHORTEST)).isNull();
    }

    @Test
    void forbiddenBoundaryTouchRejectsEdgesButPublishedClearanceRemainsLegal() throws Exception {
        List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(List.of(
                park("middle", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))")), 100);
        ConstraintIndex index = rules.index(constraints);
        Coordinate start = new Coordinate(0, 0);
        Coordinate boundary = new Coordinate(constraints.get(0).blocked().getEnvelopeInternal().getMinX(), 0);
        Coordinate publishedClearance = new Coordinate(39, 0);

        assertThat(rules.pointInsideForbiddenClearance(boundary, index)).isTrue();
        assertThat(rules.segmentAllowed(start, boundary, index)).isFalse();
        assertThat(rules.segmentAllowed(boundary, start, index)).isFalse();
        assertThat(rules.pointInsideForbiddenClearance(publishedClearance, index)).isFalse();
        assertThat(rules.segmentAllowed(start, publishedClearance, index)).isTrue();
    }

    @Test
    void skipsOnlyBlockedVerticesWithoutChangingTheOriginalRouteOrNodeOrder() throws Exception {
        List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(List.of(
                park("middle", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))")), 100);
        ConstraintIndex index = rules.index(constraints);
        Coordinate interior = new Coordinate(50, 0);
        Coordinate boundary = new Coordinate(constraints.get(0).blocked().getEnvelopeInternal().getMinX(), 0);
        List<Coordinate> nodes = List.of(new Coordinate(0, 0), new Coordinate(100, 0),
                interior, new Coordinate(38, 12), boundary, new Coordinate(62, 12),
                new Coordinate(38, -12), new Coordinate(62, -12));
        for (RoutePreference preference : RoutePreference.values()) {
            VisibilityCounter originalRules = new VisibilityCounter(Set.of(interior, boundary));
            VisibilityCounter optimizedRules = new VisibilityCounter(Set.of(interior, boundary));
            List<Coordinate> expected = originalSearch(nodes, index, preference, originalRules);
            List<Coordinate> actual = search(new OfficialObstacleRouter(optimizedRules), nodes, index, preference);

            assertThat(actual).isNotEmpty().containsExactlyElementsOf(expected);
            assertThat(originalRules.blockedEndpointChecks).isPositive();
            assertThat(optimizedRules.blockedEndpointChecks).isZero();
            assertThat(optimizedRules.visibilityChecks).isLessThan(originalRules.visibilityChecks);
            assertThat(nodes.get(2)).isSameAs(interior);
            assertThat(nodes.get(4)).isSameAs(boundary);
        }
    }

    @Test
    void blockedStartAndDestinationStillHaveNoPath() throws Exception {
        ConstraintIndex index = rules.index(rules.baseConstraints(List.of(
                park("middle", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))")), 100));
        for (List<Coordinate> nodes : List.of(
                List.of(new Coordinate(50, 0), new Coordinate(100, 0), new Coordinate(62, 12)),
                List.of(new Coordinate(0, 0), new Coordinate(50, 0), new Coordinate(38, 12)))) {
            assertThat(search(nodes, index, RoutePreference.SHORTEST)).isEmpty();
            assertThat(originalSearch(nodes, index, RoutePreference.SHORTEST)).isEmpty();
        }
    }

    @Test
    void pocketEnvelopeGateRejectsOutsideEndpointsButRetainsBoundaryAnchors() throws Exception {
        OfficialRouteGeometryRules.Constraint constraint = rules.baseConstraints(List.of(park("courtyard",
                "POLYGON ((0 0,100 0,100 100,60 100,60 20,40 20,40 100,0 100,0 0))")), 100).get(0);
        Geometry buffered = constraint.blocked().buffer(0.25, 2);
        Geometry hull = buffered.convexHull();
        Coordinate[] support = ReflectionTestUtils.invokeMethod(router, "navigationCoordinates", hull);
        List<Coordinate> outside = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(router, "addPocketNavigationNodes", outside,
                new Coordinate(-20, 50), new Coordinate(120, 50), constraint, buffered, hull, support);
        assertThat(outside).isEmpty();

        Coordinate boundaryAnchor = new Coordinate(50, hull.getEnvelopeInternal().getMaxY());
        assertThat(hull.covers(hull.getFactory().createPoint(boundaryAnchor))).isTrue();
        assertThat(constraint.preparedBlocked().covers(hull.getFactory().createPoint(boundaryAnchor))).isFalse();
        List<Coordinate> boundary = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(router, "addPocketNavigationNodes", boundary,
                boundaryAnchor, new Coordinate(50, -20), constraint, buffered, hull, support);
        assertThat(boundary).isNotEmpty();
        assertThat(boundary).anySatisfy(point -> assertThat(point.distance(boundaryAnchor)).isLessThan(1e-8));
    }

    @Test
    void keepsTheHullRouteWhenTheSameCorridorOffersADifferentPocketRoute() throws Exception {
        assertHullRoutePrecedesPockets(110, 75);
    }

    @Test
    void triesAllHullCorridorsBeforeAnAvailableSmallCorridorPocketRoute() throws Exception {
        assertHullRoutePrecedesPockets(150, 200);
    }

    private void assertHullRoutePrecedesPockets(double markerY, double hullExpansion) throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                park("courtyard", "POLYGON ((0 0,100 0,100 100,60 100,60 20,40 20,40 100,0 100,0 0))"),
                park("outside-marker", "POLYGON ((48 " + markerY + ", 52 " + markerY + ", 52 "
                        + (markerY + 4) + ", 48 " + (markerY + 4) + ", 48 " + markerY + "))"));
        Coordinate start = new Coordinate(50, 35);
        Coordinate end = new Coordinate(50, -40);
        OfficialRoutingEnvironment environment = router.prepare(features);
        List<OfficialRouteGeometryRules.Constraint> constraints = environment.constraints(100, Set.of(), start, end);
        ConstraintIndex index = rules.index(constraints);
        if (hullExpansion > 75) {
            List<Coordinate> smallHull = ReflectionTestUtils.invokeMethod(router, "navigationNodes",
                    start, end, index, 75.0, false);
            assertThat(search(smallHull, index, RoutePreference.SHORTEST)).isEmpty();
        }
        RoutePath ordinary = validatedGraphRoute(start, end, index, constraints, hullExpansion, false);
        RoutePath pocket = validatedGraphRoute(start, end, index, constraints, 75, true);
        assertThat(pocket.coordinates()).isNotEqualTo(ordinary.coordinates());

        RoutePath actual = router.find(start, end, 100, environment, Set.of(), RoutePreference.SHORTEST);

        assertThat(actual).isNotNull();
        assertThat(actual.coordinates()).containsExactlyElementsOf(ordinary.coordinates());
        assertThat(actual.lengthM()).isEqualTo(ordinary.lengthM());
        assertThat(router.lineAllowed(actual.coordinates(), 100, environment, Set.of(), List.of())).isTrue();
    }

    private RoutePath validatedGraphRoute(Coordinate start, Coordinate end, ConstraintIndex index,
            List<OfficialRouteGeometryRules.Constraint> constraints, double expansion, boolean pockets) {
        List<Coordinate> nodes = ReflectionTestUtils.invokeMethod(router, "navigationNodes",
                start, end, index, expansion, pockets);
        List<Coordinate> path = search(nodes, index, RoutePreference.SHORTEST);
        assertThat(path).isNotEmpty();
        List<Coordinate> normalized = ReflectionTestUtils.invokeMethod(router, "normalize", path, index);
        List<Coordinate> constructible = ReflectionTestUtils.invokeMethod(router, "snapConstructibleCorners",
                normalized, index, RoutePreference.SHORTEST);
        assertThat(rules.lineAllowed(rules.line(constructible), index)).isTrue();
        return ReflectionTestUtils.invokeMethod(router, "path", constructible, constraints);
    }

    @Test
    void preservesCancellationAndInterruptStatusDuringSearch() {
        List<Coordinate> nodes = List.of(new Coordinate(0, 0), new Coordinate(10, 0));
        ConstraintIndex constraints = rules.index(List.of());
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> search(nodes, constraints, RoutePreference.SHORTEST))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void keepsEndpointAndFinalGeometryValidationForFreshSearches() throws Exception {
        List<ImportedOfficialFeature> obstacles = List.of(
                park("middle", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))"));
        Coordinate start = new Coordinate(0, 0);
        Coordinate end = new Coordinate(100, 0);
        for (RoutePreference preference : RoutePreference.values()) {
            RoutePath previous = null;
            for (int repeat = 0; repeat < 2; repeat++) {
                // Новое окружение исключает повторное использование ранее найденного маршрута.
                OfficialRoutingEnvironment environment = router.prepare(obstacles);
                RoutePath route = router.find(start, end, 100, environment, Set.of(), preference);
                assertThat(route).isNotNull();
                assertThat(route.coordinates().get(0)).isEqualTo(start);
                assertThat(route.coordinates().get(route.coordinates().size() - 1)).isEqualTo(end);
                assertThat(router.lineAllowed(route.coordinates(), 100, environment, Set.of(), List.of()))
                        .isTrue();
                if (previous != null) {
                    assertThat(route.coordinates()).containsExactlyElementsOf(previous.coordinates());
                    assertThat(route.lengthM()).isEqualTo(previous.lengthM());
                }
                previous = route;
            }
        }
        assertThat(router.find(new Coordinate(50, 0), end, 100, obstacles, Set.of(),
                RoutePreference.SHORTEST)).isNull();
        assertThat(router.find(start, new Coordinate(50, 0), 100, obstacles, Set.of(),
                RoutePreference.SHORTEST)).isNull();
    }

    @SuppressWarnings("unchecked")
    private List<Coordinate> search(
            List<Coordinate> nodes, ConstraintIndex constraints, RoutePreference preference) {
        return search(router, nodes, constraints, preference);
    }

    @SuppressWarnings("unchecked")
    private List<Coordinate> search(OfficialObstacleRouter searchRouter,
            List<Coordinate> nodes, ConstraintIndex constraints, RoutePreference preference) {
        Object result = ReflectionTestUtils.invokeMethod(
                searchRouter, "shortestPath", nodes, constraints, preference, nodes.get(0), nodes.get(1));
        return (List<Coordinate>) ReflectionTestUtils.getField(result, "coordinates");
    }

    /** Исходный обход 6171362: проверка видимости до сравнения стоимости и boxed-состояния. */
    private List<Coordinate> originalSearch(
            List<Coordinate> nodes, ConstraintIndex constraints, RoutePreference preference) {
        return originalSearch(nodes, constraints, preference, rules);
    }

    private List<Coordinate> originalSearch(List<Coordinate> nodes, ConstraintIndex constraints,
            RoutePreference preference, OfficialRouteGeometryRules visibilityRules) {
        return referenceSearch(nodes, constraints, preference, visibilityRules, false);
    }

    /** Без отсечений по нижней оценке; legal-вариант — Дейкстра с итоговым предикатом угла. */
    private List<Coordinate> referenceSearch(List<Coordinate> nodes, ConstraintIndex constraints,
            RoutePreference preference, OfficialRouteGeometryRules visibilityRules, boolean mandatoryTurns) {
        Map<Long, Double> distance = new HashMap<>();
        Map<Long, Long> predecessor = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        Map<Long, Boolean> visibility = new HashMap<>();
        long startState = key(-1, 0);
        distance.put(startState, 0.0);
        PriorityQueue<ReferenceState> queue = new PriorityQueue<>(Comparator
                .comparingDouble((ReferenceState state) -> state.priority)
                .thenComparingDouble(state -> state.cost)
                .thenComparingInt(state -> state.node)
                .thenComparingInt(state -> state.previous));
        queue.add(new ReferenceState(-1, 0, 0, mandatoryTurns ? 0 : heuristic(nodes.get(0), nodes.get(1))));
        ReferenceState targetState = null;
        while (!queue.isEmpty()) {
            ReferenceState state = queue.poll();
            long currentState = key(state.previous, state.node);
            if (!visited.add(currentState)) {
                continue;
            }
            if (state.node == 1) {
                targetState = state;
                break;
            }
            Coordinate current = nodes.get(state.node);
            for (int next = 0; next < nodes.size(); next++) {
                if (next == state.node || next == state.previous) {
                    continue;
                }
                int left = Math.min(state.node, next);
                int right = Math.max(state.node, next);
                if (!visibility.computeIfAbsent(key(left, right), ignored ->
                        visibilityRules.segmentAllowed(nodes.get(left), nodes.get(right), constraints))) {
                    continue;
                }
                Coordinate target = nodes.get(next);
                if (mandatoryTurns && state.previous >= 0
                        && !legalTurn(nodes.get(state.previous), current, target)) continue;
                double factor = invokeDouble(PREFERENCE_FACTOR,
                        current, target, nodes.get(0), nodes.get(1), preference);
                double bend = invokeDouble(BEND_PENALTY,
                        nodes, state.previous, state.node, next, preference);
                double candidate = state.cost + current.distance(target) * factor * bend;
                long nextState = key(state.node, next);
                double currentBest = distance.getOrDefault(nextState, Double.POSITIVE_INFINITY);
                Long priorState = predecessor.get(nextState);
                if (candidate + 1e-9 < currentBest
                        || (Math.abs(candidate - currentBest) <= 1e-9
                                && (priorState == null || currentState < priorState))) {
                    distance.put(nextState, candidate);
                    predecessor.put(nextState, currentState);
                    queue.add(new ReferenceState(state.node, next, candidate,
                            candidate + (mandatoryTurns ? 0 : heuristic(target, nodes.get(1)))));
                }
            }
        }
        if (targetState == null) {
            return List.of();
        }
        List<Coordinate> result = new ArrayList<>();
        long cursor = key(targetState.previous, targetState.node);
        while (true) {
            result.add(new Coordinate(nodes.get((int) cursor)));
            if (cursor == startState) {
                break;
            }
            cursor = predecessor.get(cursor);
        }
        Collections.reverse(result);
        return result;
    }

    private static double heuristic(Coordinate coordinate, Coordinate end) {
        return 0.985 * coordinate.distance(end);
    }

    private static Method privateMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = OfficialObstacleRouter.class.getDeclaredMethod(name, parameterTypes);
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

    private static long key(int previous, int node) {
        return ((long) (previous + 1) << 32) | (node & 0xffffffffL);
    }

    private ImportedOfficialFeature park(String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction",
                new ObjectMapper().readTree("{\"restriction_type\":\"park\"}"), new WKTReader().read(wkt));
    }

    private static final class VisibilityCounter extends OfficialRouteGeometryRules {
        private final Set<Coordinate> blockedEndpoints;
        private int visibilityChecks;
        private int blockedEndpointChecks;

        private VisibilityCounter(Set<Coordinate> blockedEndpoints) {
            super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
            this.blockedEndpoints = blockedEndpoints;
        }

        @Override
        boolean segmentAllowed(Coordinate start, Coordinate end, ConstraintIndex constraints) {
            visibilityChecks++;
            if (blockedEndpoints.contains(start) || blockedEndpoints.contains(end)) {
                blockedEndpointChecks++;
            }
            return super.segmentAllowed(start, end, constraints);
        }
    }

    /** Тестируем направленный поиск на явно заданном графе, независимо от ГИС-адаптера. */
    private static final class ExplicitGraphRules extends OfficialRouteGeometryRules {
        private final List<Coordinate> nodes;
        private final Set<Long> links = new HashSet<>();
        private final Map<Long, Integer> checks = new HashMap<>();
        private ExplicitGraphRules(List<Coordinate> nodes, int[][] links) {
            super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
            this.nodes = nodes;
            for (int[] link : links) this.links.add(edgeKey(link[0], link[1]));
        }
        private long edgeKey(int first, int second) { return key(Math.min(first, second), Math.max(first, second)); }
        @Override boolean segmentAllowed(Coordinate start, Coordinate end, ConstraintIndex constraints) {
            long edge = edgeKey(nodes.indexOf(start), nodes.indexOf(end));
            checks.merge(edge, 1, Integer::sum);
            return links.contains(edge);
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
