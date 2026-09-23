package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialObstacleRouter {
    private static final double NAVIGATION_MARGIN_M = 0.25;
    private static final double SPECIAL_CROSSING_PORTAL_MARGIN_M = 0.25;
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
        String key = routeCacheKey(
                start,
                end,
                diameter,
                exemptFeatureIds,
                preference,
                acceptedRoutes,
                additionalConstraints);
        return environment.cachedRoute(key, () -> findUncached(
                start, end, diameter, environment, exemptFeatureIds, preference,
                acceptedRoutes, additionalConstraints));
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
        if (preference == RoutePreference.ENGINEERING) {
            RoutePath dogleg = directEngineeringDogleg(start, end, constraintIndex, constraints);
            if (dogleg != null) {
                return dogleg;
            }
        }
        for (double expansion : CORRIDOR_EXPANSIONS) {
            List<Coordinate> nodes = navigationNodes(start, end, constraintIndex, expansion);
            SearchResult search = shortestPath(nodes, constraintIndex, preference, start, end);
            environment.recordVisibilitySearch(nodes.size(), expansion, search.evaluatedPairCount);
            if (!search.coordinates.isEmpty()) {
                List<Coordinate> normalized = normalize(search.coordinates, constraintIndex);
                List<Coordinate> constructible = snapConstructibleCorners(
                        normalized, constraintIndex, preference);
                LineString line = rules.line(constructible);
                if (rules.lineAllowed(line, constraintIndex)) {
                    return path(constructible, constraints);
                }
            }
        }
        return null;
    }

    /**
     * Tries a single constructible elbow before entering the visibility graph. A legal 90..135
     * degree dogleg is preferable to an obstacle-hugging path that overshoots the destination and
     * returns through a near-zero-degree hairpin.
     */
    private RoutePath directEngineeringDogleg(
            Coordinate start,
            Coordinate end,
            ConstraintIndex constraints,
            List<Constraint> sourceConstraints) {
        double directLength = start.distance(end);
        Coordinate best = null;
        double bestLength = Double.POSITIVE_INFINITY;
        for (Coordinate candidate : constructibleCornerCandidates(
                start, end, RoutePreference.ENGINEERING)) {
            if (candidate.distance(start) <= OfficialRouteGeometryRules.EPSILON_M
                    || candidate.distance(end) <= OfficialRouteGeometryRules.EPSILON_M
                    || !rules.segmentAllowed(start, candidate, constraints)
                    || !rules.segmentAllowed(candidate, end, constraints)) {
                continue;
            }
            double internalAngle = internalAngleDegrees(start, candidate, end);
            double candidateLength = start.distance(candidate) + candidate.distance(end);
            if (internalAngle + 0.5 < EngineeringRouteEvaluator.MIN_INTERNAL_ANGLE_DEGREES
                    || internalAngle > EngineeringRouteEvaluator.MAX_INTERNAL_ANGLE_DEGREES + 0.5
                    || candidateLength > directLength * 1.42
                    || candidateLength >= bestLength) {
                continue;
            }
            best = candidate;
            bestLength = candidateLength;
        }
        return best == null ? null : path(List.of(start, best, end), sourceConstraints);
    }

    private double internalAngleDegrees(
            Coordinate before,
            Coordinate at,
            Coordinate after) {
        double ax = before.x - at.x;
        double ay = before.y - at.y;
        double bx = after.x - at.x;
        double by = after.y - at.y;
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator <= 1e-9) {
            return 180.0;
        }
        double cosine = Math.max(-1.0, Math.min(1.0, (ax * bx + ay * by) / denominator));
        return Math.toDegrees(Math.acos(cosine));
    }

    private String routeCacheKey(
            Coordinate start,
            Coordinate end,
            int diameter,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes,
            List<Constraint> additionalConstraints) {
        String exemptions = exemptFeatureIds.stream().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return Math.round(start.x * 1000.0) + ":" + Math.round(start.y * 1000.0)
                + ">" + Math.round(end.x * 1000.0) + ":" + Math.round(end.y * 1000.0)
                + "|" + diameter + "|" + preference + "|" + exemptions
                + "|routes=" + geometrySetSignature(acceptedRoutes)
                + "|constraints=" + constraintSetSignature(additionalConstraints);
    }

    /**
     * Includes dynamic tree geometry in the cache identity. Repeated portfolio and engineering
     * probes often ask the same constrained routing question; caching it is safe only when the
     * complete avoidance context participates in the key.
     */
    private String geometrySetSignature(List<? extends Geometry> geometries) {
        return geometries.stream()
                .map(this::geometrySignature)
                .sorted()
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private String constraintSetSignature(List<Constraint> constraints) {
        return constraints.stream()
                .map(constraint -> constraint.id() + ":" + constraint.type()
                        + ":" + geometrySignature(constraint.source())
                        + ":" + geometrySignature(constraint.blocked()))
                .sorted()
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private String geometrySignature(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return "-";
        }
        StringBuilder result = new StringBuilder();
        for (Coordinate coordinate : geometry.getCoordinates()) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(Math.round(coordinate.x * 1000.0))
                    .append(':')
                    .append(Math.round(coordinate.y * 1000.0));
        }
        return result.toString();
    }

    RoutePath regularize(
            List<Coordinate> coordinates,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes) {
        if (coordinates.size() < 2) {
            return null;
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
        ConstraintIndex constraintIndex = rules.index(constraints);
        List<Coordinate> normalized = normalize(coordinates, constraintIndex);
        List<Coordinate> constructible = snapConstructibleCorners(
                normalized, constraintIndex, RoutePreference.ENGINEERING);
        LineString line = rules.line(constructible);
        return rules.lineAllowed(line, constraintIndex) ? path(constructible, constraints) : null;
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
            if (!constraint.rule().isForbidden()) {
                addSpecialCrossingPortals(result, constraint, directLine, corridor);
                continue;
            }
            if (!constraint.blocked().getEnvelopeInternal().intersects(corridor)
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
     * Adds a bounded perpendicular crossing through a polygonal road or tram restriction. Without
     * these two nodes a shallow direct crossing is rejected, but the visibility graph has no legal
     * place from which to enter and leave the carriageway at the required angle.
     */
    private void addSpecialCrossingPortals(
            List<Coordinate> result,
            Constraint constraint,
            LineString directLine,
            Envelope corridor) {
        if (constraint.rule().getMinimumCrossingAngleDegrees() == null
                || constraint.source().getDimension() != 2
                || !constraint.source().getEnvelopeInternal().intersects(corridor)
                || !constraint.source().intersects(directLine)) {
            return;
        }
        Geometry rectangle = new MinimumDiameter(constraint.source()).getMinimumRectangle();
        Coordinate[] rectangleCoordinates = rectangle.getCoordinates();
        LineSegment axis = null;
        for (int index = 0; index + 1 < rectangleCoordinates.length; index++) {
            LineSegment candidate = new LineSegment(rectangleCoordinates[index], rectangleCoordinates[index + 1]);
            if (axis == null || candidate.getLength() > axis.getLength()) {
                axis = candidate;
            }
        }
        if (axis == null || axis.getLength() <= OfficialRouteGeometryRules.EPSILON_M) {
            return;
        }
        Geometry intersection = constraint.source().intersection(directLine);
        Coordinate anchor = intersection.isEmpty()
                ? constraint.source().getCentroid().getCoordinate()
                : intersection.getCentroid().getCoordinate();
        double normalX = -(axis.p1.y - axis.p0.y) / axis.getLength();
        double normalY = (axis.p1.x - axis.p0.x) / axis.getLength();
        double anchorProjection = anchor.x * normalX + anchor.y * normalY;
        double minimumProjection = Double.POSITIVE_INFINITY;
        double maximumProjection = Double.NEGATIVE_INFINITY;
        for (Coordinate coordinate : constraint.source().getCoordinates()) {
            double projection = coordinate.x * normalX + coordinate.y * normalY;
            minimumProjection = Math.min(minimumProjection, projection);
            maximumProjection = Math.max(maximumProjection, projection);
        }
        double before = minimumProjection - anchorProjection - SPECIAL_CROSSING_PORTAL_MARGIN_M;
        double after = maximumProjection - anchorProjection + SPECIAL_CROSSING_PORTAL_MARGIN_M;
        result.add(new Coordinate(anchor.x + normalX * before, anchor.y + normalY * before));
        result.add(new Coordinate(anchor.x + normalX * after, anchor.y + normalY * after));
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
                        * bendPenalty(nodes, state.previous, state.node, next, preference);
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
        return bendPenalty(nodes, previous, current, next, RoutePreference.SHORTEST);
    }

    private double bendPenalty(
            List<Coordinate> nodes,
            int previous,
            int current,
            int next,
            RoutePreference preference) {
        if (previous < 0) {
            return 1.0;
        }
        Coordinate before = nodes.get(previous);
        Coordinate at = nodes.get(current);
        Coordinate after = nodes.get(next);
        double ax = before.x - at.x;
        double ay = before.y - at.y;
        double bx = after.x - at.x;
        double by = after.y - at.y;
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator <= 1e-9) {
            return 1.0;
        }
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
                (ax * bx + ay * by) / denominator))));
        double deflection = Math.abs(180.0 - angle);
        if (deflection <= 1.0) {
            return 1.0;
        }
        if (preference == RoutePreference.ENGINEERING) {
            // Expert constructability rule: an internal bend angle must be 90..135 degrees,
            // equivalently a 45..90 degree deflection. This remains a search preference here;
            // the portfolio evaluator applies it as a hard admission rule to two variants.
            if (angle >= EngineeringRouteEvaluator.MIN_INTERNAL_ANGLE_DEGREES
                    && angle <= EngineeringRouteEvaluator.MAX_INTERNAL_ANGLE_DEGREES) {
                return 1.001;
            }
            double rangeDeviation = angle < EngineeringRouteEvaluator.MIN_INTERNAL_ANGLE_DEGREES
                    ? EngineeringRouteEvaluator.MIN_INTERNAL_ANGLE_DEGREES - angle
                    : angle - EngineeringRouteEvaluator.MAX_INTERNAL_ANGLE_DEGREES;
            return 1.15 + Math.min(0.35, rangeDeviation / 90.0 * 0.35);
        }
        double deviation = Math.min(
                Math.abs(deflection - 45.0),
                Math.min(Math.abs(deflection), Math.abs(deflection - 90.0)));
        // This is a bounded route-search preference, not a construction tariff. Straight, 45° and
        // 90° turns are construction-friendly; another angle must be materially shorter to win.
        double normalizedDeviation = Math.min(1.0, deviation / 22.5);
        return 1.003 + 0.037 * normalizedDeviation * normalizedDeviation;
    }

    private double preferenceFactor(
            Coordinate edgeStart,
            Coordinate edgeEnd,
            Coordinate routeStart,
            Coordinate routeEnd,
            RoutePreference preference) {
        if (preference == RoutePreference.SHORTEST || preference == RoutePreference.ENGINEERING) {
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

    /**
     * Replaces an arbitrary visibility-graph corner with an equivalent orthogonal or 45-degree
     * elbow when both new segments are legal and the local length grows by no more than five
     * percent. This is intentionally conservative: obstacle-hugging vertices remain unchanged
     * whenever a constructible elbow would enter a clearance zone.
     */
    private List<Coordinate> snapConstructibleCorners(
            List<Coordinate> coordinates,
            ConstraintIndex constraints,
            RoutePreference preference) {
        if (coordinates.size() < 3) {
            return coordinates;
        }
        List<Coordinate> result = coordinates.stream()
                .map(Coordinate::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (int index = 1; index + 1 < result.size(); index++) {
            Coordinate before = result.get(index - 1);
            Coordinate current = result.get(index);
            Coordinate after = result.get(index + 1);
            double currentLength = before.distance(current) + current.distance(after);
            double bestPenalty = constructibleAngleDeviation(before, current, after);
            Coordinate best = current;
            for (Coordinate candidate : constructibleCornerCandidates(before, after, preference)) {
                if (candidate.distance(before) <= OfficialRouteGeometryRules.EPSILON_M
                        || candidate.distance(after) <= OfficialRouteGeometryRules.EPSILON_M) {
                    continue;
                }
                double candidateLength = before.distance(candidate) + candidate.distance(after);
                double maximumLengthFactor = preference == RoutePreference.ENGINEERING ? 1.42 : 1.05;
                if (candidateLength > currentLength * maximumLengthFactor
                        || !rules.segmentAllowed(before, candidate, constraints)
                        || !rules.segmentAllowed(candidate, after, constraints)) {
                    continue;
                }
                double candidatePenalty = constructibleAngleDeviation(before, candidate, after);
                if (candidatePenalty + 1e-9 < bestPenalty
                        || (Math.abs(candidatePenalty - bestPenalty) <= 1e-9
                                && candidateLength + 1e-9
                                        < before.distance(best) + best.distance(after))) {
                    best = candidate;
                    bestPenalty = candidatePenalty;
                }
            }
            result.set(index, new Coordinate(best));
        }
        return deduplicate(result);
    }

    private List<Coordinate> constructibleCornerCandidates(
            Coordinate start,
            Coordinate end,
            RoutePreference preference) {
        List<Coordinate> result = new ArrayList<>();
        result.add(new Coordinate(start.x, end.y));
        result.add(new Coordinate(end.x, start.y));
        // These orientation-independent elbows are deliberately restricted to the final expert
        // pass. Adding them to every visibility search multiplies an already dominant hot path.
        if (preference == RoutePreference.ENGINEERING) {
            addExactInternalAngleCandidates(result, start, end, 90.0);
            addExactInternalAngleCandidates(result, start, end, 105.0);
            addExactInternalAngleCandidates(result, start, end, 120.0);
            addExactInternalAngleCandidates(result, start, end, 135.0);
        }
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double signX = Math.copySign(1.0, dx == 0.0 ? 1.0 : dx);
        double signY = Math.copySign(1.0, dy == 0.0 ? 1.0 : dy);
        double absX = Math.abs(dx);
        double absY = Math.abs(dy);
        if (absX >= absY) {
            result.add(new Coordinate(start.x + signX * absY, end.y));
            result.add(new Coordinate(end.x - signX * absY, start.y));
        }
        if (absY >= absX) {
            result.add(new Coordinate(end.x, start.y + signY * absX));
            result.add(new Coordinate(start.x, end.y - signY * absX));
        }
        return deduplicate(result);
    }

    /** Adds both isosceles elbows for the requested internal angle around the endpoint chord. */
    private void addExactInternalAngleCandidates(
            List<Coordinate> result,
            Coordinate start,
            Coordinate end,
            double internalAngleDegrees) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double chord = Math.hypot(dx, dy);
        if (chord <= OfficialRouteGeometryRules.EPSILON_M) {
            return;
        }
        double offset = chord / (2.0 * Math.tan(Math.toRadians(internalAngleDegrees / 2.0)));
        double middleX = (start.x + end.x) / 2.0;
        double middleY = (start.y + end.y) / 2.0;
        double normalX = -dy / chord;
        double normalY = dx / chord;
        result.add(new Coordinate(middleX + normalX * offset, middleY + normalY * offset));
        result.add(new Coordinate(middleX - normalX * offset, middleY - normalY * offset));
    }

    private double constructibleAngleDeviation(
            Coordinate before,
            Coordinate at,
            Coordinate after) {
        double ax = before.x - at.x;
        double ay = before.y - at.y;
        double bx = after.x - at.x;
        double by = after.y - at.y;
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator <= 1e-9) {
            return 0.0;
        }
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
                (ax * bx + ay * by) / denominator))));
        double deflection = Math.abs(180.0 - angle);
        return Math.min(
                Math.abs(deflection - 45.0),
                Math.min(Math.abs(deflection), Math.abs(deflection - 90.0)));
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
