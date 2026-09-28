package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.CatalogPhysicalAsset;
import ru.lct.heatroute.domain.catalog.RoutingCatalogSnapshot;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CandidateAssemblyIncompleteException;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Собирает frozen-кандидат из master assignment. Degree-2 accounting splits схлопываются;
 * реальные порты/камеры никогда не выводятся из одного совпадения координат.
 */
public final class CatalogFrozenCandidateAssembler {
    public FrozenNetworkCandidate assemble(String candidateId, String strategy,
            RoutingProblemSnapshot problemSnapshot, RoutingCatalogSnapshot catalogSnapshot,
            CatalogNetworkProblemCompiler.Compilation compilation,
            CpSatNetworkOptimizer.Result masterResult,
            Map<String, NodeRealization> explicitNodeRealizations,
            Collection<ImportedOfficialFeature> relevantFeatures,
            EdgeSectionAssembler sectionAssembler) {
        return assembleDetailed(candidateId, strategy, problemSnapshot, catalogSnapshot,
                compilation, masterResult, explicitNodeRealizations, relevantFeatures,
                sectionAssembler).getCandidate();
    }

    public Assembly assembleDetailed(String candidateId, String strategy,
            RoutingProblemSnapshot problemSnapshot, RoutingCatalogSnapshot catalogSnapshot,
            CatalogNetworkProblemCompiler.Compilation compilation,
            CpSatNetworkOptimizer.Result masterResult,
            Map<String, NodeRealization> explicitNodeRealizations,
            Collection<ImportedOfficialFeature> relevantFeatures,
            EdgeSectionAssembler sectionAssembler) {
        return assembleDetailedInternal(candidateId, strategy, problemSnapshot, catalogSnapshot,
                compilation, masterResult, explicitNodeRealizations, relevantFeatures, null,
                sectionAssembler);
    }

    public Assembly assembleDetailed(String candidateId, String strategy,
            RoutingProblemSnapshot problemSnapshot, RoutingCatalogSnapshot catalogSnapshot,
            CatalogNetworkProblemCompiler.Compilation compilation,
            CpSatNetworkOptimizer.Result masterResult,
            Map<String, NodeRealization> explicitNodeRealizations,
            PreparedRoutingFeatureWindow featureWindow,
            EdgeSectionAssembler sectionAssembler) {
        Objects.requireNonNull(featureWindow, "featureWindow");
        return assembleDetailedInternal(candidateId, strategy, problemSnapshot, catalogSnapshot,
                compilation, masterResult, explicitNodeRealizations, featureWindow.features(),
                featureWindow, sectionAssembler);
    }

    private Assembly assembleDetailedInternal(String candidateId, String strategy,
            RoutingProblemSnapshot problemSnapshot, RoutingCatalogSnapshot catalogSnapshot,
            CatalogNetworkProblemCompiler.Compilation compilation,
            CpSatNetworkOptimizer.Result masterResult,
            Map<String, NodeRealization> explicitNodeRealizations,
            Collection<ImportedOfficialFeature> relevantFeatures,
            PreparedRoutingFeatureWindow featureWindow,
            EdgeSectionAssembler sectionAssembler) {
        Objects.requireNonNull(problemSnapshot, "problemSnapshot");
        Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
        Objects.requireNonNull(compilation, "compilation");
        Objects.requireNonNull(masterResult, "masterResult");
        Objects.requireNonNull(explicitNodeRealizations, "explicitNodeRealizations");
        Objects.requireNonNull(relevantFeatures, "relevantFeatures");
        Objects.requireNonNull(sectionAssembler, "sectionAssembler");
        if (masterResult.getStatus() != CpSatNetworkOptimizer.Status.FEASIBLE
                && masterResult.getStatus() != CpSatNetworkOptimizer.Status.OPTIMAL) {
            throw new IllegalArgumentException("A feasible master result is required");
        }
        if (!problemSnapshot.getSnapshotHash().equals(catalogSnapshot.getSourceSnapshotHash())) {
            throw new IllegalArgumentException("Catalog and problem snapshot differ");
        }

        List<SelectedArc> arcs = selectedArcs(catalogSnapshot, compilation, masterResult);
        Map<String, List<SelectedArc>> incoming = group(arcs, false);
        Map<String, List<SelectedArc>> outgoing = group(arcs, true);
        Set<String> usedNodes = usedNodes(arcs, masterResult, compilation);
        Set<String> retainedNodes = retainedNodes(
                usedNodes, incoming, outgoing, masterResult, compilation);
        Map<String, String> routeNodeIds = routeNodeIds(usedNodes, compilation);
        Map<String, NodeRealization> selectedNodeRealizations = selectedNodeRealizations(
                usedNodes, explicitNodeRealizations, compilation, masterResult);
        List<RouteNode> nodes = routeNodes(retainedNodes, masterResult,
                compilation, routeNodeIds, selectedNodeRealizations);
        EdgeResult edgeResult = routeEdges(arcs, outgoing, retainedNodes,
                compilation, routeNodeIds, selectedNodeRealizations, sectionAssembler);
        List<RouteConnection> connections = connections(compilation);
        boolean reconstructionRequired = arcs.stream().anyMatch(arc ->
                arc.physicalAsset.getConstructionMode()
                        == CatalogPhysicalAsset.ConstructionMode.RECONSTRUCTION);
        FrozenNetworkCandidate candidate = featureWindow == null
                ? new FrozenNetworkCandidate(candidateId, strategy, nodes, edgeResult.edges,
                        connections, new ArrayList<>(relevantFeatures),
                        problemSnapshot.getParameters(), reconstructionRequired)
                : new FrozenNetworkCandidate(candidateId, strategy, nodes, edgeResult.edges,
                        connections, featureWindow, problemSnapshot.getParameters(),
                        reconstructionRequired);
        return Assembly.of(candidate, edgeResult.arcIdsByEdgeId);
    }

    private static List<SelectedArc> selectedArcs(RoutingCatalogSnapshot catalog,
            CatalogNetworkProblemCompiler.Compilation compilation,
            CpSatNetworkOptimizer.Result master) {
        List<String> selectedIds = new ArrayList<>(master.getSelectedAssets());
        selectedIds.sort(Comparator.naturalOrder());
        List<SelectedArc> result = new ArrayList<>(selectedIds.size());
        for (String arcId : selectedIds) {
            CatalogNetworkProblemCompiler.ArcBinding binding = compilation.arc(arcId);
            NetworkConstraintProblem.Asset modelAsset = compilation.getProblem().asset(arcId);
            Long flow = master.getFlowUnits().get(arcId);
            Integer diameter = master.getDiameterMm().get(arcId);
            if (binding == null || modelAsset == null || flow == null || diameter == null) {
                throw new IllegalArgumentException("Master result references an incomplete catalog arc: " + arcId);
            }
            CatalogPhysicalAsset physicalAsset = catalog.physicalAsset(binding.getPhysicalAssetId());
            if (physicalAsset == null) {
                throw new IllegalArgumentException("Arc references an unknown physical asset: " + arcId);
            }
            result.add(new SelectedArc(arcId, modelAsset.getFromNodeId(), modelAsset.getToNodeId(),
                    physicalAsset, flow, diameter));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Master result selected no physical assets");
        return result;
    }

    private static Map<String, List<SelectedArc>> group(List<SelectedArc> arcs, boolean byFrom) {
        Map<String, List<SelectedArc>> result = new LinkedHashMap<>();
        for (SelectedArc arc : arcs) {
            String nodeId = byFrom ? arc.fromNodeId : arc.toNodeId;
            result.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(arc);
        }
        for (List<SelectedArc> values : result.values()) values.sort(Comparator.comparing(arc -> arc.id));
        return result;
    }

    private static Set<String> usedNodes(List<SelectedArc> arcs,
            CpSatNetworkOptimizer.Result master,
            CatalogNetworkProblemCompiler.Compilation compilation) {
        Set<String> result = new LinkedHashSet<>();
        for (SelectedArc arc : arcs) {
            result.add(arc.fromNodeId);
            result.add(arc.toNodeId);
        }
        result.addAll(master.getSelectedRoots());
        for (CatalogNetworkProblemCompiler.DemandBinding demand : compilation.getDemandBindings()) {
            result.add(demand.getNodeId());
        }
        return result;
    }

    private static Set<String> retainedNodes(Set<String> usedNodes,
            Map<String, List<SelectedArc>> incoming, Map<String, List<SelectedArc>> outgoing,
            CpSatNetworkOptimizer.Result master,
            CatalogNetworkProblemCompiler.Compilation compilation) {
        Set<String> demandNodes = new LinkedHashSet<>();
        for (CatalogNetworkProblemCompiler.DemandBinding demand : compilation.getDemandBindings()) {
            demandNodes.add(demand.getNodeId());
        }
        Set<String> retained = new LinkedHashSet<>();
        for (String nodeId : usedNodes) {
            CatalogNetworkProblemCompiler.NodeBinding binding = compilation.node(nodeId);
            if (binding == null) throw new IllegalArgumentException("Unknown compiled node: " + nodeId);
            List<SelectedArc> in = incoming.getOrDefault(nodeId, List.of());
            List<SelectedArc> out = outgoing.getOrDefault(nodeId, List.of());
            boolean chainBoundary = in.size() != 1 || out.size() != 1
                    || !compatibleForCollapse(in.isEmpty() ? null : in.get(0),
                            out.isEmpty() ? null : out.get(0));
            if (binding.isExplicitPort() || master.getSelectedRoots().contains(nodeId)
                    || demandNodes.contains(nodeId) || chainBoundary) retained.add(nodeId);
        }
        return retained;
    }

    private static boolean compatibleForCollapse(SelectedArc incoming, SelectedArc outgoing) {
        return incoming != null && outgoing != null
                && incoming.flowUnits == outgoing.flowUnits
                && incoming.diameterMm == outgoing.diameterMm
                && incoming.physicalAsset.getConstructionMode()
                        == outgoing.physicalAsset.getConstructionMode()
                && incoming.physicalAsset.getPhysicalContext()
                        .equals(outgoing.physicalAsset.getPhysicalContext());
    }

    private static Map<String, String> routeNodeIds(Set<String> usedNodes,
            CatalogNetworkProblemCompiler.Compilation compilation) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String nodeId : usedNodes) result.put(nodeId, nodeId);
        for (CatalogNetworkProblemCompiler.DemandBinding demand : compilation.getDemandBindings()) {
            String routeNodeId = "demand:" + demand.getDemandId();
            String previous = result.put(demand.getNodeId(), routeNodeId);
            if (previous != null && !previous.equals(demand.getNodeId())
                    && !previous.equals(routeNodeId)) {
                throw new IllegalArgumentException("Several demands share one catalog terminal node");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<RouteNode> routeNodes(Set<String> retainedNodes,
            CpSatNetworkOptimizer.Result master,
            CatalogNetworkProblemCompiler.Compilation compilation,
            Map<String, String> routeNodeIds,
            Map<String, NodeRealization> explicitRealizations) {
        List<RouteNode> result = new ArrayList<>();
        for (String nodeId : retainedNodes) {
            CatalogNetworkProblemCompiler.NodeBinding binding = compilation.node(nodeId);
            NodeRealization realization = explicitRealizations.get(nodeId);
            if (realization == null) {
                String reason = binding.isExplicitPort()
                        ? "missing_node_realization" : "missing_chamber_configuration";
                String label = binding.isExplicitPort()
                        ? "Missing explicit node realization: "
                        : "Missing selected node configuration: ";
                throw new CandidateAssemblyIncompleteException(reason, label + nodeId);
            }
            boolean root = master.getSelectedRoots().contains(nodeId);
            RouteCoordinate coordinate = coordinate(binding);
            result.add(new RouteNode(routeNodeIds.get(nodeId), realization.nodeType, coordinate,
                    realization.chamber, root, realization.baseIncidentSections,
                    realization.targetId, realization.existingIncidentDiameter));
        }
        result.sort(Comparator.comparing(RouteNode::getId));
        return List.copyOf(result);
    }

    private static Map<String, NodeRealization> selectedNodeRealizations(
            Set<String> usedNodes,
            Map<String, NodeRealization> explicitRealizations,
            CatalogNetworkProblemCompiler.Compilation compilation,
            CpSatNetworkOptimizer.Result master) {
        Map<String, NodeRealization> result = new LinkedHashMap<>(explicitRealizations);
        boolean managed = compilation.getProblem().getNodes().stream()
                .anyMatch(NetworkConstraintProblem.Node::isConfigurationRequired);
        if (!managed) return Collections.unmodifiableMap(result);
        Set<String> configuredNodes = new LinkedHashSet<>();
        for (String configurationId : master.getSelectedNodeConfigurations()) {
            CatalogNetworkProblemCompiler.NodeConfigurationBinding binding =
                    compilation.nodeConfiguration(configurationId);
            if (binding == null) {
                throw new IllegalArgumentException(
                        "Master selected an unknown node configuration: " + configurationId);
            }
            String nodeId = binding.getConfiguration().getNodeId();
            if (!usedNodes.contains(nodeId) || !configuredNodes.add(nodeId)) {
                throw new IllegalArgumentException(
                        "Selected node configuration has invalid ownership: " + configurationId);
            }
            NodeRealization selected = new NodeRealization(
                    binding.getNodeType(), binding.isChamber(),
                    binding.getBaseIncidentSections(), binding.getTargetId(),
                    binding.getExistingIncidentDiameter());
            NodeRealization explicit = result.get(nodeId);
            if (explicit == null) result.put(nodeId, selected);
            else if (!sameRealization(explicit, selected)) {
                throw new IllegalArgumentException(
                        "Selected configuration changes explicit node semantics: " + nodeId);
            }
        }
        if (!configuredNodes.equals(usedNodes)) {
            throw new CandidateAssemblyIncompleteException("missing_chamber_configuration",
                    "Every used catalog node requires one selected configuration");
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean sameRealization(NodeRealization left, NodeRealization right) {
        return left.nodeType.equals(right.nodeType)
                && left.chamber == right.chamber
                && left.baseIncidentSections == right.baseIncidentSections
                && Objects.equals(left.targetId, right.targetId)
                && Objects.equals(left.existingIncidentDiameter, right.existingIncidentDiameter);
    }

    private static EdgeResult routeEdges(List<SelectedArc> arcs,
            Map<String, List<SelectedArc>> outgoing, Set<String> retainedNodes,
            CatalogNetworkProblemCompiler.Compilation compilation,
            Map<String, String> routeNodeIds,
            Map<String, NodeRealization> explicitNodeRealizations,
            EdgeSectionAssembler sectionAssembler) {
        Set<String> covered = new LinkedHashSet<>();
        List<RouteEdge> result = new ArrayList<>();
        Map<String, List<String>> assembledArcIds = new LinkedHashMap<>();
        for (SelectedArc start : arcs) {
            if (covered.contains(start.id) || !retainedNodes.contains(start.fromNodeId)) continue;
            List<SelectedArc> chain = new ArrayList<>();
            SelectedArc current = start;
            while (true) {
                if (!covered.add(current.id)) throw new IllegalStateException("Selected arc cycle detected");
                chain.add(current);
                if (retainedNodes.contains(current.toNodeId)) break;
                List<SelectedArc> next = outgoing.getOrDefault(current.toNodeId, List.of());
                if (next.size() != 1) throw new IllegalStateException("Unretained node is not a chain node");
                current = next.get(0);
            }
            RouteEdge edge = routeEdge(chain, compilation, routeNodeIds,
                    explicitNodeRealizations, sectionAssembler);
            result.add(edge);
            List<String> chainArcIds = new ArrayList<>();
            for (SelectedArc arc : chain) chainArcIds.add(arc.id);
            assembledArcIds.put(edge.getId(), List.copyOf(chainArcIds));
        }
        if (covered.size() != arcs.size()) {
            throw new IllegalStateException("Selected topology contains an unassembled component");
        }
        result.sort(Comparator.comparing(RouteEdge::getId));
        Map<String, List<String>> arcIdsByEdgeId = new LinkedHashMap<>();
        for (RouteEdge edge : result) {
            List<String> arcIds = assembledArcIds.get(edge.getId());
            if (arcIds == null) throw new IllegalStateException("Missing edge assembly provenance");
            arcIdsByEdgeId.put(edge.getId(), arcIds);
        }
        return new EdgeResult(List.copyOf(result), arcIdsByEdgeId);
    }

    private static RouteEdge routeEdge(List<SelectedArc> chain,
            CatalogNetworkProblemCompiler.Compilation compilation,
            Map<String, String> routeNodeIds,
            Map<String, NodeRealization> explicitNodeRealizations,
            EdgeSectionAssembler sectionAssembler) {
        List<RouteCoordinate> coordinates = new ArrayList<>();
        List<String> assetIds = new ArrayList<>();
        List<String> arcIds = new ArrayList<>();
        for (SelectedArc arc : chain) {
            CatalogNetworkProblemCompiler.NodeBinding from = compilation.node(arc.fromNodeId);
            CatalogNetworkProblemCompiler.NodeBinding to = compilation.node(arc.toNodeId);
            RouteCoordinate fromCoordinate = coordinate(from);
            RouteCoordinate toCoordinate = coordinate(to);
            if (coordinates.isEmpty()) coordinates.add(fromCoordinate);
            else if (!sameCoordinate(coordinates.get(coordinates.size() - 1), fromCoordinate)) {
                throw new IllegalStateException("Collapsed asset chain is geometrically discontinuous");
            }
            coordinates.add(toCoordinate);
            assetIds.add(arc.physicalAsset.getId());
            arcIds.add(arc.id);
        }
        String edgeId = "edge:" + sha256(arcIds);
        SelectedArc first = chain.get(0);
        SelectedArc last = chain.get(chain.size() - 1);
        NodeRealization upstream = explicitNodeRealizations.get(first.fromNodeId);
        if (upstream == null) {
            throw new CandidateAssemblyIncompleteException("missing_node_realization",
                    "Missing upstream node realization for edge " + edgeId);
        }
        EdgeAssembly edgeAssembly = new EdgeAssembly(edgeId, arcIds, assetIds, coordinates,
                first.diameterMm, upstream.targetId);
        List<RouteSection> sections = Objects.requireNonNull(
                sectionAssembler.sections(edgeAssembly), "assembled sections");
        return new RouteEdge(edgeId, routeNodeIds.get(first.fromNodeId), routeNodeIds.get(last.toNodeId),
                lengthM(coordinates), coordinates, sections,
                BigDecimal.valueOf(first.flowUnits, compilation.getFlowScaleDecimals()),
                first.diameterMm);
    }

    private static List<RouteConnection> connections(
            CatalogNetworkProblemCompiler.Compilation compilation) {
        List<RouteConnection> result = new ArrayList<>();
        for (CatalogNetworkProblemCompiler.DemandBinding demand : compilation.getDemandBindings()) {
            result.add(new RouteConnection(demand.getDemandId(), demand.getConnectionPointId(),
                    demand.getFlowTph(), "connected", null));
        }
        result.sort(Comparator.comparing(RouteConnection::getDemandId));
        return List.copyOf(result);
    }

    private static RouteCoordinate coordinate(CatalogNetworkProblemCompiler.NodeBinding binding) {
        return new RouteCoordinate(binding.getPoint().getXM(), binding.getPoint().getYM());
    }

    private static boolean sameCoordinate(RouteCoordinate left, RouteCoordinate right) {
        return left.getXM().equals(right.getXM()) && left.getYM().equals(right.getYM());
    }

    private static double lengthM(List<RouteCoordinate> coordinates) {
        double result = 0.0;
        for (int index = 1; index < coordinates.size(); index++) {
            RouteCoordinate left = coordinates.get(index - 1);
            RouteCoordinate right = coordinates.get(index);
            result += Math.hypot(right.getXM().doubleValue() - left.getXM().doubleValue(),
                    right.getYM().doubleValue() - left.getYM().doubleValue());
        }
        return result;
    }

    private static String sha256(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, values.size());
            for (String value : values) update(digest, value);
            try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
                for (byte item : digest.digest()) formatter.format("%02x", item);
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

    @FunctionalInterface
    public interface EdgeSectionAssembler {
        List<RouteSection> sections(EdgeAssembly edge);
    }

    public static final class EdgeAssembly {
        private final String edgeId;
        private final List<String> arcIds;
        private final List<String> physicalAssetIds;
        private final List<RouteCoordinate> coordinates;
        private final int diameterMm;
        private final String upstreamTargetId;

        EdgeAssembly(String edgeId, List<String> arcIds,
                List<String> physicalAssetIds, List<RouteCoordinate> coordinates,
                int diameterMm, String upstreamTargetId) {
            this.edgeId = edgeId;
            this.arcIds = List.copyOf(arcIds);
            this.physicalAssetIds = List.copyOf(physicalAssetIds);
            this.coordinates = List.copyOf(coordinates);
            if (diameterMm <= 0) throw new IllegalArgumentException("Positive edge diameter is required");
            this.diameterMm = diameterMm;
            this.upstreamTargetId = upstreamTargetId;
        }

        public String getEdgeId() { return edgeId; }
        public List<String> getArcIds() { return arcIds; }
        public List<String> getPhysicalAssetIds() { return physicalAssetIds; }
        public List<RouteCoordinate> getCoordinates() { return coordinates; }
        public int getDiameterMm() { return diameterMm; }
        public String getUpstreamTargetId() { return upstreamTargetId; }
    }

    /** Frozen candidate plus exact correspondence back to selected master arcs. */
    public static final class Assembly {
        private final FrozenNetworkCandidate candidate;
        private final Map<String, List<String>> arcIdsByEdgeId;

        private Assembly(FrozenNetworkCandidate candidate, Map<String, List<String>> supplied) {
            this.candidate = Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(supplied, "arcIdsByEdgeId");
            Set<String> expectedEdges = new LinkedHashSet<>();
            for (RouteEdge edge : candidate.getEdges()) expectedEdges.add(edge.getId());
            if (!supplied.keySet().equals(expectedEdges)) {
                throw new IllegalArgumentException("Every frozen edge requires exact master-arc provenance");
            }
            Map<String, List<String>> copy = new LinkedHashMap<>();
            Set<String> seenArcs = new LinkedHashSet<>();
            List<String> edgeIds = new ArrayList<>(expectedEdges);
            edgeIds.sort(Comparator.naturalOrder());
            for (String edgeId : edgeIds) {
                List<String> arcIds = supplied.get(edgeId);
                if (arcIds == null || arcIds.isEmpty()) {
                    throw new IllegalArgumentException("Frozen edge provenance cannot be empty: " + edgeId);
                }
                List<String> frozen = new ArrayList<>(arcIds.size());
                for (String arcId : arcIds) {
                    String value = required(arcId, "master arc ID");
                    if (!seenArcs.add(value)) {
                        throw new IllegalArgumentException("Master arc belongs to several frozen edges: " + value);
                    }
                    frozen.add(value);
                }
                copy.put(edgeId, List.copyOf(frozen));
            }
            this.arcIdsByEdgeId = Collections.unmodifiableMap(copy);
        }

        public static Assembly of(FrozenNetworkCandidate candidate,
                Map<String, List<String>> arcIdsByEdgeId) {
            return new Assembly(candidate, arcIdsByEdgeId);
        }

        public FrozenNetworkCandidate getCandidate() { return candidate; }
        public Map<String, List<String>> getArcIdsByEdgeId() { return arcIdsByEdgeId; }
        public List<String> arcIds(String edgeId) { return arcIdsByEdgeId.get(edgeId); }
    }

    public static final class NodeRealization {
        private final String nodeType;
        private final boolean chamber;
        private final int baseIncidentSections;
        private final String targetId;
        private final Integer existingIncidentDiameter;

        public NodeRealization(String nodeType, boolean chamber, int baseIncidentSections,
                String targetId, Integer existingIncidentDiameter) {
            this.nodeType = required(nodeType, "node type");
            if (baseIncidentSections < 0) {
                throw new IllegalArgumentException("Base incident sections cannot be negative");
            }
            if (existingIncidentDiameter != null && existingIncidentDiameter <= 0) {
                throw new IllegalArgumentException("Existing incident diameter must be positive");
            }
            this.chamber = chamber;
            this.baseIncidentSections = baseIncidentSections;
            this.targetId = targetId;
            this.existingIncidentDiameter = existingIncidentDiameter;
        }

    }

    private static final class SelectedArc {
        private final String id;
        private final String fromNodeId;
        private final String toNodeId;
        private final CatalogPhysicalAsset physicalAsset;
        private final long flowUnits;
        private final int diameterMm;

        private SelectedArc(String id, String fromNodeId, String toNodeId,
                CatalogPhysicalAsset physicalAsset, long flowUnits, int diameterMm) {
            this.id = id;
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
            this.physicalAsset = physicalAsset;
            this.flowUnits = flowUnits;
            this.diameterMm = diameterMm;
        }
    }

    private static final class EdgeResult {
        private final List<RouteEdge> edges;
        private final Map<String, List<String>> arcIdsByEdgeId;

        private EdgeResult(List<RouteEdge> edges, Map<String, List<String>> arcIdsByEdgeId) {
            this.edges = edges;
            this.arcIdsByEdgeId = arcIdsByEdgeId;
        }
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }
}
