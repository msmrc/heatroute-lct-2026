package ru.lct.heatroute.domain.sizing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;

@Component
public class OfficialNetworkSizer {
    private final OfficialPipeCatalog pipeCatalog;

    public OfficialNetworkSizer(OfficialPipeCatalog pipeCatalog) {
        this.pipeCatalog = pipeCatalog;
    }

    public NetworkSizingResult size(
            List<NetworkTreeEdge> edges,
            Map<String, BigDecimal> demandFlowByNode) {
        List<NetworkSizingIssue> issues = new ArrayList<>();
        Map<String, NetworkTreeEdge> edgeById = new HashMap<>();
        Map<String, NetworkTreeEdge> upstreamEdgeByNode = new HashMap<>();
        Map<String, List<NetworkTreeEdge>> downstreamEdgesByNode = new HashMap<>();
        Set<String> allNodes = new HashSet<>();

        for (NetworkTreeEdge edge : edges) {
            if (edgeById.putIfAbsent(edge.getId(), edge) != null) {
                issues.add(new NetworkSizingIssue("DUPLICATE_EDGE_ID", edge.getId(), "Edge id must be unique"));
            }
            NetworkTreeEdge previous = upstreamEdgeByNode.putIfAbsent(edge.getDownstreamNodeId(), edge);
            if (previous != null) {
                issues.add(new NetworkSizingIssue(
                        "MULTIPLE_UPSTREAM_EDGES",
                        edge.getId(),
                        "A network node can have at most one upstream edge"));
            }
            downstreamEdgesByNode.computeIfAbsent(edge.getUpstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
            allNodes.add(edge.getUpstreamNodeId());
            allNodes.add(edge.getDownstreamNodeId());
        }
        downstreamEdgesByNode.values().forEach(list ->
                list.sort(Comparator.comparing(NetworkTreeEdge::getId)));

        for (Map.Entry<String, BigDecimal> demand : demandFlowByNode.entrySet()) {
            if (demand.getValue() == null || demand.getValue().signum() < 0) {
                issues.add(new NetworkSizingIssue(
                        "INVALID_DEMAND_FLOW", null, "Demand flow must be non-negative: " + demand.getKey()));
            }
            allNodes.add(demand.getKey());
        }

        Map<String, BigDecimal> flowByEdge = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        List<String> roots = allNodes.stream()
                .filter(node -> !upstreamEdgeByNode.containsKey(node))
                .sorted()
                .collect(Collectors.toList());
        for (String root : roots) {
            aggregateNode(root, demandFlowByNode, downstreamEdgesByNode, flowByEdge, visiting, visited, issues);
        }
        for (String node : allNodes) {
            if (!visited.contains(node)) {
                aggregateNode(node, demandFlowByNode, downstreamEdgesByNode, flowByEdge, visiting, visited, issues);
            }
        }

        Map<String, Integer> diameterByEdge = new HashMap<>();
        Map<String, BigDecimal> continuousLengthByEdge = new HashMap<>();
        for (String root : roots) {
            selectDiameters(
                    root,
                    null,
                    BigDecimal.ZERO,
                    downstreamEdgesByNode,
                    flowByEdge,
                    diameterByEdge,
                    continuousLengthByEdge,
                    new HashSet<>(),
                    issues);
        }
        for (NetworkTreeEdge edge : edges) {
            if (!diameterByEdge.containsKey(edge.getId())) {
                selectDiameter(edge, null, BigDecimal.ZERO, flowByEdge, diameterByEdge,
                        continuousLengthByEdge, issues);
            }
        }

        Map<String, SizedNetworkEdge> sized = new LinkedHashMap<>();
        edges.stream().sorted(Comparator.comparing(NetworkTreeEdge::getId)).forEach(edge -> sized.put(
                edge.getId(),
                new SizedNetworkEdge(
                        edge.getId(),
                        flowByEdge.getOrDefault(edge.getId(), BigDecimal.ZERO),
                        diameterByEdge.get(edge.getId()),
                        continuousLengthByEdge.getOrDefault(edge.getId(), edge.getLengthM()))));
        return new NetworkSizingResult(sized, issues);
    }

    private BigDecimal aggregateNode(
            String node,
            Map<String, BigDecimal> demands,
            Map<String, List<NetworkTreeEdge>> downstream,
            Map<String, BigDecimal> flowByEdge,
            Set<String> visiting,
            Set<String> visited,
            List<NetworkSizingIssue> issues) {
        if (!visiting.add(node)) {
            issues.add(new NetworkSizingIssue("NETWORK_CYCLE", null, "New network contains a cycle at node: " + node));
            return BigDecimal.ZERO;
        }
        BigDecimal total = demands.getOrDefault(node, BigDecimal.ZERO);
        if (total == null || total.signum() < 0) {
            total = BigDecimal.ZERO;
        }
        for (NetworkTreeEdge edge : downstream.getOrDefault(node, List.of())) {
            BigDecimal childFlow = aggregateNode(
                    edge.getDownstreamNodeId(), demands, downstream, flowByEdge, visiting, visited, issues);
            flowByEdge.put(edge.getId(), childFlow);
            total = total.add(childFlow);
        }
        visiting.remove(node);
        visited.add(node);
        return total;
    }

    private void selectDiameters(
            String node,
            Integer previousDiameter,
            BigDecimal previousLength,
            Map<String, List<NetworkTreeEdge>> downstream,
            Map<String, BigDecimal> flows,
            Map<String, Integer> diameters,
            Map<String, BigDecimal> continuousLengths,
            Set<String> activeEdges,
            List<NetworkSizingIssue> issues) {
        for (NetworkTreeEdge edge : downstream.getOrDefault(node, List.of())) {
            if (!activeEdges.add(edge.getId())) {
                continue;
            }
            selectDiameter(edge, previousDiameter, previousLength, flows, diameters,
                    continuousLengths, issues);
            Integer diameter = diameters.get(edge.getId());
            BigDecimal continuous = continuousLengths.getOrDefault(edge.getId(), edge.getLengthM());
            selectDiameters(
                    edge.getDownstreamNodeId(),
                    diameter,
                    continuous,
                    downstream,
                    flows,
                    diameters,
                    continuousLengths,
                    activeEdges,
                    issues);
            activeEdges.remove(edge.getId());
        }
    }

    private void selectDiameter(
            NetworkTreeEdge edge,
            Integer previousDiameter,
            BigDecimal previousLength,
            Map<String, BigDecimal> flows,
            Map<String, Integer> diameters,
            Map<String, BigDecimal> continuousLengths,
            List<NetworkSizingIssue> issues) {
        BigDecimal flow = flows.getOrDefault(edge.getId(), BigDecimal.ZERO);
        Optional<PipeCatalogEntry> minimum = flow.signum() > 0
                ? pipeCatalog.minimumForFlow(flow)
                : Optional.of(pipeCatalog.entries().get(0));
        if (minimum.isEmpty()) {
            issues.add(new NetworkSizingIssue(
                    "FLOW_EXCEEDS_CATALOG",
                    edge.getId(),
                    "No official diameter can carry " + flow.toPlainString() + " t/h"));
            return;
        }

        PipeCatalogEntry selected = minimum.get();
        BigDecimal continuous = continuousLengthM(
                edge.getLengthM(), selected.getDiameter(), previousDiameter, previousLength);
        if (continuous.compareTo(BigDecimal.valueOf(selected.getMaxContinuousLengthM())) > 0) {
            int minimumDiameter = selected.getDiameter();
            Optional<PipeCatalogEntry> promoted = pipeCatalog.entries().stream()
                    .filter(pipe -> pipe.getDiameter() > minimumDiameter)
                    .filter(pipe -> pipe.getMaxFlowTph().compareTo(flow) >= 0)
                    .filter(pipe -> BigDecimal.valueOf(pipe.getMaxContinuousLengthM())
                            .compareTo(continuousLengthM(
                                    edge.getLengthM(), pipe.getDiameter(), previousDiameter, previousLength)) >= 0)
                    .findFirst();
            if (promoted.isPresent()) {
                selected = promoted.get();
                continuous = continuousLengthM(
                        edge.getLengthM(), selected.getDiameter(), previousDiameter, previousLength);
            } else {
                issues.add(new NetworkSizingIssue(
                        "MAX_CONTINUOUS_LENGTH_EXCEEDED",
                        edge.getId(),
                        "No official diameter supports a continuous section of "
                                + edge.getLengthM().toPlainString() + " m"));
            }
        }
        diameters.put(edge.getId(), selected.getDiameter());
        continuousLengths.put(edge.getId(), continuous);
    }

    /** Повышение относительно минимального ДУ не сбрасывает длину, если фактический ДУ остался прежним. */
    private BigDecimal continuousLengthM(
            BigDecimal edgeLength, int diameter, Integer previousDiameter, BigDecimal previousLength) {
        return previousDiameter != null && diameter == previousDiameter
                ? previousLength.add(edgeLength)
                : edgeLength;
    }
}
