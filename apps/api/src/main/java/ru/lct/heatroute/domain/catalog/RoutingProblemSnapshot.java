package ru.lct.heatroute.domain.catalog;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Formatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

/**
 * Неизменяемый малый input нового solver. Большая геометрия остаётся в неизменяемом window source,
 * на который snapshot ссылается по import/source version, а не копируется целиком в heap.
 */
public final class RoutingProblemSnapshot {
    public static final String METRIC_CRS = "EPSG:32637";

    private final UUID importId;
    private final String sourceHash;
    private final String inputProfile;
    private final String codeVersion;
    private final String ruleId;
    private final String ruleVersion;
    private final String costCatalogVersion;
    private final String featureSourceVersion;
    private final OfficialRunParameters parameters;
    private final List<Demand> demands;
    private final List<RootCandidate> roots;
    private final String snapshotHash;

    public RoutingProblemSnapshot(UUID importId, String sourceHash, String inputProfile,
            String codeVersion, String ruleId, String ruleVersion, String costCatalogVersion,
            String featureSourceVersion, OfficialRunParameters parameters,
            Collection<Demand> demands, Collection<RootCandidate> roots) {
        this.importId = Objects.requireNonNull(importId, "importId");
        this.sourceHash = required(sourceHash, "source hash");
        this.inputProfile = required(inputProfile, "input profile");
        this.codeVersion = required(codeVersion, "code version");
        this.ruleId = required(ruleId, "rule ID");
        this.ruleVersion = required(ruleVersion, "rule version");
        this.costCatalogVersion = required(costCatalogVersion, "cost catalog version");
        this.featureSourceVersion = required(featureSourceVersion, "feature source version");
        OfficialRunParameters sourceParameters = Objects.requireNonNull(parameters, "parameters").validated();
        this.parameters = new OfficialRunParameters(sourceParameters.getMinimumDepthM(),
                sourceParameters.getMaximumDepthM(), sourceParameters.isDepthEnabled(),
                sourceParameters.getAlgorithmProfile()).validated();
        this.demands = sortedUnique(demands, Demand::getId, "demand");
        this.roots = sortedUnique(roots, RootCandidate::getId, "root");
        if (this.demands.isEmpty() || this.roots.isEmpty()) {
            throw new IllegalArgumentException("At least one demand and root are required");
        }
        this.snapshotHash = snapshotHash();
    }

    public UUID getImportId() { return importId; }
    public String getSourceHash() { return sourceHash; }
    public String getInputProfile() { return inputProfile; }
    public String getMetricCrs() { return METRIC_CRS; }
    public String getCodeVersion() { return codeVersion; }
    public String getRuleId() { return ruleId; }
    public String getRuleVersion() { return ruleVersion; }
    public String getCostCatalogVersion() { return costCatalogVersion; }
    public String getFeatureSourceVersion() { return featureSourceVersion; }
    public OfficialRunParameters getParameters() { return parameters; }
    public List<Demand> getDemands() { return demands; }
    public List<RootCandidate> getRoots() { return roots; }
    public String getSnapshotHash() { return snapshotHash; }

    private String snapshotHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, importId, sourceHash, inputProfile, METRIC_CRS, codeVersion, ruleId,
                    ruleVersion, costCatalogVersion, featureSourceVersion,
                    parameters.getMinimumDepthM().stripTrailingZeros().toPlainString(),
                    parameters.getMaximumDepthM().stripTrailingZeros().toPlainString(), parameters.isDepthEnabled(),
                    parameters.getAlgorithmProfile());
            update(digest, demands.size());
            for (Demand demand : demands) update(digest, demand.id, demand.flowTph.toPlainString(),
                    demand.location.getXMm(), demand.location.getYMm(),
                    demand.connectionPointId, demand.linkedOksId);
            update(digest, roots.size());
            for (RootCandidate root : roots) {
                update(digest, root.id, root.location.getXMm(), root.location.getYMm(),
                        root.existingDirections.size());
                for (DirectionVector direction : root.existingDirections) {
                    update(digest, direction.deltaXMm, direction.deltaYMm);
                }
                update(digest, root.realization == null ? "unresolved" : "resolved");
                if (root.realization != null) {
                    update(digest, root.realization.nodeType, root.realization.chamber,
                            root.realization.baseIncidentSections, root.realization.targetId,
                            root.realization.existingIncidentDiameter);
                }
            }
            try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
                for (byte value : digest.digest()) formatter.format("%02x", value);
                return formatter.toString();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    private static <T> List<T> sortedUnique(Collection<T> supplied,
            java.util.function.Function<T, String> id, String label) {
        if (supplied == null) throw new IllegalArgumentException(label + " collection is required");
        List<T> result = new ArrayList<>(supplied);
        if (result.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException(label + " cannot be null");
        result.sort(Comparator.comparing(id));
        HashSet<String> ids = new HashSet<>();
        for (T value : result) {
            if (!ids.add(id.apply(value))) throw new IllegalArgumentException("Duplicate " + label + " ID");
        }
        return List.copyOf(result);
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }

    /** Потребитель с обязательным известным расходом; нулевой расход остаётся участником связности. */
    public static final class Demand {
        private final String id;
        private final BigDecimal flowTph;
        private final CatalogMetricPoint location;
        private final String connectionPointId;
        private final String linkedOksId;

        public Demand(String id, BigDecimal flowTph, CatalogMetricPoint location,
                String connectionPointId) {
            this(id, flowTph, location, connectionPointId, null);
        }

        public Demand(String id, BigDecimal flowTph, CatalogMetricPoint location,
                String connectionPointId, String linkedOksId) {
            this.id = required(id, "demand ID");
            this.flowTph = Objects.requireNonNull(flowTph, "flowTph").stripTrailingZeros();
            if (flowTph.signum() < 0) throw new IllegalArgumentException("Demand flow cannot be negative");
            this.location = Objects.requireNonNull(location, "location");
            this.connectionPointId = connectionPointId == null
                    ? null : required(connectionPointId, "connection point ID");
            this.linkedOksId = linkedOksId == null ? null : required(linkedOksId, "linked OKS ID");
        }

        public String getId() { return id; }
        public BigDecimal getFlowTph() { return flowTph; }
        public CatalogMetricPoint getLocation() { return location; }
        public String getConnectionPointId() { return connectionPointId; }
        public String getLinkedOksId() { return linkedOksId; }
    }

    /** Допустимый корень и неизменяемые направления существующих лучей в этой точке. */
    public static final class RootCandidate {
        private final String id;
        private final CatalogMetricPoint location;
        private final List<DirectionVector> existingDirections;
        private final RootRealization realization;

        public RootCandidate(String id, CatalogMetricPoint location,
                Collection<DirectionVector> existingDirections) {
            this(id, location, existingDirections, null);
        }

        public RootCandidate(String id, CatalogMetricPoint location,
                Collection<DirectionVector> existingDirections, RootRealization realization) {
            this.id = required(id, "root ID");
            this.location = Objects.requireNonNull(location, "location");
            if (existingDirections == null || existingDirections.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Existing root directions are required");
            }
            List<DirectionVector> ordered = new ArrayList<>(existingDirections);
            ordered.sort(Comparator.comparingLong(DirectionVector::getDeltaXMm)
                    .thenComparingLong(DirectionVector::getDeltaYMm));
            for (int index = 1; index < ordered.size(); index++) {
                if (ordered.get(index - 1).equals(ordered.get(index))) {
                    throw new IllegalArgumentException("Existing root directions must be unique");
                }
            }
            this.existingDirections = List.copyOf(ordered);
            if (realization != null
                    && realization.getBaseIncidentSections() != this.existingDirections.size()) {
                throw new IllegalArgumentException(
                        "Root realization incidence must match existing directions");
            }
            this.realization = realization;
        }

        public String getId() { return id; }
        public CatalogMetricPoint getLocation() { return location; }
        public List<DirectionVector> getExistingDirections() { return existingDirections; }
        public RootRealization getRealization() { return realization; }
    }

    /** Точная семантика корневого узла, которую нельзя восстанавливать эвристикой из координат. */
    public static final class RootRealization {
        private final String nodeType;
        private final boolean chamber;
        private final int baseIncidentSections;
        private final String targetId;
        private final Integer existingIncidentDiameter;

        public RootRealization(String nodeType, boolean chamber, int baseIncidentSections,
                String targetId, Integer existingIncidentDiameter) {
            this.nodeType = required(nodeType, "root node type");
            if (!chamber) throw new IllegalArgumentException("A routing root must be a chamber");
            if (baseIncidentSections < 0) {
                throw new IllegalArgumentException("Root incidence cannot be negative");
            }
            this.chamber = true;
            this.baseIncidentSections = baseIncidentSections;
            this.targetId = required(targetId, "root target ID");
            if (existingIncidentDiameter != null && existingIncidentDiameter <= 0) {
                throw new IllegalArgumentException("Existing root diameter must be positive");
            }
            this.existingIncidentDiameter = existingIncidentDiameter;
        }

        public String getNodeType() { return nodeType; }
        public boolean isChamber() { return chamber; }
        public int getBaseIncidentSections() { return baseIncidentSections; }
        public String getTargetId() { return targetId; }
        public Integer getExistingIncidentDiameter() { return existingIncidentDiameter; }
    }

    /** Направление существующего луча без изменяемого JTS Coordinate. */
    public static final class DirectionVector {
        private final long deltaXMm;
        private final long deltaYMm;

        public DirectionVector(long deltaXMm, long deltaYMm) {
            if (deltaXMm == 0L && deltaYMm == 0L) {
                throw new IllegalArgumentException("Root direction cannot be zero");
            }
            this.deltaXMm = deltaXMm;
            this.deltaYMm = deltaYMm;
        }

        public long getDeltaXMm() { return deltaXMm; }
        public long getDeltaYMm() { return deltaYMm; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DirectionVector)) return false;
            DirectionVector vector = (DirectionVector) other;
            return deltaXMm == vector.deltaXMm && deltaYMm == vector.deltaYMm;
        }

        @Override
        public int hashCode() { return Objects.hash(deltaXMm, deltaYMm); }
    }
}
