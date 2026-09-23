package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;

/**
 * Evaluates the additional constructability rules supplied by the domain expert.
 *
 * <p>The official obstacle catalogue remains the source of truth for clearances and crossings.
 * This evaluator deliberately covers only the two extra geometry rules used by the engineering
 * and shortest portfolio representatives: an internal bend angle from 90 to 135 degrees and at
 * least two metres between consecutive bends.
 */
final class EngineeringRouteEvaluator {
    static final double MIN_INTERNAL_ANGLE_DEGREES = 90.0;
    static final double MAX_INTERNAL_ANGLE_DEGREES = 135.0;
    static final double MIN_BEND_SPACING_M = 2.0;
    private static final double ANGLE_EPSILON_DEGREES = 0.5;
    private static final double LENGTH_EPSILON_M = 0.01;

    Evaluation evaluate(List<RouteEdge> edges) {
        int bendCount = 0;
        int invalidAngleCount = 0;
        int insufficientSpacingCount = 0;
        double totalAngleDeviation = 0.0;
        double preferredAngleDeviation = 0.0;
        int irregularJunctionAngleCount = 0;
        double totalJunctionAngleDeviation = 0.0;
        Set<String> nonCompliantEdgeIds = new LinkedHashSet<>();
        Map<String, List<IncidentDirection>> directionsByNode = new LinkedHashMap<>();

        for (RouteEdge edge : edges) {
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            addIncidentDirections(directionsByNode, edge, coordinates);
            List<Integer> bends = new ArrayList<>();
            for (int index = 1; index + 1 < coordinates.size(); index++) {
                Coordinate before = coordinates.get(index - 1).toCoordinate();
                Coordinate at = coordinates.get(index).toCoordinate();
                Coordinate after = coordinates.get(index + 1).toCoordinate();
                double internalAngle = internalAngleDegrees(before, at, after);
                if (isStraight(internalAngle)) {
                    continue;
                }
                bendCount++;
                bends.add(index);
                double deviation = angleDeviation(internalAngle);
                totalAngleDeviation += deviation;
                preferredAngleDeviation += preferredBendAngleDeviation(internalAngle);
                if (deviation > ANGLE_EPSILON_DEGREES) {
                    invalidAngleCount++;
                    nonCompliantEdgeIds.add(edge.getId());
                }
            }
            for (int index = 1; index < bends.size(); index++) {
                Coordinate previous = coordinates.get(bends.get(index - 1)).toCoordinate();
                Coordinate current = coordinates.get(bends.get(index)).toCoordinate();
                if (previous.distance(current) + LENGTH_EPSILON_M < MIN_BEND_SPACING_M) {
                    insufficientSpacingCount++;
                    nonCompliantEdgeIds.add(edge.getId());
                }
            }
        }

        for (List<IncidentDirection> directions : directionsByNode.values()) {
            if (directions.size() == 2) {
                double internalAngle = angleBetween(directions.get(0), directions.get(1));
                if (isStraight(internalAngle)) {
                    continue;
                }
                bendCount++;
                double deviation = angleDeviation(internalAngle);
                totalAngleDeviation += deviation;
                preferredAngleDeviation += preferredBendAngleDeviation(internalAngle);
                if (deviation > ANGLE_EPSILON_DEGREES) {
                    invalidAngleCount++;
                    nonCompliantEdgeIds.add(directions.get(0).edgeId);
                    nonCompliantEdgeIds.add(directions.get(1).edgeId);
                }
                continue;
            }
            if (directions.size() < 3) {
                continue;
            }
            for (int left = 0; left < directions.size(); left++) {
                for (int right = left + 1; right < directions.size(); right++) {
                    double angle = angleBetween(directions.get(left), directions.get(right));
                    double deviation = preferredJunctionAngleDeviation(angle);
                    totalJunctionAngleDeviation += deviation;
                    if (deviation > ANGLE_EPSILON_DEGREES) {
                        irregularJunctionAngleCount++;
                    }
                }
            }
        }

        return new Evaluation(
                bendCount,
                invalidAngleCount,
                insufficientSpacingCount,
                totalAngleDeviation,
                preferredAngleDeviation,
                irregularJunctionAngleCount,
                totalJunctionAngleDeviation,
                nonCompliantEdgeIds);
    }

    private void addIncidentDirections(
            Map<String, List<IncidentDirection>> directionsByNode,
            RouteEdge edge,
            List<RouteCoordinate> coordinates) {
        if (coordinates.size() < 2) {
            return;
        }
        Coordinate upstream = coordinates.get(0).toCoordinate();
        Coordinate upstreamNext = coordinates.get(1).toCoordinate();
        Coordinate downstream = coordinates.get(coordinates.size() - 1).toCoordinate();
        Coordinate downstreamPrevious = coordinates.get(coordinates.size() - 2).toCoordinate();
        addIncidentDirection(directionsByNode, edge.getUpstreamNodeId(), edge.getId(), upstream, upstreamNext);
        addIncidentDirection(
                directionsByNode, edge.getDownstreamNodeId(), edge.getId(), downstream, downstreamPrevious);
    }

    private void addIncidentDirection(
            Map<String, List<IncidentDirection>> directionsByNode,
            String nodeId,
            String edgeId,
            Coordinate node,
            Coordinate adjacent) {
        double dx = adjacent.x - node.x;
        double dy = adjacent.y - node.y;
        double length = Math.hypot(dx, dy);
        if (length <= LENGTH_EPSILON_M) {
            return;
        }
        directionsByNode.computeIfAbsent(nodeId, ignored -> new ArrayList<>())
                .add(new IncidentDirection(edgeId, dx / length, dy / length));
    }

    private double angleBetween(IncidentDirection left, IncidentDirection right) {
        double cosine = Math.max(-1.0, Math.min(1.0, left.dx * right.dx + left.dy * right.dy));
        return Math.toDegrees(Math.acos(cosine));
    }

    private double internalAngleDegrees(Coordinate before, Coordinate at, Coordinate after) {
        double ax = before.x - at.x;
        double ay = before.y - at.y;
        double bx = after.x - at.x;
        double by = after.y - at.y;
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator <= LENGTH_EPSILON_M) {
            return 180.0;
        }
        double cosine = Math.max(-1.0, Math.min(1.0, (ax * bx + ay * by) / denominator));
        return Math.toDegrees(Math.acos(cosine));
    }

    private boolean isStraight(double internalAngle) {
        return Math.abs(180.0 - internalAngle) <= ANGLE_EPSILON_DEGREES;
    }

    private double angleDeviation(double internalAngle) {
        if (internalAngle + ANGLE_EPSILON_DEGREES >= MIN_INTERNAL_ANGLE_DEGREES
                && internalAngle <= MAX_INTERNAL_ANGLE_DEGREES + ANGLE_EPSILON_DEGREES) {
            return 0.0;
        }
        return Math.min(
                Math.abs(internalAngle - MIN_INTERNAL_ANGLE_DEGREES),
                Math.abs(internalAngle - MAX_INTERNAL_ANGLE_DEGREES));
    }

    private double preferredBendAngleDeviation(double internalAngle) {
        return Math.min(
                Math.abs(internalAngle - MIN_INTERNAL_ANGLE_DEGREES),
                Math.abs(internalAngle - MAX_INTERNAL_ANGLE_DEGREES));
    }

    private double preferredJunctionAngleDeviation(double angle) {
        return Math.min(
                Math.min(Math.abs(angle - 45.0), Math.abs(angle - 90.0)),
                Math.min(Math.abs(angle - 135.0), Math.abs(angle - 180.0)));
    }

    private static final class IncidentDirection {
        private final String edgeId;
        private final double dx;
        private final double dy;

        private IncidentDirection(String edgeId, double dx, double dy) {
            this.edgeId = edgeId;
            this.dx = dx;
            this.dy = dy;
        }
    }

    static final class Evaluation {
        private final int bendCount;
        private final int invalidAngleCount;
        private final int insufficientSpacingCount;
        private final double totalAngleDeviation;
        private final double preferredAngleDeviation;
        private final int irregularJunctionAngleCount;
        private final double totalJunctionAngleDeviation;
        private final Set<String> nonCompliantEdgeIds;

        private Evaluation(
                int bendCount,
                int invalidAngleCount,
                int insufficientSpacingCount,
                double totalAngleDeviation,
                double preferredAngleDeviation,
                int irregularJunctionAngleCount,
                double totalJunctionAngleDeviation,
                Set<String> nonCompliantEdgeIds) {
            this.bendCount = bendCount;
            this.invalidAngleCount = invalidAngleCount;
            this.insufficientSpacingCount = insufficientSpacingCount;
            this.totalAngleDeviation = totalAngleDeviation;
            this.preferredAngleDeviation = preferredAngleDeviation;
            this.irregularJunctionAngleCount = irregularJunctionAngleCount;
            this.totalJunctionAngleDeviation = totalJunctionAngleDeviation;
            this.nonCompliantEdgeIds = Collections.unmodifiableSet(new LinkedHashSet<>(nonCompliantEdgeIds));
        }

        int bendCount() { return bendCount; }
        int invalidAngleCount() { return invalidAngleCount; }
        int insufficientSpacingCount() { return insufficientSpacingCount; }
        double totalAngleDeviation() { return totalAngleDeviation; }
        double preferredAngleDeviation() { return preferredAngleDeviation; }
        int irregularJunctionAngleCount() { return irregularJunctionAngleCount; }
        double totalJunctionAngleDeviation() { return totalJunctionAngleDeviation; }
        Set<String> nonCompliantEdgeIds() { return nonCompliantEdgeIds; }
        boolean isCompliant() {
            return invalidAngleCount == 0 && insufficientSpacingCount == 0;
        }
    }
}
