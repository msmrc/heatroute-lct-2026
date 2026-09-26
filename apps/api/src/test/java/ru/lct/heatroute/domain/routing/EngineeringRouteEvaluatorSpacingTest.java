package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EngineeringRouteEvaluatorSpacingTest {
    private final EngineeringRouteEvaluator evaluator = new EngineeringRouteEvaluator();

    @ParameterizedTest
    @CsvSource({"10, 0.005", "0.05, 0.05", "10, 0.001", "0.001, 0.001"})
    void positiveMillimetreLegsCannotHideAnActualBend(double outerLength, double spacing) {
        List<RouteCoordinate> points = List.of(point(-outerLength, 0), point(0, 0),
                point(0, spacing), point(outerLength, spacing));
        EngineeringRouteEvaluator.Evaluation whole = evaluator.evaluate(chain(points));
        assertThat(whole.bendCount()).isEqualTo(2);
        assertThat(whole.insufficientSpacingCount()).isEqualTo(1);
        EngineeringRouteEvaluator.Evaluation split = evaluator.evaluate(chain(points, 1, 2));
        assertEquivalent(split, whole);
        assertThat(split.nonCompliantEdgeIds()).isEmpty();
    }

    @Test
    void splittingBeforeAtAndBetweenBendsPreservesPreferenceWithoutRejection() {
        List<RouteCoordinate> points = List.of(
                point(-5, 0), point(-3, 0), point(0, 0), point(0, 0.5),
                point(0, 1.5), point(3, 1.5), point(5, 1.5));
        EngineeringRouteEvaluator.Evaluation whole = evaluator.evaluate(chain(points));
        assertThat(whole.insufficientSpacingCount()).isEqualTo(1);
        assertThat(whole.isCompliant()).isTrue();

        for (int split = 1; split < points.size() - 1; split++) {
            EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(chain(points, split));
            assertEquivalent(result, whole);
            assertThat(result.nonCompliantEdgeIds()).isEmpty();
        }
        EngineeringRouteEvaluator.Evaluation all = evaluator.evaluate(chain(points, 1, 2, 3, 4, 5));
        assertEquivalent(all, whole);
        assertThat(all.nonCompliantEdgeIds()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"1.989, 1", "1.990, 0", "1.991, 0", "2.000, 0", "2.500, 0"})
    void keepsHistoricalSpacingPreferenceWithoutDefiningANormativeThreshold(double spacingM, int violations) {
        List<RouteCoordinate> points = List.of(
                point(-4, 0), point(0, 0), point(0, 0.5), point(0, spacingM), point(4, spacingM));
        EngineeringRouteEvaluator.Evaluation whole = evaluator.evaluate(chain(points));
        assertThat(whole.insufficientSpacingCount()).isEqualTo(violations);
        assertThat(whole.isCompliant()).isTrue();
        for (int[] splits : List.of(new int[] {1}, new int[] {2}, new int[] {3}, new int[] {1, 2, 3})) {
            EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(chain(points, splits));
            assertEquivalent(result, whole);
            assertThat(result.nonCompliantEdgeIds()).isEmpty();
        }
    }

    @Test
    void traversesManyStraightDegreeTwoNodesWithoutResettingSpacing() {
        List<RouteCoordinate> points = new ArrayList<>();
        points.add(point(-5, 0));
        for (int index = 0; index <= 60; index++) {
            points.add(point(0, index * 0.025));
        }
        points.add(point(5, 1.5));
        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(chain(points, everyInteriorPoint(points)));
        assertEquivalent(result, evaluator.evaluate(chain(points)));
        assertThat(result.insufficientSpacingCount()).isEqualTo(1);
        assertThat(result.nonCompliantEdgeIds()).isEmpty();
    }

    @Test
    void longStraightChainDoesNotCreateBendsOrCompareOnlyEndpointFragments() {
        List<RouteCoordinate> points = new ArrayList<>();
        points.add(point(-5, 0));
        for (int index = 0; index <= 4000; index++) {
            points.add(point(0, index * 0.25));
        }
        points.add(point(5, 1000));
        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(chain(points, everyInteriorPoint(points)));
        assertEquivalent(result, evaluator.evaluate(chain(points)));
        assertThat(result.bendCount()).isEqualTo(2);
        assertThat(result.isCompliant()).isTrue();
        assertThat(result.nonCompliantEdgeIds()).isEmpty();
    }

    @Test
    void countsEachConsecutiveBendPairOnceAcrossMixedSplits() {
        List<RouteCoordinate> points = List.of(point(-4, 0), point(0, 0), point(0, 1),
                point(1, 1), point(1, 2), point(5, 2));
        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(chain(points, 1, 3, 4));
        assertEquivalent(result, evaluator.evaluate(chain(points)));
        assertThat(result.insufficientSpacingCount()).isEqualTo(3);
        assertThat(result.nonCompliantEdgeIds()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"3", "4"})
    void cameraStopsSpacingWithoutAddingCameraToBendRule(int degree) {
        List<RouteEdge> edges = new ArrayList<>(chain(List.of(
                point(-4, 0), point(0, 0), point(0, 0.5), point(0, 1.5), point(4, 1.5)), 2));
        edges.add(edge("branch", "n1", "leaf", List.of(point(0, 0.5), point(4, 0.5))));
        if (degree == 4) {
            edges.add(edge("other-branch", "n1", "other-leaf", List.of(point(0, 0.5), point(-4, 0.5))));
        }
        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(edges);
        assertThat(result.bendCount()).isEqualTo(2);
        assertThat(result.insufficientSpacingCount()).isZero();
        assertThat(result.invalidAngleCount()).isZero();
        assertThat(result.irregularJunctionAngleCount()).isZero();
        assertThat(result.isCompliant()).isTrue();
        assertThat(result.nonCompliantEdgeIds()).isEmpty();
    }

    @Test
    void degreeThreeCameraAtCornerIsNotASpacingBend() {
        List<RouteEdge> edges = new ArrayList<>(chain(List.of(
                point(-4, 0), point(0, 0), point(0, 1), point(4, 1)), 1));
        edges.add(edge("branch", "n1", "leaf", List.of(point(0, 0), point(4, 0))));
        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(edges);
        assertThat(result.bendCount()).isEqualTo(1);
        assertThat(result.isCompliant()).isTrue();
        assertThat(result.insufficientSpacingCount()).isZero();
        assertThat(result.nonCompliantEdgeIds()).isEmpty();
    }

    @Test
    void cameraBranchesRetainSpacingPreferenceWithoutEngineeringRejection() {
        List<RouteEdge> edges = new ArrayList<>(chain(List.of(
                point(-5, 0), point(0, 0), point(0, 0.5), point(1, 0.5), point(1, 4)), 1, 2));
        edges.add(edge("branch", "n1", "leaf", List.of(point(0, 0), point(5, 0))));
        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(edges);
        assertThat(result.bendCount()).isEqualTo(2);
        assertThat(result.insufficientSpacingCount()).isEqualTo(1);
        assertThat(result.nonCompliantEdgeIds()).isEmpty();
    }

    @Test
    void edgeOrderAndIndependentOrientationsDoNotChangeEvaluation() {
        List<RouteEdge> edges = chain(List.of(point(-5, 0), point(-3, 0), point(0, 0),
                point(0, 0.5), point(0, 1.5), point(3, 1.5), point(5, 1.5)), 1, 2, 3, 4, 5);
        for (int mask = 0; mask < (1 << edges.size()); mask++) {
            List<RouteEdge> oriented = new ArrayList<>();
            for (int index = 0; index < edges.size(); index++) {
                oriented.add((mask & (1 << index)) == 0 ? edges.get(index) : reverse(edges.get(index)));
            }
            Collections.shuffle(oriented, new Random(mask));
            EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(oriented);
            assertThat(result.insufficientSpacingCount()).as("orientation mask %s", mask).isEqualTo(1);
            assertEquivalent(result, evaluator.evaluate(edges));
            assertThat(result.nonCompliantEdgeIds()).isEmpty();
        }
    }

    @Test
    void disconnectedChainsAreNotJoinedByCoincidentCoordinates() {
        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(List.of(
                edge("a", "a0", "a1", List.of(point(-5, 0), point(0, 0), point(0, 0.5))),
                edge("b", "b0", "b1", List.of(point(0, 0.5), point(0, 1.5), point(5, 1.5)))));
        assertThat(result.bendCount()).isEqualTo(2);
        assertThat(result.isCompliant()).isTrue();
        assertThat(result.nonCompliantEdgeIds()).isEmpty();
    }

    @Test
    void retainsStraightAngleToleranceAtSplitNodes() {
        List<RouteCoordinate> points = List.of(point(-4, 0), point(0, 0),
                point(0.001, 0.5), point(0, 1.5), point(4, 1.5));
        EngineeringRouteEvaluator.Evaluation whole = evaluator.evaluate(chain(points));
        EngineeringRouteEvaluator.Evaluation split = evaluator.evaluate(chain(points, 2));
        assertThat(whole.bendCount()).isEqualTo(2);
        assertThat(whole.insufficientSpacingCount()).isEqualTo(1);
        assertEquivalent(split, whole);
        assertThat(split.nonCompliantEdgeIds()).isEmpty();
    }

    @Test
    void preservesAngleFailuresWhenTheirBendsBecomeNodes() {
        List<RouteCoordinate> points = List.of(point(-4, 0), point(0, 0),
                point(-0.5, 0.866), point(4, 0.866));
        EngineeringRouteEvaluator.Evaluation whole = evaluator.evaluate(chain(points));
        EngineeringRouteEvaluator.Evaluation split = evaluator.evaluate(chain(points, 1, 2));
        assertThat(whole.invalidAngleCount()).isEqualTo(2);
        assertThat(whole.insufficientSpacingCount()).isEqualTo(1);
        assertEquivalent(split, whole);
        assertThat(split.nonCompliantEdgeIds()).containsExactlyInAnyOrder("e0", "e1", "e2");
    }

    @Test
    void closedDegreeTwoChainIncludesSpacingAcrossTraversalStart() {
        List<RouteCoordinate> points = List.of(point(-2, 0), point(0, 0), point(0, 1),
                point(-4, 1), point(-4, 0), point(-2, 0));
        EngineeringRouteEvaluator.Evaluation whole = evaluator.evaluate(List.of(edge("ring", "n0", "n0", points)));
        assertThat(whole.bendCount()).isEqualTo(4);
        assertThat(whole.insufficientSpacingCount()).isEqualTo(2);
        List<RouteEdge> split = new ArrayList<>(chain(points, 1, 2, 3, 4));
        RouteEdge last = split.remove(split.size() - 1);
        split.add(edge(last.getId(), last.getUpstreamNodeId(), "n0", last.getCoordinates()));
        for (int start = 0; start < split.size(); start++) {
            Collections.rotate(split, 1);
            EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(split);
            assertEquivalent(result, whole);
            assertThat(result.nonCompliantEdgeIds()).isEmpty();
            List<RouteEdge> reversed = new ArrayList<>();
            for (RouteEdge edge : split) {
                reversed.add(reverse(edge));
            }
            assertEquivalent(evaluator.evaluate(reversed), whole);
        }
    }

    private void assertEquivalent(EngineeringRouteEvaluator.Evaluation result,
            EngineeringRouteEvaluator.Evaluation whole) {
        assertThat(result.isCompliant()).isEqualTo(whole.isCompliant());
        assertThat(result.isCompliant()).isEqualTo(result.invalidAngleCount() == 0);
        assertThat(result.insufficientSpacingCount()).isEqualTo(whole.insufficientSpacingCount());
        assertThat(result.bendCount()).isEqualTo(whole.bendCount());
        assertThat(result.invalidAngleCount()).isEqualTo(whole.invalidAngleCount());
        assertThat(result.totalAngleDeviation()).isCloseTo(whole.totalAngleDeviation(), within(1e-9));
        assertThat(result.preferredAngleDeviation()).isCloseTo(whole.preferredAngleDeviation(), within(1e-9));
        assertThat(result.irregularJunctionAngleCount()).isEqualTo(whole.irregularJunctionAngleCount());
        assertThat(result.totalJunctionAngleDeviation()).isCloseTo(whole.totalJunctionAngleDeviation(), within(1e-9));
    }

    private int[] everyInteriorPoint(List<RouteCoordinate> points) {
        int[] splits = new int[points.size() - 2];
        for (int index = 0; index < splits.length; index++) {
            splits[index] = index + 1;
        }
        return splits;
    }

    private List<RouteEdge> chain(List<RouteCoordinate> points, int... splits) {
        List<RouteEdge> edges = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= splits.length; index++) {
            int end = index < splits.length ? splits[index] : points.size() - 1;
            edges.add(edge("e" + index, "n" + index, "n" + (index + 1), points.subList(start, end + 1)));
            start = end;
        }
        return edges;
    }

    private RouteEdge reverse(RouteEdge edge) {
        List<RouteCoordinate> points = new ArrayList<>(edge.getCoordinates());
        Collections.reverse(points);
        return edge(edge.getId(), edge.getDownstreamNodeId(), edge.getUpstreamNodeId(), points);
    }

    private RouteEdge edge(String id, String upstream, String downstream, List<RouteCoordinate> points) {
        double lengthM = 0.0;
        for (int index = 1; index < points.size(); index++) {
            lengthM += points.get(index - 1).toCoordinate().distance(points.get(index).toCoordinate());
        }
        return new RouteEdge(id, upstream, downstream, lengthM, points, List.of(), null, 100);
    }

    private RouteCoordinate point(double x, double y) {
        return new RouteCoordinate(x, y);
    }
}
