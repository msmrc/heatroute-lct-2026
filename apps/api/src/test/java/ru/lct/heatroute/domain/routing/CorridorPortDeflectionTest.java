package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

class CorridorPortDeflectionTest {
    private final GeometryFactory factory = new GeometryFactory();

    @Test
    void rejectsObtuseContinuationAtDegreeTwoPortInEitherGridDirection() {
        for (LineString grid : List.of(line(-10, 0, 0, 0), line(0, 0, -10, 0))) {
            assertThat(check(Map.of(5, path(0, 0, -1, 10)), Map.of(5, 7), List.of(grid)))
                    .containsExactly(5, -1);
            assertThat(check(Map.of(5, path(0, 0, 0, 10)), Map.of(5, 7), List.of(grid))).isNull();
            assertThat(check(Map.of(5, path(0, 0, 1, 10)), Map.of(5, 7), List.of(grid))).isNull();
        }
    }

    @Test
    void branchCameraDoesNotHaveTheDegreeTwoTurnRestriction() {
        assertThat(check(Map.of(5, path(0, 0, -1, 10)), Map.of(5, 7),
                List.of(line(-10, 0, 0, 0), line(0, 0, 10, 0)))).isNull();
        assertThat(check(Map.of(5, path(0, 0, -1, 10), 6, path(0, 0, 10, 0)),
                Map.of(5, 7, 6, 7), List.of(line(-10, 0, 0, 0)))).isNull();
    }

    @Test
    void twoStubsWithoutGridAreAThroughConnectionNotABranchCamera() {
        assertThat(check(Map.of(5, path(0, 0, 10, 0), 6, path(0, 0, 1, 10)),
                Map.of(5, 7, 6, 7), List.of())).containsExactly(5, 6);
        assertThat(check(Map.of(5, path(0, 0, 10, 0), 6, path(0, 0, -1, 10)),
                Map.of(5, 7, 6, 7), List.of())).isNull();
    }

    @Test
    void rejectsAnInternalIllegalTurnEvenWithoutCrossing() {
        assertThat(check(Map.of(5, path(0, 0, 10, 0, 9, 10)), Map.of(5, 7), List.of()))
                .containsExactly(5, -1);
        assertThat(check(Map.of(5, path(0, 0, 10, 0, 10, 10)), Map.of(5, 7), List.of())).isNull();
    }

    @Test
    void exactDuplicateSamplesDoNotHideIllegalJoins() {
        assertThat(check(Map.of(5, path(0, 0, 0, 0, -1, 10)), Map.of(5, 7),
                List.of(line(-10, 0, 0, 0, 0, 0)))).containsExactly(5, -1);
    }

    private int[] check(Map<Integer, RoutePath> paths, Map<Integer, Integer> attachments, List<LineString> grid) {
        return CorridorPortCompatibility.firstConflict(paths, attachments, grid);
    }

    private List<Coordinate> coordinates(double... xy) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new Coordinate(xy[i], xy[i + 1]));
        return points;
    }

    private RoutePath path(double... xy) {
        List<Coordinate> points = coordinates(xy);
        return new RoutePath(points, List.of(), line(xy).getLength());
    }

    private LineString line(double... xy) {
        return factory.createLineString(coordinates(xy).toArray(new Coordinate[0]));
    }
}
