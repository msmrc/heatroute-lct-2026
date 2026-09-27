package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialConstraintBoundaryMatrixTest {
    private static final List<String> FORBIDDEN = List.of(
            "park", "social_area", "prohibited_site", "water", "railway");
    private static final List<String> ANGLED_SPECIAL = List.of("road", "tram_tracks");
    private static final List<String> UTILITY_SPECIAL = List.of(
            "gas_pipeline", "power_cable", "heat_network");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WKTReader reader = new WKTReader();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test
    void coversPositiveBoundaryAndNegativeCasesForEveryForbiddenRow() throws Exception {
        for (String type : FORBIDDEN) {
            ImportedOfficialFeature restriction = restriction(type, "POLYGON ((-1 -1, 1 -1, 1 1, -1 1, -1 -1))");
            List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(
                    List.of(restriction), 100);

            // ДУ100: R=1 м, W/2=0.255 м; граница полигона y=1.
            assertThat(rules.lineAllowed(horizontal(2.256), constraints)).as(type + " positive").isTrue();
            assertThat(rules.lineAllowed(horizontal(2.255), constraints)).as(type + " boundary").isTrue();
            assertThat(rules.lineAllowed(horizontal(2.254), constraints)).as(type + " negative").isFalse();
        }
    }

    @Test
    void coversPositiveBoundaryAndNegativeCasesForEveryOksDiameterBand() throws Exception {
        ImportedOfficialFeature building = feature(
                "oks_existing", "oks", "POLYGON ((-1 -1, 1 -1, 1 1, -1 1, -1 -1))", "{}");
        int[] diameters = {50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400};
        double[] axialM = {5.2,5.215,5.235,5.255,5.3,5.325,5.44,5.525,5.575,5.685,
                7.835,7.925,8.025,8.125,10.225,10.325,10.55,10.725};
        for (int i = 0; i < diameters.length; i++) assertDynamicClearance(building, diameters[i], axialM[i]);
    }

    @Test
    void coversAngleAndSpecialSectionBoundariesForRoadAndTramRows() throws Exception {
        for (String type : ANGLED_SPECIAL) {
            // Официальные road/tram — polygon: ширина 2 м плюс 3 м с каждой стороны.
            ImportedOfficialFeature restriction = restriction(type, "POLYGON ((-20 -1,20 -1,20 1,-20 1,-20 -1))");
            LineString positive = (LineString) reader.read("LINESTRING (0 -10, 0 10)");
            LineString boundary = "road".equals(type)
                    ? positive
                    : (LineString) reader.read("LINESTRING (-10 -10, 10 10)");
            LineString negative = "road".equals(type)
                    ? (LineString) reader.read("LINESTRING (-0.03 -10, 0.03 10)")
                    : (LineString) reader.read("LINESTRING (-10 -9.9, 10 9.9)");
            List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(
                    List.of(restriction), 100);

            assertThat(rules.lineAllowed(positive, constraints)).as(type + " positive").isTrue();
            assertThat(rules.lineAllowed(boundary, constraints)).as(type + " boundary").isTrue();
            assertThat(rules.lineAllowed(negative, constraints)).as(type + " negative").isFalse();
            assertThat(rules.sections(positive, constraints)).filteredOn(section -> "special".equals(section.getKind()))
                    .singleElement().satisfies(section -> {
                        assertThat(section.getRestrictionType()).isEqualTo(type);
                        assertThat(section.getLengthM()).isEqualByComparingTo("8.000");
                    });
        }
    }

    @Test
    void coversGeneratedBoundaryAndMissingSectionCasesForEveryUtilityRow() throws Exception {
        for (String type : UTILITY_SPECIAL) {
            ImportedOfficialFeature restriction = restriction(type, "LINESTRING (-20 0, 20 0)");
            boolean obliqueAllowed = "gas_pipeline".equals(type) || "power_cable".equals(type);
            LineString crossing = (LineString) reader.read(obliqueAllowed
                    ? "LINESTRING (-10 -17.3205080767, 10 17.3205080767)"
                    : "LINESTRING (0 -10, 0 10)");
            LineString below = (LineString) reader.read(obliqueAllowed
                    ? "LINESTRING (-10 -16.9, 10 16.9)"
                    : "LINESTRING (-0.04 -10, 0.04 10)");
            List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(
                    List.of(restriction), 100);
            List<RouteSection> sections = rules.sections(crossing, constraints);
            RouteEdge valid = edge(type, crossing, sections);
            RouteEdge missing = edge(type + "-missing", crossing, List.of());

            assertThat(rules.lineAllowed(crossing, constraints)).as(type + " positive").isTrue();
            assertThat(rules.lineAllowed(below, constraints)).as(type + " below angle").isFalse();
            assertThat(sections).filteredOn(section -> "special".equals(section.getKind()))
                    .singleElement().satisfies(section -> {
                        assertThat(section.getRestrictionType()).isEqualTo(type);
                        assertThat(section.getLengthM()).isEqualByComparingTo("4.000");
                    });
            assertThat(rules.validate(valid, crossing, constraints)).as(type + " boundary").isEmpty();
            assertThat(rules.validate(missing, crossing, constraints))
                    .as(type + " negative")
                    .extracting(RouteValidationIssue::getCode)
                    .contains("SPECIAL_CROSSING_SECTION_MISSING");
        }
    }

    private void assertDynamicClearance(
            ImportedOfficialFeature building,
            int diameter,
            double clearance) throws Exception {
        List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(
                List.of(building), diameter);
        assertThat(rules.lineAllowed(horizontal(1 + clearance + 0.01), constraints)).isTrue();
        assertThat(rules.lineAllowed(horizontal(1 + clearance), constraints)).isTrue();
        assertThat(rules.lineAllowed(horizontal(1 + clearance - 0.01), constraints)).isFalse();
    }

    private LineString horizontal(double y) throws Exception {
        return (LineString) reader.read("LINESTRING (-20 " + y + ", 20 " + y + ")");
    }

    private RouteEdge edge(String id, LineString line, List<RouteSection> sections) {
        List<RouteCoordinate> coordinates = List.of(
                new RouteCoordinate(line.getStartPoint().getX(), line.getStartPoint().getY()),
                new RouteCoordinate(line.getEndPoint().getX(), line.getEndPoint().getY()));
        return new RouteEdge(id, "start", "end", line.getLength(), coordinates, sections, null, 100);
    }

    private ImportedOfficialFeature restriction(String type, String wkt) throws Exception {
        return feature("restriction", type, wkt, "{\"restriction_type\":\"" + type + "\"}");
    }

    private ImportedOfficialFeature feature(
            String objectType,
            String id,
            String wkt,
            String attributes) throws Exception {
        return new ImportedOfficialFeature(id, objectType, objectMapper.readTree(attributes), reader.read(wkt));
    }
}
