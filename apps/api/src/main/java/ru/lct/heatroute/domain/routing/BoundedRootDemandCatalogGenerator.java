package ru.lct.heatroute.domain.routing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBWriter;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.catalog.CatalogBuildResult;
import ru.lct.heatroute.domain.catalog.CatalogMetricPoint;
import ru.lct.heatroute.domain.catalog.CatalogPhysicalAsset;
import ru.lct.heatroute.domain.catalog.DirectedPathOption;
import ru.lct.heatroute.domain.catalog.PathAdmissionCertificate;
import ru.lct.heatroute.domain.catalog.PhysicalAssetCompiler;
import ru.lct.heatroute.domain.catalog.RoutingCatalogSnapshot;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Быстрый начальный N03-каталог: несколько проверенных root-to-demand путей без запуска
 * полного перебора маршрутов. Генератор намеренно не заявляет полноту общего дерева/камер.
 */
@Component
public final class BoundedRootDemandCatalogGenerator {
    private static final int MAX_COHERENT_RETRY_DEMANDS = 64;
    static final String GENERATOR_ID = "bounded-root-demand";
    static final String GENERATOR_VERSION = "2";
    private static final int SHARED_SEED_GROUP_CAPACITY = 4;
    private static final int DENSE_SPECIAL_LAYER_THRESHOLD = 32;
    private static final int MAX_NORMAL_SEED_ATTEMPTS = 128;
    private static final List<RoutePreference> DIRECT_PREFERENCES = List.of(
            RoutePreference.ENGINEERING, RoutePreference.LEFT, RoutePreference.RIGHT);

    private final OfficialObstacleRouter router;
    private final OfficialPipeCatalog pipes;
    private final BoundedSharedNetworkSeedGenerator sharedSeedGenerator;
    private final PhysicalAssetCompiler assetCompiler = new PhysicalAssetCompiler();

    public BoundedRootDemandCatalogGenerator(OfficialObstacleRouter router, OfficialPipeCatalog pipes) {
        this.router = Objects.requireNonNull(router, "router");
        this.pipes = Objects.requireNonNull(pipes, "pipes");
        this.sharedSeedGenerator = new BoundedSharedNetworkSeedGenerator(router, pipes);
    }

    public GeneratedCatalog generate(RoutingProblemSnapshot problem,
            Collection<ImportedOfficialFeature> relevantFeatures, Options options) {
        return generate(problem, PreparedRoutingFeatureWindow.prepare(router, relevantFeatures), options);
    }

    public GeneratedCatalog generate(RoutingProblemSnapshot problem,
            PreparedRoutingFeatureWindow featureWindow, Options options) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(featureWindow, "featureWindow");
        Objects.requireNonNull(options, "options");
        List<ImportedOfficialFeature> features = featureWindow.features();
        String windowFingerprint = windowFingerprint(problem.getSnapshotHash(), features);
        long started = System.nanoTime();
        State state = new State(saturatingAdd(started, options.timeBudgetNanos));
        ProbeDiameter probe = probeDiameter(problem);
        int probeDiameter = probe.diameterMm;
        OfficialRoutingEnvironment environment = featureWindow.environment();
        Set<String> structurallyUnroutableDemands = structurallyUnroutableDemands(
                problem, environment, probeDiameter);
        if (!structurallyUnroutableDemands.isEmpty()) {
            return structurallyIncomplete(problem, features, windowFingerprint, probeDiameter,
                    structurallyUnroutableDemands, started);
        }
        Set<String> routableDemandIds = problem.getDemands().stream()
                .map(RoutingProblemSnapshot.Demand::getId)
                .filter(id -> !structurallyUnroutableDemands.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> demandPorts = new LinkedHashMap<>();
        for (RoutingProblemSnapshot.Demand demand : problem.getDemands()) {
            demandPorts.put(demand.getId(), "demand-port:" + demand.getId());
        }
        Map<String, String> rootPorts = new LinkedHashMap<>();
        for (RoutingProblemSnapshot.RootCandidate root : problem.getRoots()) {
            rootPorts.put(root.getId(), "root-port:" + root.getId());
        }

        List<GeneratedPath> generated = new ArrayList<>();
        List<String> remaining = new ArrayList<>();
        Set<String> truncations = new LinkedHashSet<>();
        long totalPairs = Math.multiplyExact((long) problem.getRoots().size(),
                (long) routableDemandIds.size());
        List<RootDemandPair> orderedPairs = coverageFirstPairs(problem).stream()
                .filter(pair -> routableDemandIds.contains(pair.demand.getId()))
                .collect(Collectors.toList());
        Set<String> attemptedPairs = new LinkedHashSet<>();
        Set<String> coveredDemands = new LinkedHashSet<>();
        boolean stopped = false;
        long clusteredReserveNanos = sharedSeedReserveNanos(
                options, routableDemandIds.size());
        long standaloneDeadline = state.deadlineNanos - clusteredReserveNanos;

        // Large official tasks are collector problems, not a collection of independent
        // root-to-demand problems.  Give the coherent shared-network builder the first half of
        // the catalog budget.  When it covers every terminal, the hundreds of visibility
        // searches below cannot improve the first exact incumbent and are skipped entirely.
        // An incomplete attempt is deliberately bounded, leaving both time and route calls for
        // the proven standalone recovery path.
        BoundedSharedNetworkSeedGenerator.Result sharedSeeds = null;
        boolean completeSharedSeed = false;
        long specialConstraintCount = features.stream()
                .filter(router.rules()::isSpecialConstraintFeature)
                .count();
        boolean denseSpecialLayer = specialConstraintCount >= DENSE_SPECIAL_LAYER_THRESHOLD;
        if (routableDemandIds.size() > SHARED_SEED_GROUP_CAPACITY
                && options.maxRouteCalls > 0 && !state.expired()) {
            Set<String> allDemandIds = new LinkedHashSet<>(routableDemandIds);
            long fastSharedBudget = Math.min(TimeUnit.SECONDS.toNanos(
                            denseSpecialLayer ? 30 : 25),
                    Math.max(1L, options.timeBudgetNanos / 3L));
            long fastSharedDeadline = Math.min(state.deadlineNanos,
                    saturatingAdd(started, fastSharedBudget));
            sharedSeeds = denseSpecialLayer
                    ? sharedSeedGenerator.generateDense(problem, environment, allDemandIds,
                            fastSharedDeadline, Math.max(1, options.maxRouteCalls / 2))
                    : sharedSeedGenerator.generate(problem, environment, allDemandIds,
                            Map.of(), fastSharedDeadline, Math.max(1, options.maxRouteCalls / 2));
            state.routeCalls += sharedSeeds.getRouteCalls();
            completeSharedSeed = sharedSeeds.getCoveredPriorityDemandIds()
                    .containsAll(allDemandIds);
            if (completeSharedSeed) coveredDemands.addAll(allDemandIds);
        }

        // Phase 1 is intentionally cheap: at most one engineering route per pair, proceeding
        // round-robin through nearest roots until every demand has at least one path.
        for (RootDemandPair pair : completeSharedSeed ? List.<RootDemandPair>of() : orderedPairs) {
            if (coveredDemands.contains(pair.demand.getId())) continue;
            if (state.expired(standaloneDeadline)) {
                truncations.add("clustered_seed_budget_reserved");
                stopped = true;
                break;
            }
            if (!canAttemptPair(state, options, truncations)) {
                stopped = true;
                break;
            }
            attemptedPairs.add(pair.key());
            state.pairsAttempted++;
            PairRoutes routes = routes(pair.root, pair.demand,
                    probeDiameter, environment, options, state, true, true, true);
            record(pair, routes, generated, remaining, truncations);
            if (!routes.paths.isEmpty()) coveredDemands.add(pair.demand.getId());
            if (state.routeCalls >= options.maxRouteCalls) {
                truncations.add("route_call_limit");
                stopped = true;
                break;
            }
        }

        // The one-route probe can miss a valid combination of endpoint normals. Before spending
        // time on optional alternatives, retry only uncovered demands with the full bounded set.
        if (!stopped && coveredDemands.size() < problem.getDemands().size()) {
            recovery:
            for (RoutingProblemSnapshot.Demand demand : problem.getDemands()) {
                if (structurallyUnroutableDemands.contains(demand.getId())) continue;
                if (coveredDemands.contains(demand.getId())) continue;
                for (RootDemandPair pair : orderedPairs) {
                    if (!pair.demand.getId().equals(demand.getId())) continue;
                    if (state.expired(standaloneDeadline)) {
                        truncations.add("clustered_seed_budget_reserved");
                        stopped = true;
                        break recovery;
                    }
                    if (state.routeCalls >= options.maxRouteCalls) {
                        truncations.add("route_call_limit");
                        stopped = true;
                        break recovery;
                    }
                    state.recoveryAttempts++;
                    PairRoutes routes = routes(pair.root, pair.demand,
                            probeDiameter, environment, options, state, false, true, true);
                    record(pair, routes, generated, remaining, truncations);
                    if (!routes.paths.isEmpty()) {
                        coveredDemands.add(demand.getId());
                        break;
                    }
                }
            }
        }

        Set<String> normalRootDemands = new LinkedHashSet<>();
        Set<String> usableNormalRoots = new LinkedHashSet<>();
        Map<String, Set<String>> normalDemandsByRoot = new LinkedHashMap<>();
        Set<String> attemptedNormalPairs = new LinkedHashSet<>();
        int usableNormalRootCapacity = 0;
        Map<String, RoutingProblemSnapshot.RootCandidate> rootsById = problem.getRoots().stream()
                .collect(Collectors.toMap(RoutingProblemSnapshot.RootCandidate::getId,
                        root -> root, (left, right) -> left, LinkedHashMap::new));
        // Phase 1 already produces fully checked physical paths. Reuse every path whose first
        // run is also a valid chamber approach instead of asking the visibility router to find
        // the same root-demand connection for a second time. On the official dataset this
        // removes hundreds of redundant searches while keeping the exact root-ray rules.
        for (GeneratedPath path : generated) {
            RoutingProblemSnapshot.RootCandidate root = rootsById.get(path.rootId);
            if (root == null || !validRootApproach(root, path.points, probeDiameter)) continue;
            normalRootDemands.add(path.demandId);
            if (usableNormalRoots.add(path.rootId)) {
                usableNormalRootCapacity = Math.addExact(
                        usableNormalRootCapacity, rootNewBranchCapacity(root));
            }
            normalDemandsByRoot.computeIfAbsent(
                    path.rootId, ignored -> new LinkedHashSet<>()).add(path.demandId);
        }
        long normalSeedDeadline = standaloneDeadline;
        if (!completeSharedSeed && !stopped
                && coveredDemands.size() == problem.getDemands().size()) {
            // First guarantee one physically legal, normal root approach per demand.
            for (RootDemandPair pair : orderedPairs) {
                if (normalRootDemands.contains(pair.demand.getId())) continue;
                if (state.normalSeedAttempts >= MAX_NORMAL_SEED_ATTEMPTS) {
                    truncations.add("normal_seed_attempt_limit");
                    break;
                }
                if (state.expired(normalSeedDeadline)) {
                    truncations.add("normal_seed_budget_reserved");
                    break;
                }
                if (state.expired()) {
                    truncations.add("time_budget");
                    stopped = true;
                    break;
                }
                if (state.routeCalls >= options.maxRouteCalls) {
                    truncations.add("route_call_limit");
                    stopped = true;
                    break;
                }
                attemptedNormalPairs.add(pair.key());
                state.normalSeedAttempts++;
                PairRoutes routes = routes(pair.root, pair.demand,
                        probeDiameter, environment, options, state, true, true, true);
                generated.addAll(routes.paths);
                truncations.addAll(routes.truncationReasons);
                if (!routes.paths.isEmpty()) {
                    normalRootDemands.add(pair.demand.getId());
                    if (usableNormalRoots.add(pair.root.getId())) {
                        usableNormalRootCapacity = Math.addExact(
                                usableNormalRootCapacity, rootNewBranchCapacity(pair.root));
                    }
                    normalDemandsByRoot.computeIfAbsent(
                            pair.root.getId(), ignored -> new LinkedHashSet<>())
                            .add(pair.demand.getId());
                    int standaloneTarget = Math.max(1,
                            problem.getDemands().size() - 2 * SHARED_SEED_GROUP_CAPACITY);
                    if (problem.getDemands().size() > SHARED_SEED_GROUP_CAPACITY
                            && maximumRootAssignment(problem, normalDemandsByRoot,
                                    normalRootDemands).getMatchedCount() >= standaloneTarget) {
                        // A certified shared collector can carry the remaining group through
                        // one root branch. Stop spending visibility searches on standalone
                        // routes that cannot improve the final root-capacity assignment.
                        break;
                    }
                }
            }

            // Then grow only from currently unmatched demands. Adding edges for already matched
            // terminals cannot start an augmenting path and previously wasted most of the bounded
            // catalog budget around the same popular roots.
            int individualDemandTarget = normalRootDemands.size();
            RootAssignment assignment = maximumRootAssignment(
                    problem, normalDemandsByRoot, normalRootDemands);
            assignmentRecovery:
            while (assignment.getMatchedCount() < individualDemandTarget) {
                boolean attempted = false;
                for (RootDemandPair pair : orderedPairs) {
                    if (!assignment.getUnmatchedDemandIds().contains(pair.demand.getId())
                            || !attemptedNormalPairs.add(pair.key())) continue;
                    if (state.normalSeedAttempts >= MAX_NORMAL_SEED_ATTEMPTS) {
                        truncations.add("normal_seed_attempt_limit");
                        break assignmentRecovery;
                    }
                    if (state.expired(normalSeedDeadline)) {
                        truncations.add("normal_seed_budget_reserved");
                        break assignmentRecovery;
                    }
                    if (state.routeCalls >= options.maxRouteCalls) {
                        truncations.add("route_call_limit");
                        stopped = true;
                        break assignmentRecovery;
                    }
                    attempted = true;
                    state.normalSeedAttempts++;
                    PairRoutes routes = routes(pair.root, pair.demand,
                            probeDiameter, environment, options, state,
                            true, true, true);
                    generated.addAll(routes.paths);
                    truncations.addAll(routes.truncationReasons);
                    if (routes.paths.isEmpty()) continue;
                    if (usableNormalRoots.add(pair.root.getId())) {
                        usableNormalRootCapacity = Math.addExact(
                                usableNormalRootCapacity, rootNewBranchCapacity(pair.root));
                    }
                    normalDemandsByRoot.computeIfAbsent(
                            pair.root.getId(), ignored -> new LinkedHashSet<>())
                            .add(pair.demand.getId());
                    assignment = maximumRootAssignment(
                            problem, normalDemandsByRoot, normalRootDemands);
                    break;
                }
                if (!attempted) break;
            }
        }

        int individualDemandTarget = normalRootDemands.size();
        RootAssignment rootAssignment = maximumRootAssignment(
                problem, normalDemandsByRoot, normalRootDemands);
        int assignableNormalDemands = rootAssignment.getMatchedCount();

        Set<String> hardDemands = problem.getDemands().stream()
                .map(RoutingProblemSnapshot.Demand::getId)
                .filter(routableDemandIds::contains)
                .filter(id -> !normalRootDemands.contains(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // A demand may have a legal standalone route and still be impossible to assign because
        // every reachable root has already spent its remaining chamber degree. Include those
        // unmatched terminals in the shared-network repair: one trunk consumes a single root
        // branch while serving several demands, which is exactly the topology the master model
        // needs instead of more standalone paths to already saturated roots.
        hardDemands.addAll(rootAssignment.getUnmatchedDemandIds());
        // Build one coherent collector candidate for every spatial cluster, including terminals
        // that also have a standalone route.  Restricting this stage to unmatched terminals
        // leaves the exact master with a mixture of independent paths whose unavoidable plan
        // crossings cannot form an engineering-valid network.  The bounded seed generator now
        // spends at most one successful root per cluster, so complete spatial coverage fits the
        // same production budget without enumerating redundant alternatives.
        problem.getDemands().stream().map(RoutingProblemSnapshot.Demand::getId)
                .filter(routableDemandIds::contains)
                .forEach(hardDemands::add);
        if (!completeSharedSeed) {
            int remainingRouteCalls = Math.max(0, options.maxRouteCalls - (int) Math.min(
                    Integer.MAX_VALUE, state.routeCalls));
            // A failed cold global seed may have selected a nearby but geometrically poor root.
            // Once standalone coverage has measured actual root-to-demand reachability, retry one
            // coherent collector with that evidence. Pairwise clusters alone can each be valid
            // while their union is infeasible because of crossings or chamber-degree conflicts.
            // Large districts stay clustered to keep the bounded catalog predictable.
            if (hardDemands.size() <= MAX_COHERENT_RETRY_DEMANDS
                    && !denseSpecialLayer) {
                BoundedSharedNetworkSeedGenerator.Result coherent =
                        sharedSeedGenerator.generate(problem, environment, hardDemands,
                                normalDemandsByRoot, state.deadlineNanos, remainingRouteCalls);
                state.routeCalls += coherent.getRouteCalls();
                sharedSeeds = coherent;

                // A whole-district collector is the fastest and best first incumbent, but a
                // single obstacle can make that topology cover only a few terminals. Spend the
                // remaining bounded budget on small coherent clusters instead of publishing that
                // partial collector or exhausting the same global shape again. The clustered
                // generator enforces crossing avoidance and root branch capacity across groups.
                if (!coherent.getCoveredPriorityDemandIds().containsAll(hardDemands)
                        && !state.expired()) {
                    int clusteredRouteCalls = Math.max(0,
                            options.maxRouteCalls - (int) Math.min(
                                    Integer.MAX_VALUE, state.routeCalls));
                    if (clusteredRouteCalls > 0) {
                        BoundedSharedNetworkSeedGenerator.Result clustered =
                                sharedSeedGenerator.generateClustered(
                                        problem, environment, hardDemands,
                                        normalDemandsByRoot, state.deadlineNanos,
                                        clusteredRouteCalls);
                        state.routeCalls += clustered.getRouteCalls();
                        if (clustered.getCoveredPriorityDemandIds().size()
                                > coherent.getCoveredPriorityDemandIds().size()) {
                            sharedSeeds = clustered;
                        }
                    }
                }
            } else {
                sharedSeeds = sharedSeedGenerator.generateClustered(
                        problem, environment, hardDemands, normalDemandsByRoot,
                        state.deadlineNanos, remainingRouteCalls);
                state.routeCalls += sharedSeeds.getRouteCalls();
            }
        }
        Objects.requireNonNull(sharedSeeds, "sharedSeeds");
        Set<String> sharedCoveredDemands = sharedSeeds.getCoveredPriorityDemandIds();
        // The first production catalog needs one coherent, exactly checked incumbent before it
        // spends time on alternatives.  Retaining standalone drafts for terminals already owned
        // by a complete shared collector creates artificial inter-group crossings and can make
        // that incumbent disappear from the noded master.  Additive expansion can restore those
        // alternatives after acceptance without weakening the first-solve topology.
        generated.removeIf(path -> sharedCoveredDemands.contains(path.demandId));
        for (BoundedSharedNetworkSeedGenerator.SeedPath path : sharedSeeds.getPaths()) {
            String signature = pointSignature(path.getPoints());
            String id = "path:" + sha256(List.of(
                    path.getRootId(), path.getDemandId(), path.getPhysicalContext(), signature));
            generated.add(new GeneratedPath(id, path.getRootId(),
                    path.getDemandId(), path.getPhysicalContext(), path.getPoints()));
        }
        coveredDemands.addAll(sharedCoveredDemands);
        normalRootDemands.addAll(sharedCoveredDemands);
        if (sharedSeeds.isDeadlineReached()) truncations.add("shared_seed_deadline");

        for (RoutingProblemSnapshot.Demand demand : problem.getDemands()) {
            if (structurallyUnroutableDemands.contains(demand.getId())) {
                remaining.add("structurally-unroutable-demand:" + demand.getId());
                continue;
            }
            if (!normalRootDemands.contains(demand.getId())) {
                remaining.add("normal-root-demand:" + demand.getId());
            }
        }
        if (usableNormalRootCapacity < individualDemandTarget) {
            remaining.add("normal-root-capacity:" + usableNormalRootCapacity
                    + "/" + individualDemandTarget);
        }
        if (assignableNormalDemands < individualDemandTarget) {
            remaining.add("normal-root-assignment:" + assignableNormalDemands
                    + "/" + individualDemandTarget);
            remaining.add("normal-root-unmatched:"
                    + String.join(",", rootAssignment.getUnmatchedDemandIds()));
        }

        // Phase 2 spends the remaining budget on alternatives only after terminal coverage.
        if (!stopped && coveredDemands.size() == routableDemandIds.size()
                && normalRootDemands.size() == routableDemandIds.size()
                && sharedSeeds.getPaths().isEmpty()) {
            for (RootDemandPair pair : orderedPairs) {
                if (!attemptedPairs.add(pair.key())) continue;
                if (!canAttemptPair(state, options, truncations)) break;
                state.pairsAttempted++;
                PairRoutes routes = routes(pair.root, pair.demand,
                        probeDiameter, environment, options, state, false, true, false);
                record(pair, routes, generated, remaining, truncations);
                if (state.routeCalls >= options.maxRouteCalls
                        && (state.pairsAttempted < totalPairs || !routes.diversityCovered)) {
                    truncations.add("route_call_limit");
                    break;
                }
            }
        }
        if (state.pairsAttempted < totalPairs) {
            remaining.add("root-demand-pairs:" + (totalPairs - state.pairsAttempted));
        }
        // Эти генераторы являются отдельными N03-этапами. Их отсутствие нельзя трактовать
        // как полноту текущего star/overlap-каталога.
        remaining.add(sharedSeeds.getPaths().isEmpty()
                ? "shared-network-seeds" : "shared-network-seed-expansion");
        remaining.add("chamber-configurations");
        if (probe.flowExceedsCatalog) remaining.add("flow-exceeds-pipe-catalog");

        List<GeneratedPath> uniqueGenerated = distinctGenerated(generated);
        Compiled compiled = compile(problem, uniqueGenerated, windowFingerprint, probeDiameter);
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("features", (long) features.size());
        counters.put("pairs_total", totalPairs);
        counters.put("pairs_attempted", state.pairsAttempted);
        counters.put("recovery_attempts", state.recoveryAttempts);
        counters.put("demands_covered", (long) coveredDemands.size());
        counters.put("demands_structurally_unroutable",
                (long) structurallyUnroutableDemands.size());
        counters.put("normal_seed_attempts", state.normalSeedAttempts);
        counters.put("normal_root_demands", (long) normalRootDemands.size());
        counters.put("normal_root_count", (long) usableNormalRoots.size());
        counters.put("normal_root_capacity", (long) usableNormalRootCapacity);
        counters.put("normal_root_assignable_demands", (long) assignableNormalDemands);
        counters.put("shared_seed_root_attempts", sharedSeeds.getRootAttempts());
        counters.put("shared_seed_networks", sharedSeeds.getNetworksExamined());
        counters.put("shared_seed_paths", (long) sharedSeeds.getPaths().size());
        counters.put("route_calls", state.routeCalls);
        counters.put("regularization_calls", state.regularizationCalls);
        counters.put("directed_options", (long) compiled.options.size());
        counters.put("physical_assets", (long) compiled.assets.size());
        counters.put("probe_diameter_mm", (long) probeDiameter);
        counters.put("elapsed_ms", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        Map<String, Boolean> coverage = new LinkedHashMap<>();
        coverage.put("root-demand-pairs", state.pairsAttempted == totalPairs);
        coverage.put("root-demand-diversity", truncations.isEmpty()
                && remaining.stream().noneMatch(value -> value.startsWith("pair-diversity:")
                        || value.startsWith("unrouted-pair:")));
        coverage.put("shared-network-seeds", false);
        coverage.put("chamber-configurations", false);
        CatalogBuildResult build = new CatalogBuildResult(compiled.catalog, counters, coverage,
                distinctSorted(remaining), distinctSorted(truncations));
        return new GeneratedCatalog(build, demandPorts, rootPorts, probeDiameter,
                windowFingerprint, structurallyUnroutableDemands);
    }

    private GeneratedCatalog structurallyIncomplete(RoutingProblemSnapshot problem,
            List<ImportedOfficialFeature> features, String windowFingerprint,
            int probeDiameter, Set<String> structurallyUnroutableDemands, long started) {
        Map<String, String> demandPorts = problem.getDemands().stream()
                .collect(Collectors.toMap(RoutingProblemSnapshot.Demand::getId,
                        demand -> "demand-port:" + demand.getId(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, String> rootPorts = problem.getRoots().stream()
                .collect(Collectors.toMap(RoutingProblemSnapshot.RootCandidate::getId,
                        root -> "root-port:" + root.getId(),
                        (left, right) -> left, LinkedHashMap::new));
        Compiled compiled = compile(problem, List.of(), windowFingerprint, probeDiameter);
        List<String> remaining = structurallyUnroutableDemands.stream()
                .sorted().map(id -> "structurally-unroutable-demand:" + id)
                .collect(Collectors.toCollection(ArrayList::new));
        remaining.add("shared-network-seeds");
        remaining.add("chamber-configurations");
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("features", (long) features.size());
        counters.put("pairs_total", Math.multiplyExact((long) problem.getRoots().size(),
                (long) problem.getDemands().size()));
        counters.put("pairs_attempted", 0L);
        counters.put("recovery_attempts", 0L);
        counters.put("demands_covered", 0L);
        counters.put("demands_structurally_unroutable",
                (long) structurallyUnroutableDemands.size());
        counters.put("normal_seed_attempts", 0L);
        counters.put("normal_root_demands", 0L);
        counters.put("normal_root_count", 0L);
        counters.put("normal_root_capacity", 0L);
        counters.put("normal_root_assignable_demands", 0L);
        counters.put("shared_seed_root_attempts", 0L);
        counters.put("shared_seed_networks", 0L);
        counters.put("shared_seed_paths", 0L);
        counters.put("route_calls", 0L);
        counters.put("regularization_calls", 0L);
        counters.put("directed_options", 0L);
        counters.put("physical_assets", 0L);
        counters.put("probe_diameter_mm", (long) probeDiameter);
        counters.put("elapsed_ms", Math.max(0L,
                (System.nanoTime() - started) / 1_000_000L));
        Map<String, Boolean> coverage = new LinkedHashMap<>();
        coverage.put("root-demand-pairs", false);
        coverage.put("root-demand-diversity", false);
        coverage.put("shared-network-seeds", false);
        coverage.put("chamber-configurations", false);
        CatalogBuildResult build = new CatalogBuildResult(compiled.catalog, counters, coverage,
                distinctSorted(remaining), List.of());
        return new GeneratedCatalog(build, demandPorts, rootPorts, probeDiameter,
                windowFingerprint, structurallyUnroutableDemands);
    }

    private static Set<String> structurallyUnroutableDemands(
            RoutingProblemSnapshot problem, OfficialRoutingEnvironment environment,
            int probeDiameter) {
        if (problem.getRoots().isEmpty()) return Set.of();
        RoutingProblemSnapshot.RootCandidate target = problem.getRoots().get(0);
        Coordinate targetPoint = coordinate(target.getLocation());
        Set<String> result = new LinkedHashSet<>();
        for (RoutingProblemSnapshot.Demand demand : problem.getDemands()) {
            Coordinate terminal = coordinate(demand.getLocation());
            if (!environment.normalEgressCandidates(probeDiameter, terminal, targetPoint,
                            HeatRouteEngineeringRules.ENGINEERING_EGRESS_EXTRA_M,
                            RouteTraversal.REVERSED).isEmpty()) {
                continue;
            }
            if (environment.pointInsideForbiddenClearance(probeDiameter, terminal)) {
                result.add(demand.getId());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static long sharedSeedReserveNanos(Options options, int demandCount) {
        if (demandCount < 2) return 0L;
        // Shared collectors cover up to four terminals with one root branch and arrive already
        // certified as one non-crossing topology. They are both more valuable to the master and
        // cheaper overall than exhausting hundreds of standalone root approaches first.
        return Math.min(TimeUnit.SECONDS.toNanos(45), options.timeBudgetNanos / 2L);
    }

    private static int rootNewBranchCapacity(
            RoutingProblemSnapshot.RootCandidate root) {
        return Math.max(0, 4 - root.getExistingDirections().size());
    }

    private static boolean validRootApproach(
            RoutingProblemSnapshot.RootCandidate root,
            List<CatalogMetricPoint> points, int diameterMm) {
        if (root.getRealization() == null || points.size() < 2
                || rootNewBranchCapacity(root) == 0) return false;
        List<RouteCoordinate> coordinates = points.stream()
                .map(point -> new RouteCoordinate(point.getXM(), point.getYM()))
                .collect(Collectors.toList());
        ExpertChamberGeometryRules.PolylineSummary summary =
                ExpertChamberGeometryRules.summarize(coordinates);
        if (summary == null || summary.hasInvalidBendAngle()
                || summary.hasShortBendSpacing()
                || summary.getFirstBendDistanceM() + 1.0e-9
                        < ExpertChamberGeometryRules.minimumBendDistanceM(diameterMm)) {
            return false;
        }
        for (RoutingProblemSnapshot.DirectionVector direction : root.getExistingDirections()) {
            if (!ExpertChamberGeometryRules.compatibleRays(
                    summary.getFirstDx(), summary.getFirstDy(),
                    direction.getDeltaXMm(), direction.getDeltaYMm())) return false;
        }
        return true;
    }

    /** Maximum deterministic capacitated demand-to-root matching for generated legal approaches. */
    private static RootAssignment maximumRootAssignment(RoutingProblemSnapshot problem,
            Map<String, Set<String>> demandIdsByRoot, Collection<String> eligibleDemandIds) {
        Map<String, RoutingProblemSnapshot.RootCandidate> rootsById = problem.getRoots().stream()
                .collect(Collectors.toMap(RoutingProblemSnapshot.RootCandidate::getId,
                        root -> root, (left, right) -> left, LinkedHashMap::new));
        Map<String, String> demandBySlot = new LinkedHashMap<>();
        Set<String> eligible = Set.copyOf(eligibleDemandIds);
        List<RoutingProblemSnapshot.Demand> demands = problem.getDemands().stream()
                .filter(demand -> eligible.contains(demand.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
        demands.sort(Comparator.comparing(RoutingProblemSnapshot.Demand::getId));
        Set<String> unmatched = new LinkedHashSet<>();
        for (RoutingProblemSnapshot.Demand demand : demands) {
            if (!assignDemandToRootSlot(demand.getId(), demandIdsByRoot, rootsById,
                    demandBySlot, new LinkedHashSet<>())) unmatched.add(demand.getId());
        }
        return new RootAssignment(demands.size() - unmatched.size(), unmatched);
    }

    private static boolean assignDemandToRootSlot(String demandId,
            Map<String, Set<String>> demandIdsByRoot,
            Map<String, RoutingProblemSnapshot.RootCandidate> rootsById,
            Map<String, String> demandBySlot, Set<String> visitedSlots) {
        List<String> rootIds = demandIdsByRoot.entrySet().stream()
                .filter(entry -> entry.getValue().contains(demandId))
                .map(Map.Entry::getKey).sorted().collect(Collectors.toList());
        for (String rootId : rootIds) {
            RoutingProblemSnapshot.RootCandidate root = rootsById.get(rootId);
            if (root == null) continue;
            for (int slot = 0; slot < rootNewBranchCapacity(root); slot++) {
                String slotId = rootId + "\u0000" + slot;
                if (!visitedSlots.add(slotId)) continue;
                String displaced = demandBySlot.get(slotId);
                if (displaced == null || assignDemandToRootSlot(displaced,
                        demandIdsByRoot, rootsById, demandBySlot, visitedSlots)) {
                    demandBySlot.put(slotId, demandId);
                    return true;
                }
            }
        }
        return false;
    }

    private static final class RootAssignment {
        private final int matchedCount;
        private final Set<String> unmatchedDemandIds;

        private RootAssignment(int matchedCount, Collection<String> unmatchedDemandIds) {
            this.matchedCount = matchedCount;
            this.unmatchedDemandIds = Set.copyOf(unmatchedDemandIds);
        }

        private int getMatchedCount() { return matchedCount; }
        private Set<String> getUnmatchedDemandIds() { return unmatchedDemandIds; }
    }

    private static List<GeneratedPath> distinctGenerated(List<GeneratedPath> supplied) {
        Map<String, GeneratedPath> byId = new LinkedHashMap<>();
        for (GeneratedPath path : supplied) byId.putIfAbsent(path.id, path);
        return List.copyOf(byId.values());
    }

    private static boolean canAttemptPair(State state, Options options,
            Set<String> truncations) {
        ensureActive();
        if (state.pairsAttempted >= options.maxPairs) {
            truncations.add("pair_limit");
            return false;
        }
        if (state.expired()) {
            truncations.add("time_budget");
            return false;
        }
        return true;
    }

    private static void record(RootDemandPair pair, PairRoutes routes,
            List<GeneratedPath> generated, List<String> remaining,
            Set<String> truncations) {
        generated.addAll(routes.paths);
        truncations.addAll(routes.truncationReasons);
        String unrouted = "unrouted-pair:" + pair.root.getId()
                + ":" + pair.demand.getId();
        if (routes.paths.isEmpty()) {
            remaining.add(unrouted);
        } else if (!routes.diversityCovered) {
            remaining.remove(unrouted);
            remaining.add("pair-diversity:" + pair.root.getId()
                    + ":" + pair.demand.getId());
        } else {
            remaining.remove(unrouted);
        }
    }

    /**
     * Tries the nearest root for every demand before spending budget on second and later roots.
     * This deterministic round-robin order prevents an early root from consuming the entire
     * catalog deadline while later demands remain completely unrepresented.
     */
    private static List<RootDemandPair> coverageFirstPairs(RoutingProblemSnapshot problem) {
        List<List<RoutingProblemSnapshot.RootCandidate>> rootsByDemand = new ArrayList<>();
        for (RoutingProblemSnapshot.Demand demand : problem.getDemands()) {
            List<RoutingProblemSnapshot.RootCandidate> roots = new ArrayList<>(problem.getRoots());
            roots.sort(Comparator
                    .comparingDouble((RoutingProblemSnapshot.RootCandidate root) ->
                            distanceSquared(root.getLocation(), demand.getLocation()))
                    .thenComparing(RoutingProblemSnapshot.RootCandidate::getId));
            rootsByDemand.add(roots);
        }
        List<RootDemandPair> result = new ArrayList<>();
        for (int rootRank = 0; rootRank < problem.getRoots().size(); rootRank++) {
            for (int demandIndex = 0; demandIndex < problem.getDemands().size(); demandIndex++) {
                result.add(new RootDemandPair(
                        rootsByDemand.get(demandIndex).get(rootRank),
                        problem.getDemands().get(demandIndex)));
            }
        }
        return result;
    }

    private static double distanceSquared(CatalogMetricPoint left, CatalogMetricPoint right) {
        double dx = (double) left.getXMm() - right.getXMm();
        double dy = (double) left.getYMm() - right.getYMm();
        return dx * dx + dy * dy;
    }

    private PairRoutes routes(RoutingProblemSnapshot.RootCandidate root,
            RoutingProblemSnapshot.Demand demand, int diameter,
            OfficialRoutingEnvironment environment, Options options, State state,
            boolean coverageOnly, boolean enforceRootApproach,
            boolean firstPathOnly) {
        Coordinate terminal = coordinate(demand.getLocation());
        Coordinate target = coordinate(root.getLocation());
        Set<String> rootExemptions = root.getRealization() == null
                || root.getRealization().getTargetId() == null
                ? Set.of() : Set.of(root.getRealization().getTargetId());
        List<RoutePath> paths = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        Set<String> truncations = new LinkedHashSet<>();
        boolean fullyEnumerated = !coverageOnly;
        int maxPaths = coverageOnly || firstPathOnly ? 1 : options.maxPathsPerPair;
        int maxEgressCandidates = coverageOnly ? 1 : options.maxEgressCandidates;
        List<RoutePreference> directPreferences = coverageOnly
                ? List.of(RoutePreference.ENGINEERING) : DIRECT_PREFERENCES;
        List<Coordinate> rootApproaches = List.of();
        if (enforceRootApproach && !root.getExistingDirections().isEmpty()) {
            List<Coordinate> existingRays = root.getExistingDirections().stream()
                    .map(direction -> new Coordinate(
                            direction.getDeltaXMm(), direction.getDeltaYMm()))
                    .collect(Collectors.toList());
            double approachLength = Math.max(4.0,
                    ExpertChamberGeometryRules.minimumBendDistanceM(diameter) + 0.01);
            List<Coordinate> approaches = new ArrayList<>(
                    new ChamberApproachCandidates().build(
                            target, existingRays, approachLength, 7.5));
            approaches.sort(Comparator.comparingDouble(terminal::distance));
            if (approaches.isEmpty()) {
                return new PairRoutes(List.of(), false, truncations);
            }
            rootApproaches = List.copyOf(approaches);
        }
        List<OfficialRouteGeometryRules.NormalEgress> allEgresses =
                environment.normalEgressCandidates(diameter, terminal, target,
                        HeatRouteEngineeringRules.ENGINEERING_EGRESS_EXTRA_M,
                        RouteTraversal.REVERSED);
        if (!coverageOnly && allEgresses.size() > maxEgressCandidates) {
            truncations.add("egress_limit:" + root.getId() + ":" + demand.getId());
            fullyEnumerated = false;
        }
        List<OfficialRouteGeometryRules.NormalEgress> egresses = allEgresses.stream()
                .limit(maxEgressCandidates).collect(Collectors.toList());
        if (!rootApproaches.isEmpty()) {
            return rootConstrainedRoutes(root, demand, target, terminal,
                    rootApproaches, egresses, diameter, environment,
                    rootExemptions, options, state, coverageOnly, maxPaths, truncations);
        }
        if (egresses.isEmpty()) {
            for (RoutePreference preference : directPreferences) {
                if (!canRoute(options, state)) {
                    truncations.add(limitReason(state));
                    fullyEnumerated = false;
                    break;
                }
                state.routeCalls++;
                RoutePath candidate = router.find(terminal, target, diameter, environment,
                        rootExemptions, preference, List.of(), RouteTraversal.REVERSED);
                addDistinct(paths, signatures, candidate);
                if (paths.size() >= maxPaths) {
                    if (!coverageOnly) {
                        truncations.add("path_limit:" + root.getId() + ":" + demand.getId());
                    }
                    fullyEnumerated = false;
                    break;
                }
            }
        } else {
            for (int egressIndex = 0; egressIndex < egresses.size(); egressIndex++) {
                OfficialRouteGeometryRules.NormalEgress egress = egresses.get(egressIndex);
                List<RoutePreference> preferences = coverageOnly
                        ? List.of(RoutePreference.ENGINEERING) : egressIndex == 0
                        ? DIRECT_PREFERENCES : List.of(RoutePreference.ENGINEERING);
                for (RoutePreference preference : preferences) {
                    if (!canRoute(options, state)) {
                        truncations.add(limitReason(state));
                        fullyEnumerated = false;
                        break;
                    }
                    state.routeCalls++;
                    RoutePath outside = router.findAfter(egress.start(), egress.exit(), target,
                            diameter, environment, rootExemptions, preference, List.of(),
                            RouteTraversal.REVERSED);
                    if (outside == null) continue;
                    state.regularizationCalls++;
                    RoutePath regularized = router.regularizeAfter(egress.start(),
                            outside.coordinates(), diameter, environment, rootExemptions,
                            List.of(), RouteTraversal.REVERSED);
                    RoutePath complete = router.withCheckedTerminalPrefix(egress,
                            regularized == null ? outside : regularized, diameter, environment,
                            rootExemptions, List.of(), RouteTraversal.REVERSED);
                    addDistinct(paths, signatures, complete);
                    if (paths.size() >= maxPaths) {
                        if (!coverageOnly) {
                            truncations.add("path_limit:" + root.getId() + ":" + demand.getId());
                        }
                        fullyEnumerated = false;
                        break;
                    }
                }
                if (!fullyEnumerated && (paths.size() >= maxPaths
                        || !canRoute(options, state))) break;
            }
        }
        return generatedRoutes(root, demand, paths, fullyEnumerated, truncations);
    }

    private PairRoutes rootConstrainedRoutes(
            RoutingProblemSnapshot.RootCandidate root,
            RoutingProblemSnapshot.Demand demand,
            Coordinate rootCoordinate, Coordinate terminal,
            List<Coordinate> rootApproaches,
            List<OfficialRouteGeometryRules.NormalEgress> egresses,
            int diameter, OfficialRoutingEnvironment environment,
            Set<String> rootExemptions, Options options, State state,
            boolean coverageOnly, int maxPaths, Set<String> truncations) {
        List<RoutePath> paths = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        List<Coordinate> approaches = coverageOnly
                ? rootApproaches.subList(0, 1) : rootApproaches;
        List<RoutePreference> preferences = coverageOnly
                ? List.of(RoutePreference.ENGINEERING) : DIRECT_PREFERENCES;
        int egressCount = Math.max(1, egresses.size());
        outer:
        for (Coordinate approach : approaches) {
            for (int egressIndex = 0; egressIndex < egressCount; egressIndex++) {
                OfficialRouteGeometryRules.NormalEgress egress = egresses.isEmpty()
                        ? null : egresses.get(egressIndex);
                Coordinate outsideEnd = egress == null ? terminal : egress.exit();
                for (RoutePreference preference : preferences) {
                    if (!canRoute(options, state)) {
                        truncations.add(limitReason(state));
                        break outer;
                    }
                    state.routeCalls++;
                    RoutePath rootToOutside = router.findAfter(
                            rootCoordinate, approach, outsideEnd,
                            diameter, environment, rootExemptions, preference,
                            List.of(), RouteTraversal.AS_GIVEN);
                    if (rootToOutside == null) continue;
                    RoutePath terminalToRoot = router.withCheckedTerminalSuffix(
                            rootToOutside.reversed(), rootCoordinate, diameter,
                            environment, rootExemptions, List.of(), RouteTraversal.REVERSED);
                    if (terminalToRoot == null) continue;
                    if (egress != null) {
                        terminalToRoot = router.withCheckedTerminalPrefix(
                                egress, terminalToRoot, diameter, environment,
                                rootExemptions, List.of(), RouteTraversal.REVERSED);
                    }
                    addDistinct(paths, signatures, terminalToRoot);
                    if (paths.size() >= maxPaths) break outer;
                }
            }
        }
        return generatedRoutes(root, demand, paths, false, truncations);
    }

    private static PairRoutes generatedRoutes(
            RoutingProblemSnapshot.RootCandidate root,
            RoutingProblemSnapshot.Demand demand,
            List<RoutePath> paths, boolean fullyEnumerated,
            Set<String> truncations) {
        List<GeneratedPath> result = new ArrayList<>(paths.size());
        for (RoutePath terminalToRoot : paths) {
            RoutePath rootToTerminal = terminalToRoot.reversed();
            List<CatalogMetricPoint> points = metricPoints(rootToTerminal.coordinates());
            if (points.size() < 2) continue;
            String signature = pointSignature(points);
            String id = "path:" + sha256(List.of(root.getId(), demand.getId(), signature));
            result.add(new GeneratedPath(id, root.getId(), demand.getId(),
                    "surface:new:path:" + id, points));
        }
        result.sort(Comparator.comparing(path -> path.id));
        return new PairRoutes(result, fullyEnumerated, truncations);
    }

    private Compiled compile(RoutingProblemSnapshot problem, List<GeneratedPath> generated,
            String windowFingerprint, int probeDiameter) {
        if (generated.isEmpty()) {
            RoutingCatalogSnapshot empty = new RoutingCatalogSnapshot(problem.getSnapshotHash(),
                    problem.getRuleId(), problem.getRuleVersion(),
                    catalogVersion(problem, windowFingerprint, generated), List.of(), List.of());
            return new Compiled(empty, List.of(), List.of());
        }
        List<PhysicalAssetCompiler.CandidatePath> raw = new ArrayList<>(generated.size());
        for (GeneratedPath path : generated) {
            // Every selected seed contributes to one physical candidate network. Noding their
            // intersections makes a crossing an explicit constructible junction and lets the
            // exact master keep a connected forest rather than publish overlapping pipes.
            String networkContext = PhysicalAssetCompiler.JUNCTION_CONTEXT_PREFIX
                    + problem.getSnapshotHash();
            raw.add(new PhysicalAssetCompiler.CandidatePath(path.id, networkContext,
                    CatalogPhysicalAsset.ConstructionMode.NEW_CONSTRUCTION,
                    "root-demand:" + path.rootId + ":" + path.demandId, path.points));
        }
        PhysicalAssetCompiler.Result physical = assetCompiler.compile(raw);
        List<DirectedPathOption> options = new ArrayList<>(generated.size());
        for (GeneratedPath path : generated) {
            DirectedPathOption.Section section = new DirectedPathOption.Section(
                    "checked_path", null, null, 0, path.points.size() - 1);
            String context = "network=" + path.physicalContext
                    + ";root=" + path.rootId + ";demand=" + path.demandId;
            PathAdmissionCertificate certificate = new PathAdmissionCertificate(
                    PathAdmissionCertificate.Level.COMPLETE_PHYSICAL_PATH,
                    PathAdmissionCertificate.Status.VERIFIED_ALLOWED,
                    problem.getRuleId(), problem.getRuleVersion(), problem.getSnapshotHash(),
                    probeDiameter, PathAdmissionCertificate.Direction.FORWARD, context,
                    List.of(section.getSignature()), List.of("official_obstacle_router"),
                    GENERATOR_ID + "-" + GENERATOR_VERSION,
                    "bounded route passed the official obstacle router");
            options.add(new DirectedPathOption(path.id,
                    "root-port:" + path.rootId, "demand-port:" + path.demandId,
                    PathAdmissionCertificate.Direction.FORWARD, context, path.points,
                    physical.path(path.id).getPhysicalAssetIds(), List.of(section),
                    firstBendDistance(path.points), lastBendDistance(path.points),
                    new DirectedPathOption.Provenance(GENERATOR_ID, GENERATOR_VERSION,
                            problem.getSnapshotHash(), windowFingerprint), List.of(certificate)));
        }
        RoutingCatalogSnapshot catalog = new RoutingCatalogSnapshot(problem.getSnapshotHash(),
                problem.getRuleId(), problem.getRuleVersion(),
                catalogVersion(problem, windowFingerprint, generated),
                physical.getPhysicalAssets(), options);
        return new Compiled(catalog, physical.getPhysicalAssets(), options);
    }

    private ProbeDiameter probeDiameter(RoutingProblemSnapshot problem) {
        java.math.BigDecimal total = problem.getDemands().stream()
                .map(RoutingProblemSnapshot.Demand::getFlowTph)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        if (total.signum() <= 0) {
            return new ProbeDiameter(pipes.entries().get(0).getDiameter(), false);
        }
        java.util.Optional<PipeCatalogEntry> matched = pipes.minimumForFlow(total);
        return matched.map(entry -> new ProbeDiameter(entry.getDiameter(), false))
                .orElseGet(() -> new ProbeDiameter(
                        pipes.entries().get(pipes.entries().size() - 1).getDiameter(), true));
    }

    private static boolean canRoute(Options options, State state) {
        return state.routeCalls < options.maxRouteCalls && !state.expired();
    }

    private static String limitReason(State state) {
        return state.expired() ? "time_budget" : "route_call_limit";
    }

    private static void addDistinct(List<RoutePath> paths, Set<String> signatures, RoutePath path) {
        if (path == null) return;
        List<CatalogMetricPoint> points = metricPoints(path.coordinates());
        if (points.size() >= 2 && signatures.add(pointSignature(points))) paths.add(path);
    }

    private static List<CatalogMetricPoint> metricPoints(List<Coordinate> coordinates) {
        List<CatalogMetricPoint> result = new ArrayList<>(coordinates.size());
        for (Coordinate coordinate : coordinates) {
            CatalogMetricPoint point = CatalogMetricPoint.fromMeters(coordinate.x, coordinate.y);
            if (result.isEmpty() || !result.get(result.size() - 1).equals(point)) result.add(point);
        }
        return List.copyOf(result);
    }

    private static long firstBendDistance(List<CatalogMetricPoint> points) {
        return segmentLength(points.get(0), points.get(1));
    }

    private static long lastBendDistance(List<CatalogMetricPoint> points) {
        return segmentLength(points.get(points.size() - 2), points.get(points.size() - 1));
    }

    private static long segmentLength(CatalogMetricPoint left, CatalogMetricPoint right) {
        return Math.round(Math.hypot((double) right.getXMm() - left.getXMm(),
                (double) right.getYMm() - left.getYMm()));
    }

    private static Coordinate coordinate(CatalogMetricPoint point) {
        return new Coordinate(point.getXM(), point.getYM());
    }

    private static String pointSignature(List<CatalogMetricPoint> points) {
        return points.stream().map(point -> point.getXMicrometers()
                        + ":" + point.getYMicrometers())
                .collect(Collectors.joining(";"));
    }

    private static String catalogVersion(RoutingProblemSnapshot problem, String windowFingerprint,
            List<GeneratedPath> paths) {
        List<String> values = new ArrayList<>();
        values.add(problem.getSnapshotHash());
        values.add(windowFingerprint);
        paths.stream().map(path -> path.id).sorted().forEach(values::add);
        return GENERATOR_ID + "-" + GENERATOR_VERSION + "-" + sha256(values).substring(0, 16);
    }

    private static String windowFingerprint(String snapshotHash,
            List<ImportedOfficialFeature> features) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, snapshotHash);
            WKBWriter writer = new WKBWriter(2, true);
            for (ImportedOfficialFeature feature : features) {
                update(digest, feature.getFeatureId());
                update(digest, feature.getObjectType());
                update(digest, Objects.toString(feature.getAttributes(), ""));
                Geometry geometry = feature.getMetricGeometry();
                if (geometry == null) update(digest, "null-geometry");
                else {
                    update(digest, geometry.getSRID());
                    byte[] bytes = writer.write(geometry);
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return "window:" + hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) update(digest, value);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, Object value) {
        byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
            for (byte value : bytes) formatter.format("%02x", value);
            return formatter.toString();
        }
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static List<String> distinctSorted(Collection<String> supplied) {
        return supplied.stream().distinct().sorted().collect(Collectors.toList());
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Bounded catalog generation cancelled");
        }
    }

    public static final class Options {
        private final long timeBudgetNanos;
        private final int maxPairs;
        private final int maxRouteCalls;
        private final int maxPathsPerPair;
        private final int maxEgressCandidates;

        private Options(Duration timeBudget, int maxPairs, int maxRouteCalls,
                int maxPathsPerPair, int maxEgressCandidates) {
            Objects.requireNonNull(timeBudget, "timeBudget");
            if (timeBudget.isZero() || timeBudget.isNegative()) {
                throw new IllegalArgumentException("Positive catalog time budget is required");
            }
            try {
                this.timeBudgetNanos = timeBudget.toNanos();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Catalog time budget is too large", exception);
            }
            if (maxPairs < 1 || maxRouteCalls < 1 || maxPathsPerPair < 1
                    || maxEgressCandidates < 1) {
                throw new IllegalArgumentException("Positive catalog limits are required");
            }
            this.maxPairs = maxPairs;
            this.maxRouteCalls = maxRouteCalls;
            this.maxPathsPerPair = maxPathsPerPair;
            this.maxEgressCandidates = maxEgressCandidates;
        }

        public static Options bounded(Duration timeBudget, int maxPairs, int maxRouteCalls,
                int maxPathsPerPair, int maxEgressCandidates) {
            return new Options(timeBudget, maxPairs, maxRouteCalls,
                    maxPathsPerPair, maxEgressCandidates);
        }

        public static Options fastInitial() {
            return bounded(Duration.ofSeconds(30), 256, 1_024, 3, 8);
        }
    }

    public static final class GeneratedCatalog {
        private final CatalogBuildResult buildResult;
        private final Map<String, String> demandPortById;
        private final Map<String, String> rootPortById;
        private final int probeDiameterMm;
        private final String windowFingerprint;
        private final Set<String> structurallyUnroutableDemandIds;

        private GeneratedCatalog(CatalogBuildResult buildResult,
                Map<String, String> demandPortById, Map<String, String> rootPortById,
                int probeDiameterMm, String windowFingerprint,
                Collection<String> structurallyUnroutableDemandIds) {
            this.buildResult = buildResult;
            this.demandPortById = Collections.unmodifiableMap(new LinkedHashMap<>(demandPortById));
            this.rootPortById = Collections.unmodifiableMap(new LinkedHashMap<>(rootPortById));
            this.probeDiameterMm = probeDiameterMm;
            this.windowFingerprint = windowFingerprint;
            this.structurallyUnroutableDemandIds = Set.copyOf(
                    structurallyUnroutableDemandIds);
        }

        public CatalogBuildResult getBuildResult() { return buildResult; }
        public Map<String, String> getDemandPortById() { return demandPortById; }
        public Map<String, String> getRootPortById() { return rootPortById; }
        public int getProbeDiameterMm() { return probeDiameterMm; }
        public String getWindowFingerprint() { return windowFingerprint; }
        public Set<String> getStructurallyUnroutableDemandIds() {
            return structurallyUnroutableDemandIds;
        }
    }

    private static final class State {
        private final long deadlineNanos;
        private long pairsAttempted;
        private long recoveryAttempts;
        private long normalSeedAttempts;
        private long routeCalls;
        private long regularizationCalls;

        private State(long deadlineNanos) { this.deadlineNanos = deadlineNanos; }
        private boolean expired() { return System.nanoTime() - deadlineNanos >= 0L; }
        private boolean expired(long suppliedDeadlineNanos) {
            return System.nanoTime() - suppliedDeadlineNanos >= 0L;
        }
    }

    private static final class ProbeDiameter {
        private final int diameterMm;
        private final boolean flowExceedsCatalog;

        private ProbeDiameter(int diameterMm, boolean flowExceedsCatalog) {
            this.diameterMm = diameterMm;
            this.flowExceedsCatalog = flowExceedsCatalog;
        }
    }

    private static final class PairRoutes {
        private final List<GeneratedPath> paths;
        private final boolean diversityCovered;
        private final Set<String> truncationReasons;

        private PairRoutes(List<GeneratedPath> paths, boolean diversityCovered,
                Set<String> truncationReasons) {
            this.paths = paths;
            this.diversityCovered = diversityCovered;
            this.truncationReasons = truncationReasons;
        }
    }

    private static final class RootDemandPair {
        private final RoutingProblemSnapshot.RootCandidate root;
        private final RoutingProblemSnapshot.Demand demand;

        private RootDemandPair(RoutingProblemSnapshot.RootCandidate root,
                RoutingProblemSnapshot.Demand demand) {
            this.root = root;
            this.demand = demand;
        }

        private String key() { return root.getId() + "\u0000" + demand.getId(); }
    }

    private static final class GeneratedPath {
        private final String id;
        private final String rootId;
        private final String demandId;
        private final String physicalContext;
        private final List<CatalogMetricPoint> points;

        private GeneratedPath(String id, String rootId, String demandId,
                String physicalContext, List<CatalogMetricPoint> points) {
            this.id = id;
            this.rootId = rootId;
            this.demandId = demandId;
            this.physicalContext = Objects.requireNonNull(
                    physicalContext, "physicalContext");
            this.points = points;
        }
    }

    private static final class Compiled {
        private final RoutingCatalogSnapshot catalog;
        private final List<CatalogPhysicalAsset> assets;
        private final List<DirectedPathOption> options;

        private Compiled(RoutingCatalogSnapshot catalog,
                Collection<CatalogPhysicalAsset> assets,
                Collection<DirectedPathOption> options) {
            this.catalog = catalog;
            this.assets = List.copyOf(assets);
            this.options = List.copyOf(options);
        }
    }
}
