package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EngineeringRouteEvaluatorTest {
    private final EngineeringRouteEvaluator evaluator = new EngineeringRouteEvaluator();

    @Test
    void acceptsNinetyAndOneHundredThirtyFiveDegreeInternalAngles() {
        RouteEdge edge = edge(
                point(0, 0),
                point(2, 0),
                point(2, 2),
                point(4, 4));

        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(List.of(edge));

        assertThat(result.isCompliant()).isTrue();
        assertThat(result.invalidAngleCount()).isZero();
        assertThat(result.insufficientSpacingCount()).isZero();
    }

    @Test
    void rejectsAngleOutsideRangeAndConsecutiveBendsCloserThanTwoMetres() {
        RouteEdge edge = edge(
                point(0, 0),
                point(1, 0),
                point(1.5, 0.2),
                point(3, 2));

        EngineeringRouteEvaluator.Evaluation result = evaluator.evaluate(List.of(edge));

        assertThat(result.isCompliant()).isFalse();
        assertThat(result.invalidAngleCount()).isPositive();
        assertThat(result.insufficientSpacingCount()).isEqualTo(1);
        assertThat(result.nonCompliantEdgeIds()).containsExactly("edge");
    }

    @Test
    void prefersCanonicalNinetyOrOneHundredThirtyFiveDegreeBendsInsideAllowedRange() {
        EngineeringRouteEvaluator.Evaluation canonical = evaluator.evaluate(List.of(edge(
                point(0, 0),
                point(2, 0),
                point(2, 2))));
        EngineeringRouteEvaluator.Evaluation intermediate = evaluator.evaluate(List.of(edge(
                point(0, 0),
                point(2, 0),
                point(3, 1.7320508075688772))));

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
