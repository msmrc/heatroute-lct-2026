package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class CorridorSupportedChamberRelocationsTest {
    private static final Set<String> TERMINALS = Set.of("tap", "south");

    @Test
    void exactSignedFrontCombinesTerminalStationWithActualBackboneTransverseComponent() {
        assertThat(build(fixture())).containsExactly(c(-6, 0), c(3, 0.004), c(0, -12), c(0, 9));
        assertThat(build(fixture())).doesNotContain(c(3, 0));
    }

    @Test
    void terminalLinksSupplySeedsButNeverTransverseSupport() {
        assertThat(CorridorSupportedChamberRelocations.build(chamber(), fixture(), 0,
                Set.of("back", "tap", "south"), p -> true))
                .containsExactly(c(-6, 0), c(3, 0), c(0, -12), c(0, 9));
        assertThat(CorridorSupportedChamberRelocations.build(chamber(), fixture(), 0,
                Set.of("tap", "south", "unrelated-terminal"), p -> true)).containsExactlyElementsOf(build(fixture()));
    }

    @Test
    void supportIsSeparateForEverySignedAxisAndNeverSnapped() {
        List<RouteEdge> edges = List.of(
                edge("a", "j", "a-node", c(0, 0), c(-8, 0.003), c(-20, 0.005)),
                edge("b", "j", "b-node", c(0, 0), c(7, -0.004), c(20, 0)),
                edge("c", "j", "c-node", c(0, 0), c(0.006, -6), c(0, -20)),
                edge("d", "j", "d-node", c(0, 0), c(-0.008, 5), c(0, 20)));
        assertThat(build(edges)).containsExactly(c(-8, 0.003), c(7, -0.004), c(0.006, -6), c(-0.008, 5));
    }

    @Test
    void supportScanIncludesVerticesOutsideTheTwoSeedSlots() {
        List<RouteEdge> edges = new ArrayList<>(fixture());
        edges.set(0, edge("a", "j", "back", c(0, 0), c(1, 1), c(2, 2), c(8, 0.004), c(20, 10)));
        assertThat(build(edges)).containsExactly(c(-6, 0), c(1, 0.004), c(0, -12), c(0, 1));
    }

    @Test
    void centimetreGroupingBoundaryIsHeuristicAndPredicateCanStillRejectIt() {
        List<RouteEdge> edges = new ArrayList<>(fixture());
        edges.set(0, edge("a", "j", "back", c(0, 0), c(8, 0.010), c(20, 2)));
        assertThat(build(edges)).contains(c(3, 0.010));
        assertThat(CorridorSupportedChamberRelocations.build(chamber(), edges, 0, TERMINALS,
                p -> p.y != 0.010)).noneMatch(p -> p.x > 0 && p.y == 0.010);
        edges.set(0, edge("a", "j", "back", c(0, 0), c(8, 0.011), c(20, 2)));
        assertThat(build(edges)).contains(c(3, 0));
    }

    @Test
    void blockedNearestPointFallsBackWithinSameSignedAxis() {
        AtomicInteger calls = new AtomicInteger();
        List<Coordinate> actual = CorridorSupportedChamberRelocations.build(chamber(), fixture(), 0, TERMINALS, p -> {
            calls.incrementAndGet(); return !p.equals2D(c(3, 0.004));
        });
        assertThat(actual).containsExactly(c(-6, 0), c(8, 0.004), c(0, -12), c(0, 9));
        assertThat(calls).hasValue(5);
    }

    @Test
    void ownPointPredicateIsAppliedToEveryReturnedPoint() {
        List<Coordinate> tested = new ArrayList<>();
        List<Coordinate> actual = CorridorSupportedChamberRelocations.build(chamber(), fixture(), 0, TERMINALS, p -> {
            tested.add(new Coordinate(p)); return p.distance(c(3, 0.004)) >= 6 && p.x != -6;
        });
        assertThat(actual).containsExactly(c(20, 0.004), c(0, -12), c(0, 9));
        assertThat(tested).containsAll(actual).contains(c(3, 0.004), c(8, 0.004), c(-6, 0));
    }

    @Test
    void allBlockedFourEdgePoolHasExactlyTwentyFourDistinctPredicateCalls() {
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            edges.add(edge("e" + i, "j", "outer" + i, c(0, 0),
                    c(1 + i * 3, 13 + i * 3), c(2 + i * 3, 14 + i * 3), c(3 + i * 3, 15 + i * 3)));
        }
        List<Coordinate> tested = attempted(chamber(), edges, 0);
        assertThat(tested).hasSize(24).doesNotHaveDuplicates();
        assertThat(build(edges)).containsExactly(c(1, 0), c(0, 13));
    }

    @Test
    void atMostFourOutputsPreserveAllAvailableSigns() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(CorridorSupportedChamberRelocations.build(chamber(), fixture(), 0, TERMINALS, p -> {
            calls.incrementAndGet(); return true;
        })).containsExactly(c(-6, 0), c(3, 0.004), c(0, -12), c(0, 9));
        assertThat(calls).hasValue(4);
    }

    @Test
    void nearestTwoInteriorSeedsAreDistinctAndTiesUseLocalCoordinates() {
        List<RouteEdge> edges = new ArrayList<>(fixture());
        edges.set(0, edge("a", "j", "back", c(0, 0), c(3, 4), c(3, 4), c(4, 3), c(-3, 4), c(20, 10)));
        assertThat(attempted(chamber(), edges, 0)).contains(c(-3, 0), c(3, 0), c(0, 4))
                .doesNotContain(c(4, 0), c(0, 3));
        Collections.reverse(edges);
        edges = edges.stream().map(e -> reversed(e, true, true)).collect(Collectors.toList());
        assertThat(build(edges)).containsExactly(c(-3, 0), c(3, 0), c(0, -12), c(0, 4));
    }

    @Test
    void equalSupportStationsBreakTiesGeometricallyNotByEdgeIdOrOrder() {
        List<RouteEdge> edges = List.of(
                edge("z", "j", "first", c(0, 0), c(8, 0.004), c(20, 2)),
                edge("a", "j", "second", c(0, 0), c(8, -0.003), c(20, -2)),
                edge("t", "j", "tap", c(0, 0), c(3, 9)));
        assertThat(build(edges)).contains(c(3, -0.003));
        List<RouteEdge> reversed = new ArrayList<>(edges);
        Collections.reverse(reversed);
        assertThat(build(reversed)).containsExactlyElementsOf(build(edges));
    }

    @Test
    void allEdgePermutationsAndIndependentStorageIncidenceReversalsPreserveOrderedOutput() {
        List<RouteEdge> edges = fixture();
        List<Coordinate> expected = build(edges);
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) for (int d = 0; d < 3; d++) {
            if (a == b || a == d || b == d) continue;
            for (int mask = 0; mask < 64; mask++) {
                List<RouteEdge> input = new ArrayList<>();
                int slot = 0;
                for (int index : new int[] {a, b, d}) {
                    input.add(reversed(edges.get(index), (mask & (1 << slot)) != 0, (mask & (1 << (slot + 3))) != 0));
                    slot++;
                }
                assertThat(build(input)).containsExactlyElementsOf(expected);
            }
        }
    }

    @Test
    void utmTranslationPreservesExactMillimetreOffers() {
        double dx = 430000.123, dy = 6100000.789;
        assertThat(CorridorSupportedChamberRelocations.build(node(transform(c(0, 0), 0, dx, dy)),
                transformed(fixture(), 0, dx, dy), 0, TERMINALS, p -> true))
                .containsExactly(c(dx - 6, dy), c(dx + 3, dy + 0.004), c(dx, dy - 12), c(dx, dy + 9));
    }

    @Test
    void quarterTurnHasExactExpectedSupportedPointsInUtm() {
        double dx = 430000.123, dy = 6100000.789;
        assertThat(CorridorSupportedChamberRelocations.build(node(c(dx, dy)),
                transformed(fixture(), Math.PI / 2, dx, dy), Math.PI / 2, TERMINALS, p -> true))
                .containsExactly(c(dx, dy - 6), p(c(dx - 0.004, dy + 3)).toCoordinate(),
                        c(dx + 12, dy), c(dx - 9, dy));
    }

    @Test
    void arbitraryRotationsAndMillimetreStorageRetainSupportedFrontWithinRoundingError() {
        for (double angle : new double[] {0.37, 1.3, 4.2}) {
            double dx = 430000.123, dy = 6100000.789;
            RouteNode node = node(c(dx, dy));
            List<Coordinate> actual = CorridorSupportedChamberRelocations.build(node,
                    transformed(fixture(), angle, dx, dy), angle, TERMINALS, p -> true);
            assertThat(actual).hasSize(4);
            List<Coordinate> expected = build(fixture());
            for (int i = 0; i < 4; i++) {
                assertThat(actual.get(i).distance(transform(expected.get(i), angle, dx, dy))).isLessThanOrEqualTo(0.002);
                assertThat(actual.get(i)).isEqualTo(p(actual.get(i)).toCoordinate());
            }
        }
    }

    @Test
    void farInternalVerticesDoNotSupplySeedsButFarEndpointsCanSupplyInRadiusStations() {
        List<RouteEdge> edges = List.of(
                edge("a", "j", "back", c(0, 0), c(7, 41), c(20, 50)),
                edge("b", "j", "tap", c(0, 0), c(-50, 0)),
                edge("c", "j", "south", c(0, 0), c(0, -50)));
        assertThat(attempted(chamber(), edges, 0)).containsExactly(c(20, 0));
    }

    @Test
    void exactFortyMetresIsAllowedAndOutsideOrOwnPointNeverReachesPredicate() {
        List<RouteEdge> edges = List.of(
                edge("a", "j", "back", c(0, 0), c(40, 0)),
                edge("b", "j", "tap", c(0, 0), c(-40.001, 0)),
                edge("c", "j", "south", c(0, 0), c(0, 40.001)));
        assertThat(attempted(chamber(), edges, 0)).containsExactly(c(40, 0));
    }

    @Test
    void transverseSupportMustNotPushFortyMetreStationOutsideRadius() {
        List<RouteEdge> edges = List.of(
                edge("a", "j", "back", c(0, 0), c(45, 0.010)),
                edge("b", "j", "tap", c(0, 0), c(40, 10)),
                edge("c", "j", "south", c(0, 0), c(-50, -50)));
        assertThat(attempted(chamber(), edges, 0)).containsExactly(c(0, 10));
    }

    @Test
    void roundedPointIsRecheckedAgainstRadiusBeforePredicate() {
        double angle = 0.37;
        List<RouteEdge> edges = List.of(
                edge("a", "j", "back", c(0, 0), c(33.673, 23.798)),
                edge("b", "j", "tap", c(0, 0), c(-50, -50)),
                edge("c", "j", "south", c(0, 0), c(-60, -60)));
        double rawStation = 33.673 * Math.cos(angle) + 23.798 * Math.sin(angle);
        assertThat(rawStation).isLessThan(40);
        Coordinate rounded = transform(c(rawStation, 0), angle, 0, 0);
        assertThat(rounded).isEqualTo(c(37.293, 14.465));
        assertThat(rounded.distance(c(0, 0))).isGreaterThan(40.00000001);
        assertThat(attempted(chamber(), edges, angle)).isNotEmpty().doesNotContain(rounded)
                .allMatch(q -> q.distance(c(0, 0)) <= 40.00000001);
    }

    @Test
    void predicateReceivesUniqueRoundedCopiesAndCannotMutateSourceOrOutput() {
        List<RouteEdge> edges = new ArrayList<>(fixture());
        List<RouteEdge> identities = new ArrayList<>(edges);
        List<List<RouteCoordinate>> original = edges.stream().map(RouteEdge::getCoordinates).collect(Collectors.toList());
        List<List<Coordinate>> geometry = geometry(edges);
        Set<String> terminals = new HashSet<>(TERMINALS);
        List<Coordinate> expected = build(edges);
        List<Coordinate> actual = CorridorSupportedChamberRelocations.build(chamber(), edges, 0, terminals, point -> {
            assertThat(point).isEqualTo(p(point).toCoordinate());
            point.x = Double.NaN; point.y = 99999; return true;
        });
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(edges).containsExactlyElementsOf(identities);
        assertThat(geometry(edges)).isEqualTo(geometry);
        assertThat(edges.stream().map(RouteEdge::getCoordinates).collect(Collectors.toList())).isEqualTo(original);
        assertThat(terminals).isEqualTo(TERMINALS);
        assertThatThrownBy(() -> actual.add(c(1, 1))).isInstanceOf(UnsupportedOperationException.class);
        actual.get(0).x = 99;
        assertThat(build(edges)).containsExactlyElementsOf(expected);
        assertThat(attempted(chamber(), edges, 0)).doesNotHaveDuplicates();
    }

    @Test
    void accepts512VerticesButDeclines513WithoutReadingCoordinatesOrCallingPredicate() {
        List<RouteEdge> edges = new ArrayList<>(fixture());
        List<RouteCoordinate> points = new ArrayList<>();
        points.add(p(c(0, 0)));
        for (int i = 1; i < 512; i++) points.add(p(c(8, 0.004)));
        edges.set(0, edge("a", "j", "back", points));
        assertThat(build(edges)).contains(c(3, 0.004));
        points.add(new RouteCoordinate(1, 1) {
            @Override public Coordinate toCoordinate() { throw new AssertionError("Oversized geometry must not be read"); }
        });
        edges.set(0, edge("a", "j", "back", points));
        assertThat(CorridorSupportedChamberRelocations.build(chamber(), edges, 0, TERMINALS, p -> {
            throw new AssertionError("Oversized input must not call predicate");
        })).isEmpty();
    }

    @Test
    void rejectsMissingInvalidChamberOrientationPredicateAndTerminalSet() {
        for (RouteNode node : Arrays.asList(null,
                new RouteNode("j", "new_branch_chamber", p(c(0, 0)), true, true, 0, null),
                new RouteNode("j", "existing_chamber_tie_in", p(c(0, 0)), true, false, 0, null),
                new RouteNode("j", "new_branch_chamber", p(c(0, 0)), false, false, 0, null),
                new RouteNode(" ", "new_branch_chamber", p(c(0, 0)), true, false, 0, null),
                new RouteNode("j", "new_branch_chamber", null, true, false, 0, null))) {
            assertThatThrownBy(() -> CorridorSupportedChamberRelocations.build(node, fixture(), 0, TERMINALS, p -> true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (double angle : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> CorridorSupportedChamberRelocations.build(chamber(), fixture(), angle, TERMINALS, p -> true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CorridorSupportedChamberRelocations.build(chamber(), fixture(), 0, TERMINALS, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (Set<String> terminals : Arrays.asList(null, Set.of("j"))) {
            assertThatThrownBy(() -> CorridorSupportedChamberRelocations.build(chamber(), fixture(), 0, terminals, p -> true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInvalidBranchCounts() {
        List<RouteEdge> five = new ArrayList<>(fixture());
        five.add(edge("d", "j", "d-node", c(0, 0), c(10, 0)));
        five.add(edge("e", "j", "e-node", c(0, 0), c(-10, 0)));
        for (List<RouteEdge> edges : Arrays.asList(null, List.<RouteEdge>of(), fixture().subList(0, 2), five)) {
            assertThatThrownBy(() -> build(edges)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsBadIncidenceDuplicateEdgesAndDuplicateOuterNodes() {
        for (RouteEdge bad : Arrays.asList(null,
                edge("a", "j", "j", c(0, 0), c(20, 0)),
                edge("a", "x", "y", c(0, 0), c(20, 0)),
                edge("a", null, "j", c(0, 0), c(20, 0)),
                edge("a", " ", "j", c(0, 0), c(20, 0)),
                edge(" ", "j", "back", c(0, 0), c(20, 0)),
                edge("b", "j", "back", c(0, 0), c(20, 0)),
                edge("a", "j", "tap", c(0, 0), c(20, 0)))) {
            List<RouteEdge> edges = new ArrayList<>(fixture());
            edges.set(0, bad);
            assertThatThrownBy(() -> build(edges)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMissingAmbiguousGeometryEndpointsAndNullInteriorBeforePredicate() {
        for (List<RouteCoordinate> points : Arrays.asList(List.<RouteCoordinate>of(), List.of(p(c(0, 0))),
                List.of(p(c(-20, 0)), p(c(1, 0))), List.of(p(c(0, 0)), p(c(0, 0))),
                Arrays.asList(p(c(-20, 0)), null, p(c(0, 0))), Arrays.asList(null, p(c(0, 0))))) {
            List<RouteEdge> edges = new ArrayList<>(fixture());
            edges.set(2, edge("c", "j", "south", points));
            assertThatThrownBy(() -> CorridorSupportedChamberRelocations.build(chamber(), edges, 0, TERMINALS, p -> {
                throw new AssertionError("All bounded geometry must be checked before predicate");
            })).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void endpointMatchingToleranceDoesNotSnapOrBecomePointClearance() {
        List<RouteEdge> edges = new ArrayList<>(fixture());
        edges.set(0, edge("a", "j", "back", c(0.010, 0), c(8, 0.004), c(20, 0.004)));
        assertThat(build(edges)).containsExactlyElementsOf(build(fixture()));
        edges.set(0, edge("a", "j", "back", c(0.011, 0), c(8, 0.004), c(20, 0.004)));
        assertThatThrownBy(() -> build(edges)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonFiniteAndOverflowingLocalGeometryAreRejectedEvenOutsideSeedSelection() {
        for (Coordinate value : Arrays.asList(null, c(Double.NaN, 1), c(1, Double.POSITIVE_INFINITY),
                c(Double.MAX_VALUE, Double.MAX_VALUE))) {
            RouteCoordinate malformed = new RouteCoordinate(1, 1) {
                @Override public Coordinate toCoordinate() { return value; }
            };
            List<RouteEdge> edges = new ArrayList<>(fixture());
            edges.set(2, edge("c", "j", "south", List.of(p(c(0, 0)), p(c(1, 1)), p(c(2, 2)), malformed, p(c(-6, -12)))));
            assertThatThrownBy(() -> CorridorSupportedChamberRelocations.build(chamber(), edges, 0, TERMINALS, p -> {
                throw new AssertionError("Invalid coordinate must be found before predicate");
            })).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void preexistingInterruptCancelsAndKeepsFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> build(fixture())).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void cancellationDuringGeometryScanIsNotLost() {
        List<RouteEdge> edges = new ArrayList<>(fixture());
        edges.set(0, edge("a", "j", "back", List.of(p(c(0, 0)), new RouteCoordinate(8, 0.004) {
            @Override public Coordinate toCoordinate() { Thread.currentThread().interrupt(); return super.toCoordinate(); }
        }, p(c(20, 0.004)))));
        try {
            assertThatThrownBy(() -> build(edges)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void interruptOnLastAllowedOrLastRejectedPredicateCallCancels() {
        for (boolean allow : List.of(false, true)) {
            int last = allow ? 4 : attempted(chamber(), fixture(), 0).size();
            AtomicInteger calls = new AtomicInteger();
            try {
                assertThatThrownBy(() -> CorridorSupportedChamberRelocations.build(chamber(), fixture(), 0, TERMINALS, p -> {
                    if (calls.incrementAndGet() == last) Thread.currentThread().interrupt();
                    return allow;
                })).isInstanceOf(CancellationException.class);
                assertThat(calls).hasValue(last);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
    }

    private static List<Coordinate> build(List<RouteEdge> edges) {
        return CorridorSupportedChamberRelocations.build(chamber(), edges, 0, TERMINALS, p -> true);
    }

    private static List<Coordinate> attempted(RouteNode node, List<RouteEdge> edges, double orientation) {
        List<Coordinate> tested = new ArrayList<>();
        assertThat(CorridorSupportedChamberRelocations.build(node, edges, orientation, TERMINALS, p -> {
            tested.add(new Coordinate(p)); return false;
        })).isEmpty();
        assertThat(tested).hasSizeLessThanOrEqualTo(24).doesNotHaveDuplicates();
        return tested;
    }

    private static List<RouteEdge> fixture() {
        return List.of(edge("a", "j", "back", c(0, 0), c(8, 0.004), c(20, 0.004)),
                edge("b", "j", "tap", c(0, 0), c(3, 9), c(30, 9)),
                edge("c", "j", "south", c(0, 0), c(-6, -12)));
    }

    private static RouteEdge edge(String id, String from, String to, Coordinate... points) {
        return edge(id, from, to, Arrays.stream(points).map(CorridorSupportedChamberRelocationsTest::p).collect(Collectors.toList()));
    }

    private static RouteEdge edge(String id, String from, String to, List<RouteCoordinate> points) {
        return new RouteEdge(id, from, to, 1, points, List.of(), BigDecimal.ONE, 50);
    }

    private static RouteEdge reversed(RouteEdge edge, boolean geometry, boolean incidence) {
        List<RouteCoordinate> points = new ArrayList<>(edge.getCoordinates());
        if (geometry) Collections.reverse(points);
        return edge(edge.getId(), incidence ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId(),
                incidence ? edge.getUpstreamNodeId() : edge.getDownstreamNodeId(), points);
    }

    private static List<List<Coordinate>> geometry(List<RouteEdge> edges) {
        return edges.stream().map(e -> e.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                .collect(Collectors.toList())).collect(Collectors.toList());
    }

    private static List<RouteEdge> transformed(List<RouteEdge> edges, double angle, double dx, double dy) {
        return edges.stream().map(e -> edge(e.getId(), e.getUpstreamNodeId(), e.getDownstreamNodeId(),
                e.getCoordinates().stream().map(q -> p(transform(q.toCoordinate(), angle, dx, dy)))
                        .collect(Collectors.toList()))).collect(Collectors.toList());
    }

    private static Coordinate transform(Coordinate point, double angle, double dx, double dy) {
        return new RouteCoordinate(dx + point.x * Math.cos(angle) - point.y * Math.sin(angle),
                dy + point.x * Math.sin(angle) + point.y * Math.cos(angle)).toCoordinate();
    }

    private static RouteNode chamber() { return node(c(0, 0)); }
    private static RouteNode node(Coordinate center) {
        return new RouteNode("j", "new_branch_chamber", p(center), true, false, 0, null);
    }
    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
    private static RouteCoordinate p(Coordinate point) { return new RouteCoordinate(point.x, point.y); }
}
