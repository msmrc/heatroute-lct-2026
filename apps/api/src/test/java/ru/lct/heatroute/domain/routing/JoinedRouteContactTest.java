package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;

/** Исключение общего узла ограничено прямым локальным контактом, без ослабления препятствий. */
class JoinedRouteContactTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialRoutingEnvironment environment = router.prepare(List.of());

    @Test
    void exactSharedEndpointAllowsBothEdgeOrientations() {
        for (boolean reverseCandidate : List.of(false, true)) {
            for (boolean reverseAccepted : List.of(false, true)) {
                Context context = joined(List.of(p(0, 0), p(0, -10)), List.of(p(0, 0), p(10, 0)),
                        reverseCandidate, reverseAccepted);
                assertThat(context.avoidance.hasSharedJunction()).isTrue();
                assertThat(pointBlocked(context, p(0, 0))).isFalse();
                assertOnlyActualJoint(context);
                assertThat(allowed(context)).isTrue();
            }
        }
    }

    @Test
    void coincidentForeignEndpointDoesNotAcquireSharedIdentity() {
        RouteEdge candidate = edge("candidate", "joint", "candidate-end", List.of(p(0, 0), p(0, -10)));
        RouteEdge accepted = edge("accepted", "foreign", "accepted-end", List.of(p(0, 0), p(10, 0)));
        Map<String, RouteNode> nodes = standardNodes(p(0, 0), p(0, -10), p(10, 0));
        nodes.put("foreign", node("foreign", p(0, 0)));
        Context context = new Context(candidate, accepted, nodes);

        assertThat(context.avoidance.hasSharedJunction()).isFalse();
        assertThat(pointBlocked(context, p(0, 0))).isTrue();
        assertThat(allowed(context)).isFalse();
    }

    @Test
    void twoCommonNodeIdsDoNotCreateASingleJoinExemption() {
        RouteEdge candidate = edge("candidate", "joint", "other",
                List.of(p(0, 0), p(0, -5), p(10, -5), p(10, 0)));
        RouteEdge accepted = edge("accepted", "joint", "other", List.of(p(0, 0), p(10, 0)));
        Context context = new Context(candidate, accepted,
                Map.of("joint", node("joint", p(0, 0)), "other", node("other", p(10, 0))));

        assertThat(context.avoidance.hasSharedJunction()).isFalse();
        assertThat(pointBlocked(context, p(0, 0))).isTrue();
        assertThat(pointBlocked(context, p(10, 0))).isTrue();
        assertThat(allowed(context)).isFalse();
    }

    @Test
    void sameNodeIdRequiresExactCoordinatesOnBothEdges() {
        for (boolean mismatchCandidate : List.of(false, true)) {
            RouteEdge candidate = edge("candidate", "joint", "candidate-end",
                    List.of(p(mismatchCandidate ? 0.001 : 0, 0), p(0, -10)));
            RouteEdge accepted = edge("accepted", "joint", "accepted-end",
                    List.of(p(mismatchCandidate ? 0 : 0.001, 0), p(10, 0)));
            Context context = new Context(candidate, accepted, standardNodes(p(0, 0), p(0, -10), p(10, 0)));

            assertThat(context.avoidance.hasSharedJunction()).isFalse();
            assertThat(pointBlocked(context, p(0, 0))).isTrue();
            assertThat(allowed(context)).isFalse();
        }
    }

    @Test
    void localActualOverlapIsNotAnAllowedJoin() {
        Context context = joined(List.of(p(0, 0), p(1, 0), p(1, -5)), List.of(p(0, 0), p(10, 0)));
        assertThat(context.avoidance.hasSharedJunction()).isTrue();
        assertThat(line(context.candidate).intersection(line(context.accepted.get(0))).getLength())
                .isEqualTo(1.0);
        assertThat(allowed(context)).isFalse();
    }

    @Test
    void completeOverlapIsRejectedRegardlessOfAcceptedOrientation() {
        for (boolean reversed : List.of(false, true)) {
            Context context = joined(List.of(p(0, 0), p(10, 0)), List.of(p(0, 0), p(10, 0)), false, reversed);
            assertThat(context.avoidance.hasSharedJunction()).isTrue();
            assertThat(line(context.candidate).intersection(line(context.accepted.get(0))).getLength())
                    .isEqualTo(10.0);
            assertThat(allowed(context)).isFalse();
        }
    }

    @Test
    void sourceRemainderReturningNearJointStillBlocksTheJointPoint() {
        Context context = joined(List.of(p(0, 0), p(0, -10)), List.of(
                p(0, 0), p(10, 0), p(10, 10), p(-5, 10), p(-5, 0.1), p(-0.1, 0.1)));
        assertThat(context.avoidance.hasSharedJunction()).isTrue();
        assertOnlyActualJoint(context);
        assertThat(pointBlocked(context, p(0, 0))).isTrue();
        assertThat(allowed(context)).isFalse();
    }

    @Test
    void sourceRemainderFarFromJointRetainsItsClearanceBuffer() {
        Context context = joined(List.of(p(0, 0), p(0, 19.9)), List.of(
                p(0, 0), p(20, 0), p(20, 20), p(0, 20)));
        assertThat(context.avoidance.hasSharedJunction()).isTrue();
        assertOnlyActualJoint(context);
        assertThat(pointBlocked(context, p(0, 0))).isFalse();
        assertThat(rules.lineAllowed(line(context.candidate), constraints(context))).isFalse();
        assertThat(allowed(context)).isFalse();
    }

    @Test
    void candidateCannotLeaveThenReenterTheAcceptedBuffer() {
        Context context = joined(List.of(p(0, 0), p(0, -5), p(10, -5),
                p(10, -0.1), p(15, -0.1), p(15, -5)), List.of(p(0, 0), p(20, 0)));
        assertOnlyActualJoint(context);
        assertThat(line(context.candidate).isSimple()).isTrue();
        // Оба конца допустимы: отказ должен видеть возвращение внутрь буфера в середине трассы.
        assertThat(pointBlocked(context, p(0, 0))).isFalse();
        assertThat(pointBlocked(context, p(15, -5))).isFalse();
        assertThat(allowed(context)).isFalse();
    }

    @Test
    void passingThroughJointInPolylineInteriorIsNotAnEndpointJoin() {
        Context context = joined(List.of(p(0, 0), p(0, -10)), List.of(p(0, 0), p(10, 0)));
        List<Coordinate> through = List.of(p(-5, -5), p(0, 0), p(5, -5));
        Geometry intersection = rules.line(through).intersection(line(context.accepted.get(0)));
        assertThat((Object) intersection).isInstanceOf(Point.class);
        assertThat(intersection.getCoordinate().equals2D(p(0, 0))).isTrue();
        assertThat(pointBlocked(context, through.get(0))).isFalse();
        assertThat(pointBlocked(context, through.get(2))).isFalse();
        assertThat(allowed(context, through)).isFalse();
    }

    @Test
    void collinearTechnicalCutsOnBothEdgesPreserveTheAllowedContact() {
        for (boolean reversed : List.of(false, true)) {
            Context context = joined(List.of(p(0, 0), p(0, -0.05), p(0, -0.1), p(0, -2), p(0, -10)),
                    List.of(p(0, 0), p(0.05, 0), p(0.1, 0), p(2, 0), p(10, 0)), reversed, reversed);
            assertThat(context.avoidance.hasSharedJunction()).isTrue();
            assertOnlyActualJoint(context);
            assertThat(allowed(context)).isTrue();
        }
    }

    @Test
    void rotatedStoredMillimetreCoordinatesDoNotRequireOverlayPointsToRemainExactlyCollinear() {
        // В source77 до structural fix этот пример падал на straight.covers(intersection).
        Coordinate joint = new Coordinate(413971.991, 6173003.936);
        List<Coordinate> candidate = List.of(joint, new Coordinate(413991.946, 6172993.931),
                new Coordinate(413999.201, 6172999.398));
        List<Coordinate> accepted = List.of(joint, new Coordinate(413959.955, 6173019.909));
        for (boolean reversed : List.of(false, true)) {
            Context context = joined(candidate, accepted, reversed, reversed);
            assertOnlyActualJoint(context);
            assertThat(pointBlocked(context, joint)).isFalse();
            assertThat(allowed(context)).isTrue();
        }
    }

    @Test
    void nearbyPointDoesNotInheritExactJointPermission() {
        Context context = joined(List.of(p(0, 0), p(0, -10)), List.of(p(0, 0), p(10, 0)));
        assertThat(pointBlocked(context, p(0, 0))).isFalse();
        assertThat(pointBlocked(context, p(0.001, 0))).isTrue();
        assertThat(pointBlocked(context, p(0, -0.001))).isTrue();
        assertThat(allowed(context, List.of(p(0, -0.001), p(0, -10)))).isFalse();
    }

    @Test
    void terminalBodyAndLegCannotEachTreatAnInteriorJointAsTheirEndpoint() throws Exception {
        Context context = joined(List.of(p(0, 0), p(12, 0)), List.of(p(0, 0), p(0, 2), p(2, 2)));
        List<Coordinate> through = List.of(p(-1, 0), p(0, 0), p(1, 0));
        assertThat(allowed(context, through.subList(0, 2))).isTrue();
        assertThat(allowed(context, through.subList(1, 3))).isTrue();
        assertThat(allowed(context, through)).isFalse();

        // Контракт wrapper: разбиение не превращает середину полной трассы в общий endpoint.
        OfficialRouteGeometryRules.NormalEgress egress = normal(p(1, 0), p(0, 0));
        assertThat(router.terminalRouteAllowed(through, 100, environment, Set.of(), context.avoidance, egress))
                .isFalse();
    }

    @Test
    void terminalPrefixAndOutsideCannotEachTreatAnInteriorJointAsTheirEndpoint() throws Exception {
        Context context = joined(List.of(p(0, 0), p(12, 0)), List.of(p(0, 0), p(0, 2), p(2, 2)));
        List<Coordinate> through = List.of(p(-1, 0), p(0, 0), p(1, 0));
        assertThat(allowed(context, through.subList(0, 2))).isTrue();
        assertThat(allowed(context, through.subList(1, 3))).isTrue();
        assertThat(allowed(context, through)).isFalse();
        OfficialRouteGeometryRules.NormalEgress egress = normal(p(-1, 0), p(0, 0));
        RoutePath outside = new RoutePath(through.subList(1, 3), List.of(), 1);

        assertThat(router.withCheckedTerminalPrefix(egress, outside, 100, environment,
                Set.of(), context.avoidance)).isNull();
    }

    @Test
    void repeatedChecksDoNotMutateEdgesNodesOrPreparedGeometry() {
        Context context = joined(List.of(p(0, 0), p(0, -0.1), p(0, -10)),
                List.of(p(0, 0), p(0.1, 0), p(10, 0)));
        ObjectMapper mapper = new ObjectMapper();
        String before = mapper.valueToTree(List.of(context.candidate, context.accepted, context.nodes)).toString();
        Geometry sourceBefore = context.avoidance.constraints().get(0).source().copy();
        Geometry bufferBefore = context.avoidance.constraints().get(0).blocked().copy();

        for (int i = 0; i < 3; i++) {
            assertThat(allowed(context)).isTrue();
            assertThat(allowed(context, List.of(p(0, 0), p(1, 0), p(1, -10)))).isFalse();
            assertThat(pointBlocked(context, p(0, 0))).isFalse();
        }

        assertThat(mapper.valueToTree(List.of(context.candidate, context.accepted, context.nodes)).toString())
                .isEqualTo(before);
        assertThat(context.avoidance.constraints().get(0).source().equalsExact(sourceBefore)).isTrue();
        assertThat(context.avoidance.constraints().get(0).blocked().equalsExact(bufferBefore)).isTrue();
    }

    @Test
    void cancellationPreservesInterruptDuringPreparationAndContactChecks() {
        Context context = joined(List.of(p(0, 0), p(0, -10)), List.of(p(0, 0), p(10, 0)));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> rules.routeAvoidance(context.candidate, context.accepted, context.nodes))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThatThrownBy(() -> allowed(context)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThatThrownBy(() -> pointBlocked(context, p(0, 0))).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(allowed(context)).isTrue();
    }

    private boolean allowed(Context context) {
        return allowed(context, points(context.candidate));
    }

    private OfficialRouteGeometryRules.NormalEgress normal(Coordinate start, Coordinate exit) throws Exception {
        Constructor<OfficialRouteGeometryRules.NormalEgress> constructor = OfficialRouteGeometryRules.NormalEgress.class
                .getDeclaredConstructor(String.class, Coordinate.class, Coordinate.class);
        constructor.setAccessible(true);
        // Нет зданий и их льгот; изолируем только контракт частей и целой линии в API wrapper.
        return constructor.newInstance("unused-own-building", start, exit);
    }

    private boolean allowed(Context context, List<Coordinate> coordinates) {
        return router.lineAllowed(coordinates, 100, environment, Set.of(), context.avoidance);
    }

    private boolean pointBlocked(Context context, Coordinate coordinate) {
        return rules.pointInsideForbiddenClearance(coordinate, rules.index(constraints(context)));
    }

    private List<OfficialRouteGeometryRules.Constraint> constraints(Context context) {
        return context.avoidance.hasSharedJunction() ? context.avoidance.constraints()
                : rules.routeAvoidanceConstraints(context.avoidance.routes());
    }

    private void assertOnlyActualJoint(Context context) {
        Geometry intersection = line(context.candidate).intersection(line(context.accepted.get(0)));
        assertThat((Object) intersection).isInstanceOf(Point.class);
        assertThat(intersection.getCoordinate().equals2D(context.nodes.get("joint").getCoordinate().toCoordinate()))
                .isTrue();
    }

    private Context joined(List<Coordinate> candidate, List<Coordinate> accepted) {
        return joined(candidate, accepted, false, false);
    }

    private Context joined(List<Coordinate> candidate, List<Coordinate> accepted,
            boolean reverseCandidate, boolean reverseAccepted) {
        RouteEdge candidateEdge = orientedEdge("candidate", "candidate-end", candidate, reverseCandidate);
        RouteEdge acceptedEdge = orientedEdge("accepted", "accepted-end", accepted, reverseAccepted);
        return new Context(candidateEdge, acceptedEdge, standardNodes(candidate.get(0),
                candidate.get(candidate.size() - 1), accepted.get(accepted.size() - 1)));
    }

    private RouteEdge orientedEdge(String id, String endpointId, List<Coordinate> points, boolean reverse) {
        List<Coordinate> oriented = new ArrayList<>(points);
        if (reverse) Collections.reverse(oriented);
        return edge(id, reverse ? endpointId : "joint", reverse ? "joint" : endpointId, oriented);
    }

    private RouteEdge edge(String id, String upstream, String downstream, List<Coordinate> points) {
        List<RouteCoordinate> coordinates = points.stream().map(point -> new RouteCoordinate(point.x, point.y))
                .collect(Collectors.toList());
        LineString line = rules.line(coordinates.stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList()));
        return new RouteEdge(id, upstream, downstream, line.getLength(), coordinates, List.of(), BigDecimal.ONE, 100);
    }

    private Map<String, RouteNode> standardNodes(Coordinate joint, Coordinate candidateEnd, Coordinate acceptedEnd) {
        Map<String, RouteNode> nodes = new LinkedHashMap<>();
        nodes.put("joint", node("joint", joint));
        nodes.put("candidate-end", node("candidate-end", candidateEnd));
        nodes.put("accepted-end", node("accepted-end", acceptedEnd));
        return nodes;
    }

    private RouteNode node(String id, Coordinate point) {
        return new RouteNode(id, "new_branch_chamber", new RouteCoordinate(point.x, point.y), true, false, 0, null);
    }

    private List<Coordinate> points(RouteEdge edge) {
        return edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
    }

    private LineString line(RouteEdge edge) {
        return rules.line(points(edge));
    }

    private Coordinate p(double x, double y) {
        return new Coordinate(500000 + x, 6170000 + y);
    }

    private final class Context {
        private final RouteEdge candidate;
        private final List<RouteEdge> accepted;
        private final Map<String, RouteNode> nodes;
        private final RouteAvoidance avoidance;

        Context(RouteEdge candidate, RouteEdge accepted, Map<String, RouteNode> nodes) {
            this.candidate = candidate;
            this.accepted = List.of(accepted);
            this.nodes = nodes;
            this.avoidance = rules.routeAvoidance(candidate, this.accepted, nodes);
        }
    }
}
