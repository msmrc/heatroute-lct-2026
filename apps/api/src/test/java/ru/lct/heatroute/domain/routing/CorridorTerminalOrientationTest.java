package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class CorridorTerminalOrientationTest {
    @Test
    void choosesObservedDenseFamilyAndNotTheMeanBetweenFamilies() {
        double result = CorridorTerminalOrientation.alternative(rad(41), families(0)).orElseThrow();
        assertThat(Math.toDegrees(result)).isCloseTo(40.04, org.assertj.core.data.Offset.offset(1e-8));
    }

    @Test
    void quarterTurnsRotationAndInputPermutationsPreserveTheChosenFamily() {
        for (double rotation : new double[] {0, 55, 110, 225, -65}) {
            List<List<Double>> input = families(rotation);
            double expected = CorridorTerminalOrientation.alternative(rad(41 + rotation), input).orElseThrow();
            Collections.reverse(input);
            for (List<Double> family : input) Collections.reverse(family);
            double actual = CorridorTerminalOrientation.alternative(rad(41 + rotation), input).orElseThrow();
            assertThat(Math.abs(Math.sin(2 * (expected - actual)))).isLessThan(1e-10);
            assertThat(Math.abs(Math.sin(2 * (actual - rad(40.04 + rotation))))).isLessThan(1e-10);
        }
    }

    @Test
    void duplicatesOfOneTerminalDoNotArtificiallyIncreaseItsSupport() {
        List<List<Double>> input = families(0);
        input.get(0).addAll(Collections.nCopies(50, rad(41)));
        assertThat(CorridorTerminalOrientation.alternative(rad(41), input).orElseThrow())
                .isEqualTo(CorridorTerminalOrientation.alternative(rad(41), families(0)).orElseThrow());
    }

    @Test
    void noNearDuplicateFrameOrUnrelatedFamilyIsAdded() {
        assertThat(CorridorTerminalOrientation.alternative(rad(40.04), families(0))).isEmpty();
        assertThat(CorridorTerminalOrientation.alternative(rad(0), List.of(List.of(rad(45))))).isEmpty();
        assertThat(CorridorTerminalOrientation.alternative(0, List.of())).isEmpty();
    }

    @Test
    void enforcesFiniteInputsAndBoundedFamilies() {
        assertThatThrownBy(() -> CorridorTerminalOrientation.alternative(Double.NaN, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorridorTerminalOrientation.alternative(0, List.of(List.of(Double.POSITIVE_INFINITY)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorridorTerminalOrientation.alternative(0, Collections.nCopies(65, List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorridorTerminalOrientation.alternative(0, List.of(Collections.nCopies(65, 0.0)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(CorridorTerminalOrientation.alternative(0, Collections.nCopies(64, Collections.nCopies(64, 0.0)))).isEmpty();
    }

    @Test
    void interruptionIsNotCleared() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> CorridorTerminalOrientation.alternative(0, families(0))).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void gridHonorsExplicitFrameEvenWhenFallbackWouldChooseAnotherDirection() {
        double angle = rad(23);
        OrthogonalCorridorGrid grid = OrthogonalCorridorGrid.build(new Coordinate(0, 0),
                List.of(new Coordinate(100, 0), new Coordinate(40, 80)), List.of(), 5,
                p -> true, (a, b) -> true, angle);
        assertThat(grid.links()).isNotEmpty();
        for (int[] link : grid.links()) {
            Coordinate a = grid.points().get(link[0]), b = grid.points().get(link[1]);
            assertThat(Math.abs(Math.sin(2 * (Math.atan2(b.y - a.y, b.x - a.x) - angle)))).isLessThan(1e-10);
        }
        assertThatThrownBy(() -> OrthogonalCorridorGrid.build(new Coordinate(0, 0), List.of(), List.of(), 5,
                p -> true, (a, b) -> true, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    private List<List<Double>> families(double rotation) {
        List<List<Double>> result = new ArrayList<>();
        for (double axis : new double[] {40, 40.04, 40.08}) {
            result.add(new ArrayList<>(List.of(rad(axis + rotation), rad(axis + rotation + 90))));
        }
        result.get(0).add(rad(41 + rotation));
        return result;
    }

    private double rad(double degrees) { return Math.toRadians(degrees); }
}
