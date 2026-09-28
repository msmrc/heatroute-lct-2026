package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.databind.JsonNode;
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
 * полного legacy planner. Генератор намеренно не заявляет полноту общего дерева/камер.
 */
@Component
public final class BoundedRootDemandCatalogGenerator {
    static final String GENERATOR_ID = "bounded-root-demand";
    static final String GENERATOR_VERSION = "1";
    private static final List<RoutePreference> DIRECT_PREFERENCES = List.of(
            RoutePreference.ENGINEERING, RoutePreference.LEFT, RoutePreference.RIGHT);

    private final OfficialObstacleRouter router;
    private final OfficialPipeCatalog pipes;
    private final PhysicalAssetCompiler assetCompiler = new PhysicalAssetCompiler();

    public BoundedRootDemandCatalogGenerator(OfficialObstacleRouter router, OfficialPipeCatalog pipes) {
        this.router = Objects.requireNonNull(router, "router");
        this.pipes = Objects.requireNonNull(pipes, "pipes");
    }

    public GeneratedCatalog generate(RoutingProblemSnapshot problem,
            Collection<ImportedOfficialFeature> relevantFeatures, Options options) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(relevantFeatures, "relevantFeatures");
        Objects.requireNonNull(options, "options");
        List<ImportedOfficialFeature> features = frozenFeatures(relevantFeatures);
        String windowFingerprint = windowFingerprint(problem.getSnapshotHash(), features);
        long started = System.nanoTime();
        State state = new State(saturatingAdd(started, options.timeBudgetNanos));
        ProbeDiameter probe = probeDiameter(problem);
        int probeDiameter = probe.diameterMm;
        OfficialRoutingEnvironment environment = router.prepare(features);

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
                (long) problem.getDemands().size());
        outer:
        for (RoutingProblemSnapshot.RootCandidate root : problem.getRoots()) {
            for (RoutingProblemSnapshot.Demand demand : problem.getDemands()) {
                ensureActive();
                if (state.pairsAttempted >= options.maxPairs) {
                    truncations.add("pair_limit");
                    break outer;
                }
                if (state.expired()) {
                    truncations.add("time_budget");
                    break outer;
                }
                state.pairsAttempted++;
                PairRoutes routes = routes(root, demand, probeDiameter, environment, options, state);
                generated.addAll(routes.paths);
                truncations.addAll(routes.truncationReasons);
                if (routes.paths.isEmpty()) {
                    remaining.add("unrouted-pair:" + root.getId() + ":" + demand.getId());
                } else if (!routes.diversityCovered) {
                    remaining.add("pair-diversity:" + root.getId() + ":" + demand.getId());
                }
                if (state.routeCalls >= options.maxRouteCalls && state.pairsAttempted < totalPairs) {
                    truncations.add("route_call_limit");
                    break outer;
                }
            }
        }
        if (state.pairsAttempted < totalPairs) {
            remaining.add("root-demand-pairs:" + (totalPairs - state.pairsAttempted));
        }
        // Эти генераторы являются отдельными N03-этапами. Их отсутствие нельзя трактовать
        // как полноту текущего star/overlap-каталога.
        remaining.add("shared-network-seeds");
        remaining.add("chamber-configurations");
        if (probe.flowExceedsCatalog) remaining.add("flow-exceeds-pipe-catalog");

        Compiled compiled = compile(problem, generated, windowFingerprint, probeDiameter);
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("features", (long) features.size());
        counters.put("pairs_total", totalPairs);
        counters.put("pairs_attempted", state.pairsAttempted);
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
                windowFingerprint);
    }

    private PairRoutes routes(RoutingProblemSnapshot.RootCandidate root,
            RoutingProblemSnapshot.Demand demand, int diameter,
            OfficialRoutingEnvironment environment, Options options, State state) {
        Coordinate terminal = coordinate(demand.getLocation());
        Coordinate target = coordinate(root.getLocation());
        List<RoutePath> paths = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        Set<String> truncations = new LinkedHashSet<>();
        boolean fullyEnumerated = true;
        List<OfficialRouteGeometryRules.NormalEgress> allEgresses =
                environment.normalEgressCandidates(diameter, terminal, target,
                        RoutePlannerTuning.stable().getEngineeringEgressExtraM(),
                        RouteTraversal.REVERSED);
        if (allEgresses.size() > options.maxEgressCandidates) {
            truncations.add("egress_limit:" + root.getId() + ":" + demand.getId());
            fullyEnumerated = false;
        }
        List<OfficialRouteGeometryRules.NormalEgress> egresses = allEgresses.stream()
                .limit(options.maxEgressCandidates).collect(Collectors.toList());
        if (egresses.isEmpty()) {
            for (RoutePreference preference : DIRECT_PREFERENCES) {
                if (!canRoute(options, state)) {
                    truncations.add(limitReason(state));
                    fullyEnumerated = false;
                    break;
                }
                state.routeCalls++;
                RoutePath candidate = router.find(terminal, target, diameter, environment,
                        Set.of(), preference, List.of(), RouteTraversal.REVERSED);
                addDistinct(paths, signatures, candidate);
                if (paths.size() >= options.maxPathsPerPair) {
                    truncations.add("path_limit:" + root.getId() + ":" + demand.getId());
                    fullyEnumerated = false;
                    break;
                }
            }
        } else {
            for (int egressIndex = 0; egressIndex < egresses.size(); egressIndex++) {
                OfficialRouteGeometryRules.NormalEgress egress = egresses.get(egressIndex);
                List<RoutePreference> preferences = egressIndex == 0
                        ? DIRECT_PREFERENCES : List.of(RoutePreference.ENGINEERING);
                for (RoutePreference preference : preferences) {
                    if (!canRoute(options, state)) {
                        truncations.add(limitReason(state));
                        fullyEnumerated = false;
                        break;
                    }
                    state.routeCalls++;
                    RoutePath outside = router.findAfter(egress.start(), egress.exit(), target,
                            diameter, environment, Collections.emptySet(), preference, List.of(),
                            RouteTraversal.REVERSED);
                    if (outside == null) continue;
                    state.regularizationCalls++;
                    RoutePath regularized = router.regularizeAfter(egress.start(),
                            outside.coordinates(), diameter, environment, Collections.emptySet(),
                            List.of(), RouteTraversal.REVERSED);
                    RoutePath complete = router.withCheckedTerminalPrefix(egress,
                            regularized == null ? outside : regularized, diameter, environment,
                            Set.of(), List.of(), RouteTraversal.REVERSED);
                    addDistinct(paths, signatures, complete);
                    if (paths.size() >= options.maxPathsPerPair) {
                        truncations.add("path_limit:" + root.getId() + ":" + demand.getId());
                        fullyEnumerated = false;
                        break;
                    }
                }
                if (!fullyEnumerated && (paths.size() >= options.maxPathsPerPair
                        || !canRoute(options, state))) break;
            }
        }
        List<GeneratedPath> result = new ArrayList<>(paths.size());
        for (RoutePath terminalToRoot : paths) {
            RoutePath rootToTerminal = terminalToRoot.reversed();
            List<CatalogMetricPoint> points = metricPoints(rootToTerminal.coordinates());
            if (points.size() < 2) continue;
            String signature = pointSignature(points);
            String id = "path:" + sha256(List.of(root.getId(), demand.getId(), signature));
            result.add(new GeneratedPath(id, root.getId(), demand.getId(), points));
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
            raw.add(new PhysicalAssetCompiler.CandidatePath(path.id, "surface:new",
                    CatalogPhysicalAsset.ConstructionMode.NEW_CONSTRUCTION,
                    "root-demand:" + path.rootId + ":" + path.demandId, path.points));
        }
        PhysicalAssetCompiler.Result physical = assetCompiler.compile(raw);
        List<DirectedPathOption> options = new ArrayList<>(generated.size());
        for (GeneratedPath path : generated) {
            DirectedPathOption.Section section = new DirectedPathOption.Section(
                    "checked_path", null, null, 0, path.points.size() - 1);
            String context = "root=" + path.rootId + ";demand=" + path.demandId;
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
        return points.stream().map(point -> point.getXMm() + ":" + point.getYMm())
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

    private static List<ImportedOfficialFeature> frozenFeatures(
            Collection<ImportedOfficialFeature> supplied) {
        List<ImportedOfficialFeature> result = new ArrayList<>(supplied.size());
        for (ImportedOfficialFeature feature : supplied) {
            Objects.requireNonNull(feature, "relevant feature");
            JsonNode attributes = feature.getAttributes();
            Geometry geometry = feature.getMetricGeometry();
            result.add(new ImportedOfficialFeature(feature.getFeatureId(), feature.getObjectType(),
                    attributes == null ? null : attributes.deepCopy(),
                    geometry == null ? null : geometry.copy()));
        }
        result.sort(Comparator.comparing(ImportedOfficialFeature::getFeatureId));
        return List.copyOf(result);
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

        private GeneratedCatalog(CatalogBuildResult buildResult,
                Map<String, String> demandPortById, Map<String, String> rootPortById,
                int probeDiameterMm, String windowFingerprint) {
            this.buildResult = buildResult;
            this.demandPortById = Collections.unmodifiableMap(new LinkedHashMap<>(demandPortById));
            this.rootPortById = Collections.unmodifiableMap(new LinkedHashMap<>(rootPortById));
            this.probeDiameterMm = probeDiameterMm;
            this.windowFingerprint = windowFingerprint;
        }

        public CatalogBuildResult getBuildResult() { return buildResult; }
        public Map<String, String> getDemandPortById() { return demandPortById; }
        public Map<String, String> getRootPortById() { return rootPortById; }
        public int getProbeDiameterMm() { return probeDiameterMm; }
        public String getWindowFingerprint() { return windowFingerprint; }
    }

    private static final class State {
        private final long deadlineNanos;
        private long pairsAttempted;
        private long routeCalls;
        private long regularizationCalls;

        private State(long deadlineNanos) { this.deadlineNanos = deadlineNanos; }
        private boolean expired() { return System.nanoTime() - deadlineNanos >= 0L; }
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

    private static final class GeneratedPath {
        private final String id;
        private final String rootId;
        private final String demandId;
        private final List<CatalogMetricPoint> points;

        private GeneratedPath(String id, String rootId, String demandId,
                List<CatalogMetricPoint> points) {
            this.id = id;
            this.rootId = rootId;
            this.demandId = demandId;
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
