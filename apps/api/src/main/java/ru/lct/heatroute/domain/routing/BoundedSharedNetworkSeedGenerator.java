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
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.catalog.CatalogMetricPoint;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/**
 * Bounded adapter from the proven orthogonal corridor builder to N03 root-to-demand paths.
 * It deliberately targets only demands that lack a legal root approach and limits both roots
 * and returned trees; exhaustive shared-network enumeration remains a later catalog stage.
 */
final class BoundedSharedNetworkSeedGenerator {
    private static final int MAX_GROUP_SIZE = 4;
    private static final int MAX_ROOT_ATTEMPTS = 5;
    private static final int MAX_NETWORKS_PER_ROOT = 3;
    private static final int MAX_FALLBACK_EGRESSES = 4;
    private static final long MINIMUM_ROOT_ATTEMPT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(4);

    private final OfficialObstacleRouter router;
    private final OrthogonalCorridorNetworkBuilder corridorBuilder;

    BoundedSharedNetworkSeedGenerator(OfficialObstacleRouter router, OfficialPipeCatalog pipes) {
        this.router = Objects.requireNonNull(router, "router");
        this.corridorBuilder = new OrthogonalCorridorNetworkBuilder(
                router, Objects.requireNonNull(pipes, "pipes"));
    }

    Result generate(RoutingProblemSnapshot problem, OfficialRoutingEnvironment environment,
            Collection<String> priorityDemandIds, long deadlineNanos,
            int maximumFallbackRouteCalls) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(priorityDemandIds, "priorityDemandIds");
        if (priorityDemandIds.isEmpty() || maximumFallbackRouteCalls <= 0
                || expired(deadlineNanos)) {
            return Result.empty(expired(deadlineNanos));
        }

        Set<String> requested = new LinkedHashSet<>(priorityDemandIds);
        List<List<RoutingProblemSnapshot.Demand>> groups = demandGroups(problem, requested);
        if (groups.isEmpty()) return Result.empty(false);
        SearchState state = new SearchState(deadlineNanos, maximumFallbackRouteCalls);
        List<SeedPath> paths = new ArrayList<>();
        Set<String> pathKeys = new LinkedHashSet<>();
        int networksExamined = 0;

        groups:
        for (List<RoutingProblemSnapshot.Demand> group : groups) {
            Set<String> requestedInGroup = group.stream()
                    .map(RoutingProblemSnapshot.Demand::getId).filter(requested::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, RoutingProblemSnapshot.Demand> demandsById = group.stream()
                    .collect(Collectors.toMap(RoutingProblemSnapshot.Demand::getId,
                            demand -> demand, (left, right) -> left, LinkedHashMap::new));
            for (RoutingProblemSnapshot.RootCandidate root : eligibleRoots(problem, group)) {
                if (state.rootAttempts >= MAX_ROOT_ATTEMPTS || !state.canStartRoot()) break groups;
                RouteNode rootNode = rootNode(root);
                if (rootNode == null) continue;
                int rootCapacity = 4 - rootNode.getBaseIncidentSections();
                if (rootCapacity < 1) continue;
                state.rootAttempts++;
                List<OrthogonalCorridorNetworkBuilder.Terminal> terminals = group.stream()
                        .map(demand -> new OrthogonalCorridorNetworkBuilder.Terminal(
                                demand.getId(), demand.getConnectionPointId(),
                                coordinate(demand.getLocation()), demand.getFlowTph()))
                        .collect(Collectors.toList());
                List<Geometry> footprints = footprints(environment, root, group);
                List<OrthogonalCorridorNetworkBuilder.Network> networks = corridorBuilder.buildControl(
                        terminals, rootNode, rootCapacity, footprints, environment,
                        (demandId, junction, diameter, avoidance) -> routeTerminal(
                                demandsById.get(demandId), junction, diameter,
                                environment, avoidance, state));
                List<OrthogonalCorridorNetworkBuilder.Network> ordered = new ArrayList<>(networks);
                ordered.sort(Comparator
                        .comparingDouble(BoundedSharedNetworkSeedGenerator::networkLength)
                        .thenComparing(BoundedSharedNetworkSeedGenerator::networkSignature));
                for (OrthogonalCorridorNetworkBuilder.Network network : ordered.stream()
                        .limit(MAX_NETWORKS_PER_ROOT).collect(Collectors.toList())) {
                    networksExamined++;
                    for (RoutingProblemSnapshot.Demand demand : group) {
                        SeedPath path = path(network, root, demand);
                        if (path != null && pathKeys.add(path.key())) paths.add(path);
                    }
                }
                if (covered(paths, requestedInGroup)) break;
            }
            if (covered(paths, requested)) break;
        }
        paths.sort(Comparator.comparing(SeedPath::getRootId)
                .thenComparing(SeedPath::getDemandId)
                .thenComparing(SeedPath::pointSignature));
        return new Result(paths, state.rootAttempts, networksExamined,
                state.routeCalls, coveredIds(paths, requested), state.expired());
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
            RoutingProblemSnapshot problem, Set<String> priorityIds) {
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
                .limit((long) MAX_GROUP_SIZE * MAX_ROOT_ATTEMPTS)
                .collect(Collectors.toCollection(ArrayList::new));
        if (priority.isEmpty()) return List.of();
        List<List<RoutingProblemSnapshot.Demand>> result = new ArrayList<>();
        for (int offset = 0; offset < priority.size(); offset += MAX_GROUP_SIZE) {
            List<RoutingProblemSnapshot.Demand> group = new ArrayList<>(priority.subList(
                    offset, Math.min(priority.size(), offset + MAX_GROUP_SIZE)));
            double centerX = group.stream().mapToDouble(demand -> demand.getLocation().getXM())
                    .average().orElseThrow();
            double centerY = group.stream().mapToDouble(demand -> demand.getLocation().getYM())
                    .average().orElseThrow();
            eligible.stream().filter(demand -> !priorityIds.contains(demand.getId()))
                    .sorted(Comparator.comparingDouble((RoutingProblemSnapshot.Demand demand) ->
                                    squaredDistance(demand.getLocation().getXM(),
                                            demand.getLocation().getYM(), centerX, centerY))
                            .thenComparing(RoutingProblemSnapshot.Demand::getId))
                    .limit(MAX_GROUP_SIZE - group.size()).forEach(group::add);
            if (group.size() >= 2) result.add(List.copyOf(group));
        }
        return List.copyOf(result);
    }

    private static List<RoutingProblemSnapshot.RootCandidate> eligibleRoots(
            RoutingProblemSnapshot problem, List<RoutingProblemSnapshot.Demand> group) {
        List<RoutingProblemSnapshot.RootCandidate> roots = problem.getRoots().stream()
                .filter(root -> root.getRealization() != null)
                .filter(root -> root.getRealization().getBaseIncidentSections() < 4)
                .collect(Collectors.toCollection(ArrayList::new));
        roots.sort(Comparator
                .comparingDouble((RoutingProblemSnapshot.RootCandidate root) -> group.stream()
                        .mapToDouble(demand -> squaredDistance(
                                root.getLocation().getXM(), root.getLocation().getYM(),
                                demand.getLocation().getXM(), demand.getLocation().getYM()))
                        .sum())
                .thenComparing(RoutingProblemSnapshot.RootCandidate::getId));
        return roots;
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
            RoutingProblemSnapshot.Demand demand) {
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
        return new SeedPath(root.getId(), demand.getId(), points);
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

    private static String networkSignature(OrthogonalCorridorNetworkBuilder.Network network) {
        return network.edges().stream().sorted(Comparator.comparing(RouteEdge::getId))
                .map(edge -> edge.getId() + "=" + edge.getCoordinates().stream()
                        .map(point -> point.getXM() + ":" + point.getYM())
                        .collect(Collectors.joining(";")))
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
        private final List<CatalogMetricPoint> points;

        private SeedPath(String rootId, String demandId, List<CatalogMetricPoint> points) {
            this.rootId = rootId;
            this.demandId = demandId;
            this.points = List.copyOf(points);
        }

        String getRootId() { return rootId; }
        String getDemandId() { return demandId; }
        List<CatalogMetricPoint> getPoints() { return points; }
        private String pointSignature() {
            return points.stream().map(point -> point.getXMm() + ":" + point.getYMm())
                    .collect(Collectors.joining(";"));
        }
        private String key() { return rootId + "\u0000" + demandId + "\u0000" + pointSignature(); }
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

        private boolean expired() { return BoundedSharedNetworkSeedGenerator.expired(deadlineNanos); }
        private boolean canStartRoot() {
            return deadlineNanos - System.nanoTime() >= MINIMUM_ROOT_ATTEMPT_BUDGET_NANOS;
        }
        private boolean canRoute() { return routeCalls < maximumRouteCalls && !expired(); }
    }
}
