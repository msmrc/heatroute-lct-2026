package ru.lct.heatroute.domain.routing;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;

/** Разрешает только явно заданные порты корней и потребителей из неизменяемого входа. */
@Component
public final class CatalogProblemNodeRealizationResolver {
    public Map<String, CatalogFrozenCandidateAssembler.NodeRealization> resolve(
            RoutingProblemSnapshot problem,
            CatalogNetworkProblemCompiler.Compilation compilation,
            Map<String, String> demandPortById,
            Map<String, String> rootPortById) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(compilation, "compilation");
        Map<String, String> demandPorts = requiredMap(demandPortById, "demand port");
        Map<String, String> rootPorts = requiredMap(rootPortById, "root port");
        requireOwners(problem, demandPorts, rootPorts);

        Map<String, String> nodeByPort = new LinkedHashMap<>();
        for (CatalogNetworkProblemCompiler.NodeBinding node : compilation.getNodeBindings()) {
            if (!node.isExplicitPort()) continue;
            String previous = nodeByPort.put(node.getExplicitPortId(), node.getNodeId());
            if (previous != null) {
                throw new IllegalArgumentException("Catalog port resolves to several nodes: "
                        + node.getExplicitPortId());
            }
        }
        Map<String, CatalogFrozenCandidateAssembler.NodeRealization> result = new LinkedHashMap<>();
        for (RoutingProblemSnapshot.Demand demand : problem.getDemands()) {
            String nodeId = requiredNode(nodeByPort, demandPorts.get(demand.getId()),
                    "demand " + demand.getId());
            put(result, nodeId, new CatalogFrozenCandidateAssembler.NodeRealization(
                    "demand_connection", false, 0, demand.getConnectionPointId(), null));
        }
        for (RoutingProblemSnapshot.RootCandidate root : problem.getRoots()) {
            if (!rootPorts.containsKey(root.getId())) continue;
            RoutingProblemSnapshot.RootRealization realization = root.getRealization();
            if (realization == null) {
                throw new IllegalArgumentException("Root lacks exact node realization: " + root.getId());
            }
            String nodeId = requiredNode(nodeByPort, rootPorts.get(root.getId()),
                    "root " + root.getId());
            put(result, nodeId, new CatalogFrozenCandidateAssembler.NodeRealization(
                    realization.getNodeType(), realization.isChamber(),
                    realization.getBaseIncidentSections(), realization.getTargetId(),
                    realization.getExistingIncidentDiameter()));
        }
        return Map.copyOf(result);
    }

    private static void requireOwners(RoutingProblemSnapshot problem,
            Map<String, String> demandPorts, Map<String, String> rootPorts) {
        Set<String> expectedDemands = new LinkedHashSet<>();
        problem.getDemands().forEach(demand -> expectedDemands.add(demand.getId()));
        Set<String> expectedRoots = new LinkedHashSet<>();
        problem.getRoots().forEach(root -> expectedRoots.add(root.getId()));
        if (!demandPorts.keySet().equals(expectedDemands)) {
            throw new IllegalArgumentException("Demand port owners differ from problem snapshot");
        }
        if (rootPorts.isEmpty() || !expectedRoots.containsAll(rootPorts.keySet())) {
            throw new IllegalArgumentException("Root port owners differ from problem snapshot");
        }
    }

    private static Map<String, String> requiredMap(Map<String, String> supplied, String label) {
        if (supplied == null) throw new IllegalArgumentException(label + " map is required");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : supplied.entrySet()) {
            String owner = required(entry.getKey(), label + " owner");
            String port = required(entry.getValue(), label + " ID");
            if (result.put(owner, port) != null) {
                throw new IllegalArgumentException("Duplicate " + label + " owner");
            }
        }
        return result;
    }

    private static String requiredNode(Map<String, String> nodeByPort, String port, String label) {
        String nodeId = nodeByPort.get(port);
        if (nodeId == null) throw new IllegalArgumentException(label + " references an unknown port");
        return nodeId;
    }

    private static void put(
            Map<String, CatalogFrozenCandidateAssembler.NodeRealization> target,
            String nodeId, CatalogFrozenCandidateAssembler.NodeRealization realization) {
        if (target.put(nodeId, realization) != null) {
            throw new IllegalArgumentException("Several explicit semantics share node " + nodeId);
        }
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }
}
