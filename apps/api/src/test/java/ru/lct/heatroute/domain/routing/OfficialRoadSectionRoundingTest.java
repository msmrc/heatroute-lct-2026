package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;

/** Округление split-точек допустимо лишь при строгой исходной оси, без ослабления отступов. */
class OfficialRoadSectionRoundingTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final RoadCrossingClearance guard = new RoadCrossingClearance();
    private final Geometry road = factory.createPolygon(new Coordinate[] {
            c(0, 0), c(100, 0), c(100, 6), c(0, 6), c(0, 0)});

    @Test void roundingOfObliqueSplitCoordinatesPreservesValidCollinearCrossings() {
        for (int dx = 1; dx <= 26; dx++) {
            for (double shift : new double[] {0, 0.123, 0.777}) {
                LineString original = line(c(40 + shift, -10), c(40 + shift + dx / 2.0, 3), c(40 + shift + dx, 16));
                var expected = guard.assess(original, road, 1.755, 45, 3);
                assertThat(expected.isAllowed()).as("dx=%s shift=%s %s", dx, shift, expected.getFailureCode()).isTrue();
                var span = expected.getIntervals().get(0);
                LengthIndexedLine indexed = new LengthIndexedLine(original);
                LineString emitted = line(original.getCoordinateN(0), rounded(indexed.extractPoint(span.getStartM())),
                        original.getCoordinateN(1), rounded(indexed.extractPoint(span.getEndM())), original.getCoordinateN(2));
                var actual = guard.assessRoundedSections(original, emitted, road, 1.755, 45, 3);
                assertThat(actual.isAllowed()).as("dx=%s shift=%s %s", dx, shift, actual.getFailureCode()).isTrue();
            }
        }
    }

    @Test void roundingPermissionDoesNotRelaxOutsideClearanceEvenWithinPointQuantizationBound() {
        LineString original = line(c(10, 7.755), c(90, 7.755));
        LineString shifted = line(c(10, 7.7545), c(90, 7.7545));
        assertThat(guard.assess(original, road, 1.755, 45, 3).isAllowed()).isTrue();
        assertThat(guard.assessRoundedSections(original, shifted, road, 1.755, 45, 3).getFailureCode())
                .isEqualTo("SPECIAL_PARALLEL_CLEARANCE_VIOLATION");
    }

    @Test void invalidOriginalOrExcessiveSectionDisplacementCannotUseRoundingAllowance() {
        LineString invalid = line(c(10, 7.7), c(90, 7.7));
        assertThat(guard.assessRoundedSections(invalid, invalid, road, 1.755, 45, 3).isAllowed()).isFalse();
        LineString original = line(c(50, -10), c(50, 16));
        for (double delta : List.of(0.001, 0.002, 0.01)) {
            LineString shifted = line(c(50, -10), c(50 + delta, 3), c(50, 16));
            assertThat(guard.assessRoundedSections(original, shifted, road, 1.755, 45, 3).isAllowed()).isFalse();
        }
    }

    private Coordinate rounded(Coordinate point) { return new RouteCoordinate(point.x, point.y).toCoordinate(); }
    private Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
    private LineString line(Coordinate... points) { return factory.createLineString(points); }
}
