package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.depth.OfficialUtilityHorizontalAssessment;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.ExistingNetworkIncidence;

/** Независимо проверяет топологию, обязательные повороты, пересечения и пространственные ограничения. */
@Component
public class OfficialRouteValidator {
    private static final double TOLERANCE_M = 0.01;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final OfficialRouteGeometryRules geometryRules;
    private final OfficialUtilityHorizontalAssessment utilityHorizontalAssessment;

    public OfficialRouteValidator() {
        this.geometryRules = null;
        this.utilityHorizontalAssessment = null;
    }

    public OfficialRouteValidator(OfficialRouteGeometryRules geometryRules) {
        this(geometryRules, new OfficialPipeCatalog());
    }

    @Autowired
    public OfficialRouteValidator(
            OfficialRouteGeometryRules geometryRules, OfficialPipeCatalog pipeCatalog) {
        this.geometryRules = geometryRules;
        this.utilityHorizontalAssessment =
                geometryRules == null
                        ? null
                        : new OfficialUtilityHorizontalAssessment(pipeCatalog);
    }

    public List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges) {
        List<RouteValidationIssue> issues = new ArrayList<>();
        Map<String, RouteNode> nodeById = new HashMap<>();
        for (RouteNode node : nodes) {
            if (nodeById.putIfAbsent(node.getId(), node) != null) {
                issues.add(issue("DUPLICATE_NODE_ID", node.getId(), "Route node IDs must be unique"));
            }
        }

        Map<String, RouteEdge> upstreamByDownstream = new HashMap<>();
        Map<String, Integer> childCount = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (RouteEdge edge : edges) {
            if (!edgeIds.add(edge.getId())) {
                issues.add(issue("DUPLICATE_EDGE_ID", edge.getId(), "Route edge IDs must be unique"));
            }
            if (!nodeById.containsKey(edge.getUpstreamNodeId())
                    || !nodeById.containsKey(edge.getDownstreamNodeId())) {
                issues.add(issue("UNKNOWN_EDGE_NODE", edge.getId(), "Both edge endpoints must exist"));
                continue;
            }
            if (edge.getLengthM().signum() <= 0) {
                issues.add(issue("NON_POSITIVE_EDGE_LENGTH", edge.getId(), "Route edge length must be positive"));
            }
            validateSelfIntersection(edge, issues);
            RouteEdge previous = upstreamByDownstream.putIfAbsent(edge.getDownstreamNodeId(), edge);
            if (previous != null) {
                issues.add(issue(
                        "MULTIPLE_UPSTREAM_EDGES",
                        edge.getDownstreamNodeId(),
                        "Every non-root route node must have exactly one upstream edge"));
            }
            childCount.merge(edge.getUpstreamNodeId(), 1, Integer::sum);
        }

        validateRootsAndCycles(nodes, upstreamByDownstream, issues);
        validateChambers(nodes, childCount, issues);
        issues.addAll(chamberCapacityIssues(nodes, edges));
        issues.addAll(OfficialRouteDeflectionRules.validate(nodes, edges));
        validateCrossings(nodeById, edges, issues);
        issues.sort(Comparator.comparing(RouteValidationIssue::getCode)
                .thenComparing(issue -> issue.getSubjectId() == null ? "" : issue.getSubjectId()));
        return issues;
    }

    public List<RouteValidationIssue> validate(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features) {
        return validate(nodes, edges, features, null);
    }

    /** Создаёт сессию одного расчёта; независимая публичная проверка её подготовку не использует. */
    ValidationSession forCalculation() {
        return new ValidationSession();
    }

    /**
     * Повторно использует ограниченную подготовку препятствий и нормалей этого валидатора.
     * Маршруты и результаты допуска не удерживаются; сессия не предназначена для параллельного доступа.
     */
    final class ValidationSession {
        private final PreparedValidationConstraints preparedConstraints;
        private final PreparedNormalEgressMemo normalEgresses;
        private List<ImportedOfficialFeature> incidenceFeatures;
        private ExistingNetworkIncidence incidence;

        private ValidationSession() {
            preparedConstraints = geometryRules == null ? null : new PreparedValidationConstraints(geometryRules);
            normalEgresses = geometryRules != null && geometryRules.hasStandardPreparationRules()
                    ? new PreparedNormalEgressMemo(geometryRules) : null;
        }

        List<RouteValidationIssue> validate(
                List<RouteNode> nodes,
                List<RouteEdge> edges,
                List<ImportedOfficialFeature> features) {
            // Наследник может дополнять окончательную проверку: сохраняем его публичный hook.
            if (OfficialRouteValidator.this.getClass() != OfficialRouteValidator.class) {
                return OfficialRouteValidator.this.validate(nodes, edges, features);
            }
            if (features == null && geometryRules == null) return OfficialRouteValidator.this.validate(nodes, edges);
            if (incidenceFeatures != features) {
                incidence = new ExistingNetworkIncidence(features);
                incidenceFeatures = features;
            }
            return OfficialRouteValidator.this.validateWithResolvedIncidence(incidence.resolved(nodes), edges, features,
                    preparedConstraints != null && preparedConstraints.supports(features) ? preparedConstraints : null,
                    normalEgresses);
        }
    }

    private List<RouteValidationIssue> validate(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            PreparedValidationConstraints preparedConstraints) {
        if (features == null && geometryRules == null) return validate(nodes, edges);
        return validateWithResolvedIncidence(new ExistingNetworkIncidence(features).resolved(nodes),
                edges, features, preparedConstraints, null);
    }

    private List<RouteValidationIssue> validateWithResolvedIncidence(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            PreparedValidationConstraints preparedConstraints, PreparedNormalEgressMemo normalEgresses) {
        List<RouteValidationIssue> issues = new ArrayList<>(validate(nodes, edges));
        if (geometryRules == null) {
            return issues;
        }
        issues.addAll(validateSpatialConstraints(nodes, edges, features, preparedConstraints, false, normalEgresses));
        if (issues.isEmpty() && !edges.isEmpty()) {
            Map<String, Set<String>> tieIns = new HashMap<>();
            for (RouteNode node : nodes) {
                if (node.isRoot()) {
                    tieIns.put(
                            node.getId(),
                            node.getTargetId() == null
                                    ? Set.of()
                                    : Set.of(node.getTargetId()));
                }
            }
            OfficialUtilityHorizontalAssessment.Result utility =
                    utilityHorizontalAssessment.assess(edges, features, tieIns);
            for (OfficialUtilityHorizontalAssessment.Finding finding :
                    utility.getOrdinaryViolations()) {
                issues.add(
                        issue(
                                "UTILITY_HORIZONTAL_CLEARANCE_VIOLATION",
                                finding.getEdgeId(),
                                String.format(
                                        Locale.ROOT,
                                        "Route axis is %.6f m from %s %s at %.3f m; required %s m",
                                        finding.getActualAxisDistanceM(),
                                        finding.getType().getCode(),
                                        finding.getSourceId(),
                                        finding.getWitnessStationM(),
                                        finding.getRequiredAxisDistanceM().toPlainString())));
            }
        }
        issues.sort(Comparator.comparing(RouteValidationIssue::getCode)
                .thenComparing(issue -> issue.getSubjectId() == null ? "" : issue.getSubjectId()));
        return issues;
    }

    /**
     * Создаёт независимую проверку запрещённых препятствий и вводов ОКС по исходному импорту.
     * Сессия держит только подготовку препятствий; рёбра можно подавать по одному после проверки топологии.
     */
    public ForbiddenClearanceSession forForbiddenClearanceValidation(List<ImportedOfficialFeature> features) {
        if (geometryRules == null) throw new IllegalStateException("Spatial validation requires geometry rules");
        return new ForbiddenClearanceSession(features);
    }

    public final class ForbiddenClearanceSession {
        private final List<ImportedOfficialFeature> features;
        private final PreparedValidationConstraints prepared;

        private ForbiddenClearanceSession(List<ImportedOfficialFeature> features) {
            this.features = features;
            PreparedValidationConstraints candidate = new PreparedValidationConstraints(geometryRules);
            this.prepared = candidate.supports(features) ? candidate : null;
        }

        /** Связность, разрешённые специальные пересечения и их разметку проверяет вызывающая сторона. */
        public List<RouteValidationIssue> validate(RouteNode upstream, RouteNode downstream, RouteEdge edge) {
            return validateSpatialConstraints(List.of(upstream, downstream), List.of(edge), features, prepared, true, null);
        }
    }

    private List<RouteValidationIssue> validateSpatialConstraints(
            List<RouteNode> nodes, List<RouteEdge> edges, List<ImportedOfficialFeature> features,
            PreparedValidationConstraints preparedConstraints, boolean forbiddenOnly,
            PreparedNormalEgressMemo normalEgresses) {
        List<RouteValidationIssue> issues = new ArrayList<>();
        Map<String, RouteNode> nodesById = new HashMap<>();
        nodes.forEach(node -> nodesById.put(node.getId(), node));
        Map<String, Coordinate> sourceDemandPoints = new HashMap<>();
        features.stream().filter(feature -> "oks_connection_point".equals(feature.getObjectType()))
                .filter(feature -> feature.getMetricGeometry() instanceof Point)
                .filter(feature -> !feature.getMetricGeometry().isEmpty())
                .forEach(feature -> sourceDemandPoints.put(
                        feature.getFeatureId(), feature.getMetricGeometry().getCoordinate()));
        // Standalone/fallback готовит один набор буферов на ДУ; сессия отбирает по полной полилинии.
        // Исключения и подходы к endpoints применяются отдельно, исходные ограничения не меняются.
        Map<Integer, List<OfficialRouteGeometryRules.Constraint>> constraintsByDiameter = new HashMap<>();
        for (RouteEdge edge : edges) {
            RouteNode upstream = nodesById.get(edge.getUpstreamNodeId());
            RouteNode downstream = nodesById.get(edge.getDownstreamNodeId());
            LineString route = line(edge, nodesById);
            if (route == null || upstream == null || downstream == null) {
                continue;
            }
            if (!edge.getCoordinates().isEmpty()) {
                if (route.getCoordinateN(0).distance(upstream.getCoordinate().toCoordinate()) > TOLERANCE_M
                        || route.getCoordinateN(route.getNumPoints() - 1)
                                .distance(downstream.getCoordinate().toCoordinate()) > TOLERANCE_M) {
                    issues.add(issue(
                            "EDGE_GEOMETRY_ENDPOINT_MISMATCH",
                            edge.getId(),
                            "Route geometry must begin and end at its declared nodes"));
                }
                if (Math.abs(route.getLength() - edge.getLengthM().doubleValue()) > TOLERANCE_M) {
                    issues.add(issue(
                            "EDGE_GEOMETRY_LENGTH_MISMATCH",
                            edge.getId(),
                            "Route edge length must equal its geometry length"));
                }
            }
            Set<String> exemptions = new HashSet<>();
            if (upstream.isRoot() && upstream.getTargetId() != null) {
                exemptions.add(upstream.getTargetId());
            }
            if (downstream.isRoot() && downstream.getTargetId() != null) {
                exemptions.add(downstream.getTargetId());
            }
            int diameter = edge.getDiameter() == null ? 50 : edge.getDiameter();
            // Принадлежность ОКС определяется исходным подключением: округлённый узел на стене
            // может оказаться снаружи, но это не превращает обязательный ввод в свободную точку.
            Coordinate connectionPoint = downstream.getCoordinate().toCoordinate();
            if ("demand_connection".equals(downstream.getNodeType())) {
                connectionPoint = sourceDemandPoints.getOrDefault(downstream.getTargetId(), connectionPoint);
                if (connectionPoint.distance(downstream.getCoordinate().toCoordinate()) > TOLERANCE_M) {
                    issues.add(issue("DEMAND_CONNECTION_COORDINATE_MISMATCH", downstream.getId(),
                            "Demand node must coincide with its original input connection point"));
                }
            }
            issues.addAll(normalEgresses == null
                    ? geometryRules.validateMandatoryEgress(edge, route, features, diameter, connectionPoint)
                    : geometryRules.validateMandatoryEgress(edge, route, features, diameter, connectionPoint, normalEgresses));
            List<OfficialRouteGeometryRules.Constraint> baseConstraints = preparedConstraints == null
                    ? constraintsByDiameter.computeIfAbsent(diameter, value -> geometryRules.baseConstraints(features, value))
                    : preparedConstraints.prepareIntersecting(features, diameter, route.getEnvelopeInternal());
            if (forbiddenOnly) {
                baseConstraints = baseConstraints.stream().filter(constraint -> constraint.rule().isForbidden())
                        .collect(java.util.stream.Collectors.toList());
            }
            List<OfficialRouteGeometryRules.Constraint> allConstraints = geometryRules.applicableConstraints(
                    geometryRules.localTieInConstraints(baseConstraints, exemptions,
                            route.getCoordinateN(0), route.getCoordinateN(route.getNumPoints() - 1)),
                    Set.of(),
                    route.getCoordinateN(0),
                    route.getCoordinateN(route.getNumPoints() - 1));
            OfficialRouteGeometryRules.NormalEgress egress = "demand_connection".equals(downstream.getNodeType())
                    ? validationNormalEgress(features, diameter, connectionPoint,
                            route.getCoordinateN(route.getNumPoints() - 2), normalEgresses)
                    : null;
            if (egress == null) {
                issues.addAll(geometryRules.validate(edge, route, allConstraints));
                continue;
            }

            List<OfficialRouteGeometryRules.Constraint> outsideConstraints = allConstraints.stream()
                    .filter(constraint -> !egress.exempts(constraint))
                    .collect(java.util.stream.Collectors.toList());
            issues.addAll(geometryRules.validate(edge, route, outsideConstraints));

            // Только последний полный прямой ввод освобождён от собственного отступа.
            // Остальная линия не получает льготу ни вдоль стены, ни у её углов.
            if (route.getNumPoints() > 2) {
                Coordinate[] outsideCoordinates = new Coordinate[route.getNumPoints() - 1];
                for (int index = 0; index < outsideCoordinates.length; index++) {
                    outsideCoordinates[index] = route.getCoordinateN(index);
                }
                LineString outsideRoute = geometryFactory.createLineString(outsideCoordinates);
                issues.addAll(geometryRules.validateOwnTerminalClearance(
                        edge, outsideRoute, baseConstraints, egress, diameter));
            }
        }
        issues.sort(Comparator.comparing(RouteValidationIssue::getCode)
                .thenComparing(issue -> issue.getSubjectId() == null ? "" : issue.getSubjectId()));
        return issues;
    }

    /** Цель сортирует полный набор padded-нормалей; mandatory check отдельно использует raw-нормали. */
    private OfficialRouteGeometryRules.NormalEgress validationNormalEgress(
            List<ImportedOfficialFeature> features, int diameter, Coordinate point,
            Coordinate target, PreparedNormalEgressMemo normalEgresses) {
        if (normalEgresses == null) {
            return geometryRules.normalEgressTowards(features, diameter, point, target, RouteTraversal.REVERSED)
                    .orElse(null);
        }
        return geometryRules.sortNormalEgressesForTarget(
                normalEgresses.prepare(features, diameter, point, RouteTraversal.REVERSED), target)
                .stream().findFirst().orElse(null);
    }

    private void validateRootsAndCycles(
            List<RouteNode> nodes,
            Map<String, RouteEdge> upstreamByDownstream,
            List<RouteValidationIssue> issues) {
        for (RouteNode node : nodes) {
            Set<String> path = new HashSet<>();
            RouteNode cursor = node;
            while (true) {
                if (!path.add(cursor.getId())) {
                    issues.add(issue("ROUTE_CYCLE", node.getId(), "Route contains a directed cycle"));
                    break;
                }
                RouteEdge upstream = upstreamByDownstream.get(cursor.getId());
                if (upstream == null) {
                    if (!cursor.isRoot()) {
                        issues.add(issue(
                                "UPSTREAM_PATH_INCOMPLETE",
                                node.getId(),
                                "Every connected route node must reach an existing-network root"));
                    }
                    break;
                }
                cursor = findNode(nodes, upstream.getUpstreamNodeId());
                if (cursor == null) {
                    break;
                }
            }
        }
    }

    private void validateChambers(
            List<RouteNode> nodes,
            Map<String, Integer> childCount,
            List<RouteValidationIssue> issues) {
        for (RouteNode node : nodes) {
            int children = childCount.getOrDefault(node.getId(), 0);
            if (children > 1 && !node.isChamber()) {
                issues.add(issue(
                        "BRANCH_WITHOUT_CHAMBER",
                        node.getId(),
                        "A route can branch only in a chamber"));
            }
        }
    }

    /** Проверяет лимит четырёх участков; перед вызовом исходные примыкания разрешаются по импорту. */
    public static List<RouteValidationIssue> chamberCapacityIssues(List<RouteNode> nodes, List<RouteEdge> edges) {
        Map<String, Integer> incident = new HashMap<>();
        for (RouteEdge edge : edges) {
            incident.merge(edge.getUpstreamNodeId(), 1, Integer::sum);
            incident.merge(edge.getDownstreamNodeId(), 1, Integer::sum);
        }
        List<RouteValidationIssue> issues = new ArrayList<>();
        for (RouteNode node : nodes) {
            if (incident.getOrDefault(node.getId(), 0) + node.getBaseIncidentSections() > 4) {
                issues.add(new RouteValidationIssue(
                        "CHAMBER_DEGREE_EXCEEDED",
                        node.getId(),
                        "No chamber may have more than four incident sections"));
            }
        }
        return issues;
    }

    /** Проверяет всё ребро: короткий или повторный первый сегмент не отменяет проверку остальной геометрии. */
    private void validateSelfIntersection(RouteEdge edge, List<RouteValidationIssue> issues) {
        if (edge.getCoordinates().size() < 3) return;
        LineString route = geometryFactory.createLineString(edge.getCoordinates().stream()
                .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
        // JTS допускает простое кольцо, но ребро дерева не может образовывать физическую петлю.
        // Нулевая геометрия здесь не самопересечение; соседние совпадающие точки сами по себе допустимы.
        if (route.getLength() > 0 && (route.isClosed() || !route.isSimple())) {
            issues.add(issue("SELF_INTERSECTION", edge.getId(),
                    "Route edge must not intersect, retrace or close onto itself"));
        }
    }

    private void validateCrossings(
            Map<String, RouteNode> nodeById,
            List<RouteEdge> edges,
            List<RouteValidationIssue> issues) {
        for (int leftIndex = 0; leftIndex < edges.size(); leftIndex++) {
            RouteEdge left = edges.get(leftIndex);
            LineString leftLine = line(left, nodeById);
            if (leftLine == null) {
                continue;
            }
            for (int rightIndex = leftIndex + 1; rightIndex < edges.size(); rightIndex++) {
                RouteEdge right = edges.get(rightIndex);
                LineString rightLine = line(right, nodeById);
                if (rightLine == null || !leftLine.getEnvelopeInternal().intersects(rightLine.getEnvelopeInternal())) {
                    continue;
                }
                Geometry intersection = leftLine.intersection(rightLine);
                if (intersection.isEmpty()) {
                    continue;
                }
                String sharedNode = sharedNode(left, right);
                if (sharedNode == null || !isOnlySharedPoint(intersection, nodeById.get(sharedNode))) {
                    issues.add(issue(
                            "CROSSING_OUTSIDE_COMMON_NODE",
                            left.getId() + "|" + right.getId(),
                            "New route sections may intersect only at their shared endpoint"));
                }
            }
        }
    }

    private boolean isOnlySharedPoint(Geometry intersection, RouteNode sharedNode) {
        return intersection instanceof Point
                && sharedNode != null
                && intersection.getCoordinate().distance(sharedNode.getCoordinate().toCoordinate()) <= TOLERANCE_M;
    }

    private LineString line(RouteEdge edge, Map<String, RouteNode> nodeById) {
        RouteNode upstream = nodeById.get(edge.getUpstreamNodeId());
        RouteNode downstream = nodeById.get(edge.getDownstreamNodeId());
        if (upstream == null || downstream == null) {
            return null;
        }
        Coordinate[] coordinates = edge.getCoordinates().isEmpty()
                ? new Coordinate[] {
                    upstream.getCoordinate().toCoordinate(), downstream.getCoordinate().toCoordinate()
                }
                : edge.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate)
                        .toArray(Coordinate[]::new);
        if (coordinates[0].distance(coordinates[1]) <= TOLERANCE_M) {
            return null;
        }
        return geometryFactory.createLineString(coordinates);
    }

    private String sharedNode(RouteEdge left, RouteEdge right) {
        String[] leftNodes = {left.getUpstreamNodeId(), left.getDownstreamNodeId()};
        String[] rightNodes = {right.getUpstreamNodeId(), right.getDownstreamNodeId()};
        for (String leftNode : leftNodes) {
            for (String rightNode : rightNodes) {
                if (leftNode.equals(rightNode)) {
                    return leftNode;
                }
            }
        }
        return null;
    }

    private RouteNode findNode(List<RouteNode> nodes, String id) {
        return nodes.stream().filter(node -> node.getId().equals(id)).findFirst().orElse(null);
    }

    private RouteValidationIssue issue(String code, String subjectId, String message) {
        return new RouteValidationIssue(code, subjectId, message);
    }
}
