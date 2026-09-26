package ru.lct.heatroute.domain.topology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;

/**
 * Связывает точечное событие врезки с ближайшим к её узлу сегментом исходной сети.
 * Проверка не разрешает врезку сама по себе: ID источника и окно привязки проверяет вызывающий код.
 */
public final class ExistingSourceContact {
    private static final int ARITHMETIC_ULPS = 8;

    private ExistingSourceContact() {
    }

    /**
     * Проверяет линейный источник в метрических XY. Все координаты должны быть конечными.
     * Допуск относится только к погрешности операций double; инженерные расстояния не меняются.
     * Обход итеративный, не более двух проходов по сегментам LineString/MultiLineString.
     */
    public static boolean liesOnNearestSegment(Geometry source, Coordinate endpoint, Coordinate hit) {
        finite(endpoint);
        finite(hit);
        active();
        if (source == null || source.isEmpty()) {
            return false;
        }
        Deque<Geometry> pending = new ArrayDeque<>();
        pending.add(source);
        List<LineSegment> segments = new ArrayList<>();
        double nearest = Double.POSITIVE_INFINITY;
        while (!pending.isEmpty()) {
            active();
            Geometry part = pending.removeFirst();
            if (part instanceof LineString) {
                LineString line = (LineString) part;
                for (int index = 1; index < line.getNumPoints(); index++) {
                    active();
                    Coordinate start = line.getCoordinateN(index - 1);
                    Coordinate end = line.getCoordinateN(index);
                    finite(start);
                    finite(end);
                    if (start.equals2D(end)) {
                        continue;
                    }
                    LineSegment segment = new LineSegment(start, end);
                    segments.add(segment);
                    nearest = Math.min(nearest, segment.distance(endpoint));
                }
            } else if (part instanceof GeometryCollection) {
                for (int index = 0; index < part.getNumGeometries(); index++) {
                    active();
                    pending.addLast(part.getGeometryN(index));
                }
            } else {
                return false;
            }
        }
        if (!Double.isFinite(nearest)) {
            return false;
        }
        for (LineSegment segment : segments) {
            active();
            double tolerance = numericTolerance(segment, endpoint, hit);
            if (segment.distance(endpoint) <= nearest + tolerance
                    && segment.distance(hit) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    private static double numericTolerance(LineSegment segment, Coordinate endpoint, Coordinate hit) {
        double ulp = 0;
        for (Coordinate point : new Coordinate[] {segment.p0, segment.p1, endpoint, hit}) {
            ulp = Math.max(ulp, Math.max(Math.ulp(point.x), Math.ulp(point.y)));
        }
        return ARITHMETIC_ULPS * ulp;
    }

    private static void finite(Coordinate point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) {
            throw new IllegalArgumentException("Source contact requires finite metric XY coordinates");
        }
    }

    private static void active() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Source contact lookup cancelled");
        }
    }
}
