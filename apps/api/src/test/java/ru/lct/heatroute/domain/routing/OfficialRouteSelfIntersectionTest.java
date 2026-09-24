package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;

class OfficialRouteSelfIntersectionTest {
    private final OfficialRouteValidator structural = new OfficialRouteValidator();
    private final OfficialRouteValidator full = new OfficialRouteValidator(new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));

    @Test
    void rejectsACrossingInsideOneCompressedEdgeWithNoOtherEdgesOrObstacles() {
        assertSelfIntersection(points(0, 0, 10, 0, 10, 10, 0, 10, 0, 5, 15, 5));
    }

    @Test
    void rejectsAdjacentRetracingOfAPositiveLengthIncludingSubCentimetreOverlap() {
        assertSelfIntersection(points(0, 0, 10, 0, 5, 0, 5, 10));
        assertSelfIntersection(points(0, 0, 10, 0, 9.999, 0, 9.999, 10));
    }

    @Test
    void rejectsNonAdjacentOverlapsInEitherDirection() {
        assertSelfIntersection(points(0, 0, 10, 0, 10, 5, 2, 5, 2, 0, 8, 0, 8, -5));
        assertSelfIntersection(points(0, 0, 10, 0, 10, 5, 8, 5, 8, 0, 2, 0, 2, -5));
    }

    @Test
    void rejectsRevisitedVerticesAndAnEndpointTouchingItsOwnInterior() {
        assertSelfIntersection(points(0, 0, 5, 0, 10, 0, 10, 5, 5, 5, 5, 0, 5, -5));
        assertSelfIntersection(points(0, 0, 10, 0, 10, 5, 5, 5, 5, 0));
    }

    @Test
    void rejectsAPositiveLengthClosedRingEvenThoughJtsCallsItSimple() {
        List<RouteCoordinate> ring = points(0, 0, 10, 0, 10, 10, 0, 10, 0, 0);
        assertThat(line(ring).isSimple()).isTrue();
        // Разные графовые IDs не должны скрывать физическую петлю с совпавшими концами.
        assertSelfIntersection(ring);
    }

    @Test
    void aRepeatedOrShortInitialSegmentCannotHideLaterSelfIntersection() {
        assertSelfIntersection(points(0, 0, 0, 0, 10, 0, 10, 10, 0, 10, 0, 5, 15, 5));
        assertSelfIntersection(points(0, 0, 0.005, 0, 10, 0, 10, 10, 0, 10, 0, 5, 15, 5));
    }

    @Test
    void acceptsStraightCollinearIntermediatePointsAndHarmlessConsecutiveDuplicates() {
        assertLegal(points(0, 0, 10, 0));
        assertLegal(points(0, 0, 2, 0, 5, 0, 10, 0));
        assertLegal(points(0, 0, 0, 0, 0.005, 0, 5, 0, 5, 0, 10, 0, 10, 0));
        assertLegal(points(0, 0, 0, 5, 0, 5, 10, 5, 10, 10));
    }

    @Test
    void acceptsAnOpenPolylineThatPassesCloseToItselfWithoutTouching() {
        assertLegal(points(0, 0, 10, 0, 10, 10, 0, 10, 0, 0.001));
        assertLegal(points(0, 0, 10, 0, 10, 10, 0, 10, 0, 0.001, 9.999, 0.001));
    }

    @Test
    void legalBranchesMayMeetAtTheirSharedNodeAndImplicitStraightGeometryStillWorks() {
        RouteNode root = node("root", new RouteCoordinate(0, 0), true);
        RouteNode chamber = new RouteNode("chamber", "new_branch_chamber", new RouteCoordinate(10, 0),
                true, false, 0, null);
        RouteNode left = node("left", new RouteCoordinate(10, 10), false);
        RouteNode right = node("right", new RouteCoordinate(20, 0), false);
        List<RouteNode> nodes = List.of(root, chamber, left, right);
        List<RouteEdge> edges = List.of(
                edge("trunk", "root", "chamber", points(0, 0, 5, 0, 10, 0)),
                edge("left-edge", "chamber", "left", points(10, 0, 10, 5, 10, 10)),
                new RouteEdge("right-edge", "chamber", "right", 10));
        assertThat(structural.validate(nodes, edges)).isEmpty();
        assertThat(full.validate(nodes, edges, List.of())).isEmpty();
    }

    @Test
    void reportsOneStableIssuePerBadEdgeInDeterministicSubjectOrder() {
        List<RouteCoordinate> first = points(0, 0, 10, 0, 5, 0, 5, 10);
        List<RouteCoordinate> second = points(100, 0, 110, 0, 105, 0, 105, 10);
        List<RouteNode> nodes = List.of(node("r1", first.get(0), true), node("d1", first.get(3), false),
                node("r2", second.get(0), true), node("d2", second.get(3), false));
        RouteEdge z = edge("z", "r1", "d1", first);
        RouteEdge a = edge("a", "r2", "d2", second);
        for (List<RouteEdge> edges : List.of(List.of(z, a), List.of(a, z))) {
            List<RouteValidationIssue> issues = full.validate(nodes, edges, List.of());
            assertThat(issues).filteredOn(issue -> "SELF_INTERSECTION".equals(issue.getCode()))
                    .extracting(RouteValidationIssue::getCode)
                    .containsExactly("SELF_INTERSECTION", "SELF_INTERSECTION");
            assertThat(issues).filteredOn(issue -> "SELF_INTERSECTION".equals(issue.getCode()))
                    .extracting(RouteValidationIssue::getSubjectId).containsExactly("a", "z");
        }
    }

    @Test
    void preservesDetectionUnderReversalRotationAndTranslationWithoutMutatingInput() {
        List<RouteCoordinate> original = points(0, 0, 10, 0, 10, 10, 0, 10, 0, 5, 15, 5);
        List<RouteCoordinate> snapshot = new ArrayList<>(original);
        for (double angle : new double[] {0, 0.4, 1.7}) {
            List<RouteCoordinate> transformed = original.stream().map(RouteCoordinate::toCoordinate)
                    .map(p -> new RouteCoordinate(600000 + p.x * Math.cos(angle) - p.y * Math.sin(angle),
                            6000000 + p.x * Math.sin(angle) + p.y * Math.cos(angle)))
                    .collect(Collectors.toList());
            assertSelfIntersection(transformed);
            Collections.reverse(transformed);
            assertSelfIntersection(transformed);
        }
        assertSelfIntersection(original);
        assertThat(original).containsExactlyElementsOf(snapshot);
    }

    private void assertSelfIntersection(List<RouteCoordinate> coordinates) {
        List<RouteNode> nodes = endpoints(coordinates);
        RouteEdge edge = edge("bad-edge", "root", "demand", coordinates);
        for (List<RouteValidationIssue> issues : List.of(structural.validate(nodes, List.of(edge)),
                full.validate(nodes, List.of(edge), List.of()))) {
            assertThat(issues).filteredOn(issue -> "SELF_INTERSECTION".equals(issue.getCode()))
                    .extracting(RouteValidationIssue::getCode).containsExactly("SELF_INTERSECTION");
            assertThat(issues).filteredOn(issue -> "SELF_INTERSECTION".equals(issue.getCode()))
                    .extracting(RouteValidationIssue::getSubjectId).containsExactly("bad-edge");
        }
    }

    private void assertLegal(List<RouteCoordinate> coordinates) {
        List<RouteNode> nodes = endpoints(coordinates);
        RouteEdge edge = edge("legal-edge", "root", "demand", coordinates);
        assertThat(structural.validate(nodes, List.of(edge))).isEmpty();
        assertThat(full.validate(nodes, List.of(edge), List.of())).isEmpty();
    }

    private List<RouteNode> endpoints(List<RouteCoordinate> coordinates) {
        return List.of(node("root", coordinates.get(0), true),
                node("demand", coordinates.get(coordinates.size() - 1), false));
    }

    private RouteNode node(String id, RouteCoordinate point, boolean root) {
        return new RouteNode(id, root ? "tie_in" : "demand_connection", point, root, root, 0, null);
    }

    private RouteEdge edge(String id, String upstream, String downstream, List<RouteCoordinate> coordinates) {
        return new RouteEdge(id, upstream, downstream, line(coordinates).getLength(),
                coordinates, List.of(), BigDecimal.ONE, 50);
    }

    private LineString line(List<RouteCoordinate> coordinates) {
        return new GeometryFactory().createLineString(coordinates.stream()
                .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
    }

    private List<RouteCoordinate> points(double... xy) {
        List<RouteCoordinate> result = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) result.add(new RouteCoordinate(xy[i], xy[i + 1]));
        return result;
    }
}
