package ru.lct.heatroute.domain.routing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.catalog.CatalogBuildResult;
import ru.lct.heatroute.domain.catalog.RoutingProblemFactory;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.optimization.ConflictStore;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/**
 * Production next-generation planner. It shares one deadline between window loading, catalog
 * generation, CP-SAT refinement and the exact frozen evaluator.
 */
@Component
public final class NextGenerationRoutePlanner {
    public static final String VERSION = "nextgen-network-1";
    public static final String CHECKER_VERSION = "frozen-network-evaluator-1";
    private static final int FLOW_SCALE_DECIMALS = 3;

    private final RoutingProblemFactory problemFactory;
    private final RoutingFeatureWindowLoader featureWindowLoader;
    private final BoundedCatalogNetworkStageFactory stageFactory;
    private final AdaptiveCatalogNetworkSearch search;
    private final FinishedRouteVariantSelector selector = new FinishedRouteVariantSelector();

    public NextGenerationRoutePlanner(
            RoutingProblemFactory problemFactory,
            RoutingFeatureWindowLoader featureWindowLoader,
            BoundedCatalogNetworkStageFactory stageFactory,
            CpSatRuntime cpSatRuntime,
            FrozenNetworkEvaluator evaluator) {
        this.problemFactory = Objects.requireNonNull(problemFactory, "problemFactory");
        this.featureWindowLoader = Objects.requireNonNull(
                featureWindowLoader, "featureWindowLoader");
        this.stageFactory = Objects.requireNonNull(stageFactory, "stageFactory");
        this.search = new AdaptiveCatalogNetworkSearch(new CatalogFrozenNetworkRefinement(
                new CpSatNetworkOptimizer(Objects.requireNonNull(cpSatRuntime, "cpSatRuntime")),
                Objects.requireNonNull(evaluator, "evaluator")));
    }

    public Execution execute(
            RoutingExecutionContext context,
            Collection<ImportedOfficialFeature> calculationCore,
            TopologyAnalysis topology,
            OfficialRunParameters parameters,
            RoutingFeatureSource featureSource,
            Settings settings) {
        Objects.requireNonNull(settings, "settings");
        long started = System.nanoTime();
        long deadline = saturatingAdd(started, settings.totalBudgetNanos);
        ensureActive();
        RoutingProblemSnapshot problem = problemFactory.create(
                context, parameters.validated(), calculationCore, topology);
        List<ImportedOfficialFeature> features = featureWindowLoader.load(
                problem, calculationCore, featureSource);

        long catalogBudget = Math.min(settings.catalogBudgetNanos,
                remaining(deadline) - settings.finalReserveNanos);
        if (catalogBudget <= 0L) {
            return Execution.beforeCatalog(problem, features.size(), elapsedMillis(started),
                    "deadline_before_catalog");
        }
        BoundedRootDemandCatalogGenerator.Options catalogOptions =
                BoundedRootDemandCatalogGenerator.Options.bounded(
                        duration(catalogBudget), settings.maxPairs, settings.maxRouteCalls,
                        settings.maxPathsPerPair, settings.maxEgressCandidates);
        BoundedCatalogNetworkStageFactory.Preparation prepared = stageFactory.prepare(
                problem, features, catalogOptions, FLOW_SCALE_DECIMALS, CHECKER_VERSION,
                "nextgen", "engineering");
        CatalogBuildResult build = prepared.getGeneratedCatalog().getBuildResult();
        if (prepared.getStage().isEmpty()) {
            return Execution.incomplete(problem, build, features.size(), elapsedMillis(started),
                    prepared.getStageIncompleteReason().orElse("stage_incomplete"));
        }

        long remaining = remaining(deadline);
        long refinementBudget = Math.min(settings.perCatalogBudgetNanos,
                remaining - settings.finalReserveNanos);
        if (remaining <= settings.finalReserveNanos
                || refinementBudget <= settings.evaluationReserveNanos) {
            return Execution.incomplete(problem, build, features.size(), elapsedMillis(started),
                    "deadline_before_refinement");
        }
        AdaptiveCatalogNetworkSearch.Settings searchSettings =
                AdaptiveCatalogNetworkSearch.Settings.bounded(
                        remaining, TimeUnit.NANOSECONDS,
                        settings.finalReserveNanos, TimeUnit.NANOSECONDS,
                        refinementBudget, TimeUnit.NANOSECONDS,
                        settings.evaluationReserveNanos, TimeUnit.NANOSECONDS,
                        0, settings.maxRefinementIterations, settings.randomSeed);
        ConflictStore conflicts = new ConflictStore(settings.maxConflicts);
        AcceptedSolutionArchive archive = new AcceptedSolutionArchive(settings.archiveCapacity);
        AdaptiveCatalogNetworkSearch.Result solved = search.solve(
                prepared.getStage().orElseThrow(), conflicts, archive,
                (current, request, budget) -> AdaptiveCatalogNetworkSearch.Expansion.exhausted(
                        "targeted_catalog_expansion_not_implemented"),
                searchSettings);
        if (solved.getOutcome() != AdaptiveCatalogNetworkSearch.Outcome.ACCEPTED) {
            return Execution.ended(problem, build, features.size(), elapsedMillis(started),
                    solved, conflicts.size(), archive.size(), null);
        }
        OfficialCalculationResult result = assembleResult(
                context.getInputProfile(), problem.getDemands().size(), archive.snapshot());
        return Execution.ended(problem, build, features.size(), elapsedMillis(started),
                solved, conflicts.size(), archive.size(), result);
    }

    private OfficialCalculationResult assembleResult(String inputProfile, int demandCount,
            List<AcceptedNetworkSolution> accepted) {
        List<RouteVariant> candidates = new ArrayList<>(accepted.size());
        accepted.forEach(solution -> candidates.add(solution.toRouteVariant()));
        List<RouteVariant> selected = selector.select(candidates);
        Set<String> geometries = new LinkedHashSet<>();
        List<RouteVariant> distinct = new ArrayList<>();
        for (RouteVariant variant : selected) {
            String geometry = FrozenNetworkCandidate.geometryHash(
                    variant.getNodes(), variant.getEdges());
            if (geometries.add(geometry)) distinct.add(variant.withRank(distinct.size() + 1));
        }
        if (distinct.isEmpty()) {
            throw new IllegalStateException("Accepted archive produced no publishable variant");
        }
        return new OfficialCalculationResult(
                VERSION, inputProfile, demandCount, distinct, distinct.get(0).getId());
    }

    private static Duration duration(long nanos) {
        return Duration.ofNanos(Math.max(1L, nanos));
    }

    private static long remaining(long deadline) {
        return deadline - System.nanoTime();
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Next-generation route planning cancelled");
        }
    }

    public static final class Settings {
        private final long totalBudgetNanos;
        private final long catalogBudgetNanos;
        private final long finalReserveNanos;
        private final long perCatalogBudgetNanos;
        private final long evaluationReserveNanos;
        private final int maxPairs;
        private final int maxRouteCalls;
        private final int maxPathsPerPair;
        private final int maxEgressCandidates;
        private final int maxRefinementIterations;
        private final int randomSeed;
        private final int maxConflicts;
        private final int archiveCapacity;

        private Settings(Duration totalBudget, Duration catalogBudget, Duration finalReserve,
                Duration perCatalogBudget, Duration evaluationReserve,
                int maxPairs, int maxRouteCalls, int maxPathsPerPair,
                int maxEgressCandidates, int maxRefinementIterations, int randomSeed,
                int maxConflicts, int archiveCapacity) {
            this.totalBudgetNanos = positive(totalBudget, "total budget");
            this.catalogBudgetNanos = positive(catalogBudget, "catalog budget");
            this.finalReserveNanos = nonNegative(finalReserve, "final reserve");
            this.perCatalogBudgetNanos = positive(perCatalogBudget, "per-catalog budget");
            this.evaluationReserveNanos = nonNegative(
                    evaluationReserve, "evaluation reserve");
            if (finalReserveNanos >= totalBudgetNanos
                    || evaluationReserveNanos >= perCatalogBudgetNanos
                    || maxPairs <= 0 || maxRouteCalls <= 0 || maxPathsPerPair <= 0
                    || maxEgressCandidates <= 0 || maxRefinementIterations <= 0
                    || randomSeed < 0 || maxConflicts <= 0 || archiveCapacity <= 0) {
                throw new IllegalArgumentException("Invalid next-generation planner settings");
            }
            this.maxPairs = maxPairs;
            this.maxRouteCalls = maxRouteCalls;
            this.maxPathsPerPair = maxPathsPerPair;
            this.maxEgressCandidates = maxEgressCandidates;
            this.maxRefinementIterations = maxRefinementIterations;
            this.randomSeed = randomSeed;
            this.maxConflicts = maxConflicts;
            this.archiveCapacity = archiveCapacity;
        }

        /** Production competition budget shared by catalog generation and exact refinement. */
        public static Settings production() {
            return bounded(Duration.ofSeconds(150), Duration.ofSeconds(105),
                    Duration.ofSeconds(5), Duration.ofSeconds(45),
                    Duration.ofSeconds(5), 256, 1_024, 3, 8,
                    128, 2026, 10_000, 8);
        }

        /** Retained for source compatibility with the pre-promotion internal harness. */
        public static Settings initial() {
            return production();
        }

        public static Settings bounded(Duration totalBudget, Duration catalogBudget,
                Duration finalReserve, Duration perCatalogBudget,
                Duration evaluationReserve, int maxPairs, int maxRouteCalls,
                int maxPathsPerPair, int maxEgressCandidates,
                int maxRefinementIterations, int randomSeed,
                int maxConflicts, int archiveCapacity) {
            return new Settings(totalBudget, catalogBudget, finalReserve, perCatalogBudget,
                    evaluationReserve, maxPairs, maxRouteCalls, maxPathsPerPair,
                    maxEgressCandidates, maxRefinementIterations, randomSeed,
                    maxConflicts, archiveCapacity);
        }

        private static long positive(Duration value, String label) {
            long result = nanos(value, label);
            if (result <= 0L) throw new IllegalArgumentException(label + " must be positive");
            return result;
        }

        private static long nonNegative(Duration value, String label) {
            long result = nanos(value, label);
            if (result < 0L) throw new IllegalArgumentException(label + " cannot be negative");
            return result;
        }

        private static long nanos(Duration value, String label) {
            try {
                return Objects.requireNonNull(value, label).toNanos();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(label + " is too large", exception);
            }
        }
    }

    public static final class Execution {
        private final AdaptiveCatalogNetworkSearch.Outcome outcome;
        private final String reason;
        private final String snapshotHash;
        private final CatalogBuildResult catalogBuild;
        private final int featureCount;
        private final long elapsedMillis;
        private final int refinementRuns;
        private final int conflictCount;
        private final int archiveSize;
        private final OfficialCalculationResult result;

        private Execution(AdaptiveCatalogNetworkSearch.Outcome outcome, String reason,
                String snapshotHash, CatalogBuildResult catalogBuild, int featureCount,
                long elapsedMillis, int refinementRuns, int conflictCount, int archiveSize,
                OfficialCalculationResult result) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.reason = Objects.requireNonNull(reason, "reason");
            this.snapshotHash = Objects.requireNonNull(snapshotHash, "snapshotHash");
            this.catalogBuild = catalogBuild;
            this.featureCount = featureCount;
            this.elapsedMillis = elapsedMillis;
            this.refinementRuns = refinementRuns;
            this.conflictCount = conflictCount;
            this.archiveSize = archiveSize;
            this.result = result;
        }

        private static Execution beforeCatalog(RoutingProblemSnapshot problem,
                int featureCount, long elapsedMillis, String reason) {
            return new Execution(AdaptiveCatalogNetworkSearch.Outcome.CATALOG_INCOMPLETE,
                    reason, problem.getSnapshotHash(), null, featureCount, elapsedMillis,
                    0, 0, 0, null);
        }

        private static Execution incomplete(RoutingProblemSnapshot problem,
                CatalogBuildResult build, int featureCount, long elapsedMillis, String reason) {
            return new Execution(AdaptiveCatalogNetworkSearch.Outcome.CATALOG_INCOMPLETE,
                    reason, problem.getSnapshotHash(), build, featureCount, elapsedMillis,
                    0, 0, 0, null);
        }

        private static Execution ended(RoutingProblemSnapshot problem, CatalogBuildResult build,
                int featureCount, long elapsedMillis,
                AdaptiveCatalogNetworkSearch.Result solved, int conflicts, int archiveSize,
                OfficialCalculationResult result) {
            return new Execution(solved.getOutcome(), solved.getReason(),
                    problem.getSnapshotHash(), build, featureCount, elapsedMillis,
                    solved.getRefinementRuns(), conflicts, archiveSize, result);
        }

        public AdaptiveCatalogNetworkSearch.Outcome getOutcome() { return outcome; }
        public String getReason() { return reason; }
        public String getSnapshotHash() { return snapshotHash; }
        public CatalogBuildResult getCatalogBuild() { return catalogBuild; }
        public int getFeatureCount() { return featureCount; }
        public long getElapsedMillis() { return elapsedMillis; }
        public int getRefinementRuns() { return refinementRuns; }
        public int getConflictCount() { return conflictCount; }
        public int getArchiveSize() { return archiveSize; }
        public OfficialCalculationResult getResult() { return result; }
    }
}
