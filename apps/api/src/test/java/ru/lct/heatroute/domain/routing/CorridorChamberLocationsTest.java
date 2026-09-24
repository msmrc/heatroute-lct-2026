package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class CorridorChamberLocationsTest {
    @Test
    void includesAxisIntersectionsBesidesOldCamerasAndMidpoint() {
        List<Coordinate> points = CorridorChamberLocations.build(new Coordinate(0, 0), new Coordinate(10, 0),
                List.of(new Coordinate(-100, 0), new Coordinate(100, 0), new Coordinate(3, 100), new Coordinate(3, -100)), 0);
        assertThat(points).anyMatch(p -> p.distance(new Coordinate(3, 0)) < 1e-6);
        assertThat(points).hasSizeLessThanOrEqualTo(16).allMatch(p -> p.distance(new Coordinate(5, 0)) <= 40.000001);
    }

    @Test
    void hasSameCandidateSetUnderTranslationAndRotation() {
        List<Coordinate> outer = List.of(new Coordinate(-100, 0), new Coordinate(100, 0), new Coordinate(3, 100), new Coordinate(3, -100));
        List<Coordinate> expected = CorridorChamberLocations.build(new Coordinate(), new Coordinate(10, 0), outer, 0);
        for (double angle : new double[] {0.3, 1.3, 4.2}) {
            List<Coordinate> actual = CorridorChamberLocations.build(transform(new Coordinate(), angle), transform(new Coordinate(10, 0), angle),
                    outer.stream().map(p -> transform(p, angle)).collect(Collectors.toList()), angle);
            assertThat(actual).hasSameSizeAs(expected);
            assertThat(actual).allMatch(p -> expected.stream().anyMatch(e -> transform(e, angle).distance(p) < 1e-6));
        }
    }

    @Test
    void rejectsInvalidInputsAndHonorsCancellation() {
        assertThatThrownBy(() -> CorridorChamberLocations.build(new Coordinate(), new Coordinate(10, 0), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> CorridorChamberLocations.build(new Coordinate(), new Coordinate(10, 0),
                    List.of(new Coordinate(), new Coordinate(1, 1), new Coordinate(2, 2), new Coordinate(3, 3)), 0))
                    .isInstanceOf(CancellationException.class);
        } finally { Thread.interrupted(); }
    }

    private Coordinate transform(Coordinate point, double angle) {
        return new Coordinate(400000 + point.x * Math.cos(angle) - point.y * Math.sin(angle),
                6000000 + point.x * Math.sin(angle) + point.y * Math.cos(angle));
    }
}
