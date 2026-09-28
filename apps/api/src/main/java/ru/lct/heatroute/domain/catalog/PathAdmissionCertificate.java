package ru.lct.heatroute.domain.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Версионированный результат проверки одного direction/context/ДУ варианта пути. */
public final class PathAdmissionCertificate {
    public enum Level { SEGMENT_LOCAL, COMPLETE_PHYSICAL_PATH }
    public enum Status { VERIFIED_ALLOWED, PROVEN_FORBIDDEN, UNCHECKED }
    public enum Direction { FORWARD, REVERSE }

    private final Level level;
    private final Status status;
    private final String ruleId;
    private final String ruleVersion;
    private final String sourceSnapshotHash;
    private final int diameterMm;
    private final Direction direction;
    private final String endpointContext;
    private final List<String> sectionSignatures;
    private final List<String> completedChecks;
    private final String checkerVersion;
    private final String reason;

    public PathAdmissionCertificate(Level level, Status status, String ruleId, String ruleVersion,
            String sourceSnapshotHash, int diameterMm, Direction direction, String endpointContext,
            Collection<String> sectionSignatures, Collection<String> completedChecks,
            String checkerVersion, String reason) {
        this.level = Objects.requireNonNull(level, "level");
        this.status = Objects.requireNonNull(status, "status");
        this.ruleId = required(ruleId, "rule ID");
        this.ruleVersion = required(ruleVersion, "rule version");
        this.sourceSnapshotHash = required(sourceSnapshotHash, "source snapshot hash");
        if (diameterMm <= 0) throw new IllegalArgumentException("Positive diameter is required");
        this.diameterMm = diameterMm;
        this.direction = Objects.requireNonNull(direction, "direction");
        this.endpointContext = required(endpointContext, "endpoint context");
        this.sectionSignatures = sortedUnique(sectionSignatures, "section signature");
        this.completedChecks = sortedUnique(completedChecks, "completed check");
        this.checkerVersion = required(checkerVersion, "checker version");
        this.reason = required(reason, "certificate reason");
        if (status != Status.UNCHECKED && this.completedChecks.isEmpty()) {
            throw new IllegalArgumentException("A checked certificate requires completed checks");
        }
    }

    public boolean appliesTo(String expectedRuleId, String expectedRuleVersion,
            String expectedSourceHash, Direction expectedDirection, String expectedContext) {
        return ruleId.equals(expectedRuleId) && ruleVersion.equals(expectedRuleVersion)
                && sourceSnapshotHash.equals(expectedSourceHash) && direction == expectedDirection
                && endpointContext.equals(expectedContext);
    }

    public Level getLevel() { return level; }
    public Status getStatus() { return status; }
    public String getRuleId() { return ruleId; }
    public String getRuleVersion() { return ruleVersion; }
    public String getSourceSnapshotHash() { return sourceSnapshotHash; }
    public int getDiameterMm() { return diameterMm; }
    public Direction getDirection() { return direction; }
    public String getEndpointContext() { return endpointContext; }
    public List<String> getSectionSignatures() { return sectionSignatures; }
    public List<String> getCompletedChecks() { return completedChecks; }
    public String getCheckerVersion() { return checkerVersion; }
    public String getReason() { return reason; }

    private static List<String> sortedUnique(Collection<String> values, String label) {
        if (values == null) throw new IllegalArgumentException(label + " collection is required");
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) result.add(required(value, label));
        result.sort(Comparator.naturalOrder());
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
        if (!(other instanceof PathAdmissionCertificate)) return false;
        PathAdmissionCertificate that = (PathAdmissionCertificate) other;
        return diameterMm == that.diameterMm && level == that.level && status == that.status
                && ruleId.equals(that.ruleId) && ruleVersion.equals(that.ruleVersion)
                && sourceSnapshotHash.equals(that.sourceSnapshotHash) && direction == that.direction
                && endpointContext.equals(that.endpointContext)
                && sectionSignatures.equals(that.sectionSignatures)
                && completedChecks.equals(that.completedChecks)
                && checkerVersion.equals(that.checkerVersion) && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, status, ruleId, ruleVersion, sourceSnapshotHash, diameterMm,
                direction, endpointContext, sectionSignatures, completedChecks, checkerVersion, reason);
    }
}
