package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

class NormalCorridorTransitionsTest {
    @Test
    void alignedNormalsRetainOnlyAxialTransitionsUnderRotation() {
        for (double angle : new double[] {0, 0.2, 1.1, 3.9}) {
            Coordinate start = transform(new Coordinate(), angle);
            Coordinate exit = transform(new Coordinate(-6, 0), angle);
            Coordinate port = transform(new Coordinate(-30, -25), angle);
            var paths = NormalCorridorTransitions.build(start, exit, port, angle, 2.1);
            assertThat(paths).isNotEmpty().hasSizeLessThanOrEqualTo(44);
            for (var path : paths) {
                for (int i = 1; i < path.size(); i++) {
                    double dx = path.get(i).x - path.get(i - 1).x;
                    double dy = path.get(i).y - path.get(i - 1).y;
                    double along = dx * Math.cos(angle) + dy * Math.sin(angle);
                    double across = -dx * Math.sin(angle) + dy * Math.cos(angle);
                    assertThat(Math.min(Math.abs(along), Math.abs(across))).isLessThan(1e-6);
                }
            }
        }
    }

    @Test
    void diagonalMiddleLegConnectsAnOppositeSidePortWithTwoBends() {
        for (double degrees : new double[] {5, 13, 27, 41}) {
            double angle = Math.toRadians(degrees);
            Coordinate start = new Coordinate(), exit = new Coordinate(-6 * Math.cos(angle), -6 * Math.sin(angle));
            Coordinate port = new Coordinate(-40, 35);
            var paths = NormalCorridorTransitions.build(start, exit, port, 0, 2.1);
            assertThat(paths).as("tilted normal %s degrees", degrees).isNotEmpty().hasSizeLessThanOrEqualTo(84);
            double s = degrees <= 13 ? 5 : 10;
            double t = (5 + s) / (Math.cos(angle) + Math.sin(angle));
            double expectedLength = t + Math.sqrt(2) * (40 - t * Math.cos(angle)) + s;
            assertThat(paths).anySatisfy(path -> {
                assertThat(path).hasSize(4);
                var points = new ArrayList<>(List.of(start));
                points.addAll(path);
                assertThat(lineLength(points)).isCloseTo(expectedLength, org.assertj.core.data.Offset.offset(1e-8));
                var evaluation = new EngineeringRouteEvaluator().evaluate(List.of(edge(points)));
                assertThat(evaluation.isCompliant()).isTrue();
                assertThat(evaluation.bendCount()).isEqualTo(2);
            });
        }
    }

    @Test
    void diagonalExtensionPreservesPreviouslyAvailableAxialControls() {
        Coordinate start = new Coordinate(), exit = new Coordinate(1, 5), port = new Coordinate(30, 30);
        var paths = NormalCorridorTransitions.build(start, exit, port, 0, 2.1);
        assertThat(paths).anySatisfy(path -> assertCoordinates(path, List.of(exit, new Coordinate(6, 30), port)));
        for (double s : new double[] {2.1, 5, 10, 20}) {
            assertThat(paths).anySatisfy(path -> assertCoordinates(path, List.of(exit,
                    new Coordinate((30 - s) / 5, 30 - s), new Coordinate(30, 30 - s), port)));
        }
    }

    @Test
    void diagonalCandidatesHaveTheSameGeometryAfterReflection() {
        for (double degrees : new double[] {5, 13, 27, 41}) {
            double angle = Math.toRadians(degrees);
            Coordinate start = new Coordinate(), exit = new Coordinate(-6 * Math.cos(angle), -6 * Math.sin(angle));
            Coordinate port = new Coordinate(-40, 35);
            var control = NormalCorridorTransitions.build(start, exit, port, 0, 2.1);
            var reflected = NormalCorridorTransitions.build(start, new Coordinate(exit.x, -exit.y),
                    new Coordinate(port.x, -port.y), 0, 2.1);
            assertThat(reflected).isNotEmpty().hasSameSizeAs(control);
            for (var path : control) {
                var expected = path.stream().map(p -> new Coordinate(p.x, -p.y)).collect(Collectors.toList());
                assertThat(reflected).anySatisfy(candidate -> assertCoordinates(candidate, expected));
            }
        }
    }

    @Test
    void tiltedFacadeCanReachAnAxialPortWithoutAShallowKink() {
        Coordinate point = new Coordinate(), exit = new Coordinate(1, 5), port = new Coordinate(30, 30);
        List<List<Coordinate>> alternatives = NormalCorridorTransitions.build(point, exit, port, 0, 2.1);
        assertThat(alternatives).isNotEmpty().hasSizeLessThanOrEqualTo(84);
        for (List<Coordinate> outside : alternatives) {
            assertThat(outside.get(0)).isEqualTo(exit);
            assertThat(outside.get(outside.size() - 1)).isEqualTo(port);
            Coordinate next = outside.get(1);
            assertThat((next.x - exit.x) * 5 - (next.y - exit.y)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-8));
            assertThat(next.distance(point)).isGreaterThan(exit.distance(point));
            Coordinate before = outside.get(outside.size() - 2);
            assertThat(Math.abs(before.x - port.x) < 1e-8 || Math.abs(before.y - port.y) < 1e-8).isTrue();
            List<Coordinate> all = new ArrayList<>(List.of(point)); all.addAll(outside);
            double length = new GeometryFactory().createLineString(all.toArray(new Coordinate[0])).getLength();
            RouteEdge edge = new RouteEdge("path", "d", "j", length,
                    all.stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()), List.of(), null, 100);
            assertThat(new EngineeringRouteEvaluator().evaluate(List.of(edge)).isCompliant()).isTrue();
        }
    }

    @Test
    void rotationAndTranslationPreserveTheGeometrySet() {
        Coordinate start = new Coordinate(), exit = new Coordinate(1, 5), port = new Coordinate(30, 30);
        List<List<Coordinate>> control = NormalCorridorTransitions.build(start, exit, port, 0, 2.1);
        for (double angle : new double[] {0.2, 1.1, 3.9}) {
            List<List<Coordinate>> rotated = NormalCorridorTransitions.build(transform(start, angle), transform(exit, angle),
                    transform(port, angle), angle, 2.1);
            assertThat(rotated).hasSameSizeAs(control);
            for (int i = 0; i < control.size(); i++) {
                for (int j = 0; j < control.get(i).size(); j++) {
                    assertThat(rotated.get(i).get(j).distance(transform(control.get(i).get(j), angle))).isLessThan(1e-6);
                }
            }
        }
    }

    @Test
    void rejectsInvalidInputsAndCancelsBeforeSearch() {
        assertThatThrownBy(() -> NormalCorridorTransitions.build(new Coordinate(), new Coordinate(0, 5),
                new Coordinate(20, 20), 0, 1.99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NormalCorridorTransitions.build(new Coordinate(), new Coordinate(0, 5),
                new Coordinate(Double.NaN, 20), 0, 2.1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(NormalCorridorTransitions.build(new Coordinate(), new Coordinate(), new Coordinate(20, 20), 0, 2.1)).isEmpty();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> NormalCorridorTransitions.build(new Coordinate(), new Coordinate(0, 5),
                    new Coordinate(20, 20), 0, 2.1)).isInstanceOf(CancellationException.class);
        } finally { Thread.interrupted(); }
    }

    private Coordinate transform(Coordinate point, double angle) {
        return new Coordinate(400000 + point.x * Math.cos(angle) - point.y * Math.sin(angle),
                6000000 + point.x * Math.sin(angle) + point.y * Math.cos(angle));
    }

    private void assertCoordinates(List<Coordinate> actual, List<Coordinate> expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) assertThat(actual.get(i).distance(expected.get(i))).isLessThan(1e-8);
    }

    private double lineLength(List<Coordinate> points) {
        return new GeometryFactory().createLineString(points.toArray(new Coordinate[0])).getLength();
    }

    private RouteEdge edge(List<Coordinate> points) {
        return new RouteEdge("path", "d", "j", lineLength(points), points.stream()
                .map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()), List.of(), null, 100);
    }
}
