package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialRouteValidator {
    private static final double TOLERANCE_M = 0.01;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final OfficialRouteGeometryRules geometryRules;

    public OfficialRouteValidator() {
        this.geometryRules = null;
    }

    @Autowired
    public OfficialRouteValidator(OfficialRouteGeometryRules geometryRules) {
        this.geometryRules = geometryRules;
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
        validateChambers(nodes, upstreamByDownstream, childCount, issues);
        validateCrossings(nodeById, edges, issues);
        issues.sort(Comparator.comparing(RouteValidationIssue::getCode)
                .thenComparing(issue -> issue.getSubjectId() == null ? "" : issue.getSubjectId()));
        return issues;
    }

    public List<RouteValidationIssue> validate(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features) {
        List<RouteValidationIssue> issues = new ArrayList<>(validate(nodes, edges));
        if (geometryRules == null) {
            return issues;
        }
        Map<String, RouteNode> nodesById = new HashMap<>();
        nodes.forEach(node -> nodesById.put(node.getId(), node));
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
            issues.addAll(geometryRules.validateMandatoryEgress(edge, route, features, diameter));
            List<OfficialRouteGeometryRules.Constraint> allConstraints = geometryRules.constraints(
                    features,
                    diameter,
                    exemptions,
                    route.getCoordinateN(0),
                    route.getCoordinateN(route.getNumPoints() - 1));
            OfficialRouteGeometryRules.NormalEgress egress = "demand_connection".equals(downstream.getNodeType())
                    ? geometryRules.normalEgress(features, diameter, downstream.getCoordinate().toCoordinate())
                            .orElse(null)
                    : null;
            if (egress == null) {
                issues.addAll(geometryRules.validate(edge, route, allConstraints));
                continue;
            }

            Set<String> withOwnOksExempt = new HashSet<>(exemptions);
            withOwnOksExempt.add(egress.oksId());
            List<OfficialRouteGeometryRules.Constraint> outsideConstraints = geometryRules.constraints(
                    features,
                    diameter,
                    withOwnOksExempt,
                    route.getCoordinateN(0),
                    route.getCoordinateN(route.getNumPoints() - 1));
            issues.addAll(geometryRules.validate(edge, route, outsideConstraints));

            // Only the final, validated normal-egress leg may enter the demand's own OKS. The
            // independently checked route prefix must still avoid that building and its buffer.
            if (route.getNumPoints() > 2) {
                Coordinate[] outsideCoordinates = new Coordinate[route.getNumPoints() - 1];
                for (int index = 0; index < outsideCoordinates.length; index++) {
                    outsideCoordinates[index] = route.getCoordinateN(index);
                }
                LineString outsideRoute = geometryFactory.createLineString(outsideCoordinates);
                List<OfficialRouteGeometryRules.Constraint> ownOks = allConstraints.stream()
                        .filter(constraint -> egress.oksId().equals(constraint.id()))
                        .collect(java.util.stream.Collectors.toList());
                issues.addAll(geometryRules.validateForbidden(edge, outsideRoute, ownOks));
            }
        }
        issues.sort(Comparator.comparing(RouteValidationIssue::getCode)
                .thenComparing(issue -> issue.getSubjectId() == null ? "" : issue.getSubjectId()));
        return issues;
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
            Map<String, RouteEdge> upstreamByDownstream,
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
            int routeIncident = children + (upstreamByDownstream.containsKey(node.getId()) ? 1 : 0);
            if (routeIncident + node.getBaseIncidentSections() > 4) {
                issues.add(issue(
                        "CHAMBER_DEGREE_EXCEEDED",
                        node.getId(),
                        "No chamber may have more than four incident sections"));
            }
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
