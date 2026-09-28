package ru.lct.heatroute.domain.routing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.optimization.CandidateAssemblyIncompleteException;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;

/** Строит ограниченный набор точных локальных конфигураций узлов конечного master-каталога. */
public final class CatalogNodeConfigurationCompiler {
    private static final int MAX_CONFIGURATIONS_PER_NODE = 4_096;
    private static final int MAX_COMBINATIONS_PER_NODE = 50_000;
    private static final int MAX_TOTAL_CONFIGURATIONS = 100_000;

    public CatalogNetworkProblemCompiler.Compilation compile(
            RoutingProblemSnapshot snapshot,
            CatalogNetworkProblemCompiler.Compilation base) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(base, "base compilation");

        Map<String, List<ArcRay>> incoming = new LinkedHashMap<>();
        Map<String, List<ArcRay>> outgoing = new LinkedHashMap<>();
        for (NetworkConstraintProblem.Asset asset : base.getProblem().getAssets()) {
            CatalogNetworkProblemCompiler.NodeBinding from = base.node(asset.getFromNodeId());
            CatalogNetworkProblemCompiler.NodeBinding to = base.node(asset.getToNodeId());
            if (from == null || to == null) {
                throw new IllegalArgumentException("Master asset has no compiled endpoint");
            }
            ArcRay fromRay = new ArcRay(asset.getId(),
                    to.getPoint().getXMm() - from.getPoint().getXMm(),
                    to.getPoint().getYMm() - from.getPoint().getYMm());
            ArcRay toRay = new ArcRay(asset.getId(), -fromRay.dxMm, -fromRay.dyMm);
            outgoing.computeIfAbsent(from.getNodeId(), ignored -> new ArrayList<>()).add(fromRay);
            incoming.computeIfAbsent(to.getNodeId(), ignored -> new ArrayList<>()).add(toRay);
        }
        sortRays(incoming);
        sortRays(outgoing);

        Map<String, RoutingProblemSnapshot.RootCandidate> rootByNode = rootsByNode(snapshot, base);
        Map<String, RoutingProblemSnapshot.Demand> demandByNode = demandsByNode(snapshot, base);
        List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> bindings = new ArrayList<>();
        List<CatalogNetworkProblemCompiler.NodeBinding> nodes =
                new ArrayList<>(base.getNodeBindings());
        nodes.sort(Comparator.comparing(CatalogNetworkProblemCompiler.NodeBinding::getNodeId));
        for (CatalogNetworkProblemCompiler.NodeBinding node : nodes) {
            RoutingProblemSnapshot.RootCandidate root = rootByNode.get(node.getNodeId());
            RoutingProblemSnapshot.Demand demand = demandByNode.get(node.getNodeId());
            if (root != null && demand != null) {
                throw new IllegalArgumentException("Catalog node cannot be both root and demand");
            }
            List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> local;
            if (root != null) {
                local = rootConfigurations(node, root,
                        outgoing.getOrDefault(node.getNodeId(), List.of()));
            } else if (demand != null) {
                local = demandConfigurations(node, demand,
                        incoming.getOrDefault(node.getNodeId(), List.of()));
            } else {
                local = internalConfigurations(node,
                        incoming.getOrDefault(node.getNodeId(), List.of()),
                        outgoing.getOrDefault(node.getNodeId(), List.of()));
            }
            bindings.addAll(local);
            if (bindings.size() > MAX_TOTAL_CONFIGURATIONS) {
                throw incomplete("total_node_configuration_limit",
                        "Node configuration catalog exceeds " + MAX_TOTAL_CONFIGURATIONS);
            }
        }
        return base.withNodeConfigurations(bindings);
    }

    private static List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> rootConfigurations(
            CatalogNetworkProblemCompiler.NodeBinding node,
            RoutingProblemSnapshot.RootCandidate root,
            List<ArcRay> outgoing) {
        RoutingProblemSnapshot.RootRealization realization = root.getRealization();
        if (realization == null) {
            throw new IllegalArgumentException("Root lacks exact node realization: " + root.getId());
        }
        int availableRays = 4 - root.getExistingDirections().size();
        List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> result = new ArrayList<>();
        if (availableRays <= 0) return result;
        CombinationBudget budget = new CombinationBudget(node.getNodeId());
        List<ArcRay> existing = new ArrayList<>();
        for (RoutingProblemSnapshot.DirectionVector direction : root.getExistingDirections()) {
            existing.add(new ArcRay("existing", direction.getDeltaXMm(), direction.getDeltaYMm()));
        }
        combinations(outgoing, Math.min(availableRays, outgoing.size()), budget, selected -> {
            if (selected.isEmpty() || !compatible(existing, selected)) return;
            add(result, binding(node.getNodeId(), selected,
                    realization.getNodeType(), true,
                    realization.getBaseIncidentSections(), realization.getTargetId(),
                    realization.getExistingIncidentDiameter()));
        });
        return List.copyOf(result);
    }

    private static List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> demandConfigurations(
            CatalogNetworkProblemCompiler.NodeBinding node,
            RoutingProblemSnapshot.Demand demand,
            List<ArcRay> incoming) {
        List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> result = new ArrayList<>();
        for (ArcRay arc : incoming) {
            add(result, binding(node.getNodeId(), List.of(arc),
                    "demand_connection", false, 0, demand.getLinkedOksId(), null));
        }
        return List.copyOf(result);
    }

    private static List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> internalConfigurations(
            CatalogNetworkProblemCompiler.NodeBinding node,
            List<ArcRay> incoming,
            List<ArcRay> outgoing) {
        List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> result = new ArrayList<>();
        CombinationBudget budget = new CombinationBudget(node.getNodeId());
        for (ArcRay parent : incoming) {
            combinations(outgoing, Math.min(3, outgoing.size()), budget, selected -> {
                if (selected.isEmpty()) return;
                List<ArcRay> incidence = new ArrayList<>(selected.size() + 1);
                incidence.add(parent);
                incidence.addAll(selected);
                boolean chamber = incidence.size() >= 3;
                if (chamber && !compatible(List.of(), incidence)) return;
                add(result, binding(node.getNodeId(), incidence,
                        chamber ? "new_branch_chamber" : "technical_transition",
                        chamber, 0, null, null));
            });
        }
        return List.copyOf(result);
    }

    private static CatalogNetworkProblemCompiler.NodeConfigurationBinding binding(
            String nodeId, Collection<ArcRay> incidence, String nodeType,
            boolean chamber, int baseIncidentSections, String targetId,
            Integer existingIncidentDiameter) {
        List<String> arcIds = incidence.stream().map(ray -> ray.arcId)
                .sorted().collect(java.util.stream.Collectors.toList());
        String configurationId = "node-config:" + sha256(nodeId, arcIds).substring(0, 24);
        NetworkConstraintProblem.NodeConfiguration configuration =
                new NetworkConstraintProblem.NodeConfiguration(
                        configurationId, nodeId, arcIds);
        return new CatalogNetworkProblemCompiler.NodeConfigurationBinding(
                configuration, nodeType, chamber, baseIncidentSections,
                targetId, existingIncidentDiameter);
    }

    private static void add(
            List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> result,
            CatalogNetworkProblemCompiler.NodeConfigurationBinding binding) {
        if (result.size() >= MAX_CONFIGURATIONS_PER_NODE) {
            throw incomplete("per_node_configuration_limit",
                    "Node configuration catalog exceeds " + MAX_CONFIGURATIONS_PER_NODE
                            + " at " + binding.getConfiguration().getNodeId());
        }
        result.add(binding);
    }

    private static boolean compatible(List<ArcRay> fixed, List<ArcRay> selected) {
        List<ArcRay> all = new ArrayList<>(fixed.size() + selected.size());
        all.addAll(fixed);
        all.addAll(selected);
        if (all.size() > 4) return false;
        for (int left = 0; left < all.size(); left++) {
            for (int right = left + 1; right < all.size(); right++) {
                ArcRay first = all.get(left);
                ArcRay second = all.get(right);
                if (!ExpertChamberGeometryRules.compatibleRays(
                        first.dxMm, first.dyMm, second.dxMm, second.dyMm)) return false;
            }
        }
        return true;
    }

    private static void combinations(List<ArcRay> values, int maximumSize,
            CombinationBudget budget, Consumer<List<ArcRay>> consumer) {
        budget.record();
        consumer.accept(List.of());
        choose(values, maximumSize, 0, new ArrayList<>(), budget, consumer);
    }

    private static void choose(List<ArcRay> values, int maximumSize, int start,
            List<ArcRay> selected, CombinationBudget budget,
            Consumer<List<ArcRay>> consumer) {
        if (selected.size() >= maximumSize) return;
        for (int index = start; index < values.size(); index++) {
            selected.add(values.get(index));
            budget.record();
            consumer.accept(List.copyOf(selected));
            choose(values, maximumSize, index + 1, selected, budget, consumer);
            selected.remove(selected.size() - 1);
        }
    }

    private static Map<String, RoutingProblemSnapshot.RootCandidate> rootsByNode(
            RoutingProblemSnapshot snapshot,
            CatalogNetworkProblemCompiler.Compilation compilation) {
        Map<String, RoutingProblemSnapshot.RootCandidate> result = new LinkedHashMap<>();
        for (RoutingProblemSnapshot.RootCandidate root : snapshot.getRoots()) {
            String nodeId = compilation.getRootNodeById().get(root.getId());
            if (nodeId == null || result.put(nodeId, root) != null) {
                throw new IllegalArgumentException("Root binding is missing or ambiguous");
            }
        }
        return result;
    }

    private static Map<String, RoutingProblemSnapshot.Demand> demandsByNode(
            RoutingProblemSnapshot snapshot,
            CatalogNetworkProblemCompiler.Compilation compilation) {
        Map<String, RoutingProblemSnapshot.Demand> byId = new LinkedHashMap<>();
        snapshot.getDemands().forEach(demand -> byId.put(demand.getId(), demand));
        Map<String, RoutingProblemSnapshot.Demand> result = new LinkedHashMap<>();
        for (CatalogNetworkProblemCompiler.DemandBinding binding
                : compilation.getDemandBindings()) {
            RoutingProblemSnapshot.Demand demand = byId.get(binding.getDemandId());
            if (demand == null || result.put(binding.getNodeId(), demand) != null) {
                throw new IllegalArgumentException("Demand binding is missing or ambiguous");
            }
        }
        return result;
    }

    private static void sortRays(Map<String, List<ArcRay>> raysByNode) {
        raysByNode.values().forEach(values -> values.sort(Comparator.comparing(ray -> ray.arcId)));
    }

    private static CandidateAssemblyIncompleteException incomplete(
            String reason, String message) {
        return new CandidateAssemblyIncompleteException(reason, message);
    }

    private static String sha256(String nodeId, List<String> arcIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, nodeId);
            update(digest, arcIds.size());
            for (String arcId : arcIds) update(digest, arcId);
            try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
                for (byte value : digest.digest()) formatter.format("%02x", value);
                return formatter.toString();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, Object value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static final class ArcRay {
        private final String arcId;
        private final long dxMm;
        private final long dyMm;

        private ArcRay(String arcId, long dxMm, long dyMm) {
            this.arcId = Objects.requireNonNull(arcId, "arcId");
            if (dxMm == 0L && dyMm == 0L) {
                throw new IllegalArgumentException("Node incident arc must have a nonzero ray");
            }
            this.dxMm = dxMm;
            this.dyMm = dyMm;
        }
    }

    private static final class CombinationBudget {
        private final String nodeId;
        private int visited;

        private CombinationBudget(String nodeId) { this.nodeId = nodeId; }

        private void record() {
            visited++;
            if (visited > MAX_COMBINATIONS_PER_NODE) {
                throw incomplete("node_configuration_enumeration_limit",
                        "Node configuration enumeration exceeds "
                                + MAX_COMBINATIONS_PER_NODE + " at " + nodeId);
            }
        }
    }
}
