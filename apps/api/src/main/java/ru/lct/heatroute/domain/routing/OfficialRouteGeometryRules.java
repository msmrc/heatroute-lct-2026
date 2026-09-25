package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.constraints.PreparedRoadCrossings;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialRouteGeometryRules {
    static final double EPSILON_M = 0.01;
    static final double NORMAL_EGRESS_MARGIN_M = 0.25;
    private static final double CLEARANCE_BOUNDARY_EPSILON_M = 1e-6;
    private static final double ROUTE_AVOIDANCE_BUFFER_M = 0.20 - CLEARANCE_BOUNDARY_EPSILON_M;
    private static final Comparator<Constraint> CONSTRAINT_ORDER = Comparator
            .comparing((Constraint item) -> item.type)
            .thenComparing(item -> item.id);

    private final OfficialConstraintCatalog catalog;
    private final OfficialCrossingGeometry crossingGeometry;
    private final OfficialAxisClearance axisClearance;
    private final RoadCrossingClearance roadCrossings = new RoadCrossingClearance();
    private final BuildingWallNormals wallNormals = new BuildingWallNormals();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public OfficialRouteGeometryRules(
            OfficialConstraintCatalog catalog,
            OfficialCrossingGeometry crossingGeometry) {
        this.catalog = catalog;
        this.crossingGeometry = crossingGeometry;
        this.axisClearance = new OfficialAxisClearance(new OfficialPipeCatalog(), catalog);
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
            BigDecimal clearance = preparationClearanceM(type, diameter);
            if (clearance != null) {
                // Equality with the published minimum clearance is legal. Shrinking only by a
                // numerical epsilon keeps the prepared-geometry fast path and excludes a pure
                // tangential touch from the blocked region.
                double blockedClearance = Math.max(
                        0.0, clearance.doubleValue() - CLEARANCE_BOUNDARY_EPSILON_M);
                blocked = source.buffer(blockedClearance, 4);
            }
            result.add(new Constraint(feature.getFeatureId(), type, source, blocked, rule,
                    clearance == null ? 0 : clearance.doubleValue()));
        }
        sortConstraints(result);
        return result;
    }

    /** Called only after an input geometry has passed the null/empty checks. */
    BigDecimal preparationClearanceM(String type, int diameter) {
        SpatialConstraintRule rule = catalog.find(type).orElse(null);
        if (rule == null || (!rule.isForbidden() && !RoadCrossingClearance.supports(type))) {
            return null;
        }
        return axisClearance.axisClearanceM(type, diameter, null);
    }

    /** Пространственная подготовка не предполагает неизменность пользовательских реализаций правил. */
    boolean hasStandardPreparationRules() {
        return getClass() == OfficialRouteGeometryRules.class
                && catalog != null && catalog.getClass() == OfficialConstraintCatalog.class
                && crossingGeometry != null && crossingGeometry.getClass() == OfficialCrossingGeometry.class;
    }

    boolean hasConstraintRule(String type) {
        return catalog.find(type).isPresent();
    }

    void sortConstraints(List<Constraint> constraints) {
        constraints.sort(CONSTRAINT_ORDER);
    }

    ConstraintIndex index(List<Constraint> constraints) {
        return new ConstraintIndex(constraints, RouteTraversal.AS_GIVEN);
    }

    /** Направление относится к road/tram-проверкам запроса, а не к общим исходным ограничениям. */
    ConstraintIndex index(List<Constraint> constraints, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        return traversal == RouteTraversal.AS_GIVEN ? index(constraints) : new ConstraintIndex(constraints, traversal);
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

    /** Ближайший допустимый прямой выход по нормали с полным наружным отступом до поворота. */
    Optional<NormalEgress> normalEgress(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint) {
        return nearestLegalNormalEgresses(features, diameter, connectionPoint, RouteTraversal.AS_GIVEN).stream()
                .map(egress -> withNavigationMargin(egress, features, diameter, RouteTraversal.AS_GIVEN)).findFirst();
    }

    /** Геометрия нормали остаётся наружной; REVERSED проверяет физический ввод к подключению. */
    Optional<NormalEgress> normalEgress(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return normalEgress(features, diameter, connectionPoint);
        return nearestLegalNormalEgresses(features, diameter, connectionPoint, traversal).stream()
                .map(egress -> withNavigationMargin(egress, features, diameter, traversal)).findFirst();
    }

    /** Цель разрешает только равенство расстояний до стен, не подменяя нормаль лучом на камеру. */
    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint, Coordinate target) {
        return normalEgressCandidates(features, diameter, connectionPoint, target, 0).stream().findFirst();
    }

    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return normalEgressTowards(features, diameter, connectionPoint, target);
        return normalEgressCandidates(features, diameter, connectionPoint, target, 0, traversal).stream().findFirst();
    }

    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, double maximumAlternativeEgressExtraM) {
        return normalEgressTowards(features, diameter, connectionPoint, target);
    }

    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, double maximumAlternativeEgressExtraM, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return normalEgressTowards(
                features, diameter, connectionPoint, target, maximumAlternativeEgressExtraM);
        return normalEgressTowards(features, diameter, connectionPoint, target, traversal);
    }

    /** Дальняя стена доступна только если все более близкие полные вводы перекрыты препятствиями. */
    List<NormalEgress> normalEgressCandidates(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, double maximumAlternativeEgressExtraM) {
        return directedNormalEgressCandidates(features, diameter, connectionPoint, target, RouteTraversal.AS_GIVEN);
    }

    List<NormalEgress> normalEgressCandidates(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, double maximumAlternativeEgressExtraM, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return normalEgressCandidates(
                features, diameter, connectionPoint, target, maximumAlternativeEgressExtraM);
        return directedNormalEgressCandidates(features, diameter, connectionPoint, target, traversal);
    }

    private List<NormalEgress> directedNormalEgressCandidates(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, RouteTraversal traversal) {
        List<NormalEgress> result = nearestLegalNormalEgresses(features, diameter, connectionPoint, traversal).stream()
                .map(egress -> withNavigationMargin(egress, features, diameter, traversal))
                .collect(Collectors.toCollection(ArrayList::new));
        result.sort(Comparator.comparingDouble((NormalEgress exit) -> exit.exit().distance(target))
                .thenComparing(NormalEgress::oksId)
                .thenComparingDouble(exit -> exit.exit().x).thenComparingDouble(exit -> exit.exit().y));
        return result;
    }

    private List<NormalEgress> nearestLegalNormalEgresses(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint, RouteTraversal traversal) {
        List<ImportedOfficialFeature> containing = containingOksFeatures(features, diameter, connectionPoint);
        if (containing.isEmpty()) return List.of();
        double clearance = axisClearance.axisClearanceM("oks", diameter, null).doubleValue();
        List<WallEgress> candidates = new ArrayList<>();
        for (ImportedOfficialFeature feature : containing) {
            for (BuildingWallNormals.Exit exit : wallNormals.candidates(
                    feature.getMetricGeometry(), connectionPoint, clearance)) {
                candidates.add(new WallEgress(new NormalEgress(feature.getFeatureId(), connectionPoint, exit.point()),
                        exit.wallDistanceM()));
            }
        }
        candidates.sort(Comparator.comparingDouble((WallEgress exit) -> exit.wallDistanceM)
                .thenComparing(exit -> exit.egress.oksId())
                .thenComparingDouble(exit -> exit.egress.exit().x)
                .thenComparingDouble(exit -> exit.egress.exit().y));
        List<NormalEgress> result = new ArrayList<>();
        double nearestLegalDistance = Double.POSITIVE_INFINITY;
        for (WallEgress wall : candidates) {
            if (wall.wallDistanceM > nearestLegalDistance + EPSILON_M) break;
            NormalEgress extended = extendAcrossContainingSocialAreas(
                    features, diameter, Optional.of(wall.egress)).orElseThrow();
            if (!terminalLegAllowed(extended, features, diameter, traversal)) continue;
            nearestLegalDistance = Math.min(nearestLegalDistance, wall.wallDistanceM);
            addDistinctEgress(result, extended);
        }
        return result;
    }

    /** Запас помогает поиску и округлению, но не отменяет допустимую ближайшую стену. */
    private NormalEgress withNavigationMargin(
            NormalEgress required, List<ImportedOfficialFeature> features, int diameter, RouteTraversal traversal) {
        double length = required.start.distance(required.exit);
        double factor = (length + NORMAL_EGRESS_MARGIN_M) / length;
        Coordinate exit = new Coordinate(
                required.start.x + (required.exit.x - required.start.x) * factor,
                required.start.y + (required.exit.y - required.start.y) * factor);
        NormalEgress preferred = new NormalEgress(required.oksId, required.start, exit,
                required.socialAreaIds);
        return terminalLegAllowed(preferred, features, diameter, traversal) ? preferred : required;
    }

    /** Проверяет весь ввод без полигональной аппроксимации чужих запрещённых отступов. */
    private boolean terminalLegAllowed(NormalEgress egress, List<ImportedOfficialFeature> features,
            int diameter, RouteTraversal traversal) {
        LineString leg = line(List.of(egress.start(), egress.exit()));
        for (ImportedOfficialFeature feature : features) {
            String type = constraintType(feature);
            SpatialConstraintRule rule = type == null ? null : catalog.find(type).orElse(null);
            Geometry source = feature.getMetricGeometry();
            if (rule == null || source == null || source.isEmpty()) continue;
            BigDecimal preparedClearance = preparationClearanceM(type, diameter);
            Constraint constraint = new Constraint(feature.getFeatureId(), type, source, null, rule,
                    preparedClearance == null ? 0 : preparedClearance.doubleValue());
            if (egress.exempts(constraint)) {
                if ("oks".equals(type) && !ownApproachAllowed(egress, leg, source, diameter)) return false;
                continue;
            }
            if (rule.isForbidden()) {
                double clearance = preparationClearanceM(type, diameter).doubleValue();
                org.locationtech.jts.geom.Envelope bounds = new org.locationtech.jts.geom.Envelope(
                        source.getEnvelopeInternal());
                bounds.expandBy(clearance);
                if (bounds.intersects(leg.getEnvelopeInternal())
                        && (leg.intersects(source)
                            || leg.distance(source) < clearance - CLEARANCE_BOUNDARY_EPSILON_M)) return false;
            } else if (RoadCrossingClearance.supports(type)) {
                // Подключение фиксировано, наружный порт открыт: входящий ввод является суффиксом.
                double angle = rule.getMinimumCrossingAngleDegrees().doubleValue();
                double extension = rule.getSpecialExtensionM().doubleValue();
                boolean allowed = traversal == RouteTraversal.AS_GIVEN
                        ? roadCrossings.terminalPrefixAllowed(leg, source, constraint.clearanceM, angle, extension)
                        : roadCrossings.terminalSuffixAllowed(
                                (LineString) leg.reverse(), source, constraint.clearanceM, angle, extension);
                if (!allowed) return false;
            } else if (!lineAllowed(leg, index(List.of(constraint)))) {
                return false;
            }
        }
        return true;
    }

    /** Льгота своего ОКС заканчивается на первом полном выходе, а не на конце произвольного луча. */
    private boolean ownApproachAllowed(NormalEgress expected, LineString leg, Geometry footprint, int diameter) {
        Coordinate endpoint = leg.getCoordinateN(0);
        Coordinate adjacent = leg.getCoordinateN(1);
        Coordinate farthestIntersection = endpoint;
        for (Coordinate intersection : leg.intersection(footprint).getCoordinates()) {
            if (intersection.distance(endpoint) > farthestIntersection.distance(endpoint)) {
                farthestIntersection = intersection;
            }
        }
        // Проверяется фактический участок, включая возможное возвращение в другой компонент.
        double roundingAllowance = Math.min(EPSILON_M, endpoint.distance(expected.start));
        if (line(List.of(endpoint, farthestIntersection)).difference(footprint).getLength()
                > roundingAllowance + CLEARANCE_BOUNDARY_EPSILON_M) return false;
        double length = leg.getLength();
        if (length <= CLEARANCE_BOUNDARY_EPSILON_M) return false;
        double clearance = axisClearance.axisClearanceM("oks", diameter, null).doubleValue();
        Coordinate exteriorStart = wallNormals.firstClearancePoint(
                footprint, farthestIntersection, adjacent, clearance);
        if (exteriorStart == null || endpoint.distance(exteriorStart) > length + CLEARANCE_BOUNDARY_EPSILON_M) {
            return false;
        }
        LineString extension = line(List.of(exteriorStart, adjacent));
        return extension.distance(footprint) + CLEARANCE_BOUNDARY_EPSILON_M >= clearance;
    }

    private List<ImportedOfficialFeature> containingOksFeatures(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint) {
        Geometry point = geometryFactory.createPoint(connectionPoint);
        List<ImportedOfficialFeature> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            if (!"oks".equals(constraintType(feature))) continue;
            Geometry source = feature.getMetricGeometry();
            if (source == null || source.isEmpty()) continue;
            catalog.existingBuildingClearanceM(diameter);
            if (source.getEnvelopeInternal().contains(connectionPoint) && source.covers(point)) result.add(feature);
        }
        result.sort(Comparator.comparing(ImportedOfficialFeature::getFeatureId));
        return result;
    }

    private static final class WallEgress {
        private final NormalEgress egress;
        private final double wallDistanceM;

        private WallEgress(NormalEgress egress, double wallDistanceM) {
            this.egress = egress;
            this.wallDistanceM = wallDistanceM;
        }
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
                        farthestProjection + socialClearance);
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
        return validateMandatoryEgress(edge, route, features, diameter,
                route.getCoordinateN(route.getNumPoints() - 1));
    }

    List<RouteValidationIssue> validateMandatoryEgress(
            RouteEdge edge, LineString route, List<ImportedOfficialFeature> features,
            int diameter, Coordinate connectionPoint) {
        List<RouteValidationIssue> issues = new ArrayList<>();
        // Route edges are directed from the existing-network root towards demand. Only the demand
        // endpoint must leave its containing OKS; a tie-in may legitimately lie near another OKS.
        validateEndpointEgress(edge, route, features, diameter, connectionPoint, issues);
        return issues;
    }

    private void validateEndpointEgress(
            RouteEdge edge,
            LineString route,
            List<ImportedOfficialFeature> features,
            int diameter,
            Coordinate connectionPoint,
            List<RouteValidationIssue> issues) {
        Coordinate endpoint = route.getCoordinateN(route.getNumPoints() - 1);
        Coordinate adjacent = route.getCoordinateN(route.getNumPoints() - 2);
        List<ImportedOfficialFeature> containing = containingOksFeatures(features, diameter, connectionPoint);
        if (containing.isEmpty()) return;
        List<NormalEgress> expected = nearestLegalNormalEgresses(
                features, diameter, connectionPoint, RouteTraversal.REVERSED);
        LineString actualLeg = line(List.of(endpoint, adjacent));
        boolean valid = expected.stream().anyMatch(egress -> followsNormal(endpoint, adjacent, egress)
                && containing.stream().filter(feature -> feature.getFeatureId().equals(egress.oksId))
                        .allMatch(feature -> ownApproachAllowed(
                                egress, actualLeg, feature.getMetricGeometry(), diameter)));
        if (!valid) {
            issues.add(issue(
                    "OKS_NORMAL_EGRESS_VIOLATION",
                    edge.getId(),
                    "Route must use a nearest legal wall normal with the complete exterior approach"));
        }
    }

    private boolean followsNormal(Coordinate endpoint, Coordinate adjacent, NormalEgress expected) {
        double requiredLength = expected.start.distance(expected.exit);
        double actualLength = endpoint.distance(adjacent);
        double normalX = expected.exit.x - expected.start.x;
        double normalY = expected.exit.y - expected.start.y;
        double actualX = adjacent.x - endpoint.x;
        double actualY = adjacent.y - endpoint.y;
        double cross = Math.abs(normalX * actualY - normalY * actualX);
        double alignmentTolerance = Math.max(
                2 * EPSILON_M * actualLength, requiredLength * actualLength * 1e-4);
        return actualLength + 2 * EPSILON_M >= requiredLength
                && normalX * actualX + normalY * actualY > 0
                && cross <= alignmentTolerance;
    }

    List<Constraint> routeAvoidanceConstraints(List<LineString> routes) {
        SpatialConstraintRule rule = new SpatialConstraintRule(
                "accepted_route", true, "0.20", null, null, null, null, "1.00");
        List<Constraint> result = new ArrayList<>();
        for (int index = 0; index < routes.size(); index++) {
            LineString route = routes.get(index);
            Geometry blocked = route.buffer(ROUTE_AVOIDANCE_BUFFER_M, 2);
            result.add(new Constraint("accepted-route-" + index, "accepted_route", route, blocked, rule));
        }
        return result;
    }

    /** Совпадения координат недостаточно: исключение принадлежит одному общему ID узла. */
    RouteAvoidance routeAvoidance(RouteEdge candidate, List<RouteEdge> accepted, Map<String, RouteNode> nodes) {
        List<LineString> routes = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();
        List<JoinedRouteContact> contacts = new ArrayList<>();
        boolean shared = false;
        SpatialConstraintRule rule = new SpatialConstraintRule(
                "accepted_route", true, "0.20", null, null, null, null, "1.00");
        for (RouteEdge edge : accepted) {
            Constraint.ensureIntersectionActive();
            if (edge.getCoordinates().size() < 2) continue;
            LineString route = line(edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                    .collect(Collectors.toList()));
            routes.add(route);
            Set<String> common = new HashSet<>(List.of(candidate.getUpstreamNodeId(), candidate.getDownstreamNodeId()));
            common.retainAll(List.of(edge.getUpstreamNodeId(), edge.getDownstreamNodeId()));
            JoinedRouteContact contact = null;
            if (common.size() == 1) {
                String id = common.iterator().next();
                RouteNode node = nodes.get(id);
                if (node != null && endpointMatches(candidate, id, node) && endpointMatches(edge, id, node)) {
                    contact = JoinedRouteContact.create(route, node.getCoordinate().toCoordinate(), ROUTE_AVOIDANCE_BUFFER_M);
                }
            }
            shared |= contact != null;
            contacts.add(contact);
        }
        // Без общих узлов старые методы сохраняют свои hooks и сами готовят обычные препятствия.
        if (shared) for (int index = 0; index < routes.size(); index++) {
            Constraint.ensureIntersectionActive();
            LineString route = routes.get(index);
            constraints.add(new Constraint("accepted-route-" + index, "accepted_route", route,
                    route.buffer(ROUTE_AVOIDANCE_BUFFER_M, 2), rule, 0, contacts.get(index)));
        }
        return new RouteAvoidance(routes, constraints, shared);
    }

    private boolean endpointMatches(RouteEdge edge, String id, RouteNode node) {
        if (edge.getCoordinates().size() < 2) return false;
        RouteCoordinate endpoint = edge.getCoordinates().get(id.equals(edge.getUpstreamNodeId())
                ? 0 : edge.getCoordinates().size() - 1);
        return endpoint.toCoordinate().equals2D(node.getCoordinate().toCoordinate());
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
        return segmentAllowed(start, end, constraints, false);
    }

    private boolean segmentAllowed(Coordinate start, Coordinate end, ConstraintIndex constraints,
            boolean joinedContactsChecked) {
        if (start.distance(end) <= EPSILON_M) {
            return false;
        }
        LineString segment = geometryFactory.createLineString(new Coordinate[] {start, end});
        LineString roadSegment = null;
        for (Constraint constraint : constraints.query(segment.getEnvelopeInternal())) {
            if (constraint.rule.isForbidden()) {
                if (joinedContactsChecked && constraint.joinedContact != null) continue;
                if (intersectsInterior(segment, constraint)) {
                    return false;
                }
                continue;
            }
            if (RoadCrossingClearance.supports(constraint.type)) {
                if (roadSegment == null) roadSegment = constraints.traversal() == RouteTraversal.AS_GIVEN
                        ? segment : (LineString) segment.reverse();
                if (!constraint.roadSegmentAllowed(roadSegment, roadCrossings)) return false;
                continue;
            }
            if (constraint.rule.getMinimumCrossingAngleDegrees() != null
                    && hasSpecialCrossing(segment, constraint)
                    && !meetsCrossingAngle(segment, constraint)) {
                return false;
            }
        }
        return true;
    }

    boolean pointInsideForbiddenClearance(Coordinate coordinate, ConstraintIndex constraints) {
        org.locationtech.jts.geom.Point point = geometryFactory.createPoint(coordinate);
        for (Constraint constraint : constraints.query(point.getEnvelopeInternal())) {
            if (constraint.rule.isForbidden() && constraint.preparedBlocked.covers(point)) {
                if (constraint.joinedContact != null && constraint.joinedContact.permitsPoint(point)) continue;
                return true;
            }
        }
        return false;
    }

    boolean lineAllowed(LineString line, List<Constraint> constraints) {
        return lineAllowed(line, index(constraints));
    }

    boolean lineAllowed(LineString line, ConstraintIndex constraints) {
        return provisionalSegmentsAllowed(line, constraints) && completeRoadCrossingsAllowed(line, constraints);
    }

    /** Стык должен оставаться концом всей трассы, а не только временно выделенной части ввода. */
    boolean joinedContactsAllowed(LineString line, ConstraintIndex constraints) {
        if (!constraints.hasJoinedContacts) return true;
        for (Constraint constraint : constraints.query(line.getEnvelopeInternal())) {
            if (constraint.joinedContact != null && constraint.intersectsBlocked(line)) return false;
        }
        return true;
    }

    /** Только локальная видимость; не допускает готовый маршрут без полной проверки special. */
    boolean provisionalSegmentsAllowed(LineString line, ConstraintIndex constraints) {
        boolean joinedContactsChecked = false;
        if (constraints.hasJoinedContacts) {
            for (Constraint constraint : constraints.query(line.getEnvelopeInternal())) {
                if (constraint.joinedContact != null) {
                    if (constraint.intersectsBlocked(line)) return false;
                    joinedContactsChecked = true;
                }
            }
        }
        for (int index = 0; index < line.getNumPoints() - 1; index++) {
            boolean allowed = joinedContactsChecked
                    ? segmentAllowed(line.getCoordinateN(index), line.getCoordinateN(index + 1), constraints, true)
                    : segmentAllowed(line.getCoordinateN(index), line.getCoordinateN(index + 1), constraints);
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** Проверяет road/tram на всей физической полилинии, включая обязательный ввод. */
    boolean completeRoadCrossingsAllowed(LineString line, ConstraintIndex constraints) {
        LineString physicalLine = null;
        for (Constraint constraint : constraints.query(line.getEnvelopeInternal())) {
            if (!constraint.rule.isForbidden() && RoadCrossingClearance.supports(constraint.type)) {
                if (physicalLine == null) physicalLine = constraints.traversal() == RouteTraversal.AS_GIVEN
                        ? line : (LineString) line.reverse();
                if (!roadAssessment(physicalLine, constraint).isAllowed()) return false;
            }
        }
        return true;
    }

    /** Отдельный поворот графа не может прервать обязательный прямой road/tram special. */
    boolean specialTurnAllowed(Coordinate before, Coordinate at, Coordinate after, ConstraintIndex constraints) {
        for (Constraint constraint : constraints.roads) {
            double extension = constraint.rule.getSpecialExtensionM().doubleValue();
            org.locationtech.jts.geom.Envelope bounds = constraint.source.getEnvelopeInternal();
            if (at.x < bounds.getMinX() - extension || at.x > bounds.getMaxX() + extension
                    || at.y < bounds.getMinY() - extension || at.y > bounds.getMaxY() + extension) continue;
            if (!roadCrossings.turnAllowed(before, at, after, constraint.source, extension)) return false;
        }
        return true;
    }

    private RoadCrossingClearance.Assessment roadAssessment(LineString route, Constraint constraint) {
        return roadCrossings.assess(route, constraint.source, constraint.clearanceM,
                constraint.rule.getMinimumCrossingAngleDegrees().doubleValue(),
                constraint.rule.getSpecialExtensionM().doubleValue());
    }

    /** Возвращает порядок построения, но интервалы и углы берёт из физического направления ребра. */
    List<RouteSection> sections(LineString route, List<Constraint> constraints, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return sections(route, constraints);
        List<RouteSection> physicalSections = sections((LineString) route.reverse(), constraints);
        List<RouteSection> result = new ArrayList<>(physicalSections.size());
        for (int index = physicalSections.size() - 1; index >= 0; index--) {
            RouteSection section = physicalSections.get(index);
            List<RouteCoordinate> coordinates = new ArrayList<>(section.getCoordinates());
            Collections.reverse(coordinates);
            result.add(new RouteSection(section.getKind(), section.getRestrictionType(), section.getRestrictionId(),
                    coordinates, section.getLengthM().doubleValue(), section.getCrossingAngleDegrees() == null
                            ? null : section.getCrossingAngleDegrees().doubleValue()));
        }
        return result;
    }

    List<RouteSection> sections(LineString route, List<Constraint> constraints) {
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        List<Span> spans = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (!constraint.rule.isForbidden() && RoadCrossingClearance.supports(constraint.type)) {
                for (RoadCrossingClearance.Interval interval : roadAssessment(route, constraint).getIntervals()) {
                    spans.add(new Span(interval.getStartM(), interval.getEndM(), constraint, interval.getAngleDegrees()));
                }
                continue;
            }
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

        List<Double> cuts = new ArrayList<>();
        cuts.add(indexed.getStartIndex());
        cuts.add(indexed.getEndIndex());
        // §4: общая часть пересечений — отдельная секция, не весь union с общими атрибутами.
        for (Span span : spans) {
            cuts.add(span.start);
            cuts.add(span.end);
        }
        cuts = cuts.stream().distinct().sorted().collect(Collectors.toList());
        List<RouteSection> result = new ArrayList<>();
        for (int index = 0; index < cuts.size() - 1; index++) {
            double start = cuts.get(index);
            double end = cuts.get(index + 1);
            if (end - start <= CLEARANCE_BOUNDARY_EPSILON_M) {
                continue;
            }
            double middle = (start + end) / 2.0;
            List<Span> active = spans.stream()
                    .filter(span -> middle >= span.start && middle <= span.end)
                    .collect(Collectors.toList());
            Geometry extracted = indexed.extractLine(start, end);
            List<RouteCoordinate> coordinates = routeCoordinates(extracted.getCoordinates());
            if (active.isEmpty()) {
                result.add(new RouteSection("base", null, null, coordinates, extracted.getLength(), null));
            } else {
                String types = active.stream().map(span -> span.constraint.type).distinct()
                        .collect(Collectors.joining("+"));
                String ids = active.stream().map(span -> span.constraint.id).distinct()
                        .collect(Collectors.joining("+"));
                double angle = active.stream().mapToDouble(span -> span.angle).min().orElse(90.0);
                result.add(new RouteSection("special", types, ids, coordinates, extracted.getLength(), angle));
            }
        }
        if (result.isEmpty()) {
            result.add(new RouteSection(
                    "base", null, null, routeCoordinates(route.getCoordinates()), route.getLength(), null));
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
            if (RoadCrossingClearance.supports(constraint.type)) {
                RoadCrossingClearance.Assessment assessment = roadAssessment(route, constraint);
                if (!assessment.isAllowed()) {
                    issues.add(issue(assessment.getFailureCode(), edge.getId(),
                            "Route violates " + constraint.type + " crossing/clearance at " + constraint.id));
                }
                if (!assessment.getIntervals().isEmpty() && edge.getSections().stream()
                        .filter(section -> "special".equals(section.getKind()))
                        .map(RouteSection::getRestrictionId).filter(java.util.Objects::nonNull)
                        .flatMap(value -> java.util.Arrays.stream(value.split("\\+")))
                        .noneMatch(constraint.id::equals)) {
                    issues.add(issue("SPECIAL_CROSSING_SECTION_MISSING", edge.getId(),
                            "Crossing of " + constraint.type + " is not split into a special section"));
                }
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

    /** Льгота ввода действует только на последнем прямом звене, не на остальной трассе. */
    List<RouteValidationIssue> validateOwnTerminalClearance(RouteEdge edge, LineString outside,
            List<Constraint> constraints, NormalEgress egress, int diameter) {
        List<RouteValidationIssue> issues = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (!egress.exempts(constraint)) continue;
            double clearance = "oks".equals(constraint.type)
                    ? axisClearance.axisClearanceM("oks", diameter, null).doubleValue()
                    : preparationClearanceM(constraint.type, diameter).doubleValue();
            // Точное расстояние не пропускает срезание угла полигонального buffer.
            if (outside.distance(constraint.source) < clearance - CLEARANCE_BOUNDARY_EPSILON_M) {
                issues.add(issue("FORBIDDEN_CLEARANCE_VIOLATION", edge.getId(),
                        "Route violates own " + constraint.type + " clearance outside terminal approach at " + constraint.id));
            }
        }
        return issues;
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
        if (!route.getEnvelopeInternal().intersects(constraint.source.getEnvelopeInternal())) {
            return false;
        }
        Geometry intersection = route.intersection(constraint.source);
        if (intersection.isEmpty()) {
            return false;
        }
        if (constraint.source.getDimension() == 2) {
            return intersection.getLength() > EPSILON_M;
        }
        Coordinate routeStart = route.getCoordinateN(0);
        Coordinate routeEnd = route.getCoordinateN(route.getNumPoints() - 1);
        return java.util.Arrays.stream(intersection.getCoordinates())
                .anyMatch(coordinate -> coordinate.distance(routeStart) > EPSILON_M
                        && coordinate.distance(routeEnd) > EPSILON_M);
    }

    private boolean meetsCrossingAngle(LineString route, Constraint constraint) {
        BigDecimal minimum = constraint.rule.getMinimumCrossingAngleDegrees();
        return minimum == null || crossingAngle(route, constraint) + 1e-9 >= minimum.doubleValue();
    }

    private double crossingAngle(LineString route, Constraint constraint) {
        Coordinate crossing = route.intersection(constraint.source).getCoordinate();
        double routeAngle = localAngle(route, crossing);
        double objectAngle = constraint.source.getDimension() == 2
                ? polygonAxisAngle(constraint.source)
                : localAngle(constraint.source, crossing);
        double difference = Math.abs(Math.toDegrees(routeAngle - objectAngle)) % 180.0;
        return difference > 90.0 ? 180.0 - difference : difference;
    }

    private double polygonAxisAngle(Geometry polygonal) {
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
                    || !result.get(result.size() - 1).toCoordinate().equals2D(next.toCoordinate())) {
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
        private final long segmentIndexCoordinateReservation;
        private final long roadCrossingCoordinateReservation;
        private volatile PreparedSegmentIntersection segmentIntersection;
        private volatile boolean segmentIntersectionInitialized;
        private volatile PreparedRoadCrossings roadCrossings;
        private volatile boolean roadCrossingsInitialized;
        private final SpatialConstraintRule rule;
        private final double clearanceM;
        private final JoinedRouteContact joinedContact;

        private Constraint(
                String id,
                String type,
                Geometry source,
                Geometry blocked,
                SpatialConstraintRule rule) {
            this(id, type, source, blocked, rule, 0);
        }

        private Constraint(String id, String type, Geometry source, Geometry blocked,
                SpatialConstraintRule rule, double clearanceM) {
            this(id, type, source, blocked, rule, clearanceM, null);
        }

        private Constraint(String id, String type, Geometry source, Geometry blocked,
                SpatialConstraintRule rule, double clearanceM, JoinedRouteContact joinedContact) {
            this.id = id;
            this.type = type;
            this.source = source;
            this.blocked = blocked;
            this.preparedBlocked = blocked == null ? null : PreparedGeometryFactory.prepare(blocked);
            this.segmentIndexCoordinateReservation = PreparedSegmentIntersection.additionalCoordinateReservation(blocked);
            this.roadCrossingCoordinateReservation = !rule.isForbidden() && RoadCrossingClearance.supports(type)
                    ? PreparedRoadCrossings.additionalCoordinateReservation(source) : 0;
            this.rule = rule;
            this.clearanceM = clearanceM;
            this.joinedContact = joinedContact;
        }

        String id() { return id; }
        String type() { return type; }
        Geometry source() { return source; }
        Geometry blocked() { return blocked; }
        PreparedGeometry preparedBlocked() { return preparedBlocked; }
        long segmentIndexCoordinateReservation() { return segmentIndexCoordinateReservation; }
        long roadCrossingCoordinateReservation() { return roadCrossingCoordinateReservation; }
        SpatialConstraintRule rule() { return rule; }
        double clearanceM() { return clearanceM; }

        /** Индекс принадлежит неизменяемому Constraint одного расчёта; память зарезервирована до аллокации. */
        private boolean roadSegmentAllowed(LineString line, RoadCrossingClearance fallback) {
            ensureIntersectionActive();
            if (roadCrossingCoordinateReservation > 0 && !roadCrossingsInitialized) {
                synchronized (this) {
                    if (!roadCrossingsInitialized) {
                        roadCrossings = PreparedRoadCrossings.forReadOnlyConstraint(source);
                        roadCrossingsInitialized = true;
                    }
                }
            }
            ensureIntersectionActive();
            double angle = rule.getMinimumCrossingAngleDegrees().doubleValue();
            double extension = rule.getSpecialExtensionM().doubleValue();
            return roadCrossings == null ? fallback.segmentAllowed(line, source, clearanceM, angle, extension)
                    : roadCrossings.segmentAllowed(line, clearanceM, angle, extension);
        }

        private boolean intersectsBlocked(LineString line) {
            ensureIntersectionActive();
            if (joinedContact != null) {
                return preparedBlocked.intersects(line) && !joinedContact.permitsContact(line, blocked);
            }
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
        private final RouteTraversal traversal;
        private final boolean hasJoinedContacts;
        // На малых наборах отбор и упорядочивание кандидатов дороже линейного обхода.
        private static final int LINEAR_SCAN_THRESHOLD = 128;
        private final List<Constraint> all;
        private final List<Constraint> roads;
        private final STRtree tree;

        private ConstraintIndex(List<Constraint> constraints, RouteTraversal traversal) {
            this.traversal = Objects.requireNonNull(traversal, "Route traversal is required");
            all = List.copyOf(constraints);
            hasJoinedContacts = all.stream().anyMatch(item -> item.joinedContact != null);
            roads = all.stream().filter(item -> !item.rule.isForbidden()
                    && RoadCrossingClearance.supports(item.type)).collect(Collectors.toList());
            if (constraints.size() < LINEAR_SCAN_THRESHOLD) {
                tree = null;
            } else {
                tree = new STRtree();
                for (int ordinal = 0; ordinal < all.size(); ordinal++) {
                    Constraint constraint = all.get(ordinal);
                    Geometry indexed = constraint.blocked != null ? constraint.blocked : constraint.source;
                    if (indexed != null && !indexed.isEmpty()) {
                        org.locationtech.jts.geom.Envelope bounds = new org.locationtech.jts.geom.Envelope(
                                indexed.getEnvelopeInternal());
                        // Полигональный buffer приближает дуги внутрь. Для точного road-clearance
                        // envelope расширяется по source на полный радиус, в том числе после поворота.
                        if (!constraint.rule.isForbidden() && RoadCrossingClearance.supports(constraint.type)) {
                            org.locationtech.jts.geom.Envelope exact = new org.locationtech.jts.geom.Envelope(
                                    constraint.source.getEnvelopeInternal());
                            exact.expandBy(constraint.clearanceM);
                            bounds.expandToInclude(exact);
                        }
                        tree.insert(bounds, ordinal);
                    }
                }
                tree.build();
            }
        }

        RouteTraversal traversal() { return traversal; }

        boolean hasRoadCrossings() { return !roads.isEmpty(); }

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

}
