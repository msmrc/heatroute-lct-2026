package ru.lct.heatroute.domain.constraints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;

class OfficialConstraintRulesTest {
    private final OfficialConstraintCatalog catalog = new OfficialConstraintCatalog();
    private final OfficialCrossingGeometry geometry = new OfficialCrossingGeometry();
    private final WKTReader reader = new WKTReader();

    @Test
    void appliesFiveSevenNineMetreExistingBuildingClearanceBoundaries() {
        assertThat(catalog.existingBuildingClearanceM(400)).isEqualByComparingTo("5.0");
        assertThat(catalog.existingBuildingClearanceM(500)).isEqualByComparingTo("7.0");
        assertThat(catalog.existingBuildingClearanceM(800)).isEqualByComparingTo("7.0");
        assertThat(catalog.existingBuildingClearanceM(900)).isEqualByComparingTo("9.0");
        assertThat(catalog.existingBuildingClearanceM(1400)).isEqualByComparingTo("9.0");
        assertThatThrownBy(() -> catalog.existingBuildingClearanceM(499))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesExactRoadTramAndUtilityRules() {
        SpatialConstraintRule road = catalog.find("road").orElseThrow();
        assertThat(road.getHorizontalClearanceM()).isEqualByComparingTo("1.5");
        assertThat(road.getMinimumCrossingAngleDegrees()).isEqualByComparingTo("45");
        assertThat(road.getSpecialExtensionM()).isEqualByComparingTo("3.0");
        assertThat(road.getMinimumTopBelowSurfaceM()).isEqualByComparingTo("1.0");
        assertThat(road.getCostMultiplier()).isEqualByComparingTo("1.60");

        SpatialConstraintRule tram = catalog.find("tram_tracks").orElseThrow();
        assertThat(tram.getMinimumTopBelowSurfaceM()).isEqualByComparingTo("1.2");
        assertThat(tram.getCostMultiplier()).isEqualByComparingTo("1.75");

        assertThat(catalog.find("gas_pipeline").orElseThrow().getVerticalClearanceM())
                .isEqualByComparingTo("0.2");
        assertThat(catalog.find("power_cable").orElseThrow().getCostMultiplier())
                .isEqualByComparingTo("1.15");
        assertThat(catalog.find("heat_network").orElseThrow().getHorizontalClearanceM())
                .isEqualByComparingTo("1.0");
    }

    @Test
    void expandsSpecialSegmentByRequiredDistanceOnBothSides() throws Exception {
        LineString route = (LineString) reader.read("LINESTRING (0 0, 100 0)");
        org.locationtech.jts.geom.Geometry road = reader.read(
                "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))");

        LineString special = geometry.specialSegment(route, road, 3.0);

        assertThat(special.getLength()).isEqualTo(26.0);
        assertThat(special.getStartPoint().getX()).isEqualTo(37.0);
        assertThat(special.getEndPoint().getX()).isEqualTo(63.0);
    }

    @Test
    void treatsFortyFiveDegreesAsAllowedAndJustBelowAsRejected() throws Exception {
        LineString utility = (LineString) reader.read("LINESTRING (-10 0, 10 0)");
        LineString exactly = (LineString) reader.read("LINESTRING (-10 -10, 10 10)");
        LineString below = (LineString) reader.read("LINESTRING (-10 -9, 10 9)");

        assertThat(geometry.meetsMinimumAngle(exactly, utility, 45.0)).isTrue();
        assertThat(geometry.meetsMinimumAngle(below, utility, 45.0)).isFalse();
    }
}
