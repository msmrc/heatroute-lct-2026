package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.optimization.CpSatChoiceOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;

/** Геометрический N04-срез рядом с CorridorPortSearch, без включения в stable planner. */
class CpSatPortAssignmentTest {
    private final GeometryFactory geometry = new GeometryFactory();

    @Test
    void exactPairConflictKeepsTheFirstPathAndChangesOnlyItsPartner() {
        RoutePath north = path(0, 0, 0, 10);
        RoutePath overlap = path(0, 0, 0, 10, 10, 10);
        RoutePath east = path(0, 0, 10, 0, 10, 10);
        List<LineString> grid = List.of(line(-10, 0, 0, 0));

        CpSatPortAssignment.Result result = CpSatPortAssignment.solve(
                Map.of(2, List.of(north), 3, List.of(overlap, east)),
                Map.of(2, 1, 3, 1), grid, new CpSatRuntime(), 5.0);

        assertThat(result.status()).isEqualTo(CpSatChoiceOptimizer.Status.OPTIMAL);
        assertThat(result.paths()).containsEntry(2, north).containsEntry(3, east);
        assertThat(result.learnedConflicts()).isEqualTo(1);
        assertThat(CorridorPortCompatibility.firstConflict(result.paths(), Map.of(2, 1, 3, 1), grid)).isNull();
        assertThat(CorridorJunctionAssignment.evaluatePortGeometry(result.paths(), grid).isCompliant()).isTrue();
    }

    @Test
    void unarySelfConflictRemovesOnlyTheBrokenGeometry() {
        RoutePath loop = path(0, 0, 5, 0, 5, 5, 0, 0, 10, 0);
        RoutePath straight = path(0, 0, 0, -20, 10, -20, 10, 0);

        CpSatPortAssignment.Result result = CpSatPortAssignment.solve(
                Map.of(2, List.of(loop, straight)), Map.of(2, 1), List.of(line(-10, 0, 0, 0)),
                new CpSatRuntime(), 5.0);

        assertThat(result.status()).isEqualTo(CpSatChoiceOptimizer.Status.OPTIMAL);
        assertThat(result.paths()).containsEntry(2, straight);
        assertThat(result.learnedConflicts()).isEqualTo(1);
    }

    private RoutePath path(double... xy) {
        LineString line = line(xy);
        return new RoutePath(List.of(line.getCoordinates()), List.of(), line.getLength());
    }

    private LineString line(double... xy) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (int index = 0; index < xy.length; index += 2) {
            coordinates.add(new Coordinate(xy[index], xy[index + 1]));
        }
        return geometry.createLineString(coordinates.toArray(new Coordinate[0]));
    }
}
