package ru.lct.heatroute.domain.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Проверяет уточнение Евгения от 25.09.2026: ввод ОКС начинается в камере,
 * а между последовательными камерами требуется 10 м по фактической полилинии EPSG:32637.
 * Это отдельное экспертное правило, не правило организатора о переиспользовании камеры у врезки.
 */
public final class ExpertChamberRouteValidator {
    public static final double MIN_CHAMBER_SECTION_LENGTH_M = 10.0;
    // Только погрешность double при суммировании, не допуск округления длины до миллиметров.
    private static final double NUMERICAL_EPSILON_M = 1e-7;

    /** Обходит лес за O(V + E + число координат); технические вершины не обнуляют длину участка. */
    public List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges) {
        return validate(nodes, edges, this::actualLengthM);
    }

    /**
     * Принимает измеренные по фактическим полилиниям EPSG:32637 длины из потокового адаптера.
     * Provider не должен подставлять заявленную length_m; непроверяемой геометрии соответствует NaN.
     */
    public List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges,
            ToDoubleFunction<RouteEdge> measuredLengthM) {
        Objects.requireNonNull(measuredLengthM, "Measured geometry length provider is required");
        Map<String, RouteNode> byId = new LinkedHashMap<>();
        for (RouteNode node : nodes) {
            if (node.getId() == null || byId.putIfAbsent(node.getId(), node) != null) {
                return topologyIssue(node.getId());
            }
        }
        Map<String, RouteEdge> incoming = new HashMap<>();
        Map<String, List<RouteEdge>> outgoing = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (RouteEdge edge : edges) {
            if (edge.getId() == null || !edgeIds.add(edge.getId())
                    || !byId.containsKey(edge.getUpstreamNodeId()) || !byId.containsKey(edge.getDownstreamNodeId())
                    || incoming.putIfAbsent(edge.getDownstreamNodeId(), edge) != null) {
                return topologyIssue(edge.getId());
            }
            outgoing.computeIfAbsent(edge.getUpstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
        }
        ArrayDeque<PathState> pending = new ArrayDeque<>();
        for (RouteNode node : nodes) {
            if (!incoming.containsKey(node.getId())) pending.add(new PathState(node, null, 0));
        }
        List<RouteValidationIssue> issues = new ArrayList<>();
        int visited = 0;
        while (!pending.isEmpty()) {
            PathState state = pending.removeFirst();
            RouteNode node = state.node;
            visited++;
            List<RouteEdge> children = outgoing.getOrDefault(node.getId(), List.of());
            String chamberId = state.chamberId;
            double lengthM = state.lengthM;
            if (node.isChamber()) {
                validateSection(state, issues);
                chamberId = node.getId();
                lengthM = 0;
            } else if (children.size() > 1) {
                issues.add(new RouteValidationIssue("EXPERT_OKS_BRANCH_WITHOUT_CHAMBER", node.getId(),
                        "Ответвление к ОКС должно начинаться в тепловой камере, а не в техническом узле"));
                chamberId = null;
            } else if ("demand_connection".equals(node.getNodeType()) && incoming.containsKey(node.getId())
                    && chamberId == null) {
                issues.add(new RouteValidationIssue("EXPERT_OKS_BRANCH_WITHOUT_CHAMBER", node.getId(),
                        "Участок к точке подключения ОКС не начинается в тепловой камере"));
            }
            for (RouteEdge edge : children) {
                pending.addLast(new PathState(byId.get(edge.getDownstreamNodeId()), chamberId,
                        lengthM + measuredLengthM.applyAsDouble(edge)));
            }
        }
        if (visited != nodes.size()) issues.addAll(topologyIssue(null));
        return List.copyOf(issues);
    }

    private void validateSection(PathState state, List<RouteValidationIssue> issues) {
        if (state.chamberId == null) return;
        if (!Double.isFinite(state.lengthM)) {
            issues.add(new RouteValidationIssue("EXPERT_CHAMBER_LENGTH_UNCHECKABLE", state.node.getId(),
                    "Нельзя проверить длину участка между камерами: отсутствует полная метрическая геометрия"));
        } else if (state.lengthM + NUMERICAL_EPSILON_M < MIN_CHAMBER_SECTION_LENGTH_M) {
            issues.add(new RouteValidationIssue("EXPERT_CHAMBER_SPACING_TOO_SHORT", state.node.getId(),
                    "Участок от камеры " + state.chamberId + " до камеры " + state.node.getId()
                            + " имеет длину " + state.lengthM + " м, требуется не менее 10 м"));
        }
    }

    private double actualLengthM(RouteEdge edge) {
        List<RouteCoordinate> coordinates = edge.getCoordinates();
        if (coordinates.size() < 2) return Double.NaN;
        double lengthM = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            RouteCoordinate previous = coordinates.get(i - 1);
            RouteCoordinate current = coordinates.get(i);
            lengthM += Math.hypot(current.getXM().doubleValue() - previous.getXM().doubleValue(),
                    current.getYM().doubleValue() - previous.getYM().doubleValue());
        }
        return lengthM;
    }

    private List<RouteValidationIssue> topologyIssue(String subjectId) {
        return List.of(new RouteValidationIssue("EXPERT_CHAMBER_TOPOLOGY_UNCHECKABLE", subjectId,
                "Для проверки камер нужен лес с уникальными узлами, рёбрами и одним входом в узел"));
    }

    private static final class PathState {
        private final RouteNode node;
        private final String chamberId;
        private final double lengthM;

        private PathState(RouteNode node, String chamberId, double lengthM) {
            this.node = node;
            this.chamberId = chamberId;
            this.lengthM = lengthM;
        }
    }
}
