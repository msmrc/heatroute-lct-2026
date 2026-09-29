package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import ru.lct.heatroute.domain.catalog.CatalogBuildResult;
import ru.lct.heatroute.domain.catalog.CatalogPhysicalAsset;
import ru.lct.heatroute.domain.catalog.DirectedPathOption;
import ru.lct.heatroute.domain.catalog.RoutingCatalogSnapshot;
import ru.lct.heatroute.domain.optimization.CatalogIdentity;
import ru.lct.heatroute.domain.optimization.ConflictStore;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatNetworkRefinement;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;

/**
 * Shared-deadline outer loop for catalog expansion. The usual transition is strictly additive.
 * A bounded portfolio may also restart from an independently generated catalog for the same
 * immutable source/rule/checker scope. Exact solutions and versioned conflict proofs survive
 * between stages; incompatible transitions are rejected explicitly.
 */
public final class AdaptiveCatalogNetworkSearch {
    private final CatalogFrozenNetworkRefinement refinement;

    public AdaptiveCatalogNetworkSearch(CatalogFrozenNetworkRefinement refinement) {
        this.refinement = Objects.requireNonNull(refinement, "refinement");
    }

    public Result solve(Stage initialStage, ConflictStore conflictStore,
            AcceptedSolutionArchive archive, AdaptiveCatalogExpander expander, Settings settings) {
        Objects.requireNonNull(initialStage, "initialStage");
        Objects.requireNonNull(conflictStore, "conflictStore");
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(expander, "expander");
        Objects.requireNonNull(settings, "settings");
        long started = System.nanoTime();
        long deadline = saturatingAdd(started, settings.timeBudgetNanos);
        Stage stage = initialStage;
        int expansions = 0;
        int refinementRuns = 0;

        while (true) {
            ensureActive();
            long remaining = deadline - System.nanoTime();
            if (remaining <= settings.finalReserveNanos) {
                return ended(stage, archive, expansions, refinementRuns,
                        stage.getBuildResult().isComplete()
                                ? Outcome.SEARCH_LIMIT_REACHED : Outcome.CATALOG_INCOMPLETE,
                        "adaptive_deadline");
            }
            long stageBudget = Math.min(settings.maxRefinementNanos,
                    remaining - settings.finalReserveNanos);
            if (stageBudget <= settings.evaluationReserveNanos) {
                return ended(stage, archive, expansions, refinementRuns,
                        stage.getBuildResult().isComplete()
                                ? Outcome.SEARCH_LIMIT_REACHED : Outcome.CATALOG_INCOMPLETE,
                        "insufficient_refinement_budget");
            }
            CpSatNetworkRefinement.Settings refinementSettings =
                    CpSatNetworkRefinement.Settings.bounded(
                            stageBudget, TimeUnit.NANOSECONDS,
                            settings.evaluationReserveNanos, TimeUnit.NANOSECONDS,
                            settings.maxRefinementIterations,
                            nextSeed(settings.randomSeed, refinementRuns));
            CatalogFrozenNetworkRefinement.Result current = refinement.solve(
                    stage.getProblem(), stage.getIdentity(), conflictStore,
                    stage.getCandidateFactory(), archive, refinementSettings);
            refinementRuns++;
            if (current.getOutcome() == CpSatNetworkRefinement.Outcome.ACCEPTED) {
                return new Result(Outcome.ACCEPTED, current.getAccepted(), expansions,
                        refinementRuns, "accepted", stage.getCatalog().getCatalogHash());
            }
            if (current.getOutcome() == CpSatNetworkRefinement.Outcome.ERROR
                    || current.getOutcome() == CpSatNetworkRefinement.Outcome.STALLED) {
                return ended(stage, archive, expansions, refinementRuns, Outcome.ERROR,
                        "refinement_" + current.getOutcome() + ":" + current.getReason());
            }
            if (current.getOutcome() == CpSatNetworkRefinement.Outcome.INFEASIBLE_IN_CATALOG
                    && stage.getBuildResult().isComplete()) {
                return ended(stage, archive, expansions, refinementRuns,
                        Outcome.INFEASIBLE_IN_COMPLETE_CATALOG, current.getReason());
            }
            if (current.getOutcome() == CpSatNetworkRefinement.Outcome.SEARCH_LIMIT_REACHED
                    && stage.getBuildResult().isComplete()) {
                return ended(stage, archive, expansions, refinementRuns,
                        Outcome.SEARCH_LIMIT_REACHED, current.getReason());
            }
            if (expansions >= settings.maxExpansions) {
                return ended(stage, archive, expansions, refinementRuns, Outcome.CATALOG_INCOMPLETE,
                        "catalog_expansion_limit:" + current.getOutcome()
                                + ":" + current.getReason());
            }

            ensureActive();
            long expansionBudget = deadline - System.nanoTime() - settings.finalReserveNanos;
            if (expansionBudget <= 0L) {
                return ended(stage, archive, expansions, refinementRuns, Outcome.CATALOG_INCOMPLETE,
                        "deadline_before_catalog_expansion");
            }
            Expansion expansion;
            try {
                expansion = Objects.requireNonNull(expander.expand(stage,
                        new ExpansionRequest(current.getOutcome(), current.getReason(), expansions + 1),
                        expansionBudget), "catalog expansion");
            } catch (CancellationException exception) {
                throw exception;
            } catch (RuntimeException | LinkageError exception) {
                return ended(stage, archive, expansions, refinementRuns, Outcome.ERROR,
                        "catalog_expansion_failure:" + exception.getClass().getSimpleName());
            }
            if (!expansion.hasStage()) {
                return ended(stage, archive, expansions, refinementRuns, Outcome.CATALOG_INCOMPLETE,
                        expansion.getReason());
            }
            Stage next = expansion.getStage();
            try {
                if (expansion.getTransition() == Expansion.Transition.RESTART) {
                    validatePortfolioRestart(stage, next);
                } else {
                    validateAdditiveExpansion(stage, next);
                }
            } catch (RuntimeException exception) {
                return ended(stage, archive, expansions, refinementRuns, Outcome.ERROR,
                        (expansion.getTransition() == Expansion.Transition.RESTART
                                ? "invalid_catalog_restart:"
                                : "non_monotonic_catalog_expansion:")
                                + exception.getMessage());
            }
            stage = next;
            expansions++;
        }
    }

    private static Result ended(Stage stage, AcceptedSolutionArchive archive,
            int expansions, int refinementRuns, Outcome outcome, String reason) {
        return new Result(outcome, archive.best(), expansions, refinementRuns, required(reason),
                stage.getCatalog().getCatalogHash());
    }

    private static void validateAdditiveExpansion(Stage previous, Stage next) {
        RoutingCatalogSnapshot before = previous.getCatalog();
        RoutingCatalogSnapshot after = next.getCatalog();
        if (!before.getSourceSnapshotHash().equals(after.getSourceSnapshotHash())
                || !before.getRuleId().equals(after.getRuleId())
                || !before.getRuleVersion().equals(after.getRuleVersion())) {
            throw new IllegalArgumentException("source or rule scope changed");
        }
        if (before.getCatalogHash().equals(after.getCatalogHash())) {
            throw new IllegalArgumentException("catalog hash did not change");
        }
        for (CatalogPhysicalAsset asset : before.getPhysicalAssets()) {
            CatalogPhysicalAsset retained = after.physicalAsset(asset.getId());
            if (retained == null || !asset.getFingerprint().equals(retained.getFingerprint())) {
                throw new IllegalArgumentException("physical asset was removed or changed: " + asset.getId());
            }
        }
        for (DirectedPathOption option : before.getPathOptions()) {
            DirectedPathOption retained = after.pathOption(option.getId());
            if (retained == null || !option.getFingerprint().equals(retained.getFingerprint())) {
                throw new IllegalArgumentException("path option was removed or changed: " + option.getId());
            }
        }
        if (!next.getIdentity().getDecisionKeys().containsAll(
                previous.getIdentity().getDecisionKeys())) {
            throw new IllegalArgumentException("master decision domain is not additive");
        }
        validateProblemPreservation(previous.getProblem(), next.getProblem());
    }

    /**
     * Validates a non-additive fallback stage. A restart is allowed to replace generated
     * geometry, assets and chamber configurations, but never the imported task or the mandatory
     * root/terminal semantics. Catalog-scoped cuts are filtered by {@link ConflictStore}; an
     * accepted result still has to pass the same frozen exact evaluator.
     */
    private static void validatePortfolioRestart(Stage previous, Stage next) {
        CatalogIdentity beforeIdentity = previous.getIdentity();
        CatalogIdentity afterIdentity = next.getIdentity();
        if (!beforeIdentity.getSourceSnapshotHash().equals(afterIdentity.getSourceSnapshotHash())
                || !beforeIdentity.getRuleId().equals(afterIdentity.getRuleId())
                || !beforeIdentity.getRuleVersion().equals(afterIdentity.getRuleVersion())
                || !beforeIdentity.getCheckerVersion().equals(
                        afterIdentity.getCheckerVersion())) {
            throw new IllegalArgumentException("source, rule or checker scope changed");
        }
        if (beforeIdentity.getCatalogHash().equals(afterIdentity.getCatalogHash())) {
            throw new IllegalArgumentException("catalog hash did not change");
        }
        validateBoundaryNodeSemantics(previous.getProblem(), next.getProblem());
    }

    private static void validateBoundaryNodeSemantics(NetworkConstraintProblem before,
            NetworkConstraintProblem after) {
        for (NetworkConstraintProblem.Node node : before.getNodes()) {
            if (!node.isAllowedRoot() && !node.isTerminal()) continue;
            NetworkConstraintProblem.Node retained = after.node(node.getId());
            if (retained == null
                    || retained.isAllowedRoot() != node.isAllowedRoot()
                    || retained.isTerminal() != node.isTerminal()
                    || retained.getDemandUnits() != node.getDemandUnits()) {
                throw new IllegalArgumentException("root or terminal semantics changed");
            }
        }
    }

    private static void validateProblemPreservation(NetworkConstraintProblem before,
            NetworkConstraintProblem after) {
        for (NetworkConstraintProblem.Node node : before.getNodes()) {
            NetworkConstraintProblem.Node retained = after.node(node.getId());
            if (retained == null || retained.isAllowedRoot() != node.isAllowedRoot()
                    || retained.isTerminal() != node.isTerminal()
                    || retained.getDemandUnits() != node.getDemandUnits()
                    || retained.isConfigurationRequired() != node.isConfigurationRequired()) {
                throw new IllegalArgumentException("master node was removed or changed: " + node.getId());
            }
        }
        for (NetworkConstraintProblem.NodeConfiguration configuration
                : before.getNodeConfigurations()) {
            NetworkConstraintProblem.NodeConfiguration retained =
                    after.nodeConfiguration(configuration.getId());
            if (retained == null
                    || !retained.getNodeId().equals(configuration.getNodeId())
                    || !retained.getIncidentAssetIds().equals(
                            configuration.getIncidentAssetIds())) {
                throw new IllegalArgumentException(
                        "node configuration was removed or changed: " + configuration.getId());
            }
        }
        for (NetworkConstraintProblem.Asset asset : before.getAssets()) {
            NetworkConstraintProblem.Asset retained = after.asset(asset.getId());
            if (retained == null
                    || !retained.getFromNodeId().equals(asset.getFromNodeId())
                    || !retained.getToNodeId().equals(asset.getToNodeId())
                    || retained.getFixedCostUnits() != asset.getFixedCostUnits()) {
                throw new IllegalArgumentException("master asset was removed or changed: " + asset.getId());
            }
            for (NetworkConstraintProblem.DiameterOption option : asset.getDiameters()) {
                NetworkConstraintProblem.DiameterOption retainedOption = retained.getDiameters().stream()
                        .filter(value -> value.getDiameterMm() == option.getDiameterMm())
                        .findFirst().orElse(null);
                if (retainedOption == null
                        || retainedOption.getCapacityUnits() != option.getCapacityUnits()
                        || retainedOption.getCostUnits() != option.getCostUnits()) {
                    throw new IllegalArgumentException("diameter option was removed or changed: "
                            + asset.getId() + ":" + option.getDiameterMm());
                }
            }
        }
        Set<String> retainedConflicts = new LinkedHashSet<>();
        for (NetworkConstraintProblem.Conflict conflict : after.getConflicts()) {
            retainedConflicts.add(conflictSignature(conflict));
        }
        for (NetworkConstraintProblem.Conflict conflict : before.getConflicts()) {
            if (!retainedConflicts.contains(conflictSignature(conflict))) {
                throw new IllegalArgumentException("validated master conflict was removed");
            }
        }
    }

    private static String conflictSignature(NetworkConstraintProblem.Conflict conflict) {
        List<String> literals = new ArrayList<>();
        for (NetworkConstraintProblem.DecisionLiteral literal : conflict.getLiterals()) {
            literals.add(literal.variableKey() + "=" + literal.isExpected());
        }
        literals.sort(String::compareTo);
        return String.join(",", literals) + "#" + conflict.getReason();
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static int nextSeed(int base, int offset) {
        return (int) Math.floorMod((long) base + offset, (long) Integer.MAX_VALUE);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Adaptive catalog search cancelled");
        }
    }

    private static String required(String value) {
        String result = Objects.requireNonNull(value, "reason").trim();
        if (result.isEmpty()) throw new IllegalArgumentException("Reason is required");
        return result;
    }

    public enum Outcome {
        ACCEPTED,
        INFEASIBLE_IN_COMPLETE_CATALOG,
        CATALOG_INCOMPLETE,
        SEARCH_LIMIT_REACHED,
        ERROR
    }

    public static final class Stage {
        private final CatalogBuildResult buildResult;
        private final NetworkConstraintProblem problem;
        private final CatalogIdentity identity;
        private final CatalogFrozenNetworkRefinement.CandidateFactory candidateFactory;

        public Stage(CatalogBuildResult buildResult, NetworkConstraintProblem problem,
                CatalogIdentity identity,
                CatalogFrozenNetworkRefinement.CandidateFactory candidateFactory) {
            this.buildResult = Objects.requireNonNull(buildResult, "buildResult");
            this.problem = Objects.requireNonNull(problem, "problem");
            this.identity = Objects.requireNonNull(identity, "identity");
            this.candidateFactory = Objects.requireNonNull(candidateFactory, "candidateFactory");
            RoutingCatalogSnapshot catalog = buildResult.getSnapshot();
            CatalogIdentity expected = CatalogIdentity.fromProblem(
                    catalog.getSourceSnapshotHash(), catalog.getRuleId(), catalog.getRuleVersion(),
                    identity.getCheckerVersion(), catalog.getCatalogHash(), problem);
            if (!expected.getSourceSnapshotHash().equals(identity.getSourceSnapshotHash())
                    || !expected.getRuleId().equals(identity.getRuleId())
                    || !expected.getRuleVersion().equals(identity.getRuleVersion())
                    || !expected.getCatalogHash().equals(identity.getCatalogHash())
                    || !expected.getDecisionKeys().equals(identity.getDecisionKeys())) {
                throw new IllegalArgumentException("Catalog stage identity does not match its snapshot/problem");
            }
        }

        public CatalogBuildResult getBuildResult() { return buildResult; }
        public RoutingCatalogSnapshot getCatalog() { return buildResult.getSnapshot(); }
        public NetworkConstraintProblem getProblem() { return problem; }
        public CatalogIdentity getIdentity() { return identity; }
        public CatalogFrozenNetworkRefinement.CandidateFactory getCandidateFactory() {
            return candidateFactory;
        }
    }

    @FunctionalInterface
    public interface AdaptiveCatalogExpander {
        Expansion expand(Stage current, ExpansionRequest request, long remainingNanos);
    }

    public static final class ExpansionRequest {
        private final CpSatNetworkRefinement.Outcome refinementOutcome;
        private final String reason;
        private final int expansionNumber;

        private ExpansionRequest(CpSatNetworkRefinement.Outcome refinementOutcome,
                String reason, int expansionNumber) {
            this.refinementOutcome = Objects.requireNonNull(refinementOutcome, "refinementOutcome");
            this.reason = required(reason);
            this.expansionNumber = expansionNumber;
        }

        public CpSatNetworkRefinement.Outcome getRefinementOutcome() { return refinementOutcome; }
        public String getReason() { return reason; }
        public int getExpansionNumber() { return expansionNumber; }
    }

    public static final class Expansion {
        private final Stage stage;
        private final String reason;
        private final Transition transition;

        private Expansion(Stage stage, String reason, Transition transition) {
            this.stage = stage;
            this.reason = required(reason);
            this.transition = transition;
        }

        public static Expansion expanded(Stage stage) {
            return new Expansion(Objects.requireNonNull(stage, "stage"), "catalog_expanded",
                    Transition.ADDITIVE);
        }

        /**
         * Tries another deterministic catalog strategy for the same imported task. Unlike
         * {@link #expanded(Stage)}, generated assets may be replaced, so catalog-scoped proofs
         * cannot leak into the alternative stage.
         */
        public static Expansion restarted(Stage stage) {
            return new Expansion(Objects.requireNonNull(stage, "stage"), "catalog_restarted",
                    Transition.RESTART);
        }

        public static Expansion exhausted(String reason) {
            return new Expansion(null, reason, null);
        }

        public boolean hasStage() { return stage != null; }
        public Stage getStage() { return stage; }
        public String getReason() { return reason; }
        public Transition getTransition() { return transition; }

        public enum Transition { ADDITIVE, RESTART }
    }

    public static final class Settings {
        private final long timeBudgetNanos;
        private final long finalReserveNanos;
        private final long maxRefinementNanos;
        private final long evaluationReserveNanos;
        private final int maxExpansions;
        private final int maxRefinementIterations;
        private final int randomSeed;

        private Settings(long timeBudgetNanos, long finalReserveNanos,
                long maxRefinementNanos, long evaluationReserveNanos,
                int maxExpansions, int maxRefinementIterations, int randomSeed) {
            if (timeBudgetNanos <= 0L || finalReserveNanos < 0L
                    || maxRefinementNanos <= 0L || evaluationReserveNanos < 0L
                    || finalReserveNanos >= timeBudgetNanos
                    || evaluationReserveNanos >= maxRefinementNanos
                    || maxExpansions < 0 || maxRefinementIterations <= 0 || randomSeed < 0) {
                throw new IllegalArgumentException("Invalid adaptive catalog search settings");
            }
            this.timeBudgetNanos = timeBudgetNanos;
            this.finalReserveNanos = finalReserveNanos;
            this.maxRefinementNanos = maxRefinementNanos;
            this.evaluationReserveNanos = evaluationReserveNanos;
            this.maxExpansions = maxExpansions;
            this.maxRefinementIterations = maxRefinementIterations;
            this.randomSeed = randomSeed;
        }

        public static Settings bounded(long time, TimeUnit timeUnit,
                long finalReserve, TimeUnit finalReserveUnit,
                long perCatalog, TimeUnit perCatalogUnit,
                long evaluationReserve, TimeUnit evaluationReserveUnit,
                int maxExpansions, int maxRefinementIterations, int randomSeed) {
            return new Settings(
                    Objects.requireNonNull(timeUnit, "timeUnit").toNanos(time),
                    Objects.requireNonNull(finalReserveUnit, "finalReserveUnit").toNanos(finalReserve),
                    Objects.requireNonNull(perCatalogUnit, "perCatalogUnit").toNanos(perCatalog),
                    Objects.requireNonNull(evaluationReserveUnit, "evaluationReserveUnit")
                            .toNanos(evaluationReserve),
                    maxExpansions, maxRefinementIterations, randomSeed);
        }
    }

    public static final class Result {
        private final Outcome outcome;
        private final AcceptedNetworkSolution accepted;
        private final int expansions;
        private final int refinementRuns;
        private final String reason;
        private final String finalCatalogHash;

        private Result(Outcome outcome, AcceptedNetworkSolution accepted, int expansions,
                int refinementRuns, String reason, String finalCatalogHash) {
            this.outcome = outcome;
            this.accepted = accepted;
            this.expansions = expansions;
            this.refinementRuns = refinementRuns;
            this.reason = required(reason);
            this.finalCatalogHash = Objects.requireNonNull(finalCatalogHash, "finalCatalogHash");
        }

        public Outcome getOutcome() { return outcome; }
        public AcceptedNetworkSolution getAccepted() { return accepted; }
        public int getExpansions() { return expansions; }
        public int getRefinementRuns() { return refinementRuns; }
        public String getReason() { return reason; }
        public String getFinalCatalogHash() { return finalCatalogHash; }
    }
}
