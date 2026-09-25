package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

/**
 * Строит ограниченный граф общих проходов в системе направлений фасадов.
 * Координаты линий выводятся из потребителей и застройки; сохраняется кандидат ребра,
 * допустимый хотя бы в одном направлении. Фактическое rooted-направление и полный special
 * обязательны при сборке сети; неориентированный link сам по себе не является допуском трассы.
 */
final class OrthogonalCorridorGrid {
    private static final int MAX_AXIS_COORDINATES = 96;
    private static final double MIN_AXIS_SPACING_M = 2.5;
    private static final double BOUNDARY_MARGIN_M = 0.5;
    private final List<Coordinate> points;
    private final List<int[]> links;
    private final int rootIndex;
    private final boolean[] reachable;

    private OrthogonalCorridorGrid(List<Coordinate> points, List<int[]> links, int rootIndex) {
        this.points = points;
        this.links = links;
        this.rootIndex = rootIndex;
        reachable = new boolean[points.size()];
        List<List<Integer>> neighbors = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) neighbors.add(new ArrayList<>());
        for (int[] link : links) {
            neighbors.get(link[0]).add(link[1]); neighbors.get(link[1]).add(link[0]);
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        reachable[rootIndex] = true; queue.add(rootIndex);
        while (!queue.isEmpty()) {
            ensureActive();
            for (int next : neighbors.get(queue.remove())) {
                if (!reachable[next]) { reachable[next] = true; queue.add(next); }
            }
        }
    }

    static OrthogonalCorridorGrid build(Coordinate root, List<Coordinate> terminals,
            List<Geometry> footprints, double clearanceM, Predicate<Coordinate> pointAllowed,
            BiPredicate<Coordinate, Coordinate> edgeAllowed) {
        return build(root, terminals, footprints, clearanceM, pointAllowed, edgeAllowed,
                CorridorOrientation.angle(footprints, terminals, root));
    }

    /** Одна и та же система осей для сетки и вводов; повторного выбора по смещённым anchors нет. */
    static OrthogonalCorridorGrid build(Coordinate root, List<Coordinate> terminals,
            List<Geometry> footprints, double clearanceM, Predicate<Coordinate> pointAllowed,
            BiPredicate<Coordinate, Coordinate> edgeAllowed, double orientation) {
        requireFinite(root);
        Objects.requireNonNull(terminals, "terminals").forEach(OrthogonalCorridorGrid::requireFinite);
        Objects.requireNonNull(footprints, "footprints");
        Objects.requireNonNull(pointAllowed, "pointAllowed");
        Objects.requireNonNull(edgeAllowed, "edgeAllowed");
        if (!Double.isFinite(clearanceM) || clearanceM < 0) throw new IllegalArgumentException("Invalid clearance");
        if (!Double.isFinite(orientation)) throw new IllegalArgumentException("Invalid orientation");
        ensureActive();
        if (terminals.isEmpty()) return new OrthogonalCorridorGrid(List.of(new Coordinate(root)), List.of(), 0);
        Frame frame = new Frame(root, orientation);
        List<Coordinate> localTerminals = new ArrayList<>();
        Envelope bounds = new Envelope(0, 0, 0, 0);
        for (Coordinate terminal : terminals) {
            Coordinate local = frame.local(terminal);
            localTerminals.add(local);
            bounds.expandToInclude(local);
        }
        bounds.expandBy(Math.max(30, clearanceM * 2));
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        localTerminals.forEach(point -> { xs.add(point.x); ys.add(point.y); });
        xs.add(bounds.getMinX()); xs.add(bounds.getMaxX());
        ys.add(bounds.getMinY()); ys.add(bounds.getMaxY());
        for (Geometry footprint : footprints) {
            ensureActive();
            if (footprint == null || footprint.isEmpty() || footprint.getDimension() != 2) continue;
            Envelope box = new Envelope();
            for (Coordinate coordinate : footprint.getCoordinates()) box.expandToInclude(frame.local(coordinate));
            box.expandBy(clearanceM + BOUNDARY_MARGIN_M);
            if (!bounds.intersects(box)) continue;
            addInside(xs, box.getMinX(), bounds.getMinX(), bounds.getMaxX());
            addInside(xs, box.getMaxX(), bounds.getMinX(), bounds.getMaxX());
            addInside(ys, box.getMinY(), bounds.getMinY(), bounds.getMaxY());
            addInside(ys, box.getMaxY(), bounds.getMinY(), bounds.getMaxY());
        }
        List<Double> xAxis = axis(xs), yAxis = axis(ys);
        int[][] ids = new int[xAxis.size()][yAxis.size()];
        List<Coordinate> points = new ArrayList<>();
        int rootIndex = -1;
        for (int x = 0; x < xAxis.size(); x++) {
            ensureActive();
            for (int y = 0; y < yAxis.size(); y++) {
                Coordinate point = frame.world(xAxis.get(x), yAxis.get(y));
                boolean isRoot = xAxis.get(x) == 0.0 && yAxis.get(y) == 0.0;
                ids[x][y] = -1;
                // Локальный подход к существующему корню проверяется на ребре, не глобальным
                // отключением буфера его здания для всей кандидатной сети.
                if (!isRoot && !pointAllowed.test(point)) continue;
                if (isRoot) rootIndex = points.size();
                ids[x][y] = points.size();
                points.add(point);
            }
        }
        List<int[]> links = new ArrayList<>();
        for (int x = 0; x < xAxis.size(); x++) {
            ensureActive();
            for (int y = 0; y < yAxis.size(); y++) {
                if (ids[x][y] < 0) continue;
                if (x > 0) link(ids[x][y], ids[x - 1][y], points, links, edgeAllowed);
                if (y > 0) link(ids[x][y], ids[x][y - 1], points, links, edgeAllowed);
            }
        }
        return new OrthogonalCorridorGrid(points, links, rootIndex);
    }

    private static void link(int from, int to, List<Coordinate> points, List<int[]> links,
            BiPredicate<Coordinate, Coordinate> allowed) {
        if (to >= 0 && (allowed.test(points.get(from), points.get(to))
                || allowed.test(points.get(to), points.get(from)))) links.add(new int[] {from, to});
    }

    /** Первые кандидаты — ближайшие свободные узлы; реальный ввод проверяет маршрутизатор. */
    List<Integer> portsNear(Coordinate point, int limit) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) if (i != rootIndex && reachable[i]) indices.add(i);
        indices.sort(Comparator.comparingDouble((Integer index) -> points.get(index).distance(point))
                .thenComparingDouble(index -> points.get(index).x).thenComparingDouble(index -> points.get(index).y));
        return List.copyOf(indices.subList(0, Math.min(limit, indices.size())));
    }

    private static void addInside(List<Double> values, double value, double min, double max) {
        if (value > min && value < max) values.add(value);
    }

    private static List<Double> axis(List<Double> source) {
        TreeSet<Double> selected = new TreeSet<>();
        selected.add(0.0);
        // Геометрический приоритет, независимый от порядка объектов/их идентификаторов.
        source.sort(Comparator.comparingDouble((Double value) -> Math.abs(value)).thenComparingDouble(value -> value));
        for (double value : source) {
            Double lower = selected.floor(value), upper = selected.ceiling(value);
            if ((lower == null || value - lower >= MIN_AXIS_SPACING_M)
                    && (upper == null || upper - value >= MIN_AXIS_SPACING_M)) selected.add(value);
            if (selected.size() >= MAX_AXIS_COORDINATES) break;
        }
        return List.copyOf(selected);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor generation cancelled");
    }

    private static void requireFinite(Coordinate point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) {
            throw new IllegalArgumentException("Expected finite metric coordinate");
        }
    }

    List<Coordinate> points() { return points; }
    List<int[]> links() { return links; }
    int rootIndex() { return rootIndex; }

    private static final class Frame {
        private final Coordinate origin;
        private final double ux, uy;
        private Frame(Coordinate root, double angle) {
            origin = new Coordinate(root); ux = Math.cos(angle); uy = Math.sin(angle);
        }
        private Coordinate local(Coordinate point) {
            double x = point.x - origin.x, y = point.y - origin.y;
            return new Coordinate(x * ux + y * uy, -x * uy + y * ux);
        }
        private Coordinate world(double x, double y) {
            return new Coordinate(origin.x + x * ux - y * uy, origin.y + x * uy + y * ux);
        }
    }
}
