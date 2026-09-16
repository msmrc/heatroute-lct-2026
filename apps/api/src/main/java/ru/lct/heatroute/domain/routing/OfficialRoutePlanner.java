package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.reconstruction.TieInLoad;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.NetworkSizingResult;
import ru.lct.heatroute.domain.sizing.NetworkTreeEdge;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.sizing.SizedNetworkEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

@Component
public class OfficialRoutePlanner {
    public static final String ALGORITHM_VERSION = "r8-depth-profile-2";
    private static final double MIN_EDGE_LENGTH_M = 0.01;
    private static final double MIN_SHARED_SAVING_M = 0.01;
    private static final double MAX_SHARED_PAIR_DISTANCE_M = 500.0;
    private static final long MAX_SHARED_TARGETS_PER_PAIR = 2;
    private static final int SHARED_NEIGHBOUR_FACTOR = 2;
    private static final long MAX_ASSIGNMENT_CANDIDATES = 4;

    private final OfficialRouteValidator validator;
    private final OfficialObstacleRouter obstacleRouter;
    private final OfficialPipeCatalog pipeCatalog;
    private final OfficialNetworkSizer networkSizer;
    private final OfficialExistingNetworkReconstructor reconstructor;
    private final OfficialVariantEconomicsCalculator economicsCalculator;
    private final OfficialDepthPlanner depthPlanner;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public OfficialRoutePlanner(
            OfficialRouteValidator validator,
            OfficialObstacleRouter obstacleRouter,
            OfficialPipeCatalog pipeCatalog,
            OfficialNetworkSizer networkSizer,
            OfficialExistingNetworkReconstructor reconstructor,
            OfficialVariantEconomicsCalculator economicsCalculator,
            OfficialDepthPlanner depthPlanner) {
        this.validator = validator;
        this.obstacleRouter = obstacleRouter;
        this.pipeCatalog = pipeCatalog;
        this.networkSizer = networkSizer;
        this.reconstructor = reconstructor;
        this.economicsCalculator = economicsCalculator;
        this.depthPlanner = depthPlanner;
    }

    public OfficialCalculationResult plan(
            List<ImportedOfficialFeature> features,
            TopologyAnalysis topology) {
        return plan(features, topology, OfficialRunParameters.defaults());
    }

    public OfficialCalculationResult plan(
            List<ImportedOfficialFeature> features,
            TopologyAnalysis topology,
            OfficialRunParameters parameters) {
        OfficialRunParameters validatedParameters = parameters.validated();
        Map<String, ImportedOfficialFeature> featuresById = features.stream().collect(Collectors.toMap(
                ImportedOfficialFeature::getFeatureId,
                feature -> feature,
                (left, right) -> left,
                LinkedHashMap::new));
        Map<String, Integer> chamberIncidentCounts = chamberIncidentCounts(features);
        List<Demand> demands = demands(features, featuresById);
        OfficialRoutingEnvironment routingEnvironment = obstacleRouter.prepare(features);
        Map<String, List<TieInCandidate>> candidatesByConnection = topology.getTieInCandidates().stream()
                .collect(Collectors.groupingBy(
                        TieInCandidate::getConnectionPointId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        VariantDraft independentDraft = independent(
                demands,
                candidatesByConnection,
                featuresById,
                chamberIncidentCounts,
                routingEnvironment,
                Collections.emptyMap(),
                RoutePreference.SHORTEST,
                "independent");
        RouteVariant independent = finish(
                "independent", "independent", independentDraft, features, validatedParameters);
        List<RouteVariant> variants = new ArrayList<>();
        variants.add(independent);

        VariantDraft sharedDraft = shared(
                demands,
                candidatesByConnection,
                featuresById,
                chamberIncidentCounts,
                independentDraft.lengthByDemand,
                routingEnvironment);
        RouteVariant shared = finish("shared", "shared_trunk", sharedDraft, features, validatedParameters);
        if (sharedDraft.sharedPairCount > 0
                && !edgeSignature(independent).equals(edgeSignature(shared))) {
            variants.add(shared);
        }

        VariantDraft diverseDraft = independent(
                demands,
                candidatesByConnection,
                featuresById,
                chamberIncidentCounts,
                routingEnvironment,
                independentDraft.targetByDemand,
                RoutePreference.RIGHT,
                "diverse");
        RouteVariant diverse = finish(
                "diverse", "alternative_tie_ins", diverseDraft, features, validatedParameters);
        Set<String> existingSignatures = variants.stream()
                .map(this::edgeSignature)
                .collect(Collectors.toSet());
        if (diverse.getConnectedDemandCount() > 0
                && diverse.isValid()
                && existingSignatures.add(edgeSignature(diverse))) {
            variants.add(diverse);
        }

        Map<String, Integer> rankById = new HashMap<>();
        List<RouteVariant> rankable = variants.stream()
                .filter(RouteVariant::isValid)
                .filter(variant -> variant.getEconomics().isComplete())
                .sorted(Comparator.comparing((RouteVariant variant) -> variant.getEconomics().getScore())
                        .thenComparing(RouteVariant::getId))
                .collect(Collectors.toList());
        for (int index = 0; index < rankable.size(); index++) {
            rankById.put(rankable.get(index).getId(), index + 1);
        }
        variants = variants.stream()
                .map(variant -> rankById.containsKey(variant.getId())
                        ? variant.withRank(rankById.get(variant.getId()))
                        : variant)
                .collect(Collectors.toList());
        String preferred = variants.stream()
                .filter(variant -> Integer.valueOf(1).equals(variant.getRank()))
                .map(RouteVariant::getId)
                .findFirst()
                .orElse(null);
        if (preferred == null) {
            preferred = variants.stream()
                        .filter(RouteVariant::isValid)
                        .sorted(Comparator
                                .comparingLong(RouteVariant::getConnectedDemandCount).reversed()
                                .thenComparing(RouteVariant::getTotalLengthM)
                                .thenComparing(RouteVariant::getId))
                        .map(RouteVariant::getId)
                        .findFirst()
                        .orElse(null);
        }
        return new OfficialCalculationResult(ALGORITHM_VERSION, demands.size(), variants, preferred);
    }

    private VariantDraft independent(
            List<Demand> demands,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            OfficialRoutingEnvironment routingEnvironment,
            Map<String, String> avoidedTargetByDemand,
            RoutePreference preference,
            String strategyPrefix) {
        VariantDraft draft = new VariantDraft();
        Map<String, Integer> usedChamberSlots = new HashMap<>();
        for (Demand demand : demands) {
            String avoidedTarget = avoidedTargetByDemand.get(demand.id);
            Assignment assignment = chooseAssignment(
                    demand,
                    candidatesByConnection.getOrDefault(demand.connectionPointId, List.of()),
                    featuresById,
                    chamberIncidentCounts,
                    usedChamberSlots,
                    draft,
                    strategyPrefix + ":" + demand.id,
                    routingEnvironment,
                    avoidedTarget == null ? Collections.emptySet() : Set.of(avoidedTarget),
                    preference);
            if (assignment == null) {
                draft.noRoute(
                        demand,
                        candidatesByConnection.getOrDefault(demand.connectionPointId, List.of()).isEmpty()
                                ? "NO_TIE_IN_CANDIDATE"
                                : "NO_NON_CROSSING_ROUTE");
                continue;
            }
            if (assignment.chamberTargetId() != null) {
                usedChamberSlots.merge(assignment.chamberTargetId(), 1, Integer::sum);
            }
            addDirect(draft, demand, assignment, strategyPrefix);
        }
        return draft;
    }

    private VariantDraft shared(
            List<Demand> demands,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            Map<String, Double> independentLengthByDemand,
            OfficialRoutingEnvironment routingEnvironment) {
        List<DemandPair> demandPairs = new ArrayList<>();
        for (int leftIndex = 0; leftIndex < demands.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < demands.size(); rightIndex++) {
                Demand left = demands.get(leftIndex);
                Demand right = demands.get(rightIndex);
                double distance = left.coordinate.distance(right.coordinate);
                if (distance <= MAX_SHARED_PAIR_DISTANCE_M) {
                    demandPairs.add(new DemandPair(left, right, distance));
                }
            }
        }
        demandPairs.sort(Comparator.comparingDouble((DemandPair pair) -> pair.distanceM)
                .thenComparing(pair -> pair.left.id)
                .thenComparing(pair -> pair.right.id));
        List<PairPlan> plans = new ArrayList<>();
        int pairLimit = Math.max(1, demands.size() * SHARED_NEIGHBOUR_FACTOR);
        for (DemandPair pair : demandPairs.stream().limit(pairLimit).collect(Collectors.toList())) {
                PairPlan plan = bestPairPlan(
                        pair.left,
                        pair.right,
                        candidatesByConnection,
                        featuresById,
                        chamberIncidentCounts,
                        independentLengthByDemand,
                        routingEnvironment);
                if (plan != null && plan.savingM > MIN_SHARED_SAVING_M) {
                    plans.add(plan);
                }
        }
        plans.sort(Comparator.comparingDouble((PairPlan plan) -> plan.savingM).reversed()
                .thenComparing(plan -> plan.left.id)
                .thenComparing(plan -> plan.right.id));

        VariantDraft draft = new VariantDraft();
        Set<String> paired = new HashSet<>();
        Map<String, Integer> usedChamberSlots = new HashMap<>();
        for (PairPlan plan : plans) {
            if (paired.contains(plan.left.id) || paired.contains(plan.right.id)) {
                continue;
            }
            String chamberTargetId = plan.assignment.chamberTargetId();
            if (chamberTargetId != null
                    && !hasChamberCapacity(chamberTargetId, chamberIncidentCounts, usedChamberSlots)) {
                continue;
            }
            if (!canAddSharedPair(draft, plan)) {
                continue;
            }
            paired.add(plan.left.id);
            paired.add(plan.right.id);
            if (chamberTargetId != null) {
                usedChamberSlots.merge(chamberTargetId, 1, Integer::sum);
            }
            addSharedPair(draft, plan);
        }

        for (Demand demand : demands) {
            if (paired.contains(demand.id)) {
                continue;
            }
            Assignment assignment = chooseAssignment(
                    demand,
                    candidatesByConnection.getOrDefault(demand.connectionPointId, List.of()),
                    featuresById,
                    chamberIncidentCounts,
                    usedChamberSlots,
                    draft,
                    "shared:" + demand.id,
                    routingEnvironment,
                    Collections.emptySet(),
                    RoutePreference.SHORTEST);
            if (assignment == null) {
                draft.noRoute(
                        demand,
                        candidatesByConnection.getOrDefault(demand.connectionPointId, List.of()).isEmpty()
                                ? "NO_TIE_IN_CANDIDATE"
                                : "NO_NON_CROSSING_ROUTE");
                continue;
            }
            if (assignment.chamberTargetId() != null) {
                usedChamberSlots.merge(assignment.chamberTargetId(), 1, Integer::sum);
            }
            addDirect(draft, demand, assignment, "shared");
        }
        return draft;
    }

    private PairPlan bestPairPlan(
            Demand left,
            Demand right,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            Map<String, Double> independentLengthByDemand,
            OfficialRoutingEnvironment routingEnvironment) {
        Double independentLeft = independentLengthByDemand.get(left.id);
        Double independentRight = independentLengthByDemand.get(right.id);
        if (independentLeft == null || independentRight == null) {
            return null;
        }
        if (left.coordinate.distance(right.coordinate) > MAX_SHARED_PAIR_DISTANCE_M) {
            return null;
        }
        Map<String, TieInCandidate> rightByTarget = candidatesByConnection
                .getOrDefault(right.connectionPointId, List.of())
                .stream()
                .collect(Collectors.toMap(this::targetKey, candidate -> candidate, (a, b) -> a));
        PairPlan best = null;
        List<TieInCandidate> leftCandidates = candidatesByConnection
                .getOrDefault(left.connectionPointId, List.of())
                .stream()
                .sorted(Comparator.comparing(TieInCandidate::getDistanceM).thenComparing(this::targetKey))
                .limit(MAX_SHARED_TARGETS_PER_PAIR)
                .collect(Collectors.toList());
        for (TieInCandidate leftCandidate : leftCandidates) {
            TieInCandidate rightCandidate = rightByTarget.get(targetKey(leftCandidate));
            if (rightCandidate == null) {
                continue;
            }
            ImportedOfficialFeature target = featuresById.get(leftCandidate.getTargetId());
            if (target == null) {
                continue;
            }
            Coordinate junction = midpoint(left.coordinate, right.coordinate);
            Coordinate targetCoordinate = targetCoordinate(junction, target.getMetricGeometry());
            int leftDiameter = diameterFor(left.flowTph);
            int rightDiameter = diameterFor(right.flowTph);
            BigDecimal trunkFlow = left.flowTph.add(right.flowTph);
            int trunkDiameter = diameterFor(trunkFlow);
            RoutePath leftPath = obstacleRouter.find(
                    left.coordinate,
                    junction,
                    leftDiameter,
                    routingEnvironment,
                    Collections.emptySet(),
                    RoutePreference.LEFT);
            RoutePath rightPath = obstacleRouter.find(
                    right.coordinate,
                    junction,
                    rightDiameter,
                    routingEnvironment,
                    Collections.emptySet(),
                    RoutePreference.RIGHT);
            RoutePath trunkPath = obstacleRouter.find(
                    junction,
                    targetCoordinate,
                    trunkDiameter,
                    routingEnvironment,
                    Set.of(leftCandidate.getTargetId()),
                    RoutePreference.SHORTEST);
            if (leftPath == null || rightPath == null || trunkPath == null) {
                continue;
            }
            double totalLength = leftPath.lengthM() + rightPath.lengthM() + trunkPath.lengthM();
            double saving = independentLeft + independentRight - totalLength;
            Assignment assignment = new Assignment(
                    leftCandidate,
                    targetCoordinate,
                    rootNode(leftCandidate, targetCoordinate, chamberIncidentCounts,
                            "shared:" + left.id + ":" + right.id),
                    trunkPath,
                    trunkFlow,
                    trunkDiameter);
            PairPlan candidate = new PairPlan(
                    left,
                    right,
                    junction,
                    assignment,
                    leftPath,
                    rightPath,
                    leftDiameter,
                    rightDiameter,
                    totalLength,
                    saving);
            if (best == null
                    || candidate.savingM > best.savingM
                    || (candidate.savingM == best.savingM
                            && targetKey(candidate.assignment.candidate)
                                    .compareTo(targetKey(best.assignment.candidate)) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private Assignment chooseAssignment(
            Demand demand,
            List<TieInCandidate> candidates,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            Map<String, Integer> usedChamberSlots,
            VariantDraft draft,
            String rootSuffix,
            OfficialRoutingEnvironment routingEnvironment,
            Set<String> avoidedTargets,
            RoutePreference preference) {
        int diameter = diameterFor(demand.flowTph);
        return candidates.stream()
                .sorted(Comparator.comparing(TieInCandidate::getDistanceM)
                        .thenComparing(this::targetKey))
                .filter(candidate -> !avoidedTargets.contains(candidate.getTargetId()))
                .filter(candidate -> !"heat_chamber".equals(candidate.getTargetType())
                        || hasChamberCapacity(candidate.getTargetId(), chamberIncidentCounts, usedChamberSlots))
                .limit(MAX_ASSIGNMENT_CANDIDATES)
                .map(candidate -> {
                    ImportedOfficialFeature target = featuresById.get(candidate.getTargetId());
                    if (target == null) {
                        return null;
                    }
                    Coordinate coordinate = targetCoordinate(demand.coordinate, target.getMetricGeometry());
                    RoutePath path = obstacleRouter.find(
                            demand.coordinate,
                            coordinate,
                            diameter,
                            routingEnvironment,
                            Set.of(candidate.getTargetId()),
                            preference,
                            avoidanceLines(draft));
                    if (path == null) {
                        return null;
                    }
                    return new Assignment(
                            candidate,
                            coordinate,
                            rootNode(candidate, coordinate, chamberIncidentCounts, rootSuffix),
                            path,
                            demand.flowTph,
                            diameter);
                })
                .filter(assignment -> assignment != null)
                .sorted(Comparator.comparingDouble((Assignment assignment) -> assignment.path.lengthM())
                        .thenComparing(assignment -> targetKey(assignment.candidate)))
                .filter(assignment -> canAddDirect(draft, demand, assignment, rootSuffix))
                .findFirst()
                .orElse(null);
    }

    private boolean canAddDirect(
            VariantDraft draft,
            Demand demand,
            Assignment assignment,
            String prefix) {
        VariantDraft simulation = draft.copy();
        int before = simulation.edges.size();
        addDirect(simulation, demand, assignment, prefix);
        return simulation.edges.size() == before + 1 && isStructurallyValid(simulation);
    }

    private boolean canAddSharedPair(VariantDraft draft, PairPlan plan) {
        VariantDraft simulation = draft.copy();
        int before = simulation.edges.size();
        addSharedPair(simulation, plan);
        return simulation.edges.size() == before + 3 && isStructurallyValid(simulation);
    }

    private boolean isStructurallyValid(VariantDraft draft) {
        return validator.validate(new ArrayList<>(draft.nodes.values()), draft.edges).isEmpty();
    }

    private RouteNode rootNode(
            TieInCandidate candidate,
            Coordinate coordinate,
            Map<String, Integer> chamberIncidentCounts,
            String suffix) {
        boolean existingChamber = "heat_chamber".equals(candidate.getTargetType());
        String id = existingChamber
                ? "tie:chamber:" + candidate.getTargetId()
                : "tie:segment:" + candidate.getTargetId() + ":" + suffix;
        return new RouteNode(
                id,
                existingChamber ? "existing_chamber_tie_in" : "new_tie_in_chamber",
                new RouteCoordinate(coordinate.x, coordinate.y),
                true,
                true,
                existingChamber ? chamberIncidentCounts.getOrDefault(candidate.getTargetId(), 0) : 2,
                candidate.getTargetId());
    }

    private void addDirect(VariantDraft draft, Demand demand, Assignment assignment, String prefix) {
        RouteNode demandNode = demandNode(demand);
        draft.addNode(demandNode);
        draft.addNode(assignment.root);
        double length = assignment.path.lengthM();
        if (length <= MIN_EDGE_LENGTH_M) {
            draft.noRoute(demand, "ROUTE_LENGTH_ZERO");
            return;
        }
        draft.addEdge(routeEdge(
                prefix + ":edge:" + demand.id,
                assignment.root.getId(),
                demandNode.getId(),
                assignment.path.reversed(),
                assignment.flowTph,
                assignment.diameter));
        draft.targetByDemand.put(demand.id, assignment.candidate.getTargetId());
        draft.connected(demand, length);
    }

    private void addSharedPair(VariantDraft draft, PairPlan plan) {
        draft.sharedPairCount++;
        RouteNode leftNode = demandNode(plan.left);
        RouteNode rightNode = demandNode(plan.right);
        String pairId = plan.left.id + ":" + plan.right.id;
        RouteNode junction = new RouteNode(
                "junction:" + pairId,
                "new_branch_chamber",
                new RouteCoordinate(plan.junction.x, plan.junction.y),
                true,
                false,
                0,
                null);
        draft.addNode(leftNode);
        draft.addNode(rightNode);
        draft.addNode(junction);
        draft.addNode(plan.assignment.root);
        double leftLength = plan.leftPath.lengthM();
        double rightLength = plan.rightPath.lengthM();
        double trunkLength = plan.assignment.path.lengthM();
        if (leftLength <= MIN_EDGE_LENGTH_M || rightLength <= MIN_EDGE_LENGTH_M || trunkLength <= MIN_EDGE_LENGTH_M) {
            draft.noRoute(plan.left, "ROUTE_LENGTH_ZERO");
            draft.noRoute(plan.right, "ROUTE_LENGTH_ZERO");
            return;
        }
        draft.addEdge(routeEdge(
                "shared:branch:" + plan.left.id,
                junction.getId(),
                leftNode.getId(),
                plan.leftPath.reversed(),
                plan.left.flowTph,
                plan.leftDiameter));
        draft.addEdge(routeEdge(
                "shared:branch:" + plan.right.id,
                junction.getId(),
                rightNode.getId(),
                plan.rightPath.reversed(),
                plan.right.flowTph,
                plan.rightDiameter));
        draft.addEdge(routeEdge(
                "shared:trunk:" + pairId,
                plan.assignment.root.getId(),
                junction.getId(),
                plan.assignment.path.reversed(),
                plan.assignment.flowTph,
                plan.assignment.diameter));
        draft.targetByDemand.put(plan.left.id, plan.assignment.candidate.getTargetId());
        draft.targetByDemand.put(plan.right.id, plan.assignment.candidate.getTargetId());
        draft.connected(plan.left, leftLength + trunkLength);
        draft.connected(plan.right, rightLength + trunkLength);
    }

    private RouteEdge routeEdge(
            String id,
            String upstreamNodeId,
            String downstreamNodeId,
            RoutePath path,
            BigDecimal flowTph,
            int diameter) {
        List<RouteCoordinate> coordinates = path.coordinates().stream()
                .map(coordinate -> new RouteCoordinate(coordinate.x, coordinate.y))
                .collect(Collectors.toList());
        return new RouteEdge(
                id,
                upstreamNodeId,
                downstreamNodeId,
                path.lengthM(),
                coordinates,
                path.sections(),
                flowTph,
                diameter);
    }

    private RouteVariant finish(
            String id,
            String strategy,
            VariantDraft draft,
            List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters) {
        List<RouteNode> nodes = draft.nodes.values().stream()
                .sorted(Comparator.comparing(RouteNode::getId))
                .collect(Collectors.toList());
        draft.edges.sort(Comparator.comparing(RouteEdge::getId));
        draft.connections.sort(Comparator.comparing(RouteConnection::getDemandId));
        Map<String, BigDecimal> demandFlowByNode = draft.connections.stream()
                .filter(connection -> "connected".equals(connection.getStatus()))
                .collect(Collectors.toMap(
                        connection -> "demand:" + connection.getDemandId(),
                        RouteConnection::getFlowTph,
                        BigDecimal::add,
                        LinkedHashMap::new));
        NetworkSizingResult initialSizing = sizeRoutes(draft.edges, demandFlowByNode);
        List<RouteEdge> sizedEdges = applySizing(draft.edges, initialSizing);
        List<RouteEdge> depthReroutedEdges = rerouteDepthConflicts(
                nodes, sizedEdges, features, parameters);
        NetworkSizingResult sizing = sizeRoutes(depthReroutedEdges, demandFlowByNode);
        List<RouteEdge> profiledEdges = withDepthProfiles(
                nodes, applySizing(depthReroutedEdges, sizing), features, parameters);
        List<RouteValidationIssue> issues = validator.validate(nodes, profiledEdges, features);
        ExistingNetworkReconstructionResult reconstruction = reconstructor.reconstruct(
                features,
                tieInLoads(nodes, profiledEdges));
        VariantEconomics economics = economicsCalculator.calculate(
                nodes, profiledEdges, draft.connections, reconstruction);
        BigDecimal totalLength = profiledEdges.stream()
                .map(RouteEdge::getLengthM)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(3, RoundingMode.HALF_UP);
        return new RouteVariant(
                id,
                strategy,
                nodes,
                profiledEdges,
                draft.connections,
                totalLength,
                issues,
                sizing.getIssues(),
                reconstruction,
                economics,
                null);
    }

    private NetworkSizingResult sizeRoutes(
            List<RouteEdge> edges,
            Map<String, BigDecimal> demandFlowByNode) {
        return networkSizer.size(
                edges.stream()
                        .map(edge -> new NetworkTreeEdge(
                                edge.getId(),
                                edge.getUpstreamNodeId(),
                                edge.getDownstreamNodeId(),
                                edge.getLengthM()))
                        .collect(Collectors.toList()),
                demandFlowByNode);
    }

    private List<RouteEdge> applySizing(
            List<RouteEdge> edges,
            NetworkSizingResult sizing) {
        return edges.stream().map(edge -> {
            SizedNetworkEdge sized = sizing.getEdges().get(edge.getId());
            return sized == null ? edge : new RouteEdge(
                    edge.getId(),
                    edge.getUpstreamNodeId(),
                    edge.getDownstreamNodeId(),
                    edge.getLengthM().doubleValue(),
                    edge.getCoordinates(),
                    edge.getSections(),
                    sized.getFlowTph(),
                    sized.getDiameter());
        }).collect(Collectors.toList());
    }

    private List<RouteEdge> withDepthProfiles(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters) {
        Map<String, RouteNode> nodesById = nodes.stream().collect(Collectors.toMap(
                RouteNode::getId,
                node -> node,
                (left, right) -> left,
                LinkedHashMap::new));
        return edges.stream()
                .map(edge -> withDepthProfile(edge, depthPlanner.plan(
                        edge,
                        features,
                        parameters.getMinimumDepthM(),
                        parameters.getMaximumDepthM(),
                        endpointFeatureIds(edge, nodesById))))
                .collect(Collectors.toList());
    }

    private List<RouteEdge> rerouteDepthConflicts(
            List<RouteNode> nodes,
            List<RouteEdge> sizedEdges,
            List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters) {
        Map<String, RouteNode> nodesById = nodes.stream().collect(Collectors.toMap(
                RouteNode::getId,
                node -> node,
                (left, right) -> left,
                LinkedHashMap::new));
        OfficialRoutingEnvironment environment = obstacleRouter.prepare(features);
        List<RouteEdge> result = new ArrayList<>(sizedEdges);
        for (int index = 0; index < result.size(); index++) {
            RouteEdge edge = result.get(index);
            Set<String> exemptions = endpointFeatureIds(edge, nodesById);
            DepthProfileResult profile = depthPlanner.plan(
                    edge,
                    features,
                    parameters.getMinimumDepthM(),
                    parameters.getMaximumDepthM(),
                    exemptions);
            if (profile.isComplete()) continue;
            Set<String> failedUtilityIds = profile.getIssues().stream()
                    .map(issue -> issue.getCrossingId())
                    .filter(java.util.Objects::nonNull)
                    .map(value -> value.replaceFirst("#\\d+$", ""))
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            if (failedUtilityIds.isEmpty()) continue;
            RouteNode upstream = nodesById.get(edge.getUpstreamNodeId());
            RouteNode downstream = nodesById.get(edge.getDownstreamNodeId());
            if (upstream == null || downstream == null) continue;
            List<LineString> acceptedRoutes = result.stream()
                    .filter(other -> !other.getId().equals(edge.getId()))
                    .filter(other -> other.getCoordinates().size() >= 2)
                    .map(this::routeLine)
                    .collect(Collectors.toList());
            RoutePath rerouted = obstacleRouter.findAvoidingDepthConflicts(
                    upstream.getCoordinate().toCoordinate(),
                    downstream.getCoordinate().toCoordinate(),
                    edge.getDiameter(),
                    environment,
                    exemptions,
                    failedUtilityIds,
                    acceptedRoutes);
            if (rerouted == null || rerouted.lengthM() <= MIN_EDGE_LENGTH_M) continue;
            RouteEdge candidate = routeEdge(
                    edge.getId(),
                    edge.getUpstreamNodeId(),
                    edge.getDownstreamNodeId(),
                    rerouted,
                    edge.getFlowTph(),
                    edge.getDiameter());
            DepthProfileResult candidateProfile = depthPlanner.plan(
                    candidate,
                    features,
                    parameters.getMinimumDepthM(),
                    parameters.getMaximumDepthM(),
                    exemptions);
            if (candidateProfile.isComplete()) {
                result.set(index, withDepthProfile(candidate, candidateProfile));
            }
        }
        return result;
    }

    private Set<String> endpointFeatureIds(RouteEdge edge, Map<String, RouteNode> nodesById) {
        RouteNode upstream = nodesById.get(edge.getUpstreamNodeId());
        RouteNode downstream = nodesById.get(edge.getDownstreamNodeId());
        return java.util.stream.Stream.of(upstream, downstream)
                .filter(java.util.Objects::nonNull)
                .map(RouteNode::getTargetId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private RouteEdge withDepthProfile(RouteEdge edge, DepthProfileResult profile) {
        return new RouteEdge(
                edge.getId(),
                edge.getUpstreamNodeId(),
                edge.getDownstreamNodeId(),
                edge.getLengthM().doubleValue(),
                edge.getCoordinates(),
                edge.getSections(),
                edge.getFlowTph(),
                edge.getDiameter(),
                profile);
    }

    private LineString routeLine(RouteEdge edge) {
        return geometryFactory.createLineString(edge.getCoordinates().stream()
                .map(RouteCoordinate::toCoordinate)
                .toArray(Coordinate[]::new));
    }

    private List<TieInLoad> tieInLoads(List<RouteNode> nodes, List<RouteEdge> edges) {
        Map<String, BigDecimal> flowByRoot = edges.stream()
                .filter(edge -> edge.getFlowTph() != null)
                .collect(Collectors.toMap(
                        RouteEdge::getUpstreamNodeId,
                        RouteEdge::getFlowTph,
                        BigDecimal::add,
                        LinkedHashMap::new));
        return nodes.stream()
                .filter(RouteNode::isRoot)
                .filter(node -> node.getTargetId() != null)
                .map(node -> new TieInLoad(
                        node.getTargetId(),
                        node.getCoordinate(),
                        flowByRoot.getOrDefault(node.getId(), BigDecimal.ZERO)))
                .filter(load -> load.getAddedFlowTph().signum() > 0)
                .sorted(Comparator.comparing(TieInLoad::getTargetId)
                        .thenComparing(load -> load.getCoordinate().getXM())
                        .thenComparing(load -> load.getCoordinate().getYM()))
                .collect(Collectors.toList());
    }

    private Map<String, Integer> chamberIncidentCounts(List<ImportedOfficialFeature> features) {
        List<ImportedOfficialFeature> chambers = byType(features, "heat_chamber");
        List<ImportedOfficialFeature> segments = byType(features, "heat_network");
        Map<String, Integer> result = new HashMap<>();
        for (ImportedOfficialFeature chamber : chambers) {
            int count = (int) segments.stream()
                    .filter(segment -> segment.getMetricGeometry().distance(chamber.getMetricGeometry()) <= 0.01)
                    .count();
            result.put(chamber.getFeatureId(), count);
        }
        return result;
    }

    private List<Demand> demands(
            List<ImportedOfficialFeature> features,
            Map<String, ImportedOfficialFeature> featuresById) {
        return byType(features, "oks_connection_point").stream()
                .map(connection -> {
                    String linkedOks = connection.getAttributes().path("oks_id").asText();
                    String demandId = linkedOks.isBlank() ? connection.getFeatureId() : linkedOks;
                    ImportedOfficialFeature oks = featuresById.get(demandId);
                    BigDecimal flow = decimal(connection.getAttributes(), "flow_tph");
                    if (flow == null && oks != null) {
                        flow = decimal(oks.getAttributes(), "flow_tph");
                    }
                    return new Demand(
                            demandId,
                            connection.getFeatureId(),
                            connection.getMetricGeometry().getCoordinate(),
                            flow == null ? BigDecimal.ZERO : flow);
                })
                .sorted(Comparator.comparing(demand -> demand.id))
                .collect(Collectors.toList());
    }

    private BigDecimal decimal(JsonNode attributes, String field) {
        JsonNode value = attributes.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.decimalValue();
    }

    private int diameterFor(BigDecimal flowTph) {
        if (flowTph == null || flowTph.signum() <= 0) {
            return pipeCatalog.entries().get(0).getDiameter();
        }
        return pipeCatalog.minimumForFlow(flowTph)
                .map(PipeCatalogEntry::getDiameter)
                .orElseGet(() -> pipeCatalog.entries().get(pipeCatalog.entries().size() - 1).getDiameter());
    }

    private List<LineString> avoidanceLines(VariantDraft draft) {
        return draft.edges.stream()
                .filter(edge -> edge.getCoordinates().size() >= 2)
                .map(edge -> geometryFactory.createLineString(edge.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate)
                        .toArray(Coordinate[]::new)))
                .collect(Collectors.toList());
    }

    private boolean hasChamberCapacity(
            String chamberId,
            Map<String, Integer> base,
            Map<String, Integer> used) {
        return base.getOrDefault(chamberId, 0) + used.getOrDefault(chamberId, 0) < 4;
    }

    private Coordinate targetCoordinate(Coordinate origin, Geometry target) {
        if (target instanceof LineString) {
            return DistanceOp.nearestPoints(target.getFactory().createPoint(origin), target)[1];
        }
        return target.getCoordinate();
    }

    private Coordinate midpoint(Coordinate left, Coordinate right) {
        return new Coordinate((left.x + right.x) / 2.0, (left.y + right.y) / 2.0);
    }

    private RouteNode demandNode(Demand demand) {
        return new RouteNode(
                "demand:" + demand.id,
                "demand_connection",
                new RouteCoordinate(demand.coordinate.x, demand.coordinate.y),
                false,
                false,
                0,
                demand.connectionPointId);
    }

    private String targetKey(TieInCandidate candidate) {
        return candidate.getTargetType() + ":" + candidate.getTargetId();
    }

    private String edgeSignature(RouteVariant variant) {
        return variant.getEdges().stream()
                .map(edge -> edge.getUpstreamNodeId()
                        + "<-" + edge.getDownstreamNodeId()
                        + ":" + edge.getCoordinates().stream()
                                .map(coordinate -> coordinate.getXM() + "," + coordinate.getYM())
                                .collect(Collectors.joining(";")))
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private List<ImportedOfficialFeature> byType(List<ImportedOfficialFeature> features, String type) {
        return features.stream()
                .filter(feature -> type.equals(feature.getObjectType()))
                .collect(Collectors.toList());
    }

    private static class Demand {
        private final String id;
        private final String connectionPointId;
        private final Coordinate coordinate;
        private final BigDecimal flowTph;

        private Demand(String id, String connectionPointId, Coordinate coordinate, BigDecimal flowTph) {
            this.id = id;
            this.connectionPointId = connectionPointId;
            this.coordinate = coordinate;
            this.flowTph = flowTph;
        }
    }

    private static class Assignment {
        private final TieInCandidate candidate;
        private final Coordinate targetCoordinate;
        private final RouteNode root;
        private final RoutePath path;
        private final BigDecimal flowTph;
        private final int diameter;

        private Assignment(
                TieInCandidate candidate,
                Coordinate targetCoordinate,
                RouteNode root,
                RoutePath path,
                BigDecimal flowTph,
                int diameter) {
            this.candidate = candidate;
            this.targetCoordinate = targetCoordinate;
            this.root = root;
            this.path = path;
            this.flowTph = flowTph;
            this.diameter = diameter;
        }

        private String chamberTargetId() {
            return "heat_chamber".equals(candidate.getTargetType()) ? candidate.getTargetId() : null;
        }
    }

    private static class PairPlan {
        private final Demand left;
        private final Demand right;
        private final Coordinate junction;
        private final Assignment assignment;
        private final RoutePath leftPath;
        private final RoutePath rightPath;
        private final int leftDiameter;
        private final int rightDiameter;
        private final double totalLengthM;
        private final double savingM;

        private PairPlan(
                Demand left,
                Demand right,
                Coordinate junction,
                Assignment assignment,
                RoutePath leftPath,
                RoutePath rightPath,
                int leftDiameter,
                int rightDiameter,
                double totalLengthM,
                double savingM) {
            this.left = left;
            this.right = right;
            this.junction = junction;
            this.assignment = assignment;
            this.leftPath = leftPath;
            this.rightPath = rightPath;
            this.leftDiameter = leftDiameter;
            this.rightDiameter = rightDiameter;
            this.totalLengthM = totalLengthM;
            this.savingM = savingM;
        }
    }

    private static class DemandPair {
        private final Demand left;
        private final Demand right;
        private final double distanceM;

        private DemandPair(Demand left, Demand right, double distanceM) {
            this.left = left;
            this.right = right;
            this.distanceM = distanceM;
        }
    }

    private static class VariantDraft {
        private final Map<String, RouteNode> nodes = new LinkedHashMap<>();
        private final List<RouteEdge> edges = new ArrayList<>();
        private final List<RouteConnection> connections = new ArrayList<>();
        private final Map<String, Double> lengthByDemand = new HashMap<>();
        private final Map<String, String> targetByDemand = new HashMap<>();
        private int sharedPairCount;

        private VariantDraft copy() {
            VariantDraft copy = new VariantDraft();
            copy.nodes.putAll(nodes);
            copy.edges.addAll(edges);
            copy.connections.addAll(connections);
            copy.lengthByDemand.putAll(lengthByDemand);
            copy.targetByDemand.putAll(targetByDemand);
            copy.sharedPairCount = sharedPairCount;
            return copy;
        }

        private void addNode(RouteNode node) {
            nodes.putIfAbsent(node.getId(), node);
        }

        private void addEdge(RouteEdge edge) {
            edges.add(edge);
        }

        private void connected(Demand demand, double routeLength) {
            connections.add(new RouteConnection(
                    demand.id, demand.connectionPointId, demand.flowTph, "connected", null));
            lengthByDemand.put(demand.id, routeLength);
        }

        private void noRoute(Demand demand, String reason) {
            boolean alreadyPresent = connections.stream()
                    .anyMatch(connection -> connection.getDemandId().equals(demand.id));
            if (!alreadyPresent) {
                connections.add(new RouteConnection(
                        demand.id, demand.connectionPointId, demand.flowTph, "no_route", reason));
            }
        }
    }
}
