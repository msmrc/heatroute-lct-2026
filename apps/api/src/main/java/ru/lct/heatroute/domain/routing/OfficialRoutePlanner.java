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
import java.util.LinkedHashSet;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    public static final String ALGORITHM_VERSION = RoutePlannerTuning.STABLE_ALGORITHM_VERSION;
    private static final double MIN_EDGE_LENGTH_M = 0.01;
    private static final double LENGTH_EPSILON_M = 1e-9;
    private static final double MAX_SHARED_PAIR_DISTANCE_M = 500.0;
    private static final int MAX_SHARED_PAIR_CANDIDATES = 4;
    private static final long MAX_SHARED_TARGETS_PER_PAIR = 1;
    private static final int MAX_COSTED_ASSIGNMENT_CANDIDATES = 2;
    private static final int SHARED_NEIGHBOUR_FACTOR = 2;
    private static final int SHARED_SELECTION_BEAM_WIDTH = 24;
    private static final int MAX_GRAFT_EDGES_PER_DEMAND = 3;
    private static final int MAX_GRAFT_EDGES_FOR_DETOUR_REPAIR = 6;
    private static final int MAX_WHOLE_TREE_ACCEPTED_MOVES = 3;
    private static final double EXCESSIVE_DETOUR_RATIO = 1.35;
    private static final double CHAMBER_APPROACH_LENGTH_M = 4.0;
    private static final int MAX_ADDITIONAL_CHAMBER_APPROACHES = 3;
    private static final double JUNCTION_ANGLE_TOLERANCE_DEGREES = 7.5;
    private static final double MAX_CHAMBER_MERGE_LINK_M = 20.0;
    private static final double MAX_CHAMBER_MERGE_RELOCATION_M = 40.0;
    private static final double EXISTING_CHAMBER_REUSE_DISTANCE_M = 10.0;
    private static final double MAX_OPTIONAL_EXISTING_CHAMBER_DISTANCE_M = 60.0;
    private static final int MAX_EXISTING_CHAMBER_ALTERNATIVES_PER_ROOT = 2;
    private static final double ENGINEERING_PRIMARY_DEVIATION_RATIO = 0.05;
    private static final double ENGINEERING_RELAXED_DEVIATION_RATIO = 0.10;
    private static final double DEFAULT_EGRESS_EXTRA_M = 10.0;

    private final OfficialRouteValidator validator;
    private final OfficialObstacleRouter obstacleRouter;
    private final OfficialPipeCatalog pipeCatalog;
    private final OfficialNetworkSizer networkSizer;
    private final OfficialExistingNetworkReconstructor reconstructor;
    private final OfficialVariantEconomicsCalculator economicsCalculator;
    private final OfficialDepthPlanner depthPlanner;
    private final RoutePlannerTuning tuning;
    private final EngineeringRouteEvaluator engineeringEvaluator = new EngineeringRouteEvaluator();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Autowired
    public OfficialRoutePlanner(
            OfficialRouteValidator validator,
            OfficialObstacleRouter obstacleRouter,
            OfficialPipeCatalog pipeCatalog,
            OfficialNetworkSizer networkSizer,
            OfficialExistingNetworkReconstructor reconstructor,
            OfficialVariantEconomicsCalculator economicsCalculator,
            OfficialDepthPlanner depthPlanner) {
        this(
                validator,
                obstacleRouter,
                pipeCatalog,
                networkSizer,
                reconstructor,
                economicsCalculator,
                depthPlanner,
                RoutePlannerTuning.stable());
    }

    OfficialRoutePlanner(
            OfficialRouteValidator validator,
            OfficialObstacleRouter obstacleRouter,
            OfficialPipeCatalog pipeCatalog,
            OfficialNetworkSizer networkSizer,
            OfficialExistingNetworkReconstructor reconstructor,
            OfficialVariantEconomicsCalculator economicsCalculator,
            OfficialDepthPlanner depthPlanner,
            RoutePlannerTuning tuning) {
        this.validator = validator;
        this.obstacleRouter = obstacleRouter;
        this.pipeCatalog = pipeCatalog;
        this.networkSizer = networkSizer;
        this.reconstructor = reconstructor;
        this.economicsCalculator = economicsCalculator;
        this.depthPlanner = depthPlanner;
        this.tuning = tuning;
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
        Map<String, List<TieInCandidate>> candidatesByConnection = topology.getTieInCandidates().stream()
                .collect(Collectors.groupingBy(
                        TieInCandidate::getConnectionPointId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<Demand> demands = demands(
                features, featuresById, candidatesByConnection, routingEnvironment);
        VariantDraft independentDraft = coverageFirstIndependent(
                demands,
                candidatesByConnection,
                featuresById,
                chamberIncidentCounts,
                routingEnvironment,
                Collections.emptyMap(),
                RoutePreference.SHORTEST,
                "independent");
        VariantDraft shortestDraft = completeWithTreeAttachments(
                independentDraft, demands, routingEnvironment);
        routingEnvironment.logVisibilitySummary("independent");

        List<VariantDraft> sharedControls = new ArrayList<>();
        VariantDraft sharedDraft = coverageFirstShared(
                demands,
                candidatesByConnection,
                featuresById,
                chamberIncidentCounts,
                independentDraft.lengthByDemand,
                independentDraft.connectionCostByDemand,
                routingEnvironment, sharedControls);
        routingEnvironment.logVisibilitySummary("shared");

        List<VariantDraft> portfolio = new ArrayList<>();
        addDistinctDraft(portfolio, shortestDraft);
        addDistinctDraft(portfolio, sharedDraft);
        sharedControls.forEach(candidate -> addDistinctDraft(portfolio, candidate));
        for (VariantDraft spine : sharedSpineAlternatives(demands, candidatesByConnection,
                featuresById, chamberIncidentCounts, shortestDraft, routingEnvironment)) {
            addDistinctDraft(portfolio, spine);
        }
        routingEnvironment.logVisibilitySummary("group_spines");

        VariantDraft cheapestDraft = selectCheapestDraft(portfolio);
        cheapestDraft = reuseExistingChambersForCheapest(
                cheapestDraft, featuresById, chamberIncidentCounts, routingEnvironment);
        addDistinctDraft(portfolio, cheapestDraft);
        for (int index = 0; index < portfolio.size(); index++) {
            VariantDraft candidate = portfolio.get(index);
            LOGGER.info("Routing portfolio candidate={} connected={} length_m={} cost_rub={} score={} chambers={} rays={}",
                    index, connectedCount(candidate), totalRouteLength(candidate),
                    totalNetworkConstructionCost(candidate), draftScore(candidate),
                    newBranchChamberCount(candidate), tieInRayCount(candidate));
        }

        List<VariantDraft> engineeringPortfolio = new ArrayList<>();
        // Keep genuinely different topology representatives. Mandatory final OKS egress and
        // sizing can change their geometry, so the expert bend rules are enforced after finish().
        portfolio.forEach(candidate -> addDistinctDraft(engineeringPortfolio, candidate));

        VariantDraft shortestAlternative = selectShortestDraft(engineeringPortfolio);
        if (shortestAlternative == null) {
            shortestAlternative = regularizeEngineeringDraft(
                    shortestDraft, demands, routingEnvironment, false);
        }
        VariantDraft engineeringDraft = selectEngineeringDraft(
                engineeringPortfolio, cheapestDraft, shortestAlternative);
        if (engineeringDraft == null) {
            engineeringDraft = shortestAlternative;
        }
        boolean sharedEngineeringDraft = draftGeometrySignature(engineeringDraft)
                .equals(draftGeometrySignature(shortestAlternative));
        shortestAlternative = regularizeEngineeringDraft(
                shortestAlternative, demands, routingEnvironment, false);
        if (sharedEngineeringDraft) {
            engineeringDraft = regularizeEngineeringDraft(
                    shortestAlternative, demands, routingEnvironment, true);
        } else {
            engineeringDraft = regularizeEngineeringDraft(
                    engineeringDraft, demands, routingEnvironment, true);
        }

        RouteVariant engineering = finish(
                "balanced", "engineering", engineeringDraft, features, validatedParameters,
                reconstructionRequired, routingEnvironment);
        RouteVariant shortest = finish(
                "shortest", "shortest", shortestAlternative, features, validatedParameters,
                reconstructionRequired, routingEnvironment);
        RouteVariant cheapest = finish(
                "cheapest", "cheapest", cheapestDraft, features, validatedParameters,
                reconstructionRequired, routingEnvironment);
        List<RouteVariant> finalized = new ArrayList<>(List.of(
                withEngineeringAssessment(engineering),
                withEngineeringAssessment(shortest),
                cheapest));
        List<RouteVariant> finishedCorridors = new ArrayList<>();
        // Уже регуляризованные коридорные победители также участвуют в bounded-объединении:
        // их исходный portfolio draft мог ещё содержать исправляемый неудобный ввод.
        if (engineeringDraft.corridorCandidate) finishedCorridors.add(withEngineeringAssessment(engineering));
        if (shortestAlternative.corridorCandidate) finishedCorridors.add(withEngineeringAssessment(shortest));
        // Сравниваем не только победителей чернового отбора: обязательный выход из ОКС,
        // регуляризация и итоговый ДУ могут изменить порядок стоимости и длины.
        // Эти деревья уже построены; повторного глобального поиска для них не выполняем.
        for (int index = 0; index < portfolio.size(); index++) {
            RouteVariant candidate = withEngineeringAssessment(finish(
                    "portfolio-" + index, "engineering", portfolio.get(index).copy(), features,
                    validatedParameters, reconstructionRequired, routingEnvironment));
            finalized.add(candidate);
            if (portfolio.get(index).corridorCandidate) finishedCorridors.add(candidate);
            logVariantSummary(candidate);
        }
        finalized.addAll(refineCorridorVariants(finishedCorridors, demands, features,
                validatedParameters, reconstructionRequired, routingEnvironment));
        List<RouteVariant> variants = new FinishedRouteVariantSelector()
                .select(finalized, validatedParameters.isDepthEnabled());
        variants = relocateSelectedVariants(variants, demands, features, validatedParameters,
                reconstructionRequired, routingEnvironment);
        routingEnvironment.logVisibilitySummary("finalized_portfolio");
        for (RouteVariant variant : variants) {
            logVariantSummary(variant);
            EngineeringRouteEvaluator.Evaluation evaluation =
                    engineeringEvaluator.evaluate(variant.getEdges());
            LOGGER.info(
                    "Engineering geometry id={} bends={} invalid_angles={} close_bend_pairs={}",
                    variant.getId(), evaluation.bendCount(), evaluation.invalidAngleCount(),
                    evaluation.insufficientSpacingCount());
        }

        // Official-invalid drafts remain diagnostic implementation details. Additional expert
        // geometry rules are published separately as warnings so the three objective-specific
        // official-valid alternatives remain available for comparison and further correction.
        variants = variants.stream()
                .filter(RouteVariant::isValid)
                .collect(Collectors.toList());

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
        return new OfficialCalculationResult(
                tuning.getAlgorithmVersion(), inputProfile, demands.size(), variants, preferred);
    }

    RouteVariant withEngineeringAssessment(RouteVariant variant) {
        EngineeringRouteEvaluator.Evaluation evaluation = engineeringEvaluator.evaluate(variant.getEdges());
        List<RouteValidationIssue> issues = new ArrayList<>();
        String affectedEdges = evaluation.nonCompliantEdgeIds().isEmpty()
                ? null
                : String.join(",", evaluation.nonCompliantEdgeIds());
        if (evaluation.invalidAngleCount() > 0) {
            issues.add(new RouteValidationIssue(
                    "EXPERT_BEND_ANGLE_OUT_OF_RANGE",
                    affectedEdges,
                    evaluation.invalidAngleCount()
                            + " bend angles are outside the expert 90-135 degree range"));
        }
        if (evaluation.insufficientSpacingCount() > 0) {
            issues.add(new RouteValidationIssue(
                    "EXPERT_BEND_SPACING_TOO_SHORT",
                    affectedEdges,
                    evaluation.insufficientSpacingCount()
                            + " consecutive bend pairs are less than 2 m apart"));
        }
        return variant.withEngineeringIssues(issues);
    }

    /**
     * Дополняет лес кратчайших независимых трасс общими ветвями, если отдельная трасса
     * не может дойти до сети без пересечения уже принятой геометрии.
     */
    private VariantDraft completeWithTreeAttachments(
            VariantDraft source,
            List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment) {
        VariantDraft result = source.copy();
        Set<String> failedIds = result.connections.stream()
                .filter(connection -> "no_route".equals(connection.getStatus()))
                .map(RouteConnection::getDemandId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (Demand demand : demands) {
            if (!failedIds.contains(demand.id)) {
                continue;
            }
            TreeAttachment attachment = chooseTreeAttachment(demand, result, routingEnvironment);
            if (attachment == null) {
                continue;
            }
            result.connections.removeIf(connection -> connection.getDemandId().equals(demand.id));
            result.failureDiagnostics.remove(demand.id);
            addTreeAttachment(result, demand, attachment);
        }
        return result;
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
                .thenComparingInt(demand -> candidatesByConnection
                        .getOrDefault(demand.connectionPointId, List.of()).size())
                .thenComparing(Comparator.comparingDouble(
                        (Demand demand) -> nearestTieInDistance(
                                demand, candidatesByConnection)).reversed())
                .thenComparing(this::compareDemandIds));
        VariantDraft retry = independent(
                retryOrder, candidatesByConnection, featuresById, chamberIncidentCounts,
                routingEnvironment, avoidedTargetByDemand, preference, strategyPrefix);
        return betterDraft(first, retry);
    }

    private long connectedCount(VariantDraft draft) {
        return draft.connections.stream()
                .filter(connection -> "connected".equals(connection.getStatus()))
                .count();
    }

    private double nearestTieInDistance(
            Demand demand,
            Map<String, List<TieInCandidate>> candidatesByConnection) {
        return candidatesByConnection.getOrDefault(demand.connectionPointId, List.of()).stream()
                .map(TieInCandidate::getDistanceM)
                .mapToDouble(BigDecimal::doubleValue)
                .min()
                .orElse(Double.POSITIVE_INFINITY);
    }

    /**
     * Compares complete draft economics, including the official unconnected penalty. The previous
     * comparison used construction cost only and could therefore prefer a cheap partial tree over
     * another tree with the same connection count but materially smaller official penalty.
     */
    private VariantDraft betterDraft(VariantDraft left, VariantDraft right) {
        long leftConnected = connectedCount(left);
        long rightConnected = connectedCount(right);
        if (leftConnected != rightConnected) {
            return rightConnected > leftConnected ? right : left;
        }
        BigDecimal leftScore = draftScore(left);
        BigDecimal rightScore = draftScore(right);
        if (leftScore != null && rightScore != null && leftScore.compareTo(rightScore) != 0) {
            return rightScore.compareTo(leftScore) < 0 ? right : left;
        }
        double leftLength = totalRouteLength(left);
        double rightLength = totalRouteLength(right);
        if (Math.abs(leftLength - rightLength) > LENGTH_EPSILON_M) {
            return rightLength < leftLength ? right : left;
        }
        int leftChambers = newBranchChamberCount(left);
        int rightChambers = newBranchChamberCount(right);
        if (leftChambers != rightChambers) {
            return rightChambers < leftChambers ? right : left;
        }
        double leftGeometry = constructabilityPenalty(left);
        double rightGeometry = constructabilityPenalty(right);
        return rightGeometry + LENGTH_EPSILON_M < leftGeometry ? right : left;
    }

    private BigDecimal draftScore(VariantDraft draft) {
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
        return economicsCalculator.calculate(
                        new ArrayList<>(draft.nodes.values()),
                        applySizing(draft.edges, sizing),
                        draft.connections,
                        ExistingNetworkReconstructionResult.empty(),
                        false)
                .getScore();
    }

    /** Secondary deterministic tie-breaker; it is deliberately not an official cost tariff. */
    private double constructabilityPenalty(VariantDraft draft) {
        double result = 0.0;
        for (RouteEdge edge : draft.edges) {
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            for (int index = 1; index + 1 < coordinates.size(); index++) {
                Coordinate before = coordinates.get(index - 1).toCoordinate();
                Coordinate at = coordinates.get(index).toCoordinate();
                Coordinate after = coordinates.get(index + 1).toCoordinate();
                result += turnPenalty(before, at, after);
            }
        }
        return result;
    }

    private double turnPenalty(Coordinate before, Coordinate at, Coordinate after) {
        double ax = before.x - at.x;
        double ay = before.y - at.y;
        double bx = after.x - at.x;
        double by = after.y - at.y;
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator <= LENGTH_EPSILON_M) {
            return 0.0;
        }
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
                (ax * bx + ay * by) / denominator))));
        double deflection = Math.abs(180.0 - angle);
        if (deflection <= 1.0) {
            return 0.0;
        }
        return 1.0 + Math.pow(Math.sin(Math.toRadians(2.0 * deflection)), 2.0);
    }

    private VariantDraft shared(
            List<Demand> demands,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            Map<String, Double> independentLengthByDemand,
            Map<String, BigDecimal> independentCostByDemand,
            OfficialRoutingEnvironment routingEnvironment,
            Set<String> directFirstDemandIds,
            List<VariantDraft> controls) {
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
        return improveWholeTree(draft, demands, featuresById, routingEnvironment, controls);
    }

    private VariantDraft coverageFirstShared(
            List<Demand> demands,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            Map<String, Double> independentLengthByDemand,
            Map<String, BigDecimal> independentCostByDemand,
            OfficialRoutingEnvironment routingEnvironment,
            List<VariantDraft> controls) {
        VariantDraft first = shared(
                demands, candidatesByConnection, featuresById, chamberIncidentCounts,
                independentLengthByDemand, independentCostByDemand,
                routingEnvironment, Collections.emptySet(), controls);
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
                routingEnvironment, failedDemandIds, controls);
        return betterDraft(first, retry);
    }

    /**
     * Сравнивает готовые групповые магистрали с независимыми и последовательно собранными деревьями.
     * Положение врезки выбирается для группы, без привязки к проекции одного потребителя.
     */
    private List<VariantDraft> sharedSpineAlternatives(
            List<Demand> demands, Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById, Map<String, Integer> chamberIncidentCounts,
            VariantDraft control, OfficialRoutingEnvironment environment) {
        List<VariantDraft> result = new ArrayList<>();
        SharedSpineCandidateBuilder candidateBuilder = new SharedSpineCandidateBuilder();
        SharedSpineNetworkBuilder networkBuilder = new SharedSpineNetworkBuilder(obstacleRouter, pipeCatalog);
        OrthogonalCorridorNetworkBuilder corridorBuilder = new OrthogonalCorridorNetworkBuilder(obstacleRouter, pipeCatalog);
        for (List<Demand> group : spatialDemandGroups(demands)) {
            if (group.size() < 4) continue;
            VariantDraft remaining = control.copy();
            for (Demand demand : group) {
                VariantDraft detached = detachDemandAndNormalize(remaining, demand);
                if (detached != null) remaining = detached;
                else remaining.connections.removeIf(connection -> connection.getDemandId().equals(demand.id));
            }
            final VariantDraft retained = remaining;
            Map<String, Demand> groupById = group.stream().collect(Collectors.toMap(
                    demand -> demand.id, demand -> demand, (left, right) -> left, LinkedHashMap::new));
            Map<String, SharedSpineNetworkBuilder.Terminal> terminals = new LinkedHashMap<>();
            group.forEach(demand -> terminals.put(demand.id, new SharedSpineNetworkBuilder.Terminal(
                    demand.id, demand.connectionPointId, demand.coordinate, demand.flowTph)));
            List<SharedSpineCandidateBuilder.Terminal> positions = group.stream()
                    .map(demand -> new SharedSpineCandidateBuilder.Terminal(demand.id, demand.routingStart()))
                    .collect(Collectors.toList());
            org.locationtech.jts.geom.Envelope bounds = new org.locationtech.jts.geom.Envelope();
            group.forEach(demand -> bounds.expandToInclude(demand.coordinate));
            List<Geometry> footprints = environment.buildingFootprints(
                    new Coordinate(bounds.getMinX(), bounds.getMinY()),
                    new Coordinate(bounds.getMaxX(), bounds.getMaxY()));
            int attempted = 0;
            int accepted = 0;
            int rootedTrees = 0;
            boolean terminalFrameAttempted = false;
            for (TieInCandidate candidate : groupTieInCandidates(
                    group, candidatesByConnection, featuresById, chamberIncidentCounts)) {
                ImportedOfficialFeature target = featuresById.get(candidate.getTargetId());
                Coordinate rootCoordinate = targetCoordinate(group.get(0).coordinate, candidate, target.getMetricGeometry());
                RouteNode root = rootNode(candidate, rootCoordinate, chamberIncidentCounts,
                        "spine:" + group.get(0).id, environment);
                List<OrthogonalCorridorNetworkBuilder.Terminal> corridorTerminals = group.stream()
                        .map(demand -> new OrthogonalCorridorNetworkBuilder.Terminal(demand.id,
                                demand.connectionPointId, demand.coordinate, demand.flowTph))
                        .collect(Collectors.toList());
                int occupied = (int) retained.edges.stream()
                        .filter(edge -> edge.getUpstreamNodeId().equals(root.getId())).count();
                int corridorAccepted = 0;
                SharedSpineNetworkBuilder.TerminalRouter terminalRouter =
                        (id, junction, diameter, avoidance) -> routeDemandTowards(groupById.get(id), junction,
                                diameter, environment, avoidance, RoutePreference.ENGINEERING);
                int rootCapacity = 4 - root.getBaseIncidentSections() - occupied;
                // Не удваиваем весь portfolio: дополнительная система осей только у первого
                // геометрически выбранного корня группы. Остальные контрольные сетки сохраняются.
                List<OrthogonalCorridorNetworkBuilder.Network> corridorNetworks = terminalFrameAttempted
                        ? corridorBuilder.build(corridorTerminals, root, rootCapacity, footprints, environment, terminalRouter)
                        : corridorBuilder.buildWithTerminalFrame(corridorTerminals, root, rootCapacity, footprints, environment, terminalRouter);
                terminalFrameAttempted = true;
                for (OrthogonalCorridorNetworkBuilder.Network network : corridorNetworks) {
                    VariantDraft draft = retained.copy();
                    draft.corridorCandidate = true;
                    network.nodes().forEach(draft::addNode);
                    draft.edges.addAll(network.edges());
                    draft.connections.addAll(network.connections());
                    normalizeRedundantBranchChambers(draft);
                    if (connectedCount(draft) >= connectedCount(control) && isFinalGeometryValid(draft, environment)) {
                        addDistinctDraft(result, draft);
                        corridorAccepted++;
                        LOGGER.info("Orthogonal corridor target={} connected={} length_m={} chambers={} rays={}",
                                candidate.getTargetId(), connectedCount(draft), totalRouteLength(draft),
                                newBranchChamberCount(draft), tieInRayCount(draft));
                    }
                }
                LOGGER.info("Orthogonal corridor target={} accepted={}", candidate.getTargetId(), corridorAccepted);
                // Криволинейная опорная магистраль дополняет прямолинейные коридоры в сложной застройке.
                if (rootedTrees++ < 2) {
                    VariantDraft rooted = sharedRootTree(group, candidate, rootCoordinate,
                            featuresById, chamberIncidentCounts, retained, environment);
                    if (rooted != null && connectedCount(rooted) >= connectedCount(control)
                            && isFinalGeometryValid(rooted, environment)) {
                        addDistinctDraft(result, rooted);
                        addDistinctDraft(result, mergeAdjacentBranchChambers(rooted, group, environment));
                    }
                }
                // Несколько направлений/смещений на каждый корень сохраняют разнообразие топологий.
                List<SharedSpineCandidateBuilder.SpineCandidate> proposals = candidateBuilder
                        .build(positions, rootCoordinate, footprints);
                int routedForRoot = 0;
                for (SharedSpineCandidateBuilder.SpineCandidate proposal : proposals) {
                    if (routedForRoot++ >= 3) break;
                    attempted++;
                    SharedSpineNetworkBuilder.Network network = networkBuilder.build(proposal, root, terminals,
                            environment, retained.edges,
                            (id, junction, diameter, avoidance) -> {
                                return routeDemandTowards(groupById.get(id), junction, diameter,
                                        environment, avoidance, RoutePreference.ENGINEERING);
                            },
                            (junction, flow, diameter, avoidance) -> routeToTieIn(
                                    new Demand("spine-root", "spine-root", junction, flow, null),
                                    candidate, target, rootCoordinate, diameter, environment,
                                    Set.of(candidate.getTargetId()), RoutePreference.ENGINEERING,
                                    avoidance, featuresById));
                    if (network == null) continue;
                    VariantDraft draft = retained.copy();
                    network.nodes().forEach(draft::addNode);
                    draft.edges.addAll(network.edges());
                    draft.connections.addAll(network.connections());
                    normalizeRedundantBranchChambers(draft);
                    if (connectedCount(draft) >= connectedCount(control)
                            && isFinalGeometryValid(draft, environment)) {
                        accepted++;
                        addDistinctDraft(result, draft);
                    }
                }
            }
            LOGGER.info("Shared spine group demands={} attempted={} accepted={}", group.size(), attempted, accepted);
        }
        return result;
    }

    /** Строит дерево от дальнего потребителя к общей врезке, затем присоединяет ближайшие к дереву вводы. */
    private VariantDraft sharedRootTree(List<Demand> group, TieInCandidate candidate,
            Coordinate rootCoordinate,
            Map<String, ImportedOfficialFeature> featuresById, Map<String, Integer> chamberIncidentCounts,
            VariantDraft retained, OfficialRoutingEnvironment environment) {
        List<Demand> remaining = new ArrayList<>(group);
        remaining.sort(Comparator.comparingDouble((Demand demand) -> demand.routingStart().distance(rootCoordinate))
                .reversed().thenComparingDouble(demand -> demand.coordinate.x)
                .thenComparingDouble(demand -> demand.coordinate.y).thenComparing(demand -> demand.id));
        VariantDraft draft = retained.copy();
        Map<String, Integer> usedSlots = new HashMap<>();
        for (RouteNode node : retained.nodes.values()) {
            if (node.isRoot() && node.getTargetId() != null) {
                usedSlots.merge(node.getTargetId(), (int) retained.edges.stream()
                        .filter(edge -> edge.getUpstreamNodeId().equals(node.getId())).count(), Integer::sum);
            }
        }
        Set<String> groupEdges = new HashSet<>();
        while (!remaining.isEmpty()) {
            if (!groupEdges.isEmpty()) {
                List<LineString> spine = draft.edges.stream().filter(edge -> groupEdges.contains(edge.getId()))
                        .map(this::routeLine).collect(Collectors.toList());
                remaining.sort(Comparator.comparingDouble((Demand demand) -> spine.stream()
                                .mapToDouble(line -> line.distance(geometryFactory.createPoint(demand.routingStart())))
                                .min().orElse(Double.POSITIVE_INFINITY))
                        .thenComparingDouble(demand -> demand.coordinate.x)
                        .thenComparingDouble(demand -> demand.coordinate.y).thenComparing(demand -> demand.id));
            }
            Demand demand = remaining.remove(0);
            Set<String> before = draft.edges.stream().map(RouteEdge::getId).collect(Collectors.toSet());
            Assignment direct = chooseAssignment(demand, List.of(candidate), featuresById, chamberIncidentCounts,
                    usedSlots, draft, "rooted:" + demand.id, environment, Set.of(), RoutePreference.ENGINEERING);
            TreeAttachment graft = groupEdges.isEmpty() ? null : chooseTreeAttachment(
                    demand, draft, environment, false, false, RoutePreference.ENGINEERING, groupEdges);
            BigDecimal directCost = direct == null ? null : totalCostAfterDirect(draft, demand, direct);
            if (graft != null && (directCost == null || graft.totalNetworkCost.compareTo(directCost) <= 0)) {
                addTreeAttachment(draft, demand, graft);
            } else if (direct != null) {
                addDirect(draft, demand, direct, "rooted");
                if (direct.chamberTargetId() != null) usedSlots.merge(direct.chamberTargetId(), 1, Integer::sum);
            } else {
                return null;
            }
            draft.edges.stream().filter(edge -> !before.contains(edge.getId()))
                    .map(RouteEdge::getId).forEach(groupEdges::add);
        }
        normalizeRedundantBranchChambers(draft);
        LOGGER.info("Shared root tree target={} demands={} rays={} branch_chambers={}",
                candidate.getTargetId(), group.size(), tieInRayCount(draft), newBranchChamberCount(draft));
        return draft;
    }

    private List<List<Demand>> spatialDemandGroups(List<Demand> demands) {
        List<Demand> remaining = demands.stream().sorted(Comparator
                .comparingDouble((Demand demand) -> demand.coordinate.x)
                .thenComparingDouble(demand -> demand.coordinate.y).thenComparing(demand -> demand.id))
                .collect(Collectors.toCollection(ArrayList::new));
        List<List<Demand>> groups = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<Demand> group = new ArrayList<>();
            group.add(remaining.remove(0));
            for (int i = 0; i < group.size(); i++) {
                Demand current = group.get(i);
                for (int j = remaining.size() - 1; j >= 0; j--) {
                    if (current.coordinate.distance(remaining.get(j).coordinate) <= MAX_SHARED_PAIR_DISTANCE_M) {
                        group.add(remaining.remove(j));
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private List<TieInCandidate> groupTieInCandidates(List<Demand> group,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById, Map<String, Integer> chamberIncidentCounts) {
        Coordinate center = new Coordinate(group.stream().mapToDouble(demand -> demand.routingStart().x).average().orElseThrow(),
                group.stream().mapToDouble(demand -> demand.routingStart().y).average().orElseThrow());
        Set<String> targets = group.stream().flatMap(demand -> candidatesByConnection
                        .getOrDefault(demand.connectionPointId, List.of()).stream())
                .map(TieInCandidate::getTargetId).collect(Collectors.toCollection(LinkedHashSet::new));
        // Общая выгодная камера может отсутствовать в индивидуальных списках ближайших врезок.
        featuresById.values().stream().filter(feature -> "heat_chamber".equals(feature.getObjectType()))
                .filter(feature -> chamberIncidentCounts.getOrDefault(feature.getFeatureId(), 0) > 0)
                .forEach(feature -> targets.add(feature.getFeatureId()));
        List<TieInCandidate> roots = new ArrayList<>();
        for (String id : targets) {
            ImportedOfficialFeature target = featuresById.get(id);
            if (target == null || target.getMetricGeometry() == null) continue;
            boolean chamber = "heat_chamber".equals(target.getObjectType());
            if (chamber && chamberIncidentCounts.getOrDefault(id, 0) >= 4) continue;
            Coordinate point = chamber ? target.getMetricGeometry().getCoordinate()
                    : closestPointOnLinework(target.getMetricGeometry(), center);
            if (point == null || (!chamber && tooCloseToExistingChamber(point, featuresById))) continue;
            if (group.stream().mapToDouble(demand -> demand.coordinate.distance(point)).min().orElseThrow()
                    > MAX_SHARED_PAIR_DISTANCE_M) continue;
            roots.add(new TieInCandidate(group.get(0).connectionPointId, id,
                    chamber ? "heat_chamber" : "heat_network", center.distance(point), !chamber, point.x, point.y));
        }
        Comparator<TieInCandidate> order = Comparator.comparing(TieInCandidate::getDistanceM)
                .thenComparing(this::targetKey);
        List<TieInCandidate> result = roots.stream().filter(candidate -> !candidate.isNewChamberRequired())
                .sorted(order).limit(2).collect(Collectors.toCollection(ArrayList::new));
        roots.stream().filter(TieInCandidate::isNewChamberRequired).sorted(order).limit(2).forEach(result::add);
        return result;
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
                    midpoint(left.routingStart(), right.routingStart()),
                    leftCandidate,
                    target.getMetricGeometry());
            for (Coordinate junction : sharedJunctionCandidates(
                    left, right, targetCoordinateForCandidates, trunkDiameter, routingEnvironment)) {
                Coordinate targetCoordinate = targetCoordinate(
                        junction, leftCandidate, target.getMetricGeometry());
                RoutePath leftPath = routeDemand(
                        left, junction, leftDiameter, routingEnvironment,
                        Collections.emptySet(), RoutePreference.LEFT, Collections.emptyList());
                RoutePath rightPath = routeDemand(
                        right, junction, rightDiameter, routingEnvironment,
                        Collections.emptySet(), RoutePreference.RIGHT, Collections.emptyList());
                RoutePath trunkPath = obstacleRouter.find(
                        junction, targetCoordinate, trunkDiameter, routingEnvironment,
                        Set.of(leftCandidate.getTargetId()), RoutePreference.SHORTEST);
                if (leftPath == null || rightPath == null || trunkPath == null) {
                    continue;
                }
                double totalLength = leftPath.lengthM() + rightPath.lengthM() + trunkPath.lengthM();
                double saving = independentLeft + independentRight - totalLength;
                Assignment assignment = new Assignment(
                        leftCandidate,
                        targetCoordinate,
                        rootNode(leftCandidate, targetCoordinate, chamberIncidentCounts,
                                "shared:" + left.id + ":" + right.id, routingEnvironment),
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
                if (betterPairPlan(candidate, best)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private boolean betterPairPlan(PairPlan candidate, PairPlan current) {
        return current == null
                || candidate.savingCost.compareTo(current.savingCost) > 0
                || (candidate.savingCost.compareTo(current.savingCost) == 0
                        && candidate.savingM > current.savingM)
                || (candidate.savingCost.compareTo(current.savingCost) == 0
                        && candidate.savingM == current.savingM
                        && targetKey(candidate.assignment.candidate)
                                .compareTo(targetKey(current.assignment.candidate)) < 0);
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
        List<String> attemptedTargetIds = new ArrayList<>();
        Set<String> directBlockers = new java.util.TreeSet<>();
        int routedCandidateCount = 0;
        for (TieInCandidate candidate : eligibleCandidates) {
            ImportedOfficialFeature target = featuresById.get(candidate.getTargetId());
            if (target == null) {
                continue;
            }
            attemptedTargetIds.add(candidate.getTargetId());
            Coordinate coordinate = targetCoordinate(
                    demand.coordinate, candidate, target.getMetricGeometry());
            RouteNode candidateRoot = rootNode(candidate, coordinate, chamberIncidentCounts, rootSuffix, routingEnvironment);
            // Лучи одной физической врезки могут иметь общий конец. Остальные пересечения
            // по-прежнему запрещены и независимо проверяются на собранном дереве.
            List<LineString> acceptedRoutes = draft.edges.stream()
                    .filter(edge -> !edge.getUpstreamNodeId().equals(candidateRoot.getId())
                            && !edge.getDownstreamNodeId().equals(candidateRoot.getId()))
                    .map(this::routeLine).collect(Collectors.toList());
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
            RoutePath path = routeToTieIn(
                    demand, candidate, target, coordinate, diameter, routingEnvironment,
                    Set.of(candidate.getTargetId()), preference, acceptedRoutes, featuresById);
            if (path == null) {
                directBlockers.addAll(obstacleRouter.directBlockingConstraintIds(
                        demand.routingStart(), coordinate, diameter, routingEnvironment,
                        Set.of(candidate.getTargetId()), acceptedRoutes));
                OfficialRouteGeometryRules.NormalEgress alternate = demand.egressTowards(
                        routingEnvironment, diameter, coordinate);
                directBlockers.addAll(obstacleRouter.directBlockingConstraintIds(
                        demand.routingStart(alternate), coordinate, diameter, routingEnvironment,
                        Set.of(candidate.getTargetId()), acceptedRoutes));
                continue;
            }
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

    /**
     * Prefers a short perpendicular final leg into a chamber or chamber tie-in. The point model
     * has no wall geometry, therefore this is a constructability preference relative to the local
     * existing-network tangent, not a claimed official tariff or unconditional validity rule.
     */
    private RoutePath routeToTieIn(
            Demand demand,
            TieInCandidate candidate,
            ImportedOfficialFeature target,
            Coordinate coordinate,
            int diameter,
            OfficialRoutingEnvironment routingEnvironment,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes,
            Map<String, ImportedOfficialFeature> featuresById) {
        LineString supportingNetwork = supportingNetwork(candidate, target, coordinate, featuresById);
        if (supportingNetwork != null) {
            Coordinate waypoint = perpendicularApproachWaypoint(
                    supportingNetwork, coordinate, demand.routingStart());
            if (waypoint != null) {
                RoutePath approach = routeDemand(
                        demand, waypoint, diameter, routingEnvironment,
                        exemptFeatureIds, preference, acceptedRoutes);
                if (approach != null) {
                    RoutePath perpendicular = obstacleRouter.withCheckedTerminalSuffix(
                            approach, coordinate, diameter, routingEnvironment, exemptFeatureIds, acceptedRoutes);
                    if (perpendicular != null) {
                        return perpendicular;
                    }
                }
            }
        }
        return routeDemand(
                demand, coordinate, diameter, routingEnvironment,
                exemptFeatureIds, preference, acceptedRoutes);
    }

    private LineString supportingNetwork(
            TieInCandidate candidate,
            ImportedOfficialFeature target,
            Coordinate coordinate,
            Map<String, ImportedOfficialFeature> featuresById) {
        if (target.getMetricGeometry() instanceof LineString) {
            return (LineString) target.getMetricGeometry();
        }
        if (!"heat_chamber".equals(candidate.getTargetType())) {
            return null;
        }
        return featuresById.values().stream()
                .filter(feature -> "heat_network".equals(feature.getObjectType()))
                .filter(feature -> feature.getMetricGeometry() instanceof LineString)
                .map(feature -> (LineString) feature.getMetricGeometry())
                .filter(line -> line.distance(geometryFactory.createPoint(coordinate))
                        <= OfficialRouteGeometryRules.EPSILON_M)
                .min(Comparator.comparing(line -> line.toText()))
                .orElse(null);
    }

    private Coordinate perpendicularApproachWaypoint(
            LineString supportingNetwork,
            Coordinate tieIn,
            Coordinate origin) {
        LengthIndexedLine indexed = new LengthIndexedLine(supportingNetwork);
        double index = indexed.project(tieIn);
        double beforeIndex = Math.max(0.0, index - 1.0);
        double afterIndex = Math.min(supportingNetwork.getLength(), index + 1.0);
        if (afterIndex - beforeIndex <= LENGTH_EPSILON_M) {
            return null;
        }
        Coordinate before = indexed.extractPoint(beforeIndex);
        Coordinate after = indexed.extractPoint(afterIndex);
        double dx = after.x - before.x;
        double dy = after.y - before.y;
        double length = Math.hypot(dx, dy);
        if (length <= LENGTH_EPSILON_M) {
            return null;
        }
        Coordinate left = new Coordinate(
                tieIn.x - dy / length * CHAMBER_APPROACH_LENGTH_M,
                tieIn.y + dx / length * CHAMBER_APPROACH_LENGTH_M);
        Coordinate right = new Coordinate(
                tieIn.x + dy / length * CHAMBER_APPROACH_LENGTH_M,
                tieIn.y - dx / length * CHAMBER_APPROACH_LENGTH_M);
        return origin.distance(left) <= origin.distance(right) ? left : right;
    }

    /**
     * Keeps the nearest short own-OKS exit as the default. A target-facing side is attempted only
     * when that established route is unavailable, preventing the fallback that restores difficult
     * terminals from lengthening every already-good connection.
     */
    private RoutePath routeDemand(
            Demand demand,
            Coordinate target,
            int diameter,
            OfficialRoutingEnvironment routingEnvironment,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes) {
        RoutePath nearest = obstacleRouter.find(
                demand.routingStart(), target, diameter, routingEnvironment,
                exemptFeatureIds, preference, acceptedRoutes);
        if (nearest != null) {
            RoutePath nearestWithEgress = demand.withMandatoryEgress(nearest, demand.egress);
            double direct = demand.coordinate.distance(target);
            if (direct <= MIN_EDGE_LENGTH_M
                    || nearestWithEgress.lengthM() / direct <= EXCESSIVE_DETOUR_RATIO) {
                return nearestWithEgress;
            }
            OfficialRouteGeometryRules.NormalEgress alternate = demand.egressTowards(
                    routingEnvironment, diameter, target);
            if (!sameEgress(demand.egress, alternate)) {
                RoutePath targetFacing = obstacleRouter.find(
                        demand.routingStart(alternate), target, diameter, routingEnvironment,
                        exemptFeatureIds, preference, acceptedRoutes);
                if (targetFacing != null) {
                    targetFacing = demand.withMandatoryEgress(targetFacing, alternate);
                    if (targetFacing.lengthM() + LENGTH_EPSILON_M < nearestWithEgress.lengthM()) {
                        return targetFacing;
                    }
                }
            }
            return nearestWithEgress;
        }
        if (!acceptedRoutes.isEmpty()) {
            RoutePath unobstructedByTree = obstacleRouter.find(
                    demand.routingStart(), target, diameter, routingEnvironment,
                    exemptFeatureIds, preference, Collections.emptyList());
            if (unobstructedByTree != null) {
                return null;
            }
        }
        OfficialRouteGeometryRules.NormalEgress alternate = demand.egressTowards(
                routingEnvironment, diameter, target);
        if (sameEgress(demand.egress, alternate)) {
            return null;
        }
        RoutePath fallback = obstacleRouter.find(
                demand.routingStart(alternate), target, diameter, routingEnvironment,
                exemptFeatureIds, preference, acceptedRoutes);
        return fallback == null ? null : demand.withMandatoryEgress(fallback, alternate);
    }

    private boolean sameEgress(
            OfficialRouteGeometryRules.NormalEgress left,
            OfficialRouteGeometryRules.NormalEgress right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.exit().distance(right.exit()) <= OfficialRouteGeometryRules.EPSILON_M;
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

    TreeAttachment chooseTreeAttachment(
            Demand demand,
            VariantDraft draft,
            OfficialRoutingEnvironment routingEnvironment) {
        return chooseTreeAttachment(demand, draft, routingEnvironment, false, false);
    }

    private TreeAttachment chooseTreeAttachment(
            Demand demand,
            VariantDraft draft,
            OfficialRoutingEnvironment routingEnvironment,
            boolean boundedWholeTreeSearch,
            boolean detourRepair) {
        return chooseTreeAttachment(
                demand,
                draft,
                routingEnvironment,
                boundedWholeTreeSearch,
                detourRepair,
                RoutePreference.SHORTEST);
    }

    private TreeAttachment chooseTreeAttachment(
            Demand demand,
            VariantDraft draft,
            OfficialRoutingEnvironment routingEnvironment,
            boolean boundedWholeTreeSearch,
            boolean detourRepair,
            RoutePreference preference) {
        return chooseTreeAttachment(
                demand,
                draft,
                routingEnvironment,
                boundedWholeTreeSearch,
                detourRepair,
                preference,
                null);
    }

    private TreeAttachment chooseTreeAttachment(
            Demand demand,
            VariantDraft draft,
            OfficialRoutingEnvironment routingEnvironment,
            boolean boundedWholeTreeSearch,
            boolean detourRepair,
            RoutePreference preference,
            Set<String> allowedEdgeIds) {
        if (draft.edges.isEmpty()) {
            return null;
        }
        boolean restrictedToGroupSpine = allowedEdgeIds != null && !allowedEdgeIds.isEmpty();
        List<TreeAttachment> attachments = new ArrayList<>();
        List<RouteNode> existingJunctions = draft.nodes.values().stream()
                .filter(RouteNode::isChamber)
                .filter(node -> !node.isRoot())
                .filter(node -> incidentEdges(draft, node.getId()).size()
                        + node.getBaseIncidentSections() < 4)
                .filter(node -> !restrictedToGroupSpine || draft.edges.stream()
                        .filter(edge -> allowedEdgeIds.contains(edge.getId()))
                        .anyMatch(edge -> edge.getUpstreamNodeId().equals(node.getId())
                                || edge.getDownstreamNodeId().equals(node.getId())))
                .sorted(Comparator
                        .comparingDouble((RouteNode node) -> node.getCoordinate().toCoordinate()
                                .distance(demand.coordinate))
                        .thenComparing(RouteNode::getId))
                .collect(Collectors.toList());
        for (RouteNode junction : existingJunctions) {
            Coordinate junctionCoordinate = junction.getCoordinate().toCoordinate();
            int diameter = diameterFor(demand.flowTph);
            List<RouteEdge> incidentEdges = draft.edges.stream()
                    .filter(edge -> edge.getUpstreamNodeId().equals(junction.getId())
                            || edge.getDownstreamNodeId().equals(junction.getId()))
                    .collect(Collectors.toList());
            List<LineString> acceptedRoutes = draft.edges.stream()
                    .filter(edge -> !edge.getUpstreamNodeId().equals(junction.getId()))
                    .filter(edge -> !edge.getDownstreamNodeId().equals(junction.getId()))
                    .filter(edge -> edge.getCoordinates().size() >= 2)
                    .map(this::routeLine)
                    .collect(Collectors.toList());
            RoutePath branch = routeDemand(
                    demand, junctionCoordinate, diameter, routingEnvironment,
                    Collections.emptySet(), preference, acceptedRoutes);
            if (branch == null) {
                continue;
            }
            if (branch.lengthM() <= MIN_EDGE_LENGTH_M) {
                continue;
            }
            List<RoutePath> branches = incidentEdges.size() >= 3
                    && !constructibleAdditionalRay(junctionCoordinate, branch, incidentEdges)
                    ? constructibleBranchApproaches(demand, junctionCoordinate, diameter, incidentEdges,
                            routingEnvironment, acceptedRoutes, preference)
                    : List.of(branch);
            for (RoutePath candidateBranch : branches) {
                TreeAttachment candidate = costTreeAttachment(
                        demand, draft, junction, null, null, candidateBranch, routingEnvironment);
                if (candidate != null) attachments.add(candidate);
            }
        }

        List<RouteEdge> nearestEdges = draft.edges.stream()
                .filter(edge -> edge.getCoordinates().size() >= 2)
                .filter(edge -> !restrictedToGroupSpine || allowedEdgeIds.contains(edge.getId()))
                .sorted(Comparator
                        .comparingDouble((RouteEdge edge) -> routeLine(edge)
                                .distance(geometryFactory.createPoint(demand.coordinate)))
                        .thenComparing(RouteEdge::getId))
                .limit(boundedWholeTreeSearch
                        ? (detourRepair ? MAX_GRAFT_EDGES_FOR_DETOUR_REPAIR : 2)
                        : MAX_GRAFT_EDGES_PER_DEMAND)
                .collect(Collectors.toList());
        for (RouteEdge targetEdge : nearestEdges) {
            LineString targetLine = routeLine(targetEdge);
            LengthIndexedLine indexed = new LengthIndexedLine(targetLine);
            double length = targetLine.getLength();
            List<Double> indexes = boundedWholeTreeSearch
                    ? List.of(indexed.project(demand.coordinate))
                    : List.of(indexed.project(demand.coordinate), length * 0.50);
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
                int diameter = diameterFor(demand.flowTph);
                RoutePath branch = routeDemand(
                        demand, split.coordinate(), diameter, routingEnvironment,
                        Collections.emptySet(), preference, acceptedRoutes);
                if (branch == null) {
                    continue;
                }
                branch = preferPerpendicularBranchApproach(
                        demand,
                        targetLine,
                        split.coordinate(),
                        diameter,
                        routingEnvironment,
                        acceptedRoutes,
                        branch);
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
                if (candidate != null) {
                    attachments.add(candidate);
                }
            }
        }
        // Стоимость ранжирует кандидатов, но не заменяет проверку после увеличения ДУ.
        attachments.sort(Comparator.comparing((TreeAttachment candidate) -> candidate.totalNetworkCost)
                .thenComparingDouble(candidate -> candidate.branchPath.lengthM())
                .thenComparing(candidate -> candidate.signature));
        for (TreeAttachment candidate : attachments) {
            VariantDraft simulation = draft.copy();
            addTreeAttachment(simulation, demand, candidate);
            if (isFinalGeometryValid(simulation, routingEnvironment)) {
                return candidate;
            }
        }
        return null;
    }

    /** Даёт четвёртому лучу короткий правильный подход вместо безусловного создания новой камеры. */
    private List<RoutePath> constructibleBranchApproaches(Demand demand, Coordinate junction, int diameter,
            List<RouteEdge> incident, OfficialRoutingEnvironment environment,
            List<LineString> avoidance, RoutePreference preference) {
        List<Coordinate> rays = incident.stream().map(edge -> rayAtNode(edge, junction))
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        List<RoutePath> result = new ArrayList<>();
        if (rays.size() != incident.size()) return result;
        // Дополнительные подходы расширяют прежний поиск; ограничиваем только новую локальную ветку.
        List<Coordinate> waypoints = new ChamberApproachCandidates().build(
                junction, rays, CHAMBER_APPROACH_LENGTH_M, JUNCTION_ANGLE_TOLERANCE_DEGREES).stream()
                .sorted(Comparator.comparingDouble((Coordinate point) -> point.distance(demand.routingStart()))
                        .thenComparingDouble(point -> point.x).thenComparingDouble(point -> point.y))
                .limit(MAX_ADDITIONAL_CHAMBER_APPROACHES).collect(Collectors.toList());
        for (Coordinate waypoint : waypoints) {
            if (!obstacleRouter.lineAllowed(List.of(waypoint, junction), diameter, environment, Set.of(), avoidance)) continue;
            RoutePath suffix = obstacleRouter.find(waypoint, junction, diameter, environment,
                    Set.of(), preference, avoidance);
            if (suffix == null || suffix.coordinates().size() != 2) continue;
            RoutePath approach = routeDemandTowards(demand, waypoint, diameter, environment, avoidance, preference);
            if (approach == null) continue;
            List<Coordinate> coordinates = new ArrayList<>(approach.coordinates());
            coordinates.add(suffix.coordinates().get(1));
            List<RouteSection> sections = new ArrayList<>(approach.sections());
            sections.addAll(suffix.sections());
            RoutePath path = new RoutePath(coordinates, sections, approach.lengthM() + suffix.lengthM());
            if (constructibleAdditionalRay(junction, path, incident)) result.add(path);
        }
        return result;
    }

    /**
     * Prevents a cheap but unbuildable star: every new ray entering an existing chamber must make
     * a straight, 45-degree or 90-degree construction direction with all rays already present.
     * A rejected attachment may still be connected through a separate chamber on an adjacent edge.
     */
    private boolean constructibleAdditionalRay(
            Coordinate junction,
            RoutePath branch,
            List<RouteEdge> incidentEdges) {
        Coordinate newRay = rayAtPathEnd(branch, junction);
        if (newRay == null) {
            return false;
        }
        for (RouteEdge edge : incidentEdges) {
            Coordinate existingRay = rayAtNode(edge, junction);
            if (existingRay == null || !isConstructibleRayPair(newRay, existingRay)) {
                return false;
            }
        }
        return true;
    }

    private Coordinate rayAtPathEnd(RoutePath path, Coordinate junction) {
        List<Coordinate> coordinates = path.coordinates();
        if (coordinates.size() < 2) {
            return null;
        }
        Coordinate adjacent = coordinates.get(coordinates.size() - 2);
        return ray(junction, adjacent);
    }

    private Coordinate rayAtNode(RouteEdge edge, Coordinate junction) {
        List<RouteCoordinate> coordinates = edge.getCoordinates();
        if (coordinates.size() < 2) {
            return null;
        }
        Coordinate first = coordinates.get(0).toCoordinate();
        Coordinate last = coordinates.get(coordinates.size() - 1).toCoordinate();
        Coordinate adjacent = first.distance(junction) <= last.distance(junction)
                ? coordinates.get(1).toCoordinate()
                : coordinates.get(coordinates.size() - 2).toCoordinate();
        return ray(junction, adjacent);
    }

    private Coordinate ray(Coordinate origin, Coordinate destination) {
        double dx = destination.x - origin.x;
        double dy = destination.y - origin.y;
        return Math.hypot(dx, dy) <= LENGTH_EPSILON_M ? null : new Coordinate(dx, dy);
    }

    private boolean isConstructibleRayPair(Coordinate first, Coordinate second) {
        return isConstructibleRayPair(first, second, JUNCTION_ANGLE_TOLERANCE_DEGREES);
    }

    private boolean isConstructibleRayPair(
            Coordinate first,
            Coordinate second,
            double toleranceDegrees) {
        double denominator = Math.hypot(first.x, first.y) * Math.hypot(second.x, second.y);
        if (denominator <= LENGTH_EPSILON_M) {
            return false;
        }
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
                (first.x * second.x + first.y * second.y) / denominator))));
        double deviation = Math.min(
                Math.abs(angle - 45.0),
                Math.min(Math.abs(angle - 90.0),
                        Math.min(Math.abs(angle - 135.0), Math.abs(angle - 180.0))));
        return deviation <= toleranceDegrees;
    }

    /**
     * A graft chamber is easier to build when the new branch reaches the supporting trunk at a
     * right angle. Keep the ordinary shortest branch as the baseline and accept a four-metre
     * perpendicular final leg only when it is legal and adds at most five percent locally.
     */
    private RoutePath preferPerpendicularBranchApproach(
            Demand demand,
            LineString supportingEdge,
            Coordinate junction,
            int diameter,
            OfficialRoutingEnvironment routingEnvironment,
            List<LineString> acceptedRoutes,
            RoutePath shortest) {
        Coordinate waypoint = perpendicularApproachWaypoint(
                supportingEdge, junction, demand.routingStart());
        if (waypoint == null) {
            return shortest;
        }
        RoutePath approach = routeDemand(
                demand,
                waypoint,
                diameter,
                routingEnvironment,
                Collections.emptySet(),
                RoutePreference.SHORTEST,
                acceptedRoutes);
        if (approach == null) {
            return shortest;
        }
        RoutePath perpendicular = obstacleRouter.withCheckedTerminalSuffix(
                approach, junction, diameter, routingEnvironment, Collections.emptySet(), acceptedRoutes);
        if (perpendicular == null || perpendicular.lengthM() > shortest.lengthM() * 1.05) {
            return shortest;
        }
        return perpendicular;
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
        List<RouteCoordinate> coordinates = edge.getCoordinates();
        Coordinate approach = coordinates.size() >= 2
                ? coordinates.get(coordinates.size() - 2).toCoordinate()
                : edge.getCoordinates().get(0).toCoordinate();
        OfficialRouteGeometryRules.NormalEgress egress = routingEnvironment.normalEgressTowards(
                        diameter, downstream.getCoordinate().toCoordinate(), approach)
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
            Map<String, ImportedOfficialFeature> featuresById,
            OfficialRoutingEnvironment routingEnvironment,
            List<VariantDraft> controls) {
        VariantDraft current = initial;
        // Удешевление дерева может удлинить трассу. Сохраняем уже построенные альтернативы,
        // чтобы окончательный отбор мог сравнить их без повторного поиска маршрутов.
        addDistinctDraft(controls, current.copy());
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
                boolean detourRepair = current.edges.stream()
                        .filter(edge -> edge.getDownstreamNodeId().equals("demand:" + demand.id))
                        .anyMatch(edge -> edgeDetourRatio(edge) > EXCESSIVE_DETOUR_RATIO);
                VariantDraft withoutDemand = detachDemandAndNormalize(current, demand);
                if (withoutDemand == null || withoutDemand.edges.isEmpty()) {
                    continue;
                }
                TreeAttachment attachment = chooseTreeAttachment(
                        demand, withoutDemand, routingEnvironment, true, detourRepair);
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
                    addDistinctDraft(controls, current.copy());
                    acceptedMoves++;
                    improved = true;
                    break;
                }
            }
        } while (improved && acceptedMoves < MAX_WHOLE_TREE_ACCEPTED_MOVES);
        current = relocateNewTieInChambers(current, featuresById, routingEnvironment);
        addDistinctDraft(controls, current.copy());
        return mergeAdjacentBranchChambers(current, demands, routingEnvironment);
    }

    /**
     * Reprojects a new tie-in chamber after the shared tree has been assembled. The initial point
     * is selected for one or two terminals; retaining it after grafting can leave the final trunk
     * behind an OKS and introduce an avoidable obstacle detour.
     */
    private VariantDraft relocateNewTieInChambers(
            VariantDraft source,
            Map<String, ImportedOfficialFeature> featuresById,
            OfficialRoutingEnvironment routingEnvironment) {
        VariantDraft current = source;
        List<String> rootIds = source.nodes.values().stream()
                .filter(RouteNode::isRoot)
                .filter(node -> "new_tie_in_chamber".equals(node.getNodeType()))
                .filter(node -> node.getTargetId() != null)
                .map(RouteNode::getId)
                .sorted()
                .collect(Collectors.toList());
        for (String rootId : rootIds) {
            RouteNode root = current.nodes.get(rootId);
            ImportedOfficialFeature supportingFeature = root == null
                    ? null
                    : featuresById.get(root.getTargetId());
            if (supportingFeature == null
                    || !"heat_network".equals(supportingFeature.getObjectType())) {
                continue;
            }
            List<RouteEdge> incident = incidentEdges(current, rootId);
            if (incident.size() != 1) {
                continue;
            }
            RouteEdge rootEdge = incident.get(0);
            String treeNodeId = rootEdge.getUpstreamNodeId().equals(rootId)
                    ? rootEdge.getDownstreamNodeId()
                    : rootEdge.getUpstreamNodeId();
            RouteNode treeNode = current.nodes.get(treeNodeId);
            if (treeNode == null) {
                continue;
            }
            Coordinate projected = closestPointOnLinework(
                    supportingFeature.getMetricGeometry(),
                    treeNode.getCoordinate().toCoordinate());
            if (projected == null
                    || projected.distance(root.getCoordinate().toCoordinate()) <= 0.10
                    || projected.distance(treeNode.getCoordinate().toCoordinate())
                            + CHAMBER_APPROACH_LENGTH_M >= rootEdge.getLengthM().doubleValue()
                    || tooCloseToExistingChamber(projected, featuresById)) {
                continue;
            }
            VariantDraft candidate = relocatedTieInDraft(
                    current, root, treeNode, rootEdge, supportingFeature, projected,
                    routingEnvironment);
            if (candidate == null || !isFinalGeometryValid(candidate, routingEnvironment)) {
                continue;
            }
            BigDecimal currentCost = totalNetworkConstructionCost(current);
            BigDecimal candidateCost = totalNetworkConstructionCost(candidate);
            if (currentCost == null || candidateCost == null) {
                continue;
            }
            double currentLength = totalRouteLength(current);
            double candidateLength = totalRouteLength(candidate);
            if (candidateCost.compareTo(currentCost) < 0
                    || candidateCost.compareTo(currentCost) == 0
                            && candidateLength + LENGTH_EPSILON_M < currentLength) {
                LOGGER.info(
                        "Tie-in chamber relocated target={} old_x={} old_y={} new_x={} new_y={} length_before={} length_after={}",
                        root.getTargetId(),
                        root.getCoordinate().getXM(), root.getCoordinate().getYM(),
                        projected.x, projected.y, currentLength, candidateLength);
                current = candidate;
            }
        }
        return current;
    }

    /**
     * Cost-only topology pass. A new chamber on an existing segment may be replaced by a connected
     * existing chamber even beyond the mandatory 10 m reuse radius, but only when the fully sized
     * and validated network becomes cheaper.
     */
    private VariantDraft reuseExistingChambersForCheapest(
            VariantDraft source,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            OfficialRoutingEnvironment routingEnvironment) {
        VariantDraft current = source;
        BigDecimal currentCost = totalNetworkConstructionCost(current);
        if (currentCost == null) {
            return current;
        }
        List<String> rootIds = source.nodes.values().stream()
                .filter(RouteNode::isRoot)
                .filter(node -> "new_tie_in_chamber".equals(node.getNodeType()))
                .map(RouteNode::getId)
                .sorted()
                .collect(Collectors.toList());
        for (String rootId : rootIds) {
            RouteNode root = current.nodes.get(rootId);
            ImportedOfficialFeature supportingFeature = root == null
                    ? null
                    : featuresById.get(root.getTargetId());
            if (root == null
                    || !"new_tie_in_chamber".equals(root.getNodeType())
                    || supportingFeature == null
                    || !"heat_network".equals(supportingFeature.getObjectType())) {
                continue;
            }
            List<RouteEdge> incident = incidentEdges(current, rootId);
            if (incident.size() != 1) {
                continue;
            }
            RouteEdge rootEdge = incident.get(0);
            String treeNodeId = rootEdge.getUpstreamNodeId().equals(rootId)
                    ? rootEdge.getDownstreamNodeId()
                    : rootEdge.getUpstreamNodeId();
            RouteNode treeNode = current.nodes.get(treeNodeId);
            if (treeNode == null) {
                continue;
            }
            VariantDraft currentSnapshot = current;
            List<ImportedOfficialFeature> chamberAlternatives = featuresById.values().stream()
                    .filter(feature -> "heat_chamber".equals(feature.getObjectType()))
                    .filter(feature -> chamberIncidentCounts.getOrDefault(feature.getFeatureId(), 0) < 4)
                    .filter(feature -> supportingFeature.getMetricGeometry()
                            .distance(feature.getMetricGeometry()) <= OfficialRouteGeometryRules.EPSILON_M)
                    .filter(feature -> root.getCoordinate().toCoordinate()
                            .distance(feature.getMetricGeometry().getCoordinate())
                            <= MAX_OPTIONAL_EXISTING_CHAMBER_DISTANCE_M)
                    .filter(feature -> currentSnapshot.nodes.values().stream()
                            .filter(RouteNode::isRoot)
                            .noneMatch(node -> feature.getFeatureId().equals(node.getTargetId())))
                    .sorted(Comparator.comparingDouble(feature -> root.getCoordinate().toCoordinate()
                            .distance(feature.getMetricGeometry().getCoordinate())))
                    .limit(MAX_EXISTING_CHAMBER_ALTERNATIVES_PER_ROOT)
                    .collect(Collectors.toList());
            VariantDraft best = current;
            BigDecimal bestCost = currentCost;
            double bestLength = totalRouteLength(current);
            ImportedOfficialFeature selectedChamber = null;
            for (ImportedOfficialFeature chamber : chamberAlternatives) {
                Coordinate chamberCoordinate = chamber.getMetricGeometry().getCoordinate();
                List<ImportedOfficialFeature> chamberNetworks = featuresById.values().stream()
                        .filter(feature -> "heat_network".equals(feature.getObjectType()))
                        .filter(feature -> feature.getMetricGeometry()
                                .distance(chamber.getMetricGeometry()) <= OfficialRouteGeometryRules.EPSILON_M)
                        .sorted(Comparator
                                .comparing((ImportedOfficialFeature feature) ->
                                        !feature.getFeatureId().equals(supportingFeature.getFeatureId()))
                                .thenComparing(ImportedOfficialFeature::getFeatureId))
                        .limit(4)
                        .collect(Collectors.toList());
                for (ImportedOfficialFeature approachNetwork : chamberNetworks) {
                    VariantDraft candidate = rewiredTieInDraft(
                            current,
                            root,
                            treeNode,
                            rootEdge,
                            approachNetwork,
                            chamberCoordinate,
                            "existing_chamber_tie_in",
                            chamber.getFeatureId(),
                            chamberIncidentCounts.getOrDefault(chamber.getFeatureId(), 0),
                            "optimized:tie-existing:",
                            routingEnvironment);
                    if (candidate == null || !isFinalGeometryValid(candidate, routingEnvironment)) {
                        continue;
                    }
                    BigDecimal candidateCost = totalNetworkConstructionCost(candidate);
                    double candidateLength = totalRouteLength(candidate);
                    if (candidateCost != null
                            && (candidateCost.compareTo(bestCost) < 0
                                    || candidateCost.compareTo(bestCost) == 0
                                            && candidateLength + LENGTH_EPSILON_M < bestLength)) {
                        best = candidate;
                        bestCost = candidateCost;
                        bestLength = candidateLength;
                        selectedChamber = chamber;
                    }
                }
            }
            if (best != current) {
                LOGGER.info(
                        "Cheapest variant reused chamber={} instead_of_segment={} cost_before={} cost_after={} length_after={}",
                        selectedChamber == null ? null : selectedChamber.getFeatureId(),
                        root.getTargetId(), currentCost, bestCost, bestLength);
                current = best;
                currentCost = bestCost;
            }
        }
        return current;
    }

    private VariantDraft relocatedTieInDraft(
            VariantDraft source,
            RouteNode root,
            RouteNode treeNode,
            RouteEdge rootEdge,
            ImportedOfficialFeature supportingFeature,
            Coordinate projected,
            OfficialRoutingEnvironment routingEnvironment) {
        return rewiredTieInDraft(
                source,
                root,
                treeNode,
                rootEdge,
                supportingFeature,
                projected,
                root.getNodeType(),
                root.getTargetId(),
                root.getBaseIncidentSections(),
                "optimized:tie-relocate:",
                routingEnvironment);
    }

    private VariantDraft rewiredTieInDraft(
            VariantDraft source,
            RouteNode root,
            RouteNode treeNode,
            RouteEdge rootEdge,
            ImportedOfficialFeature supportingFeature,
            Coordinate projected,
            String replacementNodeType,
            String replacementTargetId,
            int replacementBaseIncidentSections,
            String edgeIdPrefix,
            OfficialRoutingEnvironment routingEnvironment) {
        LineString supportingLine = closestLineString(
                supportingFeature.getMetricGeometry(), projected);
        if (supportingLine == null) {
            return null;
        }
        Coordinate treeCoordinate = treeNode.getCoordinate().toCoordinate();
        Coordinate waypoint = perpendicularApproachWaypoint(
                supportingLine, projected, treeCoordinate);
        if (waypoint == null) {
            return null;
        }
        List<LineString> acceptedRoutes = source.edges.stream()
                .filter(edge -> !edge.getId().equals(rootEdge.getId()))
                .filter(edge -> !edge.getUpstreamNodeId().equals(treeNode.getId()))
                .filter(edge -> !edge.getDownstreamNodeId().equals(treeNode.getId()))
                .filter(edge -> edge.getCoordinates().size() >= 2)
                .map(this::routeLine)
                .collect(Collectors.toList());
        RoutePath treeToWaypoint = obstacleRouter.find(
                treeCoordinate,
                waypoint,
                rootEdge.getDiameter(),
                routingEnvironment,
                Set.of(supportingFeature.getFeatureId()),
                RoutePreference.SHORTEST,
                acceptedRoutes);
        if (treeToWaypoint == null) {
            return null;
        }
        RoutePath treeToTieIn = treeToWaypoint.withMandatorySuffix(projected);
        List<RouteEdge> remainingAtTreeNode = incidentEdges(source, treeNode.getId()).stream()
                .filter(edge -> !edge.getId().equals(rootEdge.getId()))
                .collect(Collectors.toList());
        if (!constructibleAdditionalRay(
                treeCoordinate, treeToTieIn.reversed(), remainingAtTreeNode)) {
            treeToTieIn = constructibleTieInPath(
                    projected,
                    waypoint,
                    treeNode,
                    remainingAtTreeNode,
                    rootEdge.getDiameter(),
                    routingEnvironment,
                    supportingFeature.getFeatureId(),
                    acceptedRoutes);
        }
        if (treeToTieIn == null) {
            return null;
        }
        if (!obstacleRouter.lineAllowed(
                treeToTieIn.coordinates(),
                rootEdge.getDiameter(),
                routingEnvironment,
                Set.of(supportingFeature.getFeatureId()),
                acceptedRoutes)) {
            return null;
        }
        if (!constructibleAdditionalRay(
                treeCoordinate, treeToTieIn.reversed(), remainingAtTreeNode)) {
            return null;
        }
        VariantDraft result = source.copy();
        result.nodes.put(root.getId(), routingEnvironment.verifiedRootSupport(new RouteNode(
                root.getId(),
                replacementNodeType,
                new RouteCoordinate(projected.x, projected.y),
                root.isChamber(),
                true,
                replacementBaseIncidentSections,
                replacementTargetId)));
        result.edges.removeIf(edge -> edge.getId().equals(rootEdge.getId()));
        boolean rootIsUpstream = rootEdge.getUpstreamNodeId().equals(root.getId());
        RoutePath oriented = rootIsUpstream ? treeToTieIn.reversed() : treeToTieIn;
        result.addEdge(routeEdge(
                edgeIdPrefix + rootEdge.getId(),
                rootEdge.getUpstreamNodeId(),
                rootEdge.getDownstreamNodeId(),
                oriented,
                rootEdge.getFlowTph(),
                rootEdge.getDiameter()));
        return isStructurallyValid(result) ? result : null;
    }

    private RoutePath constructibleTieInPath(
            Coordinate tieIn,
            Coordinate tieInApproach,
            RouteNode treeNode,
            List<RouteEdge> existingTreeRays,
            int diameter,
            OfficialRoutingEnvironment routingEnvironment,
            String supportingFeatureId,
            List<LineString> acceptedRoutes) {
        Coordinate treeCoordinate = treeNode.getCoordinate().toCoordinate();
        for (Coordinate treeApproach : constructibleApproachWaypoints(
                treeCoordinate, tieInApproach, existingTreeRays)) {
            RoutePath betweenApproaches = obstacleRouter.find(
                    tieInApproach,
                    treeApproach,
                    diameter,
                    routingEnvironment,
                    Set.of(supportingFeatureId),
                    RoutePreference.SHORTEST,
                    acceptedRoutes);
            if (betweenApproaches == null) {
                continue;
            }
            RoutePath candidate = betweenApproaches
                    .withMandatoryPrefix(tieIn)
                    .withMandatorySuffix(treeCoordinate);
            if (constructibleAdditionalRay(
                    treeCoordinate, candidate, existingTreeRays)
                    && obstacleRouter.lineAllowed(
                            candidate.coordinates(),
                            diameter,
                            routingEnvironment,
                            Set.of(supportingFeatureId),
                            acceptedRoutes)) {
                return candidate.reversed();
            }
        }
        return null;
    }

    private List<Coordinate> constructibleApproachWaypoints(
            Coordinate junction,
            Coordinate origin,
            List<RouteEdge> existingEdges) {
        List<Coordinate> candidates = new ArrayList<>();
        for (RouteEdge edge : existingEdges) {
            Coordinate existingRay = rayAtNode(edge, junction);
            if (existingRay == null) {
                continue;
            }
            double baseAngle = Math.atan2(existingRay.y, existingRay.x);
            for (int eighthTurn = 1; eighthTurn < 8; eighthTurn++) {
                double angle = baseAngle + eighthTurn * Math.PI / 4.0;
                Coordinate candidate = new Coordinate(
                        junction.x + Math.cos(angle) * CHAMBER_APPROACH_LENGTH_M,
                        junction.y + Math.sin(angle) * CHAMBER_APPROACH_LENGTH_M);
                Coordinate candidateRay = ray(junction, candidate);
                boolean constructible = candidateRay != null && existingEdges.stream()
                        .map(existing -> rayAtNode(existing, junction))
                        .filter(java.util.Objects::nonNull)
                        .allMatch(ray -> isConstructibleRayPair(candidateRay, ray));
                if (constructible
                        && candidates.stream().noneMatch(existing -> existing.distance(candidate) < 0.10)) {
                    candidates.add(candidate);
                }
            }
        }
        candidates.sort(Comparator
                .comparingDouble((Coordinate candidate) -> candidate.distance(origin))
                .thenComparingDouble(candidate -> candidate.x)
                .thenComparingDouble(candidate -> candidate.y));
        return candidates.stream().limit(2).collect(Collectors.toList());
    }

    private Coordinate closestPointOnLinework(Geometry geometry, Coordinate origin) {
        LineString line = closestLineString(geometry, origin);
        if (line == null) {
            return null;
        }
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        return indexed.extractPoint(indexed.project(origin));
    }

    private LineString closestLineString(Geometry geometry, Coordinate origin) {
        LineString closest = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < geometry.getNumGeometries(); index++) {
            Geometry part = geometry.getGeometryN(index);
            if (!(part instanceof LineString)) {
                continue;
            }
            LineString line = (LineString) part;
            double distance = line.distance(geometryFactory.createPoint(origin));
            if (distance < bestDistance) {
                closest = line;
                bestDistance = distance;
            }
        }
        return closest;
    }

    private boolean tooCloseToExistingChamber(
            Coordinate coordinate,
            Map<String, ImportedOfficialFeature> featuresById) {
        return featuresById.values().stream()
                .filter(feature -> "heat_chamber".equals(feature.getObjectType()))
                .anyMatch(feature -> feature.getMetricGeometry()
                        .distance(geometryFactory.createPoint(coordinate))
                        <= EXISTING_CHAMBER_REUSE_DISTANCE_M + LENGTH_EPSILON_M);
    }

    /**
     * Replaces two short-linked degree-three chambers with one degree-four chamber. All four outer
     * branches are routed again as a group, so the operation can move the junction to the actual
     * intersection instead of merely deleting one node and overlapping two pipes on the old link.
     */
    private VariantDraft mergeAdjacentBranchChambers(
            VariantDraft source,
            List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment) {
        VariantDraft merged = bestMergedBranchChamberVariant(
                source, demands, routingEnvironment, true);
        return merged == null ? source : merged;
    }

    /**
     * Evaluates each expensive chamber-merge topology once and keeps separate winners for length
     * and construction cost. Previously the same obstacle-routing search ran twice.
     */
    private MergedBranchAlternatives mergedBranchChamberAlternatives(
            VariantDraft source,
            List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment) {
        List<VariantDraft> candidates = mergedBranchChamberCandidates(
                source, demands, routingEnvironment);
        VariantDraft shortest = null;
        VariantDraft cheapest = null;
        for (VariantDraft candidate : candidates) {
            shortest = shorterDraft(shortest, candidate);
            cheapest = cheaperDraft(cheapest, candidate);
        }
        return new MergedBranchAlternatives(shortest, cheapest, candidates);
    }

    private VariantDraft bestMergedBranchChamberVariant(
            VariantDraft source,
            List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment,
            boolean requireScoreImprovement) {
        VariantDraft best = requireScoreImprovement ? source : null;
        BigDecimal bestScore = requireScoreImprovement ? draftScore(source) : null;
        for (VariantDraft merged : mergedBranchChamberCandidates(source, demands, routingEnvironment)) {
            BigDecimal score = draftScore(merged);
            LOGGER.info("Chamber merge candidate score={} control_score={}", score, bestScore);
            if (score != null && (bestScore == null || score.compareTo(bestScore) < 0)) {
                best = merged;
                bestScore = score;
            }
        }
        return best;
    }

    private List<VariantDraft> mergedBranchChamberCandidates(
            VariantDraft source,
            List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment) {
        return assessedChamberMergeCandidates(source, demands, routingEnvironment,
                MAX_CHAMBER_MERGE_LINK_M, Integer.MAX_VALUE, Comparator.comparing(RouteEdge::getId), false)
                .stream().map(AssessedRouteDraft::draft).collect(Collectors.toList());
    }

    /**
     * Ограниченный поиск общих камер для коридорной сети: сначала ближайшие соседние тройники.
     * Возвращает только черновики; конечные ДУ, стоимость, глубину и углы надо проверить повторно.
     */
    List<VariantDraft> corridorChamberMergeCandidates(VariantDraft source, List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment) {
        return assessedCorridorChamberMergeCandidates(source, demands, routingEnvironment)
                .stream().map(AssessedRouteDraft::draft).collect(Collectors.toList());
    }

    private List<AssessedRouteDraft> assessedCorridorChamberMergeCandidates(VariantDraft source,
            List<Demand> demands, OfficialRoutingEnvironment environment) {
        return assessedChamberMergeCandidates(source, demands, environment, 80.0, 4,
                Comparator.comparing(RouteEdge::getLengthM).thenComparing(RouteEdge::getId), true);
    }

    /** Улучшает только три выбранные роли, сохраняя исходные сети для повторного итогового отбора. */
    List<RouteVariant> relocateSelectedVariants(List<RouteVariant> selected, List<Demand> demands,
            List<ImportedOfficialFeature> features, OfficialRunParameters parameters,
            boolean reconstructionRequired, OfficialRoutingEnvironment environment) {
        List<RouteVariant> alternatives = new ArrayList<>(selected);
        for (RouteVariant original : selected) {
            RouteVariant improved = relocateFinishedChambers(original, demands, features,
                    parameters, reconstructionRequired, environment);
            if (improved == original) continue;
            alternatives.add(new RouteVariant("relocated-" + original.getId(), improved.getStrategy(),
                    improved.getNodes(), improved.getEdges(), improved.getConnections(), improved.getTotalLengthM(),
                    improved.getValidationIssues(), improved.getEngineeringIssues(), improved.getSizingIssues(),
                    improved.getReconstruction(), improved.getEconomics(), null));
            LOGGER.info("Chamber relocation role={} length_before_m={} length_after_m={} bends_before={} bends_after={}",
                    original.getId(), original.getTotalLengthM(), improved.getTotalLengthM(),
                    engineeringEvaluator.evaluate(original.getEdges()).bendCount(),
                    engineeringEvaluator.evaluate(improved.getEdges()).bendCount());
        }
        return new FinishedRouteVariantSelector().select(alternatives, parameters.isDepthEnabled());
    }

    /**
     * Согласованно переносит новую камеру и её подходы ограниченным локальным поиском.
     * Принимает только полностью пересчитанную сеть без ухудшения длины, цены и геометрии.
     */
    RouteVariant relocateFinishedChambers(RouteVariant baseline, List<Demand> demands,
            List<ImportedOfficialFeature> features, OfficialRunParameters parameters,
            boolean reconstructionRequired, OfficialRoutingEnvironment environment) {
        if (!baseline.isValid() || !baseline.getEconomics().isComplete()) return baseline;
        Map<String, Demand> demandsByNode = demands.stream().collect(Collectors.toMap(
                demand -> "demand:" + demand.id, demand -> demand));
        RouteVariant current = baseline;
        for (int pass = 0; pass < 2; pass++) {
            VariantDraft source = new VariantDraft(current.getNodes(), current.getEdges(), current.getConnections());
            List<RouteNode> chambers = current.getNodes().stream().filter(this::mergeableBranchChamber)
                    .filter(node -> incidentEdges(source, node.getId()).size() >= 3
                            && incidentEdges(source, node.getId()).size() <= 4)
                    .sorted(Comparator.comparingDouble((RouteNode node) -> chamberApproachDetour(source, node)).reversed()
                            .thenComparing(RouteNode::getId)).limit(4).collect(Collectors.toList());
            Map<String, AssessedRouteDraft> drafts = new LinkedHashMap<>();
            EngineeringRouteEvaluator.Evaluation currentGeometry = engineeringEvaluator.evaluate(current.getEdges());
            for (RouteNode chamber : chambers) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Chamber relocation cancelled");
                List<RouteEdge> incident = incidentEdges(source, chamber.getId()).stream()
                        .sorted(Comparator.comparing(RouteEdge::getId)).collect(Collectors.toList());
                int diameter = incident.stream().mapToInt(RouteEdge::getDiameter).max().orElseThrow();
                org.locationtech.jts.geom.Envelope bounds = new org.locationtech.jts.geom.Envelope(chamber.getCoordinate().toCoordinate());
                bounds.expandBy(40.001);
                java.util.function.Predicate<Coordinate> blocked = environment.preparePointClearance(diameter, bounds);
                for (Coordinate at : CorridorChamberRelocations.build(chamber, incident,
                        corridorEdgeOrientation(incident), point -> !blocked.test(point)
                                && source.nodes.values().stream().noneMatch(node ->
                                        node.getCoordinate().toCoordinate().distance(point) <= 0.01))) {
                    VariantDraft candidate = rebuildBranchJunction(source, Set.of(chamber.getId()), Set.of(),
                            chamber.getId(), "", incident, at, demandsByNode, environment, true, true);
                    if (candidate == null) continue;
                    EngineeringRouteEvaluator.Evaluation geometry = engineeringEvaluator.evaluate(candidate.edges);
                    if (!improvesJunctionGeometry(currentGeometry, geometry)
                            || !isFinalGeometryValid(candidate, environment)) continue;
                    BigDecimal score = draftScore(candidate);
                    if (score != null) drafts.putIfAbsent(draftGeometrySignature(candidate),
                            new AssessedRouteDraft(candidate, score, totalRouteLength(candidate), geometry.bendCount()));
                }
            }
            List<VariantDraft> selected = AssessedRouteDraft.byScoreAndBends(
                    new ArrayList<>(drafts.values()), this::draftGeometrySignature);
            RouteVariant best = current;
            for (VariantDraft draft : selected) {
                // Не теряем ранее допустимый способ доводки. Для уже ограниченного отбора
                // сравниваем и вариант, сохраняющий совместно построенные подходы к камере.
                for (TerminalApproachPolicy policy : TerminalApproachPolicy.values()) {
                    RouteVariant candidate = withEngineeringAssessment(finish(current.getId(), current.getStrategy(),
                            draft, features, parameters, reconstructionRequired, environment, policy));
                    if (!candidate.isValid() || !candidate.getEconomics().isComplete()
                            || candidate.getTotalLengthM().compareTo(current.getTotalLengthM()) > 0
                            || candidate.getEconomics().getCalculatedCost().compareTo(current.getEconomics().getCalculatedCost()) > 0
                            || !improvesJunctionGeometry(current.getEdges(), candidate.getEdges())
                            || (parameters.isDepthEnabled() && candidate.getEdges().stream().anyMatch(edge ->
                                    edge.getDepthProfile() == null || !edge.getDepthProfile().isComplete()
                                            || !edge.getDepthProfile().getIssues().isEmpty()))) continue;
                    if (best == current || candidate.getEconomics().getScore().compareTo(best.getEconomics().getScore()) < 0) best = candidate;
                }
            }
            if (best == current) break;
            current = best;
        }
        return current;
    }

    /** Приоритет локальной эвристики: суммарные обходы у узла, не новая стоимость строительства. */
    private double chamberApproachDetour(VariantDraft source, RouteNode chamber) {
        return incidentEdges(source, chamber.getId()).stream().mapToDouble(edge -> {
            Coordinate first = edge.getCoordinates().get(0).toCoordinate();
            Coordinate last = edge.getCoordinates().get(edge.getCoordinates().size() - 1).toCoordinate();
            return Math.max(0, edge.getLengthM().doubleValue() - first.distance(last));
        }).sum();
    }

    private boolean improvesJunctionGeometry(List<RouteEdge> beforeEdges, List<RouteEdge> afterEdges) {
        return improvesJunctionGeometry(engineeringEvaluator.evaluate(beforeEdges), engineeringEvaluator.evaluate(afterEdges));
    }

    private boolean improvesJunctionGeometry(EngineeringRouteEvaluator.Evaluation before,
            EngineeringRouteEvaluator.Evaluation after) {
        return (after.bendCount() < before.bendCount() || after.irregularJunctionAngleCount() < before.irregularJunctionAngleCount())
                && after.bendCount() <= before.bendCount()
                && after.invalidAngleCount() <= before.invalidAngleCount()
                && after.insufficientSpacingCount() <= before.insufficientSpacingCount()
                && after.irregularJunctionAngleCount() <= before.irregularJunctionAngleCount()
                && after.totalAngleDeviation() <= before.totalAngleDeviation() + 1e-7
                && after.preferredAngleDeviation() <= before.preferredAngleDeviation() + 1e-7
                && after.totalJunctionAngleDeviation() <= before.totalJunctionAngleDeviation() + 1e-7;
    }

    /** Окончательный допуск после каждого объединения, с ограничением дорогих завершений. */
    List<RouteVariant> refineCorridorVariants(List<RouteVariant> seeds, List<Demand> demands,
            List<ImportedOfficialFeature> features, OfficialRunParameters parameters,
            boolean reconstructionRequired, OfficialRoutingEnvironment environment) {
        int[] nextId = {0};
        return CorridorRefinementSearch.improve(seeds, parameters.isDepthEnabled(), current -> {
            List<AssessedRouteDraft> candidates = assessedCorridorChamberMergeCandidates(
                    new VariantDraft(current.getNodes(), current.getEdges(), current.getConnections()), demands, environment);
            // Сохраняем ограниченные представители по черновому score и длине. Это бюджет эвристики,
            // не доказательство доминирования: исходные полные сети остаются в общем portfolio.
            List<VariantDraft> selected = AssessedRouteDraft.byScoreAndLength(candidates, this::draftGeometrySignature);
            List<RouteVariant> completed = new ArrayList<>();
            for (VariantDraft draft : selected) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Corridor finish cancelled");
                RouteVariant variant = withEngineeringAssessment(finish("corridor-refined-" + nextId[0]++,
                        "engineering", draft, features, parameters, reconstructionRequired, environment));
                completed.add(variant);
                logVariantSummary(variant);
            }
            return completed;
        });
    }

    private List<AssessedRouteDraft> assessedChamberMergeCandidates(VariantDraft source, List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment, double maximumLinkM, int maximumLinks,
            Comparator<RouteEdge> linkOrder, boolean orthogonalApproaches) {
        Map<String, Demand> demandsByNode = demands.stream().collect(Collectors.toMap(
                demand -> "demand:" + demand.id,
                demand -> demand,
                (left, right) -> left,
                LinkedHashMap::new));
        List<AssessedRouteDraft> candidates = new ArrayList<>();
        List<RouteEdge> links = source.edges.stream()
                .filter(edge -> edge.getLengthM().doubleValue() <= maximumLinkM)
                .filter(edge -> mergeableBranchChamber(source.nodes.get(edge.getUpstreamNodeId()))
                        && mergeableBranchChamber(source.nodes.get(edge.getDownstreamNodeId())))
                .filter(edge -> incidentEdges(source, edge.getUpstreamNodeId()).size() == 3
                        && incidentEdges(source, edge.getDownstreamNodeId()).size() == 3)
                .sorted(linkOrder)
                .limit(maximumLinks)
                .collect(Collectors.toList());
        for (RouteEdge link : links) {
            RouteNode left = source.nodes.get(link.getUpstreamNodeId());
            RouteNode right = source.nodes.get(link.getDownstreamNodeId());
            if (!mergeableBranchChamber(left) || !mergeableBranchChamber(right)) {
                continue;
            }
            List<RouteEdge> leftIncident = incidentEdges(source, left.getId());
            List<RouteEdge> rightIncident = incidentEdges(source, right.getId());
            if (leftIncident.size() != 3 || rightIncident.size() != 3) {
                continue;
            }
            List<RouteEdge> outerEdges = java.util.stream.Stream
                    .concat(leftIncident.stream(), rightIncident.stream())
                    .filter(edge -> !edge.getId().equals(link.getId()))
                    .distinct()
                    .sorted(Comparator.comparing(RouteEdge::getId))
                    .collect(Collectors.toList());
            if (outerEdges.size() != 4) {
                continue;
            }
            List<Coordinate> outerCoordinates = outerEdges.stream()
                    .map(edge -> outerNode(source, edge, left.getId(), right.getId()))
                    .filter(java.util.Objects::nonNull)
                    .map(node -> node.getCoordinate().toCoordinate())
                    .collect(Collectors.toList());
            if (outerCoordinates.size() != 4) {
                continue;
            }
            List<Coordinate> locations = mergedChamberCandidates(left, right, outerCoordinates);
            if (orthogonalApproaches) {
                for (Coordinate point : CorridorChamberLocations.build(left.getCoordinate().toCoordinate(),
                        right.getCoordinate().toCoordinate(), outerCoordinates, corridorEdgeOrientation(outerEdges))) {
                    if (locations.stream().noneMatch(existing -> existing.distance(point) < 0.01)) locations.add(point);
                }
            }
            for (Coordinate candidate : locations) {
                VariantDraft merged = buildMergedChamberDraft(
                        source, left, right, link, outerEdges, candidate,
                        demandsByNode, routingEnvironment, orthogonalApproaches);
                if (merged == null) {
                    LOGGER.debug("Chamber merge candidate rejected during routing link={} x={} y={}",
                            link.getId(), candidate.x, candidate.y);
                    continue;
                }
                if (!isFinalGeometryValid(merged, routingEnvironment)) {
                    LOGGER.debug("Chamber merge candidate rejected by final geometry link={} x={} y={}",
                            link.getId(), candidate.x, candidate.y);
                    continue;
                }
                BigDecimal score = draftScore(merged);
                if (score != null) {
                    LOGGER.info("Valid chamber merge candidate link={} x={} y={}",
                            link.getId(), candidate.x, candidate.y);
                    candidates.add(new AssessedRouteDraft(merged, score, totalRouteLength(merged), 0));
                }
            }
        }
        return candidates;
    }

    private VariantDraft shorterDraft(VariantDraft left, VariantDraft right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        double leftLength = totalRouteLength(left);
        double rightLength = totalRouteLength(right);
        if (rightLength + LENGTH_EPSILON_M < leftLength) {
            return right;
        }
        if (Math.abs(rightLength - leftLength) <= LENGTH_EPSILON_M) {
            return cheaperDraft(left, right);
        }
        return left;
    }

    private VariantDraft cheaperDraft(VariantDraft left, VariantDraft right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        BigDecimal leftCost = totalNetworkConstructionCost(left);
        BigDecimal rightCost = totalNetworkConstructionCost(right);
        if (leftCost == null) {
            return rightCost == null ? shorterDraftWithoutCost(left, right) : right;
        }
        if (rightCost == null) {
            return left;
        }
        int comparison = rightCost.compareTo(leftCost);
        return comparison < 0
                ? right
                : comparison > 0 ? left : shorterDraftWithoutCost(left, right);
    }

    private VariantDraft shorterDraftWithoutCost(VariantDraft left, VariantDraft right) {
        return totalRouteLength(right) + LENGTH_EPSILON_M < totalRouteLength(left) ? right : left;
    }

    private void addDistinctDraft(List<VariantDraft> portfolio, VariantDraft candidate) {
        if (candidate == null) {
            return;
        }
        String signature = draftGeometrySignature(candidate);
        boolean duplicate = portfolio.stream()
                .anyMatch(existing -> draftGeometrySignature(existing).equals(signature));
        if (!duplicate) {
            portfolio.add(candidate);
        }
    }

    private String draftGeometrySignature(VariantDraft draft) {
        return draft.edges.stream()
                .map(edge -> edge.getUpstreamNodeId()
                        + "<-" + edge.getDownstreamNodeId()
                        + ":" + edge.getCoordinates().stream()
                                .map(coordinate -> Math.round(coordinate.getXM().doubleValue() * 1000.0)
                                        + "," + Math.round(coordinate.getYM().doubleValue() * 1000.0))
                                .collect(Collectors.joining(";")))
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private VariantDraft selectCheapestDraft(List<VariantDraft> candidates) {
        long maximumCoverage = candidates.stream()
                .mapToLong(this::connectedCount)
                .max()
                .orElse(0L);
        VariantDraft best = null;
        for (VariantDraft candidate : candidates) {
            if (connectedCount(candidate) != maximumCoverage
                    || totalNetworkConstructionCost(candidate) == null) {
                continue;
            }
            best = cheaperDraft(best, candidate);
        }
        return best == null ? candidates.get(0) : best;
    }

    private VariantDraft selectShortestDraft(List<VariantDraft> candidates) {
        long maximumCoverage = candidates.stream()
                .mapToLong(this::connectedCount)
                .max()
                .orElse(0L);
        VariantDraft best = null;
        for (VariantDraft candidate : candidates) {
            if (connectedCount(candidate) == maximumCoverage) {
                best = shorterDraft(best, candidate);
            }
        }
        return best;
    }

    /**
     * Chooses the most constructible complete tree inside a bounded economic/length corridor.
     * The initial five-percent corridor is relaxed once to ten percent when it is empty.
     */
    private VariantDraft selectEngineeringDraft(
            List<VariantDraft> candidates,
            VariantDraft cheapest,
            VariantDraft shortest) {
        if (candidates.isEmpty() || cheapest == null || shortest == null) {
            return null;
        }
        List<VariantDraft> eligible = engineeringCandidatesWithin(
                candidates, cheapest, shortest, ENGINEERING_PRIMARY_DEVIATION_RATIO);
        if (eligible.isEmpty()) {
            eligible = engineeringCandidatesWithin(
                    candidates, cheapest, shortest, ENGINEERING_RELAXED_DEVIATION_RATIO);
        }
        if (eligible.isEmpty()) {
            return null;
        }
        return eligible.stream()
                .min(engineeringDraftComparator())
                .orElse(null);
    }

    private Comparator<VariantDraft> engineeringDraftComparator() {
        return Comparator
                .comparingInt((VariantDraft draft) ->
                        engineeringEvaluator.evaluate(draft.edges).invalidAngleCount())
                .thenComparingInt(draft ->
                        engineeringEvaluator.evaluate(draft.edges).insufficientSpacingCount())
                .thenComparingDouble(draft ->
                        engineeringEvaluator.evaluate(draft.edges).totalAngleDeviation())
                .thenComparingInt(draft ->
                        engineeringEvaluator.evaluate(draft.edges).irregularJunctionAngleCount())
                .thenComparingDouble(draft -> {
                    EngineeringRouteEvaluator.Evaluation evaluation =
                            engineeringEvaluator.evaluate(draft.edges);
                    return evaluation.preferredAngleDeviation()
                            + evaluation.totalJunctionAngleDeviation();
                })
                .thenComparingInt(draft ->
                        engineeringEvaluator.evaluate(draft.edges).bendCount())
                .thenComparingInt(this::newBranchChamberCount)
                .thenComparing(this::totalNetworkConstructionCost)
                .thenComparingDouble(this::totalRouteLength)
                .thenComparing(this::draftGeometrySignature);
    }

    private List<VariantDraft> engineeringCandidatesWithin(
            List<VariantDraft> candidates,
            VariantDraft cheapest,
            VariantDraft shortest,
            double deviationRatio) {
        BigDecimal minimumCost = totalNetworkConstructionCost(cheapest);
        if (minimumCost == null) {
            return Collections.emptyList();
        }
        BigDecimal maximumCost = minimumCost.multiply(BigDecimal.valueOf(1.0 + deviationRatio));
        double maximumLength = totalRouteLength(shortest) * (1.0 + deviationRatio);
        long maximumCoverage = candidates.stream()
                .mapToLong(this::connectedCount)
                .max()
                .orElse(0L);
        return candidates.stream()
                .filter(candidate -> connectedCount(candidate) == maximumCoverage)
                .filter(candidate -> {
                    BigDecimal cost = totalNetworkConstructionCost(candidate);
                    return cost != null && cost.compareTo(maximumCost) <= 0;
                })
                .filter(candidate -> totalRouteLength(candidate) <= maximumLength + LENGTH_EPSILON_M)
                .collect(Collectors.toList());
    }

    private boolean mergeableBranchChamber(RouteNode node) {
        return node != null
                && !node.isRoot()
                && "new_branch_chamber".equals(node.getNodeType())
                && node.getTargetId() == null;
    }

    private List<RouteEdge> incidentEdges(VariantDraft draft, String nodeId) {
        return draft.edges.stream()
                .filter(edge -> edge.getUpstreamNodeId().equals(nodeId)
                        || edge.getDownstreamNodeId().equals(nodeId))
                .collect(Collectors.toList());
    }

    private RouteNode outerNode(
            VariantDraft draft,
            RouteEdge edge,
            String leftId,
            String rightId) {
        String outerId = edge.getUpstreamNodeId().equals(leftId)
                        || edge.getUpstreamNodeId().equals(rightId)
                ? edge.getDownstreamNodeId()
                : edge.getUpstreamNodeId();
        return draft.nodes.get(outerId);
    }

    private List<Coordinate> mergedChamberCandidates(
            RouteNode left,
            RouteNode right,
            List<Coordinate> outer) {
        Coordinate leftCoordinate = left.getCoordinate().toCoordinate();
        Coordinate rightCoordinate = right.getCoordinate().toCoordinate();
        Coordinate center = midpoint(leftCoordinate, rightCoordinate);
        List<Coordinate> raw = new ArrayList<>(List.of(
                leftCoordinate,
                rightCoordinate,
                center));
        int[][] pairings = {{0, 1, 2, 3}, {0, 2, 1, 3}, {0, 3, 1, 2}};
        for (int[] pairing : pairings) {
            Coordinate intersection = lineIntersection(
                    outer.get(pairing[0]), outer.get(pairing[1]),
                    outer.get(pairing[2]), outer.get(pairing[3]));
            if (intersection != null
                    && intersection.distance(center) <= MAX_CHAMBER_MERGE_RELOCATION_M) {
                raw.add(intersection);
            }
        }
        List<Coordinate> result = new ArrayList<>();
        for (Coordinate candidate : raw) {
            if (result.stream().noneMatch(existing -> existing.distance(candidate) < 0.1)) {
                result.add(new Coordinate(candidate));
            }
        }
        return result;
    }

    private Coordinate lineIntersection(
            Coordinate firstStart,
            Coordinate firstEnd,
            Coordinate secondStart,
            Coordinate secondEnd) {
        double firstDx = firstEnd.x - firstStart.x;
        double firstDy = firstEnd.y - firstStart.y;
        double secondDx = secondEnd.x - secondStart.x;
        double secondDy = secondEnd.y - secondStart.y;
        double denominator = firstDx * secondDy - firstDy * secondDx;
        if (Math.abs(denominator) <= LENGTH_EPSILON_M) {
            return null;
        }
        double offsetX = secondStart.x - firstStart.x;
        double offsetY = secondStart.y - firstStart.y;
        double fraction = (offsetX * secondDy - offsetY * secondDx) / denominator;
        return new Coordinate(
                firstStart.x + fraction * firstDx,
                firstStart.y + fraction * firstDy);
    }

    private VariantDraft buildMergedChamberDraft(
            VariantDraft source,
            RouteNode left,
            RouteNode right,
            RouteEdge link,
            List<RouteEdge> outerEdges,
            Coordinate coordinate,
            Map<String, Demand> demandsByNode,
            OfficialRoutingEnvironment routingEnvironment,
            boolean orthogonalApproaches) {
        return rebuildBranchJunction(source, Set.of(left.getId(), right.getId()), Set.of(link.getId()),
                "junction:merge:" + left.getId() + ":" + right.getId(), "optimized:merge:", outerEdges,
                coordinate, demandsByNode, routingEnvironment, orthogonalApproaches, false);
    }

    /** Перестраивает все внешние подходы заменяемого узла; окончательный допуск остаётся у finish. */
    private VariantDraft rebuildBranchJunction(VariantDraft source, Set<String> replacedNodeIds,
            Set<String> internalEdgeIds, String mergedId, String edgePrefix, List<RouteEdge> outerEdges,
            Coordinate coordinate, Map<String, Demand> demandsByNode,
            OfficialRoutingEnvironment routingEnvironment, boolean orthogonalApproaches, boolean localOnly) {
        int maximumDiameter = outerEdges.stream()
                .mapToInt(RouteEdge::getDiameter)
                .max()
                .orElse(0);
        if (routingEnvironment.pointInsideForbiddenClearance(maximumDiameter, coordinate)) {
            return null;
        }
        VariantDraft result = source.copy();
        Set<String> replacedEdgeIds = new HashSet<>(internalEdgeIds);
        outerEdges.forEach(edge -> replacedEdgeIds.add(edge.getId()));
        result.edges.removeIf(edge -> replacedEdgeIds.contains(edge.getId()));
        replacedNodeIds.forEach(result.nodes::remove);
        RouteNode mergedNode = new RouteNode(
                mergedId,
                "new_branch_chamber",
                new RouteCoordinate(coordinate.x, coordinate.y),
                true,
                false,
                0,
                null);
        result.addNode(mergedNode);
        List<LineString> acceptedRoutes = result.edges.stream()
                .filter(edge -> edge.getCoordinates().size() >= 2)
                .map(this::routeLine)
                .collect(Collectors.toCollection(ArrayList::new));
        List<RouteEdge> mergedEdges = new ArrayList<>();
        List<List<RoutePath>> approachChoices = new ArrayList<>();
        double orientation = corridorEdgeOrientation(outerEdges);
        CorridorTerminalRouter terminalApproaches = orthogonalApproaches
                ? new CorridorTerminalRouter(obstacleRouter, routingEnvironment,
                        (id, target, diameter, avoidance) -> routeDemandTowards(demandsByNode.get(id), target,
                                diameter, routingEnvironment, avoidance, RoutePreference.ENGINEERING), orientation)
                : null;
        for (RouteEdge edge : outerEdges) {
            String outerNodeId = replacedNodeIds.contains(edge.getUpstreamNodeId())
                    ? edge.getDownstreamNodeId()
                    : edge.getUpstreamNodeId();
            RouteNode outer = result.nodes.get(outerNodeId);
            if (outer == null) {
                return null;
            }
            RoutePath outerToMerged;
            Demand demand = demandsByNode.get(outerNodeId);
            if (orthogonalApproaches) {
                List<RoutePath> alternatives = demand == null
                        ? CorridorLinkApproaches.build(edge, outer, coordinate, orientation, obstacleRouter, routingEnvironment)
                        : CorridorRetainedTerminalApproaches.build(edge, outer, coordinate, orientation,
                                obstacleRouter, routingEnvironment,
                                localOnly
                                        ? terminalApproaches.localAlternatives(outerNodeId, demand.coordinate, coordinate, edge.getDiameter())
                                        : terminalApproaches.alternatives(outerNodeId, demand.coordinate, coordinate, edge.getDiameter()),
                                result.edges);
                alternatives = alternatives.stream()
                        .filter(path -> CorridorJunctionAssignment.clearsRetained(path, outerNodeId, result.edges))
                        .collect(Collectors.toList());
                if (alternatives.isEmpty()) {
                    LOGGER.debug("Corridor merge no approach outer={} demand={} orientation={} x={} y={}",
                            outerNodeId, demand != null, orientation, coordinate.x, coordinate.y);
                    return null;
                }
                approachChoices.add(alternatives);
                continue;
            }
            if (demand != null) {
                outerToMerged = routeDemandTowards(
                        demand, coordinate, edge.getDiameter(), routingEnvironment, acceptedRoutes);
            } else {
                List<LineString> routeAvoidance = result.edges.stream()
                        .filter(existing -> !existing.getUpstreamNodeId().equals(outerNodeId)
                                && !existing.getDownstreamNodeId().equals(outerNodeId))
                        .filter(existing -> existing.getCoordinates().size() >= 2)
                        .map(this::routeLine)
                        .collect(Collectors.toCollection(ArrayList::new));
                outerToMerged = obstacleRouter.find(
                        outer.getCoordinate().toCoordinate(),
                        coordinate,
                        edge.getDiameter(),
                        routingEnvironment,
                        Collections.emptySet(),
                        RoutePreference.SHORTEST,
                        routeAvoidance);
            }
            if (outerToMerged == null) {
                return null;
            }
            boolean mergedIsUpstream = replacedNodeIds.contains(edge.getUpstreamNodeId());
            RoutePath oriented = mergedIsUpstream ? outerToMerged.reversed() : outerToMerged;
            RouteEdge mergedEdge = routeEdge(
                    edgePrefix + edge.getId(),
                    mergedIsUpstream ? mergedId : outerNodeId,
                    mergedIsUpstream ? outerNodeId : mergedId,
                    oriented,
                    edge.getFlowTph(),
                    edge.getDiameter());
            mergedEdges.add(mergedEdge);
        }
        if (orthogonalApproaches) {
            List<RoutePath> chosen = CorridorJunctionAssignment.choose(coordinate, approachChoices);
            if (chosen == null) {
                LOGGER.debug("Corridor merge incompatible approaches counts={} x={} y={}",
                        approachChoices.stream().map(List::size).collect(Collectors.toList()), coordinate.x, coordinate.y);
                return null;
            }
            for (int i = 0; i < outerEdges.size(); i++) {
                RouteEdge edge = outerEdges.get(i);
                boolean mergedIsUpstream = replacedNodeIds.contains(edge.getUpstreamNodeId());
                String outerId = mergedIsUpstream ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId();
                mergedEdges.add(routeEdge(edgePrefix + edge.getId(),
                        mergedIsUpstream ? mergedId : outerId, mergedIsUpstream ? outerId : mergedId,
                        mergedIsUpstream ? chosen.get(i).reversed() : chosen.get(i), edge.getFlowTph(), edge.getDiameter()));
            }
        }
        result.edges.addAll(mergedEdges);
        if (!isStructurallyValid(result)) {
            return null;
        }
        return result;
    }

    /** Длинный существующий прямой участок задаёт локальную ось, без координат эталона. */
    private double corridorEdgeOrientation(List<RouteEdge> edges) {
        double longest = 0.0;
        double angle = 0.0;
        for (RouteEdge edge : edges) {
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            for (int i = 1; i < coordinates.size(); i++) {
                Coordinate before = coordinates.get(i - 1).toCoordinate(), after = coordinates.get(i).toCoordinate();
                double length = before.distance(after);
                if (length > longest) {
                    longest = length; angle = Math.atan2(after.y - before.y, after.x - before.x);
                }
            }
        }
        return angle;
    }

    /**
     * Re-routes only edges that violate the expert bend rules. This bounded repair reuses the
     * already-built tree and therefore avoids a second global search for the engineering variants.
     */
    private VariantDraft regularizeEngineeringDraft(
            VariantDraft source,
            List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment,
            boolean rebuildZones) {
        VariantDraft current = source;
        Map<String, Demand> demandsByNode = demands.stream().collect(Collectors.toMap(
                demand -> "demand:" + demand.id,
                demand -> demand,
                (left, right) -> left,
                LinkedHashMap::new));
        EngineeringRouteEvaluator.Evaluation currentEvaluation =
                engineeringEvaluator.evaluate(current.edges);
        VariantDraft rebuiltZone = rebuildZones
                ? rebuildEngineeringZones(
                        current, demands, routingEnvironment, currentEvaluation)
                : null;
        if (rebuiltZone != null && isStructurallyValid(rebuiltZone)
                && isFinalGeometryValid(rebuiltZone, routingEnvironment)
                && withinEngineeringRepairCorridor(current, rebuiltZone)) {
            EngineeringRouteEvaluator.Evaluation rebuiltEvaluation =
                    engineeringEvaluator.evaluate(rebuiltZone.edges);
            if (engineeringPenalty(rebuiltEvaluation) + LENGTH_EPSILON_M
                    < engineeringPenalty(currentEvaluation)) {
                current = rebuiltZone;
                currentEvaluation = rebuiltEvaluation;
            }
        }
        List<String> edgeIds = new ArrayList<>(currentEvaluation.nonCompliantEdgeIds());
        Collections.sort(edgeIds);
        for (String edgeId : edgeIds) {
            RouteEdge edge = current.edges.stream()
                    .filter(candidate -> candidate.getId().equals(edgeId))
                    .findFirst()
                    .orElse(null);
            if (edge == null) {
                continue;
            }
            VariantDraft candidate = regularizeEngineeringEdgeLocally(
                    current, edge, routingEnvironment);
            if (candidate == null || !isStructurallyValid(candidate)
                    || !isFinalGeometryValid(candidate, routingEnvironment)) {
                continue;
            }
            EngineeringRouteEvaluator.Evaluation candidateEvaluation =
                    engineeringEvaluator.evaluate(candidate.edges);
            if (candidateEvaluation.invalidAngleCount() < currentEvaluation.invalidAngleCount()
                    && engineeringPenalty(candidateEvaluation) + LENGTH_EPSILON_M
                            < engineeringPenalty(currentEvaluation)) {
                current = candidate;
                currentEvaluation = candidateEvaluation;
            }
        }
        edgeIds = new ArrayList<>(currentEvaluation.nonCompliantEdgeIds());
        edgeIds.sort(Comparator
                .comparingDouble((String edgeId) -> source.edges.stream()
                        .filter(edge -> edge.getId().equals(edgeId))
                        .findFirst()
                        .map(edge -> engineeringPenalty(engineeringEvaluator.evaluate(List.of(edge))))
                        .orElse(0.0))
                .reversed()
                .thenComparing(edgeId -> edgeId));
        int globalRepairs = 0;
        for (String edgeId : edgeIds) {
            if (globalRepairs >= tuning.getMaximumGlobalEngineeringRepairs()) {
                break;
            }
            RouteEdge edge = current.edges.stream()
                    .filter(candidate -> candidate.getId().equals(edgeId))
                    .findFirst()
                    .orElse(null);
            if (edge == null) {
                continue;
            }
            VariantDraft relocated = relocateEngineeringTerminalJunction(
                    current, edge, demandsByNode, routingEnvironment);
            if (relocated != null && isStructurallyValid(relocated)
                    && isFinalGeometryValid(relocated, routingEnvironment)
                    && withinEngineeringRepairCorridor(current, relocated)) {
                EngineeringRouteEvaluator.Evaluation relocatedEvaluation =
                        engineeringEvaluator.evaluate(relocated.edges);
                if (relocatedEvaluation.invalidAngleCount() < currentEvaluation.invalidAngleCount()) {
                    current = relocated;
                    currentEvaluation = relocatedEvaluation;
                    edge = current.edges.stream()
                            .filter(candidate -> candidate.getId().equals(edgeId))
                            .findFirst()
                            .orElse(null);
                    if (edge == null || !currentEvaluation.nonCompliantEdgeIds().contains(edgeId)) {
                        continue;
                    }
                }
            }
            VariantDraft candidate = rerouteEngineeringEdge(
                    current, edge, demandsByNode, routingEnvironment);
            globalRepairs++;
            if (candidate == null || !isStructurallyValid(candidate)
                    || !isFinalGeometryValid(candidate, routingEnvironment)) {
                continue;
            }
            EngineeringRouteEvaluator.Evaluation candidateEvaluation =
                    engineeringEvaluator.evaluate(candidate.edges);
            if (engineeringPenalty(candidateEvaluation) + LENGTH_EPSILON_M
                    < engineeringPenalty(currentEvaluation)) {
                current = candidate;
                currentEvaluation = candidateEvaluation;
            }
        }
        return current;
    }

    /**
     * Rebuilds a bounded neighbourhood as one decision instead of freezing each terminal branch
     * independently. Up to four nearby demands are detached together and reattached in several
     * deterministic orders; the caller admits only a whole-zone geometry improvement.
     */
    private VariantDraft rebuildEngineeringZones(
            VariantDraft source,
            List<Demand> demands,
            OfficialRoutingEnvironment routingEnvironment,
            EngineeringRouteEvaluator.Evaluation sourceEvaluation) {
        VariantDraft current = source;
        EngineeringRouteEvaluator.Evaluation currentEvaluation = sourceEvaluation;
        boolean changed = false;
        Set<String> triedZones = new HashSet<>();
        int rebuiltZones = 0;
        List<RouteEdge> seeds = source.edges.stream()
                .filter(edge -> sourceEvaluation.nonCompliantEdgeIds().contains(edge.getId()))
                .sorted(Comparator.comparing(RouteEdge::getId))
                .collect(Collectors.toList());
        for (RouteEdge seed : seeds) {
            if (rebuiltZones >= tuning.getMaximumEngineeringZoneRebuilds()) {
                break;
            }
            Coordinate seedCoordinate = midpoint(
                    seed.getCoordinates().get(0).toCoordinate(),
                    seed.getCoordinates().get(seed.getCoordinates().size() - 1).toCoordinate());
            List<Demand> zone = demands.stream()
                    .filter(demand -> demand.coordinate.distance(seedCoordinate)
                            <= tuning.getEngineeringZoneRadiusM())
                    .sorted(Comparator
                            .comparingDouble((Demand demand) -> demand.coordinate.distance(seedCoordinate))
                            .thenComparing(demand -> demand.id))
                    .limit(tuning.getMaximumEngineeringZoneDemands())
                    .collect(Collectors.toList());
            if (zone.size() < 2) {
                continue;
            }
            String zoneSignature = zone.stream()
                    .map(demand -> demand.id)
                    .sorted()
                    .collect(Collectors.joining(","));
            if (!triedZones.add(zoneSignature)) {
                continue;
            }
            VariantDraft base = current;
            boolean detachable = true;
            for (Demand demand : zone) {
                base = detachDemandAndNormalize(base, demand);
                if (base == null || base.edges.isEmpty()) {
                    detachable = false;
                    break;
                }
            }
            if (!detachable) {
                continue;
            }
            List<List<Demand>> orders = new ArrayList<>();
            List<Demand> farthestFirst = new ArrayList<>(zone);
            Collections.reverse(farthestFirst);
            orders.add(farthestFirst);
            orders.add(new ArrayList<>(zone));
            List<Demand> flowFirst = new ArrayList<>(zone);
            flowFirst.sort(Comparator
                    .comparingDouble((Demand demand) -> demand.flowTph.doubleValue())
                    .reversed()
                    .thenComparing(demand -> demand.id));
            orders.add(flowFirst);
            VariantDraft bestForZone = null;
            for (List<Demand> order : orders) {
                VariantDraft candidate = base.copy();
                boolean complete = true;
                Set<String> groupSpineEdgeIds = new LinkedHashSet<>();
                for (Demand demand : order) {
                    TreeAttachment attachment = chooseTreeAttachment(
                            demand,
                            candidate,
                            routingEnvironment,
                            true,
                            true,
                            RoutePreference.ENGINEERING,
                            groupSpineEdgeIds);
                    if (attachment == null) {
                        complete = false;
                        break;
                    }
                    Set<String> previousSpineEdgeIds = new LinkedHashSet<>(groupSpineEdgeIds);
                    addTreeAttachment(candidate, demand, attachment);
                    String newBranchId = "shared:graft:branch:" + demand.id;
                    groupSpineEdgeIds = candidate.edges.stream()
                            .map(RouteEdge::getId)
                            .filter(edgeId -> edgeId.equals(newBranchId)
                                    || previousSpineEdgeIds.stream()
                                            .anyMatch(edgeId::startsWith))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                }
                if (!complete || !isStructurallyValid(candidate)
                        || !isFinalGeometryValid(candidate, routingEnvironment)
                        || !withinEngineeringRepairCorridor(current, candidate)) {
                    continue;
                }
                EngineeringRouteEvaluator.Evaluation candidateEvaluation =
                        engineeringEvaluator.evaluate(candidate.edges);
                LOGGER.info(
                        "Engineering zone candidate demands={} invalid_before={} invalid_after={} bends_before={} bends_after={}",
                        zoneSignature,
                        currentEvaluation.invalidAngleCount(),
                        candidateEvaluation.invalidAngleCount(),
                        currentEvaluation.bendCount(),
                        candidateEvaluation.bendCount());
                if (engineeringPenalty(candidateEvaluation) + LENGTH_EPSILON_M
                        >= engineeringPenalty(currentEvaluation)) {
                    continue;
                }
                bestForZone = bestForZone == null
                                || engineeringRepairComparator().compare(candidate, bestForZone) < 0
                        ? candidate
                        : bestForZone;
            }
            if (bestForZone != null) {
                current = bestForZone;
                currentEvaluation = engineeringEvaluator.evaluate(current.edges);
                changed = true;
                rebuiltZones++;
            }
        }
        return changed ? current : null;
    }

    /**
     * Moves a degree-three terminal branch chamber onto the projection of the OKS exit over the
     * neighbouring trunk axis, then rebuilds all three incident rays. This produces the practical
     * pattern "straight trunk - one branch - short normal building entry" instead of preserving an
     * unfortunate graft point selected by the initial global tree search.
     */
    private VariantDraft relocateEngineeringTerminalJunction(
            VariantDraft source,
            RouteEdge terminalEdge,
            Map<String, Demand> demandsByNode,
            OfficialRoutingEnvironment routingEnvironment) {
        String demandNodeId = demandsByNode.containsKey(terminalEdge.getDownstreamNodeId())
                ? terminalEdge.getDownstreamNodeId()
                : demandsByNode.containsKey(terminalEdge.getUpstreamNodeId())
                        ? terminalEdge.getUpstreamNodeId()
                        : null;
        if (demandNodeId == null) {
            return null;
        }
        String junctionNodeId = terminalEdge.getUpstreamNodeId().equals(demandNodeId)
                ? terminalEdge.getDownstreamNodeId()
                : terminalEdge.getUpstreamNodeId();
        RouteNode junction = source.nodes.get(junctionNodeId);
        if (junction == null || !junction.isChamber() || junction.isRoot()) {
            return null;
        }
        List<RouteEdge> incident = incidentEdges(source, junctionNodeId);
        if (incident.size() != 3) {
            return null;
        }
        Map<RouteEdge, Demand> terminalDemands = new LinkedHashMap<>();
        for (RouteEdge edge : incident) {
            String outerNodeId = edge.getUpstreamNodeId().equals(junctionNodeId)
                    ? edge.getDownstreamNodeId()
                    : edge.getUpstreamNodeId();
            Demand outerDemand = demandsByNode.get(outerNodeId);
            if (outerDemand != null) {
                terminalDemands.put(edge, outerDemand);
            }
        }
        List<RouteEdge> trunkEdges = incident.stream()
                .filter(edge -> !terminalDemands.containsKey(edge))
                .collect(Collectors.toList());
        // The current repair targets the common real-world pattern seen in the dataset: one
        // incoming trunk reaches a chamber and is split into exactly two terminal OKS branches.
        if (terminalDemands.size() != 2 || trunkEdges.size() != 1) {
            return null;
        }
        RouteNode trunkOuter = outerNode(source, trunkEdges.get(0), junctionNodeId, junctionNodeId);
        if (trunkOuter == null) {
            return null;
        }
        Coordinate axisStart = trunkOuter.getCoordinate().toCoordinate();
        Coordinate axisEnd = junction.getCoordinate().toCoordinate();
        double axisLengthSquared = Math.pow(axisEnd.x - axisStart.x, 2)
                + Math.pow(axisEnd.y - axisStart.y, 2);
        if (axisLengthSquared <= LENGTH_EPSILON_M) {
            return null;
        }
        int maximumDiameter = incident.stream()
                .map(RouteEdge::getDiameter)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(50);
        VariantDraft best = null;
        Set<String> signatures = new HashSet<>();
        EngineeringRouteEvaluator.Evaluation sourceEvaluation =
                engineeringEvaluator.evaluate(source.edges);
        for (Map.Entry<RouteEdge, Demand> entry : terminalDemands.entrySet()) {
            int branchDiameter = entry.getKey().getDiameter() == null
                    ? 50
                    : entry.getKey().getDiameter();
            List<OfficialRouteGeometryRules.NormalEgress> exits = routingEnvironment
                    .normalEgressCandidates(
                            branchDiameter,
                            entry.getValue().coordinate,
                            axisEnd,
                            tuning.getEngineeringEgressExtraM()).stream()
                    .limit(3)
                    .collect(Collectors.toList());
            for (OfficialRouteGeometryRules.NormalEgress exit : exits) {
                Coordinate projected = closestPointOnLine(axisStart, axisEnd, exit.exit());
                if (projected.distance(axisStart) < EngineeringRouteEvaluator.MIN_BEND_SPACING_M
                        || projected.distance(axisEnd) < 0.10
                        || projected.distance(axisEnd) > MAX_CHAMBER_MERGE_RELOCATION_M) {
                    continue;
                }
                for (Coordinate relocation : legalRelocationCandidates(
                        projected,
                        axisStart,
                        axisEnd,
                        maximumDiameter,
                        routingEnvironment)) {
                    String signature = Math.round(relocation.x * 1000.0) + ":"
                            + Math.round(relocation.y * 1000.0);
                    if (!signatures.add(signature)) {
                        continue;
                    }
                    VariantDraft candidate = rebuildTerminalJunction(
                            source,
                            junction,
                            incident,
                            demandsByNode,
                            relocation,
                            routingEnvironment);
                    if (candidate == null || !withinEngineeringRepairCorridor(source, candidate)) {
                        LOGGER.info(
                                "Engineering junction relocation rejected during routing junction={} x={} y={}",
                                junctionNodeId, relocation.x, relocation.y);
                        continue;
                    }
                    EngineeringRouteEvaluator.Evaluation candidateEvaluation =
                            engineeringEvaluator.evaluate(candidate.edges);
                    LOGGER.info(
                            "Engineering junction relocation evaluated junction={} x={} y={} invalid_before={} invalid_after={}",
                            junctionNodeId,
                            relocation.x,
                            relocation.y,
                            sourceEvaluation.invalidAngleCount(),
                            candidateEvaluation.invalidAngleCount());
                    if (candidateEvaluation.invalidAngleCount() >= sourceEvaluation.invalidAngleCount()) {
                        continue;
                    }
                    best = best == null || engineeringRepairComparator().compare(candidate, best) < 0
                            ? candidate
                            : best;
                }
            }
        }
        return best;
    }

    private List<Coordinate> legalRelocationCandidates(
            Coordinate projected,
            Coordinate axisStart,
            Coordinate axisEnd,
            int diameter,
            OfficialRoutingEnvironment routingEnvironment) {
        if (!routingEnvironment.pointInsideForbiddenClearance(diameter, projected)) {
            return List.of(projected);
        }
        double axisLength = axisStart.distance(axisEnd);
        if (axisLength <= LENGTH_EPSILON_M) {
            return List.of();
        }
        double unitX = (axisEnd.x - axisStart.x) / axisLength;
        double unitY = (axisEnd.y - axisStart.y) / axisLength;
        List<Coordinate> result = new ArrayList<>();
        for (double offset : new double[] {2.5, -2.5, 5.0, -5.0, 10.0, -10.0}) {
            Coordinate candidate = new Coordinate(
                    projected.x + unitX * offset,
                    projected.y + unitY * offset);
            if (candidate.distance(axisStart) < EngineeringRouteEvaluator.MIN_BEND_SPACING_M
                    || candidate.distance(axisEnd) > MAX_CHAMBER_MERGE_RELOCATION_M
                    || routingEnvironment.pointInsideForbiddenClearance(diameter, candidate)) {
                continue;
            }
            result.add(candidate);
            if (result.size() >= 2) {
                break;
            }
        }
        return result;
    }

    private Coordinate closestPointOnLine(
            Coordinate start,
            Coordinate end,
            Coordinate point) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double lengthSquared = dx * dx + dy * dy;
        double fraction = ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared;
        return new Coordinate(start.x + fraction * dx, start.y + fraction * dy);
    }

    private VariantDraft rebuildTerminalJunction(
            VariantDraft source,
            RouteNode junction,
            List<RouteEdge> incident,
            Map<String, Demand> demandsByNode,
            Coordinate coordinate,
            OfficialRoutingEnvironment routingEnvironment) {
        VariantDraft result = source.copy();
        Set<String> incidentIds = incident.stream()
                .map(RouteEdge::getId)
                .collect(Collectors.toSet());
        result.edges.removeIf(edge -> incidentIds.contains(edge.getId()));
        result.nodes.put(junction.getId(), new RouteNode(
                junction.getId(),
                junction.getNodeType(),
                new RouteCoordinate(coordinate.x, coordinate.y),
                junction.isChamber(),
                junction.isRoot(),
                junction.getBaseIncidentSections(),
                junction.getTargetId(), junction.getExistingIncidentDiameter()));
        List<LineString> fixedRoutes = result.edges.stream()
                .filter(edge -> edge.getCoordinates().size() >= 2)
                .map(this::routeLine)
                .collect(Collectors.toCollection(ArrayList::new));
        for (RouteEdge edge : incident) {
            String outerNodeId = edge.getUpstreamNodeId().equals(junction.getId())
                    ? edge.getDownstreamNodeId()
                    : edge.getUpstreamNodeId();
            RouteNode outer = result.nodes.get(outerNodeId);
            if (outer == null) {
                return null;
            }
            RoutePath outerToJunction;
            Demand outerDemand = demandsByNode.get(outerNodeId);
            if (outerDemand != null) {
                outerToJunction = routeDemandTowards(
                        outerDemand,
                        coordinate,
                        edge.getDiameter() == null ? 50 : edge.getDiameter(),
                        routingEnvironment,
                        fixedRoutes,
                        RoutePreference.ENGINEERING);
            } else {
                List<LineString> routeAvoidance = result.edges.stream()
                        .filter(existing -> !existing.getUpstreamNodeId().equals(outerNodeId)
                                && !existing.getDownstreamNodeId().equals(outerNodeId))
                        .filter(existing -> existing.getCoordinates().size() >= 2)
                        .map(this::routeLine)
                        .collect(Collectors.toCollection(ArrayList::new));
                outerToJunction = obstacleRouter.find(
                        outer.getCoordinate().toCoordinate(),
                        coordinate,
                        edge.getDiameter() == null ? 50 : edge.getDiameter(),
                        routingEnvironment,
                        endpointFeatureIds(edge, source.nodes),
                        RoutePreference.ENGINEERING,
                        routeAvoidance);
            }
            if (outerToJunction == null) {
                return null;
            }
            boolean junctionIsUpstream = edge.getUpstreamNodeId().equals(junction.getId());
            RoutePath oriented = junctionIsUpstream ? outerToJunction.reversed() : outerToJunction;
            result.edges.add(routeEdge(
                    edge.getId(),
                    edge.getUpstreamNodeId(),
                    edge.getDownstreamNodeId(),
                    oriented,
                    edge.getFlowTph(),
                    edge.getDiameter() == null ? 50 : edge.getDiameter()));
        }
        return isStructurallyValid(result) ? result : null;
    }

    private VariantDraft regularizeEngineeringEdgeLocally(
            VariantDraft source,
            RouteEdge edge,
            OfficialRoutingEnvironment routingEnvironment) {
        RoutePath replacement = obstacleRouter.regularize(
                edge.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate)
                        .collect(Collectors.toList()),
                edge.getDiameter() == null ? 50 : edge.getDiameter(),
                routingEnvironment,
                endpointFeatureIds(edge, source.nodes),
                Collections.emptyList());
        if (replacement == null) {
            return null;
        }
        VariantDraft result = source.copy();
        for (int index = 0; index < result.edges.size(); index++) {
            if (result.edges.get(index).getId().equals(edge.getId())) {
                result.edges.set(index, routeEdge(
                        edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                        replacement, edge.getFlowTph(), edge.getDiameter() == null ? 50 : edge.getDiameter()));
                return result;
            }
        }
        return null;
    }

    private VariantDraft rerouteEngineeringEdge(
            VariantDraft source,
            RouteEdge edge,
            Map<String, Demand> demandsByNode,
            OfficialRoutingEnvironment routingEnvironment) {
        RouteNode upstream = source.nodes.get(edge.getUpstreamNodeId());
        RouteNode downstream = source.nodes.get(edge.getDownstreamNodeId());
        if (upstream == null || downstream == null) {
            return null;
        }
        List<LineString> acceptedRoutes = source.edges.stream()
                .filter(existing -> !existing.getId().equals(edge.getId()))
                .filter(existing -> !existing.getUpstreamNodeId().equals(upstream.getId())
                        && !existing.getDownstreamNodeId().equals(upstream.getId()))
                .filter(existing -> !existing.getUpstreamNodeId().equals(downstream.getId())
                        && !existing.getDownstreamNodeId().equals(downstream.getId()))
                .filter(existing -> existing.getCoordinates().size() >= 2)
                .map(this::routeLine)
                .collect(Collectors.toCollection(ArrayList::new));
        int diameter = edge.getDiameter() == null ? 50 : edge.getDiameter();
        List<RoutePath> replacements = new ArrayList<>();
        Demand terminal = demandsByNode.get(downstream.getId());
        if (terminal != null) {
            replacements.addAll(routeDemandEngineeringAlternatives(
                    terminal,
                    upstream.getCoordinate().toCoordinate(),
                    diameter,
                    routingEnvironment,
                    acceptedRoutes).stream()
                    .map(RoutePath::reversed)
                    .collect(Collectors.toList()));
        } else {
            RoutePath replacement = obstacleRouter.find(
                    upstream.getCoordinate().toCoordinate(),
                    downstream.getCoordinate().toCoordinate(),
                    diameter,
                    routingEnvironment,
                    endpointFeatureIds(edge, source.nodes),
                    RoutePreference.ENGINEERING,
                    acceptedRoutes);
            if (replacement != null) {
                replacements.add(replacement);
            }
        }
        if (replacements.isEmpty()) {
            return null;
        }
        VariantDraft best = null;
        EngineeringRouteEvaluator.Evaluation controlEvaluation =
                engineeringEvaluator.evaluate(source.edges);
        for (RoutePath replacement : replacements) {
            VariantDraft candidate = source.copy();
            for (int index = 0; index < candidate.edges.size(); index++) {
                if (candidate.edges.get(index).getId().equals(edge.getId())) {
                    candidate.edges.set(index, routeEdge(
                            edge.getId(),
                            edge.getUpstreamNodeId(),
                            edge.getDownstreamNodeId(),
                            replacement,
                            edge.getFlowTph(),
                            diameter));
                    break;
                }
            }
            if (!withinEngineeringRepairCorridor(source, candidate)) {
                continue;
            }
            EngineeringRouteEvaluator.Evaluation candidateEvaluation =
                    engineeringEvaluator.evaluate(candidate.edges);
            if (candidateEvaluation.invalidAngleCount() >= controlEvaluation.invalidAngleCount()) {
                continue;
            }
            best = best == null
                    ? candidate
                    : engineeringRepairComparator().compare(candidate, best) < 0
                            ? candidate
                            : best;
        }
        return best;
    }

    /**
     * Once two branch alternatives remove the same hard expert violations, prefer the simpler and
     * cheaper one. This prevents a visually smooth but unnecessarily long obstacle detour from
     * winning merely because its already-valid angles are closer to the centre of the range.
     */
    private Comparator<VariantDraft> engineeringRepairComparator() {
        return Comparator
                .comparingInt((VariantDraft draft) ->
                        engineeringEvaluator.evaluate(draft.edges).invalidAngleCount())
                .thenComparingInt(draft ->
                        engineeringEvaluator.evaluate(draft.edges).insufficientSpacingCount())
                .thenComparingInt(draft ->
                        engineeringEvaluator.evaluate(draft.edges).irregularJunctionAngleCount())
                .thenComparingInt(draft -> engineeringEvaluator.evaluate(draft.edges).bendCount())
                .thenComparingInt(this::newBranchChamberCount)
                .thenComparing(this::totalNetworkConstructionCost)
                .thenComparingDouble(this::totalRouteLength)
                .thenComparingDouble(draft ->
                        engineeringEvaluator.evaluate(draft.edges).totalAngleDeviation())
                .thenComparing(this::draftGeometrySignature);
    }

    private List<RoutePath> routeDemandEngineeringAlternatives(
            Demand demand,
            Coordinate target,
            int diameter,
            OfficialRoutingEnvironment routingEnvironment,
            List<LineString> acceptedRoutes) {
        List<OfficialRouteGeometryRules.NormalEgress> egressCandidates =
                routingEnvironment.normalEgressCandidates(
                        diameter,
                        demand.coordinate,
                        target,
                        tuning.getEngineeringEgressExtraM()).stream()
                        .limit(tuning.getMaximumEngineeringEgressCandidates())
                        .collect(Collectors.toList());
        if (egressCandidates.isEmpty()) {
            RoutePath fallback = routeDemandTowards(
                    demand,
                    target,
                    diameter,
                    routingEnvironment,
                    acceptedRoutes,
                    RoutePreference.ENGINEERING);
            return fallback == null ? List.of() : List.of(fallback);
        }
        List<RoutePath> result = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        for (int egressIndex = 0; egressIndex < egressCandidates.size(); egressIndex++) {
            OfficialRouteGeometryRules.NormalEgress egress = egressCandidates.get(egressIndex);
            List<RoutePreference> preferences = egressIndex == 0
                    ? List.of(RoutePreference.ENGINEERING, RoutePreference.LEFT, RoutePreference.RIGHT)
                    : List.of(RoutePreference.ENGINEERING);
            for (RoutePreference preference : preferences) {
                RoutePath path = obstacleRouter.find(
                        egress.exit(),
                        target,
                        diameter,
                        routingEnvironment,
                        Collections.emptySet(),
                        preference,
                        acceptedRoutes);
                if (path == null) {
                    continue;
                }
                RoutePath regularized = obstacleRouter.regularize(
                        path.coordinates(),
                        diameter,
                        routingEnvironment,
                        Collections.emptySet(),
                        acceptedRoutes);
                RoutePath withEgress = (regularized == null ? path : regularized)
                        .withMandatoryPrefix(egress.start());
                String signature = withEgress.coordinates().stream()
                        .map(coordinate -> Math.round(coordinate.x * 1000.0)
                                + ":" + Math.round(coordinate.y * 1000.0))
                        .collect(Collectors.joining(";"));
                if (signatures.add(signature)) {
                    result.add(withEgress);
                }
            }
        }
        LOGGER.info(
                "Engineering terminal alternatives demand={} candidates={} routed={}",
                demand.id, egressCandidates.size(), result.size());
        return result;
    }

    private boolean withinEngineeringRepairCorridor(VariantDraft control, VariantDraft candidate) {
        if (totalRouteLength(candidate)
                > totalRouteLength(control) * (1.0 + ENGINEERING_RELAXED_DEVIATION_RATIO)
                        + LENGTH_EPSILON_M) {
            return false;
        }
        BigDecimal controlCost = totalNetworkConstructionCost(control);
        BigDecimal candidateCost = totalNetworkConstructionCost(candidate);
        return controlCost != null
                && candidateCost != null
                && candidateCost.compareTo(controlCost.multiply(BigDecimal.valueOf(
                        1.0 + ENGINEERING_RELAXED_DEVIATION_RATIO))) <= 0;
    }

    private double engineeringPenalty(EngineeringRouteEvaluator.Evaluation evaluation) {
        return evaluation.invalidAngleCount() * 1_000_000.0
                + evaluation.insufficientSpacingCount() * 100_000.0
                + evaluation.totalAngleDeviation() * 100.0
                + evaluation.irregularJunctionAngleCount() * 10.0
                + evaluation.totalJunctionAngleDeviation()
                + evaluation.preferredAngleDeviation()
                + evaluation.bendCount();
    }

    /** Forces the terminal to leave the side of its OKS facing the merged street junction. */
    private RoutePath routeDemandTowards(
            Demand demand,
            Coordinate target,
            int diameter,
            OfficialRoutingEnvironment routingEnvironment,
            List<LineString> acceptedRoutes) {
        return routeDemandTowards(
                demand, target, diameter, routingEnvironment, acceptedRoutes, RoutePreference.SHORTEST);
    }

    private RoutePath routeDemandTowards(
            Demand demand,
            Coordinate target,
            int diameter,
            OfficialRoutingEnvironment routingEnvironment,
            List<LineString> acceptedRoutes,
            RoutePreference preference) {
        double maximumEgressExtraM = preference == RoutePreference.ENGINEERING
                ? tuning.getEngineeringEgressExtraM()
                : DEFAULT_EGRESS_EXTRA_M;
        OfficialRouteGeometryRules.NormalEgress selected = demand.egressTowards(
                routingEnvironment, diameter, target, maximumEgressExtraM);
        RoutePath path = obstacleRouter.find(
                demand.routingStart(selected),
                target,
                diameter,
                routingEnvironment,
                Collections.emptySet(),
                preference,
                acceptedRoutes);
        return path == null ? null : demand.withMandatoryEgress(path, selected);
    }

    private double edgeDetourRatio(RouteEdge edge) {
        List<RouteCoordinate> coordinates = edge.getCoordinates();
        if (coordinates.size() < 2) {
            return 1.0;
        }
        double direct = coordinates.get(0).toCoordinate()
                .distance(coordinates.get(coordinates.size() - 1).toCoordinate());
        return direct <= MIN_EDGE_LENGTH_M ? 1.0 : edge.getLengthM().doubleValue() / direct;
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
            String suffix,
            OfficialRoutingEnvironment environment) {
        boolean existingChamber = "heat_chamber".equals(candidate.getTargetType());
        String id = existingChamber
                ? "tie:chamber:" + candidate.getTargetId()
                : "tie:segment:" + candidate.getTargetId() + ":"
                        + Double.toHexString(coordinate.x) + ":" + Double.toHexString(coordinate.y);
        return environment.verifiedRootSupport(new RouteNode(
                id,
                existingChamber ? "existing_chamber_tie_in" : "new_tie_in_chamber",
                new RouteCoordinate(coordinate.x, coordinate.y),
                true,
                true,
                existingChamber ? chamberIncidentCounts.getOrDefault(candidate.getTargetId(), 0) : 2,
                candidate.getTargetId()));
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

    RouteVariant finish(
            String id,
            String strategy,
            VariantDraft draft,
            List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters,
            boolean reconstructionRequired,
            OfficialRoutingEnvironment routingEnvironment) {
        return finish(id, strategy, draft, features, parameters, reconstructionRequired,
                routingEnvironment, TerminalApproachPolicy.TOWARD_UPSTREAM);
    }

    RouteVariant finish(
            String id, String strategy, VariantDraft draft, List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters, boolean reconstructionRequired,
            OfficialRoutingEnvironment routingEnvironment, TerminalApproachPolicy approachPolicy) {
        RouteVariant baseline = finishGeometry(id, strategy, draft, features, parameters,
                reconstructionRequired, routingEnvironment, approachPolicy);
        if (!baseline.isValid() || !baseline.getEconomics().isComplete()) return baseline;
        List<RouteEdge> simplified = simplifyCompliantEdges(baseline, features, routingEnvironment);
        if (simplified == baseline.getEdges()) return baseline;
        // Повторяем весь допуск на изменённой геометрии; старые ДУ, глубина и смета не переносятся.
        RouteVariant candidate = finishGeometry(id, strategy,
                new VariantDraft(baseline.getNodes(), simplified, baseline.getConnections()),
                features, parameters, reconstructionRequired, routingEnvironment, approachPolicy);
        if (!candidate.isValid() || !candidate.getEconomics().isComplete()
                || candidate.getTotalLengthM().compareTo(baseline.getTotalLengthM()) >= 0
                || candidate.getEconomics().getCalculatedCost().compareTo(baseline.getEconomics().getCalculatedCost()) > 0
                || !simplificationImprovesEngineering(baseline.getEdges(), candidate.getEdges())
                || (parameters.isDepthEnabled() && candidate.getEdges().stream().anyMatch(edge ->
                        edge.getDepthProfile() == null || !edge.getDepthProfile().isComplete()
                                || !edge.getDepthProfile().getIssues().isEmpty()))) return baseline;
        for (int index = 0; index < baseline.getEdges().size(); index++) {
            if (!RetainedEndpointSimplifier.sameTerminalSegments(
                    baseline.getEdges().get(index), candidate.getEdges().get(index))) return baseline;
        }
        return candidate;
    }

    /** Один ограниченный проход по допустимым рёбрам; камеры и топология не перемещаются. */
    private List<RouteEdge> simplifyCompliantEdges(RouteVariant baseline,
            List<ImportedOfficialFeature> features, OfficialRoutingEnvironment environment) {
        List<RouteEdge> current = new ArrayList<>(baseline.getEdges());
        Map<String, RouteNode> nodesById = baseline.getNodes().stream()
                .collect(Collectors.toMap(RouteNode::getId, node -> node));
        RetainedEndpointSimplifier simplifier = new RetainedEndpointSimplifier();
        boolean changed = false;
        for (int index = 0; index < current.size(); index++) {
            RouteEdge edge = current.get(index);
            EngineeringRouteEvaluator.Evaluation before = engineeringEvaluator.evaluate(List.of(edge));
            if (!before.isCompliant() || before.bendCount() < 2) continue;
            List<LineString> avoidance = current.stream()
                    .filter(other -> !other.getId().equals(edge.getId()) && !sharesEndpoint(other, edge))
                    .map(this::routeLine).collect(Collectors.toList());
            RoutePath path = simplifier.simplify(edge, obstacleRouter, environment,
                    endpointFeatureIds(edge, nodesById), avoidance);
            if (path == null) continue;
            RouteEdge replacement = routeEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                    path, edge.getFlowTph(), edge.getDiameter());
            if (!RetainedEndpointSimplifier.sameTerminalSegments(edge, replacement)
                    || !simplificationImprovesEngineering(List.of(edge), List.of(replacement))
                    || economicsCalculator.marginalConnectionCost(List.of(replacement), List.of()).compareTo(
                            economicsCalculator.marginalConnectionCost(List.of(edge), List.of())) > 0) continue;
            List<RouteEdge> candidate = new ArrayList<>(current);
            candidate.set(index, replacement);
            if (!validator.validate(baseline.getNodes(), candidate,
                    featuresForEdges(features, candidate, environment)).isEmpty()) continue;
            current = candidate;
            changed = true;
        }
        return changed ? current : baseline.getEdges();
    }

    private boolean simplificationImprovesEngineering(List<RouteEdge> baseline, List<RouteEdge> candidate) {
        EngineeringRouteEvaluator.Evaluation before = engineeringEvaluator.evaluate(baseline);
        EngineeringRouteEvaluator.Evaluation after = engineeringEvaluator.evaluate(candidate);
        return after.bendCount() < before.bendCount()
                && after.invalidAngleCount() <= before.invalidAngleCount()
                && after.insufficientSpacingCount() <= before.insufficientSpacingCount()
                && after.irregularJunctionAngleCount() <= before.irregularJunctionAngleCount()
                && after.totalAngleDeviation() <= before.totalAngleDeviation() + 1e-7
                && after.preferredAngleDeviation() <= before.preferredAngleDeviation() + 1e-7
                && after.totalJunctionAngleDeviation() <= before.totalJunctionAngleDeviation() + 1e-7;
    }

    private RouteVariant finishGeometry(
            String id, String strategy, VariantDraft draft, List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters, boolean reconstructionRequired,
            OfficialRoutingEnvironment routingEnvironment, TerminalApproachPolicy approachPolicy) {
        List<RouteNode> nodes = draft.nodes.values().stream()
                .map(routingEnvironment::verifiedRootSupport)
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
                nodes, applySizing(draft.edges, initialSizing), features, routingEnvironment, approachPolicy);
        List<ImportedOfficialFeature> routeFeatures = featuresForEdges(features, sizedEdges, routingEnvironment);
        List<RouteEdge> depthReroutedEdges = parameters.isDepthEnabled()
                ? rerouteDepthConflicts(nodes, sizedEdges, routeFeatures, parameters)
                : sizedEdges;
        NetworkSizingResult sizing = sizeRoutes(depthReroutedEdges, demandFlowByNode);
        List<RouteEdge> finalSizedEdges = ensureMandatoryEgress(
                nodes, applySizing(depthReroutedEdges, sizing), features, routingEnvironment, approachPolicy);
        if (!"cheapest".equals(strategy)) {
            finalSizedEdges = regularizeFinishedEngineeringEdges(
                    nodes, finalSizedEdges, features, routingEnvironment);
        }
        // Выход из ОКС и регуляризация меняют длину: окончательный ДУ определяем после них.
        // Далее геометрию не меняем; валидатор отклонит отступы, недостаточные для нового ДУ.
        NetworkSizingResult finalSizing = sizeRoutes(finalSizedEdges, demandFlowByNode);
        finalSizedEdges = applySizing(finalSizedEdges, finalSizing);
        routeFeatures = featuresForEdges(features, finalSizedEdges, routingEnvironment);
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
                finalSizing.getIssues(),
                reconstruction,
                economics,
                null);
    }

    /**
     * Applies the expert constructability rules to the actual exported geometry. Tree grafting,
     * chamber contraction and mandatory OKS egress all happen after the initial path search and
     * can introduce shallow technical kinks; checking only the search paths therefore misses the
     * geometry that the user sees. This bounded local pass removes a kink when the direct segment
     * is legal, otherwise it tries a 90/135-degree elbow. Every accepted replacement must improve
     * the expert score and keep the complete route valid against the official constraint catalogue.
     */
    private List<RouteEdge> regularizeFinishedEngineeringEdges(
            List<RouteNode> nodes,
            List<RouteEdge> source,
            List<ImportedOfficialFeature> features,
            OfficialRoutingEnvironment routingEnvironment) {
        List<RouteEdge> current = new ArrayList<>(source);
        Map<String, RouteNode> nodesById = nodes.stream()
                .collect(Collectors.toMap(RouteNode::getId, node -> node));
        for (int pass = 0; pass < 2; pass++) {
            boolean improved = false;
            List<String> nonCompliantIds = new ArrayList<>(
                    engineeringEvaluator.evaluate(current).nonCompliantEdgeIds());
            for (String edgeId : nonCompliantIds) {
                int edgeIndex = -1;
                for (int index = 0; index < current.size(); index++) {
                    if (current.get(index).getId().equals(edgeId)) {
                        edgeIndex = index;
                        break;
                    }
                }
                if (edgeIndex < 0) {
                    continue;
                }
                RouteEdge edge = current.get(edgeIndex);
                EngineeringRouteEvaluator.Evaluation before =
                        engineeringEvaluator.evaluate(List.of(edge));
                List<LineString> acceptedRoutes = current.stream()
                        .filter(existing -> !existing.getId().equals(edge.getId()))
                        .filter(existing -> !sharesEndpoint(existing, edge))
                        .map(this::routeLine)
                        .collect(Collectors.toList());
                RoutePath replacement = obstacleRouter.regularize(
                        edge.getCoordinates().stream()
                                .map(RouteCoordinate::toCoordinate)
                                .collect(Collectors.toList()),
                        edge.getDiameter() == null ? 50 : edge.getDiameter(),
                        routingEnvironment,
                        endpointFeatureIds(edge, nodesById),
                        acceptedRoutes);
                if (replacement == null) {
                    continue;
                }
                RouteEdge candidateEdge = routeEdge(
                        edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                        replacement, edge.getFlowTph(), edge.getDiameter() == null ? 50 : edge.getDiameter());
                EngineeringRouteEvaluator.Evaluation after =
                        engineeringEvaluator.evaluate(List.of(candidateEdge));
                if (engineeringPenalty(after) + LENGTH_EPSILON_M >= engineeringPenalty(before)) {
                    continue;
                }
                List<RouteEdge> candidate = new ArrayList<>(current);
                candidate.set(edgeIndex, candidateEdge);
                if (!validator.validate(
                                nodes,
                                candidate,
                                featuresForEdges(features, candidate, routingEnvironment))
                        .isEmpty()) {
                    continue;
                }
                current = candidate;
                improved = true;
            }
            if (!improved || engineeringEvaluator.evaluate(current).isCompliant()) {
                break;
            }
        }
        return current;
    }

    private boolean sharesEndpoint(RouteEdge left, RouteEdge right) {
        return left.getUpstreamNodeId().equals(right.getUpstreamNodeId())
                || left.getUpstreamNodeId().equals(right.getDownstreamNodeId())
                || left.getDownstreamNodeId().equals(right.getUpstreamNodeId())
                || left.getDownstreamNodeId().equals(right.getDownstreamNodeId());
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

    /** Перепроверяет ввод и отступы при окончательном ДУ в выбранной альтернативе геометрии. */
    private List<RouteEdge> ensureMandatoryEgress(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            OfficialRoutingEnvironment routingEnvironment, TerminalApproachPolicy approachPolicy) {
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
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            Coordinate approach = coordinates.size() >= 2
                    ? coordinates.get(coordinates.size() - 2).toCoordinate()
                    : upstream.getCoordinate().toCoordinate();
            // Сохранение совместно построенных подходов — отдельный кандидат, не замена
            // старого способа доводки всего portfolio: ранний выбор может потерять хорошую топологию.
            Coordinate target = approachPolicy == TerminalApproachPolicy.PRESERVE_VALID
                    ? approach : upstream.getCoordinate().toCoordinate();
            OfficialRouteGeometryRules.NormalEgress egress = demandEdge
                    ? routingEnvironment.normalEgressTowards(
                                    edge.getDiameter(),
                                    downstream.getCoordinate().toCoordinate(),
                                    target)
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
            if (validAtFinalDiameter && (egress == null || (hasMandatoryEgress(edge, egress)
                    && obstacleRouter.terminalApproachAllowed(approach,
                            downstream.getCoordinate().toCoordinate(), edge.getDiameter(),
                            routingEnvironment, exemptions, acceptedRoutes, egress)))) {
                result.add(edge);
                acceptedRoutes.add(routeLine(edge));
                continue;
            }
            // При настоящем нарушении сохраняем прежний выбор цели для восстановительного поиска.
            egress = demandEdge
                    ? routingEnvironment.normalEgressTowards(
                                    edge.getDiameter(),
                                    downstream.getCoordinate().toCoordinate(),
                                    upstream.getCoordinate().toCoordinate())
                            .orElse(null)
                    : null;
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

    List<ImportedOfficialFeature> featuresForEdges(
            List<ImportedOfficialFeature> core,
            List<RouteEdge> edges,
            OfficialRoutingEnvironment environment) {
        Map<String, ImportedOfficialFeature> result = new LinkedHashMap<>();
        core.forEach(feature -> result.put(feature.getFeatureId(), feature));
        for (RouteEdge edge : edges) {
            if (edge.getCoordinates().size() < 2) continue;
            org.locationtech.jts.geom.Envelope bounds = new org.locationtech.jts.geom.Envelope();
            edge.getCoordinates().forEach(coordinate -> bounds.expandToInclude(coordinate.toCoordinate()));
            // Обход может выйти за окно концов: независимая проверка видит всю фактическую полилинию.
            environment.featuresInWindow(new Coordinate(bounds.getMinX(), bounds.getMinY()),
                            new Coordinate(bounds.getMaxX(), bounds.getMaxY()))
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
            OfficialRouteGeometryRules.NormalEgress egress = environment.normalEgressTowards(
                            edge.getDiameter(),
                            downstream.getCoordinate().toCoordinate(),
                            upstream.getCoordinate().toCoordinate())
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
            Map<String, List<TieInCandidate>> candidatesByConnection,
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
                    Coordinate approachTarget = candidatesByConnection
                            .getOrDefault(connection.getFeatureId(), List.of()).stream()
                            .map(candidate -> {
                                ImportedOfficialFeature target = featuresById.get(candidate.getTargetId());
                                return target == null
                                        ? null
                                        : targetCoordinate(
                                                coordinate, candidate, target.getMetricGeometry());
                            })
                            .filter(java.util.Objects::nonNull)
                            .min(Comparator.comparingDouble(coordinate::distance))
                            .orElse(coordinate);
                    OfficialRouteGeometryRules.NormalEgress egress = routingEnvironment
                            .normalEgressTowards(
                                    diameterFor(resolvedFlow), coordinate, approachTarget,
                                    DEFAULT_EGRESS_EXTRA_M)
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

    private Coordinate targetCoordinate(
            Coordinate origin,
            TieInCandidate candidate,
            Geometry target) {
        if (candidate.hasFixedTieIn()) {
            return new Coordinate(candidate.getTieInXm(), candidate.getTieInYm());
        }
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
        String coordinate = candidate.hasFixedTieIn()
                ? ":" + Math.round(candidate.getTieInXm() * 1000.0)
                        + ":" + Math.round(candidate.getTieInYm() * 1000.0)
                : "";
        return candidate.getTargetType() + ":" + candidate.getTargetId() + coordinate;
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

    static class Demand {
        private final String id;
        private final String connectionPointId;
        private final Coordinate coordinate;
        private final BigDecimal flowTph;
        private final OfficialRouteGeometryRules.NormalEgress egress;

        Demand(
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

        private OfficialRouteGeometryRules.NormalEgress egressTowards(
                OfficialRoutingEnvironment environment,
                int diameter,
                Coordinate approachTarget) {
            return egressTowards(environment, diameter, approachTarget, DEFAULT_EGRESS_EXTRA_M);
        }

        private OfficialRouteGeometryRules.NormalEgress egressTowards(
                OfficialRoutingEnvironment environment,
                int diameter,
                Coordinate approachTarget,
                double maximumAlternativeEgressExtraM) {
            return environment.normalEgressTowards(
                            diameter, coordinate, approachTarget, maximumAlternativeEgressExtraM)
                    .orElse(egress);
        }

        private Coordinate routingStart(OfficialRouteGeometryRules.NormalEgress selectedEgress) {
            return selectedEgress == null ? coordinate : selectedEgress.exit();
        }

        private RoutePath withMandatoryEgress(
                RoutePath path,
                OfficialRouteGeometryRules.NormalEgress selectedEgress) {
            return selectedEgress == null
                    ? path
                    : path.withMandatoryPrefix(selectedEgress.start());
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

    static class TreeAttachment {
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

        RouteNode junction() { return junction; }
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

    private static class MergedBranchAlternatives {
        private final VariantDraft shortest;
        private final VariantDraft cheapest;
        private final List<VariantDraft> candidates;

        private MergedBranchAlternatives(
                VariantDraft shortest,
                VariantDraft cheapest,
                List<VariantDraft> candidates) {
            this.shortest = shortest;
            this.cheapest = cheapest;
            this.candidates = List.copyOf(candidates);
        }
    }

    static class VariantDraft {
        private final Map<String, RouteNode> nodes = new LinkedHashMap<>();
        private final List<RouteEdge> edges = new ArrayList<>();
        private final List<RouteConnection> connections = new ArrayList<>();
        private final Map<String, Double> lengthByDemand = new HashMap<>();
        private final Map<String, BigDecimal> connectionCostByDemand = new HashMap<>();
        private final Map<String, String> targetByDemand = new HashMap<>();
        private final Map<String, RouteFailureDiagnostics> failureDiagnostics = new HashMap<>();
        private int sharedPairCount;
        private boolean corridorCandidate;

        VariantDraft() { }

        VariantDraft(List<RouteNode> initialNodes, List<RouteEdge> initialEdges,
                List<RouteConnection> initialConnections) {
            initialNodes.forEach(this::addNode);
            edges.addAll(initialEdges);
            connections.addAll(initialConnections);
        }

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
            copy.corridorCandidate = corridorCandidate;
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
