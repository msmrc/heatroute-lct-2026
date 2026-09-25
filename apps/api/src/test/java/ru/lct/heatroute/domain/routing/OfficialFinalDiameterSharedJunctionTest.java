package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Общий узел не блокирует доводку ДУ; льгота стыка не разрешает чужие и дальние пересечения. */
class OfficialFinalDiameterSharedJunctionTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);
    private final GeometryFactory factory = new GeometryFactory();

    @Test
    void finalDiameterRepairWithoutAcceptedSiblingIsAValidControl() throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge before = fixture.branch(500);
        assertThat(before.getLengthM()).isEqualByComparingTo("81.000");
        assertThat(codes(fixture.singleNodes(), List.of(before), fixture.features))
                .containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");

        List<RouteEdge> after = ensure(fixture.singleNodes(), List.of(before), fixture.features);

        assertValid(fixture.singleNodes(), after, fixture.features);
        assertThat(after.get(0)).isNotSameAs(before);
        assertThat(after.get(0).getLengthM()).isEqualByComparingTo("31.408");
    }

    @Test
    void finalDiameterRepairMustSurviveAnEarlierSiblingAtTheSameRoot() throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge before = fixture.branch(500);
        RouteEdge sibling = fixture.sibling(500);
        List<RouteEdge> input = List.of(sibling, before);
        assertThat(codes(fixture.sharedNodes(), input, fixture.features))
                .containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");

        List<RouteEdge> after = ensure(fixture.sharedNodes(), input, fixture.features);

        assertValid(fixture.sharedNodes(), after, fixture.features);
        assertThat(find(after, "branch")).isNotSameAs(before);
        assertThat(find(after, "sibling")).isSameAs(sibling);
    }

    @Test
    void validSharedNodeRetainsBothExactEdgesAndTheirSections() throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge branch = fixture.branch(400);
        RouteEdge sibling = fixture.sibling(400);
        List<RouteEdge> input = List.of(sibling, branch);
        assertValid(fixture.sharedNodes(), input, fixture.features);

        List<RouteEdge> after = ensure(fixture.sharedNodes(), input, fixture.features);

        assertValid(fixture.sharedNodes(), after, fixture.features);
        assertThat(find(after, "branch")).isSameAs(branch);
        assertThat(find(after, "sibling")).isSameAs(sibling);
    }

    @Test
    void branchFirstOrderStillProducesAValidSharedNetwork() throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge branch = fixture.branch(500);
        RouteEdge sibling = fixture.sibling(500);

        List<RouteEdge> after = ensure(fixture.sharedNodes(), List.of(branch, sibling), fixture.features);

        assertValid(fixture.sharedNodes(), after, fixture.features);
        assertThat(find(after, "branch")).isNotSameAs(branch);
        assertThat(find(after, "sibling")).isSameAs(sibling);
    }

    @Test
    void repairWorksWhenAcceptedEdgeEndsAtTheSharedNode() throws Exception {
        assertIncomingAcceptedEdge(500, true);
    }

    @Test
    void validGeometryIsRetainedWhenAcceptedEdgeEndsAtTheSharedNode() throws Exception {
        assertIncomingAcceptedEdge(400, false);
    }

    @Test
    void repairWorksWithLocalMetricCoordinates() throws Exception {
        assertTransformedRepair(new Fixture(0, 0, 0));
    }

    @Test
    void repairWorksAfterRotationAndUtmTranslation() throws Exception {
        assertTransformedRepair(new Fixture(37, 414000, 6173000));
    }

    @Test
    void validRotatedSharedGeometryIsRetainedExactly() throws Exception {
        Fixture fixture = new Fixture(-73, 650000, 6500000);
        RouteEdge branch = fixture.branch(400);
        RouteEdge sibling = fixture.sibling(400);
        List<RouteEdge> input = List.of(sibling, branch);
        assertValid(fixture.sharedNodes(), input, fixture.features);

        List<RouteEdge> after = ensure(fixture.sharedNodes(), input, fixture.features);

        assertValid(fixture.sharedNodes(), after, fixture.features);
        assertThat(find(after, "branch")).isSameAs(branch);
        assertThat(find(after, "sibling")).isSameAs(sibling);
    }

    @Test
    void validNetworkIsNotRepairedBecauseOfASiblingsSearchBuffer() throws Exception {
        assertSearchBufferDoesNotChangeValidNetwork(new Fixture(), false, false);
    }

    @Test
    void validNetworkPreservationDoesNotDependOnEdgeOrder() throws Exception {
        assertSearchBufferDoesNotChangeValidNetwork(new Fixture(), false, true);
    }

    @Test
    void validNetworkIsRetainedWithAnIncomingSiblingSearchBuffer() throws Exception {
        assertSearchBufferDoesNotChangeValidNetwork(new Fixture(), true, false);
    }

    @Test
    void searchBufferPreservationWorksAfterRotationAndUtmTranslation() throws Exception {
        assertSearchBufferDoesNotChangeValidNetwork(new Fixture(90, 414000, 6173000), false, false);
    }

    @Test
    void realCrossingAwayFromTheSharedNodeStillRequiresRepair() throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge branch = fixture.branch(400);
        RouteEdge sibling = fixture.edge("sibling", "root", "other", 400, List.of(
                fixture.point(-20, 20), fixture.point(-20, 40),
                fixture.point(19.9, 40), fixture.point(19.9, 0)));
        List<RouteNode> nodes = List.of(fixture.node("root", -20, 20, true),
                fixture.node("demand", -1, 0, false), fixture.node("other", 19.9, 0, false));
        List<RouteEdge> before = List.of(sibling, branch);
        assertThat(codes(nodes, before, fixture.features)).containsExactly("CROSSING_OUTSIDE_COMMON_NODE");

        List<RouteEdge> after = ensure(nodes, before, fixture.features);

        assertValid(nodes, after, fixture.features);
        assertThat(find(after, "branch")).isNotSameAs(branch);
        assertThat(find(after, "sibling")).isSameAs(sibling);
    }

    @Test
    void validNetworkWithoutBuildingEgressIsAlsoPreserved() throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge branch = fixture.edge("branch", "root", "demand", 400, List.of(
                fixture.point(0, 0), fixture.point(20, 0), fixture.point(20, 20), fixture.point(40, 20)));
        RouteEdge sibling = fixture.edge("sibling", "root", "other", 400, List.of(
                fixture.point(0, 0), fixture.point(0, -20), fixture.point(20.1, -20), fixture.point(20.1, 10)));
        List<RouteNode> nodes = List.of(fixture.node("root", 0, 0, true),
                fixture.node("demand", 40, 20, false), fixture.node("other", 20.1, 10, false));
        List<RouteEdge> before = List.of(sibling, branch);
        assertValid(nodes, before, List.of());

        List<RouteEdge> after = ensure(nodes, before, List.of());

        assertValid(nodes, after, List.of());
        assertThat(find(after, "branch")).isSameAs(branch);
        assertThat(find(after, "sibling")).isSameAs(sibling);
    }

    private void assertSearchBufferDoesNotChangeValidNetwork(Fixture fixture,
            boolean incoming, boolean branchFirst) throws Exception {
        RouteEdge branch = fixture.branch(400);
        List<Coordinate> siblingPoints = new ArrayList<>(List.of(
                fixture.point(-20, 20), fixture.point(-20, 40),
                fixture.point(20.1, 40), fixture.point(20.1, 0)));
        if (incoming) java.util.Collections.reverse(siblingPoints);
        RouteEdge sibling = fixture.edge("sibling", incoming ? "other" : "root",
                incoming ? "root" : "other", 400, siblingPoints);
        List<RouteNode> nodes = incoming
                ? List.of(fixture.node("other", 20.1, 0, true),
                        new RouteNode("root", "new_branch_chamber",
                                new RouteCoordinate(fixture.point(-20, 20).x, fixture.point(-20, 20).y),
                                true, false, 0, null), fixture.node("demand", -1, 0, false))
                : List.of(fixture.node("root", -20, 20, true),
                        fixture.node("demand", -1, 0, false), fixture.node("other", 20.1, 0, false));
        List<RouteEdge> before = branchFirst ? List.of(branch, sibling) : List.of(sibling, branch);
        assertValid(nodes, before, fixture.features);
        assertThat(line(branch).intersection(line(sibling)).getDimension()).isZero();
        var byId = nodes.stream().collect(Collectors.toMap(RouteNode::getId, node -> node));
        var environment = router.prepare(fixture.features);
        var egress = environment.normalEgressTowards(400, fixture.point(-1, 0),
                fixture.point(10, 0), RouteTraversal.REVERSED).orElseThrow();
        assertThat(router.terminalRouteAllowed(branch.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate).collect(Collectors.toList()),
                400, environment, java.util.Set.of(), router.avoidanceFor(branch, List.of(sibling), byId), egress))
                .as("search buffer still blocks this candidate; it is not a final network violation").isFalse();

        List<RouteEdge> after = ensure(nodes, before, fixture.features);

        assertValid(nodes, after, fixture.features);
        assertThat(find(after, "branch")).as("PRESERVE_VALID must not replace an independently valid branch")
                .isSameAs(branch);
        assertThat(find(after, "sibling")).isSameAs(sibling);
    }

    @Test
    void coincidentForeignEndpointWithDifferentNodeIdDoesNotEnableRepair() throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge branch = fixture.branch(500);
        RouteEdge foreign = fixture.edge("sibling", "foreign-root", "other", 500,
                List.of(fixture.point(-20, 20), fixture.point(-20, 40)));
        List<RouteNode> nodes = new ArrayList<>(fixture.sharedNodes());
        nodes.add(fixture.node("foreign-root", -20, 20, true));
        List<RouteEdge> input = List.of(foreign, branch);
        assertThat(codes(nodes, input, fixture.features))
                .contains("CROSSING_OUTSIDE_COMMON_NODE", "FORBIDDEN_CLEARANCE_VIOLATION");

        List<RouteEdge> after = ensure(nodes, input, fixture.features);

        // Другой ID в тех же XY не является разрешённым присоединением к существующему узлу.
        assertThat(find(after, "branch")).isSameAs(branch);
        assertThat(find(after, "sibling")).isSameAs(foreign);
        assertThat(codes(nodes, after, fixture.features))
                .contains("CROSSING_OUTSIDE_COMMON_NODE", "FORBIDDEN_CLEARANCE_VIOLATION");
    }

    @Test
    void incidentEdgeStillBlocksACrossingFarFromTheSharedNode() throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge before = fixture.branch(500);
        RouteEdge incident = fixture.edge("sibling", "root", "other", 500, List.of(
                fixture.point(-20, 20), fixture.point(-40, 20), fixture.point(-40, -20),
                fixture.point(-12, -20), fixture.point(-12, 15)));
        List<RouteNode> nodes = new ArrayList<>(fixture.singleNodes());
        nodes.add(fixture.node("other", -12, 15, false));
        assertRemoteCollisionRemainsBlocked(fixture, nodes, before, incident, 0);
    }

    @Test
    void incidentEdgeStillBlocksAnOverlapFarFromTheSharedNode() throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge before = fixture.edge("branch", "root", "demand", 500, List.of(
                fixture.point(-20, 0), fixture.point(-20, 20), fixture.point(20, 20),
                fixture.point(20, 0), fixture.point(10, 0), fixture.point(-1, 0)));
        RouteEdge incident = fixture.edge("sibling", "root", "other", 500, List.of(
                fixture.point(-20, 0), fixture.point(-20, -20), fixture.point(-12, -20),
                fixture.point(-12, 0), fixture.point(-15, 0)));
        List<RouteNode> nodes = List.of(fixture.node("root", -20, 0, true),
                fixture.node("demand", -1, 0, false), fixture.node("other", -15, 0, false));
        assertRemoteCollisionRemainsBlocked(fixture, nodes, before, incident, 1);
    }

    private void assertIncomingAcceptedEdge(int diameter, boolean repairRequired) throws Exception {
        Fixture fixture = new Fixture();
        RouteEdge branch = fixture.branch(diameter);
        RouteEdge incoming = fixture.edge("sibling", "other", "root", diameter,
                List.of(fixture.point(-20, 40), fixture.point(-20, 20)));
        Coordinate junction = fixture.point(-20, 20);
        List<RouteNode> nodes = List.of(fixture.node("other", -20, 40, true),
                new RouteNode("root", "new_branch_chamber", new RouteCoordinate(junction.x, junction.y),
                        true, false, 0, null), fixture.node("demand", -1, 0, false));
        List<RouteEdge> input = List.of(incoming, branch);
        if (repairRequired) {
            assertThat(codes(nodes, input, fixture.features)).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        } else {
            assertValid(nodes, input, fixture.features);
        }

        List<RouteEdge> after = ensure(nodes, input, fixture.features);

        assertValid(nodes, after, fixture.features);
        assertThat(find(after, "sibling")).isSameAs(incoming);
        if (repairRequired) assertThat(find(after, "branch")).isNotSameAs(branch);
        else assertThat(find(after, "branch")).isSameAs(branch);
    }

    private void assertTransformedRepair(Fixture fixture) throws Exception {
        RouteEdge branch = fixture.branch(500);
        RouteEdge sibling = fixture.sibling(500);
        assertThat(codes(fixture.sharedNodes(), List.of(sibling, branch), fixture.features))
                .containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        // Отдельный контроль доказывает, что поворот/округление не сделали сам ремонт невозможным.
        assertValid(fixture.singleNodes(), ensure(fixture.singleNodes(), List.of(branch), fixture.features),
                fixture.features);

        List<RouteEdge> after = ensure(fixture.sharedNodes(), List.of(sibling, branch), fixture.features);

        assertValid(fixture.sharedNodes(), after, fixture.features);
        assertThat(find(after, "branch")).isNotSameAs(branch);
        assertThat(find(after, "sibling")).isSameAs(sibling);
    }

    private void assertRemoteCollisionRemainsBlocked(Fixture fixture, List<RouteNode> nodes,
            RouteEdge before, RouteEdge incident, int intersectionDimension) throws Exception {
        List<RouteEdge> input = List.of(incident, before);
        assertThat(codes(nodes, input, fixture.features)).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        List<RouteNode> branchNodes = nodes.stream().filter(node -> !"other".equals(node.getId()))
                .collect(Collectors.toList());
        List<RouteEdge> unguarded = ensure(branchNodes, List.of(before), fixture.features);
        assertValid(branchNodes, unguarded, fixture.features);
        RouteEdge control = find(unguarded, "branch");
        assertThat(line(control).intersection(line(incident)).getDimension()).isEqualTo(intersectionDimension);
        assertThat(codes(nodes, List.of(incident, control), fixture.features))
                .contains("CROSSING_OUTSIDE_COMMON_NODE");

        List<RouteEdge> after = ensure(nodes, input, fixture.features);

        assertThat(find(after, "sibling")).isSameAs(incident);
        RouteEdge repaired = find(after, "branch");
        if (repaired == before) {
            // Внутренняя процедура вправе оставить отклоняемый черновик при отсутствии ремонта.
            assertThat(codes(nodes, after, fixture.features)).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        } else {
            // Удаление всего incident-edge из препятствий выдало бы пересекающийся control.
            assertValid(nodes, after, fixture.features);
        }
    }

    private LineString line(RouteEdge edge) {
        return rules.line(edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                .collect(Collectors.toList()));
    }

    private List<String> codes(List<RouteNode> nodes, List<RouteEdge> edges,
            List<ImportedOfficialFeature> features) {
        return validator.validate(nodes, edges, features).stream()
                .map(RouteValidationIssue::getCode).collect(Collectors.toList());
    }

    private void assertValid(List<RouteNode> nodes, List<RouteEdge> edges,
            List<ImportedOfficialFeature> features) {
        assertThat(codes(nodes, edges, features)).as("independent geometry/tree/normal validator").isEmpty();
        for (RouteEdge edge : edges) {
            assertThat(edge.getSections().stream().map(RouteSection::getLengthM)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(edge.getLengthM());
        }
    }

    private RouteEdge find(List<RouteEdge> edges, String id) {
        return edges.stream().filter(edge -> id.equals(edge.getId())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<RouteEdge> ensure(List<RouteNode> nodes, List<RouteEdge> edges,
            List<ImportedOfficialFeature> features) throws Exception {
        Method method = OfficialRoutePlanner.class.getDeclaredMethod("ensureMandatoryEgress",
                List.class, List.class, List.class, OfficialRoutingEnvironment.class, TerminalApproachPolicy.class);
        method.setAccessible(true);
        try {
            List<RouteEdge> result = (List<RouteEdge>) method.invoke(new OfficialDatasetRoutingTest().planner(),
                    nodes, edges, features, router.prepare(features), TerminalApproachPolicy.PRESERVE_VALID);
            assertThat(result).hasSameSizeAs(edges);
            return result;
        } catch (InvocationTargetException exception) {
            throw new AssertionError("ensureMandatoryEgress threw", exception.getCause());
        }
    }

    private final class Fixture {
        private final double rotation;
        private final double offsetX;
        private final double offsetY;
        private final List<ImportedOfficialFeature> features;

        Fixture() {
            this(0, 500000, 6170000);
        }

        Fixture(double degrees, double offsetX, double offsetY) {
            this.rotation = Math.toRadians(degrees);
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.features = List.of(rectangle("own", -2, -10, 0, 10), rectangle("foreign", -8, 26, 8, 30));
        }

        Coordinate point(double x, double y) {
            return new Coordinate(offsetX + x * Math.cos(rotation) - y * Math.sin(rotation),
                    offsetY + x * Math.sin(rotation) + y * Math.cos(rotation));
        }

        RouteEdge branch(int diameter) {
            return edge("branch", "root", "demand", diameter,
                    List.of(point(-20, 20), point(20, 20), point(20, 0), point(10, 0), point(-1, 0)));
        }

        RouteEdge sibling(int diameter) {
            return edge("sibling", "root", "other", diameter, List.of(point(-20, 20), point(-20, 40)));
        }

        RouteEdge edge(String id, String upstream, String downstream, int diameter, List<Coordinate> points) {
            List<RouteCoordinate> coordinates = points.stream().map(point -> new RouteCoordinate(point.x, point.y))
                    .collect(Collectors.toList());
            LineString line = rules.line(coordinates.stream().map(RouteCoordinate::toCoordinate)
                    .collect(Collectors.toList()));
            return new RouteEdge(id, upstream, downstream, line.getLength(), coordinates,
                    rules.sections(line, rules.baseConstraints(features, diameter)), BigDecimal.ONE, diameter);
        }

        List<RouteNode> singleNodes() {
            return List.of(node("root", -20, 20, true), node("demand", -1, 0, false));
        }

        List<RouteNode> sharedNodes() {
            List<RouteNode> nodes = new ArrayList<>(singleNodes());
            nodes.add(node("other", -20, 40, false));
            return nodes;
        }

        RouteNode node(String id, double x, double y, boolean root) {
            Coordinate point = point(x, y);
            return new RouteNode(id, root ? "existing_chamber_tie_in" : "demand_connection",
                    new RouteCoordinate(point.x, point.y), root, root, root ? 2 : 0, null);
        }

        ImportedOfficialFeature rectangle(String id, double x1, double y1, double x2, double y2) {
            return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                    .put("restriction_type", "oks"), factory.createPolygon(new Coordinate[] {
                            point(x1, y1), point(x2, y1), point(x2, y2), point(x1, y2), point(x1, y1)}));
        }
    }
}
