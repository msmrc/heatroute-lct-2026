package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

/**
 * Выбирает совместимые подходы к общей камере: разные лучи, без наложений и скрытых пересечений.
 * Ортогональность с допуском 5° — фильтр этой кандидатной стратегии, не новая официальная норма.
 * Препятствия проверяет поставщик путей; итоговая сеть всё равно проходит независимый validator.
 */
final class CorridorJunctionAssignment {
    private static final GeometryFactory GEOMETRIES = new GeometryFactory();
    private static final double POSITION_EPSILON_M = 0.01;
    private static final double ORTHOGONAL_COSINE = Math.sin(Math.toRadians(5));
    private static final double OPPOSITE_COSINE = -Math.cos(Math.toRadians(5));

    private CorridorJunctionAssignment() { }

    /** С неизменяемой частью сети допускается только касание своего внешнего узла по его ID. */
    static boolean clearsRetained(RoutePath path, String outerNodeId, List<RouteEdge> retained) {
        ensureActive();
        LineString line = GEOMETRIES.createLineString(path.coordinates().toArray(new Coordinate[0]));
        Coordinate outer = path.coordinates().get(0);
        for (RouteEdge edge : retained) {
            ensureActive();
            if (edge.getCoordinates().size() < 2) return false;
            LineString fixed = GEOMETRIES.createLineString(edge.getCoordinates().stream()
                    .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
            if (!line.getEnvelopeInternal().intersects(fixed.getEnvelopeInternal())) continue;
            Geometry intersection = line.intersection(fixed);
            if (intersection.isEmpty()) continue;
            boolean sharesOuter = outerNodeId.equals(edge.getUpstreamNodeId()) || outerNodeId.equals(edge.getDownstreamNodeId());
            if (!sharesOuter || !(intersection instanceof Point)
                    || intersection.getCoordinate().distance(outer) > POSITION_EPSILON_M) return false;
        }
        return true;
    }

    /** Пути направлены внешний узел → камера; ответ сохраняет порядок внешних узлов. */
    static List<RoutePath> choose(Coordinate junction, List<List<RoutePath>> alternatives) {
        ensureActive();
        if (junction == null || !Double.isFinite(junction.x) || !Double.isFinite(junction.y)
                || alternatives == null || alternatives.size() < 2 || alternatives.size() > 4) {
            throw new IllegalArgumentException("Finite junction and two to four branches required");
        }
        List<List<Approach>> choices = new ArrayList<>();
        for (List<RoutePath> branch : alternatives) {
            if (branch == null || branch.size() > 8) throw new IllegalArgumentException("At most eight paths per branch");
            List<Approach> paths = new ArrayList<>();
            for (RoutePath path : branch) {
                ensureActive();
                Approach approach = prepare(junction, path);
                if (approach != null) paths.add(approach);
            }
            if (paths.isEmpty()) return null;
            paths.sort(Comparator.comparingDouble(p -> p.line.getLength()));
            choices.add(paths);
        }
        // Максимум 8^4 комбинаций. Парные отказы и нижняя граница длины сокращают поиск.
        Search search = new Search(junction, choices);
        search.visit(new ArrayList<>(), 0.0);
        if (search.best == null) return null;
        List<RoutePath> result = new ArrayList<>();
        for (Approach approach : search.best) result.add(approach.path);
        return List.copyOf(result);
    }

    private static Approach prepare(Coordinate junction, RoutePath path) {
        if (path == null || path.coordinates().size() < 2 || path.coordinates().size() > 1000) {
            throw new IllegalArgumentException("A path requires 2..1000 coordinates");
        }
        List<Coordinate> coordinates = path.coordinates();
        for (Coordinate c : coordinates) {
            if (c == null || !Double.isFinite(c.x) || !Double.isFinite(c.y)) {
                throw new IllegalArgumentException("Finite path coordinates required");
            }
        }
        Coordinate endpoint = coordinates.get(coordinates.size() - 1);
        if (endpoint.distance(junction) > POSITION_EPSILON_M) throw new IllegalArgumentException("Path misses junction");
        LineString line = GEOMETRIES.createLineString(coordinates.toArray(new Coordinate[0]));
        if (!line.isSimple() || line.isClosed() || !Double.isFinite(line.getLength()) || line.getLength() <= 0) return null;
        Coordinate before = null;
        for (int i = coordinates.size() - 2; i >= 0; i--) {
            if (coordinates.get(i).distance(endpoint) > POSITION_EPSILON_M) { before = coordinates.get(i); break; }
        }
        if (before == null) return null;
        double length = before.distance(endpoint);
        return new Approach(path, line, (before.x - endpoint.x) / length, (before.y - endpoint.y) / length);
    }

    private static boolean compatible(Coordinate junction, Approach a, Approach b) {
        double cosine = a.x * b.x + a.y * b.y;
        if (Math.abs(cosine) > ORTHOGONAL_COSINE + 1e-9 && cosine > OPPOSITE_COSINE + 1e-9) return false;
        Geometry intersection = a.line.intersection(b.line);
        return intersection.isEmpty() || intersection instanceof Point
                && intersection.getCoordinate().distance(junction) <= POSITION_EPSILON_M;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Junction assignment cancelled");
    }

    private static final class Search {
        private final Coordinate junction;
        private final List<List<Approach>> choices;
        private List<Approach> best;
        private double bestLength = Double.POSITIVE_INFINITY;

        private Search(Coordinate junction, List<List<Approach>> choices) {
            this.junction = junction;
            this.choices = choices;
        }

        private void visit(List<Approach> accepted, double length) {
            ensureActive();
            if (length >= bestLength) return;
            if (accepted.size() == choices.size()) {
                best = List.copyOf(accepted); bestLength = length; return;
            }
            for (Approach candidate : choices.get(accepted.size())) {
                ensureActive();
                if (accepted.stream().anyMatch(previous -> !compatible(junction, previous, candidate))) continue;
                accepted.add(candidate);
                visit(accepted, length + candidate.line.getLength());
                accepted.remove(accepted.size() - 1);
            }
        }
    }

    private static final class Approach {
        private final RoutePath path;
        private final LineString line;
        private final double x;
        private final double y;

        private Approach(RoutePath path, LineString line, double x, double y) {
            this.path = path; this.line = line; this.x = x; this.y = y;
        }
    }
}
