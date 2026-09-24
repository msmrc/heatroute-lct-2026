package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;

/**
 * Выбирает ось самого весомого фасадного кластера шириной 6° с периодом 90°, в радианах.
 * Вес — длина рёбер минимальных прямоугольников; это кандидатная эвристика, не норматив.
 * При отсутствии выраженной оси или превышении бюджета использует направление к дальнему терминалу.
 */
final class CorridorOrientation {
    private static final double PERIOD = Math.PI / 2;
    private static final double CLUSTER_WIDTH = Math.toRadians(6);
    private static final double ANGULAR_EPSILON = 1e-9;
    private static final double WEIGHT_EPSILON = 1e-12;
    private static final int MAX_DIRECTIONS = 100_000;
    private static final int MAX_FOOTPRINTS = 25_000;
    private static final long MAX_INPUT_COORDINATES = 1_000_000;

    private CorridorOrientation() { }

    static double angle(List<Geometry> footprints, List<Coordinate> terminals, Coordinate root) {
        ensureActive();
        if (footprints == null || terminals == null || !finite(root)) {
            throw new IllegalArgumentException("Footprints, terminals and finite root XY are required");
        }
        List<Direction> directions = directions(footprints);
        if (directions.isEmpty()) return fallback(terminals, root);
        directions.sort(Comparator.comparingDouble((Direction direction) -> direction.angle)
                .thenComparingDouble(direction -> direction.length));
        double maximumLength = directions.stream().mapToDouble(direction -> direction.length).max().orElseThrow();
        double totalWeight = 0;
        double cosine = 0;
        double sine = 0;
        for (Direction direction : directions) {
            ensureActive();
            direction.weight = direction.length / maximumLength;
            totalWeight += direction.weight;
            cosine += direction.weight * Math.cos(4 * direction.angle);
            sine += direction.weight * Math.sin(4 * direction.angle);
        }
        // Нулевой четвёртый момент означает изотропное/симметричное распределение осей.
        if (Math.hypot(cosine, sine) <= totalWeight * 1e-8) return fallback(terminals, root);
        Window best = largestWindow(directions, totalWeight);
        // У выбранного окна должно быть хотя бы вдвое больше веса, чем у равномерного фона.
        if (best.weight < totalWeight * (2 * CLUSTER_WIDTH / PERIOD)) return fallback(terminals, root);
        cosine = 0;
        sine = 0;
        for (int index = best.start; index < best.end; index++) {
            ensureActive();
            Direction direction = directions.get(index % directions.size());
            cosine += direction.weight * Math.cos(4 * direction.angle);
            sine += direction.weight * Math.sin(4 * direction.angle);
        }
        return normalize(Math.atan2(sine, cosine) / 4);
    }

    private static List<Direction> directions(List<Geometry> footprints) {
        if (footprints.size() > MAX_FOOTPRINTS) return List.of();
        List<Direction> result = new ArrayList<>();
        long coordinateCount = 0;
        for (Geometry footprint : footprints) {
            ensureActive();
            if (footprint == null || footprint.getDimension() != 2 || footprint.isEmpty()) continue;
            coordinateCount += footprint.getNumPoints();
            if (coordinateCount > MAX_INPUT_COORDINATES) return List.of();
            FiniteCoordinates check = new FiniteCoordinates();
            footprint.apply(check);
            if (!check.valid) continue;
            Geometry rectangle = MinimumDiameter.getMinimumRectangle(footprint);
            ensureActive();
            Coordinate[] points = rectangle.getCoordinates();
            for (int index = 1; index < points.length; index++) {
                double dx = points[index].x - points[index - 1].x;
                double dy = points[index].y - points[index - 1].y;
                double length = Math.hypot(dx, dy);
                if (!Double.isFinite(length) || length <= 0) continue;
                if (result.size() == MAX_DIRECTIONS) return List.of();
                result.add(new Direction(normalize(Math.atan2(dy, dx)), length));
            }
        }
        return result;
    }

    /** После сортировки правая граница проходит не более двух оборотов; полного сканирования окон нет. */
    private static Window largestWindow(List<Direction> directions, double totalWeight) {
        int count = directions.size();
        int right = 0;
        double weight = 0;
        Window best = null;
        double tolerance = totalWeight * WEIGHT_EPSILON;
        for (int left = 0; left < count; left++) {
            ensureActive();
            while (right < left + count
                    && unwrappedAngle(directions, right) - directions.get(left).angle
                            <= CLUSTER_WIDTH + ANGULAR_EPSILON) {
                weight += directions.get(right % count).weight;
                right++;
            }
            double span = unwrappedAngle(directions, right - 1) - directions.get(left).angle;
            if (best == null || weight > best.weight + tolerance
                    || (Math.abs(weight - best.weight) <= tolerance && span < best.span - ANGULAR_EPSILON)) {
                best = new Window(left, right, weight, span);
            }
            weight -= directions.get(left).weight;
        }
        return best;
    }

    private static double unwrappedAngle(List<Direction> directions, int index) {
        return directions.get(index % directions.size()).angle + (index >= directions.size() ? PERIOD : 0);
    }

    private static double fallback(List<Coordinate> terminals, Coordinate root) {
        Coordinate farthest = null;
        double maximumDistance = 0;
        for (Coordinate terminal : terminals) {
            ensureActive();
            if (!finite(terminal)) continue;
            double distance = Math.hypot(terminal.x - root.x, terminal.y - root.y);
            if (!Double.isFinite(distance) || distance == 0) continue;
            if (farthest == null || distance > maximumDistance
                    || (distance == maximumDistance && coordinateOrder(terminal, farthest) < 0)) {
                farthest = terminal;
                maximumDistance = distance;
            }
        }
        return farthest == null ? 0 : normalize(Math.atan2(farthest.y - root.y, farthest.x - root.x));
    }

    private static int coordinateOrder(Coordinate left, Coordinate right) {
        int xOrder = Double.compare(left.x, right.x);
        return xOrder == 0 ? Double.compare(left.y, right.y) : xOrder;
    }

    private static boolean finite(Coordinate point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y);
    }

    private static double normalize(double angle) {
        double normalized = angle % PERIOD;
        if (normalized < 0) normalized += PERIOD;
        return normalized == 0 || normalized == PERIOD ? 0 : normalized;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor orientation cancelled");
    }

    private static final class FiniteCoordinates implements CoordinateSequenceFilter {
        private boolean valid = true;

        @Override
        public void filter(CoordinateSequence sequence, int index) {
            ensureActive();
            valid = Double.isFinite(sequence.getX(index)) && Double.isFinite(sequence.getY(index));
        }

        @Override public boolean isDone() { return !valid; }
        @Override public boolean isGeometryChanged() { return false; }
    }

    private static final class Direction {
        private final double angle;
        private final double length;
        private double weight;

        private Direction(double angle, double length) {
            this.angle = angle;
            this.length = length;
        }
    }

    private static final class Window {
        private final int start;
        private final int end;
        private final double weight;
        private final double span;

        private Window(int start, int end, double weight, double span) {
            this.start = start;
            this.end = end;
            this.weight = weight;
            this.span = span;
        }
    }
}
