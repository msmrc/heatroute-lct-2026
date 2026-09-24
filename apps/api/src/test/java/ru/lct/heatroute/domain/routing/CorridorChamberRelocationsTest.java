package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class CorridorChamberRelocationsTest {
    @Test
    void criticalDoglegsOfferSeventyMetreStraightBranchWitnessFirst() {
        List<RouteEdge> edges = criticalEdges();
        List<Coordinate> candidates = build(edges);
        assertThat(candidates).hasSize(8);
        assertThat(candidates.get(0)).isEqualTo(c(0, 10));
        assertThat(edges.stream().mapToDouble(e -> e.getLengthM().doubleValue()).sum()).isEqualTo(80);
        Coordinate relocation = candidates.get(0);
        double straightLength = relocation.distance(c(-20, 10)) + relocation.distance(c(20, 10))
                + relocation.distance(c(0, -20));
        assertThat(straightLength).isEqualTo(70);
        // Только геометрический witness: допустимость всей перестроенной сети проверяет внешний planner.
        for (Coordinate endpoint : List.of(c(-20, 10), c(20, 10), c(0, -20))) {
            assertThat(OfficialRouteDeflectionRules.validatePolyline("straight",
                    List.of(p(relocation), p(endpoint))).getIssues()).isEmpty();
        }
    }

    @Test
    void blockedBestPointIsFilteredBeforeEightChoiceLimit() {
        List<Coordinate> fullOrder = allCandidates(chamber(), criticalEdges(), 0);
        List<Coordinate> actual = CorridorChamberRelocations.build(chamber(), criticalEdges(), 0,
                point -> !point.equals2D(c(0, 10)));
        assertThat(fullOrder).hasSize(14);
        assertThat(actual).containsExactlyElementsOf(fullOrder.subList(1, 9));
        assertThat(actual).doesNotContain(c(0, 10));
    }

    @Test
    void acceptsOnlyAfterMoreThanEightBlockedCandidates() {
        List<Coordinate> fullOrder = allCandidates(chamber(), criticalEdges(), 0);
        AtomicInteger calls = new AtomicInteger();
        List<Coordinate> actual = CorridorChamberRelocations.build(chamber(), criticalEdges(), 0,
                point -> calls.incrementAndGet() > 8);
        assertThat(actual).containsExactlyElementsOf(fullOrder.subList(8, fullOrder.size()));
        assertThat(calls).hasValue(fullOrder.size());
    }

    @Test
    void completelyBlockedPoolReturnsEmpty() {
        assertThat(CorridorChamberRelocations.build(chamber(), criticalEdges(), 0, point -> false)).isEmpty();
    }

    @Test
    void reversedStorageIncidenceAndIncidentOrderHaveIdenticalOrderedOutput() {
        List<RouteEdge> input = criticalEdges();
        List<Coordinate> expected = build(input);
        for (boolean reverseCoordinates : List.of(false, true)) {
            for (boolean reverseIncidence : List.of(false, true)) {
                List<RouteEdge> reversed = input.stream().map(e -> reversed(e, reverseCoordinates, reverseIncidence))
                        .collect(Collectors.toList());
                Collections.reverse(reversed);
                assertThat(build(reversed)).containsExactlyElementsOf(expected);
            }
        }
    }

    @Test
    void translatedUtmFixturePreservesExactOrderedCandidates() {
        double dx = 414000.123, dy = 6173500.789;
        List<Coordinate> actual = CorridorChamberRelocations.build(transformedChamber(0, dx, dy),
                transformedEdges(0, dx, dy), 0, point -> true);
        List<Coordinate> expected = build(criticalEdges()).stream().map(p -> transform(p, 0, dx, dy))
                .collect(Collectors.toList());
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void rotatedMillimetreFixturesRetainCriticalRelocationAndRadius() {
        for (double angle : new double[] {Math.PI / 2, 0.37, 1.3, 4.2}) {
            RouteNode node = transformedChamber(angle, 414000.123, 6173500.789);
            List<Coordinate> actual = CorridorChamberRelocations.build(node,
                    transformedEdges(angle, 414000.123, 6173500.789), angle, point -> true);
            Coordinate critical = transform(c(0, 10), angle, 414000.123, 6173500.789);
            assertThat(actual).isNotEmpty().hasSizeLessThanOrEqualTo(8)
                    .anyMatch(p -> p.distance(critical) <= 0.002)
                    .allMatch(p -> p.distance(node.getCoordinate().toCoordinate()) <= 40.00000001);
        }
    }

    @Test
    void supportsExactlyFourIncidentBranches() {
        List<RouteEdge> four = new ArrayList<>(criticalEdges());
        four.add(edge("four", "j", "outer-four", c(0, 0), c(0, 30)));
        assertThat(build(four)).hasSize(8).contains(c(0, 10));
    }

    @Test
    void maximumThirteenSeedsBoundPredicateCallsToOneHundredSixtyNine() {
        List<RouteEdge> four = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            four.add(edge("edge" + i, "j", "outer" + i, c(0, 0),
                    c(1 + i * 3, 13 + i * 3), c(2 + i * 3, 14 + i * 3), c(3 + i * 3, 15 + i * 3)));
        }
        List<Coordinate> attempted = allCandidates(chamber(), four, 0);
        // 13 независимых u и v; только исходная камера исключена из 169 пересечений.
        assertThat(attempted).hasSize(168).doesNotHaveDuplicates();
        assertThat(build(four)).hasSize(8);
    }

    @Test
    void onlyTwoNearestDistinctInteriorVerticesPerEdgeSupplyAxes() {
        List<RouteEdge> edges = new ArrayList<>(criticalEdges());
        edges.set(0, edge("root", "root-node", "j", c(-20, 10), c(-11, 17), c(-2, 10),
                c(-2, 0), c(-2, 0), c(0, 0)));
        List<Coordinate> candidates = allCandidates(chamber(), edges, 0);
        assertThat(candidates).noneMatch(p -> p.x == -11 || p.y == 17);
        assertThat(candidates).contains(c(-2, 10));
        assertThat(candidates).containsExactlyElementsOf(allCandidates(chamber(), criticalEdges(), 0));
    }

    @Test
    void farInteriorVerticesDoNotSupplyAxes() {
        List<RouteEdge> edges = List.of(
                edge("a", "j", "a-node", c(0, 0), c(7, 41), c(-20, 0)),
                edge("b", "j", "b-node", c(0, 0), c(20, 0)),
                edge("c", "j", "c-node", c(0, 0), c(0, -20)));
        assertThat(allCandidates(chamber(), edges, 0)).noneMatch(p -> p.x == 7 || p.y == 41);
    }

    @Test
    void exactFortyMetreBoundaryAllowedButOutsideAndOldLocationNeverTested() {
        List<RouteEdge> edges = List.of(
                edge("a", "j", "a-node", c(0, 0), c(40, 0)),
                edge("b", "j", "b-node", c(0, 0), c(-40.001, 0)),
                edge("c", "j", "c-node", c(0, 0), c(0, 40.001)));
        List<Coordinate> attempted = allCandidates(chamber(), edges, 0);
        assertThat(attempted).containsExactly(c(40, 0));
    }

    @Test
    void roundingCannotMoveCandidateOutsideRadiusBeforePredicate() {
        double angle = Math.PI / 4;
        List<RouteEdge> edges = List.of(
                edge("a", "j", "a-node", c(0, 0), c(42.426, 14.142)),
                edge("b", "j", "b-node", c(0, 0), c(-10, 0)),
                edge("c", "j", "c-node", c(0, 0), c(0, -10)));
        List<Coordinate> attempted = allCandidates(chamber(), edges, angle);
        assertThat(attempted).isNotEmpty().allMatch(point -> point.distance(c(0, 0)) <= 40.00000001);
    }

    @Test
    void predicateReceivesOnlyUniqueRoundedCoordinates() {
        List<Coordinate> attempted = allCandidates(transformedChamber(0.37, 414000.123, 6173500.789),
                transformedEdges(0.37, 414000.123, 6173500.789), 0.37);
        assertThat(attempted).isNotEmpty().doesNotHaveDuplicates();
        assertThat(attempted).allSatisfy(point -> assertThat(point).isEqualTo(p(point).toCoordinate()));
    }

    @Test
    void inputListsAndGeometryArePreservedEvenIfPredicateMutatesItsCopy() {
        List<RouteEdge> edges = new ArrayList<>(criticalEdges());
        List<List<Coordinate>> before = edges.stream().map(e -> e.getCoordinates().stream()
                .map(RouteCoordinate::toCoordinate).collect(Collectors.toList())).collect(Collectors.toList());
        List<RouteEdge> identities = new ArrayList<>(edges);
        List<Coordinate> expected = build(edges);
        List<Coordinate> actual = CorridorChamberRelocations.build(chamber(), edges, 0, point -> {
            point.x = 123456; point.y = -987654; return true;
        });
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(edges).containsExactlyElementsOf(identities);
        assertThat(edges.stream().map(e -> e.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                .collect(Collectors.toList())).collect(Collectors.toList())).isEqualTo(before);
    }

    @Test
    void exactlyFiveHundredTwelveVerticesAreProcessedButNextVertexDeclinesWithoutPredicate() {
        List<RouteEdge> edges = new ArrayList<>(criticalEdges());
        List<RouteCoordinate> points = new ArrayList<>();
        for (int i = 0; i < 512; i++) points.add(new RouteCoordinate(-20 + i * 20.0 / 511, 0));
        edges.set(0, edge("root", "root-node", "j", points));
        assertThat(build(edges)).isNotEmpty();
        points.add(0, new RouteCoordinate(-20, 0));
        edges.set(0, edge("root", "root-node", "j", points));
        assertThat(CorridorChamberRelocations.build(chamber(), edges, 0, point -> {
            throw new AssertionError("Oversized edge must decline before clearance");
        })).isEmpty();
    }

    @Test
    void invalidChamberOrOrientationOrPredicateIsRejected() {
        for (RouteNode node : Arrays.asList(null,
                new RouteNode("j", "new_branch_chamber", p(c(0, 0)), true, true, 0, null),
                new RouteNode("j", "existing_chamber_tie_in", p(c(0, 0)), true, false, 0, null),
                new RouteNode("j", "new_branch_chamber", p(c(0, 0)), false, false, 0, null),
                new RouteNode(" ", "new_branch_chamber", p(c(0, 0)), true, false, 0, null),
                new RouteNode("j", "new_branch_chamber", null, true, false, 0, null))) {
            assertThatThrownBy(() -> CorridorChamberRelocations.build(node, criticalEdges(), 0, p -> true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (double angle : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> CorridorChamberRelocations.build(chamber(), criticalEdges(), angle, p -> true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CorridorChamberRelocations.build(chamber(), criticalEdges(), 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingOrWrongBranchCountIsRejected() {
        List<RouteEdge> five = new ArrayList<>(criticalEdges());
        five.add(edge("d", "j", "d-node", c(0, 0), c(10, 0)));
        five.add(edge("e", "j", "e-node", c(0, 0), c(-10, 0)));
        for (List<RouteEdge> edges : Arrays.asList(null, List.<RouteEdge>of(), criticalEdges().subList(0, 2), five)) {
            assertThatThrownBy(() -> build(edges)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void malformedIncidenceAndDuplicateIdentityAreRejected() {
        List<RouteEdge> malformed = Arrays.asList(null,
                edge("root", "j", "j", c(0, 0), c(20, 0)),
                edge("root", "other", "not-j", c(0, 0), c(20, 0)),
                edge("root", " ", "j", c(0, 0), c(20, 0)),
                edge(" ", "root-node", "j", c(0, 0), c(20, 0)),
                edge("one", "root-node", "j", c(0, 0), c(20, 0)),
                edge("root", "one-node", "j", c(0, 0), c(20, 0)));
        for (RouteEdge replacement : malformed) {
            List<RouteEdge> edges = new ArrayList<>(criticalEdges());
            edges.set(0, replacement);
            assertThatThrownBy(() -> build(edges)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void missingOrAmbiguousGeometryEndpointsAndNullVerticesAreRejected() {
        for (List<RouteCoordinate> points : Arrays.asList(List.<RouteCoordinate>of(), List.of(p(c(0, 0))),
                List.of(p(c(-20, 0)), p(c(1, 0))), List.of(p(c(0, 0)), p(c(0, 0))),
                Arrays.asList(p(c(-20, 0)), null, p(c(0, 0))), Arrays.asList(null, p(c(0, 0))))) {
            List<RouteEdge> edges = new ArrayList<>(criticalEdges());
            edges.set(0, edge("root", "root-node", "j", points));
            assertThatThrownBy(() -> build(edges)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void chamberEndpointUsesCentimetreToleranceNotArbitrarySnapping() {
        List<RouteEdge> edges = new ArrayList<>(criticalEdges());
        edges.set(0, edge("root", "root-node", "j", c(-20, 10), c(0.01, 0)));
        assertThat(build(edges)).isNotEmpty();
        edges.set(0, edge("root", "root-node", "j", c(-20, 10), c(0.011, 0)));
        assertThatThrownBy(() -> build(edges)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonFiniteCoordinateFromMalformedModelIsRejectedBeforeClearance() {
        // Обычный RouteCoordinate запрещает NaN конструктором; проверяем защиту границы helper.
        RouteCoordinate malformed = new RouteCoordinate(1, 1) {
            @Override public Coordinate toCoordinate() { return c(Double.NaN, 1); }
        };
        for (List<RouteCoordinate> points : List.of(List.of(p(c(-20, 0)), malformed, p(c(0, 0))),
                List.of(malformed, p(c(0, 0))))) {
            List<RouteEdge> edges = new ArrayList<>(criticalEdges());
            edges.set(0, edge("root", "root-node", "j", points));
            assertThatThrownBy(() -> build(edges)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void preexistingInterruptCancelsWithoutClearingFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> build(criticalEdges())).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void interruptInsidePredicateCancelsBeforeReturningEvenEighthChoice() {
        AtomicInteger calls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> CorridorChamberRelocations.build(chamber(), criticalEdges(), 0, point -> {
                if (calls.incrementAndGet() == 8) Thread.currentThread().interrupt();
                return true;
            })).isInstanceOf(CancellationException.class);
            assertThat(calls).hasValue(8);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private static List<Coordinate> build(List<RouteEdge> edges) {
        return CorridorChamberRelocations.build(chamber(), edges, 0, point -> true);
    }

    private static List<Coordinate> allCandidates(RouteNode node, List<RouteEdge> edges, double angle) {
        List<Coordinate> attempted = new ArrayList<>();
        assertThat(CorridorChamberRelocations.build(node, edges, angle, point -> {
            attempted.add(new Coordinate(point)); return false;
        })).isEmpty();
        return attempted;
    }

    private static List<RouteEdge> criticalEdges() {
        return List.of(edge("root", "root-node", "j", c(-20, 10), c(-2, 10), c(-2, 0), c(0, 0)),
                edge("one", "j", "one-node", c(0, 0), c(2, 0), c(2, 10), c(20, 10)),
                edge("two", "j", "two-node", c(0, 0), c(0, -20)));
    }

    private static RouteEdge edge(String id, String from, String to, Coordinate... points) {
        return edge(id, from, to, Arrays.stream(points).map(CorridorChamberRelocationsTest::p).collect(Collectors.toList()));
    }

    private static RouteEdge edge(String id, String from, String to, List<RouteCoordinate> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i - 1) != null && points.get(i) != null) {
                double distance = points.get(i - 1).toCoordinate().distance(points.get(i).toCoordinate());
                if (Double.isFinite(distance)) length += distance;
            }
        }
        return new RouteEdge(id, from, to, length, points, List.of(), BigDecimal.ONE, 50);
    }

    private static RouteEdge reversed(RouteEdge original, boolean geometry, boolean incidence) {
        List<RouteCoordinate> points = new ArrayList<>(original.getCoordinates());
        if (geometry) Collections.reverse(points);
        return edge(original.getId(), incidence ? original.getDownstreamNodeId() : original.getUpstreamNodeId(),
                incidence ? original.getUpstreamNodeId() : original.getDownstreamNodeId(), points);
    }

    private static RouteNode chamber() { return transformedChamber(0, 0, 0); }

    private static RouteNode transformedChamber(double angle, double dx, double dy) {
        return new RouteNode("j", "new_branch_chamber", p(transform(c(0, 0), angle, dx, dy)), true, false, 0, null);
    }

    private static List<RouteEdge> transformedEdges(double angle, double dx, double dy) {
        return criticalEdges().stream().map(e -> edge(e.getId(), e.getUpstreamNodeId(), e.getDownstreamNodeId(),
                e.getCoordinates().stream().map(p -> p(transform(p.toCoordinate(), angle, dx, dy)))
                        .collect(Collectors.toList()))).collect(Collectors.toList());
    }

    private static Coordinate transform(Coordinate p, double angle, double dx, double dy) {
        return new RouteCoordinate(dx + p.x * Math.cos(angle) - p.y * Math.sin(angle),
                dy + p.x * Math.sin(angle) + p.y * Math.cos(angle)).toCoordinate();
    }

    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
    private static RouteCoordinate p(Coordinate point) { return new RouteCoordinate(point.x, point.y); }
}
