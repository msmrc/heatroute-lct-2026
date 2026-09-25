package ru.lct.heatroute.domain.constraints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/**
 * Совместимость фиксированных границ special (§4) с осевым габаритом (§3.1).
 * Без разъяснения источника 3 м не превращаются в минимум, а осевой отступ не уменьшается.
 */
class RoadSpecialBoundaryCompatibilityTest {
    private final GeometryFactory geometry = new GeometryFactory();
    private final OfficialConstraintCatalog catalog = new OfficialConstraintCatalog();
    private final OfficialAxisClearance clearances = new OfficialAxisClearance(new OfficialPipeCatalog(), catalog);
    private final RoadCrossingClearance crossing = new RoadCrossingClearance();

    @ParameterizedTest(name = "{0}, DU{1}, angle={2}, allowed={3}, rotated={4}")
    @MethodSource("sourceBoundaries")
    void checksBaseAtTheFixedSpecialBoundaryWithoutInventingALargerExemption(
            String type, int diameter, int angle, boolean allowed, boolean rotated) {
        SpatialConstraintRule rule = catalog.find(type).orElseThrow();
        assertThat(rule.getMinimumCrossingAngleDegrees()).isEqualByComparingTo("45");
        assertThat(rule.getSpecialExtensionM()).isEqualByComparingTo("3");
        double clearance = clearances.axisClearanceM(type, diameter, null).doubleValue();
        double radians = Math.toRadians(angle), dx = 50 * Math.cos(radians), dy = 50 * Math.sin(radians);
        Geometry road = geometry.createPolygon(new Coordinate[] {
            point(-1000, 0, rotated), point(1000, 0, rotated), point(1000, 6, rotated),
            point(-1000, 6, rotated), point(-1000, 0, rotated)});
        LineString route = geometry.createLineString(new Coordinate[] {
            point(-dx, -dy, rotated), point(dx, dy, rotated)});

        RoadCrossingClearance.Assessment assessment = crossing.assess(route, road, clearance, 45, 3);
        assertThat(assessment.getIntervals()).singleElement().satisfies(interval -> {
            assertThat(interval.getStartM()).isCloseTo(47, offset(1e-6));
            assertThat(interval.getEndM()).isCloseTo(53 + 6 / Math.sin(radians), offset(1e-6));
        });
        // У прямой границы расстояние от начала base до дороги равно 3·sin(угла).
        // Допустимый угол сам по себе не отменяет требование отступа этой обычной части.
        assertThat(clearance <= 3 * Math.sin(radians)).isEqualTo(allowed);
        assertThat(assessment.isAllowed()).isEqualTo(allowed);
        if (!allowed) {
            assertThat(assessment.getFailureCode()).isEqualTo("SPECIAL_PARALLEL_CLEARANCE_VIOLATION");
        }
    }

    static Stream<Arguments> sourceBoundaries() {
        return Stream.of("road", "tram_tracks").flatMap(type -> Stream.of(false, true).flatMap(rotated -> Stream.of(
                Arguments.of(type, 300, 45, true, rotated),
                Arguments.of(type, 400, 45, false, rotated),
                Arguments.of(type, 1000, 90, true, rotated),
                Arguments.of(type, 1200, 90, false, rotated),
                Arguments.of(type, 1400, 90, false, rotated))));
    }

    private Coordinate point(double x, double y, boolean rotated) {
        double cosine = rotated ? 0.6 : 1, sine = rotated ? 0.8 : 0;
        return new Coordinate(430000 + cosine * x - sine * y, 6180000 + sine * x + cosine * y);
    }
}
