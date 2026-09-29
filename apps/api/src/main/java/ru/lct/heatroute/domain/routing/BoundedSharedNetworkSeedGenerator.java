package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.catalog.CatalogMetricPoint;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.depth.OfficialSpecialSectionIntervals;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Bounded adapter from the proven orthogonal corridor builder to N03 root-to-demand paths.
 * It deliberately targets demands that lack a legal root approach or a feasible root-capacity
 * assignment and limits both roots and returned trees; exhaustive shared-network enumeration
 * remains a later catalog stage.
 */
final class BoundedSharedNetworkSeedGenerator {
    private static final int GLOBAL_GROUP_SIZE = 64;
    private static final int GLOBAL_ROOT_ATTEMPTS = 2;
    private static final int CLUSTERED_GROUP_SIZE = 2;
    private static final int CLUSTERED_ROOT_ATTEMPTS = 64;
    private static final int CLUSTERED_ROOT_ATTEMPTS_PER_GROUP = 3;
    private static final int DENSE_GLOBAL_ROOT_ATTEMPTS = 1;
    private static final long DENSE_GLOBAL_ROOT_ATTEMPT_BUDGET_NANOS =
            TimeUnit.SECONDS.toNanos(30);
    private static final long CLUSTERED_ROOT_ATTEMPT_BUDGET_NANOS =
            TimeUnit.SECONDS.toNanos(4);
    // Compare a second feasible root when the first one is not already disjoint from previously
    // accepted groups. Further roots belong to additive catalog expansion after the first exact
    // incumbent has been accepted.
    private static final int MAX_SUCCESSFUL_ROOTS_PER_GROUP = 2;
    private static final int MAX_FALLBACK_EGRESSES = 4;
    private static final long MINIMUM_ROOT_ATTEMPT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(4);

    private final OfficialObstacleRouter router;
    private final OrthogonalCorridorNetworkBuilder corridorBuilder;
    private final OfficialSpecialSectionIntervals specialSections;
    private final AxisShiftAlternativeEvaluator axisShiftEvaluator;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final ExpertChamberRouteValidator chamberValidator =
            new ExpertChamberRouteValidator();

    BoundedSharedNetworkSeedGenerator(OfficialObstacleRouter router, OfficialPipeCatalog pipes) {
        this.router = Objects.requireNonNull(router, "router");
        OfficialPipeCatalog requiredPipes = Objects.requireNonNull(pipes, "pipes");
        this.corridorBuilder = new OrthogonalCorridorNetworkBuilder(router, requiredPipes);
        this.specialSections = new OfficialSpecialSectionIntervals(requiredPipes);
        OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
        OfficialEconomics costs = new OfficialEconomics();
        this.axisShiftEvaluator = new AxisShiftAlternativeEvaluator(
                new OfficialRouteValidator(router.rules(), requiredPipes), router,
                new OfficialNetworkSizer(requiredPipes),
                new OfficialDepthPlanner(
                        new OfficialDepthCrossingExtractor(constraints, requiredPipes),
                        new OfficialDepthOptimizer(requiredPipes, costs),
                        new OfficialDepthProfileValidator(requiredPipes)),
                new OfficialVariantEconomicsCalculator(requiredPipes, costs));
    }

    Result generate(RoutingProblemSnapshot problem, OfficialRoutingEnvironment environment,
            Collection<String> priorityDemandIds, long deadlineNanos,
            int maximumFallbackRouteCalls) {
        return generate(problem, environment, priorityDemandIds, Map.of(), deadlineNanos,
                maximumFallbackRouteCalls, GLOBAL_GROUP_SIZE, GLOBAL_ROOT_ATTEMPTS,
                GLOBAL_ROOT_ATTEMPTS, 0L, true);
    }

    Result generate(RoutingProblemSnapshot problem, OfficialRoutingEnvironment environment,
            Collection<String> priorityDemandIds,
            Map<String, ? extends Collection<String>> knownDemandIdsByRoot,
            long deadlineNanos, int maximumFallbackRouteCalls) {
        return generate(problem, environment, priorityDemandIds, knownDemandIdsByRoot,
                deadlineNanos, maximumFallbackRouteCalls,
                GLOBAL_GROUP_SIZE, GLOBAL_ROOT_ATTEMPTS, GLOBAL_ROOT_ATTEMPTS, 0L, false);
    }

    Result generateClustered(RoutingProblemSnapshot problem,
            OfficialRoutingEnvironment environment,
            Collection<String> priorityDemandIds,
            Map<String, ? extends Collection<String>> knownDemandIdsByRoot,
            long deadlineNanos, int maximumFallbackRouteCalls) {
        return generate(problem, environment, priorityDemandIds, knownDemandIdsByRoot,
                deadlineNanos, maximumFallbackRouteCalls,
                CLUSTERED_GROUP_SIZE, CLUSTERED_ROOT_ATTEMPTS,
                CLUSTERED_ROOT_ATTEMPTS_PER_GROUP,
                CLUSTERED_ROOT_ATTEMPT_BUDGET_NANOS, false);
    }

    Result generateDense(RoutingProblemSnapshot problem,
            OfficialRoutingEnvironment environment,
            Collection<String> priorityDemandIds,
            long deadlineNanos, int maximumFallbackRouteCalls) {
        return generate(problem, environment, priorityDemandIds, Map.of(),
                deadlineNanos, maximumFallbackRouteCalls,
                GLOBAL_GROUP_SIZE, DENSE_GLOBAL_ROOT_ATTEMPTS,
                DENSE_GLOBAL_ROOT_ATTEMPTS,
                DENSE_GLOBAL_ROOT_ATTEMPT_BUDGET_NANOS, false);
    }

    private Result generate(RoutingProblemSnapshot problem,
            OfficialRoutingEnvironment environment,
            Collection<String> priorityDemandIds,
            Map<String, ? extends Collection<String>> knownDemandIdsByRoot,
            long deadlineNanos, int maximumFallbackRouteCalls,
            int maximumGroupSize, int maximumRootAttempts,
            int maximumRootAttemptsPerGroup, long rootAttemptBudgetNanos,
            boolean preferExistingRoots) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(priorityDemandIds, "priorityDemandIds");
        Objects.requireNonNull(knownDemandIdsByRoot, "knownDemandIdsByRoot");
        if (priorityDemandIds.isEmpty() || maximumFallbackRouteCalls <= 0
                || expired(deadlineNanos)) {
            return Result.empty(expired(deadlineNanos));
        }

        Set<String> requested = new LinkedHashSet<>(priorityDemandIds);
        List<List<RoutingProblemSnapshot.Demand>> groups = demandGroups(
                problem, requested, maximumGroupSize, maximumRootAttempts);
        if (groups.isEmpty()) return Result.empty(false);
        SearchState state = new SearchState(deadlineNanos, maximumFallbackRouteCalls);
        List<SeedPath> paths = new ArrayList<>();
        Set<String> pathKeys = new LinkedHashSet<>();
        List<LineString> acceptedNetworkLines = new ArrayList<>();
        int networksExamined = 0;

        for (List<RoutingProblemSnapshot.Demand> group : groups) {
            int successfulRoots = 0;
            int groupRootAttempts = 0;
            NetworkChoice best = null;
            Map<String, RoutingProblemSnapshot.Demand> demandsById = group.stream()
                    .collect(Collectors.toMap(RoutingProblemSnapshot.Demand::getId,
                            demand -> demand, (left, right) -> left, LinkedHashMap::new));
            for (RoutingProblemSnapshot.RootCandidate root : eligibleRoots(
                    problem, group, knownDemandIdsByRoot, preferExistingRoots)) {
                if (state.rootAttempts >= maximumRootAttempts
                        || groupRootAttempts >= maximumRootAttemptsPerGroup
                        || !state.canStartRoot()) break;
                RouteNode rootNode = rootNode(root);
                if (rootNode == null) continue;
                int rootCapacity = 4 - rootNode.getBaseIncidentSections();
                if (rootCapacity < 1) continue;
                state.rootAttempts++;
                groupRootAttempts++;
                SearchState rootState = state.forRootAttempt(rootAttemptBudgetNanos);
                List<OrthogonalCorridorNetworkBuilder.Terminal> terminals = group.stream()
                        .map(demand -> new OrthogonalCorridorNetworkBuilder.Terminal(
                                demand.getId(), demand.getConnectionPointId(),
                                coordinate(demand.getLocation()), demand.getFlowTph()))
                        .collect(Collectors.toList());
                List<Geometry> footprints = footprints(environment, root, group);
                List<LineString> fixedAvoidance = List.copyOf(acceptedNetworkLines);
                List<OrthogonalCorridorNetworkBuilder.Network> networks;
                try {
                    networks = corridorBuilder.buildWithTerminalFrame(
                            terminals, rootNode, rootCapacity, footprints, environment,
                            (demandId, junction, diameter, avoidance) -> routeTerminal(
                                    demandsById.get(demandId), junction, diameter,
                                    environment, combined(fixedAvoidance, avoidance), rootState));
                } finally {
                    state.absorb(rootState);
                }
                List<OrthogonalCorridorNetworkBuilder.Network> ordered = networks.stream()
                        .filter(network -> engineeringReady(network, environment))
                        .collect(Collectors.toCollection(ArrayList::new));
                ordered.sort(Comparator
                        .comparingInt((OrthogonalCorridorNetworkBuilder.Network network) ->
                                crossingCount(network, acceptedNetworkLines))
                        .thenComparingDouble(BoundedSharedNetworkSeedGenerator::networkLength)
                        .thenComparing(BoundedSharedNetworkSeedGenerator::networkGeometrySignature));
                Map<String, OrthogonalCorridorNetworkBuilder.Network> distinctNetworks =
                        new LinkedHashMap<>();
                for (OrthogonalCorridorNetworkBuilder.Network network : ordered) {
                    distinctNetworks.putIfAbsent(networkGeometrySignature(network), network);
                }
                if (distinctNetworks.isEmpty()) continue;
                OrthogonalCorridorNetworkBuilder.Network candidate =
                        distinctNetworks.values().iterator().next();
                networksExamined++;
                NetworkChoice choice = new NetworkChoice(root, candidate,
                        crossingCount(candidate, acceptedNetworkLines));
                if (best == null || choice.betterThan(best)) best = choice;
                successfulRoots++;
                if (best.crossingCount == 0
                        || successfulRoots >= MAX_SUCCESSFUL_ROOTS_PER_GROUP) break;
            }
            if (best != null) {
                best = new NetworkChoice(best.root,
                        refineAxisShifts(best.network, problem, environment),
                        best.crossingCount);
                String physicalContext = "surface:new:shared:" + best.root.getId()
                        + ":" + networkGeometrySignature(best.network);
                for (RoutingProblemSnapshot.Demand demand : group) {
                    SeedPath path = path(best.network, best.root, demand, physicalContext);
                    if (path != null && pathKeys.add(path.key())) paths.add(path);
                }
                best.network.edges().stream().map(this::line).forEach(acceptedNetworkLines::add);
            }
            if (covered(paths, requested)) break;
            if (state.rootAttempts >= maximumRootAttempts || !state.canStartRoot()) break;
        }
        paths.sort(Comparator.comparing(SeedPath::getRootId)
                .thenComparing(SeedPath::getDemandId)
                .thenComparing(SeedPath::pointSignature));
        return new Result(paths, state.rootAttempts, networksExamined,
                state.routeCalls, coveredIds(paths, requested), state.expired());
    }

    /** Repair drafts stay outside N03; only configurations admissible by the frozen rules seed it. */
    private boolean engineeringReady(OrthogonalCorridorNetworkBuilder.Network network,
            OfficialRoutingEnvironment environment) {
        if (!chamberValidator.validate(network.nodes(), network.edges(),
                        environment::existingDirections).isEmpty()
                || !ExpertRouteBendRules.validate(network.nodes(), network.edges()).isEmpty()) {
            return false;
        }
        Envelope bounds = new Envelope();
        network.edges().stream().flatMap(edge -> edge.getCoordinates().stream())
                .forEach(point -> bounds.expandToInclude(point.getXM().doubleValue(),
                        point.getYM().doubleValue()));
        if (bounds.isNull()) return false;
        List<ImportedOfficialFeature> relevant = environment.featuresInWindow(
                new Coordinate(bounds.getMinX(), bounds.getMinY()),
                new Coordinate(bounds.getMaxX(), bounds.getMaxY()));
        Map<String, Set<String>> tieIns = network.nodes().stream()
                .filter(RouteNode::isRoot)
                .filter(node -> node.getTargetId() != null)
                .collect(Collectors.toMap(RouteNode::getId,
                        node -> Set.of(node.getTargetId()),
                        (left, right) -> left, LinkedHashMap::new));
        try {
            specialSections.extractUtilities(network.edges(), relevant, tieIns);
            return true;
        } catch (IllegalArgumentException invalidSpecialCrossing) {
            return false;
        }
    }

    private OrthogonalCorridorNetworkBuilder.Network refineAxisShifts(
            OrthogonalCorridorNetworkBuilder.Network original,
            RoutingProblemSnapshot problem, OfficialRoutingEnvironment environment) {
        OrthogonalCorridorNetworkBuilder.Network current = original;
        int maximumMoves = new EngineeringRouteEvaluator()
                .evaluate(original.edges()).bendCount();
        for (int move = 0; move < maximumMoves; move++) {
            OrthogonalCorridorNetworkBuilder.Network at = current;
            List<ImportedOfficialFeature> features = featuresFor(at, environment);
            RouteVariant improved = new RouteAxisShiftControl().firstImprovement(
                    at.nodes(), at.edges(), replacement -> axisShiftEvaluator.assessPrepared(
                            replacement, "nextgen-seed", "engineering", at.connections(),
                            features, problem.getParameters(), environment));
            if (improved == null) break;
            current = new OrthogonalCorridorNetworkBuilder.Network(
                    improved.getNodes(), improved.getEdges(), improved.getConnections());
        }
        return current;
    }

    private static List<ImportedOfficialFeature> featuresFor(
            OrthogonalCorridorNetworkBuilder.Network network,
            OfficialRoutingEnvironment environment) {
        Envelope bounds = new Envelope();
        network.edges().stream().flatMap(edge -> edge.getCoordinates().stream())
                .forEach(point -> bounds.expandToInclude(point.getXM().doubleValue(),
                        point.getYM().doubleValue()));
        if (bounds.isNull()) return List.of();
        return environment.featuresInWindow(
                new Coordinate(bounds.getMinX(), bounds.getMinY()),
                new Coordinate(bounds.getMaxX(), bounds.getMaxY()));
    }

    private RoutePath routeTerminal(RoutingProblemSnapshot.Demand demand,
            Coordinate junction, int diameter, OfficialRoutingEnvironment environment,
            List<LineString> avoidance, SearchState state) {
        if (demand == null || state.expired()) return null;
        Coordinate terminal = coordinate(demand.getLocation());
        List<OfficialRouteGeometryRules.NormalEgress> egresses =
                environment.normalEgressCandidates(diameter, terminal, junction,
                        RoutePlannerTuning.stable().getEngineeringEgressExtraM(),
                        RouteTraversal.REVERSED);
        for (int index = 0; index < Math.min(MAX_FALLBACK_EGRESSES, egresses.size()); index++) {
            if (!state.canRoute()) return null;
            state.routeCalls++;
            OfficialRouteGeometryRules.NormalEgress egress = egresses.get(index);
            RoutePath outside = router.findAfter(egress.start(), egress.exit(), junction,
                    diameter, environment, Set.of(), RoutePreference.ENGINEERING,
                    avoidance, RouteTraversal.REVERSED);
            if (outside == null) continue;
            RoutePath regularized = router.regularizeAfter(egress.start(),
                    outside.coordinates(), diameter, environment, Set.of(),
                    avoidance, RouteTraversal.REVERSED);
            RoutePath complete = router.withCheckedTerminalPrefix(egress,
                    regularized == null ? outside : regularized, diameter,
                    environment, Set.of(), avoidance, RouteTraversal.REVERSED);
            if (complete != null) return complete;
        }
        if (!egresses.isEmpty() || !state.canRoute()) return null;
        state.routeCalls++;
        return router.find(terminal, junction, diameter, environment, Set.of(),
                RoutePreference.ENGINEERING, avoidance, RouteTraversal.REVERSED);
    }

    private static List<List<RoutingProblemSnapshot.Demand>> demandGroups(
            RoutingProblemSnapshot problem, Set<String> priorityIds,
            int maximumGroupSize, int maximumRootAttempts) {
        if (maximumGroupSize < 2 || maximumRootAttempts < 1) {
            throw new IllegalArgumentException("Invalid shared-network search bounds");
        }
        List<RoutingProblemSnapshot.Demand> eligible = problem.getDemands().stream()
                .filter(demand -> demand.getFlowTph().signum() > 0)
                .filter(demand -> demand.getConnectionPointId() != null)
                .collect(Collectors.toList());
        List<RoutingProblemSnapshot.Demand> priority = eligible.stream()
                .filter(demand -> priorityIds.contains(demand.getId()))
                .sorted(Comparator
                        .comparingLong((RoutingProblemSnapshot.Demand demand) ->
                                demand.getLocation().getXMm())
                        .thenComparingLong(demand -> demand.getLocation().getYMm())
                        .thenComparing(RoutingProblemSnapshot.Demand::getId))
                .limit((long) maximumGroupSize * maximumRootAttempts)
                .collect(Collectors.toCollection(ArrayList::new));
        if (priority.isEmpty()) return List.of();
        List<List<RoutingProblemSnapshot.Demand>> result = new ArrayList<>();
        for (int offset = 0; offset < priority.size(); offset += maximumGroupSize) {
            List<RoutingProblemSnapshot.Demand> group = new ArrayList<>(priority.subList(
                    offset, Math.min(priority.size(), offset + maximumGroupSize)));
            double centerX = group.stream().mapToDouble(demand -> demand.getLocation().getXM())
                    .average().orElseThrow();
            double centerY = group.stream().mapToDouble(demand -> demand.getLocation().getYM())
                    .average().orElseThrow();
            if (group.size() < 2) {
                Set<String> groupIds = group.stream().map(RoutingProblemSnapshot.Demand::getId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                eligible.stream().filter(demand -> !groupIds.contains(demand.getId()))
                        .sorted(Comparator.comparingDouble((RoutingProblemSnapshot.Demand demand) ->
                                        squaredDistance(demand.getLocation().getXM(),
                                                demand.getLocation().getYM(), centerX, centerY))
                                .thenComparing(RoutingProblemSnapshot.Demand::getId))
                        .limit(2 - group.size()).forEach(group::add);
            }
            if (group.size() >= 2) result.add(List.copyOf(group));
        }
        return List.copyOf(result);
    }

    static List<RoutingProblemSnapshot.RootCandidate> eligibleRoots(
            RoutingProblemSnapshot problem, List<RoutingProblemSnapshot.Demand> group,
            Map<String, ? extends Collection<String>> knownDemandIdsByRoot,
            boolean preferExistingRoots) {
        Set<String> groupDemandIds = group.stream()
                .map(RoutingProblemSnapshot.Demand::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<RoutingProblemSnapshot.RootCandidate> roots = problem.getRoots().stream()
                .filter(root -> root.getRealization() != null)
                .filter(root -> root.getRealization().getBaseIncidentSections() < 4)
                .collect(Collectors.toCollection(ArrayList::new));
        Comparator<RoutingProblemSnapshot.RootCandidate> existingRank = Comparator
                .comparingInt(root -> "existing_chamber_tie_in".equals(
                        root.getRealization().getNodeType()) ? 0 : 1);
        Comparator<RoutingProblemSnapshot.RootCandidate> knownCoverage = Comparator
                .comparingInt(root -> -knownCoverage(
                        root, groupDemandIds, knownDemandIdsByRoot));
        Comparator<RoutingProblemSnapshot.RootCandidate> distance = Comparator
                .comparingDouble(root -> group.stream()
                        .mapToDouble(demand -> squaredDistance(
                                root.getLocation().getXM(), root.getLocation().getYM(),
                                demand.getLocation().getXM(), demand.getLocation().getYM()))
                        .sum());
        Comparator<RoutingProblemSnapshot.RootCandidate> stableOrder = Comparator
                .comparingInt((RoutingProblemSnapshot.RootCandidate root) ->
                        root.getRealization().getBaseIncidentSections())
                .thenComparing(distance)
                .thenComparing(RoutingProblemSnapshot.RootCandidate::getId);
        Comparator<RoutingProblemSnapshot.RootCandidate> existingFirstOrder = existingRank
                .thenComparing(knownCoverage)
                .thenComparing(stableOrder);
        Comparator<RoutingProblemSnapshot.RootCandidate> measuredOrder = knownCoverage
                .thenComparing(distance)
                .thenComparing(existingRank)
                .thenComparing(stableOrder);
        roots.sort(preferExistingRoots ? existingFirstOrder : measuredOrder);
        if (!preferExistingRoots && !knownDemandIdsByRoot.isEmpty()) {
            RoutingProblemSnapshot.RootCandidate existingAnchor = roots.stream()
                    .filter(root -> "existing_chamber_tie_in".equals(
                            root.getRealization().getNodeType()))
                    .min(existingFirstOrder)
                    .orElse(null);
            if (existingAnchor != null && roots.get(0) != existingAnchor) {
                roots.remove(existingAnchor);
                roots.add(0, existingAnchor);
            }
        }
        return roots;
    }

    private static int knownCoverage(RoutingProblemSnapshot.RootCandidate root,
            Set<String> groupDemandIds,
            Map<String, ? extends Collection<String>> knownDemandIdsByRoot) {
        Collection<String> reachable = knownDemandIdsByRoot.get(root.getId());
        if (reachable == null || reachable.isEmpty()) return 0;
        return (int) groupDemandIds.stream().filter(reachable::contains).count();
    }

    private static RouteNode rootNode(RoutingProblemSnapshot.RootCandidate root) {
        RoutingProblemSnapshot.RootRealization realization = root.getRealization();
        if (realization == null) return null;
        return new RouteNode(root.getId(), realization.getNodeType(),
                new RouteCoordinate(root.getLocation().getXM(), root.getLocation().getYM()),
                realization.isChamber(), true, realization.getBaseIncidentSections(),
                realization.getTargetId(), realization.getExistingIncidentDiameter());
    }

    private static List<Geometry> footprints(OfficialRoutingEnvironment environment,
            RoutingProblemSnapshot.RootCandidate root,
            List<RoutingProblemSnapshot.Demand> demands) {
        Envelope bounds = new Envelope(coordinate(root.getLocation()));
        demands.forEach(demand -> bounds.expandToInclude(coordinate(demand.getLocation())));
        return environment.buildingFootprints(
                new Coordinate(bounds.getMinX(), bounds.getMinY()),
                new Coordinate(bounds.getMaxX(), bounds.getMaxY()));
    }

    private static SeedPath path(OrthogonalCorridorNetworkBuilder.Network network,
            RoutingProblemSnapshot.RootCandidate root,
            RoutingProblemSnapshot.Demand demand, String physicalContext) {
        Map<String, RouteEdge> incoming = new HashMap<>();
        for (RouteEdge edge : network.edges()) {
            if (incoming.putIfAbsent(edge.getDownstreamNodeId(), edge) != null) return null;
        }
        List<RouteEdge> reversed = new ArrayList<>();
        String at = "demand:" + demand.getId();
        Set<String> visited = new LinkedHashSet<>();
        while (!at.equals(root.getId())) {
            if (!visited.add(at)) return null;
            RouteEdge edge = incoming.get(at);
            if (edge == null) return null;
            reversed.add(edge);
            at = edge.getUpstreamNodeId();
        }
        java.util.Collections.reverse(reversed);
        List<CatalogMetricPoint> points = new ArrayList<>();
        for (RouteEdge edge : reversed) {
            for (RouteCoordinate coordinate : edge.getCoordinates()) {
                CatalogMetricPoint point = CatalogMetricPoint.fromMeters(
                        coordinate.getXM().doubleValue(), coordinate.getYM().doubleValue());
                if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) points.add(point);
            }
        }
        if (points.size() < 2 || !points.get(0).equals(root.getLocation())
                || !points.get(points.size() - 1).equals(demand.getLocation())) return null;
        return new SeedPath(root.getId(), demand.getId(), physicalContext, points);
    }

    private static boolean covered(List<SeedPath> paths, Set<String> requested) {
        return coveredIds(paths, requested).containsAll(requested);
    }

    private static Set<String> coveredIds(List<SeedPath> paths, Set<String> requested) {
        return paths.stream().map(SeedPath::getDemandId).filter(requested::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static double networkLength(OrthogonalCorridorNetworkBuilder.Network network) {
        return network.edges().stream().mapToDouble(edge -> edge.getLengthM().doubleValue()).sum();
    }

    private int crossingCount(OrthogonalCorridorNetworkBuilder.Network network,
            List<LineString> accepted) {
        if (accepted.isEmpty()) return 0;
        int result = 0;
        for (RouteEdge edge : network.edges()) {
            LineString candidate = line(edge);
            for (LineString fixed : accepted) {
                if (candidate.getEnvelopeInternal().intersects(fixed.getEnvelopeInternal())
                        && candidate.intersects(fixed)
                        && incompatibleIntersection(candidate, fixed)) result++;
            }
        }
        return result;
    }

    private boolean incompatibleIntersection(LineString left, LineString right) {
        Coordinate[] leftPoints = left.getCoordinates();
        Coordinate[] rightPoints = right.getCoordinates();
        for (int leftIndex = 1; leftIndex < leftPoints.length; leftIndex++) {
            Coordinate leftFrom = leftPoints[leftIndex - 1], leftTo = leftPoints[leftIndex];
            LineString leftSegment = geometryFactory.createLineString(
                    new Coordinate[] {new Coordinate(leftFrom), new Coordinate(leftTo)});
            for (int rightIndex = 1; rightIndex < rightPoints.length; rightIndex++) {
                Coordinate rightFrom = rightPoints[rightIndex - 1], rightTo = rightPoints[rightIndex];
                LineString rightSegment = geometryFactory.createLineString(
                        new Coordinate[] {new Coordinate(rightFrom), new Coordinate(rightTo)});
                if (!leftSegment.getEnvelopeInternal().intersects(
                        rightSegment.getEnvelopeInternal())
                        || !leftSegment.intersects(rightSegment)) continue;
                double leftDx = leftTo.x - leftFrom.x, leftDy = leftTo.y - leftFrom.y;
                double rightDx = rightTo.x - rightFrom.x, rightDy = rightTo.y - rightFrom.y;
                double denominator = Math.hypot(leftDx, leftDy) * Math.hypot(rightDx, rightDy);
                if (!(denominator > 0.0)) return true;
                double cosine = Math.abs(leftDx * rightDx + leftDy * rightDy) / denominator;
                boolean perpendicular = cosine <= Math.sin(Math.toRadians(0.1)) + 1e-12;
                boolean collinear = cosine >= Math.cos(Math.toRadians(0.1)) - 1e-12;
                if (!perpendicular && !collinear) return true;
            }
        }
        return false;
    }

    private LineString line(RouteEdge edge) {
        return geometryFactory.createLineString(edge.getCoordinates().stream()
                .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
    }

    private static List<LineString> combined(
            List<LineString> fixed, List<LineString> local) {
        if (fixed.isEmpty()) return local;
        if (local.isEmpty()) return fixed;
        List<LineString> result = new ArrayList<>(fixed.size() + local.size());
        result.addAll(fixed);
        result.addAll(local);
        return List.copyOf(result);
    }

    private static String networkGeometrySignature(
            OrthogonalCorridorNetworkBuilder.Network network) {
        return network.edges().stream()
                .map(edge -> edge.getCoordinates().stream()
                        .map(point -> point.getXM() + ":" + point.getYM())
                        .collect(Collectors.joining(";")))
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private static Coordinate coordinate(CatalogMetricPoint point) {
        return new Coordinate(point.getXM(), point.getYM());
    }

    private static double squaredDistance(
            double leftX, double leftY, double rightX, double rightY) {
        double dx = leftX - rightX, dy = leftY - rightY;
        return dx * dx + dy * dy;
    }

    private static boolean expired(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos >= 0L;
    }

    static final class SeedPath {
        private final String rootId;
        private final String demandId;
        private final String physicalContext;
        private final List<CatalogMetricPoint> points;

        private SeedPath(String rootId, String demandId, String physicalContext,
                List<CatalogMetricPoint> points) {
            this.rootId = rootId;
            this.demandId = demandId;
            this.physicalContext = Objects.requireNonNull(
                    physicalContext, "physicalContext");
            this.points = List.copyOf(points);
        }

        String getRootId() { return rootId; }
        String getDemandId() { return demandId; }
        String getPhysicalContext() { return physicalContext; }
        List<CatalogMetricPoint> getPoints() { return points; }
        private String pointSignature() {
            return points.stream().map(point -> point.getXMicrometers()
                            + ":" + point.getYMicrometers())
                    .collect(Collectors.joining(";"));
        }
        private String key() { return rootId + "\u0000" + demandId + "\u0000"
                + physicalContext + "\u0000" + pointSignature(); }
    }

    static final class Result {
        private final List<SeedPath> paths;
        private final long rootAttempts;
        private final long networksExamined;
        private final long routeCalls;
        private final Set<String> coveredPriorityDemandIds;
        private final boolean deadlineReached;

        private Result(List<SeedPath> paths, long rootAttempts, long networksExamined,
                long routeCalls, Collection<String> coveredPriorityDemandIds,
                boolean deadlineReached) {
            this.paths = List.copyOf(paths);
            this.rootAttempts = rootAttempts;
            this.networksExamined = networksExamined;
            this.routeCalls = routeCalls;
            this.coveredPriorityDemandIds = Set.copyOf(coveredPriorityDemandIds);
            this.deadlineReached = deadlineReached;
        }

        private static Result empty(boolean deadlineReached) {
            return new Result(List.of(), 0, 0, 0, Set.of(), deadlineReached);
        }

        List<SeedPath> getPaths() { return paths; }
        long getRootAttempts() { return rootAttempts; }
        long getNetworksExamined() { return networksExamined; }
        long getRouteCalls() { return routeCalls; }
        Set<String> getCoveredPriorityDemandIds() { return coveredPriorityDemandIds; }
        boolean isDeadlineReached() { return deadlineReached; }
    }

    private static final class SearchState {
        private final long deadlineNanos;
        private final int maximumRouteCalls;
        private int rootAttempts;
        private int routeCalls;

        private SearchState(long deadlineNanos, int maximumRouteCalls) {
            this.deadlineNanos = deadlineNanos;
            this.maximumRouteCalls = maximumRouteCalls;
        }

        private SearchState forRootAttempt(long attemptBudgetNanos) {
            if (attemptBudgetNanos <= 0L) return this;
            long attemptDeadline = Math.min(deadlineNanos,
                    saturatingAdd(System.nanoTime(), attemptBudgetNanos));
            return new SearchState(attemptDeadline,
                    Math.max(0, maximumRouteCalls - routeCalls));
        }

        private void absorb(SearchState child) {
            if (child != this) routeCalls += child.routeCalls;
        }

        private boolean expired() { return BoundedSharedNetworkSeedGenerator.expired(deadlineNanos); }
        private boolean canStartRoot() {
            return deadlineNanos - System.nanoTime() >= MINIMUM_ROOT_ATTEMPT_BUDGET_NANOS;
        }
        private boolean canRoute() { return routeCalls < maximumRouteCalls && !expired(); }
    }

    private static long saturatingAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) return Long.MAX_VALUE;
        return result;
    }

    private static final class NetworkChoice {
        private final RoutingProblemSnapshot.RootCandidate root;
        private final OrthogonalCorridorNetworkBuilder.Network network;
        private final int crossingCount;

        private NetworkChoice(RoutingProblemSnapshot.RootCandidate root,
                OrthogonalCorridorNetworkBuilder.Network network, int crossingCount) {
            this.root = root;
            this.network = network;
            this.crossingCount = crossingCount;
        }

        private boolean betterThan(NetworkChoice other) {
            int crossings = Integer.compare(crossingCount, other.crossingCount);
            if (crossings != 0) return crossings < 0;
            int length = Double.compare(networkLength(network), networkLength(other.network));
            if (length != 0) return length < 0;
            return networkGeometrySignature(network)
                    .compareTo(networkGeometrySignature(other.network)) < 0;
        }
    }
}
