package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Проверяет обязательное изменение направления не более 90° по §2.1 приложения организатора.
 * Одинаково проверяет внутренние вершины и продолжение через узел степени два, независимо
 * от направления записи геометрии. Углы между ветвями камер степени три/четыре не ограничивает.
 */
public final class OfficialRouteDeflectionRules {
    private static final double RIGHT_ANGLE = Math.PI / 2.0;
    private static final double ENDPOINT_TOLERANCE_M = 0.01;
    // Каждая ордината округлена до 0.001 м: ошибка разности двух точек <= sqrt(2) * 0.001 м.
    private static final double VECTOR_ROUNDING_ERROR_M = Math.sqrt(2.0) * 0.001;
    // Только верхний ограничитель вычисленной погрешности, а не безусловный допуск 0.1°.
    // На миллиметровых отрезках оценка погрешности иначе могла бы разрешить даже разворот.
    private static final double MAX_ROUNDING_TOLERANCE = Math.toRadians(0.1);
    private static final double FLOATING_POINT_TOLERANCE = 1e-12;
    private static final double MAX_EXCESS_SINE = Math.sin(MAX_ROUNDING_TOLERANCE + FLOATING_POINT_TOLERANCE);

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

    /** Один проход по координатам: память не зависит от числа вершин; результат хранит лишь крайние лучи. */
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
        return new EdgeDirections(first, previous, firstDirection, incoming.reversed());
    }

    private static boolean exceedsRightAngle(Vector incoming, Vector outgoing) {
        return !allowsTurn(incoming.dx, incoming.dy, outgoing.dx, outgoing.dy);
    }

    /** Тот же допуск для горячего поиска; векторы в метрах получены из миллиметровых координат. */
    static boolean allowsTurn(double inX, double inY, double outX, double outY) {
        if (!Double.isFinite(inX) || !Double.isFinite(inY) || !Double.isFinite(outX) || !Double.isFinite(outY)
                || inX == 0 && inY == 0 || outX == 0 && outY == 0) return false;
        // Для 0..90° не нужны ни тригонометрия, ни вычисление допуска округления.
        double dot = inX * outX + inY * outY;
        if (dot >= 0) return true;
        // L1-нормы не меньше евклидовых: это консервативный отказ за пределами общего
        // верхнего допуска. Точная тригонометрия остаётся лишь в узкой полосе около 90°.
        if (-dot > MAX_EXCESS_SINE * (Math.abs(inX) + Math.abs(inY)) * (Math.abs(outX) + Math.abs(outY))) return false;
        double inLength = Math.hypot(inX, inY), outLength = Math.hypot(outX, outY);
        if (!Double.isFinite(inLength) || !Double.isFinite(outLength)) return false;
        double ax = inX / inLength, ay = inY / inLength, bx = outX / outLength, by = outY / outLength;
        double angle = Math.atan2(Math.abs(ax * by - ay * bx), ax * bx + ay * by);
        double rounding = Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / inLength))
                + Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / outLength));
        return angle <= RIGHT_ANGLE + Math.min(MAX_ROUNDING_TOLERANCE, rounding) + FLOATING_POINT_TOLERANCE;
    }

    private static double angle(Vector incoming, Vector outgoing) {
        double ax = incoming.dx / incoming.length, ay = incoming.dy / incoming.length;
        double bx = outgoing.dx / outgoing.length, by = outgoing.dy / outgoing.length;
        return Math.atan2(Math.abs(ax * by - ay * bx), ax * bx + ay * by);
    }

    private static RouteValidationIssue exceeded(String subject, Vector incoming, Vector outgoing, String location) {
        return new RouteValidationIssue("ROUTE_DEFLECTION_EXCEEDED", subject, String.format(Locale.ROOT,
                "Direction change %s is %.6f degrees; maximum is 90 degrees (official appendix 2.1)",
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
        private EdgeDirections(RouteCoordinate firstPoint, RouteCoordinate lastPoint, Vector first, Vector last) {
            this.firstPoint = firstPoint; this.lastPoint = lastPoint; this.first = first; this.last = last;
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
