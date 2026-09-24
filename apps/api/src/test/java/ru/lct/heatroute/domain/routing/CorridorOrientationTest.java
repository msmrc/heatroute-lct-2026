package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;

class CorridorOrientationTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final Coordinate root = new Coordinate(0, 0);
    private final List<Coordinate> terminals = List.of(new Coordinate(10, 30));

    @Test
    void selectsLargestLengthWeightedClusterInsteadOfAveragingCompetingSeventyAndEightyDegreeBlocks() {
        List<Geometry> footprints = List.of(rectangle(70, 100, 10), rectangle(80, 50, 10));

        assertAngle(CorridorOrientation.angle(footprints, terminals, root), 70);
    }

    @Test
    void wallLengthNotBuildingCountControlsTheWinningCluster() {
        List<Geometry> footprints = List.of(rectangle(70, 100, 10), rectangle(80, 20, 5),
                rectangle(80, 20, 5), rectangle(80, 20, 5));

        assertAngle(CorridorOrientation.angle(footprints, terminals, root), 70);
    }

    @Test
    void nonpolygonalFeaturesCannotBiasTheFacadeClusterEvenWhenMuchLonger() {
        Geometry line = factory.createLineString(new Coordinate[] {
            new Coordinate(0, 0), new Coordinate(100000, 0)
        });
        Geometry multiline = factory.createMultiLineString(new org.locationtech.jts.geom.LineString[] {
            (org.locationtech.jts.geom.LineString) line
        });
        assertAngle(CorridorOrientation.angle(List.of(line, multiline, rectangle(70, 100, 10)), terminals, root), 70);
        assertAngle(CorridorOrientation.angle(List.of(line, multiline), terminals, root),
                Math.toDegrees(Math.atan2(30, 10)));
    }

    @Test
    void sumsClusterWeightsAndComputesMeanOnlyInsideTheWinningCluster() {
        List<Geometry> footprints = List.of(rectangle(69, 100, 10), rectangle(71, 90, 10), rectangle(80, 50, 10));
        double expected = weightedMeanDegrees(new double[] {69, 71}, new double[] {220, 200});

        assertAngle(CorridorOrientation.angle(footprints, terminals, root), expected);
        // Два близких направления вместе сильнее самого длинного одиночного прямоугольника.
        assertAngle(CorridorOrientation.angle(List.of(rectangle(70, 100, 10),
                rectangle(79, 70, 10), rectangle(81, 70, 10)), terminals, root), 80);
    }

    @Test
    void sixDegreesIsTheFullInclusiveWindowNotASixDegreeRadius() {
        Geometry competitor = rectangle(80, 170, 10);
        assertAngle(CorridorOrientation.angle(List.of(rectangle(67, 100, 10),
                rectangle(73, 100, 10), competitor), terminals, root), 70);
        assertAngle(CorridorOrientation.angle(List.of(rectangle(67, 100, 10),
                rectangle(73.1, 100, 10), competitor), terminals, root), 80);
    }

    @Test
    void clusterCrossingModuloNinetyBoundaryUsesTheCircularMean() {
        assertAngle(CorridorOrientation.angle(List.of(rectangle(89, 100, 10),
                rectangle(1, 100, 10), rectangle(45, 20, 5)), terminals, root), 0);
        double expected = weightedMeanDegrees(new double[] {89, 1}, new double[] {220, 120});
        assertAngle(CorridorOrientation.angle(List.of(rectangle(89, 100, 10), rectangle(1, 50, 10)),
                terminals, root), expected);
    }

    @Test
    void permutationDoesNotChangeTheChosenAngleOrMutateGeometriesAndCoordinates() {
        List<Geometry> footprints = new ArrayList<>(List.of(rectangle(67, 100, 10),
                rectangle(69, 90, 10), rectangle(79, 50, 10), rectangle(81, 30, 10)));
        List<Geometry> snapshots = footprints.stream().map(Geometry::copy).collect(Collectors.toList());
        List<Geometry> originalOrder = List.copyOf(footprints);
        List<Coordinate> shuffledTerminals = new ArrayList<>(List.of(new Coordinate(10, 30), new Coordinate(-20, 3)));
        double expected = CorridorOrientation.angle(footprints, shuffledTerminals, root);
        Random random = new Random(682026);
        for (int repeat = 0; repeat < 30; repeat++) {
            Collections.shuffle(footprints, random);
            Collections.shuffle(shuffledTerminals, random);
            assertThat(CorridorOrientation.angle(footprints, shuffledTerminals, root)).isEqualTo(expected);
        }
        for (int index = 0; index < snapshots.size(); index++) {
            assertThat(originalOrder.get(index).equalsExact(snapshots.get(index))).isTrue();
        }
        assertThat(root).isEqualTo(new Coordinate(0, 0));
        assertThat(shuffledTerminals).containsExactlyInAnyOrder(new Coordinate(10, 30), new Coordinate(-20, 3));
    }

    @Test
    void uniqueDominantClusterRotatesAndTranslatesWithTheSceneIncludingWrap() {
        List<Geometry> footprints = List.of(rectangle(67, 100, 10), rectangle(69, 90, 10), rectangle(79, 50, 10));
        double original = CorridorOrientation.angle(footprints, terminals, root);
        for (double degrees : new double[] {-81, -13, 0, 20, 45, 90, 173}) {
            double rotation = Math.toRadians(degrees);
            AffineTransformation transform = new AffineTransformation(Math.cos(rotation), -Math.sin(rotation), 600000,
                    Math.sin(rotation), Math.cos(rotation), 6000000);
            List<Geometry> changed = footprints.stream().map(transform::transform).collect(Collectors.toList());
            List<Coordinate> changedTerminals = terminals.stream().map(point -> transform.transform(point, new Coordinate()))
                    .collect(Collectors.toList());
            Coordinate changedRoot = transform.transform(root, new Coordinate());
            assertAngle(CorridorOrientation.angle(changed, changedTerminals, changedRoot),
                    Math.toDegrees(original) + degrees);
        }
    }

    @Test
    void isotropicAndDiffuseDirectionsFallBackToTheFarthestTerminal() {
        Coordinate offsetRoot = new Coordinate(10, -30);
        List<Coordinate> targets = List.of(new Coordinate(12, -10), new Coordinate(110, 20));
        List<Geometry> isotropic = new ArrayList<>();
        for (int degrees = 0; degrees < 90; degrees += 15) isotropic.add(rectangle(degrees, 100, 10));
        double expected = Math.toDegrees(Math.atan2(50, 100));
        assertAngle(CorridorOrientation.angle(isotropic, targets, offsetRoot), expected);
        List<Geometry> diffuse = new ArrayList<>();
        for (int degrees = 0; degrees < 90; degrees += 3) diffuse.add(rectangle(degrees, 15, 5));
        diffuse.add(rectangle(71, 4, 1));
        assertAngle(CorridorOrientation.angle(diffuse, targets, offsetRoot), expected);
    }

    @Test
    void emptyDegenerateAndNonfiniteDirectionsDoNotPolluteFallbackOrAValidCluster() {
        Geometry invalid = factory.createLineString(new Coordinate[] {new Coordinate(Double.NaN, 0), new Coordinate(1, 2)});
        Geometry repeated = factory.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(0, 0)});
        List<Geometry> unusable = Arrays.asList(null, factory.createPolygon(), factory.createPoint(new Coordinate(1, 1)),
                invalid, repeated);
        assertAngle(CorridorOrientation.angle(unusable, terminals, root), Math.toDegrees(Math.atan2(30, 10)));
        List<Geometry> mixed = new ArrayList<>(unusable);
        mixed.add(rectangle(70, 100, 10));
        assertAngle(CorridorOrientation.angle(mixed, terminals, root), 70);
        assertAngle(CorridorOrientation.angle(List.of(), Arrays.asList(null, new Coordinate(Double.POSITIVE_INFINITY, 1),
                root.copy()), root), 0);
    }

    @Test
    void farthestTerminalTiesUseCoordinateOrderAndFallbackIsRotationEquivalentWithoutTies() {
        Coordinate lowX = new Coordinate(6, 8), highX = new Coordinate(8, 6);
        double expected = Math.toDegrees(Math.atan2(8, 6));
        assertAngle(CorridorOrientation.angle(List.of(), List.of(lowX, highX), root), expected);
        assertAngle(CorridorOrientation.angle(List.of(), List.of(highX, lowX), root), expected);
        double rotation = Math.toRadians(31);
        Coordinate far = new Coordinate(10 * Math.cos(rotation), 10 * Math.sin(rotation));
        assertAngle(CorridorOrientation.angle(List.of(), List.of(new Coordinate(1, 0), far), root), 31);
    }

    @Test
    void boundsFootprintAndCoordinateWorkWithoutUsingAPartialBiasedSample() {
        Geometry footprint = rectangle(70, 100, 10);
        double expected = Math.toDegrees(Math.atan2(30, 10));
        assertAngle(CorridorOrientation.angle(Collections.nCopies(25001, footprint), terminals, root), expected);
        Geometry[] repeated = new Geometry[200001];
        Arrays.fill(repeated, footprint);
        Geometry oversized = factory.createGeometryCollection(repeated);
        assertAngle(CorridorOrientation.angle(List.of(footprint, oversized), terminals, root), expected);
    }

    @Test
    void rejectsMissingInputsAndInvalidRoot() {
        assertThatThrownBy(() -> CorridorOrientation.angle(null, terminals, root)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorridorOrientation.angle(List.of(), null, root)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorridorOrientation.angle(List.of(), terminals, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorridorOrientation.angle(List.of(), terminals, new Coordinate(Double.NaN, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationIsPropagatedAndInterruptStatusIsPreserved() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> CorridorOrientation.angle(List.of(), terminals, root))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        Geometry footprint = rectangle(70, 100, 10);
        List<Geometry> interrupting = new AbstractList<>() {
            @Override public Geometry get(int index) {
                if (index == 1) Thread.currentThread().interrupt();
                return footprint;
            }
            @Override public int size() { return 4; }
        };
        try {
            assertThatThrownBy(() -> CorridorOrientation.angle(interrupting, terminals, root))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private Geometry rectangle(double degrees, double width, double height) {
        Coordinate[] coordinates = {new Coordinate(0, 0), new Coordinate(width, 0), new Coordinate(width, height),
            new Coordinate(0, height), new Coordinate(0, 0)};
        double angle = Math.toRadians(degrees);
        for (Coordinate point : coordinates) {
            double x = point.x, y = point.y;
            point.x = x * Math.cos(angle) - y * Math.sin(angle);
            point.y = x * Math.sin(angle) + y * Math.cos(angle);
        }
        return factory.createPolygon(coordinates);
    }

    private double weightedMeanDegrees(double[] degrees, double[] weights) {
        double cosine = 0, sine = 0;
        for (int index = 0; index < degrees.length; index++) {
            cosine += weights[index] * Math.cos(4 * Math.toRadians(degrees[index]));
            sine += weights[index] * Math.sin(4 * Math.toRadians(degrees[index]));
        }
        return Math.toDegrees(Math.atan2(sine, cosine) / 4);
    }

    private void assertAngle(double actual, double expectedDegrees) {
        assertThat(actual).isFinite().isGreaterThanOrEqualTo(0).isLessThan(Math.PI / 2);
        double difference = Math.abs((Math.toDegrees(actual) - expectedDegrees) % 90);
        difference = Math.min(difference, 90 - difference);
        assertThat(difference).as("actual=%s° expected=%s°", Math.toDegrees(actual), expectedDegrees).isLessThan(1e-6);
    }
}
