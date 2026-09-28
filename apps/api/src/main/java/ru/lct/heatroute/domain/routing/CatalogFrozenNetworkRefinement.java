package ru.lct.heatroute.domain.routing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Formatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ru.lct.heatroute.domain.optimization.CatalogIdentity;
import ru.lct.heatroute.domain.optimization.ConflictExplanation;
import ru.lct.heatroute.domain.optimization.ConflictStore;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatNetworkRefinement;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;
import ru.lct.heatroute.domain.sizing.NetworkSizingIssue;

/**
 * Production bridge between the finite-catalog master and the immutable exact evaluator.
 * Only a complete Boolean master assignment is rejected unless a narrower proof is supplied
 * independently; this keeps sizing and engineering feedback sound under topology alternatives.
 */
public final class CatalogFrozenNetworkRefinement {
    private static final int MAX_EVIDENCE_REFERENCES = 256;

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
                master -> new Attempt(master,
                        Objects.requireNonNull(candidateFactory.assemble(master), "frozen candidate"), null),
                attempt -> assess(problem, catalogIdentity, archive, attempt), settings);
        return Result.from(raw);
    }

    private CpSatNetworkRefinement.Assessment<Attempt> assess(NetworkConstraintProblem problem,
            CatalogIdentity identity, AcceptedSolutionArchive archive, Attempt attempt) {
        FrozenNetworkEvaluator.Evaluation evaluation = evaluator.evaluate(attempt.candidate);
        switch (evaluation.getOutcome()) {
            case ACCEPTED:
                AcceptedNetworkSolution accepted = Objects.requireNonNull(
                        evaluation.getAccepted(), "accepted solution");
                archive.add(accepted);
                return CpSatNetworkRefinement.Assessment.accepted(attempt.accepted(accepted));
            case CANONICAL_SIZING_REQUIREMENT:
                return CpSatNetworkRefinement.Assessment.sizingRequired(List.of(fullAssignmentProof(
                        "canonical_sizing", evaluation.getReason(), problem, identity,
                        attempt.master, evidence(evaluation, attempt.candidate))));
            case PROVEN_REJECTED:
                return CpSatNetworkRefinement.Assessment.rejected(fullAssignmentProof(
                        rejectionType(evaluation), evaluation.getReason(), problem, identity,
                        attempt.master, evidence(evaluation, attempt.candidate)));
            case UNKNOWN:
                return CpSatNetworkRefinement.Assessment.unknown(requiredReason(evaluation));
            case ERROR:
                return CpSatNetworkRefinement.Assessment.error(requiredReason(evaluation));
            default:
                throw new IllegalStateException("Unsupported frozen evaluation outcome: "
                        + evaluation.getOutcome());
        }
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
        FrozenNetworkCandidate assemble(CpSatNetworkOptimizer.Result masterResult);
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
        private final FrozenNetworkCandidate candidate;
        private final AcceptedNetworkSolution accepted;

        private Attempt(CpSatNetworkOptimizer.Result master, FrozenNetworkCandidate candidate,
                AcceptedNetworkSolution accepted) {
            this.master = Objects.requireNonNull(master, "master");
            this.candidate = Objects.requireNonNull(candidate, "candidate");
            this.accepted = accepted;
        }

        private Attempt accepted(AcceptedNetworkSolution value) {
            return new Attempt(master, candidate, Objects.requireNonNull(value, "accepted"));
        }
    }
}
