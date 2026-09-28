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

/** Конечный целочисленный каталог направленных физических активов, корней, расходов и ДУ. */
public final class NetworkConstraintProblem {
    private final List<Node> nodes;
    private final List<Asset> assets;
    private final List<Conflict> conflicts;
    private final Map<String, Node> nodesById;
    private final Map<String, Asset> assetsById;
    private final long totalDemandUnits;

    public NetworkConstraintProblem(
            Collection<Node> nodes, Collection<Asset> assets, Collection<Conflict> conflicts) {
        if (nodes == null || nodes.isEmpty() || assets == null || assets.isEmpty() || conflicts == null) {
            throw new IllegalArgumentException("Non-empty nodes/assets and a conflicts collection are required");
        }
        this.nodes = sortedUnique(nodes, Node::getId, "node");
        this.assets = sortedUnique(assets, Asset::getId, "asset");
        this.nodesById = index(this.nodes, Node::getId);
        this.assetsById = index(this.assets, Asset::getId);
        long demand = 0L;
        boolean hasRoot = false;
        for (Node node : this.nodes) {
            demand = Math.addExact(demand, node.demandUnits);
            hasRoot |= node.allowedRoot;
            if (node.allowedRoot && node.demandUnits > 0L) {
                throw new IllegalArgumentException("A terminal cannot also be an allowed root: " + node.id);
            }
        }
        if (!hasRoot || demand <= 0L) throw new IllegalArgumentException("At least one root and positive demand required");
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
        List<Conflict> orderedConflicts = new ArrayList<>(conflicts);
        orderedConflicts.sort(Comparator.comparing(Conflict::signature));
        Set<String> conflictSignatures = new HashSet<>();
        for (Conflict conflict : orderedConflicts) {
            if (conflict == null || !conflictSignatures.add(conflict.signature())) {
                throw new IllegalArgumentException("Conflicts must be non-null and unique");
            }
            for (String assetId : conflict.assetIds) {
                if (!assetsById.containsKey(assetId)) {
                    throw new IllegalArgumentException("Conflict references an unknown asset: " + assetId);
                }
            }
        }
        this.conflicts = List.copyOf(orderedConflicts);
        this.totalDemandUnits = demand;
    }

    public List<Node> getNodes() { return nodes; }
    public List<Asset> getAssets() { return assets; }
    public List<Conflict> getConflicts() { return conflicts; }
    public Node node(String id) { return nodesById.get(id); }
    public Asset asset(String id) { return assetsById.get(id); }
    public long getTotalDemandUnits() { return totalDemandUnits; }

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

        public Node(String id, boolean allowedRoot, long demandUnits) {
            this.id = required(id, "node");
            if (demandUnits < 0L) throw new IllegalArgumentException("Node demand cannot be negative");
            this.allowedRoot = allowedRoot;
            this.demandUnits = demandUnits;
        }

        public String getId() { return id; }
        public boolean isAllowedRoot() { return allowedRoot; }
        public long getDemandUnits() { return demandUnits; }
        public boolean isTerminal() { return demandUnits > 0L; }
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

    /** Набор физических активов, которые доказанно нельзя выбрать одновременно. */
    public static final class Conflict {
        private final List<String> assetIds;
        private final String reason;

        public Conflict(Collection<String> assetIds, String reason) {
            if (assetIds == null || assetIds.isEmpty()) throw new IllegalArgumentException("Conflict cannot be empty");
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String assetId : assetIds) unique.add(required(assetId, "conflict asset"));
            if (unique.size() != assetIds.size()) throw new IllegalArgumentException("Duplicate conflict asset");
            List<String> ordered = new ArrayList<>(unique);
            Collections.sort(ordered);
            this.assetIds = List.copyOf(ordered);
            this.reason = required(reason, "conflict reason");
        }

        public List<String> getAssetIds() { return assetIds; }
        public String getReason() { return reason; }
        private String signature() { return assetIds + "#" + reason; }
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " ID required");
        return value;
    }
}
