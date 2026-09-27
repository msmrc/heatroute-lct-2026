package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EngineeringRouteEvaluatorTest {
    private final EngineeringRouteEvaluator evaluator = new EngineeringRouteEvaluator();

    @Test
    void acceptsNinetyAndOneHundredTwentyDegreeInternalAngles() {
        RouteEdge edge = edge(
                point(0, 0),
                point(2, 0),
                point(2, 2),
                point(2 + 2 * Math.cos(Math.toRadians(30)), 3));

        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(List.of(edge));

        assertThat(result.isCompliant()).isTrue();
        assertThat(result.invalidAngleCount()).isZero();
        assertThat(result.insufficientSpacingCount()).isZero();
    }

    @Test
    void rejectsAngleOutsideRangeEvenWhenSpacingAlsoHasAPreferencePenalty() {
        RouteEdge edge = edge(
                point(0, 0),
                point(1, 0),
                point(0.5, 0.2),
                point(3, 2));

        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(List.of(edge));

        assertThat(result.isCompliant()).isFalse();
        assertThat(result.invalidAngleCount()).isPositive();
        assertThat(result.insufficientSpacingCount()).isEqualTo(1);
        assertThat(result.nonCompliantEdgeIds()).containsExactly("edge");
    }

    @ParameterizedTest
    @CsvSource({"89,false", "90,true", "120,true", "120.6,false", "135,false", "150,false", "179,false", "180,true"})
    void appliesTheUpdatedNinetyToOneHundredTwentyInternalRange(double internalAngle, boolean valid) {
        double turn = Math.toRadians(180 - internalAngle);
        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(List.of(edge(
                point(0, 0), point(5, 0), point(5 + 5 * Math.cos(turn), 5 * Math.sin(turn)))));
        assertThat(result.isCompliant()).isEqualTo(valid);
        assertThat(result.invalidAngleCount()).isEqualTo(valid ? 0 : 1);
    }

    @Test
    void prefersTheNinetyOrOneHundredTwentyDegreeBoundariesInsideAllowedRange() {
        EngineeringRouteEvaluator.Evaluation canonical = evaluator.evaluate(List.of(edge(
                point(0, 0),
                point(2, 0),
                point(2, 2))));
        EngineeringRouteEvaluator.Evaluation intermediate = evaluator.evaluate(List.of(edge(
                point(0, 0),
                point(2, 0),
                point(2 + 2 * Math.cos(Math.toRadians(75)), 2 * Math.sin(Math.toRadians(75))))));

        assertThat(canonical.invalidAngleCount()).isZero();
        assertThat(intermediate.invalidAngleCount()).isZero();
        assertThat(canonical.preferredAngleDeviation()).isZero();
        assertThat(intermediate.preferredAngleDeviation()).isGreaterThan(0.0);
    }

    @Test
    void evaluatesAnglesWhereSeparateEdgesMeetAtANetworkNode() {
        RouteEdge trunkLeft = edge("left", "left-node", "junction", point(-10, 0), point(0, 0));
        RouteEdge trunkRight = edge("right", "junction", "right-node", point(0, 0), point(10, 0));
        RouteEdge branch = edge("branch", "junction", "branch-node", point(0, 0), point(2, 10));

        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(List.of(
                trunkLeft, trunkRight, branch));

        assertThat(result.irregularJunctionAngleCount()).isPositive();
        assertThat(result.totalJunctionAngleDeviation()).isGreaterThan(0.0);
    }

    @Test
    void shortLegalBendsDoNotPolluteTheEdgeIdsOfASeparateAngleViolation() {
        RouteEdge legal = edge("short-legal", "legal-a", "legal-b",
                point(0, 0), point(10, 0), point(10, .001), point(20, .001));
        RouteEdge sharp = edge("sharp", "sharp-a", "sharp-b",
                point(0, 10), point(10, 10), point(9, 11));

        EngineeringRouteEvaluator.Evaluation legalOnly = evaluator.evaluate(List.of(legal));
        assertThat(legalOnly.insufficientSpacingCount()).isEqualTo(1);
        assertThat(legalOnly.isCompliant()).isTrue();
        assertThat(legalOnly.nonCompliantEdgeIds()).isEmpty();

        EngineeringRouteEvaluator.Evaluation mixed = evaluator.evaluate(List.of(legal, sharp));
        assertThat(mixed.invalidAngleCount()).isEqualTo(1);
        assertThat(mixed.insufficientSpacingCount()).isEqualTo(1);
        assertThat(mixed.isCompliant()).isFalse();
        assertThat(mixed.nonCompliantEdgeIds()).containsExactly("sharp");
    }

    private RouteEdge edge(RouteCoordinate... coordinates) {
        return edge("edge", "upstream", "downstream", coordinates);
    }

    private RouteEdge edge(
            String id,
            String upstream,
            String downstream,
            RouteCoordinate... coordinates) {
        double length = 0.0;
        for (int index = 1; index < coordinates.length; index++) {
            length += coordinates[index - 1].toCoordinate().distance(coordinates[index].toCoordinate());
        }
        return new RouteEdge(
                id, upstream, downstream, length,
                List.of(coordinates), List.of(), null, 100);
    }

    private RouteCoordinate point(double x, double y) {
        return new RouteCoordinate(x, y);
    }
}
