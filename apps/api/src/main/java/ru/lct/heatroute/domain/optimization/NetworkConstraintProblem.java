package ru.lct.heatroute.domain.optimization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Конечный каталог направленных активов, точных конфигураций узлов, корней, расходов и ДУ. */
public final class NetworkConstraintProblem {
    private final List<Node> nodes;
    private final List<Asset> assets;
    private final List<NodeConfiguration> nodeConfigurations;
    private final List<Conflict> conflicts;
    private final Map<String, Node> nodesById;
    private final Map<String, Asset> assetsById;
    private final Map<String, NodeConfiguration> nodeConfigurationsById;
    private final long totalDemandUnits;

    public NetworkConstraintProblem(
            Collection<Node> nodes, Collection<Asset> assets, Collection<Conflict> conflicts) {
        this(nodes, assets, List.of(), conflicts);
    }

    public NetworkConstraintProblem(Collection<Node> nodes, Collection<Asset> assets,
            Collection<NodeConfiguration> nodeConfigurations,
            Collection<Conflict> conflicts) {
        if (nodes == null || nodes.isEmpty() || assets == null || assets.isEmpty() || conflicts == null) {
            throw new IllegalArgumentException("Non-empty nodes/assets and a conflicts collection are required");
        }
        this.nodes = sortedUnique(nodes, Node::getId, "node");
        this.assets = sortedUnique(assets, Asset::getId, "asset");
        this.nodeConfigurations = sortedUnique(
                Objects.requireNonNull(nodeConfigurations, "nodeConfigurations"),
                NodeConfiguration::getId, "node configuration");
        this.nodesById = index(this.nodes, Node::getId);
        this.assetsById = index(this.assets, Asset::getId);
        this.nodeConfigurationsById = index(this.nodeConfigurations, NodeConfiguration::getId);
        long demand = 0L;
        boolean hasRoot = false;
        boolean hasTerminal = false;
        for (Node node : this.nodes) {
            demand = Math.addExact(demand, node.demandUnits);
            hasRoot |= node.allowedRoot;
            hasTerminal |= node.mandatoryTerminal;
            if (node.allowedRoot && node.mandatoryTerminal) {
                throw new IllegalArgumentException("A terminal cannot also be an allowed root: " + node.id);
            }
        }
        if (!hasRoot || !hasTerminal) {
            throw new IllegalArgumentException("At least one root and mandatory terminal required");
        }
        long objectiveUpperBound = 0L;
        for (Asset asset : this.assets) {
            if (!nodesById.containsKey(asset.fromNodeId) || !nodesById.containsKey(asset.toNodeId)) {
                throw new IllegalArgumentException("Asset references an unknown node: " + asset.id);
            }
            if (asset.fromNodeId.equals(asset.toNodeId)) {
                throw new IllegalArgumentException("Self-loop assets are not allowed: " + asset.id);
            }
            long maximumDiameterCost = asset.diameters.stream()
                    .mapToLong(DiameterOption::getCostUnits).max().orElseThrow();
            objectiveUpperBound = Math.addExact(objectiveUpperBound,
                    Math.addExact(asset.fixedCostUnits, maximumDiameterCost));
        }
        if (objectiveUpperBound < 0L) throw new IllegalArgumentException("Objective overflow");
        Map<String, Set<Set<String>>> incidentSetsByNode = new LinkedHashMap<>();
        for (NodeConfiguration configuration : this.nodeConfigurations) {
            Node node = nodesById.get(configuration.nodeId);
            if (node == null || !node.configurationRequired) {
                throw new IllegalArgumentException(
                        "Configuration references an unknown or unmanaged node: " + configuration.id);
            }
            for (String assetId : configuration.incidentAssetIds) {
                Asset asset = assetsById.get(assetId);
                if (asset == null || !asset.fromNodeId.equals(node.id)
                        && !asset.toNodeId.equals(node.id)) {
                    throw new IllegalArgumentException(
                            "Configuration references a non-incident asset: " + configuration.id);
                }
            }
            Set<Set<String>> incidentSets = incidentSetsByNode.computeIfAbsent(
                    node.id, ignored -> new HashSet<>());
            if (!incidentSets.add(configuration.incidentAssetIds)) {
                throw new IllegalArgumentException(
                        "Duplicate node configuration incidence at node: " + node.id);
            }
        }
        List<Conflict> orderedConflicts = new ArrayList<>(conflicts);
        orderedConflicts.sort(Comparator.comparing(Conflict::signature));
        Set<String> conflictSignatures = new HashSet<>();
        for (Conflict conflict : orderedConflicts) {
            if (conflict == null || !conflictSignatures.add(conflict.signature())) {
                throw new IllegalArgumentException("Conflicts must be non-null and unique");
            }
            for (DecisionLiteral literal : conflict.literals) validateLiteral(literal);
        }
        this.conflicts = List.copyOf(orderedConflicts);
        this.totalDemandUnits = demand;
    }

    public List<Node> getNodes() { return nodes; }
    public List<Asset> getAssets() { return assets; }
    public List<NodeConfiguration> getNodeConfigurations() { return nodeConfigurations; }
    public List<Conflict> getConflicts() { return conflicts; }
    public Node node(String id) { return nodesById.get(id); }
    public Asset asset(String id) { return assetsById.get(id); }
    public NodeConfiguration nodeConfiguration(String id) { return nodeConfigurationsById.get(id); }
    public long getTotalDemandUnits() { return totalDemandUnits; }

    private void validateLiteral(DecisionLiteral literal) {
        switch (literal.type) {
            case ASSET_SELECTED:
                if (!assetsById.containsKey(literal.subjectId)) {
                    throw new IllegalArgumentException("Conflict references an unknown asset: " + literal.subjectId);
                }
                return;
            case ROOT_SELECTED:
                Node node = nodesById.get(literal.subjectId);
                if (node == null || !node.allowedRoot) {
                    throw new IllegalArgumentException("Conflict references an unknown root: " + literal.subjectId);
                }
                return;
            case NODE_CONFIGURATION_SELECTED:
                if (!nodeConfigurationsById.containsKey(literal.subjectId)) {
                    throw new IllegalArgumentException(
                            "Conflict references an unknown node configuration: " + literal.subjectId);
                }
                return;
            case DIAMETER_SELECTED:
                Asset asset = assetsById.get(literal.subjectId);
                boolean exists = asset != null && asset.diameters.stream()
                        .anyMatch(option -> option.diameterMm == literal.diameterMm);
                if (!exists) {
                    throw new IllegalArgumentException("Conflict references an unknown diameter: " + literal.variableKey());
                }
                return;
            default:
                throw new IllegalStateException("Unsupported conflict literal type: " + literal.type);
        }
    }

    private static <T> List<T> sortedUnique(Collection<T> source,
            java.util.function.Function<T, String> id, String label) {
        List<T> ordered = new ArrayList<>(source);
        if (ordered.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException(label + " cannot be null");
        ordered.sort(Comparator.comparing(id));
        Set<String> ids = new HashSet<>();
        for (T item : ordered) if (!ids.add(id.apply(item))) {
            throw new IllegalArgumentException("Duplicate " + label + " ID: " + id.apply(item));
        }
        return List.copyOf(ordered);
    }

    private static <T> Map<String, T> index(List<T> values, java.util.function.Function<T, String> id) {
        Map<String, T> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(id.apply(value), value));
        return Collections.unmodifiableMap(result);
    }

    public static final class Node {
        private final String id;
        private final boolean allowedRoot;
        private final long demandUnits;
        private final boolean mandatoryTerminal;
        private final boolean configurationRequired;

        public Node(String id, boolean allowedRoot, long demandUnits) {
            this(id, allowedRoot, demandUnits, demandUnits > 0L, false);
        }

        public Node(String id, boolean allowedRoot, long demandUnits, boolean mandatoryTerminal) {
            this(id, allowedRoot, demandUnits, mandatoryTerminal, false);
        }

        public Node(String id, boolean allowedRoot, long demandUnits,
                boolean mandatoryTerminal, boolean configurationRequired) {
            this.id = required(id, "node");
            if (demandUnits < 0L) throw new IllegalArgumentException("Node demand cannot be negative");
            this.allowedRoot = allowedRoot;
            this.demandUnits = demandUnits;
            this.mandatoryTerminal = mandatoryTerminal;
            this.configurationRequired = configurationRequired;
        }

        public String getId() { return id; }
        public boolean isAllowedRoot() { return allowedRoot; }
        public long getDemandUnits() { return demandUnits; }
        public boolean isTerminal() { return mandatoryTerminal; }
        public boolean isConfigurationRequired() { return configurationRequired; }
    }

    /** Точный набор направленных дуг, допустимый в одном используемом узле. */
    public static final class NodeConfiguration {
        private final String id;
        private final String nodeId;
        private final Set<String> incidentAssetIds;

        public NodeConfiguration(String id, String nodeId, Collection<String> incidentAssetIds) {
            this.id = required(id, "node configuration");
            this.nodeId = required(nodeId, "configuration node");
            if (incidentAssetIds == null || incidentAssetIds.isEmpty()) {
                throw new IllegalArgumentException("Node configuration incidence cannot be empty");
            }
            List<String> ordered = new ArrayList<>(incidentAssetIds.size());
            for (String assetId : incidentAssetIds) {
                ordered.add(required(assetId, "configuration asset"));
            }
            ordered.sort(Comparator.naturalOrder());
            if (ordered.stream().distinct().count() != ordered.size()) {
                throw new IllegalArgumentException("Node configuration assets must be unique");
            }
            this.incidentAssetIds = Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
        }

        public String getId() { return id; }
        public String getNodeId() { return nodeId; }
        public Set<String> getIncidentAssetIds() { return incidentAssetIds; }
    }

    public static final class Asset {
        private final String id;
        private final String fromNodeId;
        private final String toNodeId;
        private final long fixedCostUnits;
        private final List<DiameterOption> diameters;

        public Asset(String id, String fromNodeId, String toNodeId, long fixedCostUnits,
                Collection<DiameterOption> diameters) {
            this.id = required(id, "asset");
            this.fromNodeId = required(fromNodeId, "asset from-node");
            this.toNodeId = required(toNodeId, "asset to-node");
            if (fixedCostUnits < 0L) throw new IllegalArgumentException("Asset cost cannot be negative");
            this.fixedCostUnits = fixedCostUnits;
            if (diameters == null || diameters.isEmpty()) {
                throw new IllegalArgumentException("Every asset requires a diameter domain");
            }
            List<DiameterOption> ordered = new ArrayList<>(diameters);
            ordered.sort(Comparator.comparingInt(DiameterOption::getDiameterMm));
            Set<Integer> ids = new HashSet<>();
            for (DiameterOption option : ordered) {
                if (option == null || !ids.add(option.diameterMm)) {
                    throw new IllegalArgumentException("Diameter options must be non-null and unique");
                }
            }
            this.diameters = List.copyOf(ordered);
        }

        public String getId() { return id; }
        public String getFromNodeId() { return fromNodeId; }
        public String getToNodeId() { return toNodeId; }
        public long getFixedCostUnits() { return fixedCostUnits; }
        public List<DiameterOption> getDiameters() { return diameters; }
    }

    public static final class DiameterOption {
        private final int diameterMm;
        private final long capacityUnits;
        private final long costUnits;

        public DiameterOption(int diameterMm, long capacityUnits, long costUnits) {
            if (diameterMm <= 0 || capacityUnits <= 0L || costUnits < 0L) {
                throw new IllegalArgumentException("Positive diameter/capacity and non-negative cost required");
            }
            this.diameterMm = diameterMm;
            this.capacityUnits = capacityUnits;
            this.costUnits = costUnits;
        }

        public int getDiameterMm() { return diameterMm; }
        public long getCapacityUnits() { return capacityUnits; }
        public long getCostUnits() { return costUnits; }
    }

    /** Boolean-решение master-модели с ожидаемым значением в доказанно невозможной конъюнкции. */
    public static final class DecisionLiteral {
        public enum Type {
            ASSET_SELECTED,
            ROOT_SELECTED,
            NODE_CONFIGURATION_SELECTED,
            DIAMETER_SELECTED
        }

        private final Type type;
        private final String subjectId;
        private final Integer diameterMm;
        private final boolean expected;

        private DecisionLiteral(Type type, String subjectId, Integer diameterMm, boolean expected) {
            this.type = Objects.requireNonNull(type, "type");
            this.subjectId = required(subjectId, "literal subject");
            if ((type == Type.DIAMETER_SELECTED && (diameterMm == null || diameterMm <= 0))
                    || (type != Type.DIAMETER_SELECTED && diameterMm != null)) {
                throw new IllegalArgumentException("Diameter is required only for diameter literals");
            }
            this.diameterMm = diameterMm;
            this.expected = expected;
        }

        public static DecisionLiteral asset(String assetId, boolean selected) {
            return new DecisionLiteral(Type.ASSET_SELECTED, assetId, null, selected);
        }

        public static DecisionLiteral root(String nodeId, boolean selected) {
            return new DecisionLiteral(Type.ROOT_SELECTED, nodeId, null, selected);
        }

        public static DecisionLiteral nodeConfiguration(String configurationId, boolean selected) {
            return new DecisionLiteral(
                    Type.NODE_CONFIGURATION_SELECTED, configurationId, null, selected);
        }

        public static DecisionLiteral diameter(String assetId, int diameterMm, boolean selected) {
            return new DecisionLiteral(Type.DIAMETER_SELECTED, assetId, diameterMm, selected);
        }

        public Type getType() { return type; }
        public String getSubjectId() { return subjectId; }
        public Integer getDiameterMm() { return diameterMm; }
        public boolean isExpected() { return expected; }

        /** Стабильная identity Boolean-переменной без ожидаемого значения. */
        public String variableKey() {
            return type.name() + ":" + subjectId
                    + (diameterMm == null ? "" : ":" + diameterMm);
        }

        private String signature() { return variableKey() + "=" + expected; }
    }

    /** Конъюнкция Boolean literals, которая доказанно не может быть истинна целиком. */
    public static final class Conflict {
        private final List<DecisionLiteral> literals;
        private final String reason;

        public Conflict(Collection<String> assetIds, String reason) {
            this(assetLiterals(assetIds), reason, true);
        }

        public static Conflict ofLiterals(Collection<DecisionLiteral> literals, String reason) {
            return new Conflict(literals, reason, true);
        }

        private Conflict(Collection<DecisionLiteral> literals, String reason, boolean ignored) {
            if (literals == null || literals.isEmpty()) throw new IllegalArgumentException("Conflict cannot be empty");
            List<DecisionLiteral> ordered = new ArrayList<>(literals);
            if (ordered.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Conflict literal cannot be null");
            }
            ordered.sort(Comparator.comparing(DecisionLiteral::signature));
            Map<String, Boolean> expectations = new LinkedHashMap<>();
            for (DecisionLiteral literal : ordered) {
                Boolean previous = expectations.putIfAbsent(literal.variableKey(), literal.expected);
                if (previous != null) {
                    String detail = previous == literal.expected ? "Duplicate" : "Contradictory";
                    throw new IllegalArgumentException(detail + " conflict literal: " + literal.variableKey());
                }
            }
            this.literals = List.copyOf(ordered);
            this.reason = required(reason, "conflict reason");
        }

        private static List<DecisionLiteral> assetLiterals(Collection<String> assetIds) {
            if (assetIds == null) throw new IllegalArgumentException("Conflict cannot be null");
            List<DecisionLiteral> result = new ArrayList<>(assetIds.size());
            for (String assetId : assetIds) result.add(DecisionLiteral.asset(assetId, true));
            return result;
        }

        public List<DecisionLiteral> getLiterals() { return literals; }
        public String getReason() { return reason; }
        private String signature() {
            return literals.stream().map(DecisionLiteral::signature)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]#" + reason));
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " ID required");
        return value;
    }
}
