package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;

/**
 * Проверяет рёбра одного общего коридора. Корневое исключение ограничено коротким выходом
 * из отступа ОКС; выход не повторяет занятый луч существующей теплосети. Это консервативный
 * фильтр кандидатов, а не изменение официального валидатора или правил глубины.
 */
final class PreparedCorridor {
    private static final double ROOT_ROUNDING_TOLERANCE_M = 0.001;
    private static final double EXIT_ROUNDING_MARGIN_M = 0.002;
    private static final double MIN_OUTWARD_COSINE = Math.sqrt(0.5);
    private static final double MAX_EXIT_STRETCH = Math.sqrt(2.0);
    private static final double DIRECTION_EPSILON = 1e-8;
    private static final double GEOMETRY_EPSILON_M = 1e-7;
    // Конус от занятого исходящего луча: 45° с запасом 0,5° на ориентацию коридорной сетки.
    private static final double MAX_ROOT_RAY_COSINE = Math.cos(Math.toRadians(45.0 - 0.5));
    private final OfficialRouteGeometryRules rules;
    private final List<Constraint> constraints;
    private final List<Constraint> rootConstraints;
    private final List<Constraint> rootSetbacks;
    private final List<Coordinate> rootIncidentRays;
    private final ConstraintIndex strictIndex;
    private final ConstraintIndex rootIndex;
    private final Coordinate root;

    PreparedCorridor(OfficialRouteGeometryRules rules, List<Constraint> constraints,
            Coordinate root, String targetId) {
        this(rules, constraints, root, targetId, RouteTraversal.AS_GIVEN);
    }

    PreparedCorridor(OfficialRouteGeometryRules rules, List<Constraint> constraints,
            Coordinate root, String targetId, RouteTraversal traversal) {
        this.rules = Objects.requireNonNull(rules, "Corridor geometry rules are required");
        this.constraints = List.copyOf(constraints);
        requireFinite(root);
        this.root = new Coordinate(root);
        List<Coordinate> incidentRays = new ArrayList<>();
        for (Constraint constraint : this.constraints) {
            if ("heat_network".equals(constraint.type())) collectIncidentRays(constraint.source(), incidentRays);
        }
        // Выбранная сеть врезки тоже занимает луч: вычисляем до любых endpoint-исключений.
        this.rootIncidentRays = List.copyOf(incidentRays);
        Geometry rootPoint = new GeometryFactory().createPoint(root);
        this.rootSetbacks = this.constraints.stream()
                .filter(constraint -> "oks".equals(constraint.type()) && constraint.rule().isForbidden()
                        && constraint.blocked().covers(rootPoint) && !constraint.source().covers(rootPoint))
                .collect(Collectors.toList());
        // ID разрешает лишь врезку в выбранную существующую теплосеть, а не отмену любого
        // одноимённого запрета. Фильтруем сами constraints: ID разных типов могут совпасть.
        List<Constraint> rootBase = this.constraints.stream()
                .filter(constraint -> !(targetId != null && targetId.equals(constraint.id())
                        && "heat_network".equals(constraint.type()) && !constraint.rule().isForbidden()))
                .collect(Collectors.toList());
        this.rootConstraints = rules.applicableConstraints(rootBase, Set.of(), root, root);
        strictIndex = rules.index(this.constraints, traversal);
        rootIndex = rules.index(rootConstraints, traversal);
    }

    boolean pointAllowed(Coordinate point) {
        requireFinite(point);
        return !rules.pointInsideForbiddenClearance(point, strictIndex);
    }

    boolean edgeAllowed(Coordinate from, Coordinate to) {
        requireFinite(from);
        requireFinite(to);
        boolean atRoot = isRoot(from) || isRoot(to);
        ConstraintIndex index = atRoot ? rootIndex : strictIndex;
        if (rules.pointInsideForbiddenClearance(from, index)
                || rules.pointInsideForbiddenClearance(to, index) || !rules.segmentAllowed(from, to, index)) return false;
        if (!atRoot) return true;
        Coordinate start = isRoot(from) ? from : to;
        Coordinate end = isRoot(from) ? to : from;
        if (!rootDirectionAllowed(start, end)) return false;
        if (rootSetbacks.isEmpty()) return true;
        LineString edge = rules.line(List.of(start, end));
        for (Constraint setback : rootSetbacks) {
            if (!localExitAllowed(edge, start, end, setback)) return false;
        }
        return true;
    }

    /** Только локальные исходящие сегменты в пределах 1 мм; дальние части полилинии не задают луч. */
    private void collectIncidentRays(Geometry source, List<Coordinate> rays) {
        if (source instanceof LineString) {
            LineString line = (LineString) source;
            for (int i = 1; i < line.getNumPoints(); i++) {
                Coordinate from = line.getCoordinateN(i - 1), to = line.getCoordinateN(i);
                LineSegment segment = new LineSegment(from, to);
                double length = segment.getLength();
                if (length <= GEOMETRY_EPSILON_M || segment.distance(root) > ROOT_ROUNDING_TOLERANCE_M) continue;
                double dx = (to.x - from.x) / length, dy = (to.y - from.y) / length;
                // У endpoint существует один луч, внутри сегмента — два. Субмиллиметровый
                // остаток до endpoint не должен создавать противоположный луч из-за округления.
                if (to.distance(root) > ROOT_ROUNDING_TOLERANCE_M) rays.add(new Coordinate(dx, dy));
                if (from.distance(root) > ROOT_ROUNDING_TOLERANCE_M) rays.add(new Coordinate(-dx, -dy));
            }
        } else if (source instanceof GeometryCollection) {
            for (int i = 0; i < source.getNumGeometries(); i++) collectIncidentRays(source.getGeometryN(i), rays);
        }
    }

    private boolean rootDirectionAllowed(Coordinate start, Coordinate end) {
        double length = start.distance(end);
        double dx = (end.x - start.x) / length, dy = (end.y - start.y) / length;
        for (Coordinate ray : rootIncidentRays) {
            // Знак важен: свободное продолжение в противоположную сторону не является наложением.
            if (dx * ray.x + dy * ray.y > MAX_ROOT_RAY_COSINE + DIRECTION_EPSILON) return false;
        }
        return true;
    }

    /**
     * Из фактической (в том числе округлённой) точки корня разрешён прямой выход в конусе ±45°
     * от наружного вектора к ближайшему footprint. Пересечение с буфером — единственный префикс,
     * длиной не более sqrt(2) кратчайшего выхода + 2 мм. Углы ОКС получают радиальную нормаль.
     */
    private boolean localExitAllowed(LineString edge, Coordinate start, Coordinate end, Constraint setback) {
        Geometry startPoint = edge.getFactory().createPoint(start);
        Geometry endPoint = edge.getFactory().createPoint(end);
        Geometry buffer = setback.blocked();
        if (!buffer.covers(startPoint)) {
            // Округление могло вывести корень за отступ: тогда для этого ОКС уже нет исключения.
            return !buffer.covers(endPoint) && rules.segmentAllowed(start, end, List.of(setback));
        }
        if (buffer.covers(endPoint)) return false;
        Coordinate nearest = DistanceOp.nearestPoints(setback.source(), startPoint)[0];
        double nx = start.x - nearest.x, ny = start.y - nearest.y;
        double normalLength = Math.hypot(nx, ny);
        double edgeLength = start.distance(end);
        if (normalLength <= GEOMETRY_EPSILON_M) return false;
        double outwardCosine = (nx / normalLength) * ((end.x - start.x) / edgeLength)
                + (ny / normalLength) * ((end.y - start.y) / edgeLength);
        if (outwardCosine < MIN_OUTWARD_COSINE - DIRECTION_EPSILON) return false;
        Geometry inside = edge.intersection(buffer);
        if (inside.isEmpty() || inside.getNumGeometries() != 1
                || inside.distance(startPoint) > GEOMETRY_EPSILON_M) return false;
        double shortestExit = buffer.getBoundary().distance(startPoint);
        return inside.getLength() <= MAX_EXIT_STRETCH * shortestExit + EXIT_ROUNDING_MARGIN_M;
    }

    RoutePath path(List<Coordinate> coordinates) {
        return pathAfter(null, coordinates);
    }

    /** Наружный кандидат после реального ввода; окончательный допуск — только после сборки префикса. */
    RoutePath pathAfter(Coordinate previous, List<Coordinate> coordinates) {
        if (coordinates.size() < 2) return null;
        if (previous != null) requireFinite(previous);
        coordinates.forEach(PreparedCorridor::requireFinite);
        List<Coordinate> rounded = coordinates.stream()
                .map(point -> new RouteCoordinate(point.x, point.y).toCoordinate()).collect(Collectors.toList());
        // Один подход у конечной точки, не повторное получение исключения в середине маршрута.
        for (int i = 1; i < rounded.size() - 1; i++) if (isRoot(rounded.get(i))) return null;
        for (int i = 1; i < rounded.size(); i++) {
            if (!edgeAllowed(rounded.get(i - 1), rounded.get(i))) return null;
        }
        LineString line = rules.line(rounded);
        boolean atRoot = isRoot(rounded.get(0)) || isRoot(rounded.get(rounded.size() - 1));
        // Видимость звена допускает часть crossing; готовый путь обязан содержать весь special.
        ConstraintIndex index = atRoot ? rootIndex : strictIndex;
        if (previous == null) {
            if (!rules.lineAllowed(line, index)) return null;
        } else {
            if (!rules.provisionalSegmentsAllowed(line, index)) return null;
            List<Coordinate> complete = new ArrayList<>();
            complete.add(new RouteCoordinate(previous.x, previous.y).toCoordinate());
            complete.addAll(rounded);
            LineString fullLine = rules.line(complete);
            if (!rules.completeRoadCrossingsAllowed(fullLine, index)) return null;
        }
        return new RoutePath(rounded, rules.sections(line, atRoot ? rootConstraints : constraints, index.traversal()), line.getLength());
    }

    private boolean isRoot(Coordinate point) { return point.distance(root) <= ROOT_ROUNDING_TOLERANCE_M; }

    private static void requireFinite(Coordinate point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) {
            throw new IllegalArgumentException("Corridor XY coordinates must be finite");
        }
    }
}
