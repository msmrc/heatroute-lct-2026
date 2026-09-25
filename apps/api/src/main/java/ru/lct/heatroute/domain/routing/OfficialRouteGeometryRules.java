package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialRouteGeometryRules {
    static final double EPSILON_M = 0.01;
    static final double NORMAL_EGRESS_MARGIN_M = 0.25;
    private static final double MAX_ALTERNATIVE_EGRESS_EXTRA_M = 60.0;
    private static final double MAX_ALTERNATIVE_EGRESS_DISTANCE_FACTOR = 3.0;
    private static final double CLEARANCE_BOUNDARY_EPSILON_M = 1e-6;
    private static final Comparator<Constraint> CONSTRAINT_ORDER = Comparator
            .comparing((Constraint item) -> item.type)
            .thenComparing(item -> item.id);

    private final OfficialConstraintCatalog catalog;
    private final OfficialCrossingGeometry crossingGeometry;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public OfficialRouteGeometryRules(
            OfficialConstraintCatalog catalog,
            OfficialCrossingGeometry crossingGeometry) {
        this.catalog = catalog;
        this.crossingGeometry = crossingGeometry;
    }

    List<Constraint> constraints(
            List<ImportedOfficialFeature> features,
            int diameter,
            Set<String> exemptFeatureIds,
            Coordinate start,
            Coordinate end) {
        return applicableConstraints(
                baseConstraints(features, diameter), exemptFeatureIds, start, end);
    }

    List<Constraint> baseConstraints(List<ImportedOfficialFeature> features, int diameter) {
        List<Constraint> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            String type = constraintType(feature);
            if (type == null) {
                continue;
            }
            SpatialConstraintRule rule = catalog.find(type).orElse(null);
            Geometry source = feature.getMetricGeometry();
            if (rule == null || source == null || source.isEmpty()) {
                continue;
            }
            Geometry blocked = null;
            if (rule.isForbidden()) {
                BigDecimal clearance = preparationClearanceM(type, diameter);
                // Equality with the published minimum clearance is legal. Shrinking only by a
                // numerical epsilon keeps the prepared-geometry fast path and excludes a pure
                // tangential touch from the blocked region.
                double blockedClearance = Math.max(
                        0.0, clearance.doubleValue() - CLEARANCE_BOUNDARY_EPSILON_M);
                blocked = source.buffer(blockedClearance, 4);
            }
            result.add(new Constraint(feature.getFeatureId(), type, source, blocked, rule));
        }
        sortConstraints(result);
        return result;
    }

    /** Called only after an input geometry has passed the null/empty checks. */
    BigDecimal preparationClearanceM(String type, int diameter) {
        SpatialConstraintRule rule = catalog.find(type).orElse(null);
        if (rule == null || !rule.isForbidden()) {
            return null;
        }
        return "oks".equals(type)
                ? catalog.existingBuildingClearanceM(diameter)
                : rule.getHorizontalClearanceM();
    }

    void sortConstraints(List<Constraint> constraints) {
        constraints.sort(CONSTRAINT_ORDER);
    }

    ConstraintIndex index(List<Constraint> constraints) {
        return new ConstraintIndex(constraints);
    }

    List<Constraint> applicableConstraints(
            List<Constraint> base,
            Set<String> exemptFeatureIds,
            Coordinate start,
            Coordinate end) {
        Geometry startPoint = geometryFactory.createPoint(start);
        Geometry endPoint = geometryFactory.createPoint(end);
        List<Constraint> result = new ArrayList<>();
        for (Constraint constraint : base) {
            if (exemptFeatureIds.contains(constraint.id)) {
                continue;
            }
            if ("oks".equals(constraint.type)
                    && constraint.rule.isForbidden()
                    && (constraint.blocked.covers(startPoint) || constraint.blocked.covers(endPoint))
                    && !constraint.source.covers(startPoint)
                    && !constraint.source.covers(endPoint)) {
                // A tie-in on an existing network may already be located inside the published
                // building setback. Permit the local approach to that endpoint, but keep the
                // building footprint itself as a hard obstacle. The former implementation
                // omitted the complete OKS constraint and could therefore route through a house.
                result.add(new Constraint(
                        constraint.id,
                        constraint.type,
                        constraint.source,
                        constraint.source,
                        constraint.rule));
                continue;
            }
            result.add(constraint);
        }
        return result;
    }

    /**
     * Возвращает короткий финальный подход от ближайшей границы своего ОКС к точке
     * подключения. Отступ от своего здания на этом финальном отрезке не требуется.
     */
    Optional<NormalEgress> normalEgress(
            List<ImportedOfficialFeature> features,
            int diameter,
            Coordinate connectionPoint) {
        return extendAcrossContainingSocialAreas(
                features,
                diameter,
                normalEgressFromContainingOks(
                        containingOksFeatures(features, diameter, connectionPoint), connectionPoint));
    }

    /** Отбирает только исходные ОКС: выход не использует буферы отступов остальных объектов. */
    private List<ImportedOfficialFeature> containingOksFeatures(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint) {
        SpatialConstraintRule rule = catalog.find("oks").orElse(null);
        if (rule == null) {
            return List.of();
        }
        Geometry point = geometryFactory.createPoint(connectionPoint);
        List<ImportedOfficialFeature> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            if (!"oks".equals(constraintType(feature))) {
                continue;
            }
            Geometry source = feature.getMetricGeometry();
            if (source == null || source.isEmpty()) {
                continue;
            }
            if (rule.isForbidden()) {
                // Сохраняем прежний отказ для неподдерживаемого ДУ, даже если ОКС далеко от точки.
                catalog.existingBuildingClearanceM(diameter);
            }
            if (source.getEnvelopeInternal().contains(connectionPoint) && source.covers(point)) {
                result.add(feature);
            }
        }
        result.sort(Comparator.comparing(ImportedOfficialFeature::getFeatureId));
        return result;
    }

    private Optional<NormalEgress> normalEgressFromContainingOks(
            List<ImportedOfficialFeature> containingOks, Coordinate connectionPoint) {
        Geometry point = geometryFactory.createPoint(connectionPoint);
        ImportedOfficialFeature nearest = null;
        Coordinate boundaryPoint = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (ImportedOfficialFeature feature : containingOks) {
            Geometry boundary = feature.getMetricGeometry().getBoundary();
            if (boundary.isEmpty()) {
                continue;
            }
            Coordinate candidate = DistanceOp.nearestPoints(point, boundary)[1];
            double distance = connectionPoint.distance(candidate);
            if (distance < nearestDistance - EPSILON_M
                    || (Math.abs(distance - nearestDistance) <= EPSILON_M
                            && (nearest == null || feature.getFeatureId().compareTo(nearest.getFeatureId()) < 0))) {
                nearest = feature;
                boundaryPoint = candidate;
                nearestDistance = distance;
            }
        }
        if (nearest == null || boundaryPoint == null || nearestDistance <= EPSILON_M) {
            return Optional.empty();
        }
        double directionX = (boundaryPoint.x - connectionPoint.x) / nearestDistance;
        double directionY = (boundaryPoint.y - connectionPoint.y) / nearestDistance;
        double exitDistance = nearestDistance + NORMAL_EGRESS_MARGIN_M;
        Coordinate exit = new Coordinate(
                connectionPoint.x + directionX * exitDistance,
                connectionPoint.y + directionY * exitDistance);
        return Optional.of(new NormalEgress(nearest.getFeatureId(), new Coordinate(connectionPoint), exit));
    }

    /**
     * Выбирает сторону подхода к точке подключения по направлению к цели. Это не даёт
     * ближайшей границе на противоположной стороне создать круговой обход своего ОКС.
     */
    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features,
            int diameter,
            Coordinate connectionPoint,
            Coordinate target) {
        return normalEgressTowards(
                features, diameter, connectionPoint, target, MAX_ALTERNATIVE_EGRESS_EXTRA_M);
    }

    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features,
            int diameter,
            Coordinate connectionPoint,
            Coordinate target,
            double maximumAlternativeEgressExtraM) {
        List<ImportedOfficialFeature> containingOks = containingOksFeatures(features, diameter, connectionPoint);
        Optional<NormalEgress> nearest = normalEgressFromContainingOks(containingOks, connectionPoint);
        return extendAcrossContainingSocialAreas(
                features,
                diameter,
                normalEgressTowardsContainingOks(
                        containingOks, connectionPoint, target, maximumAlternativeEgressExtraM, nearest));
    }

    private Optional<NormalEgress> normalEgressTowardsContainingOks(
            List<ImportedOfficialFeature> containingOks,
            Coordinate connectionPoint,
            Coordinate target,
            double maximumAlternativeEgressExtraM,
            Optional<NormalEgress> nearest) {
        double targetDistance = connectionPoint.distance(target);
        if (targetDistance <= EPSILON_M || nearest.isEmpty()) {
            return nearest;
        }
        double nearestApproachDistance = connectionPoint.distance(nearest.get().exit());
        double maximumApproachDistance = Math.min(
                nearestApproachDistance + maximumAlternativeEgressExtraM,
                nearestApproachDistance * MAX_ALTERNATIVE_EGRESS_DISTANCE_FACTOR);
        double directionX = (target.x - connectionPoint.x) / targetDistance;
        double directionY = (target.y - connectionPoint.y) / targetDistance;
        NormalEgress best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (ImportedOfficialFeature feature : containingOks) {
            Geometry source = feature.getMetricGeometry();
            double rayLength = Math.max(
                    targetDistance,
                    Math.hypot(
                            source.getEnvelopeInternal().getWidth(),
                            source.getEnvelopeInternal().getHeight()) * 2.0);
            Coordinate rayEnd = new Coordinate(
                    connectionPoint.x + directionX * rayLength,
                    connectionPoint.y + directionY * rayLength);
            Geometry intersections = geometryFactory
                    .createLineString(new Coordinate[] {connectionPoint, rayEnd})
                    .intersection(source.getBoundary());
            for (Coordinate intersection : intersections.getCoordinates()) {
                double projection = (intersection.x - connectionPoint.x) * directionX
                        + (intersection.y - connectionPoint.y) * directionY;
                if (projection <= EPSILON_M
                        || projection + NORMAL_EGRESS_MARGIN_M > maximumApproachDistance
                        || projection >= bestDistance) {
                    continue;
                }
                Coordinate exit = new Coordinate(
                        connectionPoint.x + directionX * (projection + NORMAL_EGRESS_MARGIN_M),
                        connectionPoint.y + directionY * (projection + NORMAL_EGRESS_MARGIN_M));
                if (source.covers(geometryFactory.createPoint(exit))) {
                    continue;
                }
                bestDistance = projection;
                best = new NormalEgress(feature.getFeatureId(), connectionPoint, exit);
            }
        }
        return best == null ? nearest : Optional.of(best);
    }

    /**
     * Returns a bounded set of constructible exits from the OKS. Besides the nearest and
     * target-facing exits, the set contains exits normal to the dominant rectangle sides of the
     * building. This lets the engineering portfolio rebuild a complete terminal branch instead of
     * preserving a locally short exit that forces a long or irregular obstacle detour.
     */
    List<NormalEgress> normalEgressCandidates(
            List<ImportedOfficialFeature> features,
            int diameter,
            Coordinate connectionPoint,
            Coordinate target,
            double maximumAlternativeEgressExtraM) {
        List<ImportedOfficialFeature> containingOks = containingOksFeatures(features, diameter, connectionPoint);
        Optional<NormalEgress> nearest = normalEgressFromContainingOks(containingOks, connectionPoint);
        if (nearest.isEmpty()) {
            return List.of();
        }
        double nearestApproachDistance = connectionPoint.distance(nearest.get().exit());
        double maximumApproachDistance = Math.min(
                nearestApproachDistance + maximumAlternativeEgressExtraM,
                nearestApproachDistance * MAX_ALTERNATIVE_EGRESS_DISTANCE_FACTOR);
        List<NormalEgress> result = new ArrayList<>();
        extendAcrossContainingSocialAreas(features, diameter, nearest)
                .ifPresent(candidate -> addDistinctEgress(result, candidate));
        normalEgressTowardsContainingOks(
                containingOks, connectionPoint, target, maximumAlternativeEgressExtraM, nearest)
                .flatMap(candidate -> extendAcrossContainingSocialAreas(
                        features, diameter, Optional.of(candidate)))
                .ifPresent(candidate -> addDistinctEgress(result, candidate));

        for (ImportedOfficialFeature feature : containingOks) {
            Geometry rectangle = new MinimumDiameter(feature.getMetricGeometry()).getMinimumRectangle();
            Coordinate[] rectangleCoordinates = rectangle.getCoordinates();
            for (int index = 0; index + 1 < rectangleCoordinates.length; index++) {
                double edgeX = rectangleCoordinates[index + 1].x - rectangleCoordinates[index].x;
                double edgeY = rectangleCoordinates[index + 1].y - rectangleCoordinates[index].y;
                double edgeLength = Math.hypot(edgeX, edgeY);
                if (edgeLength <= EPSILON_M) {
                    continue;
                }
                double normalX = -edgeY / edgeLength;
                double normalY = edgeX / edgeLength;
                normalEgressAlongDirection(
                        feature, connectionPoint, normalX, normalY, maximumApproachDistance)
                        .flatMap(candidate -> extendAcrossContainingSocialAreas(
                                features, diameter, Optional.of(candidate)))
                        .ifPresent(candidate -> addDistinctEgress(result, candidate));
                normalEgressAlongDirection(
                        feature, connectionPoint, -normalX, -normalY, maximumApproachDistance)
                        .flatMap(candidate -> extendAcrossContainingSocialAreas(
                                features, diameter, Optional.of(candidate)))
                        .ifPresent(candidate -> addDistinctEgress(result, candidate));
            }
        }
        result.sort(Comparator
                .comparingDouble((NormalEgress candidate) ->
                        connectionPoint.distance(candidate.exit()) + candidate.exit().distance(target))
                .thenComparingDouble(candidate -> candidate.exit().x)
                .thenComparingDouble(candidate -> candidate.exit().y));
        return result;
    }

    private Optional<NormalEgress> normalEgressAlongDirection(
            ImportedOfficialFeature feature,
            Coordinate connectionPoint,
            double directionX,
            double directionY,
            double maximumApproachDistance) {
        Geometry source = feature.getMetricGeometry();
        double rayLength = Math.max(
                maximumApproachDistance + NORMAL_EGRESS_MARGIN_M,
                Math.hypot(
                        source.getEnvelopeInternal().getWidth(),
                        source.getEnvelopeInternal().getHeight()) * 2.0);
        Coordinate rayEnd = new Coordinate(
                connectionPoint.x + directionX * rayLength,
                connectionPoint.y + directionY * rayLength);
        Geometry intersections = geometryFactory
                .createLineString(new Coordinate[] {connectionPoint, rayEnd})
                .intersection(source.getBoundary());
        double bestProjection = Double.POSITIVE_INFINITY;
        for (Coordinate intersection : intersections.getCoordinates()) {
            double projection = (intersection.x - connectionPoint.x) * directionX
                    + (intersection.y - connectionPoint.y) * directionY;
            if (projection > EPSILON_M && projection < bestProjection) {
                bestProjection = projection;
            }
        }
        if (!Double.isFinite(bestProjection)
                || bestProjection + NORMAL_EGRESS_MARGIN_M > maximumApproachDistance) {
            return Optional.empty();
        }
        Coordinate exit = new Coordinate(
                connectionPoint.x + directionX * (bestProjection + NORMAL_EGRESS_MARGIN_M),
                connectionPoint.y + directionY * (bestProjection + NORMAL_EGRESS_MARGIN_M));
        if (source.covers(geometryFactory.createPoint(exit))) {
            return Optional.empty();
        }
        return Optional.of(new NormalEgress(feature.getFeatureId(), connectionPoint, exit));
    }

    /**
     * A demand located on its own social-site parcel may leave that exact parcel through the same
     * straight building-normal terminal leg. Other social sites remain forbidden, and the route
     * after this terminal leg is still checked against the complete catalogue.
     */
    private Optional<NormalEgress> extendAcrossContainingSocialAreas(
            List<ImportedOfficialFeature> features,
            int diameter,
            Optional<NormalEgress> candidate) {
        if (candidate.isEmpty()) {
            return candidate;
        }
        NormalEgress egress = candidate.get();
        Coordinate start = egress.start();
        Coordinate exit = egress.exit();
        double baseDistance = start.distance(exit);
        if (baseDistance <= EPSILON_M) {
            return candidate;
        }
        double directionX = (exit.x - start.x) / baseDistance;
        double directionY = (exit.y - start.y) / baseDistance;
        Geometry point = geometryFactory.createPoint(start);
        List<ImportedOfficialFeature> containingSocialAreas = features.stream()
                .filter(feature -> "social_area".equals(constraintType(feature)))
                .filter(feature -> feature.getMetricGeometry() != null && !feature.getMetricGeometry().isEmpty())
                .filter(feature -> feature.getMetricGeometry().getEnvelopeInternal().contains(start))
                .filter(feature -> feature.getMetricGeometry().covers(point))
                .sorted(Comparator.comparing(ImportedOfficialFeature::getFeatureId))
                .collect(Collectors.toList());
        if (containingSocialAreas.isEmpty()) {
            return candidate;
        }

        double rayLength = baseDistance;
        for (ImportedOfficialFeature feature : containingSocialAreas) {
            org.locationtech.jts.geom.Envelope envelope = feature.getMetricGeometry().getEnvelopeInternal();
            rayLength = Math.max(rayLength, Math.hypot(envelope.getWidth(), envelope.getHeight()) * 2.0);
        }
        Coordinate rayEnd = new Coordinate(
                start.x + directionX * rayLength,
                start.y + directionY * rayLength);
        LineString ray = geometryFactory.createLineString(new Coordinate[] {start, rayEnd});
        double requiredDistance = baseDistance;
        Set<String> socialAreaIds = new HashSet<>(egress.socialAreaIds());
        double socialClearance = preparationClearanceM("social_area", diameter).doubleValue();
        for (ImportedOfficialFeature feature : containingSocialAreas) {
            Geometry intersections = ray.intersection(feature.getMetricGeometry().getBoundary());
            double farthestProjection = Double.NEGATIVE_INFINITY;
            for (Coordinate intersection : intersections.getCoordinates()) {
                double projection = (intersection.x - start.x) * directionX
                        + (intersection.y - start.y) * directionY;
                if (projection > farthestProjection) {
                    farthestProjection = projection;
                }
            }
            if (Double.isFinite(farthestProjection) && farthestProjection > EPSILON_M) {
                requiredDistance = Math.max(
                        requiredDistance,
                        farthestProjection + socialClearance + NORMAL_EGRESS_MARGIN_M);
                socialAreaIds.add(feature.getFeatureId());
            }
        }
        Coordinate extendedExit = new Coordinate(
                start.x + directionX * requiredDistance,
                start.y + directionY * requiredDistance);
        return Optional.of(new NormalEgress(egress.oksId(), start, extendedExit, socialAreaIds));
    }

    private void addDistinctEgress(List<NormalEgress> result, NormalEgress candidate) {
        if (result.stream().noneMatch(existing -> existing.exit().distance(candidate.exit()) <= 0.1)) {
            result.add(candidate);
        }
    }

    List<RouteValidationIssue> validateMandatoryEgress(
            RouteEdge edge,
            LineString route,
            List<ImportedOfficialFeature> features,
            int diameter) {
        List<RouteValidationIssue> issues = new ArrayList<>();
        // Route edges are directed from the existing-network root towards demand. Only the demand
        // endpoint must leave its containing OKS; a tie-in may legitimately lie near another OKS.
        validateEndpointEgress(edge, route, features, diameter, false, issues);
        return issues;
    }

    private void validateEndpointEgress(
            RouteEdge edge,
            LineString route,
            List<ImportedOfficialFeature> features,
            int diameter,
            boolean fromStart,
            List<RouteValidationIssue> issues) {
        int endpointIndex = fromStart ? 0 : route.getNumPoints() - 1;
        int adjacentIndex = fromStart ? 1 : route.getNumPoints() - 2;
        Coordinate endpoint = route.getCoordinateN(endpointIndex);
        Coordinate adjacent = route.getCoordinateN(adjacentIndex);
        NormalEgress expected = normalEgressTowards(features, diameter, endpoint, adjacent).orElse(null);
        if (expected == null) {
            return;
        }
        double requiredLength = endpoint.distance(expected.exit);
        double actualLength = endpoint.distance(adjacent);
        double normalX = expected.exit.x - endpoint.x;
        double normalY = expected.exit.y - endpoint.y;
        double actualX = adjacent.x - endpoint.x;
        double actualY = adjacent.y - endpoint.y;
        double cross = Math.abs(normalX * actualY - normalY * actualX);
        double alignmentTolerance = Math.max(
                2 * EPSILON_M * actualLength, requiredLength * actualLength * 1e-4);
        if (actualLength + 2 * EPSILON_M < requiredLength || cross > alignmentTolerance) {
            issues.add(issue(
                    "OKS_NORMAL_EGRESS_VIOLATION",
                    edge.getId(),
                    "Route must reach the connection point through one boundary of its containing OKS"));
        }
    }

    List<Constraint> routeAvoidanceConstraints(List<LineString> routes) {
        SpatialConstraintRule rule = new SpatialConstraintRule(
                "accepted_route", true, "0.20", null, null, null, null, "1.00");
        List<Constraint> result = new ArrayList<>();
        for (int index = 0; index < routes.size(); index++) {
            LineString route = routes.get(index);
            Geometry blocked = route.buffer(0.20 - CLEARANCE_BOUNDARY_EPSILON_M, 2);
            result.add(new Constraint("accepted-route-" + index, "accepted_route", route, blocked, rule));
        }
        return result;
    }

    List<Constraint> depthAvoidanceConstraints(
            List<ImportedOfficialFeature> features,
            Set<String> featureIds) {
        List<Constraint> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            if (!featureIds.contains(feature.getFeatureId())) continue;
            String type = constraintType(feature);
            SpatialConstraintRule original = type == null ? null : catalog.find(type).orElse(null);
            Geometry source = feature.getMetricGeometry();
            if (original == null || original.isForbidden() || source == null || source.isEmpty()) continue;
            double clearance = Math.max(
                    0.0,
                    original.getHorizontalClearanceM().doubleValue() - CLEARANCE_BOUNDARY_EPSILON_M);
            Geometry blocked = source.buffer(clearance, 4);
            SpatialConstraintRule avoidance = new SpatialConstraintRule(
                    type,
                    true,
                    original.getHorizontalClearanceM().toPlainString(),
                    null,
                    null,
                    null,
                    null,
                    "1.00");
            result.add(new Constraint(feature.getFeatureId(), type, source, blocked, avoidance));
        }
        result.sort(CONSTRAINT_ORDER);
        return result;
    }

    boolean segmentAllowed(Coordinate start, Coordinate end, List<Constraint> constraints) {
        return segmentAllowed(start, end, index(constraints));
    }

    boolean segmentAllowed(Coordinate start, Coordinate end, ConstraintIndex constraints) {
        if (start.distance(end) <= EPSILON_M) {
            return false;
        }
        LineString segment = geometryFactory.createLineString(new Coordinate[] {start, end});
        for (Constraint constraint : constraints.query(segment.getEnvelopeInternal())) {
            if (constraint.rule.isForbidden()) {
                if (intersectsInterior(segment, constraint)) {
                    return false;
                }
                continue;
            }
            if (constraint.rule.getMinimumCrossingAngleDegrees() != null) {
                if (constraint.source.getDimension() == 2
                        && meetsPolygonCrossingAngle(segment, constraint)) {
                    // A straight segment has one direction. If that direction is already legal,
                    // an exact polygon intersection cannot make the crossing angle worse.
                    continue;
                }
                Coordinate crossing = specialCrossingCoordinate(segment, constraint);
                if (crossing != null && !meetsCrossingAngle(segment, constraint, crossing)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean meetsPolygonCrossingAngle(LineString segment, Constraint constraint) {
        Coordinate start = segment.getCoordinateN(0);
        Coordinate end = segment.getCoordinateN(segment.getNumPoints() - 1);
        double routeAngle = Math.atan2(end.y - start.y, end.x - start.x);
        double difference = Math.abs(Math.toDegrees(routeAngle - constraint.sourceAxisAngle)) % 180.0;
        double angle = difference > 90.0 ? 180.0 - difference : difference;
        return angle + 1e-9 >= constraint.rule.getMinimumCrossingAngleDegrees().doubleValue();
    }

    boolean pointInsideForbiddenClearance(Coordinate coordinate, ConstraintIndex constraints) {
        Geometry point = geometryFactory.createPoint(coordinate);
        for (Constraint constraint : constraints.query(point.getEnvelopeInternal())) {
            if (constraint.rule.isForbidden() && constraint.preparedBlocked.covers(point)) {
                return true;
            }
        }
        return false;
    }

    boolean lineAllowed(LineString line, List<Constraint> constraints) {
        return lineAllowed(line, index(constraints));
    }

    boolean lineAllowed(LineString line, ConstraintIndex constraints) {
        for (int index = 0; index < line.getNumPoints() - 1; index++) {
            if (!segmentAllowed(line.getCoordinateN(index), line.getCoordinateN(index + 1), constraints)) {
                return false;
            }
        }
        return true;
    }

    List<RouteSection> sections(LineString route, List<Constraint> constraints) {
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        List<Span> spans = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (constraint.rule.isForbidden() || !hasSpecialCrossing(route, constraint)) {
                continue;
            }
            double extension = constraint.rule.getSpecialExtensionM() == null
                    ? 0.0
                    : constraint.rule.getSpecialExtensionM().doubleValue();
            LineString special = crossingGeometry.specialSegment(route, constraint.source, extension);
            double first = indexed.project(special.getCoordinateN(0));
            double last = indexed.project(special.getCoordinateN(special.getNumPoints() - 1));
            double angle = crossingAngle(route, constraint);
            spans.add(new Span(
                    Math.min(first, last),
                    Math.max(first, last),
                    constraint,
                    angle));
        }
        spans.sort(Comparator.comparingDouble((Span span) -> span.start)
                .thenComparing(span -> span.constraint.type)
                .thenComparing(span -> span.constraint.id));

        List<MergedSpan> merged = mergeSpans(spans);
        List<Double> cuts = new ArrayList<>();
        cuts.add(indexed.getStartIndex());
        cuts.add(indexed.getEndIndex());
        for (MergedSpan span : merged) {
            cuts.add(span.start);
            cuts.add(span.end);
        }
        cuts = cuts.stream().distinct().sorted().collect(Collectors.toList());
        List<RouteSection> result = new ArrayList<>();
        for (int index = 0; index < cuts.size() - 1; index++) {
            double start = cuts.get(index);
            double end = cuts.get(index + 1);
            if (end - start <= EPSILON_M) {
                continue;
            }
            double middle = (start + end) / 2.0;
            MergedSpan active = merged.stream()
                    .filter(span -> middle >= span.start - EPSILON_M && middle <= span.end + EPSILON_M)
                    .findFirst()
                    .orElse(null);
            Geometry extracted = indexed.extractLine(start, end);
            List<RouteCoordinate> coordinates = routeCoordinates(extracted.getCoordinates());
            if (active == null) {
                result.add(new RouteSection("base", null, null, coordinates, extracted.getLength(), null));
            } else {
                String types = active.spans.stream().map(span -> span.constraint.type).distinct()
                        .collect(Collectors.joining("+"));
                String ids = active.spans.stream().map(span -> span.constraint.id).distinct()
                        .collect(Collectors.joining("+"));
                double angle = active.spans.stream().mapToDouble(span -> span.angle).min().orElse(90.0);
                result.add(new RouteSection("special", types, ids, coordinates, extracted.getLength(), angle));
            }
        }
        if (result.isEmpty()) {
            result.add(new RouteSection(
                    "base", null, null, routeCoordinates(route.getCoordinates()), route.getLength(), null));
        }
        return result;
    }

    private List<MergedSpan> mergeSpans(List<Span> spans) {
        List<MergedSpan> result = new ArrayList<>();
        for (Span span : spans) {
            if (result.isEmpty() || span.start > result.get(result.size() - 1).end + EPSILON_M) {
                result.add(new MergedSpan(span));
            } else {
                result.get(result.size() - 1).add(span);
            }
        }
        return result;
    }

    List<RouteValidationIssue> validate(
            RouteEdge edge,
            LineString route,
            List<Constraint> constraints) {
        List<RouteValidationIssue> issues = new ArrayList<>(validateForbidden(edge, route, constraints));
        for (Constraint constraint : constraints) {
            if (constraint.rule.isForbidden()) {
                continue;
            }
            if (!constraint.rule.isForbidden() && hasSpecialCrossing(route, constraint)) {
                if (!meetsCrossingAngle(route, constraint)) {
                    issues.add(issue(
                            "SPECIAL_CROSSING_ANGLE_VIOLATION",
                            edge.getId(),
                            "Route crosses " + constraint.type + " below the minimum angle"));
                }
                boolean represented = edge.getSections().stream()
                        .filter(section -> "special".equals(section.getKind()))
                        .map(RouteSection::getRestrictionId)
                        .filter(value -> value != null)
                        .flatMap(value -> java.util.Arrays.stream(value.split("\\+")))
                        .anyMatch(constraint.id::equals);
                if (!represented) {
                    issues.add(issue(
                            "SPECIAL_CROSSING_SECTION_MISSING",
                            edge.getId(),
                            "Crossing of " + constraint.type + " is not split into a special section"));
                }
            }
        }
        return issues;
    }

    List<RouteValidationIssue> validateForbidden(
            RouteEdge edge,
            LineString route,
            List<Constraint> constraints) {
        List<RouteValidationIssue> issues = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (constraint.rule.isForbidden() && intersectsInterior(route, constraint)) {
                issues.add(issue(
                        "FORBIDDEN_CLEARANCE_VIOLATION",
                        edge.getId(),
                        "Route violates " + constraint.type + " clearance at " + constraint.id));
            }
        }
        return issues;
    }

    List<Constraint> ownOksFootprintConstraint(List<Constraint> constraints, String oksId) {
        return ownTerminalFootprintConstraints(constraints, Set.of(oksId));
    }

    List<Constraint> ownTerminalFootprintConstraints(List<Constraint> constraints, Set<String> featureIds) {
        return constraints.stream()
                .filter(constraint -> featureIds.contains(constraint.id))
                .filter(constraint -> "oks".equals(constraint.type) || "social_area".equals(constraint.type))
                .map(constraint -> new Constraint(
                        constraint.id,
                        constraint.type,
                        constraint.source,
                        constraint.source,
                        constraint.rule))
                .collect(Collectors.toList());
    }

    List<Constraint> ownTerminalFootprintConstraints(List<Constraint> constraints, NormalEgress egress) {
        return constraints.stream()
                .filter(egress::exempts)
                .map(constraint -> new Constraint(
                        constraint.id,
                        constraint.type,
                        constraint.source,
                        constraint.source,
                        constraint.rule))
                .collect(Collectors.toList());
    }

    LineString line(List<Coordinate> coordinates) {
        return geometryFactory.createLineString(coordinates.toArray(new Coordinate[0]));
    }

    boolean isBuildingFeature(ImportedOfficialFeature feature) {
        return "oks".equals(constraintType(feature));
    }

    String constraintType(ImportedOfficialFeature feature) {
        if ("restriction".equals(feature.getObjectType())) {
            String type = feature.getAttributes().path("restriction_type").asText();
            return type.isBlank() ? null : type;
        }
        if ("heat_network".equals(feature.getObjectType())) {
            return "heat_network";
        }
        if ("oks_existing".equals(feature.getObjectType())) {
            return "oks";
        }
        return null;
    }

    private boolean intersectsInterior(LineString line, Constraint constraint) {
        if (!line.getEnvelopeInternal().intersects(constraint.blocked.getEnvelopeInternal())) {
            return false;
        }
        return constraint.intersectsBlocked(line);
    }

    private boolean hasSpecialCrossing(LineString route, Constraint constraint) {
        return specialCrossingCoordinate(route, constraint) != null;
    }

    private Coordinate specialCrossingCoordinate(LineString route, Constraint constraint) {
        if (!route.getEnvelopeInternal().intersects(constraint.source.getEnvelopeInternal())) {
            return null;
        }
        if (constraint.preparedSource != null && !constraint.preparedSource.intersects(route)) {
            return null;
        }
        Geometry intersection = route.intersection(constraint.source);
        if (intersection.isEmpty()) {
            return null;
        }
        if (constraint.source.getDimension() == 2) {
            return intersection.getLength() > EPSILON_M ? intersection.getCoordinate() : null;
        }
        Coordinate routeStart = route.getCoordinateN(0);
        Coordinate routeEnd = route.getCoordinateN(route.getNumPoints() - 1);
        boolean interior = java.util.Arrays.stream(intersection.getCoordinates())
                .anyMatch(coordinate -> coordinate.distance(routeStart) > EPSILON_M
                        && coordinate.distance(routeEnd) > EPSILON_M);
        return interior ? intersection.getCoordinate() : null;
    }

    private boolean meetsCrossingAngle(LineString route, Constraint constraint) {
        Coordinate crossing = specialCrossingCoordinate(route, constraint);
        return crossing == null || meetsCrossingAngle(route, constraint, crossing);
    }

    private boolean meetsCrossingAngle(
            LineString route, Constraint constraint, Coordinate crossing) {
        BigDecimal minimum = constraint.rule.getMinimumCrossingAngleDegrees();
        return minimum == null
                || crossingAngle(route, constraint, crossing) + 1e-9 >= minimum.doubleValue();
    }

    private double crossingAngle(LineString route, Constraint constraint) {
        Coordinate crossing = route.intersection(constraint.source).getCoordinate();
        return crossingAngle(route, constraint, crossing);
    }

    private double crossingAngle(
            LineString route, Constraint constraint, Coordinate crossing) {
        double routeAngle = localAngle(route, crossing);
        double objectAngle = constraint.source.getDimension() == 2
                ? constraint.sourceAxisAngle
                : localAngle(constraint.source, crossing);
        double difference = Math.abs(Math.toDegrees(routeAngle - objectAngle)) % 180.0;
        return difference > 90.0 ? 180.0 - difference : difference;
    }

    private static double polygonAxisAngle(Geometry polygonal) {
        Geometry rectangle = new MinimumDiameter(polygonal).getMinimumRectangle();
        Coordinate[] coordinates = rectangle.getCoordinates();
        LineSegment longest = null;
        for (int index = 0; index < coordinates.length - 1; index++) {
            LineSegment candidate = new LineSegment(coordinates[index], coordinates[index + 1]);
            if (longest == null || candidate.getLength() > longest.getLength()) {
                longest = candidate;
            }
        }
        return longest == null ? 0.0 : Math.atan2(longest.p1.y - longest.p0.y, longest.p1.x - longest.p0.x);
    }

    private double localAngle(Geometry lineal, Coordinate crossing) {
        Coordinate[] coordinates = lineal.getCoordinates();
        double bestDistance = Double.POSITIVE_INFINITY;
        double result = 0.0;
        for (int index = 0; index < coordinates.length - 1; index++) {
            if (coordinates[index].equals2D(coordinates[index + 1])) {
                continue;
            }
            LineSegment segment = new LineSegment(coordinates[index], coordinates[index + 1]);
            double distance = segment.distance(crossing);
            if (distance < bestDistance) {
                bestDistance = distance;
                result = Math.atan2(segment.p1.y - segment.p0.y, segment.p1.x - segment.p0.x);
            }
        }
        return result;
    }

    private List<RouteCoordinate> routeCoordinates(Coordinate[] coordinates) {
        List<RouteCoordinate> result = new ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            RouteCoordinate next = new RouteCoordinate(coordinate.x, coordinate.y);
            if (result.isEmpty()
                    || result.get(result.size() - 1).toCoordinate().distance(next.toCoordinate()) > EPSILON_M) {
                result.add(next);
            }
        }
        return result;
    }

    private RouteValidationIssue issue(String code, String subject, String message) {
        return new RouteValidationIssue(code, subject, message);
    }

    static final class Constraint {
        private final String id;
        private final String type;
        private final Geometry source;
        private final Geometry blocked;
        private final PreparedGeometry preparedBlocked;
        private final PreparedGeometry preparedSource;
        private final double sourceAxisAngle;
        private final long segmentIndexCoordinateReservation;
        private volatile PreparedSegmentIntersection segmentIntersection;
        private volatile boolean segmentIntersectionInitialized;
        private final SpatialConstraintRule rule;

        private Constraint(
                String id,
                String type,
                Geometry source,
                Geometry blocked,
                SpatialConstraintRule rule) {
            this.id = id;
            this.type = type;
            this.source = source;
            this.blocked = blocked;
            this.preparedBlocked = blocked == null ? null : PreparedGeometryFactory.prepare(blocked);
            boolean specialPolygon = !rule.isForbidden()
                    && rule.getMinimumCrossingAngleDegrees() != null
                    && source.getDimension() == 2;
            this.preparedSource = specialPolygon ? PreparedGeometryFactory.prepare(source) : null;
            this.sourceAxisAngle = specialPolygon ? polygonAxisAngle(source) : 0.0;
            this.segmentIndexCoordinateReservation = PreparedSegmentIntersection.additionalCoordinateReservation(blocked);
            this.rule = rule;
        }

        String id() { return id; }
        String type() { return type; }
        Geometry source() { return source; }
        Geometry blocked() { return blocked; }
        PreparedGeometry preparedBlocked() { return preparedBlocked; }
        long segmentIndexCoordinateReservation() { return segmentIndexCoordinateReservation; }
        SpatialConstraintRule rule() { return rule; }

        private boolean intersectsBlocked(LineString line) {
            ensureIntersectionActive();
            if (line.getNumPoints() != 2 || segmentIndexCoordinateReservation == 0) {
                return preparedBlocked.intersects(line);
            }
            if (!segmentIntersectionInitialized) {
                synchronized (this) {
                    if (!segmentIntersectionInitialized) {
                        segmentIntersection = PreparedSegmentIntersection.forReadOnlyConstraint(blocked);
                        // Отказ тоже публикуется: не сканируем validity на каждом запросе.
                        // Исключение/отмена оставляют инициализацию незавершённой для повторной попытки.
                        segmentIntersectionInitialized = true;
                    }
                }
            }
            ensureIntersectionActive();
            PreparedSegmentIntersection prepared = segmentIntersection;
            return prepared == null ? preparedBlocked.intersects(line) : prepared.intersects(line);
        }

        private static void ensureIntersectionActive() {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Constraint intersection cancelled");
            }
        }
    }

    static final class NormalEgress {
        private final String oksId;
        private final Coordinate start;
        private final Coordinate exit;
        private final Set<String> socialAreaIds;

        private NormalEgress(String oksId, Coordinate start, Coordinate exit) {
            this(oksId, start, exit, Set.of());
        }

        private NormalEgress(String oksId, Coordinate start, Coordinate exit, Set<String> socialAreaIds) {
            this.oksId = oksId;
            this.start = new Coordinate(start);
            this.exit = new Coordinate(exit);
            this.socialAreaIds = Set.copyOf(socialAreaIds);
        }

        String oksId() { return oksId; }
        Coordinate start() { return new Coordinate(start); }
        Coordinate exit() { return new Coordinate(exit); }
        Set<String> socialAreaIds() { return socialAreaIds; }
        Set<String> terminalExemptionIds() {
            Set<String> ids = new HashSet<>(socialAreaIds);
            ids.add(oksId);
            return Set.copyOf(ids);
        }
        boolean exempts(Constraint constraint) {
            return ("oks".equals(constraint.type()) && oksId.equals(constraint.id()))
                    || ("social_area".equals(constraint.type()) && socialAreaIds.contains(constraint.id()));
        }
    }

    static final class ConstraintIndex {
        // На малых наборах отбор и упорядочивание кандидатов дороже линейного обхода.
        private static final int LINEAR_SCAN_THRESHOLD = 128;
        private final List<Constraint> all;
        private final STRtree tree;

        private ConstraintIndex(List<Constraint> constraints) {
            all = List.copyOf(constraints);
            if (constraints.size() < LINEAR_SCAN_THRESHOLD) {
                tree = null;
            } else {
                tree = new STRtree();
                for (int ordinal = 0; ordinal < all.size(); ordinal++) {
                    Constraint constraint = all.get(ordinal);
                    Geometry indexed = constraint.rule.isForbidden() ? constraint.blocked : constraint.source;
                    if (indexed != null && !indexed.isEmpty()) {
                        tree.insert(indexed.getEnvelopeInternal(), ordinal);
                    }
                }
                tree.build();
            }
        }

        @SuppressWarnings("unchecked")
        List<Constraint> query(org.locationtech.jts.geom.Envelope envelope) {
            if (tree == null) {
                return all;
            }
            // STRtree обходит элементы в пространственном порядке. Возвращаем исходный порядок,
            // поскольку от него зависят индексы навигационных узлов и разрешение равенств поиска.
            List<Integer> ordinals = (List<Integer>) tree.query(envelope);
            if (ordinals.isEmpty()) {
                return List.of();
            }
            if (ordinals.size() == 1) {
                return List.of(all.get(ordinals.get(0)));
            }
            if (ordinals.size() == 2) {
                int first = ordinals.get(0);
                int second = ordinals.get(1);
                return first < second ? List.of(all.get(first), all.get(second))
                        : List.of(all.get(second), all.get(first));
            }
            ordinals.sort(Integer::compare);
            List<Constraint> result = new ArrayList<>(ordinals.size());
            for (int ordinal : ordinals) {
                result.add(all.get(ordinal));
            }
            return result;
        }
    }

    private static final class Span {
        private final double start;
        private final double end;
        private final Constraint constraint;
        private final double angle;

        private Span(double start, double end, Constraint constraint, double angle) {
            this.start = start;
            this.end = end;
            this.constraint = constraint;
            this.angle = angle;
        }
    }

    private static final class MergedSpan {
        private final double start;
        private double end;
        private final List<Span> spans = new ArrayList<>();

        private MergedSpan(Span first) {
            start = first.start;
            end = first.end;
            spans.add(first);
        }

        private void add(Span next) {
            end = Math.max(end, next.end);
            spans.add(next);
        }
    }
}
