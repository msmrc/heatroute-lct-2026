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
    void tiltedFacadeCanReachAnAxialPortWithoutAShallowKink() {
        Coordinate point = new Coordinate(), exit = new Coordinate(1, 5), port = new Coordinate(30, 30);
        List<List<Coordinate>> alternatives = NormalCorridorTransitions.build(point, exit, port, 0, 2.1);
        assertThat(alternatives).isNotEmpty().hasSizeLessThanOrEqualTo(44);
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
}
