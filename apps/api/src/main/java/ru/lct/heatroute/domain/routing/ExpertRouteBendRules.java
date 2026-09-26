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

/** Проверяет изменение направления 0–90° по ТЗ, включая стыки технических рёбер. */
public final class ExpertRouteBendRules {
    private static final double ENDPOINT_TOLERANCE_M = 0.01;

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
        while (visited.add(edge.getId())) {
            ensureActive();
            PolylineSummary summary = summaries.apply(edge);
            Direction outward = at(summary, at);
            String nextId = edge.getUpstreamNodeId().equals(at.getId()) ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId();
            RouteNode next = nodes.get(nextId);
            Direction far = at(summary, next);
            if (outward == null || far == null) { add(issues, undefined(edge.getId())); return; }
            boolean turnAtNode = false;
            if (arrival != null && !ExpertChamberGeometryRules.straightDirections(arrival.dx, arrival.dy, outward.dx, outward.dy)) {
                turnAtNode = true;
                if (!ExpertChamberGeometryRules.allowsBend(arrival.dx, arrival.dy, outward.dx, outward.dy)) {
                    add(issues, badAngle(at.getId()));
                }
            }
            List<RouteEdge> connected = incident.getOrDefault(nextId, List.of());
            if (next.isChamber() || connected.size() != 2) return;
            // Техническое разбиение не должно обнулять накопленное направление прямого хода:
            // последовательные отклонения меньше погрешности могут вместе образовать реальный изгиб.
            arrival = arrival != null && !turnAtNode && !Double.isFinite(outward.nearestBendM)
                    ? new Direction(arrival.dx + outward.dx, arrival.dy + outward.dy, Double.NaN)
                    : new Direction(-far.dx, -far.dy, Double.NaN);
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
                "Изменение направления теплосети не должно превышать 90° по §2.1 ТЗ");
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
    private static final class Direction {
        private final double dx, dy, nearestBendM;
        private Direction(double dx, double dy, double nearestBendM) {
            this.dx = dx; this.dy = dy; this.nearestBendM = nearestBendM;
        }
    }
}
