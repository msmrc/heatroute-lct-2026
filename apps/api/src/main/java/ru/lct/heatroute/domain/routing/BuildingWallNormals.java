package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * Строит перпендикуляры к реальным отрезкам стен, а не к bounding rectangle здания.
 * Возвращает выходы по возрастанию расстояния до стены; отступ задан от стены до оси в метрах.
 */
final class BuildingWallNormals {
    private static final double EPSILON = 1e-7;
    private static final GeometryFactory FACTORY = new GeometryFactory();

    List<Exit> candidates(Geometry footprint, Coordinate point, double clearanceM) {
        if (footprint == null || point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)
                || !Double.isFinite(clearanceM) || clearanceM <= 0) {
            throw new IllegalArgumentException("Footprint, finite point and positive metric clearance are required");
        }
        if (footprint.isEmpty() || footprint.getDimension() != 2
                || !footprint.covers(FACTORY.createPoint(point))) return List.of();
        List<Exit> exits = new ArrayList<>();
        Geometry boundary = footprint.getBoundary();
        for (int ring = 0; ring < boundary.getNumGeometries(); ring++) {
            Coordinate[] coordinates = boundary.getGeometryN(ring).getCoordinates();
            for (int i = 1; i < coordinates.length; i++) {
                Coordinate a = coordinates[i - 1], b = coordinates[i];
                double dx = b.x - a.x, dy = b.y - a.y;
                double length = Math.hypot(dx, dy);
                if (length <= EPSILON) continue;
                double projection = ((point.x - a.x) * dx + (point.y - a.y) * dy) / (length * length);
                if (projection < -EPSILON || projection > 1 + EPSILON) continue;
                // Не зажимаем проекцию в вершину: такой отрезок уже не нормаль к стене.
                Coordinate wall = new Coordinate(a.x + projection * dx, a.y + projection * dy);
                double distance = wall.distance(point);
                if (distance <= EPSILON) {
                    add(exits, wall, -dy / length, dx / length, 0, clearanceM);
                    add(exits, wall, dy / length, -dx / length, 0, clearanceM);
                } else {
                    add(exits, wall, (wall.x - point.x) / distance,
                            (wall.y - point.y) / distance, distance, clearanceM);
                }
            }
        }
        exits.sort(Comparator.comparingDouble(Exit::wallDistanceM)
                .thenComparingDouble(exit -> exit.point().x)
                .thenComparingDouble(exit -> exit.point().y));
        List<Exit> result = new ArrayList<>();
        for (Exit raw : exits) {
            if (result.stream().anyMatch(previous -> previous.point.distance(raw.point) <= EPSILON)) continue;
            Exit exit = raw;
            // Вычисленная проекция может выйти за стену на единицы ULP при координатах UTM.
            // Проверяем длину выхода, а не топологический covers без численного допуска.
            if (exit.wallDistanceM > EPSILON
                    && line(point, exit.wall).difference(footprint).getLength() > EPSILON) continue;
            if (footprint.distance(FACTORY.createPoint(exit.point)) + EPSILON < clearanceM) {
                exit = extendToClearance(exit, footprint, clearanceM);
                if (exit == null) continue;
            }
            LineString outside = line(exit.wall, exit.point);
            // Вогнутый контур/другой компонент не должен повторно пересекать прямой ввод.
            boolean reenters = false;
            for (Coordinate intersection : outside.intersection(footprint).getCoordinates()) {
                if (intersection.distance(exit.wall) > EPSILON) { reenters = true; break; }
            }
            if (reenters) continue;
            if (footprint.distance(FACTORY.createPoint(exit.point)) + EPSILON < clearanceM) continue;
            Coordinate finalPoint = exit.point;
            if (result.stream().anyMatch(previous -> previous.point.distance(finalPoint) <= EPSILON)) continue;
            result.add(exit);
        }
        return List.copyOf(result);
    }

    /** Первый полный отступ вдоль фактического луча, в том числе после миллиметрового округления. */
    Coordinate firstClearancePoint(Geometry footprint, Coordinate origin, Coordinate towards, double clearanceM) {
        if (footprint.distance(FACTORY.createPoint(origin)) + EPSILON >= clearanceM) return new Coordinate(origin);
        if (origin.distance(towards) <= EPSILON) return null;
        Exit exit = extendToClearance(new Exit(origin, towards, 0), footprint, clearanceM);
        return exit == null ? null : exit.point();
    }

    /** Первый выход луча из объединения точных отступов от сегментов контура. */
    private Exit extendToClearance(Exit exit, Geometry footprint, double clearanceM) {
        double initialLength = exit.point.distance(exit.wall);
        double nx = (exit.point.x - exit.wall.x) / initialLength;
        double ny = (exit.point.y - exit.wall.y) / initialLength;
        List<double[]> intervals = new ArrayList<>();
        Geometry boundary = footprint.getBoundary();
        for (int ring = 0; ring < boundary.getNumGeometries(); ring++) {
            Coordinate[] points = boundary.getGeometryN(ring).getCoordinates();
            for (int i = 1; i < points.length; i++) {
                capsuleIntervals(intervals, exit.wall, nx, ny, points[i - 1], points[i], clearanceM);
            }
        }
        intervals.sort(Comparator.comparingDouble(interval -> interval[0]));
        double distance = 0;
        for (double[] interval : intervals) {
            if (interval[1] < 0) continue;
            // На касании двух открытых зон отступа может существовать допустимая точка.
            // Проверяем весь footprint: стык полосы и круга одной капсулы исключением не станет.
            if (distance > 0) {
                Coordinate contact = new Coordinate(exit.wall.x + nx * distance, exit.wall.y + ny * distance);
                if (footprint.distance(FACTORY.createPoint(contact)) + EPSILON >= clearanceM) {
                    return new Exit(exit.wall, contact, exit.wallDistanceM);
                }
            }
            if (interval[0] > distance + EPSILON) break;
            distance = Math.max(distance, interval[1]);
        }
        Coordinate point = new Coordinate(exit.wall.x + nx * distance, exit.wall.y + ny * distance);
        return footprint.distance(FACTORY.createPoint(point)) + EPSILON >= clearanceM
                ? new Exit(exit.wall, point, exit.wallDistanceM) : null;
    }

    /** Пересечение луча с капсулой: прямоугольная полоса сегмента и круги на концах. */
    private void capsuleIntervals(List<double[]> intervals, Coordinate origin, double nx, double ny,
            Coordinate a, Coordinate b, double radius) {
        circleInterval(intervals, origin, nx, ny, a, radius);
        circleInterval(intervals, origin, nx, ny, b, radius);
        double length = a.distance(b);
        if (length <= EPSILON) return;
        double ux = (b.x - a.x) / length, uy = (b.y - a.y) / length;
        double ox = origin.x - a.x, oy = origin.y - a.y;
        double[] interval = {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        if (clip(interval, ox * ux + oy * uy, nx * ux + ny * uy, 0, length)
                && clip(interval, -ox * uy + oy * ux, -nx * uy + ny * ux, -radius, radius)) {
            intervals.add(interval);
        }
    }

    private void circleInterval(List<double[]> intervals, Coordinate origin, double nx, double ny,
            Coordinate center, double radius) {
        double dx = center.x - origin.x, dy = center.y - origin.y;
        double along = dx * nx + dy * ny;
        double perpendicular = dx * ny - dy * nx;
        double remainder = radius * radius - perpendicular * perpendicular;
        if (remainder < 0) return;
        double halfLength = Math.sqrt(remainder);
        intervals.add(new double[] {along - halfLength, along + halfLength});
    }

    private boolean clip(double[] interval, double offset, double slope, double low, double high) {
        if (Math.abs(slope) < 1e-14) return offset >= low && offset <= high;
        double first = (low - offset) / slope, last = (high - offset) / slope;
        interval[0] = Math.max(interval[0], Math.min(first, last));
        interval[1] = Math.min(interval[1], Math.max(first, last));
        return interval[0] <= interval[1];
    }

    private void add(List<Exit> exits, Coordinate wall, double nx, double ny, double distance, double clearance) {
        exits.add(new Exit(wall, new Coordinate(wall.x + nx * clearance, wall.y + ny * clearance), distance));
    }

    private LineString line(Coordinate start, Coordinate end) {
        return FACTORY.createLineString(new Coordinate[] {new Coordinate(start), new Coordinate(end)});
    }

    static final class Exit {
        private final Coordinate wall;
        private final Coordinate point;
        private final double wallDistanceM;

        private Exit(Coordinate wall, Coordinate point, double wallDistanceM) {
            this.wall = new Coordinate(wall);
            this.point = new Coordinate(point);
            this.wallDistanceM = wallDistanceM;
        }

        Coordinate point() { return new Coordinate(point); }
        double wallDistanceM() { return wallDistanceM; }
    }
}
