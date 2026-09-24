package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;

class BuildingWallNormalsTest {
    private final BuildingWallNormals normals = new BuildingWallNormals();

    @Test
    void ordersRealWallsByDistanceAndPreservesNormal() throws Exception {
        Geometry building = polygon("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))");
        var candidates = normals.candidates(building, new Coordinate(2, 8), 5.2);
        assertThat(candidates).extracting(BuildingWallNormals.Exit::wallDistanceM)
                .containsExactly(2.0, 8.0, 12.0, 18.0);
        assertThat(candidates.get(0).point().equals2D(new Coordinate(-5.2, 8))).isTrue();
        candidates.get(0).point().x = 1234;
        assertThat(candidates.get(0).point().x).isEqualTo(-5.2);
    }

    @Test
    void ignoresRingStartAndWindingWhenWallsAreEquidistant() throws Exception {
        List<Coordinate> expected = exits("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))", new Coordinate(10, 10));
        assertThat(exits("POLYGON ((20 20, 20 0, 0 0, 0 20, 20 20))", new Coordinate(10, 10)))
                .extracting(point -> List.of(point.x, point.y))
                .containsExactlyElementsOf(expected.stream().map(point -> List.of(point.x, point.y))
                        .collect(Collectors.toList()));
    }

    @Test
    void rejectsReentryIntoAnotherWingOnConcaveFootprint() throws Exception {
        Geometry building = polygon("POLYGON ((0 0, 20 0, 20 20, 16 20, 16 4, 4 4, 4 20, 0 20, 0 0))");
        var candidates = normals.candidates(building, new Coordinate(2, 10), 15);
        assertThat(candidates).isNotEmpty();
        assertThat(candidates).noneMatch(exit -> exit.point().x > 4 && exit.point().y == 10);
    }

    @Test
    void checksHoleAndOtherMultipolygonPartBeforeAllowingFirstTurn() throws Exception {
        Geometry hole = polygon("POLYGON ((0 0, 30 0, 30 30, 0 30, 0 0),"
                + "(10 10, 10 20, 20 20, 20 10, 10 10))");
        assertThat(normals.candidates(hole, new Coordinate(8, 15), 6))
                .noneMatch(exit -> exit.point().x > 10 && exit.point().x < 20);
        Geometry parts = polygon("MULTIPOLYGON (((0 0, 5 0, 5 20, 0 20, 0 0)),"
                + "((8 0, 15 0, 15 20, 8 20, 8 0)))");
        assertThat(normals.candidates(parts, new Coordinate(4, 10), 5.2))
                .noneMatch(exit -> exit.point().x > 5 && exit.point().y == 10);
    }

    @Test
    void rotatesWithFootprintInsteadOfUsingGlobalAxes() throws Exception {
        Geometry building = polygon("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))");
        Coordinate start = new Coordinate(2, 8);
        Coordinate expected = normals.candidates(building, start, 5.2).get(0).point();
        AffineTransformation transform = AffineTransformation.rotationInstance(0.413);
        transform.translate(400_000, 6_170_000);
        Geometry rotated = transform.transform(building);
        var actual = normals.candidates(rotated, transform.transform(start, new Coordinate()), 5.2).get(0);
        assertThat(actual.point().distance(transform.transform(expected, new Coordinate()))).isLessThan(1e-7);
    }

    @Test
    void findsNearTangentClearanceWithoutAFixedIterationLimit() throws Exception {
        Geometry building = polygon("POLYGON ((-10 -1, 0 -1, 0 4.999, 10 4.999, 10 6, -10 6, -10 -1))");
        for (double shift : List.of(0.0, 400_000.0)) {
            AffineTransformation transform = AffineTransformation.translationInstance(shift, shift * 15);
            var candidates = normals.candidates(transform.transform(building),
                    new Coordinate(-1 + shift, shift * 15), 5);
            assertThat(candidates).anyMatch(exit -> Math.abs(exit.point().y - shift * 15) < 1e-7
                    && Math.abs(exit.point().x - shift - (10 + Math.sqrt(25 - 4.999 * 4.999))) < 1e-7);
        }
    }

    @Test
    void doesNotMergeAMillimetreWideLegalGapBetweenClearanceIntervals() throws Exception {
        Geometry building = polygon("MULTIPOLYGON (((-10 -10, 0 -10, 0 10, -10 10, -10 -10)),"
                + "((4 1, 8 1, 8 8, 4 8, 4 1)),"
                + "((17.899979486 -1, 30 -1, 30 1, 17.899979486 1, 17.899979486 -1)))");
        assertThat(normals.candidates(building, new Coordinate(-1, 0), 5))
                .anyMatch(exit -> Math.abs(exit.point().y) < 1e-7
                        && Math.abs(exit.point().x - (8 + Math.sqrt(24))) < 1e-7);
    }

    @Test
    void retainsAFirstTurnExactlyAtTheMinimumBetweenTouchingClearanceAreas() throws Exception {
        Geometry building = polygon("MULTIPOLYGON (((-10 -10, 0 -10, 0 10, -10 10, -10 -10)),"
                + "((4 3, 8 3, 8 8, 4 8, 4 3)),"
                + "((17 -1, 30 -1, 30 1, 17 1, 17 -1)))");
        assertThat(normals.candidates(building, new Coordinate(-1, 0), 5))
                .anyMatch(exit -> Math.abs(exit.point().x - 12) < 1e-7 && Math.abs(exit.point().y) < 1e-7);
    }

    @Test
    void rejectsEvenPointContactWithAnotherFootprintComponent() throws Exception {
        Geometry building = polygon("MULTIPOLYGON (((-10 -1, 0 -1, 0 1, -10 1, -10 -1)),"
                + "((2 0, 3 1, 1 1, 2 0)))");
        assertThat(normals.candidates(building, new Coordinate(-1, 0), 5))
                .noneMatch(exit -> exit.point().x > 0 && Math.abs(exit.point().y) < 1e-7);
    }

    @Test
    void supportsBoundaryAndRejectsInvalidParametersWithoutChangingInput() throws Exception {
        Geometry building = polygon("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))");
        Geometry copy = building.copy();
        assertThat(normals.candidates(building, new Coordinate(0, 8), 5.2).get(0).point().x).isEqualTo(-5.2);
        assertThat(normals.candidates(building, new Coordinate(-1, 8), 5.2)).isEmpty();
        assertThatThrownBy(() -> normals.candidates(building, new Coordinate(2, 8), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normals.candidates(building, new Coordinate(Double.NaN, 8), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(building.equalsExact(copy)).isTrue();
    }

    private List<Coordinate> exits(String wkt, Coordinate point) throws Exception {
        return normals.candidates(polygon(wkt), point, 5.2).stream()
                .map(BuildingWallNormals.Exit::point).collect(Collectors.toList());
    }

    private Geometry polygon(String wkt) throws Exception { return new WKTReader().read(wkt); }
}
