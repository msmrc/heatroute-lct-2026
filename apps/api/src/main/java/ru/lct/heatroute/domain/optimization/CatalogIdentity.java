package ru.lct.heatroute.domain.optimization;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Версия input/rules/catalog и множество стабильных Boolean identities текущей master-модели. */
public final class CatalogIdentity {
    private final String sourceSnapshotHash;
    private final String ruleId;
    private final String ruleVersion;
    private final String checkerVersion;
    private final String catalogHash;
    private final Set<String> decisionKeys;

    public CatalogIdentity(String sourceSnapshotHash, String ruleId, String ruleVersion,
            String checkerVersion, String catalogHash, Collection<String> decisionKeys) {
        this.sourceSnapshotHash = required(sourceSnapshotHash, "source snapshot hash");
        this.ruleId = required(ruleId, "rule ID");
        this.ruleVersion = required(ruleVersion, "rule version");
        this.checkerVersion = required(checkerVersion, "checker version");
        this.catalogHash = required(catalogHash, "catalog hash");
        if (decisionKeys == null || decisionKeys.isEmpty()
                || decisionKeys.stream().anyMatch(key -> key == null || key.trim().isEmpty())) {
            throw new IllegalArgumentException("Non-empty decision keys are required");
        }
        this.decisionKeys = Set.copyOf(decisionKeys);
        if (this.decisionKeys.size() != decisionKeys.size()) {
            throw new IllegalArgumentException("Decision keys must be unique");
        }
    }

    /** Строит decision identity непосредственно из валидированной конечной master-задачи. */
    public static CatalogIdentity fromProblem(String sourceSnapshotHash, String ruleId,
            String ruleVersion, String checkerVersion, String catalogHash,
            NetworkConstraintProblem problem) {
        Objects.requireNonNull(problem, "problem");
        Set<String> keys = new LinkedHashSet<>();
        for (NetworkConstraintProblem.Node node : problem.getNodes()) {
            if (node.isAllowedRoot()) {
                keys.add(NetworkConstraintProblem.DecisionLiteral.root(node.getId(), true).variableKey());
            }
        }
        for (NetworkConstraintProblem.Asset asset : problem.getAssets()) {
            keys.add(NetworkConstraintProblem.DecisionLiteral.asset(asset.getId(), true).variableKey());
            for (NetworkConstraintProblem.DiameterOption option : asset.getDiameters()) {
                keys.add(NetworkConstraintProblem.DecisionLiteral
                        .diameter(asset.getId(), option.getDiameterMm(), true).variableKey());
            }
        }
        return new CatalogIdentity(
                sourceSnapshotHash, ruleId, ruleVersion, checkerVersion, catalogHash, keys);
    }

    public String getSourceSnapshotHash() { return sourceSnapshotHash; }
    public String getRuleId() { return ruleId; }
    public String getRuleVersion() { return ruleVersion; }
    public String getCheckerVersion() { return checkerVersion; }
    public String getCatalogHash() { return catalogHash; }
    public Set<String> getDecisionKeys() { return decisionKeys; }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }
}
