package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * Перестраивает конец коридорной трубы с осевым подходом к перенесённой камере.
 * Сохраняет проверяемый префикс существующей полилинии; все новые варианты проверяются целиком.
 */
final class CorridorLinkApproaches {
    private CorridorLinkApproaches() { }

    static List<RoutePath> build(RouteEdge edge, RouteNode outer, Coordinate junction, double angle,
            OfficialObstacleRouter router, OfficialRoutingEnvironment environment) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor approach cancelled");
        if (edge == null || outer == null || router == null || environment == null || edge.getDiameter() == null
                || !Double.isFinite(angle) || junction == null || !Double.isFinite(junction.x) || !Double.isFinite(junction.y)) {
            throw new IllegalArgumentException("Finite orientation and junction required");
        }
        if (!edge.getUpstreamNodeId().equals(outer.getId()) && !edge.getDownstreamNodeId().equals(outer.getId())) {
            throw new IllegalArgumentException("Outer node must be an edge endpoint");
        }
        List<Coordinate> source = edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                .collect(Collectors.toCollection(ArrayList::new));
        RouteTraversal traversal = edge.getUpstreamNodeId().equals(outer.getId())
                ? RouteTraversal.AS_GIVEN : RouteTraversal.REVERSED;
        if (traversal == RouteTraversal.REVERSED) Collections.reverse(source);
        if (source.size() < 2 || source.size() > 1000) return List.of();
        if (source.get(0).distance(outer.getCoordinate().toCoordinate()) > 0.01) {
            throw new IllegalArgumentException("Edge geometry misses its outer node");
        }
        double nx = Math.cos(angle), ny = Math.sin(angle);
        List<RoutePath> paths = new ArrayList<>();
        List<Integer> cuts = new ArrayList<>(List.of(source.size() - 2));
        if (source.size() > 2) cuts.add(0);
        for (int cut : cuts) {
            Coordinate start = source.get(cut);
            List<Coordinate> approaches = new ArrayList<>(List.of(junction));
            for (double length : new double[] {2.1, 5.0}) {
                for (int direction = 0; direction < 4; direction++) {
                    double bearing = angle + direction * Math.PI / 2;
                    approaches.add(new Coordinate(junction.x + length * Math.cos(bearing), junction.y + length * Math.sin(bearing)));
                }
            }
            for (Coordinate approach : approaches) {
                for (boolean primaryFirst : new boolean[] {true, false}) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor approach cancelled");
                    double x = primaryFirst ? nx : -ny, y = primaryFirst ? ny : nx;
                    double projection = (approach.x - start.x) * x + (approach.y - start.y) * y;
                    Coordinate elbow = new Coordinate(start.x + x * projection, start.y + y * projection);
                    List<Coordinate> coordinates = new ArrayList<>(source.subList(0, cut + 1));
                    append(coordinates, elbow); append(coordinates, approach); append(coordinates, junction);
                    if (coordinates.size() < 2) continue;
                    Envelope bounds = new Envelope();
                    coordinates.forEach(bounds::expandToInclude);
                    PreparedCorridor checks = router.prepareCorridor(edge.getDiameter(), environment, bounds,
                            outer.getCoordinate().toCoordinate(), outer.isRoot() ? outer.getTargetId() : null, traversal);
                    // Поиск всегда outer→камера, но вход в дорогу и special проверяем по потоку.
                    // Исключение выхода из setback предназначено существующему корню, не новой камере.
                    if (!checks.pointAllowed(junction)
                            || !outer.isRoot() && !checks.pointAllowed(outer.getCoordinate().toCoordinate())) continue;
                    RoutePath path = checks.path(coordinates);
                    if (path == null || !engineeringCompliant(path)) continue;
                    if (paths.stream().noneMatch(previous -> same(previous, path))) paths.add(path);
                }
            }
        }
        paths.sort(Comparator.comparingDouble(RoutePath::lengthM));
        List<RoutePath> result = new ArrayList<>();
        // Сначала лучший путь каждого конечного луча, затем дополнительные варианты обхода.
        for (RoutePath path : paths) {
            if (result.stream().noneMatch(previous -> sameRay(previous, path))) result.add(path);
            if (result.size() == 8) return List.copyOf(result);
        }
        for (RoutePath path : paths) {
            if (!result.contains(path)) result.add(path);
            if (result.size() == 8) break;
        }
        return List.copyOf(result);
    }

    private static void append(List<Coordinate> coordinates, Coordinate point) {
        if (coordinates.get(coordinates.size() - 1).distance(point) > 0.001) coordinates.add(point);
    }

    private static boolean engineeringCompliant(RoutePath path) {
        LineString line = new GeometryFactory().createLineString(path.coordinates().toArray(new Coordinate[0]));
        if (!line.isSimple() || line.isClosed()) return false;
        RouteEdge candidate = new RouteEdge("approach", "outer", "junction", path.lengthM(),
                path.coordinates().stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()),
                path.sections(), null, null);
        return new EngineeringRouteEvaluator().evaluate(List.of(candidate)).isCompliant();
    }

    private static boolean same(RoutePath a, RoutePath b) {
        if (a.coordinates().size() != b.coordinates().size()) return false;
        for (int i = 0; i < a.coordinates().size(); i++) {
            if (a.coordinates().get(i).distance(b.coordinates().get(i)) > 0.001) return false;
        }
        return true;
    }

    private static boolean sameRay(RoutePath a, RoutePath b) {
        Coordinate ae = a.coordinates().get(a.coordinates().size() - 1), ap = a.coordinates().get(a.coordinates().size() - 2);
        Coordinate be = b.coordinates().get(b.coordinates().size() - 1), bp = b.coordinates().get(b.coordinates().size() - 2);
        double cosine = ((ap.x - ae.x) * (bp.x - be.x) + (ap.y - ae.y) * (bp.y - be.y)) / ap.distance(ae) / bp.distance(be);
        return cosine > Math.cos(Math.toRadians(1));
    }
}
