package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialRouteGeometryRules {
    static final double EPSILON_M = 0.01;
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
                BigDecimal clearance = "oks".equals(type)
                        ? catalog.existingBuildingClearanceM(diameter)
                        : rule.getHorizontalClearanceM();
                // Equality with the published minimum clearance is legal. Shrinking only by a
                // numerical epsilon keeps the prepared-geometry fast path and excludes a pure
                // tangential touch from the blocked region.
                double blockedClearance = Math.max(
                        0.0, clearance.doubleValue() - CLEARANCE_BOUNDARY_EPSILON_M);
                blocked = source.buffer(blockedClearance, 4);
            }
            result.add(new Constraint(feature.getFeatureId(), type, source, blocked, rule));
        }
        result.sort(CONSTRAINT_ORDER);
        return result;
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
            if (constraint.rule.isForbidden()) {
                // A connection point may be placed on its own OKS boundary in the supplied
                // compatibility dataset. Permit only that endpoint's egress by omitting the
                // containing object from this one route calculation.
                if (constraint.blocked.covers(startPoint) || constraint.blocked.covers(endPoint)) {
                    continue;
                }
            }
            result.add(constraint);
        }
        return result;
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
            if (constraint.rule.getMinimumCrossingAngleDegrees() != null
                    && hasSpecialCrossing(segment, constraint)
                    && !meetsCrossingAngle(segment, constraint)) {
                return false;
            }
        }
        return true;
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

        List<Double> cuts = new ArrayList<>();
        cuts.add(indexed.getStartIndex());
        cuts.add(indexed.getEndIndex());
        for (Span span : spans) {
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
            List<Span> active = spans.stream()
                    .filter(span -> middle >= span.start - EPSILON_M && middle <= span.end + EPSILON_M)
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
        List<RouteValidationIssue> issues = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (constraint.rule.isForbidden() && intersectsInterior(route, constraint)) {
                issues.add(issue(
                        "FORBIDDEN_CLEARANCE_VIOLATION",
                        edge.getId(),
                        "Route violates " + constraint.type + " clearance at " + constraint.id));
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

    LineString line(List<Coordinate> coordinates) {
        return geometryFactory.createLineString(coordinates.toArray(new Coordinate[0]));
    }

    private String constraintType(ImportedOfficialFeature feature) {
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
        return constraint.preparedBlocked.intersects(line);
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
            this.rule = rule;
        }

        String id() { return id; }
        String type() { return type; }
        Geometry source() { return source; }
        Geometry blocked() { return blocked; }
        PreparedGeometry preparedBlocked() { return preparedBlocked; }
        SpatialConstraintRule rule() { return rule; }
    }

    static final class ConstraintIndex {
        private static final int LINEAR_SCAN_THRESHOLD = 256;
        private final List<Constraint> all;
        private final STRtree tree;

        private ConstraintIndex(List<Constraint> constraints) {
            all = List.copyOf(constraints);
            if (constraints.size() < LINEAR_SCAN_THRESHOLD) {
                tree = null;
            } else {
                tree = new STRtree();
                for (Constraint constraint : constraints) {
                    Geometry indexed = constraint.rule.isForbidden() ? constraint.blocked : constraint.source;
                    if (indexed != null && !indexed.isEmpty()) {
                        tree.insert(indexed.getEnvelopeInternal(), constraint);
                    }
                }
                tree.build();
            }
        }

        @SuppressWarnings("unchecked")
        List<Constraint> query(org.locationtech.jts.geom.Envelope envelope) {
            return tree == null ? all : (List<Constraint>) tree.query(envelope);
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
