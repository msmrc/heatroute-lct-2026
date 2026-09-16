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
            "park", "social_area", "prohibited_site", "water");
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

            assertThat(rules.lineAllowed(horizontal(2.01), constraints)).as(type + " positive").isTrue();
            assertThat(rules.lineAllowed(horizontal(2.0), constraints)).as(type + " boundary").isTrue();
            assertThat(rules.lineAllowed(horizontal(1.99), constraints)).as(type + " negative").isFalse();
        }
    }

    @Test
    void coversPositiveBoundaryAndNegativeCasesForEveryOksDiameterBand() throws Exception {
        ImportedOfficialFeature building = feature(
                "oks_existing", "oks", "POLYGON ((-1 -1, 1 -1, 1 1, -1 1, -1 -1))", "{}");
        assertDynamicClearance(building, 400, 5.0);
        assertDynamicClearance(building, 500, 7.0);
        assertDynamicClearance(building, 900, 9.0);
    }

    @Test
    void coversAngleAndSpecialSectionBoundariesForRoadAndTramRows() throws Exception {
        for (String type : ANGLED_SPECIAL) {
            ImportedOfficialFeature restriction = restriction(type, "LINESTRING (-20 0, 20 0)");
            LineString positive = (LineString) reader.read("LINESTRING (0 -10, 0 10)");
            LineString boundary = (LineString) reader.read("LINESTRING (-10 -10, 10 10)");
            LineString negative = (LineString) reader.read("LINESTRING (-10 -9.9, 10 9.9)");
            List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(
                    List.of(restriction), 100);

            assertThat(rules.lineAllowed(positive, constraints)).as(type + " positive").isTrue();
            assertThat(rules.lineAllowed(boundary, constraints)).as(type + " boundary").isTrue();
            assertThat(rules.lineAllowed(negative, constraints)).as(type + " negative").isFalse();
            assertThat(rules.sections(positive, constraints)).filteredOn(section -> "special".equals(section.getKind()))
                    .singleElement().satisfies(section -> {
                        assertThat(section.getRestrictionType()).isEqualTo(type);
                        assertThat(section.getLengthM()).isEqualByComparingTo("6.000");
                    });
        }
    }

    @Test
    void coversGeneratedBoundaryAndMissingSectionCasesForEveryUtilityRow() throws Exception {
        for (String type : UTILITY_SPECIAL) {
            ImportedOfficialFeature restriction = restriction(type, "LINESTRING (-20 0, 20 0)");
            LineString crossing = (LineString) reader.read("LINESTRING (0 -10, 0 10)");
            List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(
                    List.of(restriction), 100);
            List<RouteSection> sections = rules.sections(crossing, constraints);
            RouteEdge valid = edge(type, crossing, sections);
            RouteEdge missing = edge(type + "-missing", crossing, List.of());

            assertThat(rules.lineAllowed(crossing, constraints)).as(type + " positive").isTrue();
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

    @Test
    void keepsRailwayCompatibilityRuleConservativelyForbiddenAtItsBoundary() throws Exception {
        ImportedOfficialFeature railway = restriction(
                "railway", "POLYGON ((-1 -1, 1 -1, 1 1, -1 1, -1 -1))");
        List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(List.of(railway), 100);

        assertThat(rules.lineAllowed(horizontal(2.51), constraints)).isTrue();
        assertThat(rules.lineAllowed(horizontal(2.5), constraints)).isTrue();
        assertThat(rules.lineAllowed(horizontal(2.49), constraints)).isFalse();
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
