package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Проверяет актуальный диапазон внутреннего угла поворота 90–120°: изменение направления 60–90°.
 * Одинаково проверяет внутренние вершины и продолжение через узел степени два, независимо
 * от направления записи геометрии. Углы между ветвями камер степени три/четыре не ограничивает.
 */
public final class OfficialRouteDeflectionRules {
    private static final double RIGHT_ANGLE = Math.PI / 2.0;
    private static final double MINIMUM_TURN = Math.PI / 3.0;
    private static final double ENDPOINT_TOLERANCE_M = 0.01;
    // Каждая ордината округлена до 0.001 м: ошибка разности двух точек <= sqrt(2) * 0.001 м.
    private static final double VECTOR_ROUNDING_ERROR_M = Math.sqrt(2.0) * 0.001;
    // Только верхний ограничитель вычисленной погрешности, а не безусловный допуск 0.1°.
    // На миллиметровых отрезках оценка погрешности иначе могла бы разрешить даже разворот.
    private static final double MAX_ROUNDING_TOLERANCE = Math.toRadians(0.1);
    // Направления, отличающиеся не более чем на 0.5°, считаем одной проектной осью.
    // Это согласовано с инженерной оценкой маршрута и не ослабляет диапазон фактических
    // поворотов 60–90°: допуск применяется только около коллинеарного продолжения.
    private static final double COLLINEAR_TOLERANCE = Math.toRadians(0.5);
    private static final double FLOATING_POINT_TOLERANCE = 1e-12;

    private OfficialRouteDeflectionRules() { }

    /** Чистая проверка итоговых метрических координат; не заменяет проверку топологии и препятствий. */
    public static List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges) {
        if (nodes == null || edges == null) throw new IllegalArgumentException("Route nodes and edges are required");
        ensureActive();
        List<RouteValidationIssue> issues = new ArrayList<>();
        Map<String, RouteNode> nodeById = new HashMap<>();
        for (RouteNode node : nodes) {
            ensureActive();
            nodeById.putIfAbsent(node.getId(), node);
        }
        List<EdgeEndpoints> endpoints = new ArrayList<>();
        for (RouteEdge edge : edges) {
            ensureActive();
            RouteNode upstream = nodeById.get(edge.getUpstreamNodeId());
            RouteNode downstream = nodeById.get(edge.getDownstreamNodeId());
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            if (coordinates.isEmpty() && upstream != null && downstream != null) {
                coordinates = List.of(upstream.getCoordinate(), downstream.getCoordinate());
            }
            PolylineCheck check = validatePolyline(edge.getId(), coordinates);
            issues.addAll(check.getIssues());
            endpoints.add(check.endpoints(edge.getUpstreamNodeId(), edge.getDownstreamNodeId()));
        }
        issues.addAll(validateDegreeTwoNodes(nodes, endpoints));
        return sorted(issues);
    }

    /** Один проход по координатам: память не зависит от числа вершин; результат хранит крайние лучи и длину. */
    public static PolylineCheck validatePolyline(String edgeId, Iterable<RouteCoordinate> coordinates) {
        if (coordinates == null) throw new IllegalArgumentException("Route coordinates are required");
        ensureActive();
        List<RouteValidationIssue> issues = new ArrayList<>();
        EdgeDirections directions = directions(edgeId, coordinates, issues);
        return new PolylineCheck(issues, directions);
    }

    /** Проверяет только продолжения в узлах по сводкам концов, не читая полилинии повторно. */
    public static List<RouteValidationIssue> validateDegreeTwoNodes(List<RouteNode> nodes, Iterable<EdgeEndpoints> endpoints) {
        if (nodes == null || endpoints == null) throw new IllegalArgumentException("Nodes and edge endpoints are required");
        ensureActive();
        List<RouteValidationIssue> issues = new ArrayList<>();
        Map<String, Incidence> incident = new HashMap<>();
        for (RouteNode node : nodes) {
            ensureActive();
            incident.putIfAbsent(node.getId(), new Incidence(node));
        }
        for (EdgeEndpoints edge : endpoints) {
            ensureActive();
            Incidence upstream = incident.get(edge.upstreamNodeId);
            Incidence downstream = incident.get(edge.downstreamNodeId);
            if (upstream != null) upstream.add(edge.directions == null ? null : edge.directions.at(upstream.node.getCoordinate()));
            if (downstream != null) downstream.add(edge.directions == null ? null : edge.directions.at(downstream.node.getCoordinate()));
        }
        for (Incidence entry : incident.values()) {
            ensureActive();
            if (entry.count != 2) continue;
            // Старые примыкания учитываются только у корневой камеры. Чужое/отрицательное
            // поле baseIncidentSections не должно скрыть сквозной поворот технического узла.
            if (entry.node.isRoot() && entry.node.isChamber() && entry.node.getBaseIncidentSections() > 0) continue;
            if (entry.first == null || entry.second == null) {
                issues.add(undefined(entry.node.getId(), "Cannot determine both directions at a degree-two node"));
            } else {
                Vector incoming = entry.first.reversed();
                if (exceedsRightAngle(incoming, entry.second)) {
                    issues.add(exceeded(entry.node.getId(), incoming, entry.second, "through degree-two node"));
                }
            }
        }
        return sorted(issues);
    }

    private static List<RouteValidationIssue> sorted(List<RouteValidationIssue> issues) {
        ensureActive();
        issues.sort(Comparator.comparing(RouteValidationIssue::getCode)
                .thenComparing(i -> i.getSubjectId() == null ? "" : i.getSubjectId()));
        return List.copyOf(issues);
    }

    private static EdgeDirections directions(String edgeId, Iterable<RouteCoordinate> coordinates,
            List<RouteValidationIssue> issues) {
        RouteCoordinate first = null;
        RouteCoordinate previous = null;
        Vector firstDirection = null;
        Vector incoming = null;
        boolean reported = false;
        long index = -1;
        double actualLengthM = 0;
        for (RouteCoordinate point : coordinates) {
            ensureActive();
            index++;
            if (point == null) {
                issues.add(undefined(edgeId, "Route geometry contains a missing coordinate"));
                return null;
            }
            if (previous == null) {
                first = point;
                previous = point;
                continue;
            }
            Vector outgoing = Vector.between(previous, point);
            // Убираем только точные повторы, не короткие положительные участки.
            if (outgoing.length == 0.0) continue;
            if (!Double.isFinite(outgoing.length)) {
                issues.add(undefined(edgeId, "Route direction must be finite"));
                return null;
            }
            actualLengthM += outgoing.length;
            if (firstDirection == null) firstDirection = outgoing;
            if (!reported && incoming != null && exceedsRightAngle(incoming, outgoing)) {
                issues.add(exceeded(edgeId, incoming, outgoing, "at internal vertex before coordinate " + index));
                reported = true;
            }
            incoming = outgoing;
            previous = point;
        }
        ensureActive();
        if (incoming == null) {
            issues.add(undefined(edgeId, "Route geometry needs at least two distinct coordinates"));
            return null;
        }
        return new EdgeDirections(first, previous, firstDirection, incoming.reversed(), actualLengthM);
    }

    private static boolean exceedsRightAngle(Vector incoming, Vector outgoing) {
        return !allowsTurn(incoming.dx, incoming.dy, outgoing.dx, outgoing.dy);
    }

    /** Тот же допуск для горячего поиска; векторы в метрах получены из миллиметровых координат. */
    static boolean allowsTurn(double inX, double inY, double outX, double outY) {
        return allowsTurn(inX, inY, outX, outY, true);
    }

    /** Одно входное направление используется во всех переходах одного состояния поиска. */
    static PreparedDirection prepareDirection(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x == 0 && y == 0) {
            return PreparedDirection.INVALID;
        }
        double length = Math.hypot(x, y);
        if (!Double.isFinite(length)) return PreparedDirection.INVALID;
        return new PreparedDirection(x / length, y / length,
                Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / length)), true);
    }

    /** Внутренние угловые области проверяет без тригонометрии; границы идут прежним путём. */
    static boolean allowsTurn(PreparedDirection incoming, double outX, double outY) {
        if (!incoming.valid || !Double.isFinite(outX) || !Double.isFinite(outY)
                || outX == 0 && outY == 0) return false;
        double outScale = Math.max(Math.abs(outX), Math.abs(outY));
        double inScale = Math.max(Math.abs(incoming.x), Math.abs(incoming.y));
        if (outScale >= 0x1.0p-256 && outScale <= 0x1.0p256
                && inScale >= 0.5 && inScale <= 2.0) {
            double dotFloor = inScale * outScale / 16.0;
            double rawCross = Math.abs(incoming.x * outY - incoming.y * outX);
            double rawDot = incoming.x * outX + incoming.y * outY;
            // Floor сохраняет знак dot и ограничивает ошибку; эти конусы удалены
            // от границ правил более чем на 3°. См. ROUTING_ANGLE_SHORTCUT_PROOF.md.
            if (rawDot < 0 && -rawDot * 16 >= rawCross && -rawDot >= dotFloor) return false;
            if (rawDot > 0 && rawCross > 0 && rawCross <= 1.5 * rawDot
                    && rawCross * 32 >= rawDot && rawDot >= dotFloor) return false;
            if (rawDot > 0 && rawCross >= 2 * rawDot && rawDot >= dotFloor) return true;
        }
        double outLength = Math.hypot(outX, outY);
        if (!Double.isFinite(outLength)) return false;
        double bx = outX / outLength, by = outY / outLength;
        double cross = Math.abs(incoming.x * by - incoming.y * bx);
        double dot = incoming.x * bx + incoming.y * by;
        // Только внутренние области текущих правил: спорные границы идут в прежний atan2.
        // 135..180 и atan(1/32)..45 не допускаются даже при максимальной погрешности.
        if (dot < 0 && -dot >= cross) return false;
        if (dot > 0 && cross > 0 && cross <= dot && cross * 32 >= dot) return false;
        // atan(2)..90 допустимы; ошибка atan2 около 90 меньше прежнего FP-допуска 1e-12.
        if (dot >= 0 && cross > 0 && cross >= 2 * dot) return true;
        double angle = Math.atan2(cross, dot);
        double rounding = incoming.rounding
                + Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / outLength));
        return allowsAngle(angle, rounding, true);
    }

    /**
     * Для двух отдельных лучей камеры почти прямое продолжение допускается только в пределах
     * погрешности миллиметровых координат. Допуск 0,5° относится к одной оцифрованной оси,
     * а не разрешает два разных выхода из камеры в практически совпадающем направлении.
     */
    static boolean allowsJunctionContinuation(double inX, double inY, double outX, double outY) {
        return allowsTurn(inX, inY, outX, outY, false);
    }

    private static boolean allowsTurn(double inX, double inY, double outX, double outY,
            boolean digitizedAxisTolerance) {
        if (!Double.isFinite(inX) || !Double.isFinite(inY) || !Double.isFinite(outX) || !Double.isFinite(outY)
                || inX == 0 && inY == 0 || outX == 0 && outY == 0) return false;
        double inLength = Math.hypot(inX, inY), outLength = Math.hypot(outX, outY);
        if (!Double.isFinite(inLength) || !Double.isFinite(outLength)) return false;
        double ax = inX / inLength, ay = inY / inLength, bx = outX / outLength, by = outY / outLength;
        double angle = Math.atan2(Math.abs(ax * by - ay * bx), ax * bx + ay * by);
        double rounding = Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / inLength))
                + Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / outLength));
        return allowsAngle(angle, rounding, digitizedAxisTolerance);
    }

    private static boolean allowsAngle(double angle, double rounding, boolean digitizedAxisTolerance) {
        double tolerance = Math.min(MAX_ROUNDING_TOLERANCE, rounding) + FLOATING_POINT_TOLERANCE;
        // Коллинеарное продолжение не является поворотом. Любой фактический поворот должен
        // соответствовать внутреннему углу 90–120°, то есть отклонению 60–90°.
        double collinearTolerance = digitizedAxisTolerance
                ? COLLINEAR_TOLERANCE + FLOATING_POINT_TOLERANCE : tolerance;
        return angle <= collinearTolerance
                || angle + tolerance >= MINIMUM_TURN && angle <= RIGHT_ANGLE + tolerance;
    }

    /** Неизменяемая нормализация и погрешность одного луча, без кеша между поисками. */
    static final class PreparedDirection {
        private static final PreparedDirection INVALID = new PreparedDirection(0, 0, 0, false);
        private final double x;
        private final double y;
        private final double rounding;
        private final boolean valid;

        private PreparedDirection(double x, double y, double rounding, boolean valid) {
            this.x = x;
            this.y = y;
            this.rounding = rounding;
            this.valid = valid;
        }
    }

    private static double angle(Vector incoming, Vector outgoing) {
        double ax = incoming.dx / incoming.length, ay = incoming.dy / incoming.length;
        double bx = outgoing.dx / outgoing.length, by = outgoing.dy / outgoing.length;
        return Math.atan2(Math.abs(ax * by - ay * bx), ax * bx + ay * by);
    }

    private static RouteValidationIssue exceeded(String subject, Vector incoming, Vector outgoing, String location) {
        return new RouteValidationIssue("ROUTE_DEFLECTION_EXCEEDED", subject, String.format(Locale.ROOT,
                "Direction change %s is %.6f degrees; a bend must be 60–90 degrees (internal 90–120)",
                location, Math.toDegrees(angle(incoming, outgoing))));
    }

    private static RouteValidationIssue undefined(String subject, String reason) {
        return new RouteValidationIssue("ROUTE_DEFLECTION_UNDEFINED", subject, reason);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Route deflection validation cancelled");
    }

    /** Ограниченный результат потоковой проверки одной полилинии. */
    public static final class PolylineCheck {
        private final List<RouteValidationIssue> issues;
        private final EdgeDirections directions;
        private PolylineCheck(List<RouteValidationIssue> issues, EdgeDirections directions) {
            this.issues = List.copyOf(issues); this.directions = directions;
        }
        public List<RouteValidationIssue> getIssues() { return issues; }
        /** Фактическая длина в метрах без округления; NaN при неопределённой геометрии. */
        public double getActualLengthM() { return directions == null ? Double.NaN : directions.actualLengthM; }
        public EdgeEndpoints endpoints(String upstreamNodeId, String downstreamNodeId) {
            return new EdgeEndpoints(upstreamNodeId, downstreamNodeId, directions);
        }
    }

    /** Два крайних луча и инцидентность ребра; промежуточных вершин здесь нет. */
    public static final class EdgeEndpoints {
        private final String upstreamNodeId;
        private final String downstreamNodeId;
        private final EdgeDirections directions;
        private EdgeEndpoints(String upstreamNodeId, String downstreamNodeId, EdgeDirections directions) {
            this.upstreamNodeId = upstreamNodeId; this.downstreamNodeId = downstreamNodeId; this.directions = directions;
        }
    }

    private static final class Vector {
        private final double dx;
        private final double dy;
        private final double length;
        private Vector(double dx, double dy) { this.dx = dx; this.dy = dy; this.length = Math.hypot(dx, dy); }
        private static Vector between(RouteCoordinate from, RouteCoordinate to) {
            // Вычитание до double сохраняет миллиметровую точность при больших UTM-координатах.
            return new Vector(to.getXM().subtract(from.getXM()).doubleValue(), to.getYM().subtract(from.getYM()).doubleValue());
        }
        private Vector reversed() { return new Vector(-dx, -dy); }
    }

    private static final class EdgeDirections {
        private final RouteCoordinate firstPoint;
        private final RouteCoordinate lastPoint;
        private final Vector first;
        private final Vector last;
        private final double actualLengthM;
        private EdgeDirections(RouteCoordinate firstPoint, RouteCoordinate lastPoint, Vector first, Vector last,
                double actualLengthM) {
            this.firstPoint = firstPoint; this.lastPoint = lastPoint; this.first = first; this.last = last;
            this.actualLengthM = actualLengthM;
        }
        private Vector at(RouteCoordinate node) {
            double firstDistance = Vector.between(node, firstPoint).length;
            double lastDistance = Vector.between(node, lastPoint).length;
            if (firstDistance < lastDistance && firstDistance <= ENDPOINT_TOLERANCE_M) return first;
            if (lastDistance < firstDistance && lastDistance <= ENDPOINT_TOLERANCE_M) return last;
            return null;
        }
    }

    private static final class Incidence {
        private final RouteNode node;
        private int count;
        private Vector first;
        private Vector second;
        private Incidence(RouteNode node) { this.node = node; }
        private void add(Vector direction) {
            if (count == 0) first = direction;
            if (count == 1) second = direction;
            count++;
        }
    }
}
