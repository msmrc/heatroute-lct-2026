package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;

/**
 * Предлагает положение общей камеры на пересечениях осей внешних узлов (локальная сетка Ханана).
 * Не ограничивается серединой старой связи: вводы и сохраняемые магистрали могут задать другой узел.
 * Не более 16 точек в радиусе 40 м от середины; препятствия проверяются при построении путей.
 */
final class CorridorChamberLocations {
    private CorridorChamberLocations() { }

    static List<Coordinate> build(Coordinate left, Coordinate right, List<Coordinate> outer, double angle) {
        requireFinite(left); requireFinite(right);
        if (outer == null || outer.size() != 4 || !Double.isFinite(angle)) {
            throw new IllegalArgumentException("Four outer points and finite orientation required");
        }
        outer.forEach(CorridorChamberLocations::requireFinite);
        double nx = Math.cos(angle), ny = Math.sin(angle);
        Coordinate center = new Coordinate((left.x + right.x) / 2, (left.y + right.y) / 2);
        List<Coordinate> seeds = new ArrayList<>(outer); seeds.add(left); seeds.add(right); seeds.add(center);
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (Coordinate seed : seeds) {
            double dx = seed.x - center.x, dy = seed.y - center.y;
            add(xs, dx * nx + dy * ny); add(ys, -dx * ny + dy * nx);
        }
        List<Coordinate> candidates = new ArrayList<>();
        for (double x : xs) for (double y : ys) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Chamber locations cancelled");
            if (Math.hypot(x, y) > 40.0 + 1e-8) continue;
            Coordinate point = new Coordinate(center.x + x * nx - y * ny, center.y + x * ny + y * nx);
            if (candidates.stream().noneMatch(previous -> previous.distance(point) < 0.01)) candidates.add(point);
        }
        candidates.sort(Comparator.comparingDouble((Coordinate c) -> outer.stream().mapToDouble(c::distance).sum())
                .thenComparingDouble(center::distance)
                .thenComparingDouble(c -> (c.x - center.x) * nx + (c.y - center.y) * ny)
                .thenComparingDouble(c -> -(c.x - center.x) * ny + (c.y - center.y) * nx));
        return List.copyOf(candidates.subList(0, Math.min(16, candidates.size())));
    }

    private static void add(List<Double> values, double value) {
        if (values.stream().noneMatch(existing -> Math.abs(existing - value) < 0.01)) values.add(value);
    }
    private static void requireFinite(Coordinate point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) {
            throw new IllegalArgumentException("Finite metric point required");
        }
    }
}
