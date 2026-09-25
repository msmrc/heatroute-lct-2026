package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.NormalEgress;

/**
 * Сохраняет перепроверенный нормальный ввод и меняет лишь хвост у объединяемой камеры.
 * Не запускает глобальный поиск: два места среза, до 38 хвостов, не более восьми итоговых путей.
 * Окончательный допуск сети, ДУ и глубины остаётся у planner.finish.
 */
final class CorridorRetainedTerminalApproaches {
    private static final int MAX_CHOICES = 8;
    private static final int MAX_SOURCE_POINTS = 512;
    private static final int MAX_EGRESSES = 16;
    // Две независимо округлённые до миллиметра геометрии, не разрешение нового косого ввода.
    private static final double PREFIX_ROUNDING_M = 0.002;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private CorridorRetainedTerminalApproaches() { }

    /** fresh — уже проверенные варианты CorridorTerminalRouter; фильтруем пересечения до лимита. */
    static List<RoutePath> build(RouteEdge edge, RouteNode terminal, Coordinate junction, double orientation,
            OfficialObstacleRouter router, OfficialRoutingEnvironment environment,
            List<RoutePath> fresh, List<RouteEdge> retained) {
        ensureActive();
        Objects.requireNonNull(edge); Objects.requireNonNull(terminal);
        Objects.requireNonNull(router); Objects.requireNonNull(environment);
        Objects.requireNonNull(fresh); Objects.requireNonNull(retained);
        if (!finite(junction) || !Double.isFinite(orientation) || edge.getDiameter() == null
                || edge.getDiameter() <= 0 || fresh.size() > MAX_CHOICES
                || !(terminal.getId().equals(edge.getUpstreamNodeId())
                        || terminal.getId().equals(edge.getDownstreamNodeId()))) {
            throw new IllegalArgumentException("Terminal incidence, diameter and bounded finite approaches required");
        }
        List<RoutePath> current = new ArrayList<>();
        for (RoutePath path : fresh) {
            ensureActive();
            if (CorridorJunctionAssignment.clearsRetained(path, terminal.getId(), retained)) addDistinct(current, path);
        }
        List<RoutePath> preserved = retainedPaths(edge, terminal, rounded(junction), orientation, router, environment, retained);
        List<RoutePath> candidates = new ArrayList<>(current);
        preserved.forEach(path -> addDistinct(candidates, path));
        candidates.sort(Comparator.comparingDouble(RoutePath::lengthM).thenComparing(CorridorRetainedTerminalApproaches::key));
        List<RoutePath> selected = new ArrayList<>();
        Coordinate oldEnd = edge.getCoordinates().isEmpty() ? null : edge.getCoordinates().get(
                terminal.getId().equals(edge.getUpstreamNodeId()) ? edge.getCoordinates().size() - 1 : 0).toCoordinate();
        if (oldEnd != null && oldEnd.equals2D(rounded(junction)) && !preserved.isEmpty()) selected.add(preserved.get(0));
        if (!current.isEmpty()) addDistinct(selected, current.get(0));
        // Сначала разные конечные лучи, затем остальные геометрии. Сохранённый путь не вытесняется.
        for (RoutePath path : candidates) {
            if (selected.size() == MAX_CHOICES) break;
            if (selected.stream().noneMatch(other -> ray(other, orientation) == ray(path, orientation))) addDistinct(selected, path);
        }
        for (RoutePath path : candidates) {
            if (selected.size() == MAX_CHOICES) break;
            addDistinct(selected, path);
        }
        ensureActive();
        return List.copyOf(selected);
    }

    private static List<RoutePath> retainedPaths(RouteEdge edge, RouteNode terminal, Coordinate junction,
            double orientation, OfficialObstacleRouter router, OfficialRoutingEnvironment environment, List<RouteEdge> retained) {
        if (edge.getCoordinates().size() < 2 || edge.getCoordinates().size() > MAX_SOURCE_POINTS) return List.of();
        List<Coordinate> source = edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
        RouteTraversal traversal = terminal.getId().equals(edge.getUpstreamNodeId())
                ? RouteTraversal.AS_GIVEN : RouteTraversal.REVERSED;
        if (traversal == RouteTraversal.REVERSED) Collections.reverse(source);
        if (source.stream().anyMatch(point -> !finite(point))
                || source.get(0).distance(terminal.getCoordinate().toCoordinate()) > 0.001) return List.of();
        List<NormalEgress> egresses = environment.normalEgressCandidates(edge.getDiameter(), source.get(0), source.get(1),
                RoutePlannerTuning.stable().getEngineeringEgressExtraM(), traversal);
        NormalEgress egress = null;
        List<Coordinate> outside = source;
        if (!egresses.isEmpty()) {
            outside = null;
            for (int i = 0; i < Math.min(MAX_EGRESSES, egresses.size()); i++) {
                ensureActive();
                List<Coordinate> candidate = afterNormalPrefix(source, egresses.get(i));
                if (candidate != null) { egress = egresses.get(i); outside = candidate; break; }
            }
            if (outside == null) return List.of();
        }
        List<List<Coordinate>> tails = new ArrayList<>();
        if (source.get(source.size() - 1).equals2D(junction)) {
            tails.add(outside);
        } else {
            // Не режем произвольный дальний префикс: только старый конец и предыдущую вершину.
            for (int cut = outside.size() - 1; cut >= Math.max(0, outside.size() - 2); cut--) {
                ensureActive();
                List<Coordinate> prefix = outside.subList(0, cut + 1);
                addTail(tails, prefix, List.of(junction));
                List<Coordinate> approaches = new ArrayList<>(List.of(junction));
                for (double length : new double[] {2.1, 5.0}) {
                    for (int direction = 0; direction < 4; direction++) {
                        double angle = orientation + direction * Math.PI / 2;
                        approaches.add(new Coordinate(junction.x + length * Math.cos(angle), junction.y + length * Math.sin(angle)));
                    }
                }
                Coordinate anchor = prefix.get(prefix.size() - 1);
                for (Coordinate approach : approaches) {
                    for (int axis = 0; axis < 2; axis++) {
                        double angle = orientation + axis * Math.PI / 2;
                        double nx = Math.cos(angle), ny = Math.sin(angle);
                        double projection = (approach.x - anchor.x) * nx + (approach.y - anchor.y) * ny;
                        Coordinate elbow = new Coordinate(anchor.x + nx * projection, anchor.y + ny * projection);
                        addTail(tails, prefix, List.of(elbow, approach, junction));
                    }
                }
            }
        }
        Envelope bounds = new Envelope();
        tails.forEach(points -> points.forEach(bounds::expandToInclude));
        if (egress != null) bounds.expandToInclude(egress.start());
        PreparedCorridor checks = router.prepareCorridor(edge.getDiameter(), environment, bounds, outside.get(0), null, traversal);
        List<RoutePath> paths = new ArrayList<>();
        Set<List<Coordinate>> checkedTails = new HashSet<>();
        for (List<Coordinate> points : tails) {
            ensureActive();
            // Без нормального ввода нет корневого исключения из отступа ОКС.
            if (egress == null && points.stream().anyMatch(point -> !checks.pointAllowed(point))) continue;
            // path() проверяет миллиметровые координаты. Первый такой хвост сохраняет порядок;
            // сырые точки выше проверяем ДО ключа: их допуск может отличаться внутри одного мм.
            if (!checkedTails.add(roundedTailKey(points))) continue;
            RoutePath path = egress == null ? checks.path(points) : checks.pathAfter(egress.start(), points);
            if (path == null) continue;
            if (egress == null && path.coordinates().stream().anyMatch(point -> !checks.pointAllowed(point))) continue;
            if (egress != null) path = router.withCheckedTerminalPrefix(egress, path, edge.getDiameter(), environment, traversal);
            if (sound(path, edge.getDiameter()) && CorridorJunctionAssignment.clearsRetained(path, terminal.getId(), retained)) {
                addDistinct(paths, path);
            }
        }
        return paths;
    }

    private static List<Coordinate> roundedTailKey(List<Coordinate> points) {
        return points.stream().map(CorridorRetainedTerminalApproaches::rounded).collect(Collectors.toList());
    }

    /** Находит нормальный выход внутри первого прямого хода, даже если он не записан отдельной вершиной. */
    private static List<Coordinate> afterNormalPrefix(List<Coordinate> source, NormalEgress egress) {
        LineSegment normal = new LineSegment(egress.start(), egress.exit());
        double previous = 0;
        for (int i = 1; i < source.size(); i++) {
            Coordinate next = source.get(i);
            double projection = normal.projectionFactor(next);
            if (projection < previous || normal.distancePerpendicular(next) > PREFIX_ROUNDING_M) return null;
            if (projection >= 1 || next.distance(egress.exit()) <= PREFIX_ROUNDING_M) {
                List<Coordinate> outside = new ArrayList<>(List.of(egress.exit()));
                for (int j = i; j < source.size(); j++) {
                    if (j == i && next.distance(egress.exit()) <= PREFIX_ROUNDING_M) continue;
                    append(outside, source.get(j));
                }
                return outside.size() >= 2 ? outside : null;
            }
            previous = projection;
        }
        return null;
    }

    private static void addTail(List<List<Coordinate>> tails, List<Coordinate> prefix, List<Coordinate> suffix) {
        List<Coordinate> points = new ArrayList<>(prefix);
        suffix.forEach(point -> append(points, point));
        if (points.size() >= 2) tails.add(points);
    }

    private static void append(List<Coordinate> points, Coordinate point) {
        if (points.isEmpty() || !rounded(points.get(points.size() - 1)).equals2D(rounded(point))) points.add(point);
    }

    private static boolean sound(RoutePath path, int diameter) {
        ensureActive();
        if (path == null || path.coordinates().size() < 2 || !Double.isFinite(path.lengthM())) return false;
        LineString line = GEOMETRY.createLineString(path.coordinates().toArray(new Coordinate[0]));
        if (!line.isSimple() || line.isClosed()) return false;
        List<RouteCoordinate> points = path.coordinates().stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList());
        if (!OfficialRouteDeflectionRules.validatePolyline("retained-terminal", points).getIssues().isEmpty()) return false;
        RouteEdge edge = new RouteEdge("retained-terminal", "terminal", "chamber", path.lengthM(), points, path.sections(), null, diameter);
        return new EngineeringRouteEvaluator().evaluate(List.of(edge)).isCompliant();
    }

    private static int ray(RoutePath path, double orientation) {
        List<Coordinate> points = path.coordinates();
        Coordinate previous = points.get(points.size() - 2), end = points.get(points.size() - 1);
        double angle = Math.atan2(end.y - previous.y, end.x - previous.x) - orientation;
        if (Math.abs(Math.sin(2 * angle)) > Math.sin(Math.toRadians(1))) return 4;
        return Math.floorMod((int) Math.round(Math.IEEEremainder(angle, 2 * Math.PI) / (Math.PI / 2)), 4);
    }

    private static void addDistinct(List<RoutePath> paths, RoutePath candidate) {
        if (paths.stream().noneMatch(path -> path.coordinates().equals(candidate.coordinates()))) paths.add(candidate);
    }

    private static String key(RoutePath path) { return path.coordinates().toString(); }
    private static Coordinate rounded(Coordinate point) { return new RouteCoordinate(point.x, point.y).toCoordinate(); }
    private static boolean finite(Coordinate point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y)
                && Math.ulp(point.x) <= 0.001 && Math.ulp(point.y) <= 0.001;
    }
    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Retained terminal approach cancelled");
    }
}
