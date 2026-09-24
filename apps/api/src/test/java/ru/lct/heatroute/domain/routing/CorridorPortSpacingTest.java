package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/** Разбиение на сетку и ввод не скрывает короткую полку между двумя изгибами. */
class CorridorPortSpacingTest {
    private final GeometryFactory geometry = new GeometryFactory();

    @Test
    void millimetreGridPieceCannotConcealTheBendNextToATerminal() {
        RoutePath close = path(0, 0.005, 10, 0.005);
        RoutePath legal = path(0, 0.005, 0, 10, 10, 10, 10, 0.005);
        List<LineString> grid = List.of(line(-10, 0, 0, 0), line(0, 0, 0, 0.005));
        assertThat(CorridorJunctionAssignment.choosePortPaths(Map.of(3, close), Map.of(3, 2), grid,
                leaf -> List.of(close, legal))).containsEntry(3, legal);
    }

    @Test
    void repairsTheExtraBendCreatedByConcatenatingGridAndTerminalWithoutDroppingTheControl() {
        RoutePath close = path(0, 0, 0, 0.8, 10, 0.8);
        RoutePath legal = path(0, 0, 10, 0, 10, 0.8);
        List<Coordinate> points = List.of(new Coordinate(-4, 0), new Coordinate(), new Coordinate(10, 0.8));
        List<int[]> tree = List.of(new int[] {0, 1}, new int[] {1, 2});
        List<CorridorPortSearch.Selection> results = CorridorPortSearch.solveWithPaths(points, tree,
                List.of(4.0, close.lengthM()), 2, Map.of(2, Map.of(1, close)),
                (leaf, port) -> List.of(close, legal), (links, weights) -> tree);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).paths().get(2)).isSameAs(close);
        assertThat(results.get(1).paths().get(2)).isSameAs(legal);
    }

    @Test
    void straightPortDoesNotHideCloseBendsOnOppositeSidesOfTheJoin() {
        RoutePath close = path(0, 0, 0, 0.4, 10, 0.4);
        RoutePath legal = path(0, 0, 0, 10, 10, 10, 10, 0.4);
        List<LineString> grid = List.of(line(-4, -0.4, 0, -0.4), line(0, -0.4, 0, 0));
        Map<Integer, RoutePath> result = CorridorJunctionAssignment.choosePortPaths(Map.of(2, close),
                Map.of(2, 1), grid, leaf -> List.of(close, legal));
        assertThat(result).containsEntry(2, legal);
    }

    @Test
    void exactlyTwoMetresAndARealBranchChamberDoNotGetANewSpacingRestriction() {
        for (List<LineString> grid : List.of(List.of(line(-4, 0, 0, 0)),
                List.of(line(-4, 0, 0, 0), line(0, 0, 4, 0)))) {
            RoutePath legal = grid.size() == 1 ? path(0, 0, 0, 2, 10, 2) : path(0, 0, 0, 0.8, 10, 0.8);
            assertThat(CorridorJunctionAssignment.choosePortPaths(Map.of(2, legal), Map.of(2, 1), grid,
                    leaf -> { throw new AssertionError("Valid control needs no alternatives"); }))
                    .containsEntry(2, legal);
        }
    }

    private LineString line(double... xy) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new Coordinate(xy[i], xy[i + 1]));
        return geometry.createLineString(points.toArray(new Coordinate[0]));
    }

    private RoutePath path(double... xy) {
        LineString line = line(xy);
        return new RoutePath(List.of(line.getCoordinates()), List.of(), line.getLength());
    }
}
