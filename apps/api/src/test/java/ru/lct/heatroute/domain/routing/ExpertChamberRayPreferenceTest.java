package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;

/** Предпочтение Т/креста: соседние лучи 90°, противоположные 180°; не новая обязательная норма. */
class ExpertChamberRayPreferenceTest {
    private final EngineeringRouteEvaluator evaluator = new EngineeringRouteEvaluator();

    @ParameterizedTest
    @ValueSource(doubles = {45, 135})
    void fortyFiveAndOneHundredThirtyFiveDegreeChamberRaysAreIrregularButCompliant(double branch) {
        var result = evaluator.evaluate(star("camera", 0, 0, 0, 0, branch, 180));
        assertThat(result.irregularJunctionAngleCount()).isEqualTo(2);
        assertThat(result.totalJunctionAngleDeviation()).isCloseTo(90, within(1e-7));
        assertOnlyPreference(result);
    }

    @Test
    void orthogonalTeeAndCrossHaveNoIrregularPairs() {
        for (double[] angles : List.of(new double[] {0, 90, 180}, new double[] {0, 90, 180, 270})) {
            var result = evaluator.evaluate(star("camera", 0, 0, 0, angles));
            assertThat(result.irregularJunctionAngleCount()).isZero();
            assertThat(result.totalJunctionAngleDeviation()).isZero();
            assertOnlyPreference(result);
        }
    }

    @Test
    void diagonalFourthRayContributesThreeIrregularPairsNotOneIrregularNode() {
        var result = evaluator.evaluate(star("camera", 0, 0, 0, 0, 45, 90, 180));
        assertThat(result.irregularJunctionAngleCount()).isEqualTo(3);
        assertThat(result.totalJunctionAngleDeviation()).isCloseTo(135, within(1e-7));
        assertOnlyPreference(result);
    }

    @Test
    void coincidentRaysRemainIrregularWithoutIntroducingANearParallelThreshold() {
        var result = evaluator.evaluate(star("camera", 0, 0, 0, 0, 0, 90));
        assertThat(result.irregularJunctionAngleCount()).isEqualTo(1);
        assertThat(result.totalJunctionAngleDeviation()).isCloseTo(90, within(1e-7));
        assertOnlyPreference(result);
    }

    @Test
    void threeWayOneHundredTwentyDegreeJunctionUsesDistanceToNinetyOrOneEighty() {
        var result = evaluator.evaluate(star("camera", 0, 0, 0, 0, 120, 240));
        assertThat(result.irregularJunctionAngleCount()).isEqualTo(3);
        assertThat(result.totalJunctionAngleDeviation()).isCloseTo(90, within(0.002));
        assertOnlyPreference(result);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 31, 90, 173, 271})
    void rotatedMillimetreUtmStoragePreservesDiagonalPreferenceAndOrthogonalControls(double rotation) {
        for (double branch : new double[] {45, 90, 135}) {
            var result = evaluator.evaluate(star("camera", 430000.123, 6100000.789, rotation, 0, branch, 180));
            assertThat(result.irregularJunctionAngleCount()).isEqualTo(branch == 90 ? 0 : 2);
            assertThat(result.totalJunctionAngleDeviation()).isCloseTo(branch == 90 ? 0 : 90, within(0.002));
            assertOnlyPreference(result);
        }
        var cross = evaluator.evaluate(star("cross", 430000.123, 6100000.789, rotation, 0, 90, 180, 270));
        assertThat(cross.irregularJunctionAngleCount()).isZero();
        assertThat(cross.totalJunctionAngleDeviation()).isLessThan(0.002);
        assertOnlyPreference(cross);
    }

    @Test
    void allStorageDirectionsAndEdgeOrdersPreservePairMetricsWithoutMutatingInput() {
        List<RouteEdge> source = star("camera", 430000.123, 6100000.789, 31, 0, 45, 90, 180);
        List<List<RouteCoordinate>> before = source.stream().map(RouteEdge::getCoordinates).collect(Collectors.toList());
        var expected = evaluator.evaluate(source);
        assertThat(expected.irregularJunctionAngleCount()).isEqualTo(3);
        for (List<RouteEdge> order : permutations(source)) for (int mask = 0; mask < 16; mask++) {
            List<RouteEdge> input = new ArrayList<>();
            for (int i = 0; i < 4; i++) input.add((mask & (1 << i)) == 0 ? order.get(i) : reversed(order.get(i)));
            var actual = evaluator.evaluate(input);
            assertThat(actual.irregularJunctionAngleCount()).isEqualTo(expected.irregularJunctionAngleCount());
            assertThat(actual.totalJunctionAngleDeviation()).isCloseTo(expected.totalJunctionAngleDeviation(), within(1e-9));
            assertThat(actual.preservesJunctionQualityOf(expected)).isTrue();
            assertThat(expected.preservesJunctionQualityOf(actual)).isTrue();
            assertOnlyPreference(actual);
        }
        assertThat(source.stream().map(RouteEdge::getCoordinates).collect(Collectors.toList())).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"89.499, 2", "89.501, 0", "90.499, 0", "90.501, 2",
            "179.499, 2", "179.501, 0", "180.499, 0", "180.501, 2"})
    void halfDegreeToleranceIsUnchangedOnBothSidesOfNinetyAndOneEighty(double ray, int irregular) {
        double other = ray < 100 ? 180 : 90;
        var result = evaluator.evaluate(star("camera", 430000.123, 6100000.789, 0, 0, ray, other));
        assertThat(result.irregularJunctionAngleCount()).isEqualTo(irregular);
        double deviation = 2 * Math.abs(ray - (ray < 100 ? 90 : 180));
        assertThat(result.totalJunctionAngleDeviation()).isCloseTo(deviation, within(0.00002));
        assertOnlyPreference(result);
    }

    @Test
    void exactHalfDegreeNumericBoundaryIsIncludedWithoutDeadbandingDeviation() {
        // Isolate the existing floating-point angle boundary from millimetre storage roundtrip.
        double radians = Math.toRadians(90.5);
        RouteCoordinate preciseRay = new RouteCoordinate(0, 1) {
            @Override public Coordinate toCoordinate() { return new Coordinate(Math.cos(radians), Math.sin(radians)); }
        };
        var result = evaluator.evaluate(List.of(
                edge("a", "j", "a-end", point(0, 0), point(1, 0)),
                edge("b", "j", "b-end", point(0, 0), preciseRay),
                edge("c", "j", "c-end", point(0, 0), point(-1, 0))));
        assertThat(result.irregularJunctionAngleCount()).isZero();
        assertThat(result.totalJunctionAngleDeviation()).isCloseTo(1, within(1e-12));
        assertOnlyPreference(result);
    }

    @ParameterizedTest
    @CsvSource({"60, false, 1, 30", "90, true, 1, 0", "120, true, 1, 15",
            "135, true, 1, 0", "180, true, 0, 0"})
    void pipeInternalAnglesAndDegreeTwoNodesKeepExistingRules(double angle, boolean compliant, int bends, double preferred) {
        RouteCoordinate first = point(100, 0), center = point(0, 0);
        RouteCoordinate last = point(100 * Math.cos(Math.toRadians(angle)), 100 * Math.sin(Math.toRadians(angle)));
        List<List<RouteEdge>> representations = List.of(
                List.of(edge("pipe", "a", "b", first, center, last)),
                List.of(edge("left", "a", "j", first, center), edge("right", "j", "b", center, last)));
        for (List<RouteEdge> edges : representations) {
            var result = evaluator.evaluate(edges);
            assertThat(result.isCompliant()).isEqualTo(compliant);
            assertThat(result.bendCount()).isEqualTo(bends);
            assertThat(result.invalidAngleCount()).isEqualTo(compliant ? 0 : 1);
            assertThat(result.insufficientSpacingCount()).isZero();
            assertThat(result.preferredAngleDeviation()).isCloseTo(preferred, within(0.001));
            assertThat(result.irregularJunctionAngleCount()).isZero();
            assertThat(result.totalJunctionAngleDeviation()).isZero();
        }
    }

    @Test
    void sameNodeOrthogonalRepairPreservesQualityButDiagonalRegressionDoesNot() {
        var diagonal = evaluator.evaluate(star("same-camera", 0, 0, 0, 0, 45, 180));
        var orthogonal = evaluator.evaluate(star("same-camera", 0, 0, 0, 0, 90, 180));
        assertThat(orthogonal.preservesJunctionQualityOf(diagonal)).isTrue();
        assertThat(diagonal.preservesJunctionQualityOf(orthogonal)).isFalse();
    }

    @Test
    void anotherCamerasImprovementCannotHideCollapseOfAnExistingNodesMinimum() {
        List<RouteEdge> before = new ArrayList<>(star("a", 0, 0, 0, 0, 90, 180));
        before.addAll(star("b", 1000, 1000, 0, 0, 45, 90, 180));
        List<RouteEdge> after = new ArrayList<>(star("a", 0, 0, 0, 0, 45, 180));
        after.addAll(star("b", 1000, 1000, 0, 0, 90, 180, 270));
        var oldGeometry = evaluator.evaluate(before);
        var newGeometry = evaluator.evaluate(after);
        assertThat(newGeometry.irregularJunctionAngleCount()).isLessThan(oldGeometry.irregularJunctionAngleCount());
        assertThat(newGeometry.totalJunctionAngleDeviation()).isLessThan(oldGeometry.totalJunctionAngleDeviation());
        assertThat(newGeometry.preservesJunctionQualityOf(oldGeometry)).isFalse();
    }

    @Test
    void removingAChamberStillCannotMasqueradeAsPreservingItsQuality() {
        var before = evaluator.evaluate(star("camera", 0, 0, 0, 0, 90, 180));
        var after = evaluator.evaluate(List.of(edge("pipe", "left", "right", point(-10, 0), point(10, 0))));
        assertThat(after.preservesJunctionQualityOf(before)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 31, 90})
    void anotherCamerasImprovementCannotHideTeeBecomingYDespiteLargerMinimum(double rotation) {
        List<RouteEdge> beforeEdges = withOtherCamera(star("a", 0, 0, rotation, 0, 90, 180), false);
        List<RouteEdge> afterEdges = withOtherCamera(star("a", 0, 0, rotation, 0, 120, 240), true);
        var before = evaluator.evaluate(beforeEdges);
        for (boolean reverse : List.of(false, true)) {
            if (reverse) {
                Collections.reverse(afterEdges);
                afterEdges = afterEdges.stream().map(this::reversed).collect(Collectors.toList());
            }
            var after = evaluator.evaluate(afterEdges);
            assertThat(after.irregularJunctionAngleCount()).isEqualTo(3).isLessThan(before.irregularJunctionAngleCount());
            assertThat(after.totalJunctionAngleDeviation()).isLessThan(before.totalJunctionAngleDeviation());
            assertOnlyPreference(before);
            assertOnlyPreference(after);
            assertThat(after.preservesJunctionQualityOf(before)).isFalse();
        }
    }

    @Test
    void perNodeCountCannotIncreaseEvenWhenThatNodesExcessDeviationFalls() {
        // A: count 2 -> 3, excess 89 -> 88.5, minimum 15 -> 30 degrees.
        var before = evaluator.evaluate(withOtherCamera(star("a", 0, 0, 0, 0, 15, 90), false));
        var after = evaluator.evaluate(withOtherCamera(star("a", 0, 0, 0, 0, 30, 105), true));
        assertThat(before.irregularJunctionAngleCount()).isEqualTo(8);
        assertThat(after.irregularJunctionAngleCount()).isEqualTo(3);
        assertThat(after.totalJunctionAngleDeviation()).isLessThan(before.totalJunctionAngleDeviation());
        assertThat(after.preservesJunctionQualityOf(before)).isFalse();
    }

    @Test
    void perNodeExcessCannotIncreaseWhenCountAndMinimumArePreserved() {
        // A: count 3 -> 3, excess 178.5 -> 208.5, minimum stays 15 degrees.
        var before = evaluator.evaluate(withOtherCamera(star("a", 0, 0, 0, 0, 15, 45), false));
        var after = evaluator.evaluate(withOtherCamera(star("a", 0, 0, 0, 0, 15, 30), true));
        assertThat(before.irregularJunctionAngleCount()).isEqualTo(9);
        assertThat(after.irregularJunctionAngleCount()).isEqualTo(3);
        assertThat(after.totalJunctionAngleDeviation()).isLessThan(before.totalJunctionAngleDeviation());
        assertThat(after.preservesJunctionQualityOf(before)).isFalse();
    }

    @Test
    void regularCameraRoundingWithinExistingToleranceDoesNotCreateALocalRegression() {
        List<RouteEdge> before = new ArrayList<>(star("a", 0, 0, 0, 0, 90, 180));
        before.addAll(star("b", 50000, 50000, 0, 0, 92, 180));
        List<RouteEdge> after = new ArrayList<>(star("a", 0, 0, 0, 0, 90.2, 180));
        after.addAll(star("b", 50000, 50000, 0, 0, 90, 180));
        var oldGeometry = evaluator.evaluate(before);
        var newGeometry = evaluator.evaluate(after);
        assertThat(newGeometry.irregularJunctionAngleCount()).isZero();
        assertThat(newGeometry.totalJunctionAngleDeviation()).isPositive().isLessThan(oldGeometry.totalJunctionAngleDeviation());
        assertThat(newGeometry.preservesJunctionQualityOf(oldGeometry)).isTrue();
    }

    @Test
    void rawAggregateGuardStillRejectsAnIncreaseEvenWhenAllLocalExcessesAreZero() {
        var before = evaluator.evaluate(star("a", 0, 0, 0, 0, 90, 180));
        var after = evaluator.evaluate(star("a", 0, 0, 0, 0, 90.2, 180));
        assertThat(after.irregularJunctionAngleCount()).isZero();
        assertThat(after.totalJunctionAngleDeviation()).isGreaterThan(before.totalJunctionAngleDeviation());
        assertThat(after.preservesJunctionQualityOf(before)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"0.00000002, true", "0.00000010, false"})
    void perNodeExcessUsesTheExistingOneEMinusSevenComparison(double angularShift, boolean preserved) {
        // No storage roundtrip here: exercise the numeric comparison, not millimetre quantization.
        var before = evaluator.evaluate(withOtherCamera(preciseStar("a", 0, 15, 45), false));
        var after = evaluator.evaluate(withOtherCamera(preciseStar("a", 0, 15, 45 - angularShift), true));
        assertThat(after.irregularJunctionAngleCount()).isEqualTo(3);
        assertThat(after.totalJunctionAngleDeviation()).isLessThan(before.totalJunctionAngleDeviation());
        assertThat(after.preservesJunctionQualityOf(before)).isEqualTo(preserved);
    }

    @Test
    void identicalRaysAtANewNodeIdDoNotPreserveTheRemovedNode() {
        var before = evaluator.evaluate(star("original", 0, 0, 0, 0, 90, 180));
        var after = evaluator.evaluate(star("replacement", 0, 0, 0, 0, 90, 180));
        assertThat(after.irregularJunctionAngleCount()).isZero();
        assertThat(after.totalJunctionAngleDeviation()).isZero();
        assertThat(after.preservesJunctionQualityOf(before)).isFalse();
    }

    private List<RouteEdge> withOtherCamera(List<RouteEdge> target, boolean repaired) {
        List<RouteEdge> result = new ArrayList<>(target);
        result.addAll(star("b", 50000, 50000, 0,
                repaired ? new double[] {0, 90, 180, 270} : new double[] {0, 10, 20, 30}));
        return result;
    }

    private List<RouteEdge> preciseStar(String id, double... angles) {
        List<RouteEdge> result = new ArrayList<>();
        for (int i = 0; i < angles.length; i++) {
            double radians = Math.toRadians(angles[i]);
            RouteCoordinate ray = new RouteCoordinate(1, 0) {
                @Override public Coordinate toCoordinate() { return new Coordinate(Math.cos(radians), Math.sin(radians)); }
            };
            result.add(edge(id + ":" + i, id, id + ":outer:" + i, point(0, 0), ray));
        }
        return result;
    }

    private void assertOnlyPreference(EngineeringRouteEvaluator.Evaluation value) {
        assertThat(value.isCompliant()).isTrue();
        assertThat(value.bendCount()).isZero();
        assertThat(value.invalidAngleCount()).isZero();
        assertThat(value.insufficientSpacingCount()).isZero();
        assertThat(value.totalAngleDeviation()).isZero();
        assertThat(value.preferredAngleDeviation()).isZero();
        assertThat(value.nonCompliantEdgeIds()).isEmpty();
    }

    private List<RouteEdge> star(String id, double dx, double dy, double rotation, double... angles) {
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 0; i < angles.length; i++) {
            double radians = Math.toRadians(rotation + angles[i]);
            edges.add(edge(id + ":edge:" + i, id, id + ":outer:" + i, point(dx, dy),
                    point(dx + 10000 * Math.cos(radians), dy + 10000 * Math.sin(radians))));
        }
        return edges;
    }

    private List<List<RouteEdge>> permutations(List<RouteEdge> edges) {
        if (edges.isEmpty()) return List.of(List.of());
        List<List<RouteEdge>> result = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            List<RouteEdge> rest = new ArrayList<>(edges);
            RouteEdge first = rest.remove(i);
            for (List<RouteEdge> tail : permutations(rest)) {
                List<RouteEdge> order = new ArrayList<>(List.of(first));
                order.addAll(tail); result.add(order);
            }
        }
        return result;
    }

    private RouteEdge reversed(RouteEdge edge) {
        List<RouteCoordinate> points = new ArrayList<>(edge.getCoordinates());
        Collections.reverse(points);
        return edge(edge.getId(), edge.getDownstreamNodeId(), edge.getUpstreamNodeId(), points.toArray(new RouteCoordinate[0]));
    }

    private RouteEdge edge(String id, String from, String to, RouteCoordinate... points) {
        double length = 0;
        for (int i = 1; i < points.length; i++) length += points[i - 1].toCoordinate().distance(points[i].toCoordinate());
        return new RouteEdge(id, from, to, length, List.of(points), List.of(), null, 50);
    }

    private RouteCoordinate point(double x, double y) { return new RouteCoordinate(x, y); }
}
