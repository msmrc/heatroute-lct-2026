package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

@Component
public class OfficialRoutePlanner {
    public static final String ALGORITHM_VERSION = "r4-heuristic-1";
    private static final double MIN_EDGE_LENGTH_M = 0.01;
    private static final double MIN_SHARED_SAVING_M = 0.01;

    private final OfficialRouteValidator validator;

    public OfficialRoutePlanner(OfficialRouteValidator validator) {
        this.validator = validator;
    }

    public OfficialCalculationResult plan(
            List<ImportedOfficialFeature> features,
            TopologyAnalysis topology) {
        Map<String, ImportedOfficialFeature> featuresById = features.stream().collect(Collectors.toMap(
                ImportedOfficialFeature::getFeatureId,
                feature -> feature,
                (left, right) -> left,
                LinkedHashMap::new));
        Map<String, Integer> chamberIncidentCounts = chamberIncidentCounts(features);
        List<Demand> demands = demands(features, featuresById);
        Map<String, List<TieInCandidate>> candidatesByConnection = topology.getTieInCandidates().stream()
                .collect(Collectors.groupingBy(
                        TieInCandidate::getConnectionPointId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        VariantDraft independentDraft = independent(
                demands, candidatesByConnection, featuresById, chamberIncidentCounts);
        RouteVariant independent = finish("independent", "independent", independentDraft);
        List<RouteVariant> variants = new ArrayList<>();
        variants.add(independent);

        VariantDraft sharedDraft = shared(
                demands,
                candidatesByConnection,
                featuresById,
                chamberIncidentCounts,
                independentDraft.lengthByDemand);
        RouteVariant shared = finish("shared", "shared_trunk", sharedDraft);
        if (sharedDraft.sharedPairCount > 0
                && !edgeSignature(independent).equals(edgeSignature(shared))) {
            variants.add(shared);
        }

        String preferred = variants.stream()
                .filter(RouteVariant::isValid)
                .sorted(Comparator
                        .comparingLong(RouteVariant::getConnectedDemandCount).reversed()
                        .thenComparing(RouteVariant::getTotalLengthM)
                        .thenComparing(RouteVariant::getId))
                .map(RouteVariant::getId)
                .findFirst()
                .orElse(null);
        return new OfficialCalculationResult(ALGORITHM_VERSION, demands.size(), variants, preferred);
    }

    private VariantDraft independent(
            List<Demand> demands,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts) {
        VariantDraft draft = new VariantDraft();
        Map<String, Integer> usedChamberSlots = new HashMap<>();
        for (Demand demand : demands) {
            Assignment assignment = chooseAssignment(
                    demand,
                    candidatesByConnection.getOrDefault(demand.connectionPointId, List.of()),
                    featuresById,
                    chamberIncidentCounts,
                    usedChamberSlots,
                    draft,
                    "independent:" + demand.id);
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
            addDirect(draft, demand, assignment, "independent");
        }
        return draft;
    }

    private VariantDraft shared(
            List<Demand> demands,
            Map<String, List<TieInCandidate>> candidatesByConnection,
            Map<String, ImportedOfficialFeature> featuresById,
            Map<String, Integer> chamberIncidentCounts,
            Map<String, Double> independentLengthByDemand) {
        List<PairPlan> plans = new ArrayList<>();
        for (int leftIndex = 0; leftIndex < demands.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < demands.size(); rightIndex++) {
                Demand left = demands.get(leftIndex);
                Demand right = demands.get(rightIndex);
                PairPlan plan = bestPairPlan(
                        left,
                        right,
                        candidatesByConnection,
                        featuresById,
                        chamberIncidentCounts,
                        independentLengthByDemand);
                if (plan != null && plan.savingM > MIN_SHARED_SAVING_M) {
                    plans.add(plan);
                }
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
                    "shared:" + demand.id);
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
            Map<String, Double> independentLengthByDemand) {
        Double independentLeft = independentLengthByDemand.get(left.id);
        Double independentRight = independentLengthByDemand.get(right.id);
        if (independentLeft == null || independentRight == null) {
            return null;
        }
        Map<String, TieInCandidate> rightByTarget = candidatesByConnection
                .getOrDefault(right.connectionPointId, List.of())
                .stream()
                .collect(Collectors.toMap(this::targetKey, candidate -> candidate, (a, b) -> a));
        PairPlan best = null;
        for (TieInCandidate leftCandidate : candidatesByConnection.getOrDefault(left.connectionPointId, List.of())) {
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
            double totalLength = left.coordinate.distance(junction)
                    + right.coordinate.distance(junction)
                    + junction.distance(targetCoordinate);
            double saving = independentLeft + independentRight - totalLength;
            Assignment assignment = new Assignment(
                    leftCandidate,
                    targetCoordinate,
                    rootNode(leftCandidate, targetCoordinate, chamberIncidentCounts,
                            "shared:" + left.id + ":" + right.id));
            PairPlan candidate = new PairPlan(left, right, junction, assignment, totalLength, saving);
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
            String rootSuffix) {
        return candidates.stream()
                .sorted(Comparator.comparing(TieInCandidate::getDistanceM)
                        .thenComparing(this::targetKey))
                .filter(candidate -> !"heat_chamber".equals(candidate.getTargetType())
                        || hasChamberCapacity(candidate.getTargetId(), chamberIncidentCounts, usedChamberSlots))
                .map(candidate -> {
                    ImportedOfficialFeature target = featuresById.get(candidate.getTargetId());
                    if (target == null) {
                        return null;
                    }
                    Coordinate coordinate = targetCoordinate(demand.coordinate, target.getMetricGeometry());
                    return new Assignment(
                            candidate,
                            coordinate,
                            rootNode(candidate, coordinate, chamberIncidentCounts, rootSuffix));
                })
                .filter(assignment -> assignment != null)
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
        double length = demand.coordinate.distance(assignment.targetCoordinate);
        if (length <= MIN_EDGE_LENGTH_M) {
            draft.noRoute(demand, "ROUTE_LENGTH_ZERO");
            return;
        }
        draft.addEdge(new RouteEdge(
                prefix + ":edge:" + demand.id,
                assignment.root.getId(),
                demandNode.getId(),
                length));
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
        double leftLength = plan.left.coordinate.distance(plan.junction);
        double rightLength = plan.right.coordinate.distance(plan.junction);
        double trunkLength = plan.junction.distance(plan.assignment.targetCoordinate);
        if (leftLength <= MIN_EDGE_LENGTH_M || rightLength <= MIN_EDGE_LENGTH_M || trunkLength <= MIN_EDGE_LENGTH_M) {
            draft.noRoute(plan.left, "ROUTE_LENGTH_ZERO");
            draft.noRoute(plan.right, "ROUTE_LENGTH_ZERO");
            return;
        }
        draft.addEdge(new RouteEdge("shared:branch:" + plan.left.id, junction.getId(), leftNode.getId(), leftLength));
        draft.addEdge(new RouteEdge("shared:branch:" + plan.right.id, junction.getId(), rightNode.getId(), rightLength));
        draft.addEdge(new RouteEdge(
                "shared:trunk:" + pairId,
                plan.assignment.root.getId(),
                junction.getId(),
                trunkLength));
        draft.connected(plan.left, leftLength + trunkLength / 2.0);
        draft.connected(plan.right, rightLength + trunkLength / 2.0);
    }

    private RouteVariant finish(String id, String strategy, VariantDraft draft) {
        List<RouteNode> nodes = draft.nodes.values().stream()
                .sorted(Comparator.comparing(RouteNode::getId))
                .collect(Collectors.toList());
        draft.edges.sort(Comparator.comparing(RouteEdge::getId));
        draft.connections.sort(Comparator.comparing(RouteConnection::getDemandId));
        List<RouteValidationIssue> issues = validator.validate(nodes, draft.edges);
        BigDecimal totalLength = draft.edges.stream()
                .map(RouteEdge::getLengthM)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(3, RoundingMode.HALF_UP);
        return new RouteVariant(id, strategy, nodes, draft.edges, draft.connections, totalLength, issues);
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
                .map(edge -> edge.getUpstreamNodeId() + "<-" + edge.getDownstreamNodeId())
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

        private Assignment(TieInCandidate candidate, Coordinate targetCoordinate, RouteNode root) {
            this.candidate = candidate;
            this.targetCoordinate = targetCoordinate;
            this.root = root;
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
        private final double totalLengthM;
        private final double savingM;

        private PairPlan(
                Demand left,
                Demand right,
                Coordinate junction,
                Assignment assignment,
                double totalLengthM,
                double savingM) {
            this.left = left;
            this.right = right;
            this.junction = junction;
            this.assignment = assignment;
            this.totalLengthM = totalLengthM;
            this.savingM = savingM;
        }
    }

    private static class VariantDraft {
        private final Map<String, RouteNode> nodes = new LinkedHashMap<>();
        private final List<RouteEdge> edges = new ArrayList<>();
        private final List<RouteConnection> connections = new ArrayList<>();
        private final Map<String, Double> lengthByDemand = new HashMap<>();
        private int sharedPairCount;

        private VariantDraft copy() {
            VariantDraft copy = new VariantDraft();
            copy.nodes.putAll(nodes);
            copy.edges.addAll(edges);
            copy.connections.addAll(connections);
            copy.lengthByDemand.putAll(lengthByDemand);
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
