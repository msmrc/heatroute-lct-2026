package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import ru.lct.heatroute.domain.routing.ExpertChamberGeometryRules.PolylineSummary;

/** Проверяет углы и расстояния между поворотами, включая стыки технических рёбер. */
public final class ExpertRouteBendRules {
    private static final double ENDPOINT_TOLERANCE_M = 0.01;
    private static final double NUMERICAL_EPSILON_M = 1e-7;

    private ExpertRouteBendRules() { }

    public static List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges) {
        Map<String, PolylineSummary> summaries = new HashMap<>();
        for (RouteEdge edge : edges) summaries.put(edge.getId(), ExpertChamberGeometryRules.summarize(edge.getCoordinates()));
        return validate(nodes, edges, edge -> summaries.get(edge.getId()));
    }

    /** Проверяет непрерывные цепочки без рекурсии и без загрузки промежуточных координат в память. */
    public static List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges,
            Function<RouteEdge, PolylineSummary> summaries) {
        Map<String, RouteNode> byId = new LinkedHashMap<>();
        Map<String, List<RouteEdge>> incident = new HashMap<>();
        Map<String, RouteValidationIssue> issues = new LinkedHashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (RouteNode node : nodes) {
            if (byId.putIfAbsent(node.getId(), node) != null) return List.of(undefined(node.getId()));
        }
        for (RouteEdge edge : edges) {
            ensureActive();
            if (!edgeIds.add(edge.getId()) || !byId.containsKey(edge.getUpstreamNodeId())
                    || !byId.containsKey(edge.getDownstreamNodeId())) return List.of(undefined(edge.getId()));
            incident.computeIfAbsent(edge.getUpstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
            incident.computeIfAbsent(edge.getDownstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
            PolylineSummary summary = summaries.apply(edge);
            if (summary == null) add(issues, undefined(edge.getId()));
            else {
                if (summary.hasInvalidBendAngle()) add(issues, badAngle(edge.getId()));
                Double minimum = minimumSpacing(edge);
                if (minimum == null) add(issues, undefined(edge.getId()));
                else if (summary.getMinimumBendSpacingM() + NUMERICAL_EPSILON_M < minimum) {
                    add(issues, tooClose(edge.getId(), minimum));
                }
            }
        }
        Set<String> visited = new HashSet<>();
        for (RouteNode node : nodes) {
            List<RouteEdge> connected = incident.getOrDefault(node.getId(), List.of());
            if (!node.isChamber() && connected.size() == 2) continue;
            for (RouteEdge edge : connected) {
                if (!visited.contains(edge.getId())) walk(node, edge, byId, incident, summaries, visited, issues);
            }
        }
        // Корректный граф — лес; замкнутую непроверяемую цепочку не допускаем молча.
        for (RouteEdge edge : edges) if (!visited.contains(edge.getId())) add(issues, undefined(edge.getId()));
        List<RouteValidationIssue> result = new ArrayList<>(issues.values());
        result.sort(Comparator.comparing(RouteValidationIssue::getCode)
                .thenComparing(issue -> issue.getSubjectId() == null ? "" : issue.getSubjectId()));
        return List.copyOf(result);
    }

    private static void walk(RouteNode start, RouteEdge edge, Map<String, RouteNode> nodes,
            Map<String, List<RouteEdge>> incident, Function<RouteEdge, PolylineSummary> summaries,
            Set<String> visited, Map<String, RouteValidationIssue> issues) {
        RouteNode at = start;
        Direction arrival = null;
        double distanceSinceBendM = Double.POSITIVE_INFINITY;
        double lastBendMinimumM = 0;
        while (visited.add(edge.getId())) {
            ensureActive();
            PolylineSummary summary = summaries.apply(edge);
            Double edgeMinimumM = minimumSpacing(edge);
            Direction outward = at(summary, at);
            String nextId = edge.getUpstreamNodeId().equals(at.getId()) ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId();
            RouteNode next = nodes.get(nextId);
            Direction far = at(summary, next);
            if (outward == null || far == null || edgeMinimumM == null) {
                add(issues, undefined(edge.getId()));
                return;
            }
            boolean turnAtNode = false;
            if (arrival != null && !ExpertChamberGeometryRules.straightDirections(arrival.dx, arrival.dy, outward.dx, outward.dy)) {
                turnAtNode = true;
                if (!ExpertChamberGeometryRules.allowsBend(arrival.dx, arrival.dy, outward.dx, outward.dy)) {
                    add(issues, badAngle(at.getId()));
                }
                double required = Math.max(arrival.minimumSpacingM, edgeMinimumM);
                checkSpacing(distanceSinceBendM, Math.max(lastBendMinimumM, required), at.getId(), issues);
                distanceSinceBendM = 0;
                lastBendMinimumM = required;
            }
            if (Double.isFinite(summary.getFirstBendDistanceM())) {
                checkSpacing(
                        distanceSinceBendM + summary.getFirstBendDistanceM(),
                        Math.max(lastBendMinimumM, edgeMinimumM),
                        edge.getId(),
                        issues);
                distanceSinceBendM = summary.getLastBendDistanceM();
                lastBendMinimumM = edgeMinimumM;
            } else if (Double.isFinite(distanceSinceBendM)) {
                distanceSinceBendM += summary.getActualLengthM();
            }
            List<RouteEdge> connected = incident.getOrDefault(nextId, List.of());
            if (next.isChamber() || connected.size() != 2) return;
            // Техническое разбиение не должно обнулять накопленное направление прямого хода:
            // последовательные отклонения меньше погрешности могут вместе образовать реальный изгиб.
            arrival = arrival != null && !turnAtNode && !Double.isFinite(outward.nearestBendM)
                    ? new Direction(
                            arrival.dx + outward.dx,
                            arrival.dy + outward.dy,
                            Double.NaN,
                            edgeMinimumM)
                    : new Direction(-far.dx, -far.dy, Double.NaN, edgeMinimumM);
            at = next;
            edge = connected.get(0) == edge ? connected.get(1) : connected.get(0);
        }
        add(issues, undefined(at.getId()));
    }

    private static Direction at(PolylineSummary summary, RouteNode node) {
        if (summary == null || node == null) return null;
        double first = node.getCoordinate().toCoordinate().distance(summary.getFirstCoordinate().toCoordinate());
        double last = node.getCoordinate().toCoordinate().distance(summary.getLastCoordinate().toCoordinate());
        if (first < last && first <= ENDPOINT_TOLERANCE_M) {
            return new Direction(summary.getFirstDx(), summary.getFirstDy(), summary.getFirstBendDistanceM());
        }
        if (last < first && last <= ENDPOINT_TOLERANCE_M) {
            return new Direction(-summary.getLastDx(), -summary.getLastDy(), summary.getLastBendDistanceM());
        }
        return null;
    }

    private static RouteValidationIssue badAngle(String subject) {
        return new RouteValidationIssue("EXPERT_ROUTE_BEND_ANGLE_INVALID", subject,
                "Внутренний угол поворота теплосети должен находиться в диапазоне 90–120°");
    }
    private static RouteValidationIssue tooClose(String subject, double minimumM) {
        return new RouteValidationIssue("EXPERT_ROUTE_BEND_TOO_CLOSE", subject,
                "Соседние повороты должны находиться не ближе " + (int) minimumM
                        + " м по трассе для фактического ДУ");
    }
    private static RouteValidationIssue undefined(String subject) {
        return new RouteValidationIssue("EXPERT_ROUTE_BEND_GEOMETRY_UNCHECKABLE", subject,
                "Нельзя проверить повороты по полной метрической геометрии непрерывного участка");
    }
    private static void add(Map<String, RouteValidationIssue> issues, RouteValidationIssue issue) {
        issues.putIfAbsent(issue.getCode() + "|" + issue.getSubjectId(), issue);
    }
    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Route bend validation cancelled");
    }
    private static Double minimumSpacing(RouteEdge edge) {
        if (edge.getDiameter() == null) return null;
        try {
            return ExpertChamberGeometryRules.minimumBendDistanceM(edge.getDiameter());
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }
    private static void checkSpacing(
            double actualM,
            double requiredM,
            String subject,
            Map<String, RouteValidationIssue> issues) {
        if (Double.isFinite(actualM) && actualM + NUMERICAL_EPSILON_M < requiredM) {
            add(issues, tooClose(subject, requiredM));
        }
    }
    private static final class Direction {
        private final double dx, dy, nearestBendM, minimumSpacingM;
        private Direction(double dx, double dy, double nearestBendM) {
            this(dx, dy, nearestBendM, 0);
        }
        private Direction(double dx, double dy, double nearestBendM, double minimumSpacingM) {
            this.dx = dx;
            this.dy = dy;
            this.nearestBendM = nearestBendM;
            this.minimumSpacingM = minimumSpacingM;
        }
    }
}
