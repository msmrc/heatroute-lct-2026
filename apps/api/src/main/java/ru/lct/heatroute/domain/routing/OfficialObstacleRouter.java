package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialObstacleRouter {
    private static final double NAVIGATION_MARGIN_M = 0.25;
    private static final int MAX_VERTICES_PER_OBSTACLE = 32;
    private static final double[] CORRIDOR_EXPANSIONS = {75.0, 200.0, 600.0};

    private final OfficialRouteGeometryRules rules;

    public OfficialObstacleRouter(OfficialRouteGeometryRules rules) {
        this.rules = rules;
    }

    OfficialRoutingEnvironment prepare(List<ImportedOfficialFeature> features) {
        return new OfficialRoutingEnvironment(features, rules);
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

    RoutePath find(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes) {
        List<Constraint> constraints = new ArrayList<>(environment.constraints(
                diameter, exemptFeatureIds, start, end));
        constraints.addAll(rules.applicableConstraints(
                rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(),
                start,
                end));
        ConstraintIndex constraintIndex = rules.index(constraints);
        if (rules.segmentAllowed(start, end, constraintIndex)) {
            return path(List.of(start, end), constraints);
        }
        for (double expansion : CORRIDOR_EXPANSIONS) {
            List<Coordinate> nodes = navigationNodes(start, end, constraintIndex, expansion);
            List<Coordinate> candidate = shortestPath(nodes, constraintIndex, preference, start, end);
            if (!candidate.isEmpty()) {
                List<Coordinate> normalized = normalize(candidate, constraintIndex);
                LineString line = rules.line(normalized);
                if (rules.lineAllowed(line, constraintIndex)) {
                    return path(normalized, constraints);
                }
            }
        }
        return null;
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
        for (Constraint constraint : constraints.query(corridor)) {
            if (!constraint.rule().isForbidden()
                    || !constraint.blocked().getEnvelopeInternal().intersects(corridor)) {
                continue;
            }
            Geometry navigationGeometry = DouglasPeuckerSimplifier.simplify(
                    constraint.blocked().buffer(NAVIGATION_MARGIN_M, 2).convexHull(),
                    0.10);
            Coordinate[] coordinates = navigationGeometry.getCoordinates();
            int uniqueCount = coordinates.length > 1 && coordinates[0].equals2D(coordinates[coordinates.length - 1])
                    ? coordinates.length - 1
                    : coordinates.length;
            int step = Math.max(1, (int) Math.ceil(uniqueCount / (double) MAX_VERTICES_PER_OBSTACLE));
            for (int index = 0; index < uniqueCount; index += step) {
                result.add(new Coordinate(coordinates[index]));
            }
        }
        return deduplicate(result);
    }

    private List<Coordinate> shortestPath(
            List<Coordinate> nodes,
            ConstraintIndex constraints,
            RoutePreference preference,
            Coordinate start,
            Coordinate end) {
        int size = nodes.size();
        double[] distance = new double[size];
        int[] previous = new int[size];
        boolean[] visited = new boolean[size];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);
        distance[0] = 0.0;
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator
                .comparingDouble((State state) -> state.cost)
                .thenComparingInt(state -> state.node));
        queue.add(new State(0, 0.0));
        while (!queue.isEmpty()) {
            State state = queue.poll();
            if (visited[state.node]) {
                continue;
            }
            visited[state.node] = true;
            if (state.node == 1) {
                break;
            }
            Coordinate current = nodes.get(state.node);
            for (int next = 0; next < size; next++) {
                if (next == state.node || visited[next]) {
                    continue;
                }
                Coordinate target = nodes.get(next);
                if (!rules.segmentAllowed(current, target, constraints)) {
                    continue;
                }
                double edgeCost = current.distance(target) * preferenceFactor(current, target, start, end, preference);
                double candidate = distance[state.node] + edgeCost;
                if (candidate + 1e-9 < distance[next]
                        || (Math.abs(candidate - distance[next]) <= 1e-9 && state.node < previous[next])) {
                    distance[next] = candidate;
                    previous[next] = state.node;
                    queue.add(new State(next, candidate));
                }
            }
        }
        if (!Double.isFinite(distance[1])) {
            return Collections.emptyList();
        }
        List<Coordinate> result = new ArrayList<>();
        int cursor = 1;
        while (cursor >= 0) {
            result.add(new Coordinate(nodes.get(cursor)));
            cursor = previous[cursor];
        }
        Collections.reverse(result);
        return result;
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
        private final int node;
        private final double cost;

        private State(int node, double cost) {
            this.node = node;
            this.cost = cost;
        }
    }
}
