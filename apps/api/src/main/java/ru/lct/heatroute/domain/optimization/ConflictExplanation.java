package ru.lct.heatroute.domain.optimization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Версионированное доменное доказательство невозможной конъюнкции master-решений.
 * Оно применимо только к тому же input/rules и к явно указанной области каталога.
 */
public final class ConflictExplanation {
    public enum ProofScope {
        STABLE_DECISION_SET,
        FULL_CATALOG_ASSIGNMENT,
        CATALOG_SIZING_IMPLICATION
    }

    private final String type;
    private final String ruleId;
    private final String ruleVersion;
    private final String sourceSnapshotHash;
    private final String catalogHash;
    private final List<NetworkConstraintProblem.DecisionLiteral> literals;
    private final List<String> evidenceReferences;
    private final String reason;
    private final String checkerVersion;
    private final ProofScope proofScope;
    private final String signature;

    public ConflictExplanation(String type, String ruleId, String ruleVersion,
            String sourceSnapshotHash, String catalogHash,
            Collection<NetworkConstraintProblem.DecisionLiteral> literals,
            Collection<String> evidenceReferences, String reason, String checkerVersion,
            ProofScope proofScope) {
        this.type = required(type, "conflict type");
        this.ruleId = required(ruleId, "rule ID");
        this.ruleVersion = required(ruleVersion, "rule version");
        this.sourceSnapshotHash = required(sourceSnapshotHash, "source snapshot hash");
        this.catalogHash = required(catalogHash, "catalog hash");
        this.reason = required(reason, "conflict reason");
        this.checkerVersion = required(checkerVersion, "checker version");
        this.proofScope = Objects.requireNonNull(proofScope, "proofScope");

        NetworkConstraintProblem.Conflict validated =
                NetworkConstraintProblem.Conflict.ofLiterals(literals, this.reason);
        this.literals = validated.getLiterals();
        if (proofScope == ProofScope.CATALOG_SIZING_IMPLICATION) {
            List<NetworkConstraintProblem.DecisionLiteral> diameterLiterals = this.literals.stream()
                    .filter(literal -> literal.getType()
                            == NetworkConstraintProblem.DecisionLiteral.Type.DIAMETER_SELECTED)
                    .collect(Collectors.toList());
            if (diameterLiterals.size() != 1 || diameterLiterals.get(0).isExpected()) {
                throw new IllegalArgumentException(
                        "Catalog sizing implication requires exactly one false diameter literal");
            }
        }
        this.evidenceReferences = references(evidenceReferences);
        this.signature = signatureOf();
    }

    public boolean appliesTo(CatalogIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!sourceSnapshotHash.equals(identity.getSourceSnapshotHash())
                || !ruleId.equals(identity.getRuleId())
                || !ruleVersion.equals(identity.getRuleVersion())
                || !checkerVersion.equals(identity.getCheckerVersion())) return false;
        Set<String> literalKeys = literals.stream()
                .map(NetworkConstraintProblem.DecisionLiteral::variableKey).collect(Collectors.toSet());
        if (proofScope == ProofScope.FULL_CATALOG_ASSIGNMENT) {
            return catalogHash.equals(identity.getCatalogHash())
                    && literalKeys.equals(identity.getDecisionKeys());
        }
        if (proofScope == ProofScope.CATALOG_SIZING_IMPLICATION) {
            Set<String> topologyKeys = identity.getDecisionKeys().stream()
                    .filter(key -> !key.startsWith("DIAMETER_SELECTED:"))
                    .collect(Collectors.toSet());
            long diameterLiterals = literalKeys.stream()
                    .filter(key -> key.startsWith("DIAMETER_SELECTED:"))
                    .count();
            return catalogHash.equals(identity.getCatalogHash())
                    && identity.getDecisionKeys().containsAll(literalKeys)
                    && literalKeys.containsAll(topologyKeys)
                    && literalKeys.size() == topologyKeys.size() + 1
                    && diameterLiterals == 1L;
        }
        return identity.getDecisionKeys().containsAll(literalKeys);
    }

    public NetworkConstraintProblem.Conflict toModelConflict() {
        return NetworkConstraintProblem.Conflict.ofLiterals(literals, type + ":" + reason);
    }

    public String getType() { return type; }
    public String getRuleId() { return ruleId; }
    public String getRuleVersion() { return ruleVersion; }
    public String getSourceSnapshotHash() { return sourceSnapshotHash; }
    public String getCatalogHash() { return catalogHash; }
    public List<NetworkConstraintProblem.DecisionLiteral> getLiterals() { return literals; }
    public List<String> getEvidenceReferences() { return evidenceReferences; }
    public String getReason() { return reason; }
    public String getCheckerVersion() { return checkerVersion; }
    public ProofScope getProofScope() { return proofScope; }
    public String getSignature() { return signature; }

    private String signatureOf() {
        return type + "|" + ruleId + "|" + ruleVersion + "|" + sourceSnapshotHash + "|"
                + catalogHash + "|" + proofScope + "|" + checkerVersion + "|"
                + literals.stream().map(literal -> literal.variableKey() + "=" + literal.isExpected())
                        .collect(Collectors.joining(","))
                + "|" + reason;
    }

    private static List<String> references(Collection<String> supplied) {
        if (supplied == null || supplied.isEmpty()) {
            throw new IllegalArgumentException("Evidence references are required");
        }
        List<String> result = new ArrayList<>(supplied.size());
        for (String value : supplied) {
            String reference = required(value, "evidence reference");
            if (reference.length() > 512) throw new IllegalArgumentException("Evidence reference is too long");
            result.add(reference);
        }
        result.sort(Comparator.naturalOrder());
        if (result.size() > 256 || result.stream().distinct().count() != result.size()) {
            throw new IllegalArgumentException("Evidence references must be bounded and unique");
        }
        return List.copyOf(result);
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }
}
