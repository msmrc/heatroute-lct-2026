package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Прямой special ограничен отдельным пересечением и защитными 3 м по трассе. */
class OfficialRoadCrossingGeometryTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test void rejectsTurnBeforeProtectiveExtensionEnds() {
        assertThat(allowed(100, road(), c(50, -10), c(50, 6.25), c(80, 6.25))).isFalse();
    }

    @Test void preparedCorridorCannotReturnProvisionalClippedCrossingAsFinishedPath() {
        Coordinate start = c(50, -2), end = c(50, 16);
        PreparedCorridor corridor = new PreparedCorridor(rules, constraints(100, road()), start, null);
        assertThat(corridor.edgeAllowed(start, end)).isTrue();
        assertThat(corridor.path(List.of(start, end))).isNull();
    }

    @Test void acceptsTurnExactlyAfterThreeMetres() {
        assertThat(allowed(100, road(), c(50, -10), c(50, 9), c(80, 9))).isTrue();
    }

    @Test void crossingDoesNotExemptLaterParallelViolation() {
        assertThat(allowed(100, road(), c(10, -10), c(10, 9), c(20, 9),
                c(21.3, 7.7), c(90, 7.7))).isFalse();
    }

    @Test void rejectsClippedEntryOrExitAndRoadInteriorEndpoints() {
        assertThat(allowed(100, road(), c(50, -2), c(50, 16))).isFalse();
        assertThat(allowed(100, road(), c(50, -10), c(50, 8))).isFalse();
        assertThat(allowed(100, road(), c(50, 2), c(50, 16))).isFalse();
    }

    @Test void collinearVerticesDoNotBreakCrossingOrExtensions() {
        Coordinate[] points = {c(50, -10), c(50, -2), c(50, 0), c(50, 3), c(50, 6), c(50, 8), c(50, 16)};
        assertThat(allowed(100, road(), points)).isTrue();
        assertThat(sections(100, road(), points)).filteredOn(s -> "special".equals(s.getKind()))
                .singleElement().satisfies(s -> assertThat(s.getLengthM()).isEqualByComparingTo("12"));
    }

    @Test void obliqueRoadCrossingIsRejectedByTheUpdatedNormalRule() {
        assertThat(allowed(100, road(), c(40, -10), c(66, 16))).isFalse();
        assertThat(allowed(400, road(), c(40, -10), c(66, 16))).isFalse();
    }

    @Test void actualShortBoundaryDefinesAngleInsteadOfLongestRectangleAxis() {
        assertThat(allowed(100, road(), c(-10, 3), c(110, 3))).isTrue();
        assertThat(sections(100, road(), c(-10, 3), c(110, 3)))
                .filteredOn(s -> "special".equals(s.getKind())).singleElement()
                .satisfies(s -> assertThat(s.getCrossingAngleDegrees()).isEqualByComparingTo("90"));
    }

    @Test void tangentAndBoundaryOverlapDoNotGrantCrossingExemption() {
        assertThat(allowed(100, road(), c(-10, 0), c(110, 0))).isFalse();
        assertThat(allowed(100, road(), c(-10, 10), c(10, -10))).isFalse();
    }

    @Test void disjointCrossingsRemainSeparateSpecialIntervals() {
        Geometry twoRoads = factory.createMultiPolygon(new org.locationtech.jts.geom.Polygon[] {
                rectangle(0, 0, 100, 6), rectangle(0, 20, 100, 26)});
        assertThat(allowed(100, twoRoads, c(50, -10), c(50, 36))).isTrue();
        List<RouteSection> sections = sections(100, twoRoads, c(50, -10), c(50, 36));
        assertThat(sections).extracting(RouteSection::getKind)
                .containsExactly("base", "special", "base", "special", "base");
        assertThat(sections.get(2).getLengthM()).isEqualByComparingTo("8");
    }

    @Test void routerNeverAcceptsAnEndpointWithOnlyClippedProtectiveExtension() {
        ImportedOfficialFeature road = feature(road());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        RoutePath path = router.find(c(50, -2), c(50, 16), 100, router.prepare(List.of(road)),
                Set.of(), RoutePreference.SHORTEST);
        // Возможен обход края, но не прямой путь с обрезанной защитной частью.
        if (path != null) {
            assertThat(path.lengthM()).isGreaterThan(18);
            assertThat(rules.lineAllowed(rules.line(path.coordinates()), constraints(100, road()))).isTrue();
        }
    }

    @Test void routerFindsCrossingWithFullProtectivePortals() {
        Geometry vertical = rectangle(40, -100, 60, 100);
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        RoutePath path = router.find(c(0, -80), c(100, 80), 100,
                router.prepare(List.of(feature(vertical))), Set.of(), RoutePreference.SHORTEST);
        assertThat(path).isNotNull();
        assertThat(rules.lineAllowed(rules.line(path.coordinates()), constraints(100, vertical))).isTrue();
        assertThat(path.sections()).filteredOn(s -> "special".equals(s.getKind())).singleElement()
                .satisfies(s -> assertThat(s.getLengthM()).isEqualByComparingTo("26"));
    }

    @Test void overlapIsSplitAtEverySpecialBoundaryAsRequiredByTechnicalAppendix() {
        Geometry road = rectangle(40, -30, 60, 30);
        ImportedOfficialFeature cable = new ImportedOfficialFeature("cable", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "power_cable"),
                line(c(59, -30), c(59, 30)));
        List<RouteSection> sections = rules.sections(line(c(0, 0), c(100, 0)),
                rules.baseConstraints(List.of(feature(road), cable), 100));
        assertThat(sections).extracting(RouteSection::getKind)
                .containsExactly("base", "special", "special", "special", "base");
        assertThat(sections.subList(1, 4)).extracting(RouteSection::getRestrictionType)
                .containsExactly("road", "road+power_cable", "road");
        assertThat(sections.get(1).getLengthM()).isEqualByComparingTo("20");
        assertThat(sections.get(2).getLengthM()).isEqualByComparingTo("4");
        assertThat(sections.get(3).getLengthM()).isEqualByComparingTo("2");
    }

    @Test void entryAngleUsesActualEntranceNotAnAdditionalExitRequirement() {
        Geometry trapezoid = factory.createPolygon(new Coordinate[] {c(0, 0), c(100, 0),
                c(100 + 6 / Math.tan(Math.toRadians(40)), 6), c(0, 6), c(0, 0)});
        assertThat(allowed(100, trapezoid, c(-10, 3), c(120, 3))).isTrue();
        assertThat(allowed(100, trapezoid, c(120, 3), c(-10, 3))).isFalse();
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        List<Coordinate> forward = List.of(c(-10, 3), c(120, 3));
        List<Coordinate> reverse = List.of(c(120, 3), c(-10, 3));
        Object forwardSearch = ReflectionTestUtils.invokeMethod(router, "shortestPath", forward,
                rules.index(constraints(100, trapezoid)), RoutePreference.SHORTEST, forward.get(0), forward.get(1));
        Object reverseSearch = ReflectionTestUtils.invokeMethod(router, "shortestPath", reverse,
                rules.index(constraints(100, trapezoid)), RoutePreference.SHORTEST, reverse.get(0), reverse.get(1));
        assertThat((List<?>) ReflectionTestUtils.getField(forwardSearch, "coordinates")).hasSize(2);
        assertThat((List<?>) ReflectionTestUtils.getField(reverseSearch, "coordinates")).isEmpty();
    }

    @Test void millimetreScaleOverlapIsNotDroppedByCentimetreRoutingTolerance() {
        Geometry road = rectangle(40, -30, 60, 30);
        ImportedOfficialFeature cable = new ImportedOfficialFeature("cable", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "power_cable"),
                line(c(35.005, -30), c(35.005, 30)));
        List<RouteSection> sections = rules.sections(line(c(0, 0), c(100, 0)),
                rules.baseConstraints(List.of(feature(road), cable), 100));
        assertThat(sections).hasSize(5);
        assertThat(sections.get(2).getLengthM()).isEqualByComparingTo("0.005");
        assertThat(sections.get(2).getCoordinates()).hasSize(2);
        for (int i = 1; i < sections.size(); i++) {
            List<RouteCoordinate> before = sections.get(i - 1).getCoordinates();
            assertThat(before.get(before.size() - 1).toCoordinate())
                    .isEqualTo(sections.get(i).getCoordinates().get(0).toCoordinate());
        }
    }

    private boolean allowed(int diameter, Geometry road, Coordinate... points) {
        return rules.lineAllowed(line(points), constraints(diameter, road));
    }

    private List<RouteSection> sections(int diameter, Geometry road, Coordinate... points) {
        return rules.sections(line(points), constraints(diameter, road));
    }

    private List<OfficialRouteGeometryRules.Constraint> constraints(int diameter, Geometry road) {
        return rules.baseConstraints(List.of(feature(road)), diameter);
    }

    private ImportedOfficialFeature feature(Geometry road) {
        return new ImportedOfficialFeature("road", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "road"), road);
    }

    private LineString line(Coordinate... points) { return factory.createLineString(points); }
    private Geometry road() { return rectangle(0, 0, 100, 6); }
    private org.locationtech.jts.geom.Polygon rectangle(double x1, double y1, double x2, double y2) {
        return factory.createPolygon(new Coordinate[] {c(x1, y1), c(x2, y1), c(x2, y2), c(x1, y2), c(x1, y1)});
    }
    private Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
}
