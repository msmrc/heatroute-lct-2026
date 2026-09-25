package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import org.locationtech.jts.geom.Coordinate;

/**
 * Предлагает до четырёх переносов камеры по знаковым осям с поперечной компонентой реальных связей.
 * Группировка в 1 см — поисковая эвристика, не отступ или инженерный допуск; сеть требует полной проверки.
 */
final class CorridorSupportedChamberRelocations {
    private static final int MAX_EDGE_COORDINATES = 512;
    private static final double RADIUS_M = 40.0;
    private static final double RADIUS_EPSILON_M = 1e-8;
    private static final double ENDPOINT_TOLERANCE_M = 0.01;
    private static final double AXIS_GROUPING_M = 0.01;

    private CorridorSupportedChamberRelocations() { }

    /**
     * Принимает 3–4 разных ребра новой некорневой камеры с любым направлением хранения геометрии.
     * Возвращает ближайшую разрешённую точку для −U, +U, −V, +V; предикат вызывается не более 24 раз.
     * Некорректный вход отклоняется явно; ребро длиннее 512 вершин даёт пустой набор без обхода геометрии.
     */
    static List<Coordinate> build(RouteNode chamber, List<RouteEdge> incidentEdges, double orientation,
            Set<String> terminalNodeIds, Predicate<Coordinate> pointAllowed) {
        ensureActive();
        require(chamber != null && !chamber.isRoot() && chamber.isChamber()
                && "new_branch_chamber".equals(chamber.getNodeType()) && named(chamber.getId()),
                "A non-root new branch chamber is required");
        require(incidentEdges != null && incidentEdges.size() >= 3 && incidentEdges.size() <= 4,
                "Three or four incident edges are required");
        require(Double.isFinite(orientation) && pointAllowed != null, "Finite orientation and point predicate required");
        require(terminalNodeIds != null && !terminalNodeIds.contains(chamber.getId()),
                "Terminal node IDs must be supplied and must not include the chamber");
        Frame frame = new Frame(coordinate(chamber.getCoordinate()), orientation);
        if (!boundedIncidence(chamber.getId(), incidentEdges)) return List.of();

        List<LocalPoint> seeds = new ArrayList<>();
        LocalPoint[] supports = new LocalPoint[4];
        for (RouteEdge edge : incidentEdges) {
            String outerId = chamber.getId().equals(edge.getUpstreamNodeId())
                    ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId();
            collectEdge(edge, frame, !terminalNodeIds.contains(outerId), seeds, supports);
        }

        List<List<LocalPoint>> groups = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        // 4 ребра × (внешний конец + 2 ближайшие разные внутренние вершины) × 2 оси = максимум 24 точки.
        for (LocalPoint seed : seeds) for (int axis = 0; axis < 2; axis++) {
            ensureActive();
            double along = axis == 0 ? seed.u : seed.v;
            if (Math.abs(along) <= AXIS_GROUPING_M || Math.abs(along) > RADIUS_M) continue;
            int key = axis * 2 + (along > 0 ? 1 : 0);
            LocalPoint support = supports[key];
            double across = support == null ? 0 : axis == 0 ? support.v : support.u;
            double u = axis == 0 ? along : across, v = axis == 0 ? across : along;
            if (Math.hypot(u, v) > RADIUS_M + RADIUS_EPSILON_M) continue;
            LocalPoint candidate = frame.rounded(u, v);
            if (candidate.point.equals2D(frame.center) || candidate.movement > RADIUS_M + RADIUS_EPSILON_M) continue;
            groups.get(key).add(candidate);
        }
        List<Coordinate> accepted = new ArrayList<>();
        List<Coordinate> seen = new ArrayList<>();
        for (List<LocalPoint> group : groups) {
            group.sort(localOrder());
            for (LocalPoint candidate : group) {
                ensureActive();
                if (seen.stream().anyMatch(p -> p.equals2D(candidate.point))) continue;
                seen.add(candidate.point);
                boolean allowed = pointAllowed.test(new Coordinate(candidate.point));
                ensureActive();
                if (allowed) {
                    accepted.add(new Coordinate(candidate.point));
                    break;
                }
            }
        }
        ensureActive();
        return List.copyOf(accepted);
    }

    private static boolean boundedIncidence(String chamberId, List<RouteEdge> edges) {
        Set<String> edgeIds = new HashSet<>(), outerIds = new HashSet<>();
        boolean bounded = true;
        for (RouteEdge edge : edges) {
            ensureActive();
            require(edge != null && named(edge.getId()) && edgeIds.add(edge.getId()), "Distinct named edges required");
            String from = edge.getUpstreamNodeId(), to = edge.getDownstreamNodeId();
            require(named(from) && named(to) && (from.equals(chamberId) ^ to.equals(chamberId)),
                    "Every edge must touch the chamber exactly once");
            require(outerIds.add(from.equals(chamberId) ? to : from), "Distinct outer nodes required");
            require(edge.getCoordinates() != null && edge.getCoordinates().size() >= 2, "Two geometry endpoints required");
            bounded &= edge.getCoordinates().size() <= MAX_EDGE_COORDINATES;
        }
        return bounded;
    }

    private static void collectEdge(RouteEdge edge, Frame frame, boolean supporting,
            List<LocalPoint> seeds, LocalPoint[] supports) {
        ensureActive();
        List<RouteCoordinate> points = edge.getCoordinates();
        LocalPoint first = frame.project(coordinate(points.get(0)));
        LocalPoint last = frame.project(coordinate(points.get(points.size() - 1)));
        boolean firstAtChamber = first.movement <= ENDPOINT_TOLERANCE_M;
        boolean lastAtChamber = last.movement <= ENDPOINT_TOLERANCE_M;
        require(firstAtChamber ^ lastAtChamber, "Exactly one geometry endpoint must match the chamber within 1 cm");
        LocalPoint outer = firstAtChamber ? last : first;
        seeds.add(outer);
        if (supporting) addSupport(supports, outer);
        List<LocalPoint> nearby = new ArrayList<>();
        for (int index = 1; index + 1 < points.size(); index++) {
            ensureActive();
            LocalPoint point = frame.project(coordinate(points.get(index)));
            if (supporting) addSupport(supports, point);
            if (!point.point.equals2D(frame.center) && !point.point.equals2D(outer.point)
                    && point.movement <= RADIUS_M + RADIUS_EPSILON_M) nearby.add(point);
        }
        nearby.sort(localOrder());
        LocalPoint previous = null;
        int selected = 0;
        for (LocalPoint point : nearby) {
            if (previous != null && previous.point.equals2D(point.point)) continue;
            seeds.add(point);
            previous = point;
            if (++selected == 2) break;
        }
    }

    private static void addSupport(LocalPoint[] supports, LocalPoint point) {
        int axis = Math.abs(point.u) >= Math.abs(point.v) ? 0 : 1;
        double along = axis == 0 ? point.u : point.v, across = axis == 0 ? point.v : point.u;
        if (Math.abs(across) > AXIS_GROUPING_M || Math.abs(along) <= AXIS_GROUPING_M) return;
        int key = axis * 2 + (along > 0 ? 1 : 0);
        Comparator<LocalPoint> order = Comparator.comparingDouble((LocalPoint p) -> Math.abs(axis == 0 ? p.u : p.v))
                .thenComparingDouble(p -> p.u).thenComparingDouble(p -> p.v);
        if (supports[key] == null || order.compare(point, supports[key]) < 0) supports[key] = point;
    }

    private static Comparator<LocalPoint> localOrder() {
        return Comparator.comparingDouble((LocalPoint p) -> p.movement).thenComparingDouble(p -> p.u).thenComparingDouble(p -> p.v);
    }

    private static Coordinate coordinate(RouteCoordinate value) {
        require(value != null, "Finite XY coordinates required");
        Coordinate point = value.toCoordinate();
        require(point != null && Double.isFinite(point.x) && Double.isFinite(point.y), "Finite XY coordinates required");
        return new Coordinate(point);
    }

    private static boolean named(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Supported chamber relocation cancelled");
    }

    private static final class Frame {
        private final Coordinate center;
        private final double cos, sin;
        private Frame(Coordinate center, double orientation) {
            this.center = center; cos = Math.cos(orientation); sin = Math.sin(orientation);
        }
        private LocalPoint project(Coordinate point) {
            double dx = point.x - center.x, dy = point.y - center.y;
            double u = dx * cos + dy * sin, v = -dx * sin + dy * cos, movement = Math.hypot(dx, dy);
            require(Double.isFinite(u) && Double.isFinite(v) && Double.isFinite(movement), "Non-finite local coordinates");
            return new LocalPoint(point, u, v, movement);
        }
        private LocalPoint rounded(double u, double v) {
            double x = center.x + u * cos - v * sin, y = center.y + u * sin + v * cos;
            require(Double.isFinite(x) && Double.isFinite(y), "Non-finite candidate coordinates");
            return project(new RouteCoordinate(x, y).toCoordinate());
        }
    }

    private static final class LocalPoint {
        private final Coordinate point;
        private final double u, v, movement;
        private LocalPoint(Coordinate point, double u, double v, double movement) {
            this.point = point; this.u = u; this.v = v; this.movement = movement;
        }
    }
}
