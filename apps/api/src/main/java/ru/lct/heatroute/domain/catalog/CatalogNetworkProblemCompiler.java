package ru.lct.heatroute.domain.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;

/** Собирает конечную N05-модель из проверяемого N03-каталога без координатного склеивания. */
@Component
public final class CatalogNetworkProblemCompiler {
    private static final String PORT_PREFIX = "port\u0000";
    private static final String ENDPOINT_PREFIX = "asset-endpoint\u0000";
    private final OfficialPipeCatalog pipeCatalog;

    public CatalogNetworkProblemCompiler(OfficialPipeCatalog pipeCatalog) {
        this.pipeCatalog = Objects.requireNonNull(pipeCatalog, "pipeCatalog");
    }

    public Compilation compile(RoutingProblemSnapshot problemSnapshot,
            RoutingCatalogSnapshot catalogSnapshot, Map<String, String> demandPortById,
            Map<String, String> rootPortById, int flowScaleDecimals) {
        Objects.requireNonNull(problemSnapshot, "problemSnapshot");
        Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
        if (!catalogSnapshot.hasDeclaredPhysicalAssets()) {
            throw new IllegalArgumentException("A declared physical asset catalog is required");
        }
        if (!problemSnapshot.getSnapshotHash().equals(catalogSnapshot.getSourceSnapshotHash())) {
            throw new IllegalArgumentException("Catalog belongs to another problem snapshot");
        }
        if (!problemSnapshot.getRuleId().equals(catalogSnapshot.getRuleId())
                || !problemSnapshot.getRuleVersion().equals(catalogSnapshot.getRuleVersion())) {
            throw new IllegalArgumentException("Catalog rule version differs from problem snapshot");
        }
        if (flowScaleDecimals < 0 || flowScaleDecimals > 6) {
            throw new IllegalArgumentException("Flow scale must be between 0 and 6 decimal places");
        }
        Map<String, String> demandPorts = immutableRequiredMap(demandPortById, "demand port");
        Map<String, String> rootPorts = immutableRequiredMap(rootPortById, "root port");

        Topology topology = topology(catalogSnapshot);
        Map<String, NodeAccumulator> nodeData = new LinkedHashMap<>();
        for (String nodeId : topology.nodeIds()) nodeData.put(nodeId, new NodeAccumulator(nodeId));
        Map<String, DemandBinding> demandBindings = new LinkedHashMap<>();
        Map<String, String> rootNodeById = new LinkedHashMap<>();
        bindDemands(problemSnapshot, demandPorts, topology, nodeData,
                demandBindings, flowScaleDecimals);
        bindRoots(problemSnapshot, rootPorts, topology, nodeData, rootNodeById);

        List<NetworkConstraintProblem.Asset> modelAssets = new ArrayList<>();
        Map<String, ArcBinding> arcBindings = new LinkedHashMap<>();
        Map<String, List<String>> arcsByPhysicalAsset = new LinkedHashMap<>();
        for (ArcAccumulator arc : topology.arcs.values()) {
            CatalogPhysicalAsset asset = catalogSnapshot.physicalAsset(arc.physicalAssetId);
            List<NetworkConstraintProblem.DiameterOption> diameters = diameterDomain(
                    asset, arc, catalogSnapshot, flowScaleDecimals);
            if (diameters.isEmpty()) continue;
            String fromNodeId = topology.nodeId(arc.fromEndpointKey);
            String toNodeId = topology.nodeId(arc.toEndpointKey);
            NetworkConstraintProblem.Asset modelAsset = new NetworkConstraintProblem.Asset(
                    arc.id, fromNodeId, toNodeId, 0L, diameters);
            modelAssets.add(modelAsset);
            ArcBinding binding = new ArcBinding(arc.id, arc.physicalAssetId,
                    arc.canonicalDirection, arc.sourceOptionIds);
            arcBindings.put(arc.id, binding);
            arcsByPhysicalAsset.computeIfAbsent(arc.physicalAssetId, ignored -> new ArrayList<>()).add(arc.id);
        }
        List<NetworkConstraintProblem.Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : arcsByPhysicalAsset.entrySet()) {
            List<String> arcs = entry.getValue();
            if (arcs.size() > 1) {
                if (arcs.size() != 2) throw new IllegalStateException("Physical asset has too many directions");
                conflicts.add(new NetworkConstraintProblem.Conflict(arcs,
                        "opposite_physical_directions:" + entry.getKey()));
            }
        }
        List<NetworkConstraintProblem.Node> nodes = new ArrayList<>();
        for (NodeAccumulator value : nodeData.values()) nodes.add(value.freeze());
        NetworkConstraintProblem problem = new NetworkConstraintProblem(nodes, modelAssets, conflicts);
        return new Compilation(problem, arcBindings, topology.nodeBindings,
                demandBindings, rootNodeById, Map.of(), flowScaleDecimals,
                ObjectiveKind.LINEARIZED_CATALOG_MILLI_RUBLES);
    }

    private List<NetworkConstraintProblem.DiameterOption> diameterDomain(CatalogPhysicalAsset asset,
            ArcAccumulator arc, RoutingCatalogSnapshot catalog, int flowScaleDecimals) {
        List<NetworkConstraintProblem.DiameterOption> result = new ArrayList<>();
        for (PipeCatalogEntry entry : pipeCatalog.entries()) {
            boolean provenForbiddenEverywhere = true;
            for (String optionId : arc.sourceOptionIds) {
                DirectedPathOption option = catalog.pathOption(optionId);
                PathAdmissionCertificate.Status status = option.admissionStatus(entry.getDiameter(),
                        catalog.getRuleId(), catalog.getRuleVersion(), catalog.getSourceSnapshotHash());
                if (status != PathAdmissionCertificate.Status.PROVEN_FORBIDDEN) {
                    provenForbiddenEverywhere = false;
                    break;
                }
            }
            if (provenForbiddenEverywhere) continue;
            long capacity = scaledFlow(entry.getMaxFlowTph(), flowScaleDecimals, "catalog capacity");
            BigDecimal rate = asset.getConstructionMode() == CatalogPhysicalAsset.ConstructionMode.RECONSTRUCTION
                    ? entry.getReconstructionRubPerM() : entry.getNewConstructionRubPerM();
            long surrogateCost = rate.multiply(BigDecimal.valueOf(asset.getExactLengthMm()))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            result.add(new NetworkConstraintProblem.DiameterOption(
                    entry.getDiameter(), capacity, surrogateCost));
        }
        return result;
    }

    private static Topology topology(RoutingCatalogSnapshot catalog) {
        UnionFind components = new UnionFind();
        Map<String, CatalogMetricPoint> pointByMember = new LinkedHashMap<>();
        for (CatalogPhysicalAsset asset : catalog.getPhysicalAssets()) {
            String firstEndpoint = endpointKey(asset.getId(), false);
            String secondEndpoint = endpointKey(asset.getId(), true);
            components.add(firstEndpoint);
            components.add(secondEndpoint);
            pointByMember.put(firstEndpoint, asset.getFirstPoint());
            pointByMember.put(secondEndpoint, asset.getSecondPoint());
        }
        Map<String, ArcAccumulator> arcs = new LinkedHashMap<>();
        for (DirectedPathOption option : catalog.getPathOptions()) {
            CatalogMetricPoint current = option.getCoordinates().get(0);
            String previousEndpoint = portKey(option.getFromPortId());
            components.add(previousEndpoint);
            putPoint(pointByMember, previousEndpoint, current);
            for (String assetId : option.getPhysicalAssetIds()) {
                CatalogPhysicalAsset asset = catalog.physicalAsset(assetId);
                boolean canonical = current.equals(asset.getFirstPoint());
                String fromEndpoint = endpointKey(assetId, !canonical);
                String toEndpoint = endpointKey(assetId, canonical);
                components.union(previousEndpoint, fromEndpoint);
                String arcId = assetId + (canonical ? "/forward" : "/reverse");
                ArcAccumulator arc = arcs.computeIfAbsent(arcId,
                        ignored -> new ArcAccumulator(arcId, assetId, canonical,
                                fromEndpoint, toEndpoint));
                arc.sourceOptionIds.add(option.getId());
                current = canonical ? asset.getSecondPoint() : asset.getFirstPoint();
                previousEndpoint = toEndpoint;
            }
            String toPort = portKey(option.getToPortId());
            components.add(toPort);
            putPoint(pointByMember, toPort, current);
            components.union(previousEndpoint, toPort);
        }
        Map<String, List<String>> members = components.components();
        Map<String, String> nodeByComponent = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : members.entrySet()) {
            List<String> ports = new ArrayList<>();
            for (String member : entry.getValue()) {
                if (member.startsWith(PORT_PREFIX)) ports.add(member.substring(PORT_PREFIX.length()));
            }
            ports.sort(Comparator.naturalOrder());
            if (ports.size() > 1) {
                throw new IllegalArgumentException("Physical chain aliases distinct ports: " + ports);
            }
            String nodeId = ports.isEmpty() ? "book:" + sha256(entry.getValue()) : ports.get(0);
            nodeByComponent.put(entry.getKey(), nodeId);
        }
        Map<String, String> nodeByMember = new LinkedHashMap<>();
        Map<String, NodeBinding> nodeBindings = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : members.entrySet()) {
            String nodeId = nodeByComponent.get(entry.getKey());
            CatalogMetricPoint point = null;
            for (String member : entry.getValue()) nodeByMember.put(member, nodeId);
            for (String member : entry.getValue()) {
                CatalogMetricPoint candidate = pointByMember.get(member);
                if (candidate == null) continue;
                if (point != null && !point.equals(candidate)) {
                    throw new IllegalArgumentException("Catalog chain joins different metric points at node: "
                            + nodeId);
                }
                point = candidate;
            }
            if (point == null) throw new IllegalStateException("Catalog topology node has no coordinate");
            String explicitPortId = nodeId.startsWith("book:") ? null : nodeId;
            nodeBindings.put(nodeId, new NodeBinding(nodeId, point, explicitPortId));
        }
        return new Topology(nodeByMember, nodeBindings, arcs);
    }

    private static void putPoint(Map<String, CatalogMetricPoint> pointByMember,
            String member, CatalogMetricPoint point) {
        CatalogMetricPoint previous = pointByMember.putIfAbsent(member, point);
        if (previous != null && !previous.equals(point)) {
            throw new IllegalArgumentException("Catalog port has inconsistent coordinates: "
                    + member.substring(PORT_PREFIX.length()));
        }
    }

    private static void bindDemands(RoutingProblemSnapshot snapshot, Map<String, String> ports,
            Topology topology, Map<String, NodeAccumulator> nodes,
            Map<String, DemandBinding> bindings, int scale) {
        Set<String> expected = new LinkedHashSet<>();
        for (RoutingProblemSnapshot.Demand demand : snapshot.getDemands()) {
            expected.add(demand.getId());
            String portId = ports.get(demand.getId());
            if (portId == null) throw new IllegalArgumentException("Missing port for demand: " + demand.getId());
            NodeAccumulator node = nodes.get(topology.nodeId(portKey(portId)));
            if (node == null) throw new IllegalArgumentException("Demand references an unknown catalog port: " + portId);
            long demandUnits = scaledFlow(demand.getFlowTph(), scale, "demand " + demand.getId());
            node.demandUnits = Math.addExact(node.demandUnits, demandUnits);
            node.mandatoryTerminal = true;
            bindings.put(demand.getId(), new DemandBinding(
                    demand.getId(), portId, node.id, demand.getConnectionPointId(),
                    demand.getFlowTph(), demandUnits));
        }
        if (!ports.keySet().equals(expected)) throw new IllegalArgumentException("Demand port map IDs differ from snapshot");
    }

    private static void bindRoots(RoutingProblemSnapshot snapshot, Map<String, String> ports,
            Topology topology, Map<String, NodeAccumulator> nodes,
            Map<String, String> rootNodeById) {
        Set<String> expected = new LinkedHashSet<>();
        for (RoutingProblemSnapshot.RootCandidate root : snapshot.getRoots()) {
            expected.add(root.getId());
            String portId = ports.get(root.getId());
            if (portId == null) throw new IllegalArgumentException("Missing port for root: " + root.getId());
            NodeAccumulator node = nodes.get(topology.nodeId(portKey(portId)));
            if (node == null) throw new IllegalArgumentException("Root references an unknown catalog port: " + portId);
            node.allowedRoot = true;
            rootNodeById.put(root.getId(), node.id);
        }
        if (!ports.keySet().equals(expected)) throw new IllegalArgumentException("Root port map IDs differ from snapshot");
    }

    private static long scaledFlow(BigDecimal flow, int decimals, String label) {
        try {
            long result = flow.movePointRight(decimals).longValueExact();
            if (result < 0L) throw new IllegalArgumentException(label + " cannot be negative");
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + " exceeds the configured exact flow scale", exception);
        }
    }

    private static Map<String, String> immutableRequiredMap(Map<String, String> source, String label) {
        if (source == null) throw new IllegalArgumentException(label + " map is required");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = required(entry.getKey(), label + " owner");
            String value = required(entry.getValue(), label + " ID");
            if (result.put(key, value) != null) throw new IllegalArgumentException("Duplicate " + label + " owner");
        }
        return Collections.unmodifiableMap(result);
    }

    private static String endpointKey(String assetId, boolean second) {
        return ENDPOINT_PREFIX + assetId + (second ? "\u00001" : "\u00000");
    }

    private static String portKey(String portId) { return PORT_PREFIX + portId; }

    private static String sha256(Collection<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> ordered = new ArrayList<>(values);
            ordered.sort(Comparator.naturalOrder());
            update(digest, ordered.size());
            for (String value : ordered) update(digest, value);
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

    public enum ObjectiveKind { LINEARIZED_CATALOG_MILLI_RUBLES }

    public static final class Compilation {
        private final NetworkConstraintProblem problem;
        private final Map<String, ArcBinding> arcBindings;
        private final Map<String, NodeBinding> nodeBindings;
        private final Map<String, DemandBinding> demandBindings;
        private final Map<String, String> rootNodeById;
        private final Map<String, NodeConfigurationBinding> nodeConfigurationBindings;
        private final int flowScaleDecimals;
        private final ObjectiveKind objectiveKind;

        private Compilation(NetworkConstraintProblem problem, Map<String, ArcBinding> arcBindings,
                Map<String, NodeBinding> nodeBindings, Map<String, DemandBinding> demandBindings,
                Map<String, String> rootNodeById,
                Map<String, NodeConfigurationBinding> nodeConfigurationBindings,
                int flowScaleDecimals, ObjectiveKind objectiveKind) {
            this.problem = problem;
            this.arcBindings = Collections.unmodifiableMap(new LinkedHashMap<>(arcBindings));
            this.nodeBindings = Collections.unmodifiableMap(new LinkedHashMap<>(nodeBindings));
            this.demandBindings = Collections.unmodifiableMap(new LinkedHashMap<>(demandBindings));
            this.rootNodeById = Collections.unmodifiableMap(new LinkedHashMap<>(rootNodeById));
            this.nodeConfigurationBindings = Collections.unmodifiableMap(
                    new LinkedHashMap<>(nodeConfigurationBindings));
            this.flowScaleDecimals = flowScaleDecimals;
            this.objectiveKind = objectiveKind;
        }

        public NetworkConstraintProblem getProblem() { return problem; }
        public Collection<ArcBinding> getArcBindings() { return arcBindings.values(); }
        public ArcBinding arc(String id) { return arcBindings.get(id); }
        public Collection<NodeBinding> getNodeBindings() { return nodeBindings.values(); }
        public NodeBinding node(String id) { return nodeBindings.get(id); }
        public Collection<DemandBinding> getDemandBindings() { return demandBindings.values(); }
        public DemandBinding demand(String id) { return demandBindings.get(id); }
        public Map<String, String> getRootNodeById() { return rootNodeById; }
        public Collection<NodeConfigurationBinding> getNodeConfigurationBindings() {
            return nodeConfigurationBindings.values();
        }
        public NodeConfigurationBinding nodeConfiguration(String id) {
            return nodeConfigurationBindings.get(id);
        }
        public int getFlowScaleDecimals() { return flowScaleDecimals; }
        public ObjectiveKind getObjectiveKind() { return objectiveKind; }

        /** Возвращает ту же топологию с обязательными точными конфигурациями каждого узла. */
        public Compilation withNodeConfigurations(
                Collection<NodeConfigurationBinding> supplied) {
            Objects.requireNonNull(supplied, "node configurations");
            Map<String, NodeConfigurationBinding> bindings = new LinkedHashMap<>();
            List<NetworkConstraintProblem.NodeConfiguration> configurations = new ArrayList<>();
            for (NodeConfigurationBinding binding : supplied) {
                Objects.requireNonNull(binding, "node configuration");
                NetworkConstraintProblem.NodeConfiguration configuration =
                        binding.getConfiguration();
                if (!nodeBindings.containsKey(configuration.getNodeId())) {
                    throw new IllegalArgumentException(
                            "Configuration references an unknown compiled node: "
                                    + configuration.getNodeId());
                }
                if (bindings.put(configuration.getId(), binding) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate node configuration binding: " + configuration.getId());
                }
                configurations.add(configuration);
            }
            List<NetworkConstraintProblem.Node> managedNodes = new ArrayList<>();
            for (NetworkConstraintProblem.Node node : problem.getNodes()) {
                managedNodes.add(new NetworkConstraintProblem.Node(
                        node.getId(), node.isAllowedRoot(), node.getDemandUnits(),
                        node.isTerminal(), true));
            }
            NetworkConstraintProblem configuredProblem = new NetworkConstraintProblem(
                    managedNodes, problem.getAssets(), configurations, problem.getConflicts());
            return new Compilation(configuredProblem, arcBindings, nodeBindings,
                    demandBindings, rootNodeById, bindings, flowScaleDecimals, objectiveKind);
        }
    }

    /** Связывает master-конфигурацию с точной семантикой публикуемого RouteNode. */
    public static final class NodeConfigurationBinding {
        private final NetworkConstraintProblem.NodeConfiguration configuration;
        private final String nodeType;
        private final boolean chamber;
        private final int baseIncidentSections;
        private final String targetId;
        private final Integer existingIncidentDiameter;

        public NodeConfigurationBinding(
                NetworkConstraintProblem.NodeConfiguration configuration,
                String nodeType, boolean chamber, int baseIncidentSections,
                String targetId, Integer existingIncidentDiameter) {
            this.configuration = Objects.requireNonNull(configuration, "configuration");
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

        public NetworkConstraintProblem.NodeConfiguration getConfiguration() {
            return configuration;
        }
        public String getNodeType() { return nodeType; }
        public boolean isChamber() { return chamber; }
        public int getBaseIncidentSections() { return baseIncidentSections; }
        public String getTargetId() { return targetId; }
        public Integer getExistingIncidentDiameter() { return existingIncidentDiameter; }
    }

    public static final class NodeBinding {
        private final String nodeId;
        private final CatalogMetricPoint point;
        private final String explicitPortId;

        private NodeBinding(String nodeId, CatalogMetricPoint point, String explicitPortId) {
            this.nodeId = nodeId;
            this.point = point;
            this.explicitPortId = explicitPortId;
        }

        public String getNodeId() { return nodeId; }
        public CatalogMetricPoint getPoint() { return point; }
        public String getExplicitPortId() { return explicitPortId; }
        public boolean isExplicitPort() { return explicitPortId != null; }
    }

    public static final class DemandBinding {
        private final String demandId;
        private final String portId;
        private final String nodeId;
        private final String connectionPointId;
        private final BigDecimal flowTph;
        private final long flowUnits;

        private DemandBinding(String demandId, String portId, String nodeId,
                String connectionPointId, BigDecimal flowTph, long flowUnits) {
            this.demandId = demandId;
            this.portId = portId;
            this.nodeId = nodeId;
            this.connectionPointId = connectionPointId;
            this.flowTph = flowTph;
            this.flowUnits = flowUnits;
        }

        public String getDemandId() { return demandId; }
        public String getPortId() { return portId; }
        public String getNodeId() { return nodeId; }
        public String getConnectionPointId() { return connectionPointId; }
        public BigDecimal getFlowTph() { return flowTph; }
        public long getFlowUnits() { return flowUnits; }
    }

    public static final class ArcBinding {
        private final String arcId;
        private final String physicalAssetId;
        private final boolean canonicalDirection;
        private final List<String> sourceOptionIds;

        private ArcBinding(String arcId, String physicalAssetId, boolean canonicalDirection,
                Collection<String> sourceOptionIds) {
            this.arcId = arcId;
            this.physicalAssetId = physicalAssetId;
            this.canonicalDirection = canonicalDirection;
            List<String> sources = new ArrayList<>(sourceOptionIds);
            sources.sort(Comparator.naturalOrder());
            this.sourceOptionIds = List.copyOf(sources);
        }

        public String getArcId() { return arcId; }
        public String getPhysicalAssetId() { return physicalAssetId; }
        public boolean isCanonicalDirection() { return canonicalDirection; }
        public List<String> getSourceOptionIds() { return sourceOptionIds; }
    }

    private static final class Topology {
        private final Map<String, String> nodeByMember;
        private final Map<String, NodeBinding> nodeBindings;
        private final Map<String, ArcAccumulator> arcs;

        private Topology(Map<String, String> nodeByMember, Map<String, NodeBinding> nodeBindings,
                Map<String, ArcAccumulator> arcs) {
            this.nodeByMember = nodeByMember;
            this.nodeBindings = nodeBindings;
            this.arcs = arcs;
        }

        private String nodeId(String member) { return nodeByMember.get(member); }
        private Set<String> nodeIds() { return new LinkedHashSet<>(nodeBindings.keySet()); }
    }

    private static final class ArcAccumulator {
        private final String id;
        private final String physicalAssetId;
        private final boolean canonicalDirection;
        private final String fromEndpointKey;
        private final String toEndpointKey;
        private final Set<String> sourceOptionIds = new LinkedHashSet<>();

        private ArcAccumulator(String id, String physicalAssetId, boolean canonicalDirection,
                String fromEndpointKey, String toEndpointKey) {
            this.id = id;
            this.physicalAssetId = physicalAssetId;
            this.canonicalDirection = canonicalDirection;
            this.fromEndpointKey = fromEndpointKey;
            this.toEndpointKey = toEndpointKey;
        }
    }

    private static final class NodeAccumulator {
        private final String id;
        private boolean allowedRoot;
        private long demandUnits;
        private boolean mandatoryTerminal;

        private NodeAccumulator(String id) { this.id = id; }

        private NetworkConstraintProblem.Node freeze() {
            return new NetworkConstraintProblem.Node(id, allowedRoot, demandUnits, mandatoryTerminal);
        }
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new LinkedHashMap<>();

        private void add(String value) { parent.putIfAbsent(value, value); }

        private String find(String value) {
            String direct = parent.get(value);
            if (direct == null) throw new IllegalArgumentException("Unknown topology member: " + value);
            if (direct.equals(value)) return value;
            String root = find(direct);
            parent.put(value, root);
            return root;
        }

        private void union(String left, String right) {
            add(left);
            add(right);
            String leftRoot = find(left);
            String rightRoot = find(right);
            if (leftRoot.equals(rightRoot)) return;
            if (leftRoot.compareTo(rightRoot) <= 0) parent.put(rightRoot, leftRoot);
            else parent.put(leftRoot, rightRoot);
        }

        private Map<String, List<String>> components() {
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String value : new ArrayList<>(parent.keySet())) {
                result.computeIfAbsent(find(value), ignored -> new ArrayList<>()).add(value);
            }
            for (List<String> values : result.values()) values.sort(Comparator.naturalOrder());
            return result;
        }
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }
}
