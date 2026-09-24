package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

class CorridorJunctionAssignmentTest {
    @Test
    void choosesDifferentRaysInsteadOfTwoOverlappingShortestApproaches() {
        RoutePath north = path(0, 10, 0, 0);
        RoutePath shortOverlap = path(10, 10, 0, 10, 0, 0);
        RoutePath east = path(10, 10, 10, 0, 0, 0);
        List<RoutePath> selected = CorridorJunctionAssignment.choose(new Coordinate(), List.of(
                List.of(north), List.of(shortOverlap, east), List.of(path(-10, 0, 0, 0)), List.of(path(0, -10, 0, 0))));
        assertThat(selected).hasSize(4);
        assertThat(selected.get(0)).isSameAs(north);
        assertThat(selected.get(1)).isSameAs(east);
    }

    @Test
    void validFinalRaysCannotHideASecondCrossingElsewhere() {
        RoutePath crossing = path(-10, 5, 5, 5, 5, -5, 0, -5, 0, 0);
        RoutePath north = path(0, 10, 0, 0);
        assertThat(CorridorJunctionAssignment.choose(new Coordinate(), List.of(List.of(crossing), List.of(north)))).isNull();
    }

    @Test
    void checksRetainedEdgesBeforeChoosingAnOtherwiseCompatibleAssignment() {
        RoutePath candidate = path(0, 10, 0, 0);
        RouteEdge joined = edge("outer", "other", -10, 10, 0, 10);
        RouteEdge unrelated = edge("unrelated", "other", -10, 10, 0, 10);
        RouteEdge crossing = edge("a", "b", -10, 5, 10, 5);
        assertThat(CorridorJunctionAssignment.clearsRetained(candidate, "outer", List.of(joined))).isTrue();
        assertThat(CorridorJunctionAssignment.clearsRetained(candidate, "outer", List.of(unrelated))).isFalse();
        assertThat(CorridorJunctionAssignment.clearsRetained(candidate, "outer", List.of(crossing))).isFalse();
        assertThat(CorridorJunctionAssignment.clearsRetained(candidate, "outer", List.of(
                edge("outer", "other", 0, 10, 0, 5)))).isFalse();
    }

    @Test
    void rejectsAlmostParallelNonIntersectingApproachesAndAcceptsOppositeOnes() {
        assertThat(CorridorJunctionAssignment.choose(new Coordinate(), List.of(
                List.of(path(10, 0, 0, 0)), List.of(path(10, 0.5, 0, 0))))).isNull();
        assertThat(CorridorJunctionAssignment.choose(new Coordinate(), List.of(
                List.of(path(10, 0, 0, 0)), List.of(path(-10, 0, 0, 0))))).hasSize(2);
    }

    @Test
    void detectsSelfCrossingAndClosedPathsWithoutMutatingInputs() {
        RoutePath loop = path(0, 0, 10, 0, 10, 10, 0, 0);
        RoutePath crossing = path(-10, 0, 10, 10, -10, 10, 10, 0, 0, 0);
        List<Coordinate> before = List.copyOf(crossing.coordinates());
        assertThat(CorridorJunctionAssignment.choose(new Coordinate(), List.of(
                List.of(loop, crossing), List.of(path(0, -10, 0, 0))))).isNull();
        assertThat(crossing.coordinates()).containsExactlyElementsOf(before);
    }

    @Test
    void isInvariantUnderRotationAndTranslation() {
        for (double angle : new double[] {0.0, 0.3, 1.8, 3.9}) {
            Coordinate junction = new Coordinate(400000, 6000000);
            List<List<RoutePath>> choices = new java.util.ArrayList<>();
            for (double direction : new double[] {0, Math.PI / 2, Math.PI, 3 * Math.PI / 2}) {
                choices.add(List.of(path(junction.x + 10 * Math.cos(angle + direction),
                        junction.y + 10 * Math.sin(angle + direction), junction.x, junction.y)));
            }
            assertThat(CorridorJunctionAssignment.choose(junction, choices)).hasSize(4);
        }
    }

    @Test
    void validatesBoundsAndCancellation() {
        RoutePath north = path(0, 10, 0, 0);
        assertThatThrownBy(() -> CorridorJunctionAssignment.choose(new Coordinate(Double.NaN, 0),
                List.of(List.of(north), List.of(north)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorridorJunctionAssignment.choose(new Coordinate(), List.of(
                java.util.Collections.nCopies(9, north), List.of(north)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorridorJunctionAssignment.choose(new Coordinate(1, 1),
                List.of(List.of(north), List.of(north)))).isInstanceOf(IllegalArgumentException.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> CorridorJunctionAssignment.choose(new Coordinate(),
                    List.of(List.of(north), List.of(north)))).isInstanceOf(CancellationException.class);
        } finally { Thread.interrupted(); }
    }

    private RoutePath path(double... xy) {
        List<Coordinate> coordinates = new java.util.ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) coordinates.add(new Coordinate(xy[i], xy[i + 1]));
        double length = new GeometryFactory().createLineString(coordinates.toArray(new Coordinate[0])).getLength();
        return new RoutePath(coordinates, List.of(), length);
    }

    private RouteEdge edge(String from, String to, double... xy) {
        RoutePath path = path(xy);
        return new RouteEdge("fixed", from, to, path.lengthM(), path.coordinates().stream()
                .map(c -> new RouteCoordinate(c.x, c.y)).collect(java.util.stream.Collectors.toList()), List.of(), null, null);
    }
}
