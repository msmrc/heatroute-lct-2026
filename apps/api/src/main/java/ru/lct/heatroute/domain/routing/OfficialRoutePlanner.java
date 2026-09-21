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
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialRoutePlanner.class);
    public static final String ALGORITHM_VERSION = "global-tree-7";
    private static final double MIN_EDGE_LENGTH_M = 0.01;
    private static final double LENGTH_EPSILON_M = 1e-9;
    private static final double MAX_SHARED_PAIR_DISTANCE_M = 500.0;
    private static final int MAX_SHARED_PAIR_CANDIDATES = 4;
    private static final long MAX_SHARED_TARGETS_PER_PAIR = 1;
    private static final int MAX_COSTED_ASSIGNMENT_CANDIDATES = 2;
    private static final int SHARED_NEIGHBOUR_FACTOR = 2;
    private static final int SHARED_SELECTION_BEAM_WIDTH = 24;
    private static final int MAX_GRAFT_EDGES_PER_DEMAND = 3;
    private static final int MAX_GRAFT_JUNCTIONS_PER_DEMAND = 0;
    private static final int MAX_WHOLE_TREE_ACCEPTED_MOVES = 3;

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
        return plan(features, topology, parameters, OfficialGeoJsonInspector.EXTENDED_INPUT_PROFILE);
    }

    public OfficialCalculationResult plan(
            List<ImportedOfficialFeature> features, TopologyAnalysis topology, OfficialRunParameters parameters,
            String inputProfile, RoutingFeatureSource source) {
        return plan(features, topology, parameters, inputProfile, source, true);
    }

    public OfficialCalculationResult plan(
            List<ImportedOfficialFeature> features,
            TopologyAnalysis topology,
            OfficialRunParameters parameters,
            String inputProfile) {
        return plan(features, topology, parameters, inputProfile, new InMemoryRoutingFeatureSource(features), false);
    }

    private OfficialCalculationResult plan(
            List<ImportedOfficialFeature> features, TopologyAnalysis topology, OfficialRunParameters parameters,
            String inputProfile, RoutingFeatureSource source, boolean windowed) {
        OfficialRunParameters validatedParameters = parameters.validated();
        boolean reconstructionRequired = !OfficialGeoJsonInspector.isBaselineInputProfile(inputProfile);
        Map<String, ImportedOfficialFeature> featuresById = features.stream().collect(Collectors.toMap(
                ImportedOfficialFeature::getFeatureId,
                feature -> feature,
                (left, right) -> left,
                LinkedHashMap::new));
        Map<String, Integer> chamberIncidentCounts = chamberIncidentCounts(features);
        OfficialRoutingEnvironment routingEnvironment = windowed
                ? obstacleRouter.prepare(features, source)
                : obstacleRouter.prepare(features);
        List<Demand> demands = demands(features, featuresById, routingEnvironment);
        Map<String, List<TieInCandidate>> candidatesByConnection = topology.getTieInCandidates().stream()
                .collect(Collectors.groupingBy(
                        TieInCandidate::getConnectionPointId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        VariantDraft independentDraft = coverageFirstIndependent(
                demands,
                candidatesByConnection,
                featuresById,
                chamberIncidentCounts,
                routingEnvironment,
                Collections.emptyMap(),
                RoutePreference.SHORTEST,
                "independent");
        routingEnvironment.logVisibilitySummary("independent");
        List<RouteVariant> variants = new ArrayList<>();
        RouteVariant independent = null;
        if (connectedCount(independentDraft) == demands.size()) {
            independent = finish(
                    "shortest", "shortest", independentDraft, features, validatedParameters,
                    reconstructionRequired, routingEnvironment);
            logVariantSummary(independent);
            variants.add(independent);
        }

        VariantDraft sharedDraft = coverageFirstShared(
                demands,
                candidatesByConnection,
                featuresById,
                chamberIncidentCounts,
                independentDraft.lengthByDemand,
                independentDraft.connectionCostByDemand,
                routingEnvironment);
        routingEnvironment.logVisibilitySummary("shared");
        RouteVariant shared = finish(
                "balanced", "balanced", sharedDraft, features, validatedParameters, reconstructionRequired, routingEnvironment);
        logVariantSummary(shared);
        if (sharedDraft.sharedPairCount > 0
                && (independent == null || !edgeSignature(independent).equals(edgeSignature(shared)))) {
            variants.add(shared);
        }

        if (connectedCount(independentDraft) == demands.size()) {
            VariantDraft diverseDraft = independent(
                    demands,
                    candidatesByConnection,
                    featuresById,
                    chamberIncidentCounts,
                    routingEnvironment,
                    independentDraft.targetByDemand,
                    RoutePreference.RIGHT,
                    "diverse");
            routingEnvironment.logVisibilitySummary("diverse");
            RouteVariant diverse = finish(
                    "cheapest", "cheapest", diverseDraft, features, validatedParameters,
                    reconstructionRequired, routingEnvironment);
            logVariantSummary(diverse);
            Set<String> existingSignatures = variants.stream()
                    .map(this::edgeSignature)
                    .collect(Collectors.toSet());
            if (diverse.getConnectedDemandCount() == demands.size()
                    && diverse.isValid()
                    && existingSignatures.add(edgeSignature(diverse))) {
                variants.add(diverse);
            }
        }

        Map<String, Integer> rankById = new HashMap<>();
        List<RouteVariant> rankable = variants.stream()
                .filter(RouteVariant::isValid)
                .filter(variant -> variant.getEconomics().isComplete())
                .sorted(Comparator.comparingLong(RouteVariant::getConnectedDemandCount).reversed()
                        .thenComparing(variant -> variant.getEconomics().getScore())
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
        return new OfficialCalculationResult(ALGORITHM_VERSION, inputProfile, demands.size(), variants, preferred);
    }

    private void logVariantSummary(RouteVariant variant) {
        if (variant.isValid()) {
            LOGGER.info(
                    "Route variant id={} connected={} length_m={} score={}",
                    variant.getId(),
                    variant.getConnectedDemandCount(),
                    variant.getTotalLengthM(),
                    variant.getEconomics().getScore());
            return;
        }
        LOGGER.warn(
                "Invalid route variant id={} connected={} issues={}",
                variant.getId(),
                variant.getConnectedDemandCount(),
                variant.getValidationIssues().stream()
                        .map(issue -> issue.getCode() + ":" + issue.getSubjectId() + ":" + issue.getMessage())
                        .collect(Collectors.joining(" | ")));
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

    private VariantDraft coverageFirstIndependent(
            List<Demand> demands,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            OfficialRoutingEnvironment routingEnvironment,
            Map<String, String> avoidedTargetByDemand,
            RoutePreference preference,
            String strategyPrefix) {
        VariantDraft first = independent(
                demands, candidatesByConnection, featuresById, chamberIncidentCounts,
                routingEnvironment, avoidedTargetByDemand, preference, strategyPrefix);
        Set<String> failedDemandIds = first.connections.stream()
                .filter(connection -> "no_route".equals(connection.getStatus()))
                .map(RouteConnection::getDemandId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (failedDemandIds.isEmpty()) {
            return first;
        }
        List<Demand> retryOrder = new ArrayList<>(demands);
        retryOrder.sort(Comparator
                .comparing((Demand demand) -> !failedDemandIds.contains(demand.id))
                .thenComparing(this::compareDemandIds));
        VariantDraft retry = independent(
                retryOrder, candidatesByConnection, featuresById, chamberIncidentCounts,
                routingEnvironment, avoidedTargetByDemand, preference, strategyPrefix);
        long firstConnected = connectedCount(first);
        long retryConnected = connectedCount(retry);
        if (retryConnected != firstConnected) {
            return retryConnected > firstConnected ? retry : first;
        }
        double firstLength = first.lengthByDemand.values().stream().mapToDouble(Double::doubleValue).sum();
        double retryLength = retry.lengthByDemand.values().stream().mapToDouble(Double::doubleValue).sum();
        return retryLength + LENGTH_EPSILON_M < firstLength ? retry : first;
    }

    private long connectedCount(VariantDraft draft) {
        return draft.connections.stream()
                .filter(connection -> "connected".equals(connection.getStatus()))
                .count();
    }

    private VariantDraft shared(
            List<Demand> demands,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            Map<String, Double> independentLengthByDemand,
            Map<String, BigDecimal> independentCostByDemand,
            OfficialRoutingEnvironment routingEnvironment,
            Set<String> directFirstDemandIds) {
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
        Map<String, Integer> neighboursByDemand = new HashMap<>();
        int evaluatedPairCandidates = 0;
        for (DemandPair pair : demandPairs) {
            if (evaluatedPairCandidates >= MAX_SHARED_PAIR_CANDIDATES) {
                break;
            }
            int leftNeighbours = neighboursByDemand.getOrDefault(pair.left.id, 0);
            int rightNeighbours = neighboursByDemand.getOrDefault(pair.right.id, 0);
            if (leftNeighbours >= SHARED_NEIGHBOUR_FACTOR
                    && rightNeighbours >= SHARED_NEIGHBOUR_FACTOR) {
                continue;
            }
            neighboursByDemand.merge(pair.left.id, 1, Integer::sum);
            neighboursByDemand.merge(pair.right.id, 1, Integer::sum);
            evaluatedPairCandidates++;
            PairPlan plan = bestPairPlan(
                    pair.left,
                    pair.right,
                    candidatesByConnection,
                    featuresById,
                    chamberIncidentCounts,
                    independentLengthByDemand,
                    independentCostByDemand,
                    routingEnvironment);
            if (plan != null && plan.savingCost.signum() > 0) {
                plans.add(plan);
            }
        }
        plans.sort(Comparator.comparing((PairPlan plan) -> plan.savingCost).reversed()
                .thenComparing(Comparator.comparingDouble((PairPlan plan) -> plan.savingM).reversed())
                .thenComparing(plan -> plan.left.id)
                .thenComparing(plan -> plan.right.id));

        VariantDraft draft = new VariantDraft();
        Set<String> paired = new HashSet<>();
        Map<String, Integer> usedChamberSlots = new HashMap<>();
        for (Demand demand : demands) {
            if (!directFirstDemandIds.contains(demand.id)) {
                continue;
            }
            Assignment assignment = chooseAssignment(
                    demand,
                    candidatesByConnection.getOrDefault(demand.connectionPointId, List.of()),
                    featuresById,
                    chamberIncidentCounts,
                    usedChamberSlots,
                    draft,
                    "shared:priority:" + demand.id,
                    routingEnvironment,
                    Collections.emptySet(),
                    RoutePreference.SHORTEST);
            if (assignment == null) {
                continue;
            }
            paired.add(demand.id);
            if (assignment.chamberTargetId() != null) {
                usedChamberSlots.merge(assignment.chamberTargetId(), 1, Integer::sum);
            }
            addDirect(draft, demand, assignment, "shared:priority");
        }
        PairSelection selection = selectSharedPlans(
                plans, draft, paired, usedChamberSlots, chamberIncidentCounts);
        draft = selection.draft;
        paired = selection.pairedDemandIds;
        usedChamberSlots = selection.usedChamberSlots;

        for (Demand demand : demands) {
            if (paired.contains(demand.id)) {
                continue;
            }
            Assignment direct = chooseAssignment(
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
            TreeAttachment graft = chooseTreeAttachment(
                    demand, draft, routingEnvironment);
            if (direct == null && graft == null) {
                draft.noRoute(
                        demand,
                        candidatesByConnection.getOrDefault(demand.connectionPointId, List.of()).isEmpty()
                                ? "NO_TIE_IN_CANDIDATE"
                                : "NO_NON_CROSSING_ROUTE");
                continue;
            }
            BigDecimal directTotalCost = direct == null
                    ? null
                    : totalCostAfterDirect(draft, demand, direct);
            if (graft != null && (directTotalCost == null
                    || graft.totalNetworkCost.compareTo(directTotalCost) <= 0)) {
                addTreeAttachment(draft, demand, graft);
                continue;
            }
            if (direct.chamberTargetId() != null) {
                usedChamberSlots.merge(direct.chamberTargetId(), 1, Integer::sum);
            }
            addDirect(draft, demand, direct, "shared");
        }
        return improveWholeTree(draft, demands, routingEnvironment);
    }

    private VariantDraft coverageFirstShared(
            List<Demand> demands,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            Map<String, Double> independentLengthByDemand,
            Map<String, BigDecimal> independentCostByDemand,
            OfficialRoutingEnvironment routingEnvironment) {
        VariantDraft first = shared(
                demands, candidatesByConnection, featuresById, chamberIncidentCounts,
                independentLengthByDemand, independentCostByDemand,
                routingEnvironment, Collections.emptySet());
        Set<String> failedDemandIds = first.connections.stream()
                .filter(connection -> "no_route".equals(connection.getStatus()))
                .map(RouteConnection::getDemandId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (failedDemandIds.isEmpty()) {
            return first;
        }
        VariantDraft retry = shared(
                demands, candidatesByConnection, featuresById, chamberIncidentCounts,
                independentLengthByDemand, independentCostByDemand,
                routingEnvironment, failedDemandIds);
        long firstConnected = connectedCount(first);
        long retryConnected = connectedCount(retry);
        if (retryConnected != firstConnected) {
            return retryConnected > firstConnected ? retry : first;
        }
        BigDecimal firstCost = totalNetworkConstructionCost(first);
        BigDecimal retryCost = totalNetworkConstructionCost(retry);
        if (firstCost != null && retryCost != null && firstCost.compareTo(retryCost) != 0) {
            return retryCost.compareTo(firstCost) < 0 ? retry : first;
        }
        double firstLength = first.edges.stream().mapToDouble(edge -> edge.getLengthM().doubleValue()).sum();
        double retryLength = retry.edges.stream().mapToDouble(edge -> edge.getLengthM().doubleValue()).sum();
        return retryLength + LENGTH_EPSILON_M < firstLength ? retry : first;
    }

    private PairPlan bestPairPlan(
            Demand left,
            Demand right,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            Map<String, Double> independentLengthByDemand,
            Map<String, BigDecimal> independentCostByDemand,
            OfficialRoutingEnvironment routingEnvironment) {
        Double independentLeft = independentLengthByDemand.get(left.id);
        Double independentRight = independentLengthByDemand.get(right.id);
        if (independentLeft == null || independentRight == null) {
            return null;
        }
        BigDecimal independentLeftCost = independentCostByDemand.get(left.id);
        BigDecimal independentRightCost = independentCostByDemand.get(right.id);
        if (independentLeftCost == null || independentRightCost == null) {
            return null;
        }
        BigDecimal independentCost = independentLeftCost.add(independentRightCost);
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
            int leftDiameter = diameterFor(left.flowTph);
            int rightDiameter = diameterFor(right.flowTph);
            BigDecimal trunkFlow = left.flowTph.add(right.flowTph);
            int trunkDiameter = diameterFor(trunkFlow);
            Coordinate targetCoordinateForCandidates = targetCoordinate(
                    midpoint(left.routingStart(), right.routingStart()), target.getMetricGeometry());
            for (Coordinate junction : sharedJunctionCandidates(
                    left, right, targetCoordinateForCandidates, trunkDiameter, routingEnvironment)) {
                Coordinate targetCoordinate = targetCoordinate(junction, target.getMetricGeometry());
                RoutePath leftPath = obstacleRouter.find(
                        left.routingStart(), junction, leftDiameter, routingEnvironment,
                        Collections.emptySet(), RoutePreference.LEFT);
                RoutePath rightPath = obstacleRouter.find(
                        right.routingStart(), junction, rightDiameter, routingEnvironment,
                        Collections.emptySet(), RoutePreference.RIGHT);
                RoutePath trunkPath = obstacleRouter.find(
                        junction, targetCoordinate, trunkDiameter, routingEnvironment,
                        Set.of(leftCandidate.getTargetId()), RoutePreference.SHORTEST);
                if (leftPath == null || rightPath == null || trunkPath == null) {
                    continue;
                }
                leftPath = left.withMandatoryEgress(leftPath);
                rightPath = right.withMandatoryEgress(rightPath);
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
                PairPlan preliminary = new PairPlan(
                        left, right, junction, assignment, leftPath, rightPath,
                        leftDiameter, rightDiameter, totalLength, saving, BigDecimal.ZERO);
                BigDecimal pairCost = sharedPairCost(preliminary);
                BigDecimal savingCost = independentCost.subtract(pairCost);
                PairPlan candidate = new PairPlan(
                        left, right, junction, assignment, leftPath, rightPath,
                        leftDiameter, rightDiameter, totalLength, saving, savingCost);
                if (best == null
                        || candidate.savingCost.compareTo(best.savingCost) > 0
                        || (candidate.savingCost.compareTo(best.savingCost) == 0
                                && candidate.savingM > best.savingM)
                        || (candidate.savingCost.compareTo(best.savingCost) == 0
                                && candidate.savingM == best.savingM
                                && targetKey(candidate.assignment.candidate)
                                        .compareTo(targetKey(best.assignment.candidate)) < 0)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Candidate branch points outside every containing OKS, ordered from most balanced. */
    private List<Coordinate> sharedJunctionCandidates(
            Demand left,
            Demand right,
            Coordinate target,
            int trunkDiameter,
            OfficialRoutingEnvironment routingEnvironment) {
        Coordinate start = left.routingStart();
        Coordinate end = right.routingStart();
        List<Coordinate> raw = new ArrayList<>();
        raw.add(geometricMedian(start, end, target));
        raw.add(new Coordinate((start.x + end.x + target.x) / 3.0, (start.y + end.y + target.y) / 3.0));
        double[] fractions = {0.5, 0.25, 0.75};
        for (double fraction : fractions) {
            raw.add(new Coordinate(
                    start.x + (end.x - start.x) * fraction,
                    start.y + (end.y - start.y) * fraction));
        }
        List<Coordinate> result = new ArrayList<>();
        for (Coordinate candidate : raw) {
            if (routingEnvironment.normalEgress(trunkDiameter, candidate).isEmpty()
                    && !routingEnvironment.pointInsideForbiddenClearance(trunkDiameter, candidate)
                    && result.stream().noneMatch(existing -> existing.distance(candidate) < 0.1)) {
                result.add(candidate);
            }
        }
        return result;
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
        List<TieInCandidate> eligibleCandidates = candidates.stream()
                .sorted(Comparator.comparing(TieInCandidate::getDistanceM)
                        .thenComparing(this::targetKey))
                .filter(candidate -> !avoidedTargets.contains(candidate.getTargetId()))
                .filter(candidate -> !"heat_chamber".equals(candidate.getTargetType())
                        || hasChamberCapacity(candidate.getTargetId(), chamberIncidentCounts, usedChamberSlots))
                .collect(Collectors.toList());
        Assignment best = null;
        BigDecimal bestMarginalCost = null;
        List<LineString> acceptedRoutes = avoidanceLines(draft);
        List<String> attemptedTargetIds = new ArrayList<>();
        Set<String> directBlockers = new java.util.TreeSet<>();
        int routedCandidateCount = 0;
        for (TieInCandidate candidate : eligibleCandidates) {
            ImportedOfficialFeature target = featuresById.get(candidate.getTargetId());
            if (target == null) {
                continue;
            }
            attemptedTargetIds.add(candidate.getTargetId());
            Coordinate coordinate = targetCoordinate(demand.coordinate, target.getMetricGeometry());
            RouteNode candidateRoot = rootNode(candidate, coordinate, chamberIncidentCounts, rootSuffix);
            RoutePath optimisticStraightPath = new RoutePath(
                    List.of(demand.coordinate, coordinate),
                    Collections.emptyList(),
                    demand.coordinate.distance(coordinate));
            Assignment optimisticAssignment = new Assignment(
                    candidate,
                    coordinate,
                    candidateRoot,
                    optimisticStraightPath,
                    demand.flowTph,
                    diameter);
            BigDecimal optimisticCost = marginalConnectionCost(demand, optimisticAssignment);
            if (bestMarginalCost != null && optimisticCost.compareTo(bestMarginalCost) >= 0) {
                continue;
            }
            if (best != null && routedCandidateCount >= MAX_COSTED_ASSIGNMENT_CANDIDATES) {
                break;
            }
            routedCandidateCount++;
            RoutePath path = obstacleRouter.find(
                    demand.routingStart(),
                    coordinate,
                    diameter,
                    routingEnvironment,
                    Set.of(candidate.getTargetId()),
                    preference,
                    acceptedRoutes);
            if (path == null) {
                directBlockers.addAll(obstacleRouter.directBlockingConstraintIds(
                        demand.routingStart(), coordinate, diameter, routingEnvironment,
                        Set.of(candidate.getTargetId()), acceptedRoutes));
                continue;
            }
            path = demand.withMandatoryEgress(path);
            Assignment assignment = new Assignment(
                    candidate,
                    coordinate,
                    candidateRoot,
                    path,
                    demand.flowTph,
                    diameter);
            if (!canAddDirect(draft, demand, assignment, rootSuffix)) {
                continue;
            }
            BigDecimal marginalCost = marginalConnectionCost(demand, assignment);
            if (best == null
                    || marginalCost.compareTo(bestMarginalCost) < 0
                    || (marginalCost.compareTo(bestMarginalCost) == 0
                            && assignment.path.lengthM() + LENGTH_EPSILON_M < best.path.lengthM())
                    || (marginalCost.compareTo(bestMarginalCost) == 0
                            && Math.abs(assignment.path.lengthM() - best.path.lengthM()) <= LENGTH_EPSILON_M
                            && targetKey(assignment.candidate).compareTo(targetKey(best.candidate)) < 0)) {
                best = assignment;
                bestMarginalCost = marginalCost;
            }
        }
        if (best == null) {
            draft.failureDiagnostics.put(demand.id, new RouteFailureDiagnostics(
                    eligibleCandidates.size(),
                    attemptedTargetIds.size(),
                    attemptedTargetIds,
                    new ArrayList<>(directBlockers),
                    OfficialObstacleRouter.MAXIMUM_SEARCH_CORRIDOR_M));
        }
        return best;
    }

    private BigDecimal marginalConnectionCost(Demand demand, Assignment assignment) {
        RouteNode demandNode = demandNode(demand);
        RouteEdge edge = routeEdge(
                "candidate-cost:" + demand.id,
                assignment.root.getId(),
                demandNode.getId(),
                assignment.path.reversed(),
                assignment.flowTph,
                assignment.diameter);
        return economicsCalculator.marginalConnectionCost(
                List.of(edge),
                List.of(assignment.root, demandNode));
    }

    private BigDecimal sharedPairCost(PairPlan plan) {
        VariantDraft draft = new VariantDraft();
        addSharedPair(draft, plan);
        return economicsCalculator.marginalConnectionCost(
                draft.edges,
                new ArrayList<>(draft.nodes.values()));
    }

    private TreeAttachment chooseTreeAttachment(
            Demand demand,
            VariantDraft draft,
            OfficialRoutingEnvironment routingEnvironment) {
        return chooseTreeAttachment(demand, draft, routingEnvironment, false);
    }

    private TreeAttachment chooseTreeAttachment(
            Demand demand,
            VariantDraft draft,
            OfficialRoutingEnvironment routingEnvironment,
            boolean boundedWholeTreeSearch) {
        if (draft.edges.isEmpty()) {
            return null;
        }
        TreeAttachment best = null;
        List<RouteNode> existingJunctions = draft.nodes.values().stream()
                .filter(RouteNode::isChamber)
                .filter(node -> !node.isRoot())
                .sorted(Comparator
                        .comparingDouble((RouteNode node) -> node.getCoordinate().toCoordinate()
                                .distance(demand.routingStart()))
                        .thenComparing(RouteNode::getId))
                .limit(boundedWholeTreeSearch ? 0 : MAX_GRAFT_JUNCTIONS_PER_DEMAND)
                .collect(Collectors.toList());
        for (RouteNode junction : existingJunctions) {
            List<LineString> acceptedRoutes = draft.edges.stream()
                    .filter(edge -> !edge.getUpstreamNodeId().equals(junction.getId()))
                    .filter(edge -> !edge.getDownstreamNodeId().equals(junction.getId()))
                    .filter(edge -> edge.getCoordinates().size() >= 2)
                    .map(this::routeLine)
                    .collect(Collectors.toList());
            RoutePath branch = obstacleRouter.find(
                    demand.routingStart(),
                    junction.getCoordinate().toCoordinate(),
                    diameterFor(demand.flowTph),
                    routingEnvironment,
                    Collections.emptySet(),
                    RoutePreference.SHORTEST,
                    acceptedRoutes);
            if (branch == null) {
                continue;
            }
            branch = demand.withMandatoryEgress(branch);
            if (branch.lengthM() <= MIN_EDGE_LENGTH_M) {
                continue;
            }
            TreeAttachment candidate = costTreeAttachment(
                    demand, draft, junction, null, null, branch, routingEnvironment);
            best = betterTreeAttachment(best, candidate);
        }

        List<RouteEdge> nearestEdges = draft.edges.stream()
                .filter(edge -> edge.getCoordinates().size() >= 2)
                .sorted(Comparator
                        .comparingDouble((RouteEdge edge) -> routeLine(edge)
                                .distance(geometryFactory.createPoint(demand.routingStart())))
                        .thenComparing(RouteEdge::getId))
                .limit(boundedWholeTreeSearch ? 2 : MAX_GRAFT_EDGES_PER_DEMAND)
                .collect(Collectors.toList());
        for (RouteEdge targetEdge : nearestEdges) {
            LineString targetLine = routeLine(targetEdge);
            LengthIndexedLine indexed = new LengthIndexedLine(targetLine);
            double length = targetLine.getLength();
            List<Double> indexes = boundedWholeTreeSearch
                    ? List.of(indexed.project(demand.routingStart()))
                    : List.of(indexed.project(demand.routingStart()), length * 0.50);
            List<Coordinate> tried = new ArrayList<>();
            for (double index : indexes) {
                if (index <= MIN_EDGE_LENGTH_M || length - index <= MIN_EDGE_LENGTH_M) {
                    continue;
                }
                Coordinate requestedCut = indexed.extractPoint(index);
                if (tried.stream().anyMatch(point -> point.distance(requestedCut) <= 0.10)) {
                    continue;
                }
                tried.add(requestedCut);
                if (routingEnvironment.pointInsideForbiddenClearance(
                                diameterFor(demand.flowTph), requestedCut)
                        || liesOnSpecialSection(targetEdge, requestedCut)
                        || liesOnTerminalMandatoryEgress(
                                draft, targetEdge, requestedCut, routingEnvironment)) {
                    continue;
                }
                RoutePath.Split split = edgePath(targetEdge).splitAt(requestedCut);
                if (split == null
                        || split.upstream().lengthM() <= MIN_EDGE_LENGTH_M
                        || split.downstream().lengthM() <= MIN_EDGE_LENGTH_M) {
                    continue;
                }
                List<LineString> acceptedRoutes = draft.edges.stream()
                        .filter(edge -> !edge.getId().equals(targetEdge.getId()))
                        .filter(edge -> edge.getCoordinates().size() >= 2)
                        .map(this::routeLine)
                        .collect(Collectors.toList());
                RoutePath branch = obstacleRouter.find(
                        demand.routingStart(),
                        split.coordinate(),
                        diameterFor(demand.flowTph),
                        routingEnvironment,
                        Collections.emptySet(),
                        RoutePreference.SHORTEST,
                        acceptedRoutes);
                if (branch == null) {
                    continue;
                }
                branch = demand.withMandatoryEgress(branch);
                if (branch.lengthM() <= MIN_EDGE_LENGTH_M) {
                    continue;
                }
                RouteNode junction = new RouteNode(
                        "junction:graft:" + demand.id + ":" + targetEdge.getId(),
                        "new_branch_chamber",
                        new RouteCoordinate(split.coordinate().x, split.coordinate().y),
                        true,
                        false,
                        0,
                        null);
                TreeAttachment candidate = costTreeAttachment(
                        demand, draft, junction, targetEdge, split, branch, routingEnvironment);
                best = betterTreeAttachment(best, candidate);
            }
        }
        return best;
    }

    private boolean liesOnTerminalMandatoryEgress(
            VariantDraft draft,
            RouteEdge edge,
            Coordinate coordinate,
            OfficialRoutingEnvironment routingEnvironment) {
        RouteNode downstream = draft.nodes.get(edge.getDownstreamNodeId());
        if (downstream == null || !"demand_connection".equals(downstream.getNodeType())) {
            return false;
        }
        int diameter = edge.getDiameter() == null ? 50 : edge.getDiameter();
        OfficialRouteGeometryRules.NormalEgress egress = routingEnvironment.normalEgress(
                        diameter, downstream.getCoordinate().toCoordinate())
                .orElse(null);
        if (egress == null) {
            return false;
        }
        LineString mandatoryLeg = geometryFactory.createLineString(
                new Coordinate[] {egress.start(), egress.exit()});
        return mandatoryLeg.distance(geometryFactory.createPoint(coordinate))
                <= OfficialRouteGeometryRules.EPSILON_M;
    }

    /**
     * Deterministic whole-tree local search. Each terminal demand is detached in turn, redundant
     * branch chambers are normalized away, and the demand is routed against every eligible part of
     * the remaining forest. The accepted tuple is strictly decreasing, so the pass cannot oscillate.
     */
    private VariantDraft improveWholeTree(
            VariantDraft initial,
            List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment) {
        VariantDraft current = initial;
        boolean improved;
        int acceptedMoves = 0;
        do {
            improved = false;
            BigDecimal currentCost = totalNetworkConstructionCost(current);
            if (currentCost == null) {
                return current;
            }
            int currentTieIns = tieInRayCount(current);
            double currentLength = totalRouteLength(current);
            int currentNewChambers = newBranchChamberCount(current);
            for (Demand demand : demands) {
                VariantDraft withoutDemand = detachDemandAndNormalize(current, demand);
                if (withoutDemand == null || withoutDemand.edges.isEmpty()) {
                    continue;
                }
                TreeAttachment attachment = chooseTreeAttachment(
                        demand, withoutDemand, routingEnvironment, true);
                if (attachment == null) {
                    continue;
                }
                VariantDraft candidate = withoutDemand.copy();
                addTreeAttachment(candidate, demand, attachment);
                if (!isStructurallyValid(candidate)) {
                    continue;
                }
                BigDecimal candidateCost = totalNetworkConstructionCost(candidate);
                if (candidateCost == null) {
                    continue;
                }
                int costComparison = candidateCost.compareTo(currentCost);
                int candidateTieIns = tieInRayCount(candidate);
                double candidateLength = totalRouteLength(candidate);
                int candidateNewChambers = newBranchChamberCount(candidate);
                boolean better = costComparison < 0
                        || (costComparison == 0 && candidateTieIns < currentTieIns)
                        || (costComparison == 0 && candidateTieIns == currentTieIns
                                && candidateLength + LENGTH_EPSILON_M < currentLength)
                        || (costComparison == 0 && candidateTieIns == currentTieIns
                                && Math.abs(candidateLength - currentLength) <= LENGTH_EPSILON_M
                                && candidateNewChambers < currentNewChambers);
                if (better) {
                    current = candidate;
                    acceptedMoves++;
                    improved = true;
                    break;
                }
            }
        } while (improved && acceptedMoves < MAX_WHOLE_TREE_ACCEPTED_MOVES);
        return current;
    }

    private VariantDraft detachDemandAndNormalize(VariantDraft source, Demand demand) {
        String demandNodeId = "demand:" + demand.id;
        List<RouteEdge> incoming = source.edges.stream()
                .filter(edge -> edge.getDownstreamNodeId().equals(demandNodeId))
                .collect(Collectors.toList());
        boolean hasChildren = source.edges.stream()
                .anyMatch(edge -> edge.getUpstreamNodeId().equals(demandNodeId));
        if (incoming.size() != 1 || hasChildren) {
            return null;
        }
        VariantDraft result = source.copy();
        result.edges.removeIf(edge -> edge.getId().equals(incoming.get(0).getId()));
        result.nodes.remove(demandNodeId);
        result.connections.removeIf(connection -> connection.getDemandId().equals(demand.id));
        result.lengthByDemand.remove(demand.id);
        result.connectionCostByDemand.remove(demand.id);
        result.targetByDemand.remove(demand.id);
        result.failureDiagnostics.remove(demand.id);
        normalizeRedundantBranchChambers(result);
        removeUnusedRoots(result);
        return result;
    }

    private void normalizeRedundantBranchChambers(VariantDraft draft) {
        boolean changed;
        do {
            changed = false;
            List<RouteNode> candidates = draft.nodes.values().stream()
                    .filter(node -> !node.isRoot())
                    .filter(node -> "new_branch_chamber".equals(node.getNodeType()))
                    .filter(node -> node.getTargetId() == null)
                    .sorted(Comparator.comparing(RouteNode::getId))
                    .collect(Collectors.toList());
            for (RouteNode chamber : candidates) {
                List<RouteEdge> incoming = draft.edges.stream()
                        .filter(edge -> edge.getDownstreamNodeId().equals(chamber.getId()))
                        .collect(Collectors.toList());
                List<RouteEdge> outgoing = draft.edges.stream()
                        .filter(edge -> edge.getUpstreamNodeId().equals(chamber.getId()))
                        .collect(Collectors.toList());
                if (outgoing.isEmpty() && incoming.size() == 1) {
                    draft.edges.removeIf(edge -> edge.getId().equals(incoming.get(0).getId()));
                    draft.nodes.remove(chamber.getId());
                    changed = true;
                    break;
                }
                if (incoming.size() == 1 && outgoing.size() == 1) {
                    RouteEdge contracted = contractThroughChamber(
                            chamber, incoming.get(0), outgoing.get(0));
                    if (contracted == null) {
                        continue;
                    }
                    Set<String> replacedIds = Set.of(incoming.get(0).getId(), outgoing.get(0).getId());
                    draft.edges.removeIf(edge -> replacedIds.contains(edge.getId()));
                    draft.nodes.remove(chamber.getId());
                    draft.addEdge(contracted);
                    changed = true;
                    break;
                }
            }
        } while (changed);
    }

    private RouteEdge contractThroughChamber(
            RouteNode chamber, RouteEdge upstream, RouteEdge downstream) {
        RoutePath upstreamPath = edgePath(upstream);
        RoutePath downstreamPath = edgePath(downstream);
        List<Coordinate> coordinates = new ArrayList<>(upstreamPath.coordinates());
        List<Coordinate> downstreamCoordinates = downstreamPath.coordinates();
        if (coordinates.isEmpty() || downstreamCoordinates.isEmpty()
                || coordinates.get(coordinates.size() - 1).distance(downstreamCoordinates.get(0))
                        > OfficialRouteGeometryRules.EPSILON_M) {
            return null;
        }
        for (int index = 1; index < downstreamCoordinates.size(); index++) {
            coordinates.add(new Coordinate(downstreamCoordinates.get(index)));
        }
        List<RouteSection> sections = new ArrayList<>(upstreamPath.sections());
        sections.addAll(downstreamPath.sections());
        RoutePath contracted = new RoutePath(
                coordinates,
                sections,
                upstreamPath.lengthM() + downstreamPath.lengthM());
        return routeEdge(
                "optimized:contract:" + chamber.getId(),
                upstream.getUpstreamNodeId(),
                downstream.getDownstreamNodeId(),
                contracted,
                downstream.getFlowTph(),
                downstream.getDiameter());
    }

    private void removeUnusedRoots(VariantDraft draft) {
        Set<String> usedNodeIds = draft.edges.stream()
                .flatMap(edge -> java.util.stream.Stream.of(
                        edge.getUpstreamNodeId(), edge.getDownstreamNodeId()))
                .collect(Collectors.toSet());
        draft.nodes.entrySet().removeIf(entry -> entry.getValue().isRoot()
                && !usedNodeIds.contains(entry.getKey()));
    }

    private int tieInRayCount(VariantDraft draft) {
        Set<String> rootIds = draft.nodes.values().stream()
                .filter(RouteNode::isRoot)
                .map(RouteNode::getId)
                .collect(Collectors.toSet());
        return (int) draft.edges.stream()
                .filter(edge -> rootIds.contains(edge.getUpstreamNodeId()))
                .count();
    }

    private double totalRouteLength(VariantDraft draft) {
        return draft.edges.stream()
                .mapToDouble(edge -> edge.getLengthM().doubleValue())
                .sum();
    }

    private int newBranchChamberCount(VariantDraft draft) {
        return (int) draft.nodes.values().stream()
                .filter(node -> "new_branch_chamber".equals(node.getNodeType()))
                .count();
    }

    private boolean liesOnSpecialSection(RouteEdge edge, Coordinate coordinate) {
        return edge.getSections().stream()
                .filter(section -> "special".equals(section.getKind()))
                .filter(section -> section.getCoordinates().size() >= 2)
                .map(section -> geometryFactory.createLineString(section.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate)
                        .toArray(Coordinate[]::new)))
                .anyMatch(line -> line.distance(geometryFactory.createPoint(coordinate))
                        <= OfficialRouteGeometryRules.EPSILON_M);
    }

    private TreeAttachment costTreeAttachment(
            Demand demand,
            VariantDraft draft,
            RouteNode junction,
            RouteEdge targetEdge,
            RoutePath.Split split,
            RoutePath branch,
            OfficialRoutingEnvironment routingEnvironment) {
        TreeAttachment candidate = new TreeAttachment(
                junction, targetEdge, split, branch, null,
                junction.getId() + "|" + (targetEdge == null ? "node" : targetEdge.getId()));
        VariantDraft simulation = draft.copy();
        addTreeAttachment(simulation, demand, candidate);
        if (!isStructurallyValid(simulation)) {
            return null;
        }
        BigDecimal cost = totalNetworkConstructionCost(simulation);
        return cost == null ? null : candidate.withTotalNetworkCost(cost);
    }

    private boolean isFinalGeometryValid(
            VariantDraft draft,
            OfficialRoutingEnvironment routingEnvironment) {
        Map<String, BigDecimal> demandFlowByNode = draft.connections.stream()
                .filter(connection -> "connected".equals(connection.getStatus()))
                .collect(Collectors.toMap(
                        connection -> "demand:" + connection.getDemandId(),
                        RouteConnection::getFlowTph,
                        BigDecimal::add,
                        LinkedHashMap::new));
        NetworkSizingResult sizing = sizeRoutes(draft.edges, demandFlowByNode);
        if (!sizing.isValid()) {
            return false;
        }
        List<RouteNode> nodes = new ArrayList<>(draft.nodes.values());
        List<RouteEdge> sizedEdges = applySizing(draft.edges, sizing);
        List<ImportedOfficialFeature> features = featuresForEdges(
                Collections.emptyList(), sizedEdges, routingEnvironment);
        return validator.validate(nodes, sizedEdges, features).isEmpty();
    }

    private TreeAttachment betterTreeAttachment(TreeAttachment current, TreeAttachment candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null
                || candidate.totalNetworkCost.compareTo(current.totalNetworkCost) < 0
                || (candidate.totalNetworkCost.compareTo(current.totalNetworkCost) == 0
                        && candidate.branchPath.lengthM() + LENGTH_EPSILON_M
                                < current.branchPath.lengthM())
                || (candidate.totalNetworkCost.compareTo(current.totalNetworkCost) == 0
                        && Math.abs(candidate.branchPath.lengthM() - current.branchPath.lengthM())
                                <= LENGTH_EPSILON_M
                        && candidate.signature.compareTo(current.signature) < 0)) {
            return candidate;
        }
        return current;
    }

    private BigDecimal totalCostAfterDirect(
            VariantDraft draft, Demand demand, Assignment assignment) {
        VariantDraft simulation = draft.copy();
        addDirect(simulation, demand, assignment, "shared:comparison");
        if (!isStructurallyValid(simulation)) {
            return null;
        }
        return totalNetworkConstructionCost(simulation);
    }

    private BigDecimal totalNetworkConstructionCost(VariantDraft draft) {
        Map<String, BigDecimal> demandFlowByNode = draft.connections.stream()
                .filter(connection -> "connected".equals(connection.getStatus()))
                .collect(Collectors.toMap(
                        connection -> "demand:" + connection.getDemandId(),
                        RouteConnection::getFlowTph,
                        BigDecimal::add,
                        LinkedHashMap::new));
        NetworkSizingResult sizing = sizeRoutes(draft.edges, demandFlowByNode);
        if (!sizing.isValid()) {
            return null;
        }
        return economicsCalculator.marginalConnectionCost(
                applySizing(draft.edges, sizing),
                new ArrayList<>(draft.nodes.values()));
    }

    /**
     * Selects a compatible set of shared branches against the monetary objective. The bounded
     * beam keeps alternative matchings alive, unlike the former greedy pass where one attractive
     * pair could permanently block a cheaper complete combination.
     */
    private PairSelection selectSharedPlans(
            List<PairPlan> plans,
            VariantDraft baseDraft,
            Set<String> basePaired,
            Map<String, Integer> baseUsedChamberSlots,
            Map<String, Integer> chamberIncidentCounts) {
        // A single pair is only the seed. All remaining demands are evaluated against the growing
        // tree below, otherwise a maximum matching would freeze the result into many small trunks.
        int maximumSeededDemandCount = basePaired.size() + 2;
        List<PairSelection> beam = new ArrayList<>();
        beam.add(new PairSelection(
                baseDraft.copy(),
                new HashSet<>(basePaired),
                new HashMap<>(baseUsedChamberSlots),
                BigDecimal.ZERO,
                ""));
        for (PairPlan plan : plans) {
            List<PairSelection> expanded = new ArrayList<>(beam);
            for (PairSelection state : beam) {
                if (state.pairedDemandIds.size() >= maximumSeededDemandCount) {
                    continue;
                }
                if (state.pairedDemandIds.contains(plan.left.id)
                        || state.pairedDemandIds.contains(plan.right.id)) {
                    continue;
                }
                String chamberTargetId = plan.assignment.chamberTargetId();
                if (chamberTargetId != null
                        && !hasChamberCapacity(
                                chamberTargetId, chamberIncidentCounts, state.usedChamberSlots)) {
                    continue;
                }
                if (!canAddSharedPair(state.draft, plan)) {
                    continue;
                }
                VariantDraft nextDraft = state.draft.copy();
                addSharedPair(nextDraft, plan);
                Set<String> nextPaired = new HashSet<>(state.pairedDemandIds);
                nextPaired.add(plan.left.id);
                nextPaired.add(plan.right.id);
                Map<String, Integer> nextSlots = new HashMap<>(state.usedChamberSlots);
                if (chamberTargetId != null) {
                    nextSlots.merge(chamberTargetId, 1, Integer::sum);
                }
                String nextSignature = state.signature + "|" + plan.left.id + ":" + plan.right.id
                        + "@" + targetKey(plan.assignment.candidate);
                expanded.add(new PairSelection(
                        nextDraft,
                        nextPaired,
                        nextSlots,
                        state.savingCost.add(plan.savingCost),
                        nextSignature));
            }
            Map<String, PairSelection> bestBySignature = new LinkedHashMap<>();
            expanded.stream()
                    .sorted(pairSelectionComparator())
                    .forEach(state -> bestBySignature.putIfAbsent(state.signature, state));
            beam = bestBySignature.values().stream()
                    .sorted(pairSelectionComparator())
                    .limit(SHARED_SELECTION_BEAM_WIDTH)
                    .collect(Collectors.toList());
        }
        return beam.stream().min(pairSelectionComparator()).orElseThrow();
    }

    private Comparator<PairSelection> pairSelectionComparator() {
        return Comparator.comparing((PairSelection state) -> state.savingCost).reversed()
                .thenComparing(Comparator.comparingInt(
                        (PairSelection state) -> state.pairedDemandIds.size()).reversed())
                .thenComparing(state -> state.signature);
    }

    private Coordinate midpoint(Coordinate left, Coordinate right) {
        return new Coordinate((left.x + right.x) / 2.0, (left.y + right.y) / 2.0);
    }

    /** Weiszfeld iteration; for three terminals this converges to the Fermat/geometric-median seed. */
    private Coordinate geometricMedian(Coordinate first, Coordinate second, Coordinate third) {
        Coordinate current = new Coordinate(
                (first.x + second.x + third.x) / 3.0,
                (first.y + second.y + third.y) / 3.0);
        List<Coordinate> terminals = List.of(first, second, third);
        for (int iteration = 0; iteration < 24; iteration++) {
            double weightedX = 0.0;
            double weightedY = 0.0;
            double weightSum = 0.0;
            Coordinate coincident = null;
            for (Coordinate terminal : terminals) {
                double distance = current.distance(terminal);
                if (distance < 1e-6) {
                    coincident = terminal;
                    break;
                }
                double weight = 1.0 / distance;
                weightedX += terminal.x * weight;
                weightedY += terminal.y * weight;
                weightSum += weight;
            }
            Coordinate next = coincident == null
                    ? new Coordinate(weightedX / weightSum, weightedY / weightSum)
                    : new Coordinate(coincident);
            if (current.distance(next) < 1e-4) {
                return next;
            }
            current = next;
        }
        return current;
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
        draft.connectionCostByDemand.put(demand.id, marginalConnectionCost(demand, assignment));
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

    private void addTreeAttachment(
            VariantDraft draft, Demand demand, TreeAttachment attachment) {
        RouteNode demandNode = demandNode(demand);
        draft.addNode(demandNode);
        draft.addNode(attachment.junction);
        if (attachment.targetEdge != null) {
            draft.edges.removeIf(edge -> edge.getId().equals(attachment.targetEdge.getId()));
            String splitPrefix = attachment.targetEdge.getId() + ":graft:" + demand.id;
            draft.addEdge(routeEdge(
                    splitPrefix + ":upstream",
                    attachment.targetEdge.getUpstreamNodeId(),
                    attachment.junction.getId(),
                    attachment.split.upstream(),
                    attachment.targetEdge.getFlowTph(),
                    attachment.targetEdge.getDiameter()));
            draft.addEdge(routeEdge(
                    splitPrefix + ":downstream",
                    attachment.junction.getId(),
                    attachment.targetEdge.getDownstreamNodeId(),
                    attachment.split.downstream(),
                    attachment.targetEdge.getFlowTph(),
                    attachment.targetEdge.getDiameter()));
        }
        draft.addEdge(routeEdge(
                "shared:graft:branch:" + demand.id,
                attachment.junction.getId(),
                demandNode.getId(),
                attachment.branchPath.reversed(),
                demand.flowTph,
                diameterFor(demand.flowTph)));
        draft.sharedPairCount++;
        draft.connected(demand, attachment.branchPath.lengthM());
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

    private RoutePath edgePath(RouteEdge edge) {
        return new RoutePath(
                edge.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate)
                        .collect(Collectors.toList()),
                edge.getSections(),
                edge.getLengthM().doubleValue());
    }

    private RouteVariant finish(
            String id,
            String strategy,
            VariantDraft draft,
            List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters,
            boolean reconstructionRequired,
            OfficialRoutingEnvironment routingEnvironment) {
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
        List<RouteEdge> sizedEdges = ensureMandatoryEgress(
                nodes, applySizing(draft.edges, initialSizing), features, routingEnvironment);
        List<ImportedOfficialFeature> routeFeatures = featuresForEdges(features, sizedEdges, routingEnvironment);
        List<RouteEdge> depthReroutedEdges = parameters.isDepthEnabled()
                ? rerouteDepthConflicts(nodes, sizedEdges, routeFeatures, parameters)
                : sizedEdges;
        NetworkSizingResult sizing = sizeRoutes(depthReroutedEdges, demandFlowByNode);
        List<RouteEdge> finalSizedEdges = ensureMandatoryEgress(
                nodes, applySizing(depthReroutedEdges, sizing), features, routingEnvironment);
        List<RouteEdge> profiledEdges = parameters.isDepthEnabled()
                ? withDepthProfiles(nodes, finalSizedEdges, routeFeatures, parameters)
                : finalSizedEdges;
        List<RouteValidationIssue> issues = validator.validate(nodes, profiledEdges,
                featuresForEdges(features, profiledEdges, routingEnvironment));
        // The amended official contract explicitly excludes reconstruction of existing assets.
        ExistingNetworkReconstructionResult reconstruction = ExistingNetworkReconstructionResult.empty();
        VariantEconomics economics = economicsCalculator.calculate(
                nodes, profiledEdges, draft.connections, reconstruction, reconstructionRequired);
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

    /** Rebuilds a terminal OKS leg whenever final sizing increases the required DU clearance. */
    private List<RouteEdge> ensureMandatoryEgress(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            OfficialRoutingEnvironment routingEnvironment) {
        Map<String, RouteNode> nodesById = nodes.stream().collect(Collectors.toMap(
                RouteNode::getId,
                node -> node,
                (left, right) -> left,
                LinkedHashMap::new));
        List<RouteEdge> result = new ArrayList<>();
        List<LineString> acceptedRoutes = new ArrayList<>();
        for (RouteEdge edge : edges) {
            RouteNode upstream = nodesById.get(edge.getUpstreamNodeId());
            RouteNode downstream = nodesById.get(edge.getDownstreamNodeId());
            if (upstream == null
                    || downstream == null
                    || edge.getDiameter() == null) {
                result.add(edge);
                if (edge.getCoordinates().size() >= 2) {
                    acceptedRoutes.add(routeLine(edge));
                }
                continue;
            }
            boolean demandEdge = "demand_connection".equals(downstream.getNodeType());
            OfficialRouteGeometryRules.NormalEgress egress = demandEdge
                    ? routingEnvironment.normalEgress(
                                    edge.getDiameter(), downstream.getCoordinate().toCoordinate())
                            .orElse(null)
                    : null;
            List<Coordinate> routePrefix = edge.getCoordinates().stream()
                    .map(RouteCoordinate::toCoordinate)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (egress != null && !routePrefix.isEmpty()) {
                routePrefix.remove(routePrefix.size() - 1);
            }
            Set<String> exemptions = endpointFeatureIds(edge, nodesById);
            boolean validAtFinalDiameter = obstacleRouter.lineAllowed(
                    routePrefix,
                    edge.getDiameter(),
                    routingEnvironment,
                    exemptions,
                    acceptedRoutes);
            if (validAtFinalDiameter && (egress == null || hasMandatoryEgress(edge, egress))) {
                result.add(edge);
                acceptedRoutes.add(routeLine(edge));
                continue;
            }
            RoutePath rerouted = obstacleRouter.find(
                    upstream.getCoordinate().toCoordinate(),
                    egress == null ? downstream.getCoordinate().toCoordinate() : egress.exit(),
                    edge.getDiameter(),
                    routingEnvironment,
                    exemptions,
                    RoutePreference.SHORTEST,
                    acceptedRoutes);
            if (rerouted == null) {
                result.add(edge);
                acceptedRoutes.add(routeLine(edge));
                continue;
            }
            RoutePath finalPath = egress == null
                    ? rerouted
                    : rerouted.withMandatorySuffix(downstream.getCoordinate().toCoordinate());
            RouteEdge finalEdge = routeEdge(
                    edge.getId(),
                    edge.getUpstreamNodeId(),
                    edge.getDownstreamNodeId(),
                    finalPath,
                    edge.getFlowTph(),
                    edge.getDiameter());
            result.add(finalEdge);
            acceptedRoutes.add(routeLine(finalEdge));
        }
        return result;
    }

    private boolean hasMandatoryEgress(
            RouteEdge edge,
            OfficialRouteGeometryRules.NormalEgress expected) {
        List<RouteCoordinate> coordinates = edge.getCoordinates();
        if (coordinates.size() < 2) {
            return false;
        }
        Coordinate endpoint = coordinates.get(coordinates.size() - 1).toCoordinate();
        Coordinate adjacent = coordinates.get(coordinates.size() - 2).toCoordinate();
        Coordinate expectedExit = expected.exit();
        double requiredLength = endpoint.distance(expectedExit);
        double actualLength = endpoint.distance(adjacent);
        if (actualLength + 2 * OfficialRouteGeometryRules.EPSILON_M < requiredLength) {
            return false;
        }
        double normalX = expectedExit.x - endpoint.x;
        double normalY = expectedExit.y - endpoint.y;
        double actualX = adjacent.x - endpoint.x;
        double actualY = adjacent.y - endpoint.y;
        return Math.abs(normalX * actualY - normalY * actualX)
                <= Math.max(
                        2 * OfficialRouteGeometryRules.EPSILON_M * actualLength,
                        requiredLength * actualLength * 1e-4);
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

    private List<ImportedOfficialFeature> featuresForEdges(
            List<ImportedOfficialFeature> core,
            List<RouteEdge> edges,
            OfficialRoutingEnvironment environment) {
        Map<String, ImportedOfficialFeature> result = new LinkedHashMap<>();
        core.forEach(feature -> result.put(feature.getFeatureId(), feature));
        for (RouteEdge edge : edges) {
            if (edge.getCoordinates().size() < 2) continue;
            Coordinate start = edge.getCoordinates().get(0).toCoordinate();
            Coordinate end = edge.getCoordinates().get(edge.getCoordinates().size() - 1).toCoordinate();
            environment.featuresInWindow(start, end)
                    .forEach(feature -> result.put(feature.getFeatureId(), feature));
        }
        return new ArrayList<>(result.values());
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
            OfficialRouteGeometryRules.NormalEgress egress = environment.normalEgress(
                            edge.getDiameter(), downstream.getCoordinate().toCoordinate())
                    .orElse(null);
            Coordinate rerouteEnd = egress == null
                    ? downstream.getCoordinate().toCoordinate()
                    : egress.exit();
            RoutePath rerouted = obstacleRouter.findAvoidingDepthConflicts(
                    upstream.getCoordinate().toCoordinate(),
                    rerouteEnd,
                    edge.getDiameter(),
                    environment,
                    exemptions,
                    failedUtilityIds,
                    acceptedRoutes);
            if (rerouted == null || rerouted.lengthM() <= MIN_EDGE_LENGTH_M) continue;
            RoutePath completed = egress == null
                    ? rerouted
                    : rerouted.withMandatorySuffix(downstream.getCoordinate().toCoordinate());
            RouteEdge candidate = routeEdge(
                    edge.getId(),
                    edge.getUpstreamNodeId(),
                    edge.getDownstreamNodeId(),
                    completed,
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
            Map<String, ImportedOfficialFeature> featuresById,
            OfficialRoutingEnvironment routingEnvironment) {
        return byType(features, "oks_connection_point").stream()
                .map(connection -> {
                    String linkedOks = connection.getAttributes().path("oks_id").asText();
                    String demandId = linkedOks.isBlank() ? connection.getFeatureId() : linkedOks;
                    ImportedOfficialFeature oks = featuresById.get(demandId);
                    BigDecimal flow = decimal(connection.getAttributes(), "flow_tph");
                    if (flow == null && oks != null) {
                        flow = decimal(oks.getAttributes(), "flow_tph");
                    }
                    BigDecimal resolvedFlow = flow == null ? BigDecimal.ZERO : flow;
                    Coordinate coordinate = connection.getMetricGeometry().getCoordinate();
                    OfficialRouteGeometryRules.NormalEgress egress = routingEnvironment.normalEgress(
                                    diameterFor(resolvedFlow), coordinate)
                            .orElse(null);
                    return new Demand(
                            demandId,
                            connection.getFeatureId(),
                            coordinate,
                            resolvedFlow,
                            egress);
                })
                .sorted(this::compareDemandIds)
                .collect(Collectors.toList());
    }

    private int compareDemandIds(Demand left, Demand right) {
        java.math.BigInteger leftNumber = numericId(left.id);
        java.math.BigInteger rightNumber = numericId(right.id);
        if (leftNumber != null && rightNumber != null) {
            int numeric = leftNumber.compareTo(rightNumber);
            return numeric != 0 ? numeric : left.id.compareTo(right.id);
        }
        if (leftNumber != null) return -1;
        if (rightNumber != null) return 1;
        return left.id.compareTo(right.id);
    }

    private java.math.BigInteger numericId(String value) {
        try {
            return new java.math.BigInteger(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
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
        private final OfficialRouteGeometryRules.NormalEgress egress;

        private Demand(
                String id,
                String connectionPointId,
                Coordinate coordinate,
                BigDecimal flowTph,
                OfficialRouteGeometryRules.NormalEgress egress) {
            this.id = id;
            this.connectionPointId = connectionPointId;
            this.coordinate = coordinate;
            this.flowTph = flowTph;
            this.egress = egress;
        }

        private Coordinate routingStart() {
            return egress == null ? coordinate : egress.exit();
        }

        private RoutePath withMandatoryEgress(RoutePath path) {
            return egress == null ? path : path.withMandatoryPrefix(egress.start());
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
        private final BigDecimal savingCost;

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
                double savingM,
                BigDecimal savingCost) {
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
            this.savingCost = savingCost;
        }
    }

    private static class PairSelection {
        private final VariantDraft draft;
        private final Set<String> pairedDemandIds;
        private final Map<String, Integer> usedChamberSlots;
        private final BigDecimal savingCost;
        private final String signature;

        private PairSelection(
                VariantDraft draft,
                Set<String> pairedDemandIds,
                Map<String, Integer> usedChamberSlots,
                BigDecimal savingCost,
                String signature) {
            this.draft = draft;
            this.pairedDemandIds = pairedDemandIds;
            this.usedChamberSlots = usedChamberSlots;
            this.savingCost = savingCost;
            this.signature = signature;
        }
    }

    private static class TreeAttachment {
        private final RouteNode junction;
        private final RouteEdge targetEdge;
        private final RoutePath.Split split;
        private final RoutePath branchPath;
        private final BigDecimal totalNetworkCost;
        private final String signature;

        private TreeAttachment(
                RouteNode junction,
                RouteEdge targetEdge,
                RoutePath.Split split,
                RoutePath branchPath,
                BigDecimal totalNetworkCost,
                String signature) {
            this.junction = junction;
            this.targetEdge = targetEdge;
            this.split = split;
            this.branchPath = branchPath;
            this.totalNetworkCost = totalNetworkCost;
            this.signature = signature;
        }

        private TreeAttachment withTotalNetworkCost(BigDecimal cost) {
            return new TreeAttachment(junction, targetEdge, split, branchPath, cost, signature);
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
        private final Map<String, BigDecimal> connectionCostByDemand = new HashMap<>();
        private final Map<String, String> targetByDemand = new HashMap<>();
        private final Map<String, RouteFailureDiagnostics> failureDiagnostics = new HashMap<>();
        private int sharedPairCount;

        private VariantDraft copy() {
            VariantDraft copy = new VariantDraft();
            copy.nodes.putAll(nodes);
            copy.edges.addAll(edges);
            copy.connections.addAll(connections);
            copy.lengthByDemand.putAll(lengthByDemand);
            copy.connectionCostByDemand.putAll(connectionCostByDemand);
            copy.targetByDemand.putAll(targetByDemand);
            copy.failureDiagnostics.putAll(failureDiagnostics);
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
                        demand.id, demand.connectionPointId, demand.flowTph, "no_route", reason,
                        failureDiagnostics.get(demand.id)));
            }
        }
    }
}
