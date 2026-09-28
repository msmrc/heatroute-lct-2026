package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.NetworkSizingResult;
import ru.lct.heatroute.domain.sizing.NetworkTreeEdge;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.sizing.SizedNetworkEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Повторяет полный допуск выпрямленной сети без поиска маршрутов и без изменения предложенных XY. */
public final class AxisShiftAlternativeEvaluator {
    private static final Logger LOGGER = LoggerFactory.getLogger(AxisShiftAlternativeEvaluator.class);
    private final OfficialRouteValidator validator;
    private final OfficialObstacleRouter router;
    private final OfficialNetworkSizer sizer;
    private final OfficialDepthPlanner depth;
    private final OfficialVariantEconomicsCalculator economics;

    public AxisShiftAlternativeEvaluator(OfficialRouteValidator validator, OfficialObstacleRouter router,
            OfficialNetworkSizer sizer, OfficialDepthPlanner depth, OfficialVariantEconomicsCalculator economics) {
        this.validator = Objects.requireNonNull(validator);
        this.router = Objects.requireNonNull(router);
        this.sizer = Objects.requireNonNull(sizer);
        this.depth = Objects.requireNonNull(depth);
        this.economics = Objects.requireNonNull(economics);
    }

    /** Независимый экспорт использует те же официальные каталоги и предметные валидаторы. */
    public static AxisShiftAlternativeEvaluator standard(OfficialPipeCatalog pipes, OfficialEconomics costs) {
        OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(constraints, new OfficialCrossingGeometry());
        return new AxisShiftAlternativeEvaluator(new OfficialRouteValidator(rules, pipes), new OfficialObstacleRouter(rules),
                new OfficialNetworkSizer(pipes), new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(constraints, pipes),
                        new OfficialDepthOptimizer(pipes, costs), new OfficialDepthProfileValidator(pipes)),
                new OfficialVariantEconomicsCalculator(pipes, costs));
    }

    public RouteVariant assess(RouteAxisShiftControl.Replacement replacement, String id, String strategy,
            List<RouteConnection> connections, List<ImportedOfficialFeature> features, OfficialRunParameters parameters) {
        return assess(replacement, id, strategy, connections, features, parameters,
                router.prepare(features), false);
    }

    /**
     * Prepares immutable constraints once for a saved-result audit. Every candidate edge is still
     * independently rebuilt; the session only removes repeated spatial preparation.
     */
    public IndependentSession independentSession(List<ImportedOfficialFeature> features) {
        return new IndependentSession(List.copyOf(features), router.prepare(features));
    }

    RouteVariant assess(RouteAxisShiftControl.Replacement replacement, String id, String strategy,
            List<RouteConnection> connections, List<ImportedOfficialFeature> features, OfficialRunParameters parameters,
            OfficialRoutingEnvironment environment) {
        return assess(replacement, id, strategy, connections, features, parameters,
                environment, false);
    }

    /**
     * Reuses unchanged checked sections only inside the same prepared calculation.  Saved or
     * external variants must call {@link #assess} and are independently rebuilt in full.
     */
    RouteVariant assessPrepared(RouteAxisShiftControl.Replacement replacement, String id, String strategy,
            List<RouteConnection> connections, List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters, OfficialRoutingEnvironment environment) {
        return assess(replacement, id, strategy, connections, features, parameters,
                environment, true);
    }

    private RouteVariant assess(RouteAxisShiftControl.Replacement replacement, String id, String strategy,
            List<RouteConnection> connections, List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters, OfficialRoutingEnvironment environment,
            boolean reuseCheckedAssemblies) {
        ensureActive();
        Map<String, BigDecimal> flows = new LinkedHashMap<>();
        for (RouteConnection connection : connections) {
            if ("connected".equals(connection.getStatus())) {
                if (connection.getDemandId() == null || connection.getFlowTph() == null
                        || connection.getFlowTph().signum() < 0) return null;
                flows.merge("demand:" + connection.getDemandId(), connection.getFlowTph(), BigDecimal::add);
            }
        }
        Set<String> demandNodes = replacement.getNodes().stream()
                .filter(node -> "demand_connection".equals(node.getNodeType()))
                .map(RouteNode::getId).collect(Collectors.toSet());
        if (!flows.keySet().equals(demandNodes)) return null;
        NetworkSizingResult sizing = sizer.size(replacement.getEdges().stream().map(edge -> new NetworkTreeEdge(
                edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(), edge.getLengthM()))
                .collect(Collectors.toList()), flows);
        if (!sizing.getIssues().isEmpty()) return null;
        List<RouteNode> nodes = replacement.getNodes().stream().map(environment::verifiedRootSupport).collect(Collectors.toList());
        Map<String, RouteNode> byId = nodes.stream().collect(Collectors.toMap(RouteNode::getId, node -> node));
        List<RouteEdge> edges = new ArrayList<>();
        for (RouteEdge edge : replacement.getEdges()) {
            ensureActive();
            SizedNetworkEdge assigned = sizing.getEdges().get(edge.getId());
            if (assigned == null || assigned.getDiameter() == null) return null;
            if (reuseCheckedAssemblies && canReuseCheckedAssembly(
                    edge, assigned, replacement.getChangedEdgeIds())) {
                edges.add(new RouteEdge(edge.getId(), edge.getUpstreamNodeId(),
                        edge.getDownstreamNodeId(), edge.getLengthM().doubleValue(),
                        edge.getCoordinates(), edge.getSections(), assigned.getFlowTph(),
                        assigned.getDiameter()));
                continue;
            }
            List<Coordinate> coordinates = edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
            Envelope bounds = new Envelope();
            coordinates.forEach(bounds::expandToInclude);
            RouteNode upstream = byId.get(edge.getUpstreamNodeId());
            if (upstream == null || coordinates.size() < 2) return null;
            // Новые длины/ДУ требуют новых special-секций даже у геометрически неизменного ребра.
            RoutePath path = router.completeCheckedCorridorAssembly(assigned.getDiameter(), environment,
                    bounds, coordinates.get(0), upstream.isRoot() ? upstream.getTargetId() : null, coordinates);
            if (path == null) return null;
            edges.add(new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(), path.lengthM(),
                    edge.getCoordinates(), path.sections(), assigned.getFlowTph(), assigned.getDiameter()));
        }
        List<RouteValidationIssue> issues = new ArrayList<>(environment.validationFor(validator).validate(nodes, edges, features));
        issues.addAll(new ExpertChamberRouteValidator().validate(nodes, edges, environment::existingDirections));
        issues.addAll(ExpertRouteBendRules.validate(nodes, edges));
        if (!issues.isEmpty()) {
            RouteValidationIssue first = issues.get(0);
            LOGGER.debug("Axis shift replacement rejected shift={} constraint={} subject={} detail={}",
                    replacement.getSubjectId(), first.getCode(), first.getSubjectId(), first.getMessage());
            return null;
        }
        if (parameters.isDepthEnabled()) {
            Map<String, Set<String>> tieIns = new LinkedHashMap<>();
            for (RouteNode node : nodes) {
                if (node.isRoot() && node.getTargetId() != null) tieIns.put(node.getId(), Set.of(node.getTargetId()));
            }
            edges = depth.planNetwork(edges, features, parameters.getMinimumDepthM(), parameters.getMaximumDepthM(), tieIns, Map.of());
            if (edges.stream().anyMatch(edge -> edge.getDepthProfile() == null || !edge.getDepthProfile().isComplete()
                    || !edge.getDepthProfile().getIssues().isEmpty())) return null;
        }
        ExistingNetworkReconstructionResult reconstruction = ExistingNetworkReconstructionResult.empty();
        VariantEconomics cost = economics.calculate(nodes, edges, connections, reconstruction, false);
        if (!cost.isComplete()) return null;
        BigDecimal length = edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(3, RoundingMode.HALF_UP);
        return new RouteVariant(id, strategy, nodes, edges, connections, length, List.of(), List.of(), reconstruction, cost, null);
    }

    static boolean canReuseCheckedAssembly(RouteEdge edge, SizedNetworkEdge assigned,
            Set<String> changedEdgeIds) {
        return !changedEdgeIds.contains(edge.getId())
                && !edge.getSections().isEmpty()
                && Objects.equals(edge.getDiameter(), assigned.getDiameter())
                && edge.getFlowTph() != null
                && assigned.getFlowTph() != null
                && edge.getFlowTph().compareTo(assigned.getFlowTph()) == 0;
    }

    public final class IndependentSession {
        private final List<ImportedOfficialFeature> features;
        private final OfficialRoutingEnvironment environment;

        private IndependentSession(List<ImportedOfficialFeature> features,
                OfficialRoutingEnvironment environment) {
            this.features = features;
            this.environment = environment;
        }

        public RouteVariant assess(RouteAxisShiftControl.Replacement replacement,
                String id, String strategy, List<RouteConnection> connections,
                OfficialRunParameters parameters) {
            return AxisShiftAlternativeEvaluator.this.assess(replacement, id, strategy,
                    connections, features, parameters, environment, false);
        }
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Axis shift alternative evaluation cancelled");
    }
}
