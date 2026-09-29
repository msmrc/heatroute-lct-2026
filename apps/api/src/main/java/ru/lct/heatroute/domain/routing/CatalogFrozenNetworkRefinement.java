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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatroute.domain.optimization.CatalogIdentity;
import ru.lct.heatroute.domain.optimization.ConflictExplanation;
import ru.lct.heatroute.domain.optimization.ConflictStore;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatNetworkRefinement;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;
import ru.lct.heatroute.domain.sizing.NetworkSizingIssue;

/**
 * Production bridge between the finite-catalog master and the immutable exact evaluator.
 * Engineering rejection blocks only a complete Boolean master assignment. Canonical sizing uses
 * the narrower catalog-scoped implication from a complete topology/root scope to the required DU.
 */
public final class CatalogFrozenNetworkRefinement {
    private static final int MAX_EVIDENCE_REFERENCES = 256;
    private static final Logger LOGGER = LoggerFactory.getLogger(
            CatalogFrozenNetworkRefinement.class);

    private final CpSatNetworkRefinement<Attempt> refinement;
    private final FrozenNetworkEvaluator evaluator;

    public CatalogFrozenNetworkRefinement(CpSatNetworkOptimizer optimizer,
            FrozenNetworkEvaluator evaluator) {
        this.refinement = new CpSatNetworkRefinement<>(Objects.requireNonNull(optimizer, "optimizer"));
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    public Result solve(NetworkConstraintProblem problem, CatalogIdentity catalogIdentity,
            ConflictStore conflictStore, CandidateFactory candidateFactory,
            AcceptedSolutionArchive archive, CpSatNetworkRefinement.Settings settings) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(catalogIdentity, "catalogIdentity");
        Objects.requireNonNull(conflictStore, "conflictStore");
        Objects.requireNonNull(candidateFactory, "candidateFactory");
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(settings, "settings");

        CpSatNetworkRefinement.Result<Attempt> raw = refinement.solve(
                problem, catalogIdentity, conflictStore,
                master -> new Attempt(master, Objects.requireNonNull(
                        candidateFactory.assemble(master), "frozen assembly"), null),
                attempt -> assess(problem, catalogIdentity, conflictStore, archive, attempt), settings);
        return Result.from(raw);
    }

    private CpSatNetworkRefinement.Assessment<Attempt> assess(NetworkConstraintProblem problem,
            CatalogIdentity identity, ConflictStore conflictStore,
            AcceptedSolutionArchive archive, Attempt attempt) {
        FrozenNetworkCandidate candidate = attempt.assembly.getCandidate();
        FrozenNetworkEvaluator.Evaluation evaluation = evaluator.evaluate(candidate);
        switch (evaluation.getOutcome()) {
            case ACCEPTED:
                AcceptedNetworkSolution accepted = Objects.requireNonNull(
                        evaluation.getAccepted(), "accepted solution");
                archive.add(accepted);
                return CpSatNetworkRefinement.Assessment.accepted(attempt.accepted(accepted));
            case CANONICAL_SIZING_REQUIREMENT:
                FrozenNetworkCandidate canonical = canonicalCandidateIfFeasible(
                        evaluation, problem, identity, conflictStore, attempt);
                if (canonical != null) {
                    FrozenNetworkEvaluator.Evaluation canonicalEvaluation = evaluator.evaluate(canonical);
                    if (canonicalEvaluation.getOutcome() == FrozenNetworkEvaluator.Outcome.ACCEPTED) {
                        AcceptedNetworkSolution canonicalAccepted = Objects.requireNonNull(
                                canonicalEvaluation.getAccepted(), "accepted canonical solution");
                        archive.add(canonicalAccepted);
                        return CpSatNetworkRefinement.Assessment.accepted(
                                attempt.accepted(canonicalAccepted));
                    }
                    if (canonicalEvaluation.getOutcome() == FrozenNetworkEvaluator.Outcome.UNKNOWN) {
                        return CpSatNetworkRefinement.Assessment.unknown(
                                requiredReason(canonicalEvaluation));
                    }
                    if (canonicalEvaluation.getOutcome() == FrozenNetworkEvaluator.Outcome.ERROR) {
                        return CpSatNetworkRefinement.Assessment.error(
                                requiredReason(canonicalEvaluation));
                    }
                    if (canonicalEvaluation.getOutcome()
                            == FrozenNetworkEvaluator.Outcome.CANONICAL_SIZING_REQUIREMENT) {
                        return CpSatNetworkRefinement.Assessment.error(
                                "canonical_sizing_projection_mismatch");
                    }
                    LOGGER.info("Canonical projection rejected outcome={} reason={} validation={} sizing={}",
                            canonicalEvaluation.getOutcome(), canonicalEvaluation.getReason(),
                            canonicalEvaluation.getValidationIssues().stream().limit(12)
                                    .map(issue -> issue.getCode() + ":" + issue.getSubjectId())
                                    .collect(java.util.stream.Collectors.toList()),
                            canonicalEvaluation.getSizingIssues().size());
                    List<ConflictExplanation> localized = localizedEngineeringProofs(
                            canonicalEvaluation, problem, identity, attempt, canonical);
                    if (!localized.isEmpty()) {
                        return CpSatNetworkRefinement.Assessment.rejected(localized);
                    }
                    // A geometric rejection belongs to the canonical master assignment, not to
                    // the preliminary diameter assignment.  Let the sizing implication materialize
                    // that assignment before an auditable engineering no-good is recorded.
                }
                List<ConflictExplanation> sizingProofs = canonicalSizingProofs(
                        evaluation, problem, identity, attempt);
                if (sizingProofs.isEmpty()) {
                    return CpSatNetworkRefinement.Assessment.unknown(
                            "canonical_diameter_missing_from_catalog");
                }
                return CpSatNetworkRefinement.Assessment.sizingRequired(sizingProofs);
            case PROVEN_REJECTED:
                LOGGER.info("Exact candidate rejected reason={} validation={} sizing={}",
                        evaluation.getReason(),
                        evaluation.getValidationIssues().stream().limit(32)
                                .map(issue -> issue.getCode() + ":" + issue.getSubjectId())
                                .collect(java.util.stream.Collectors.toList()),
                        evaluation.getSizingIssues().size());
                List<ConflictExplanation> directLocalized = localizedEngineeringProofs(
                        evaluation, problem, identity, attempt, candidate);
                if (!directLocalized.isEmpty()) {
                    return CpSatNetworkRefinement.Assessment.rejected(directLocalized);
                }
                return CpSatNetworkRefinement.Assessment.rejected(fullAssignmentProof(
                        rejectionType(evaluation), evaluation.getReason(), problem, identity,
                        attempt.master, evidence(evaluation, candidate)));
            case UNKNOWN:
                return CpSatNetworkRefinement.Assessment.unknown(requiredReason(evaluation));
            case ERROR:
                return CpSatNetworkRefinement.Assessment.error(requiredReason(evaluation));
            default:
                throw new IllegalStateException("Unsupported frozen evaluation outcome: "
                        + evaluation.getOutcome());
        }
    }

    private static FrozenNetworkCandidate canonicalCandidateIfFeasible(
            FrozenNetworkEvaluator.Evaluation evaluation, NetworkConstraintProblem problem,
            CatalogIdentity identity, ConflictStore conflictStore, Attempt attempt) {
        Map<String, Integer> canonicalDiameters = new LinkedHashMap<>(
                attempt.master.getDiameterMm());
        List<RouteEdge> edges = new ArrayList<>();
        for (RouteEdge edge : attempt.assembly.getCandidate().getEdges()) {
            FrozenNetworkEvaluator.RequiredSizing required =
                    evaluation.getRequiredSizing().get(edge.getId());
            if (required == null) {
                LOGGER.info("Canonical projection unavailable: sizing missing for edge={}", edge.getId());
                return null;
            }
            List<String> arcIds = attempt.assembly.arcIds(edge.getId());
            if (arcIds == null || arcIds.isEmpty()) {
                LOGGER.info("Canonical projection unavailable: provenance missing for edge={}", edge.getId());
                return null;
            }
            for (String arcId : arcIds) {
                NetworkConstraintProblem.Asset asset = problem.asset(arcId);
                Long flow = attempt.master.getFlowUnits().get(arcId);
                if (asset == null || flow == null
                        || !attempt.master.getSelectedAssets().contains(arcId)) {
                    LOGGER.info("Canonical projection unavailable: master arc missing arc={}", arcId);
                    return null;
                }
                NetworkConstraintProblem.DiameterOption option = asset.getDiameters().stream()
                        .filter(candidate -> candidate.getDiameterMm() == required.getDiameter())
                        .findFirst().orElse(null);
                if (option == null || option.getCapacityUnits() < flow) {
                    LOGGER.info("Canonical projection unavailable: diameter/capacity arc={} diameter={} capacity={} flow={}",
                            arcId, required.getDiameter(),
                            option == null ? null : option.getCapacityUnits(), flow);
                    return null;
                }
                Integer previous = canonicalDiameters.put(arcId, required.getDiameter());
                if (previous == null) {
                    LOGGER.info("Canonical projection unavailable: prior diameter missing arc={}", arcId);
                    return null;
                }
            }
            edges.add(new RouteEdge(edge.getId(), edge.getUpstreamNodeId(),
                    edge.getDownstreamNodeId(), edge.getLengthM().doubleValue(),
                    edge.getCoordinates(), edge.getSections(), required.getFlowTph(),
                    required.getDiameter()));
        }
        List<NetworkConstraintProblem.Conflict> conflicts = new ArrayList<>(problem.getConflicts());
        conflicts.addAll(conflictStore.modelConflicts(identity));
        for (NetworkConstraintProblem.Conflict conflict : conflicts) {
            boolean prohibited = true;
            for (NetworkConstraintProblem.DecisionLiteral literal : conflict.getLiterals()) {
                if (literal.isExpected() != literalValue(literal, attempt.master,
                        canonicalDiameters)) {
                    prohibited = false;
                    break;
                }
            }
            if (prohibited) {
                LOGGER.info("Canonical projection unavailable: projected assignment is prohibited reason={}",
                        conflict.getReason());
                return null;
            }
        }
        return attempt.assembly.getCandidate().withEdges(edges);
    }

    private static List<ConflictExplanation> localizedEngineeringProofs(
            FrozenNetworkEvaluator.Evaluation evaluation, NetworkConstraintProblem problem,
            CatalogIdentity identity, Attempt attempt, FrozenNetworkCandidate candidate) {
        if (evaluation.getOutcome() != FrozenNetworkEvaluator.Outcome.PROVEN_REJECTED) {
            return List.of();
        }
        Collection<String> proofEvidence = evidence(evaluation, candidate);
        Map<String, ConflictExplanation> result = new LinkedHashMap<>();
        for (RouteValidationIssue issue : evaluation.getValidationIssues()) {
            if ("ROUTE_DEFLECTION_EXCEEDED".equals(issue.getCode())
                    && issue.getSubjectId() != null) {
                List<String> arcIds = attempt.assembly.arcIds(issue.getSubjectId());
                if (arcIds == null) {
                    arcIds = selectedIncidentArcIds(
                            problem, attempt.master, issue.getSubjectId());
                }
                addStableTopologyProof(result, arcIds, proofEvidence, issue, identity);
                continue;
            }
            if (("SELF_INTERSECTION".equals(issue.getCode())
                    || "EXPERT_ROUTE_BEND_ANGLE_INVALID".equals(issue.getCode()))
                    && issue.getSubjectId() != null) {
                addStableTopologyProof(result, attempt.assembly.arcIds(issue.getSubjectId()),
                        proofEvidence, issue, identity);
                continue;
            }
            if ("EXPERT_CHAMBER_OBLIQUE_ENTRY".equals(issue.getCode())
                    && issue.getSubjectId() != null) {
                addStableTopologyProof(result, selectedIncidentArcIds(
                        problem, attempt.master, issue.getSubjectId()),
                        proofEvidence, issue, identity);
                continue;
            }
            if ("CHAMBER_DEGREE_EXCEEDED".equals(issue.getCode())
                    && issue.getSubjectId() != null) {
                List<String> arcIds = selectedIncidentArcIds(
                        problem, attempt.master, issue.getSubjectId());
                if (arcIds.size() > 4) {
                    addStableTopologyProof(result, arcIds, proofEvidence, issue, identity);
                }
                continue;
            }
            if (!"CROSSING_OUTSIDE_COMMON_NODE".equals(issue.getCode())
                    || issue.getSubjectId() == null) continue;
            String[] edgeIds = issue.getSubjectId().split("\\|", -1);
            if (edgeIds.length != 2) continue;
            LinkedHashSet<String> arcIds = new LinkedHashSet<>();
            for (String edgeId : edgeIds) {
                List<String> provenance = attempt.assembly.arcIds(edgeId);
                if (provenance == null || provenance.isEmpty()) {
                    arcIds.clear();
                    break;
                }
                arcIds.addAll(provenance);
            }
            if (arcIds.isEmpty()) continue;
            addStableTopologyProof(result, List.copyOf(arcIds), proofEvidence, issue, identity);
        }
        return List.copyOf(result.values());
    }

    private static List<String> selectedIncidentArcIds(NetworkConstraintProblem problem,
            CpSatNetworkOptimizer.Result master, String nodeId) {
        return problem.getAssets().stream()
                .filter(asset -> master.getSelectedAssets().contains(asset.getId()))
                .filter(asset -> nodeId.equals(asset.getFromNodeId())
                        || nodeId.equals(asset.getToNodeId()))
                .map(NetworkConstraintProblem.Asset::getId)
                .sorted()
                .collect(java.util.stream.Collectors.toList());
    }

    private static void addStableTopologyProof(Map<String, ConflictExplanation> result,
            Collection<String> arcIds, Collection<String> proofEvidence,
            RouteValidationIssue issue, CatalogIdentity identity) {
        if (arcIds == null || arcIds.isEmpty()) return;
        List<NetworkConstraintProblem.DecisionLiteral> literals = arcIds.stream()
                .distinct().sorted()
                .map(arcId -> NetworkConstraintProblem.DecisionLiteral.asset(arcId, true))
                .collect(java.util.stream.Collectors.toList());
        ConflictExplanation proof = new ConflictExplanation(
                "exact_geometry", identity.getRuleId(), identity.getRuleVersion(),
                identity.getSourceSnapshotHash(), identity.getCatalogHash(), literals,
                proofEvidence, issue.getCode(), identity.getCheckerVersion(),
                ConflictExplanation.ProofScope.STABLE_DECISION_SET);
        result.putIfAbsent(proof.getSignature(), proof);
    }

    private static boolean literalValue(NetworkConstraintProblem.DecisionLiteral literal,
            CpSatNetworkOptimizer.Result master, Map<String, Integer> canonicalDiameters) {
        switch (literal.getType()) {
            case ASSET_SELECTED:
                return master.getSelectedAssets().contains(literal.getSubjectId());
            case ROOT_SELECTED:
                return master.getSelectedRoots().contains(literal.getSubjectId());
            case NODE_CONFIGURATION_SELECTED:
                return master.getSelectedNodeConfigurations().contains(literal.getSubjectId());
            case DIAMETER_SELECTED:
                return Objects.equals(canonicalDiameters.get(literal.getSubjectId()),
                        literal.getDiameterMm());
            default:
                throw new IllegalStateException("Unsupported decision literal: " + literal.getType());
        }
    }

    private static List<ConflictExplanation> canonicalSizingProofs(
            FrozenNetworkEvaluator.Evaluation evaluation, NetworkConstraintProblem problem,
            CatalogIdentity identity, Attempt attempt) {
        Collection<String> evidence = evidence(evaluation, attempt.assembly.getCandidate());
        List<ConflictExplanation> result = new ArrayList<>();
        List<Map.Entry<String, FrozenNetworkEvaluator.RequiredSizing>> requirements =
                new ArrayList<>(evaluation.getRequiredSizing().entrySet());
        requirements.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, FrozenNetworkEvaluator.RequiredSizing> entry : requirements) {
            List<String> arcIds = attempt.assembly.arcIds(entry.getKey());
            if (arcIds == null || arcIds.isEmpty()) {
                throw new IllegalArgumentException("Missing master-arc provenance for frozen edge: "
                        + entry.getKey());
            }
            int requiredDiameter = entry.getValue().getDiameter();
            for (String arcId : arcIds) {
                NetworkConstraintProblem.Asset asset = problem.asset(arcId);
                if (asset == null) {
                    throw new IllegalArgumentException("Frozen edge references an unknown master arc: " + arcId);
                }
                boolean available = asset.getDiameters().stream()
                        .anyMatch(option -> option.getDiameterMm() == requiredDiameter);
                if (!available) return List.of();
                Integer selectedDiameter = attempt.master.getDiameterMm().get(arcId);
                if (!attempt.master.getSelectedAssets().contains(arcId)
                        || selectedDiameter == null) {
                    throw new IllegalArgumentException("Canonical sizing feedback does not differ for " + arcId);
                }
                // The evaluator reports the complete canonical sizing map whenever at least one
                // frozen edge differs.  A collapsed edge can therefore reference master arcs that
                // already have the required diameter.  They need no implication; rejecting them
                // here used to turn a valid, partially matching assignment into an internal error.
                if (selectedDiameter == requiredDiameter) continue;
                result.add(sizingImplicationProof(evaluation.getReason(), problem, identity,
                        attempt.master, arcId, requiredDiameter, evidence));
            }
        }
        return List.copyOf(result);
    }

    private static ConflictExplanation sizingImplicationProof(String reason,
            NetworkConstraintProblem problem, CatalogIdentity identity,
            CpSatNetworkOptimizer.Result master, String arcId, int canonicalDiameter,
            Collection<String> evidence) {
        List<NetworkConstraintProblem.DecisionLiteral> literals = new ArrayList<>();
        for (NetworkConstraintProblem.DecisionLiteral literal : assignmentLiterals(problem, master)) {
            if (literal.getType() != NetworkConstraintProblem.DecisionLiteral.Type.DIAMETER_SELECTED) {
                literals.add(literal);
            }
        }
        literals.add(NetworkConstraintProblem.DecisionLiteral.diameter(
                arcId, canonicalDiameter, false));
        return new ConflictExplanation("canonical_sizing", identity.getRuleId(),
                identity.getRuleVersion(), identity.getSourceSnapshotHash(), identity.getCatalogHash(),
                literals, evidence, reason, identity.getCheckerVersion(),
                ConflictExplanation.ProofScope.CATALOG_SIZING_IMPLICATION);
    }

    private static ConflictExplanation fullAssignmentProof(String type, String reason,
            NetworkConstraintProblem problem, CatalogIdentity identity,
            CpSatNetworkOptimizer.Result master, Collection<String> evidence) {
        List<NetworkConstraintProblem.DecisionLiteral> literals = assignmentLiterals(problem, master);
        Set<String> literalKeys = new LinkedHashSet<>();
        for (NetworkConstraintProblem.DecisionLiteral literal : literals) {
            literalKeys.add(literal.variableKey());
        }
        if (!literalKeys.equals(identity.getDecisionKeys())) {
            throw new IllegalArgumentException("Master assignment does not cover catalog decision scope");
        }
        return new ConflictExplanation(type, identity.getRuleId(), identity.getRuleVersion(),
                identity.getSourceSnapshotHash(), identity.getCatalogHash(), literals, evidence,
                reason, identity.getCheckerVersion(),
                ConflictExplanation.ProofScope.FULL_CATALOG_ASSIGNMENT);
    }

    private static List<NetworkConstraintProblem.DecisionLiteral> assignmentLiterals(
            NetworkConstraintProblem problem, CpSatNetworkOptimizer.Result master) {
        if (master.getStatus() != CpSatNetworkOptimizer.Status.FEASIBLE
                && master.getStatus() != CpSatNetworkOptimizer.Status.OPTIMAL) {
            throw new IllegalArgumentException("A feasible master assignment is required");
        }
        if (!master.getSelectedAssets().equals(master.getFlowUnits().keySet())
                || !master.getSelectedAssets().equals(master.getDiameterMm().keySet())) {
            throw new IllegalArgumentException("Master asset, flow and diameter assignments differ");
        }

        List<NetworkConstraintProblem.DecisionLiteral> result = new ArrayList<>();
        List<NetworkConstraintProblem.Node> nodes = new ArrayList<>(problem.getNodes());
        nodes.sort(Comparator.comparing(NetworkConstraintProblem.Node::getId));
        Set<String> allowedRoots = new LinkedHashSet<>();
        for (NetworkConstraintProblem.Node node : nodes) {
            if (!node.isAllowedRoot()) continue;
            allowedRoots.add(node.getId());
            result.add(NetworkConstraintProblem.DecisionLiteral.root(
                    node.getId(), master.getSelectedRoots().contains(node.getId())));
        }
        if (!allowedRoots.containsAll(master.getSelectedRoots())) {
            throw new IllegalArgumentException("Master selected an unknown root");
        }

        Set<String> knownConfigurations = new LinkedHashSet<>();
        for (NetworkConstraintProblem.NodeConfiguration configuration
                : problem.getNodeConfigurations()) {
            knownConfigurations.add(configuration.getId());
            result.add(NetworkConstraintProblem.DecisionLiteral.nodeConfiguration(
                    configuration.getId(), master.getSelectedNodeConfigurations()
                            .contains(configuration.getId())));
        }
        if (!knownConfigurations.containsAll(master.getSelectedNodeConfigurations())) {
            throw new IllegalArgumentException("Master selected an unknown node configuration");
        }

        List<NetworkConstraintProblem.Asset> assets = new ArrayList<>(problem.getAssets());
        assets.sort(Comparator.comparing(NetworkConstraintProblem.Asset::getId));
        Set<String> knownAssets = new LinkedHashSet<>();
        for (NetworkConstraintProblem.Asset asset : assets) {
            knownAssets.add(asset.getId());
            boolean selected = master.getSelectedAssets().contains(asset.getId());
            result.add(NetworkConstraintProblem.DecisionLiteral.asset(asset.getId(), selected));
            Integer selectedDiameter = master.getDiameterMm().get(asset.getId());
            boolean knownDiameter = selectedDiameter == null;
            List<NetworkConstraintProblem.DiameterOption> options = new ArrayList<>(asset.getDiameters());
            options.sort(Comparator.comparingInt(
                    NetworkConstraintProblem.DiameterOption::getDiameterMm));
            for (NetworkConstraintProblem.DiameterOption option : options) {
                boolean chosen = selected && selectedDiameter != null
                        && selectedDiameter == option.getDiameterMm();
                if (chosen) knownDiameter = true;
                result.add(NetworkConstraintProblem.DecisionLiteral.diameter(
                        asset.getId(), option.getDiameterMm(), chosen));
            }
            if (selected && (selectedDiameter == null || !knownDiameter)) {
                throw new IllegalArgumentException("Master selected an unknown diameter for " + asset.getId());
            }
            if (!selected && selectedDiameter != null) {
                throw new IllegalArgumentException("Inactive asset has a diameter assignment");
            }
        }
        if (!knownAssets.containsAll(master.getSelectedAssets())) {
            throw new IllegalArgumentException("Master selected an unknown asset");
        }
        return List.copyOf(result);
    }

    private static List<String> evidence(FrozenNetworkEvaluator.Evaluation evaluation,
            FrozenNetworkCandidate candidate) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add("geometry:" + candidate.getGeometryHash());
        evaluation.getValidationIssues().stream()
                .sorted(Comparator.comparing(RouteValidationIssue::getCode)
                        .thenComparing(issue -> Objects.toString(issue.getSubjectId(), "")))
                .forEach(issue -> addEvidence(result, "validation",
                        issue.getCode(), issue.getSubjectId()));
        evaluation.getSizingIssues().stream()
                .sorted(Comparator.comparing(NetworkSizingIssue::getCode)
                        .thenComparing(issue -> Objects.toString(issue.getEdgeId(), "")))
                .forEach(issue -> addEvidence(result, "sizing", issue.getCode(), issue.getEdgeId()));
        evaluation.getRequiredSizing().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> addEvidence(result, "required_sizing", entry.getKey(),
                        entry.getValue().getFlowTph().toPlainString(),
                        Integer.toString(entry.getValue().getDiameter())));
        return List.copyOf(result);
    }

    private static void addEvidence(Set<String> target, String kind, String... values) {
        if (target.size() >= MAX_EVIDENCE_REFERENCES) return;
        target.add(kind + ":" + sha256(List.of(values)));
    }

    private static String requiredReason(FrozenNetworkEvaluator.Evaluation evaluation) {
        String reason = evaluation.getReason();
        if (reason == null || reason.trim().isEmpty()) return "frozen_evaluation_" + evaluation.getOutcome();
        if (evaluation.getDetail() == null || evaluation.getDetail().trim().isEmpty()) return reason;
        return reason + ":" + evaluation.getDetail();
    }

    private static String rejectionType(FrozenNetworkEvaluator.Evaluation evaluation) {
        return evaluation.getSizingIssues().isEmpty() ? "exact_engineering" : "exact_sizing";
    }

    private static String sha256(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
                for (byte value : digest.digest()) formatter.format("%02x", value);
                return formatter.toString();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @FunctionalInterface
    public interface CandidateFactory {
        CatalogFrozenCandidateAssembler.Assembly assemble(
                CpSatNetworkOptimizer.Result masterResult);
    }

    public static final class Result {
        private final CpSatNetworkRefinement.Outcome outcome;
        private final AcceptedNetworkSolution accepted;
        private final int iterations;
        private final String reason;
        private final CpSatNetworkOptimizer.Status masterStatus;

        private Result(CpSatNetworkRefinement.Outcome outcome, AcceptedNetworkSolution accepted,
                int iterations, String reason, CpSatNetworkOptimizer.Status masterStatus) {
            this.outcome = outcome;
            this.accepted = accepted;
            this.iterations = iterations;
            this.reason = reason;
            this.masterStatus = masterStatus;
        }

        private static Result from(CpSatNetworkRefinement.Result<Attempt> raw) {
            Attempt acceptedAttempt = raw.getAccepted();
            AcceptedNetworkSolution accepted = acceptedAttempt == null ? null : acceptedAttempt.accepted;
            if (raw.getOutcome() == CpSatNetworkRefinement.Outcome.ACCEPTED && accepted == null) {
                throw new IllegalStateException("Accepted refinement result has no exact solution");
            }
            return new Result(raw.getOutcome(), accepted, raw.getIterations(), raw.getReason(),
                    raw.getMasterStatus());
        }

        public CpSatNetworkRefinement.Outcome getOutcome() { return outcome; }
        public AcceptedNetworkSolution getAccepted() { return accepted; }
        public int getIterations() { return iterations; }
        public String getReason() { return reason; }
        public CpSatNetworkOptimizer.Status getMasterStatus() { return masterStatus; }
    }

    private static final class Attempt {
        private final CpSatNetworkOptimizer.Result master;
        private final CatalogFrozenCandidateAssembler.Assembly assembly;
        private final AcceptedNetworkSolution accepted;

        private Attempt(CpSatNetworkOptimizer.Result master,
                CatalogFrozenCandidateAssembler.Assembly assembly,
                AcceptedNetworkSolution accepted) {
            this.master = Objects.requireNonNull(master, "master");
            this.assembly = Objects.requireNonNull(assembly, "assembly");
            this.accepted = accepted;
        }

        private Attempt accepted(AcceptedNetworkSolution value) {
            return new Attempt(master, assembly, Objects.requireNonNull(value, "accepted"));
        }
    }
}
