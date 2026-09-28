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
    private static final double MAX_ENGINEERING_EXTRA_M = 10.0;
    private static final double MAX_ENGINEERING_LENGTH_RATIO = 1.30;
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
        List<Coordinate> retainedCore = points.subList(first, last + 1);
        RoutePath core = bestRegularizedCore(retainedCore, points.get(first - 1), points.get(last + 1),
                edge.getDiameter(), router, environment, exemptions, avoidance);
        if (core == null || !acceptableCore(core, exit.upstream(), points.get(first - 1), points.get(last + 1),
                    edge.getDiameter())
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

    /**
     * Экспертный приоритет допускает небольшой прирост длины ради меньшего числа поворотов.
     * Ограничение одновременно абсолютное и относительное; нарушения углов или интервалов
     * никогда не обмениваются на эту льготу.
     */
    private boolean acceptableCore(RoutePath candidate, RoutePath control,
            Coordinate previous, Coordinate next, int diameter) {
        if (candidate.lengthM() < control.lengthM() - 0.001) return true;
        double extra = candidate.lengthM() - control.lengthM();
        if (extra > MAX_ENGINEERING_EXTRA_M + 1e-7
                || candidate.lengthM() > control.lengthM() * MAX_ENGINEERING_LENGTH_RATIO + 1e-7) return false;
        EngineeringRouteEvaluator.Evaluation before = geometryWithNeighbours(control, previous, next, diameter);
        EngineeringRouteEvaluator.Evaluation after = geometryWithNeighbours(candidate, previous, next, diameter);
        return after.bendCount() < before.bendCount()
                && after.invalidAngleCount() <= before.invalidAngleCount()
                && after.insufficientSpacingCount() <= before.insufficientSpacingCount();
    }

    /**
     * Сначала проверяет обычное сокращение, затем Z/L-кандидаты на осях крайних звеньев.
     * Это позволяет заменить обход буферного угла тремя тупыми поворотами на два прямых,
     * сохранив направления входа и выхода. Каждый кандидат всё равно проходит regularize.
     */
    private RoutePath bestRegularizedCore(List<Coordinate> source, Coordinate previous, Coordinate next, int diameter,
            OfficialObstacleRouter router, OfficialRoutingEnvironment environment,
            Set<String> exemptions, List<LineString> avoidance) {
        List<List<Coordinate>> candidates = new ArrayList<>();
        candidates.add(new ArrayList<>(source));
        candidates.addAll(orthogonalCandidates(source, previous, next));
        RoutePath best = null;
        EngineeringRouteEvaluator.Evaluation bestGeometry = null;
        for (int index = 0; index < candidates.size(); index++) {
            checkCancellation();
            List<Coordinate> candidate = candidates.get(index);
            RoutePath checked = index == 0
                    ? router.regularizeAfter(previous, candidate, diameter, environment, exemptions, avoidance)
                    : router.regularizeAfterPreservingAxes(
                            previous, candidate, diameter, environment, exemptions, avoidance);
            if (checked == null || !checked.coordinates().get(0).equals2D(source.get(0))
                    || !checked.coordinates().get(checked.coordinates().size() - 1)
                            .equals2D(source.get(source.size() - 1))) continue;
            EngineeringRouteEvaluator.Evaluation geometry = geometryWithNeighbours(
                    checked, previous, next, diameter);
            if (best == null || better(geometry, checked, bestGeometry, best)) {
                best = checked;
                bestGeometry = geometry;
            }
        }
        // Сохраняем прежний контракт кандидата: окончательная проверка стыков принадлежит
        // planner. Новый контекстный поиск используется первым и не отбрасывает старый fallback.
        if (best == null) best = router.regularize(source, diameter, environment, exemptions, avoidance);
        return best;
    }

    private boolean better(EngineeringRouteEvaluator.Evaluation candidate, RoutePath candidatePath,
            EngineeringRouteEvaluator.Evaluation control, RoutePath controlPath) {
        if (candidate.invalidAngleCount() != control.invalidAngleCount()) {
            return candidate.invalidAngleCount() < control.invalidAngleCount();
        }
        if (candidate.insufficientSpacingCount() != control.insufficientSpacingCount()) {
            return candidate.insufficientSpacingCount() < control.insufficientSpacingCount();
        }
        if (candidate.bendCount() != control.bendCount()) return candidate.bendCount() < control.bendCount();
        if (Math.abs(candidate.preferredAngleDeviation() - control.preferredAngleDeviation()) > 1e-7) {
            return candidate.preferredAngleDeviation() < control.preferredAngleDeviation();
        }
        return candidatePath.lengthM() < controlPath.lengthM() - 0.001;
    }

    private List<List<Coordinate>> orthogonalCandidates(
            List<Coordinate> source, Coordinate previous, Coordinate next) {
        if (source.size() < 4) return List.of();
        Coordinate start = source.get(0), end = source.get(source.size() - 1);
        List<List<Coordinate>> result = new ArrayList<>();
        addOrthogonalCandidates(result, source, start, end,
                start.x - previous.x, start.y - previous.y,
                next.x - end.x, next.y - end.y);
        addOrthogonalCandidates(result, source, start, end,
                source.get(1).x - start.x, source.get(1).y - start.y,
                end.x - source.get(source.size() - 2).x,
                end.y - source.get(source.size() - 2).y);
        return List.copyOf(result);
    }

    /**
     * Строит L/Z-кандидаты по заданным осям. Первый вызов использует сохранённые крайние
     * звенья всей полилинии: иначе короткие переходные отрезки внутри core скрывают тот факт,
     * что фактические вход и выход уже параллельны либо перпендикулярны.
     */
    private void addOrthogonalCandidates(List<List<Coordinate>> result, List<Coordinate> source,
            Coordinate start, Coordinate end, double ex, double ey, double lx, double ly) {
        double firstLength = Math.hypot(ex, ey);
        double lastLength = Math.hypot(lx, ly);
        if (!(firstLength > 0.01) || !(lastLength > 0.01)) return;
        ex /= firstLength; ey /= firstLength;
        lx /= lastLength; ly /= lastLength;
        double cross = ex * ly - ey * lx;
        double dot = ex * lx + ey * ly;
        double tolerance = Math.sin(Math.toRadians(0.5));
        if (Math.abs(cross) <= tolerance && Math.abs(dot) >= Math.cos(Math.toRadians(0.5))) {
            double px = -ey, py = ex;
            double endU = projection(start, end, ex, ey);
            double endV = projection(start, end, px, py);
            double lastSign = Math.signum(dot);
            for (int index = 1; index + 1 < source.size(); index++) {
                double h = projection(start, source.get(index), ex, ey);
                if (h <= 0.01 || (endU - h) * lastSign <= 0.01) continue;
                Coordinate first = new Coordinate(start.x + h * ex, start.y + h * ey);
                Coordinate second = new Coordinate(first.x + endV * px, first.y + endV * py);
                addCandidate(result, List.of(new Coordinate(start), first, second, new Coordinate(end)));
            }
        } else if (Math.abs(dot) <= tolerance && Math.abs(cross) >= Math.cos(Math.toRadians(0.5))) {
            double alongFirst = projection(start, end, ex, ey);
            Coordinate elbow = new Coordinate(start.x + alongFirst * ex, start.y + alongFirst * ey);
            double alongLast = projection(elbow, end, lx, ly);
            if (alongFirst > 0.01 && alongLast > 0.01) {
                addCandidate(result, List.of(new Coordinate(start), elbow, new Coordinate(end)));
            }
        }
    }

    private void addCandidate(List<List<Coordinate>> candidates, List<Coordinate> raw) {
        List<Coordinate> distinct = new ArrayList<>();
        for (Coordinate point : raw) {
            Coordinate rounded = new RouteCoordinate(point.x, point.y).toCoordinate();
            if (distinct.isEmpty() || distinct.get(distinct.size() - 1).distance(rounded) > 0.01) {
                distinct.add(rounded);
            }
        }
        if (distinct.size() < 2 || !GEOMETRY.createLineString(distinct.toArray(new Coordinate[0])).isSimple()) return;
        if (candidates.stream().noneMatch(candidate -> sameCoordinates(candidate, distinct))) {
            candidates.add(List.copyOf(distinct));
        }
    }

    private boolean sameCoordinates(List<Coordinate> left, List<Coordinate> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).equals2D(right.get(index))) return false;
        }
        return true;
    }

    private double projection(Coordinate origin, Coordinate point, double dx, double dy) {
        return (point.x - origin.x) * dx + (point.y - origin.y) * dy;
    }

    private EngineeringRouteEvaluator.Evaluation geometryWithNeighbours(RoutePath path,
            Coordinate previous, Coordinate next, int diameter) {
        List<Coordinate> points = new ArrayList<>(List.of(previous));
        points.addAll(path.coordinates());
        points.add(next);
        RouteEdge edge = new RouteEdge("retained-core", "start", "end", path.lengthM(),
                points.stream().map(point -> new RouteCoordinate(point.x, point.y))
                        .collect(Collectors.toList()),
                path.sections(), null, diameter);
        return new EngineeringRouteEvaluator().evaluate(List.of(edge));
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
