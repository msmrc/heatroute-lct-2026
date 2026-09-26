package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineSegment;

/** Округляет звенья технической сетки; геометрия готовых вводов сюда не передаётся. */
final class CorridorGridPolyline {
    private CorridorGridPolyline() { }

    /** Убирает коллинеарные промежуточные точки до округления к миллиметрам. */
    static List<Coordinate> rounded(List<Coordinate> raw) {
        List<Coordinate> straight = new ArrayList<>();
        for (Coordinate point : raw) {
            while (straight.size() > 1 && collinear(straight.get(straight.size() - 2),
                    straight.get(straight.size() - 1), point)) straight.remove(straight.size() - 1);
            straight.add(point);
        }
        List<Coordinate> result = new ArrayList<>();
        for (Coordinate point : straight) {
            Coordinate rounded = new RouteCoordinate(point.x, point.y).toCoordinate();
            if (result.isEmpty() || !result.get(result.size() - 1).equals2D(rounded)) result.add(rounded);
        }
        return result;
    }

    private static boolean collinear(Coordinate a, Coordinate b, Coordinate c) {
        double ax = b.x - a.x, ay = b.y - a.y, bx = c.x - b.x, by = c.y - b.y;
        if (ax * bx + ay * by < 0 || a.equals2D(c)) return false;
        // Только шум вычисления исходной повёрнутой сетки, не геометрический допуск в миллиметрах.
        double ulp = 0;
        for (Coordinate p : new Coordinate[] {a, b, c}) ulp = Math.max(ulp,
                Math.max(Math.ulp(p.x), Math.ulp(p.y)));
        return new LineSegment(a, c).distance(b) <= 8 * Math.sqrt(2) * ulp;
    }
}
