package ru.lct.heatroute.domain.catalog;

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
import java.util.Optional;

/** Неизменяемый направленный геометрический вариант с отдельным admission по каждому ДУ. */
public final class DirectedPathOption {
    private final String id;
    private final String fromPortId;
    private final String toPortId;
    private final PathAdmissionCertificate.Direction direction;
    private final String endpointContext;
    private final List<CatalogMetricPoint> coordinates;
    private final List<String> physicalAssetIds;
    private final List<Section> sections;
    private final long lengthMm;
    private final long firstBendDistanceMm;
    private final long lastBendDistanceMm;
    private final Provenance provenance;
    private final Map<Integer, PathAdmissionCertificate> certificatesByDiameter;
    private final String fingerprint;

    public DirectedPathOption(String id, String fromPortId, String toPortId,
            PathAdmissionCertificate.Direction direction, String endpointContext,
            Collection<CatalogMetricPoint> coordinates, Collection<String> physicalAssetIds,
            Collection<Section> sections, long firstBendDistanceMm, long lastBendDistanceMm,
            Provenance provenance, Collection<PathAdmissionCertificate> certificates) {
        this.id = required(id, "path option ID");
        this.fromPortId = required(fromPortId, "from port ID");
        this.toPortId = required(toPortId, "to port ID");
        if (this.fromPortId.equals(this.toPortId)) throw new IllegalArgumentException("Path ports must differ");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.endpointContext = required(endpointContext, "endpoint context");
        this.coordinates = points(coordinates);
        this.physicalAssetIds = orderedUnique(physicalAssetIds, "physical asset ID", false);
        this.sections = sections(sections, this.coordinates.size());
        if (firstBendDistanceMm < 0L || lastBendDistanceMm < 0L) {
            throw new IllegalArgumentException("Bend distances cannot be negative");
        }
        this.firstBendDistanceMm = firstBendDistanceMm;
        this.lastBendDistanceMm = lastBendDistanceMm;
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.lengthMm = polylineLengthMm(this.coordinates);
        if (firstBendDistanceMm > this.lengthMm || lastBendDistanceMm > this.lengthMm) {
            throw new IllegalArgumentException("Bend distance cannot exceed path length");
        }
        this.certificatesByDiameter = certificates(certificates);
        this.fingerprint = fingerprintOf();
    }

    /** Непроверенный ДУ остаётся UNCHECKED, а не превращается в запрет. */
    public PathAdmissionCertificate.Status admissionStatus(int diameterMm, String ruleId,
            String ruleVersion, String sourceSnapshotHash) {
        PathAdmissionCertificate certificate = certificatesByDiameter.get(diameterMm);
        if (certificate == null || !certificate.appliesTo(ruleId, ruleVersion, sourceSnapshotHash,
                direction, endpointContext)) return PathAdmissionCertificate.Status.UNCHECKED;
        return certificate.getStatus();
    }

    public Optional<PathAdmissionCertificate> certificate(int diameterMm) {
        return Optional.ofNullable(certificatesByDiameter.get(diameterMm));
    }

    public String getId() { return id; }
    public String getFromPortId() { return fromPortId; }
    public String getToPortId() { return toPortId; }
    public PathAdmissionCertificate.Direction getDirection() { return direction; }
    public String getEndpointContext() { return endpointContext; }
    public List<CatalogMetricPoint> getCoordinates() { return coordinates; }
    public List<String> getPhysicalAssetIds() { return physicalAssetIds; }
    public List<Section> getSections() { return sections; }
    public long getLengthMm() { return lengthMm; }
    public long getFirstBendDistanceMm() { return firstBendDistanceMm; }
    public long getLastBendDistanceMm() { return lastBendDistanceMm; }
    public Provenance getProvenance() { return provenance; }
    public Map<Integer, PathAdmissionCertificate> getCertificatesByDiameter() { return certificatesByDiameter; }
    public String getFingerprint() { return fingerprint; }

    private Map<Integer, PathAdmissionCertificate> certificates(
            Collection<PathAdmissionCertificate> supplied) {
        if (supplied == null) throw new IllegalArgumentException("Certificates collection is required");
        List<PathAdmissionCertificate> ordered = new ArrayList<>(supplied);
        if (ordered.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Certificate cannot be null");
        }
        ordered.sort(Comparator.comparingInt(PathAdmissionCertificate::getDiameterMm));
        Map<Integer, PathAdmissionCertificate> result = new LinkedHashMap<>();
        List<String> expectedSections = sections.stream().map(Section::getSignature)
                .sorted().collect(java.util.stream.Collectors.toList());
        for (PathAdmissionCertificate certificate : ordered) {
            if (!certificate.appliesTo(certificate.getRuleId(), certificate.getRuleVersion(),
                    provenance.sourceSnapshotHash, direction, endpointContext)) {
                throw new IllegalArgumentException("Certificate context does not match its path option");
            }
            if (!certificate.getSectionSignatures().equals(expectedSections)) {
                throw new IllegalArgumentException("Certificate sections do not match its path option");
            }
            if (result.put(certificate.getDiameterMm(), certificate) != null) {
                throw new IllegalArgumentException("Duplicate diameter certificate");
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private String fingerprintOf() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, id, fromPortId, toPortId, direction.name(), endpointContext,
                    Long.toString(lengthMm), Long.toString(firstBendDistanceMm), Long.toString(lastBendDistanceMm),
                    provenance.generatorId, provenance.generatorVersion, provenance.sourceSnapshotHash,
                    provenance.windowFingerprint);
            update(digest, coordinates.size());
            for (CatalogMetricPoint point : coordinates) update(digest, point.getXMm(), point.getYMm());
            update(digest, physicalAssetIds.size());
            for (String assetId : physicalAssetIds) update(digest, assetId);
            update(digest, sections.size());
            for (Section section : sections) update(digest, section.getSignature());
            update(digest, certificatesByDiameter.size());
            for (PathAdmissionCertificate certificate : certificatesByDiameter.values()) {
                update(digest, certificate.getDiameterMm(), certificate.getLevel(), certificate.getStatus(),
                        certificate.getRuleId(), certificate.getRuleVersion(), certificate.getSourceSnapshotHash(),
                        certificate.getDirection(), certificate.getEndpointContext(),
                        certificate.getCheckerVersion(), certificate.getReason(),
                        certificate.getSectionSignatures().size());
                for (String section : certificate.getSectionSignatures()) update(digest, section);
                update(digest, certificate.getCompletedChecks().size());
                for (String check : certificate.getCompletedChecks()) update(digest, check);
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
            byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    private static List<CatalogMetricPoint> points(Collection<CatalogMetricPoint> supplied) {
        if (supplied == null || supplied.size() < 2 || supplied.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("At least two non-null path points are required");
        }
        List<CatalogMetricPoint> result = List.copyOf(supplied);
        for (int index = 1; index < result.size(); index++) {
            if (result.get(index - 1).equals(result.get(index))) {
                throw new IllegalArgumentException("Consecutive path points must differ");
            }
        }
        return result;
    }

    private static long polylineLengthMm(List<CatalogMetricPoint> points) {
        double total = 0.0;
        for (int index = 1; index < points.size(); index++) {
            CatalogMetricPoint left = points.get(index - 1);
            CatalogMetricPoint right = points.get(index);
            total += Math.hypot((double) right.getXMm() - left.getXMm(),
                    (double) right.getYMm() - left.getYMm());
        }
        if (!Double.isFinite(total) || total > Long.MAX_VALUE) {
            throw new IllegalArgumentException("Path length exceeds the catalog integer range");
        }
        return Math.round(total);
    }

    private static List<Section> sections(Collection<Section> supplied, int pointCount) {
        if (supplied == null) throw new IllegalArgumentException("Sections collection is required");
        List<Section> result = new ArrayList<>(supplied);
        if (result.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Section cannot be null");
        result.sort(Comparator.comparingInt(Section::getFromPointIndex)
                .thenComparingInt(Section::getToPointIndex).thenComparing(Section::getSignature));
        for (Section section : result) {
            if (section.toPointIndex >= pointCount) {
                throw new IllegalArgumentException("Section exceeds path coordinates");
            }
        }
        return List.copyOf(result);
    }

    private static List<String> orderedUnique(Collection<String> values, String label, boolean sort) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException(label + " values are required");
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) result.add(required(value, label));
        if (sort) result.sort(Comparator.naturalOrder());
        if (result.stream().distinct().count() != result.size()) {
            throw new IllegalArgumentException(label + " values must be unique");
        }
        return List.copyOf(result);
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DirectedPathOption)) return false;
        DirectedPathOption that = (DirectedPathOption) other;
        return lengthMm == that.lengthMm && firstBendDistanceMm == that.firstBendDistanceMm
                && lastBendDistanceMm == that.lastBendDistanceMm && id.equals(that.id)
                && fromPortId.equals(that.fromPortId) && toPortId.equals(that.toPortId)
                && direction == that.direction && endpointContext.equals(that.endpointContext)
                && coordinates.equals(that.coordinates) && physicalAssetIds.equals(that.physicalAssetIds)
                && sections.equals(that.sections) && provenance.equals(that.provenance)
                && certificatesByDiameter.equals(that.certificatesByDiameter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, fromPortId, toPortId, direction, endpointContext, coordinates,
                physicalAssetIds, sections, lengthMm, firstBendDistanceMm, lastBendDistanceMm,
                provenance, certificatesByDiameter);
    }

    /** Привязка непрерывного фрагмента sections к индексам исходной полилинии. */
    public static final class Section {
        private final String kind;
        private final String restrictionType;
        private final String restrictionId;
        private final int fromPointIndex;
        private final int toPointIndex;

        public Section(String kind, String restrictionType, String restrictionId,
                int fromPointIndex, int toPointIndex) {
            this.kind = required(kind, "section kind");
            this.restrictionType = optional(restrictionType);
            this.restrictionId = optional(restrictionId);
            if ((this.restrictionType == null) != (this.restrictionId == null)) {
                throw new IllegalArgumentException("Restriction type and ID must be present together");
            }
            if (fromPointIndex < 0 || toPointIndex <= fromPointIndex) {
                throw new IllegalArgumentException("Section requires an increasing point range");
            }
            this.fromPointIndex = fromPointIndex;
            this.toPointIndex = toPointIndex;
        }

        public String getKind() { return kind; }
        public String getRestrictionType() { return restrictionType; }
        public String getRestrictionId() { return restrictionId; }
        public int getFromPointIndex() { return fromPointIndex; }
        public int getToPointIndex() { return toPointIndex; }
        public String getSignature() {
            return kind + ":" + Objects.toString(restrictionType, "") + ":"
                    + Objects.toString(restrictionId, "") + ":" + fromPointIndex + ":" + toPointIndex;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Section)) return false;
            Section section = (Section) other;
            return fromPointIndex == section.fromPointIndex && toPointIndex == section.toPointIndex
                    && kind.equals(section.kind) && Objects.equals(restrictionType, section.restrictionType)
                    && Objects.equals(restrictionId, section.restrictionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, restrictionType, restrictionId, fromPointIndex, toPointIndex);
        }
    }

    /** Происхождение геометрии и точного bounded window, из которого она получена. */
    public static final class Provenance {
        private final String generatorId;
        private final String generatorVersion;
        private final String sourceSnapshotHash;
        private final String windowFingerprint;

        public Provenance(String generatorId, String generatorVersion,
                String sourceSnapshotHash, String windowFingerprint) {
            this.generatorId = required(generatorId, "generator ID");
            this.generatorVersion = required(generatorVersion, "generator version");
            this.sourceSnapshotHash = required(sourceSnapshotHash, "source snapshot hash");
            this.windowFingerprint = required(windowFingerprint, "window fingerprint");
        }

        public String getGeneratorId() { return generatorId; }
        public String getGeneratorVersion() { return generatorVersion; }
        public String getSourceSnapshotHash() { return sourceSnapshotHash; }
        public String getWindowFingerprint() { return windowFingerprint; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Provenance)) return false;
            Provenance that = (Provenance) other;
            return generatorId.equals(that.generatorId) && generatorVersion.equals(that.generatorVersion)
                    && sourceSnapshotHash.equals(that.sourceSnapshotHash)
                    && windowFingerprint.equals(that.windowFingerprint);
        }

        @Override
        public int hashCode() {
            return Objects.hash(generatorId, generatorVersion, sourceSnapshotHash, windowFingerprint);
        }
    }

    private static String optional(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
