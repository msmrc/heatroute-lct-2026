package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;

/** Строит подход к заданному последнему лучу без разворота больше 90°. */
final class TerminalSuffixGeometry {
    private TerminalSuffixGeometry() { }

    static List<Coordinate> append(List<Coordinate> source, Coordinate end) {
        if (source.size() < 2) return List.of();
        List<Coordinate> result = new ArrayList<>(source);
        Coordinate waypoint = source.get(source.size() - 1);
        if (waypoint.equals2D(end)) return valid(result) ? result : List.of();
        result.add(end);
        if (valid(result)) return result;

        Coordinate before = source.get(source.size() - 2);
        double dx = end.x - waypoint.x, dy = end.y - waypoint.y;
        double length = Math.hypot(dx, dy);
        double nx = dx / length, ny = dy / length;
        double projection = (before.x - waypoint.x) * nx + (before.y - waypoint.y) * ny;
        // Последний свободный отрезок заменяется двумя: вдоль конечного луча и поперёк.
        // Это локальный кандидат, а не разрешение двигать препятствия или обязательный ввод ОКС.
        if (projection <= 0.0) return List.of();
        Coordinate elbow = new Coordinate(before.x - projection * nx, before.y - projection * ny);
        if (elbow.distance(waypoint) <= OfficialRouteGeometryRules.EPSILON_M) return List.of();
        result.add(result.size() - 2, elbow);
        return valid(result) ? result : List.of();
    }

    private static boolean valid(List<Coordinate> coordinates) {
        return OfficialRouteDeflectionRules.validatePolyline("terminal-suffix", coordinates.stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()))
                .getIssues().isEmpty();
    }
}
