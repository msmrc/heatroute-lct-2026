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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.DirectedPathOption;
import ru.lct.heatroute.domain.catalog.RoutingCatalogSnapshot;
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
            RoutingCatalogSnapshot catalog,
            CatalogNetworkProblemCompiler.Compilation base) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(base, "base compilation");
        Map<String, String> groupByOptionId = optionGroups(catalog);

        Map<String, List<ArcRay>> incoming = new LinkedHashMap<>();
        Map<String, List<ArcRay>> outgoing = new LinkedHashMap<>();
        for (NetworkConstraintProblem.Asset asset : base.getProblem().getAssets()) {
            CatalogNetworkProblemCompiler.NodeBinding from = base.node(asset.getFromNodeId());
            CatalogNetworkProblemCompiler.NodeBinding to = base.node(asset.getToNodeId());
            if (from == null || to == null) {
                throw new IllegalArgumentException("Master asset has no compiled endpoint");
            }
            CatalogNetworkProblemCompiler.ArcBinding arcBinding = base.arc(asset.getId());
            if (arcBinding == null) {
                throw new IllegalArgumentException("Master asset has no catalog binding");
            }
            Set<String> groups = arcBinding.getSourceOptionIds().stream()
                    .map(groupByOptionId::get)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (groups.isEmpty()) {
                throw new IllegalArgumentException("Master asset has no certified path group");
            }
            ArcRay fromRay = new ArcRay(asset.getId(),
                    to.getPoint().getXMicrometers() - from.getPoint().getXMicrometers(),
                    to.getPoint().getYMicrometers() - from.getPoint().getYMicrometers(), groups);
            ArcRay toRay = new ArcRay(asset.getId(), -fromRay.dxMm, -fromRay.dyMm, groups);
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
                        outgoing.getOrDefault(node.getNodeId(), List.of()), groupByOptionId);
            }
            bindings.addAll(local);
            if (bindings.size() > MAX_TOTAL_CONFIGURATIONS) {
                throw incomplete("total_node_configuration_limit",
                        "Node configuration catalog exceeds " + MAX_TOTAL_CONFIGURATIONS);
            }
        }
        Set<String> managedNodeIds = new LinkedHashSet<>(rootByNode.keySet());
        managedNodeIds.addAll(demandByNode.keySet());
        // Internal junctions that have a finite engineering configuration catalog must select
        // one of those configurations in the master.  Leaving such a node unmanaged made a
        // shared trunk look feasible to CP-SAT but left the frozen assembler without an exact
        // chamber realization. Nodes for which no exact local incidence can be enumerated remain
        // unmanaged and are still validated or collapsed by the frozen assembler.
        bindings.stream().map(binding -> binding.getConfiguration().getNodeId())
                .filter(nodeId -> !rootByNode.containsKey(nodeId)
                        && !demandByNode.containsKey(nodeId))
                .forEach(managedNodeIds::add);
        return base.withNodeConfigurations(bindings, managedNodeIds);
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
        List<ArcRay> fixed = root.getExistingDirections().stream()
                .map(direction -> new ArcRay("existing:" + direction.getDeltaXMm()
                        + ":" + direction.getDeltaYMm(),
                        Math.multiplyExact(direction.getDeltaXMm(), 1_000L),
                        Math.multiplyExact(direction.getDeltaYMm(), 1_000L), Set.of("existing")))
                .collect(java.util.stream.Collectors.toList());
        CombinationBudget budget = new CombinationBudget(node.getNodeId());
        combinations(outgoing, Math.min(availableRays, outgoing.size()), budget, selected -> {
            if (selected.isEmpty()) return;
            if (!compatible(fixed, selected)) return;
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
                    "demand_connection", false, 0, demand.getConnectionPointId(), null));
        }
        return List.copyOf(result);
    }

    private static List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> internalConfigurations(
            CatalogNetworkProblemCompiler.NodeBinding node,
            List<ArcRay> incoming,
            List<ArcRay> outgoing,
            Map<String, String> groupByOptionId) {
        List<CatalogNetworkProblemCompiler.NodeConfigurationBinding> result = new ArrayList<>();
        Map<String, List<ArcRay>> certifiedIncidence = new LinkedHashMap<>();
        Set<String> groups = new LinkedHashSet<>(groupByOptionId.values());
        for (String group : groups) {
            List<ArcRay> groupIncoming = incoming.stream()
                    .filter(ray -> ray.optionGroups.contains(group))
                    .collect(java.util.stream.Collectors.toList());
            List<ArcRay> groupOutgoing = outgoing.stream()
                    .filter(ray -> ray.optionGroups.contains(group))
                    .collect(java.util.stream.Collectors.toList());
            if (groupIncoming.size() != 1 || groupOutgoing.isEmpty()
                    || groupOutgoing.size() > 3) continue;
            List<ArcRay> incidence = new ArrayList<>(1 + groupOutgoing.size());
            incidence.add(groupIncoming.get(0));
            incidence.addAll(groupOutgoing);
            boolean chamber = incidence.size() >= 3;
            if (chamber && !compatible(List.of(), incidence)) continue;
            certifiedIncidence.putIfAbsent(arcSignature(incidence), List.copyOf(incidence));
            add(result, binding(node.getNodeId(), incidence,
                    chamber ? "new_branch_chamber" : "technical_transition",
                    chamber, 0, null, null));
        }
        // Noding creates technical vertices where independently certified paths cross or share
        // part of the same axis. A route may continue through such a vertex without changing
        // direction even when provenance was merged into different option groups. Only add a
        // collinear pass-through here; turns and branches still require a certified group.
        for (ArcRay parent : incoming) {
            for (ArcRay child : outgoing) {
                if (!straightContinuation(parent, child)) continue;
                add(result, binding(node.getNodeId(), List.of(parent, child),
                        "technical_transition", false, 0, null, null));
            }
        }
        // Physical noding can place two independently certified networks on the same coordinate.
        // One node may select only one configuration, so add the compatible union of the complete
        // certified incidences. This permits an explicit chamber at a real shared point without
        // inventing the arbitrary parent/child splices that previously dominated the master.
        List<List<ArcRay>> unionClosure = new ArrayList<>(certifiedIncidence.values());
        for (int index = 0; index < unionClosure.size(); index++) {
            List<ArcRay> left = unionClosure.get(index);
            for (List<ArcRay> right : List.copyOf(certifiedIncidence.values())) {
                List<ArcRay> union = union(left, right);
                if (union.size() < 3 || union.size() > 4
                        || !compatible(List.of(), union)) continue;
                String signature = arcSignature(union);
                if (certifiedIncidence.putIfAbsent(signature, union) == null) {
                    unionClosure.add(union);
                    add(result, binding(node.getNodeId(), union,
                            "new_branch_chamber", true, 0, null, null));
                }
            }
        }
        // Complete the finite engineering-compatible local universe: one parent and one to three
        // children. This blocks arbitrary splices between certified paths in the master while
        // exact refinement still owns spacing, obstacles and neighbouring-bend context.
        CombinationBudget budget = new CombinationBudget(node.getNodeId());
        for (ArcRay parent : incoming) {
            combinations(outgoing, Math.min(3, outgoing.size()), budget, selected -> {
                if (selected.isEmpty()) return;
                List<ArcRay> incidence = new ArrayList<>(selected.size() + 1);
                incidence.add(parent);
                incidence.addAll(selected);
                boolean chamber = incidence.size() >= 3;
                if (chamber && !compatible(List.of(), incidence)) return;
                if (!chamber && !validTechnicalTransition(parent, selected.get(0))) return;
                add(result, binding(node.getNodeId(), incidence,
                        chamber ? "new_branch_chamber" : "technical_transition",
                        chamber, 0, null, null));
            });
        }
        return List.copyOf(result);
    }

    private static boolean validTechnicalTransition(ArcRay parent, ArcRay child) {
        return straightContinuation(parent, child)
                || ExpertChamberGeometryRules.allowsBend(
                        parent.dxMm, parent.dyMm, child.dxMm, child.dyMm);
    }

    private static List<ArcRay> union(List<ArcRay> left, List<ArcRay> right) {
        Map<String, ArcRay> byArc = new LinkedHashMap<>();
        left.forEach(ray -> byArc.put(ray.arcId, ray));
        right.forEach(ray -> byArc.putIfAbsent(ray.arcId, ray));
        return List.copyOf(byArc.values());
    }

    private static String arcSignature(Collection<ArcRay> rays) {
        return rays.stream().map(ray -> ray.arcId).sorted()
                .collect(java.util.stream.Collectors.joining("\u0000"));
    }

    private static boolean straightContinuation(ArcRay parent, ArcRay child) {
        double dot = (double) parent.dxMm * child.dxMm
                + (double) parent.dyMm * child.dyMm;
        if (!(dot < 0.0)) return false;
        double cross = Math.abs((double) parent.dxMm * child.dyMm
                - (double) parent.dyMm * child.dxMm);
        double endpointDistance = Math.hypot(
                (double) parent.dxMm - child.dxMm,
                (double) parent.dyMm - child.dyMm);
        return endpointDistance > 0.0 && cross / endpointDistance <= 2.0;
    }

    private static Map<String, String> optionGroups(RoutingCatalogSnapshot catalog) {
        Map<String, String> result = new LinkedHashMap<>();
        for (DirectedPathOption option : catalog.getPathOptions()) {
            String context = option.getEndpointContext();
            String group = context;
            if (context.startsWith("network=")) {
                int separator = context.indexOf(';');
                group = separator < 0 ? context.substring("network=".length())
                        : context.substring("network=".length(), separator);
            }
            if (group.isEmpty() || result.put(option.getId(), group) != null) {
                throw new IllegalArgumentException("Path option has no unique certified group: "
                        + option.getId());
            }
        }
        return Map.copyOf(result);
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
        String configurationId = binding.getConfiguration().getId();
        if (result.stream().anyMatch(existing ->
                existing.getConfiguration().getId().equals(configurationId))) return;
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
            if (nodeId == null) continue;
            if (result.put(nodeId, root) != null) {
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
        private final Set<String> optionGroups;

        private ArcRay(String arcId, long dxMm, long dyMm,
                Collection<String> optionGroups) {
            this.arcId = Objects.requireNonNull(arcId, "arcId");
            if (dxMm == 0L && dyMm == 0L) {
                throw new IllegalArgumentException("Node incident arc must have a nonzero ray");
            }
            this.dxMm = dxMm;
            this.dyMm = dyMm;
            this.optionGroups = Set.copyOf(optionGroups);
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
