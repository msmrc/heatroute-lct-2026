package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialObstacleRouter {
    private static final double NAVIGATION_MARGIN_M = 0.25;
    private static final double MINIMUM_PREFERENCE_FACTOR = 0.985;
    private static final int MAX_VERTICES_PER_OBSTACLE = 12;
    private static final double[] CORRIDOR_EXPANSIONS = {75.0, 200.0, 600.0};
    static final double MAXIMUM_SEARCH_CORRIDOR_M = 600.0;

    private final OfficialRouteGeometryRules rules;

    public OfficialObstacleRouter(OfficialRouteGeometryRules rules) {
        this.rules = rules;
    }

    OfficialRoutingEnvironment prepare(List<ImportedOfficialFeature> features) {
        List<ImportedOfficialFeature> core = features.stream()
                .filter(feature -> !isWindowedRoutingFeature(feature))
                .collect(java.util.stream.Collectors.toList());
        List<ImportedOfficialFeature> windowed = features.stream()
                .filter(this::isWindowedRoutingFeature)
                .collect(java.util.stream.Collectors.toList());
        return new OfficialRoutingEnvironment(
                core,
                new InMemoryRoutingFeatureSource(windowed),
                rules);
    }

    OfficialRoutingEnvironment prepare(List<ImportedOfficialFeature> features, RoutingFeatureSource source) {
        return new OfficialRoutingEnvironment(features, source, rules);
    }

    private boolean isWindowedRoutingFeature(ImportedOfficialFeature feature) {
        return "restriction".equals(feature.getObjectType())
                || "oks_existing".equals(feature.getObjectType());
    }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgress(
            List<ImportedOfficialFeature> features,
            int diameter,
            Coordinate connectionPoint) {
        return rules.normalEgress(features, diameter, connectionPoint);
    }

    RoutePath find(
            Coordinate start,
            Coordinate end,
            int diameter,
            List<ImportedOfficialFeature> features,
            Set<String> exemptFeatureIds,
            RoutePreference preference) {
        return find(start, end, diameter, prepare(features), exemptFeatureIds, preference);
    }

    List<String> directBlockingConstraintIds(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes) {
        List<Constraint> constraints = new ArrayList<>(environment.constraints(
                diameter, exemptFeatureIds, start, end));
        constraints.addAll(rules.applicableConstraints(
                rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(),
                start,
                end));
        LineString direct = rules.line(List.of(start, end));
        return constraints.stream()
                .filter(constraint -> constraint.rule().isForbidden())
                .filter(constraint -> constraint.blocked().intersects(direct))
                .map(constraint -> constraint.type() + ":" + constraint.id())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
    }

    RoutePath find(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            RoutePreference preference) {
        return find(
                start,
                end,
                diameter,
                environment,
                exemptFeatureIds,
                preference,
                Collections.emptyList());
    }

    boolean lineAllowed(
            List<Coordinate> coordinates,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes) {
        if (coordinates.size() < 2) {
            return false;
        }
        Coordinate start = coordinates.get(0);
        Coordinate end = coordinates.get(coordinates.size() - 1);
        List<Constraint> constraints = new ArrayList<>(environment.constraints(
                diameter, exemptFeatureIds, start, end));
        constraints.addAll(rules.applicableConstraints(
                rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(),
                start,
                end));
        ConstraintIndex index = rules.index(constraints);
        return !rules.pointInsideForbiddenClearance(start, index)
                && !rules.pointInsideForbiddenClearance(end, index)
                && rules.lineAllowed(rules.line(coordinates), index);
    }

    RoutePath findAvoidingDepthConflicts(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            Set<String> failedUtilityIds,
            List<LineString> acceptedRoutes) {
        List<Constraint> additional = environment.depthAvoidanceConstraints(failedUtilityIds);
        return find(
                start,
                end,
                diameter,
                environment,
                exemptFeatureIds,
                RoutePreference.SHORTEST,
                acceptedRoutes,
                additional);
    }

    RoutePath find(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes) {
        return find(
                start,
                end,
                diameter,
                environment,
                exemptFeatureIds,
                preference,
                acceptedRoutes,
                Collections.emptyList());
    }

    private RoutePath find(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes,
            List<Constraint> additionalConstraints) {
        if (acceptedRoutes.isEmpty() && additionalConstraints.isEmpty()) {
            String key = routeCacheKey(start, end, diameter, exemptFeatureIds, preference);
            return environment.cachedRoute(key, () -> findUncached(
                    start, end, diameter, environment, exemptFeatureIds, preference,
                    acceptedRoutes, additionalConstraints));
        }
        return findUncached(
                start, end, diameter, environment, exemptFeatureIds, preference,
                acceptedRoutes, additionalConstraints);
    }

    private RoutePath findUncached(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes,
            List<Constraint> additionalConstraints) {
        List<Constraint> constraints = new ArrayList<>(environment.constraints(
                diameter, exemptFeatureIds, start, end));
        constraints.addAll(rules.applicableConstraints(
                rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(),
                start,
                end));
        constraints.addAll(rules.applicableConstraints(
                additionalConstraints,
                exemptFeatureIds,
                start,
                end));
        ConstraintIndex constraintIndex = rules.index(constraints);
        if (rules.pointInsideForbiddenClearance(start, constraintIndex)
                || rules.pointInsideForbiddenClearance(end, constraintIndex)) {
            return null;
        }
        if (rules.segmentAllowed(start, end, constraintIndex)) {
            return path(List.of(start, end), constraints);
        }
        for (double expansion : CORRIDOR_EXPANSIONS) {
            List<Coordinate> nodes = navigationNodes(start, end, constraintIndex, expansion);
            SearchResult search = shortestPath(nodes, constraintIndex, preference, start, end);
            environment.recordVisibilitySearch(nodes.size(), expansion, search.evaluatedPairCount);
            if (!search.coordinates.isEmpty()) {
                List<Coordinate> normalized = normalize(search.coordinates, constraintIndex);
                LineString line = rules.line(normalized);
                if (rules.lineAllowed(line, constraintIndex)) {
                    return path(normalized, constraints);
                }
            }
        }
        return null;
    }

    private String routeCacheKey(
            Coordinate start,
            Coordinate end,
            int diameter,
            Set<String> exemptFeatureIds,
            RoutePreference preference) {
        String exemptions = exemptFeatureIds.stream().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return Math.round(start.x * 1000.0) + ":" + Math.round(start.y * 1000.0)
                + ">" + Math.round(end.x * 1000.0) + ":" + Math.round(end.y * 1000.0)
                + "|" + diameter + "|" + preference + "|" + exemptions;
    }

    private RoutePath path(List<Coordinate> coordinates, List<Constraint> constraints) {
        List<Coordinate> rounded = coordinates.stream()
                .map(coordinate -> new RouteCoordinate(coordinate.x, coordinate.y).toCoordinate())
                .collect(java.util.stream.Collectors.toList());
        LineString line = rules.line(rounded);
        return new RoutePath(rounded, rules.sections(line, constraints), line.getLength());
    }

    private List<Coordinate> navigationNodes(
            Coordinate start,
            Coordinate end,
            ConstraintIndex constraints,
            double expansionM) {
        List<Coordinate> result = new ArrayList<>();
        result.add(new Coordinate(start));
        result.add(new Coordinate(end));
        Envelope corridor = new Envelope(start, end);
        corridor.expandBy(expansionM);
        LineString directLine = rules.line(List.of(start, end));
        for (Constraint constraint : constraints.query(corridor)) {
            if (!constraint.rule().isForbidden()
                    || !constraint.blocked().getEnvelopeInternal().intersects(corridor)
                    || !constraint.blocked().isWithinDistance(directLine, expansionM)) {
                continue;
            }
            Geometry navigationGeometry = constraint.blocked().buffer(NAVIGATION_MARGIN_M, 2).convexHull();
            Coordinate[] coordinates = navigationCoordinates(navigationGeometry);
            int uniqueCount = coordinates.length > 1 && coordinates[0].equals2D(coordinates[coordinates.length - 1])
                    ? coordinates.length - 1
                    : coordinates.length;
            for (int index = 0; index < uniqueCount; index++) {
                result.add(new Coordinate(coordinates[index]));
            }
        }
        return deduplicate(result);
    }

    /**
     * Keeps every vertex for a small hull. Larger hulls are replaced by a circumscribed regular
     * polygon built from support lines. Unlike sampling every Nth original vertex, its adjacent
     * edges stay outside the blocked convex geometry and therefore remain usable navigation edges.
     */
    private Coordinate[] navigationCoordinates(Geometry convexGeometry) {
        Coordinate[] coordinates = convexGeometry.getCoordinates();
        int uniqueCount = coordinates.length > 1 && coordinates[0].equals2D(coordinates[coordinates.length - 1])
                ? coordinates.length - 1
                : coordinates.length;
        if (uniqueCount <= MAX_VERTICES_PER_OBSTACLE) {
            return coordinates;
        }
        double[] normalX = new double[MAX_VERTICES_PER_OBSTACLE];
        double[] normalY = new double[MAX_VERTICES_PER_OBSTACLE];
        double[] support = new double[MAX_VERTICES_PER_OBSTACLE];
        java.util.Arrays.fill(support, Double.NEGATIVE_INFINITY);
        for (int index = 0; index < MAX_VERTICES_PER_OBSTACLE; index++) {
            double angle = 2.0 * Math.PI * index / MAX_VERTICES_PER_OBSTACLE;
            normalX[index] = Math.cos(angle);
            normalY[index] = Math.sin(angle);
            for (int coordinateIndex = 0; coordinateIndex < uniqueCount; coordinateIndex++) {
                Coordinate coordinate = coordinates[coordinateIndex];
                support[index] = Math.max(
                        support[index],
                        normalX[index] * coordinate.x + normalY[index] * coordinate.y);
            }
        }
        Coordinate[] result = new Coordinate[MAX_VERTICES_PER_OBSTACLE + 1];
        for (int index = 0; index < MAX_VERTICES_PER_OBSTACLE; index++) {
            int next = (index + 1) % MAX_VERTICES_PER_OBSTACLE;
            double determinant = normalX[index] * normalY[next] - normalY[index] * normalX[next];
            result[index] = new Coordinate(
                    (support[index] * normalY[next] - normalY[index] * support[next]) / determinant,
                    (normalX[index] * support[next] - support[index] * normalX[next]) / determinant);
        }
        result[MAX_VERTICES_PER_OBSTACLE] = new Coordinate(result[0]);
        return result;
    }

    private SearchResult shortestPath(
            List<Coordinate> nodes,
            ConstraintIndex constraints,
            RoutePreference preference,
            Coordinate start,
            Coordinate end) {
        int size = nodes.size();
        VisibilityCache visibility = new VisibilityCache(size);
        java.util.Map<Long, Double> distance = new java.util.HashMap<>();
        java.util.Map<Long, Long> predecessor = new java.util.HashMap<>();
        Set<Long> visited = new HashSet<>();
        long startState = stateKey(-1, 0);
        distance.put(startState, 0.0);
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator
                .comparingDouble((State state) -> state.priority)
                .thenComparingDouble(state -> state.cost)
                .thenComparingInt(state -> state.node)
                .thenComparingInt(state -> state.previous));
        queue.add(new State(-1, 0, 0.0, heuristic(nodes.get(0), end)));
        State targetState = null;
        while (!queue.isEmpty()) {
            ensureNotCancelled();
            State state = queue.poll();
            long currentState = stateKey(state.previous, state.node);
            if (!visited.add(currentState)) {
                continue;
            }
            if (state.node == 1) {
                targetState = state;
                break;
            }
            Coordinate current = nodes.get(state.node);
            for (int next = 0; next < size; next++) {
                if (next == state.node || next == state.previous
                        || !visibility.isVisible(state.node, next, nodes, constraints)) {
                    continue;
                }
                Coordinate target = nodes.get(next);
                double edgeCost = current.distance(target)
                        * preferenceFactor(current, target, start, end, preference)
                        * bendPenalty(nodes, state.previous, state.node, next);
                double candidate = state.cost + edgeCost;
                long nextState = stateKey(state.node, next);
                double currentBest = distance.getOrDefault(nextState, Double.POSITIVE_INFINITY);
                Long priorState = predecessor.get(nextState);
                if (candidate + 1e-9 < currentBest
                        || (Math.abs(candidate - currentBest) <= 1e-9
                                && (priorState == null || currentState < priorState))) {
                    distance.put(nextState, candidate);
                    predecessor.put(nextState, currentState);
                    queue.add(new State(
                            state.node,
                            next,
                            candidate,
                            candidate + heuristic(target, end)));
                }
            }
        }
        if (targetState == null) {
            return new SearchResult(Collections.emptyList(), visibility.evaluatedPairCount);
        }
        List<Coordinate> result = new ArrayList<>();
        long cursor = stateKey(targetState.previous, targetState.node);
        while (true) {
            int node = stateNode(cursor);
            result.add(new Coordinate(nodes.get(node)));
            if (cursor == startState) {
                break;
            }
            cursor = predecessor.get(cursor);
        }
        Collections.reverse(result);
        return new SearchResult(result, visibility.evaluatedPairCount);
    }

    private double heuristic(Coordinate coordinate, Coordinate end) {
        return MINIMUM_PREFERENCE_FACTOR * coordinate.distance(end);
    }

    private void ensureNotCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Route calculation was cancelled");
        }
    }

    private long stateKey(int previous, int node) {
        return ((long) (previous + 1) << 32) | (node & 0xffffffffL);
    }

    private int stateNode(long state) {
        return (int) state;
    }

    double bendPenalty(List<Coordinate> nodes, int previous, int current, int next) {
        // The amended specification permits every turn from 0 to 90 degrees and defines no
        // extra construction tariff for intermediate angles.
        return 1.0;
    }

    private double preferenceFactor(
            Coordinate edgeStart,
            Coordinate edgeEnd,
            Coordinate routeStart,
            Coordinate routeEnd,
            RoutePreference preference) {
        if (preference == RoutePreference.SHORTEST) {
            return 1.0;
        }
        double dx = routeEnd.x - routeStart.x;
        double dy = routeEnd.y - routeStart.y;
        double mx = (edgeStart.x + edgeEnd.x) / 2.0 - routeStart.x;
        double my = (edgeStart.y + edgeEnd.y) / 2.0 - routeStart.y;
        double cross = dx * my - dy * mx;
        boolean preferredSide = preference == RoutePreference.LEFT ? cross >= 0 : cross <= 0;
        return preferredSide ? 0.985 : 1.015;
    }

    private List<Coordinate> normalize(List<Coordinate> path, ConstraintIndex constraints) {
        List<Coordinate> normalized = new ArrayList<>();
        int current = 0;
        normalized.add(new Coordinate(path.get(0)));
        while (current < path.size() - 1) {
            int next = path.size() - 1;
            while (next > current + 1
                    && !rules.segmentAllowed(path.get(current), path.get(next), constraints)) {
                next--;
            }
            normalized.add(new Coordinate(path.get(next)));
            current = next;
        }
        return normalized;
    }

    private List<Coordinate> deduplicate(List<Coordinate> coordinates) {
        List<Coordinate> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Coordinate coordinate : coordinates) {
            String key = Math.round(coordinate.x * 1000.0) + ":" + Math.round(coordinate.y * 1000.0);
            if (keys.add(key)) {
                result.add(coordinate);
            }
        }
        return result;
    }

    private static final class State {
        private final int previous;
        private final int node;
        private final double cost;
        private final double priority;

        private State(int previous, int node, double cost, double priority) {
            this.previous = previous;
            this.node = node;
            this.cost = cost;
            this.priority = priority;
        }
    }

    private static final class SearchResult {
        private final List<Coordinate> coordinates;
        private final long evaluatedPairCount;

        private SearchResult(List<Coordinate> coordinates, long evaluatedPairCount) {
            this.coordinates = coordinates;
            this.evaluatedPairCount = evaluatedPairCount;
        }
    }

    private final class VisibilityCache {
        private final int nodeCount;
        private final byte[] values;
        private long evaluatedPairCount;

        private VisibilityCache(int nodeCount) {
            this.nodeCount = nodeCount;
            this.values = new byte[nodeCount * (nodeCount - 1) / 2];
        }

        private boolean isVisible(
                int first,
                int second,
                List<Coordinate> nodes,
                ConstraintIndex constraints) {
            int left = Math.min(first, second);
            int right = Math.max(first, second);
            int index = left * (2 * nodeCount - left - 1) / 2 + right - left - 1;
            byte cached = values[index];
            if (cached == 0) {
                ensureNotCancelled();
                cached = rules.segmentAllowed(nodes.get(left), nodes.get(right), constraints)
                        ? (byte) 1
                        : (byte) 2;
                values[index] = cached;
                evaluatedPairCount++;
            }
            return cached == 1;
        }
    }
}
