package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class ChamberApproachCandidatesTest {
    private final ChamberApproachCandidates builder = new ChamberApproachCandidates();
    private final Coordinate junction = new Coordinate(11, -4);

    @Test
    void producesDistinctFourMeterApproachesWithLegalAnglesToAllThreeExistingRays() {
        List<Coordinate> rays = List.of(ray(0), ray(90), ray(180));
        List<Coordinate> result = builder.build(junction, rays, 4, 7.5);

        assertThat(result).hasSize(5);
        double[] expectedDirections = {45, 135, 225, 270, 315};
        for (int index = 0; index < expectedDirections.length; index++) {
            assertThat(result.get(index).distance(point(junction, expectedDirections[index], 4))).isLessThan(1e-8);
        }
        assertGeometry(result, junction, rays, 4, 7.5);
    }

    @Test
    void orderingAndDeduplicationDoNotDependOnInputOrderOrRayMagnitude() {
        List<Coordinate> rays = new ArrayList<>(List.of(new Coordinate(2, 0), new Coordinate(0, 9), new Coordinate(-3, 0)));
        List<Coordinate> result = builder.build(junction, rays, 4, 7.5);
        Collections.reverse(rays);
        List<Coordinate> reordered = builder.build(junction, rays, 4, 7.5);

        assertOrderedEqual(result, reordered);
        assertOrderedEqual(result, builder.build(junction, List.of(ray(0), ray(90), ray(180)), 4, 7.5));
        assertThat(result.stream().map(point -> angle(junction, point)).collect(Collectors.toList())).isSorted();
    }

    @Test
    void keepsAllDistinctLegalDirectionsForSlightlyNonOrthogonalExistingRays() {
        List<Coordinate> rays = List.of(ray(0), ray(89), ray(181));
        List<Coordinate> result = builder.build(junction, rays, 4, 7.5);

        assertThat(result).hasSize(15).hasSizeLessThanOrEqualTo(21);
        assertGeometry(result, junction, rays, 4, 7.5);
    }

    @Test
    void preservesCandidateSetsUnderRotationAndTranslation() {
        for (List<Coordinate> rays : List.of(List.of(ray(0), ray(90), ray(180)), List.of(ray(0), ray(89), ray(181)))) {
            List<Coordinate> original = builder.build(junction, rays, 4, 7.5);
            for (double rotation : new double[] {0, 0.17, 1.2, 3.14, -2.1}) {
                Coordinate movedJunction = moved(junction, rotation);
                List<Coordinate> rotatedRays = rays.stream().map(ray -> rotated(ray, rotation)).collect(Collectors.toList());
                List<Coordinate> actual = builder.build(movedJunction, rotatedRays, 4, 7.5);
                assertThat(actual).hasSize(original.size());
                for (Coordinate candidate : original) {
                    Coordinate expected = moved(candidate, rotation);
                    assertThat(actual).anySatisfy(point -> assertThat(point.distance(expected)).isLessThan(1e-7));
                }
                assertGeometry(actual, movedJunction, rotatedRays, 4, 7.5);
            }
        }
    }

    @Test
    void includesToleranceBoundaryAndRejectsJustOutsideIt() {
        Coordinate south = point(junction, 270, 4);
        List<Coordinate> exact = builder.build(junction, List.of(ray(0), ray(82.5), ray(180)), 4, 7.5);
        List<Coordinate> outside = builder.build(junction, List.of(ray(0), ray(82.499), ray(180)), 4, 7.5);

        assertThat(exact).anySatisfy(point -> assertThat(point.distance(south)).isLessThan(1e-8));
        assertThat(outside).allSatisfy(point -> assertThat(point.distance(south)).isGreaterThan(1e-8));
    }

    @Test
    void neverReturnsCoincidentRayEvenWithPermissiveTolerance() {
        List<Coordinate> rays = List.of(ray(0), ray(90), ray(180));
        List<Coordinate> result = builder.build(junction, rays, 4, 180);

        assertThat(result).isNotEmpty();
        for (Coordinate existing : rays) {
            Coordinate occupied = new Coordinate(junction.x + 4 * existing.x, junction.y + 4 * existing.y);
            assertThat(result).allSatisfy(point -> assertThat(point.distance(occupied)).isGreaterThan(1e-8));
        }
    }

    @Test
    void returnsEmptyWhenNoReferenceRaysOrNoFreeChamberSlotsRemain() {
        assertThat(builder.build(junction, List.of(), 4, 7.5)).isEmpty();
        assertThat(builder.build(junction, List.of(ray(0), ray(90), ray(180), ray(270)), 4, 7.5)).isEmpty();
        assertThat(builder.build(junction, List.of(ray(0), ray(45), ray(90), ray(180), ray(270)), 4, 7.5)).isEmpty();
    }

    @Test
    void returnsOnlyCandidatesSatisfyingEveryExistingRayAndSupportsExactTolerance() {
        assertThat(builder.build(junction, List.of(ray(0), ray(30), ray(180)), 4, 7.5)).isEmpty();
        List<Coordinate> rays = List.of(ray(0), ray(90), ray(180));
        List<Coordinate> exact = builder.build(junction, rays, 4, 0);
        assertThat(exact).hasSize(5);
        assertGeometry(exact, junction, rays, 4, 0);
    }

    @Test
    void rejectsZeroAndNonFiniteVectorsAndInvalidArguments() {
        for (Coordinate ray : Arrays.asList(null, new Coordinate(0, 0), new Coordinate(Double.NaN, 1),
                new Coordinate(1, Double.POSITIVE_INFINITY))) {
            assertThatThrownBy(() -> builder.build(junction, Arrays.asList(ray), 4, 7.5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> builder.build(null, List.of(ray(0)), 4, 7.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new Coordinate(Double.NaN, 0), List.of(ray(0)), 4, 7.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(junction, null, 4, 7.5)).isInstanceOf(IllegalArgumentException.class);
        for (double length : new double[] {0, -4, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> builder.build(junction, List.of(ray(0)), length, 7.5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (double tolerance : new double[] {-0.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> builder.build(junction, List.of(ray(0)), 4, tolerance))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void doesNotMutateOrRetainCallerCoordinates() {
        Coordinate origin = new Coordinate(junction);
        Coordinate existing = ray(0);
        List<Coordinate> first = builder.build(origin, List.of(existing), 4, 7.5);
        assertThat(first).hasSize(7);
        first.get(0).x = -1000;
        assertThat(origin.equals2D(junction)).isTrue();
        assertThat(existing.equals2D(ray(0))).isTrue();
        assertThat(builder.build(origin, List.of(existing), 4, 7.5).get(0).x).isNotEqualTo(-1000);
    }

    private void assertGeometry(List<Coordinate> result, Coordinate origin, List<Coordinate> rays,
            double length, double tolerance) {
        for (int index = 0; index < result.size(); index++) {
            Coordinate candidate = result.get(index);
            assertThat(Math.abs(candidate.distance(origin) - length)).isLessThan(1e-7);
            for (int other = index + 1; other < result.size(); other++) {
                assertThat(candidate.distance(result.get(other))).isGreaterThan(1e-8);
            }
            for (Coordinate ray : rays) {
                double direction = Math.atan2(candidate.y - origin.y, candidate.x - origin.x);
                double existing = Math.atan2(ray.y, ray.x);
                double separation = Math.toDegrees(Math.abs(Math.IEEEremainder(direction - existing, Math.PI * 2)));
                assertThat(separation).isGreaterThan(1e-7);
                double closest = Double.POSITIVE_INFINITY;
                for (double standard : new double[] {45, 90, 135, 180}) closest = Math.min(closest, Math.abs(separation - standard));
                assertThat(closest).isLessThanOrEqualTo(tolerance + 1e-7);
            }
        }
    }

    private void assertOrderedEqual(List<Coordinate> left, List<Coordinate> right) {
        assertThat(right).hasSize(left.size());
        for (int index = 0; index < left.size(); index++) assertThat(left.get(index).distance(right.get(index))).isLessThan(1e-8);
    }

    private Coordinate ray(double degrees) {
        return new Coordinate(Math.cos(Math.toRadians(degrees)), Math.sin(Math.toRadians(degrees)));
    }

    private Coordinate point(Coordinate origin, double degrees, double length) {
        Coordinate direction = ray(degrees);
        return new Coordinate(origin.x + direction.x * length, origin.y + direction.y * length);
    }

    private double angle(Coordinate origin, Coordinate point) {
        double angle = Math.toDegrees(Math.atan2(point.y - origin.y, point.x - origin.x));
        return angle < 0 ? angle + 360 : angle;
    }

    private Coordinate rotated(Coordinate coordinate, double rotation) {
        return new Coordinate(coordinate.x * Math.cos(rotation) - coordinate.y * Math.sin(rotation),
                coordinate.x * Math.sin(rotation) + coordinate.y * Math.cos(rotation));
    }

    private Coordinate moved(Coordinate coordinate, double rotation) {
        Coordinate result = rotated(coordinate, rotation);
        return new Coordinate(result.x + 410000, result.y + 6180000);
    }
}
