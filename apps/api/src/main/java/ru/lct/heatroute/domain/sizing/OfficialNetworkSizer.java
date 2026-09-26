package ru.lct.heatroute.domain.sizing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/** Суммирует расходы дерева и назначает минимальные ДУ по расходу и непрерывной длине. */
@Component
public class OfficialNetworkSizer {
    private final OfficialPipeCatalog pipeCatalog;

    public OfficialNetworkSizer(OfficialPipeCatalog pipeCatalog) {
        this.pipeCatalog = pipeCatalog;
    }

    public NetworkSizingResult size(
            List<NetworkTreeEdge> edges,
            Map<String, BigDecimal> demandFlowByNode) {
        checkCancellation();
        List<NetworkSizingIssue> issues = new ArrayList<>();
        Map<String, NetworkTreeEdge> edgeById = new HashMap<>();
        Map<String, NetworkTreeEdge> upstreamEdgeByNode = new HashMap<>();
        Map<String, List<NetworkTreeEdge>> incomingEdgesByNode = new HashMap<>();
        Map<String, List<NetworkTreeEdge>> downstreamEdgesByNode = new HashMap<>();
        Set<String> allNodes = new HashSet<>();

        for (NetworkTreeEdge edge : edges) {
            checkCancellation();
            if (edgeById.putIfAbsent(edge.getId(), edge) != null) {
                issues.add(new NetworkSizingIssue("DUPLICATE_EDGE_ID", edge.getId(), "Edge id must be unique"));
            }
            NetworkTreeEdge previous = upstreamEdgeByNode.putIfAbsent(edge.getDownstreamNodeId(), edge);
            if (previous != null) {
                issues.add(new NetworkSizingIssue(
                        "MULTIPLE_UPSTREAM_EDGES", edge.getId(),
                        "A network node can have at most one upstream edge"));
            }
            incomingEdgesByNode.computeIfAbsent(edge.getDownstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
            downstreamEdgesByNode.computeIfAbsent(edge.getUpstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
            allNodes.add(edge.getUpstreamNodeId());
            allNodes.add(edge.getDownstreamNodeId());
        }
        downstreamEdgesByNode.values().forEach(list -> list.sort(Comparator.comparing(NetworkTreeEdge::getId)));
        incomingEdgesByNode.values().forEach(list -> list.sort(Comparator.comparing(NetworkTreeEdge::getId)));
        Map<String, BigDecimal> totalFlowByNode = new HashMap<>();
        for (Map.Entry<String, BigDecimal> demand : demandFlowByNode.entrySet()) {
            BigDecimal flow = demand.getValue();
            if (flow == null || flow.signum() < 0) {
                issues.add(new NetworkSizingIssue(
                        "INVALID_DEMAND_FLOW", null, "Demand flow must be non-negative: " + demand.getKey()));
                flow = BigDecimal.ZERO;
            }
            totalFlowByNode.put(demand.getKey(), flow);
            allNodes.add(demand.getKey());
        }

        Map<String, BigDecimal> flowByEdge = new HashMap<>();
        List<NetworkTreeEdge> orderedEdges = aggregateFlows(allNodes, incomingEdgesByNode,
                downstreamEdgesByNode, totalFlowByNode, flowByEdge, issues);
        Map<String, Integer> diameters = issues.isEmpty()
                ? SameFlowDiameterAssignment.assign(orderedEdges, upstreamEdgeByNode,
                        downstreamEdgesByNode, flowByEdge, pipeCatalog.entries(), issues)
                : Map.of();
        Map<String, BigDecimal> continuousLengths = continuousLengths(orderedEdges, upstreamEdgeByNode, diameters);
        Map<String, SizedNetworkEdge> sized = new LinkedHashMap<>();
        edges.stream().sorted(Comparator.comparing(NetworkTreeEdge::getId)).forEach(edge -> sized.put(
                edge.getId(), new SizedNetworkEdge(
                        edge.getId(), flowByEdge.getOrDefault(edge.getId(), BigDecimal.ZERO),
                        diameters.get(edge.getId()),
                        continuousLengths.getOrDefault(edge.getId(), edge.getLengthM()))));
        return new NetworkSizingResult(sized, issues);
    }

    /** Обрабатывает листья раньше родителей без рекурсии; остаток после обхода означает цикл. */
    private List<NetworkTreeEdge> aggregateFlows(
            Set<String> allNodes,
            Map<String, List<NetworkTreeEdge>> incoming,
            Map<String, List<NetworkTreeEdge>> downstream,
            Map<String, BigDecimal> totalFlowByNode,
            Map<String, BigDecimal> flowByEdge,
            List<NetworkSizingIssue> issues) {
        Map<String, Integer> remainingChildren = new HashMap<>();
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (String node : allNodes) {
            int children = downstream.getOrDefault(node, List.of()).size();
            remainingChildren.put(node, children);
            if (children == 0) {
                ready.add(node);
            }
        }
        int visited = 0;
        List<NetworkTreeEdge> reverseOrder = new ArrayList<>();
        while (!ready.isEmpty()) {
            checkCancellation();
            String node = ready.remove();
            visited++;
            BigDecimal total = totalFlowByNode.getOrDefault(node, BigDecimal.ZERO);
            for (NetworkTreeEdge edge : incoming.getOrDefault(node, List.of())) {
                flowByEdge.put(edge.getId(), total);
                reverseOrder.add(edge);
                String parent = edge.getUpstreamNodeId();
                totalFlowByNode.merge(parent, total, BigDecimal::add);
                int remaining = remainingChildren.merge(parent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(parent);
                }
            }
        }
        if (visited != allNodes.size()) {
            String cycleNode = remainingChildren.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey).sorted().findFirst().orElse("");
            issues.add(new NetworkSizingIssue("NETWORK_CYCLE", null,
                    "New network contains a cycle at node: " + cycleNode));
        }
        Collections.reverse(reverseOrder);
        return reverseOrder;
    }

    /** DTO хранит префикс от начала текущего ДУ, тогда как выбор ДУ использует максимальный суффикс. */
    private Map<String, BigDecimal> continuousLengths(
            List<NetworkTreeEdge> orderedEdges,
            Map<String, NetworkTreeEdge> upstreamByNode,
            Map<String, Integer> diameters) {
        Map<String, BigDecimal> lengths = new HashMap<>();
        for (NetworkTreeEdge edge : orderedEdges) {
            checkCancellation();
            BigDecimal length = edge.getLengthM();
            Integer diameter = diameters.get(edge.getId());
            NetworkTreeEdge parent = upstreamByNode.get(edge.getUpstreamNodeId());
            if (diameter != null && parent != null && diameter.equals(diameters.get(parent.getId()))) {
                length = length.add(lengths.get(parent.getId()));
            }
            lengths.put(edge.getId(), length);
        }
        return lengths;
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Network sizing cancelled");
        }
    }
}
