package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * Сокращает внешнюю часть полилинии, сохраняя первые и последние звенья и ввод в свой ОКС.
 * Возвращает только кандидата: допуск всей сети, повторный sizing, глубину и цену проверяет planner.
 */
final class RetainedEndpointSimplifier {
    // Локальная эвристика не запускает новый глобальный поиск на произвольно большой полилинии.
    private static final int MAX_COORDINATES = 512;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    RoutePath simplify(RouteEdge edge, OfficialObstacleRouter router, OfficialRoutingEnvironment environment,
            Set<String> exemptions, List<LineString> avoidance) {
        checkCancellation();
        List<RouteCoordinate> stored = edge.getCoordinates();
        // Без исходных секций splitAt потеряет стоимость сохранённых концов. Такой fallback
        // оставляем целиком исходному finish, а не создаём частично обсчитанного кандидата.
        if (stored.size() < 5 || stored.size() > MAX_COORDINATES || edge.getDiameter() == null
                || edge.getSections().isEmpty()) return null;
        List<Coordinate> points = stored.stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
        int first = 1;
        int last = points.size() - 2;
        // Внутри локального исключения своего ОКС shortcut не ищем: сохраняем весь подход.
        while (first < last && environment.pointInsideForbiddenClearance(edge.getDiameter(), points.get(first))) {
            checkCancellation();
            first++;
        }
        while (last > first && environment.pointInsideForbiddenClearance(edge.getDiameter(), points.get(last))) {
            checkCancellation();
            last--;
        }
        if (last - first < 2) return null;
        RoutePath original = new RoutePath(points, edge.getSections(), edge.getLengthM().doubleValue());
        RoutePath.Split entry = original.splitAt(points.get(first));
        if (entry == null) return null;
        RoutePath.Split exit = entry.downstream().splitAt(points.get(last));
        if (exit == null) return null;
        RoutePath core = router.regularize(points.subList(first, last + 1), edge.getDiameter(),
                environment, exemptions, avoidance);
        if (core == null || core.lengthM() >= exit.upstream().lengthM() - 0.001
                || !core.coordinates().get(0).equals2D(points.get(first))
                || !core.coordinates().get(core.coordinates().size() - 1).equals2D(points.get(last))) return null;
        List<Coordinate> combined = new ArrayList<>(entry.upstream().coordinates());
        combined.addAll(core.coordinates().subList(1, core.coordinates().size()));
        combined.addAll(exit.downstream().coordinates().subList(1, exit.downstream().coordinates().size()));
        LineString line = GEOMETRY.createLineString(combined.toArray(new Coordinate[0]));
        if (!line.isSimple()) return null;
        List<RouteSection> sections = new ArrayList<>(entry.upstream().sections());
        sections.addAll(core.sections());
        sections.addAll(exit.downstream().sections());
        return new RoutePath(combined, sections, line.getLength());
    }

    static boolean sameTerminalSegments(RouteEdge original, RouteEdge candidate) {
        List<RouteCoordinate> before = original.getCoordinates(), after = candidate.getCoordinates();
        if (before.size() < 2 || after.size() < 2) return false;
        for (int i = 0; i < 2; i++) {
            if (!before.get(i).toCoordinate().equals2D(after.get(i).toCoordinate())
                    || !before.get(before.size() - 1 - i).toCoordinate()
                            .equals2D(after.get(after.size() - 1 - i).toCoordinate())) return false;
        }
        return true;
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Route simplification cancelled");
    }
}
