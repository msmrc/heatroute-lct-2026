package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialObstacleRouterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WKTReader wktReader = new WKTReader();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void routesAroundEveryForbiddenPolygonType() throws Exception {
        for (String type : List.of("park", "social_area", "prohibited_site", "water")) {
            ImportedOfficialFeature obstacle = restriction(
                    type,
                    "blocked-" + type,
                    "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))");

            RoutePath route = router.find(
                    new Coordinate(0, 0),
                    new Coordinate(100, 0),
                    50,
                    List.of(obstacle),
                    Collections.emptySet(),
                    RoutePreference.SHORTEST);

            assertThat(route).as(type).isNotNull();
            assertThat(route.coordinates()).as(type).hasSizeGreaterThan(2);
            assertThat(route.lengthM()).as(type).isGreaterThan(100.0);
            LineString line = rules.line(route.coordinates());
            assertThat(line.distance(obstacle.getMetricGeometry())).as(type)
                    .isGreaterThanOrEqualTo(1.0 - 0.01);
        }
    }

    @Test
    void inMemoryEnvironmentKeepsWindowConstraintsLocalAndUnique() throws Exception {
        ImportedOfficialFeature obstacle = restriction(
                "park", "single-park", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))");
        ImportedOfficialFeature farObstacle = restriction(
                "park", "far-park", "POLYGON ((2000 -10, 2020 -10, 2020 10, 2000 10, 2000 -10))");
        OfficialRoutingEnvironment environment = router.prepare(List.of(obstacle, farObstacle));

        List<OfficialRouteGeometryRules.Constraint> constraints = environment.constraints(
                100, Set.of(), new Coordinate(0, 0), new Coordinate(100, 0));

        assertThat(constraints).extracting(OfficialRouteGeometryRules.Constraint::id)
                .containsExactly("single-park");
    }

    @Test
    void appliesDynamicFiveSevenNineMetreOksClearance() throws Exception {
        ImportedOfficialFeature building = restriction(
                "oks", "building", "POLYGON ((40 -2, 60 -2, 60 2, 40 2, 40 -2))");

        assertClearance(building, 400, 5.0);
        assertClearance(building, 500, 7.0);
        assertClearance(building, 900, 9.0);
    }

    @Test
    void keepsTheOksFootprintBlockedWhenTheTargetFallsInsideItsClearance() throws Exception {
        ImportedOfficialFeature foreignBuilding = restriction(
                "oks", "foreign-building", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))");

        RoutePath route = router.find(
                new Coordinate(0, 0),
                new Coordinate(62, 0),
                100,
                List.of(foreignBuilding),
                Collections.emptySet(),
                RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(route.coordinates()).hasSizeGreaterThan(2);
        assertThat(rules.line(route.coordinates()).intersection(foreignBuilding.getMetricGeometry()).getLength())
                .isLessThanOrEqualTo(OfficialRouteGeometryRules.EPSILON_M);
    }

    @Test
    void normalEgressUsesTheFinalDuClearance() throws Exception {
        ImportedOfficialFeature building = restriction(
                "oks", "own-oks", "POLYGON ((90 -10, 110 -10, 110 10, 90 10, 90 -10))");

        OfficialRouteGeometryRules.NormalEgress egress = router.normalEgress(
                List.of(building), 500, new Coordinate(100, 0)).orElseThrow();

        assertThat(egress.exit().distance(new Coordinate(100, 0)))
                .isCloseTo(17.25, org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    void createsReproducibleRoadAndUtilitySpecialSections() throws Exception {
        ImportedOfficialFeature road = restriction(
                "road", "road-1", "POLYGON ((40 -30, 60 -30, 60 30, 40 30, 40 -30))");
        ImportedOfficialFeature cable = restriction(
                "power_cable", "cable-1", "LINESTRING (75 -30, 75 30)");

        RoutePath route = router.find(
                new Coordinate(0, 0),
                new Coordinate(100, 0),
                100,
                List.of(road, cable),
                Collections.emptySet(),
                RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(route.sections()).filteredOn(section -> "special".equals(section.getKind()))
                .extracting(RouteSection::getRestrictionType)
                .containsExactly("road", "power_cable");
        RouteSection roadSection = route.sections().stream()
                .filter(section -> "road".equals(section.getRestrictionType()))
                .findFirst()
                .orElseThrow();
        RouteSection cableSection = route.sections().stream()
                .filter(section -> "power_cable".equals(section.getRestrictionType()))
                .findFirst()
                .orElseThrow();
        assertThat(roadSection.getLengthM()).isEqualByComparingTo("26.000");
        assertThat(roadSection.getCrossingAngleDegrees()).isEqualByComparingTo("90.000");
        assertThat(cableSection.getLengthM()).isEqualByComparingTo("4.000");
        assertThat(route.sections().stream()
                .map(RouteSection::getLengthM)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo("100.000");
    }

    @Test
    void overlappingSpecialCrossingsBecomeOneUnionSection() throws Exception {
        ImportedOfficialFeature road = restriction(
                "road", "road-1", "POLYGON ((40 -30, 60 -30, 60 30, 40 30, 40 -30))");
        ImportedOfficialFeature cable = restriction(
                "power_cable", "cable-1", "LINESTRING (59 -30, 59 30)");

        RoutePath route = router.find(
                new Coordinate(0, 0),
                new Coordinate(100, 0),
                100,
                List.of(road, cable),
                Collections.emptySet(),
                RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(route.sections()).filteredOn(section -> "special".equals(section.getKind()))
                .singleElement()
                .extracting(RouteSection::getRestrictionType, RouteSection::getRestrictionId)
                .containsExactly("road+power_cable", "road-1+cable-1");
    }

    @Test
    void nonStandardBendCarriesDeterministicSearchPenalty() {
        List<Coordinate> nodes = List.of(
                new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(20, 10), new Coordinate(30, 15));

        assertThat(router.bendPenalty(nodes, 0, 1, 2)).isEqualTo(1.0);
        assertThat(router.bendPenalty(nodes, 1, 2, 3)).isEqualTo(1.5);
    }

    @Test
    void depthConflictCreatesSeparateHorizontalDetourAroundUtility() throws Exception {
        ImportedOfficialFeature cable = restriction(
                "power_cable", "cable-1", "LINESTRING (50 -20, 50 20)");
        OfficialRoutingEnvironment environment = router.prepare(List.of(cable));

        RoutePath direct = router.find(
                new Coordinate(0, 0),
                new Coordinate(100, 0),
                100,
                environment,
                Set.of(),
                RoutePreference.SHORTEST);
        RoutePath detour = router.findAvoidingDepthConflicts(
                new Coordinate(0, 0),
                new Coordinate(100, 0),
                100,
                environment,
                Set.of(),
                Set.of("cable-1"),
                List.of());

        assertThat(direct).isNotNull();
        assertThat(direct.lengthM()).isEqualTo(100.0);
        assertThat(detour).isNotNull();
        assertThat(detour.lengthM()).isGreaterThan(100.0);
        assertThat(rules.line(detour.coordinates()).distance(cable.getMetricGeometry()))
                .isGreaterThanOrEqualTo(1.99);
        assertThat(detour.sections()).noneMatch(section ->
                "cable-1".equals(section.getRestrictionId()));
    }

    @Test
    void acceptsFortyFiveDegreeRoadCrossingAndRejectsBelowBoundary() throws Exception {
        ImportedOfficialFeature road = restriction(
                "road", "road-1", "POLYGON ((40 -100, 60 -100, 60 100, 40 100, 40 -100))");
        Coordinate start = new Coordinate(0, -50);
        Coordinate exact = new Coordinate(100, 50);
        Coordinate below = new Coordinate(100, 69.175);

        List<OfficialRouteGeometryRules.Constraint> exactConstraints = rules.constraints(
                List.of(road), 100, Set.of(), start, exact);
        List<OfficialRouteGeometryRules.Constraint> belowConstraints = rules.constraints(
                List.of(road), 100, Set.of(), start, below);

        assertThat(rules.segmentAllowed(start, exact, exactConstraints)).isTrue();
        assertThat(rules.segmentAllowed(start, below, belowConstraints)).isFalse();
    }

    @Test
    void finalValidatorRejectsAHandCraftedRouteThroughForbiddenArea() throws Exception {
        ImportedOfficialFeature water = restriction(
                "water", "water-1", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))");
        RouteNode root = node("root", 0, 0, true);
        RouteNode demand = node("demand", 100, 0, false);
        RouteEdge invalid = new RouteEdge(
                "edge",
                "root",
                "demand",
                100.0,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(100, 0)),
                List.of(new RouteSection(
                        "base",
                        null,
                        null,
                        List.of(new RouteCoordinate(0, 0), new RouteCoordinate(100, 0)),
                        100,
                        null)),
                java.math.BigDecimal.TEN,
                100);

        OfficialRouteValidator validator = new OfficialRouteValidator(rules);

        assertThat(validator.validate(List.of(root, demand), List.of(invalid), List.of(water)))
                .extracting(RouteValidationIssue::getCode)
                .contains("FORBIDDEN_CLEARANCE_VIOLATION");
    }

    @Test
    void finalValidatorRejectsArbitraryExitFromContainingOks() throws Exception {
        ImportedOfficialFeature ownOks = restriction(
                "oks", "own-oks", "POLYGON ((90 -10, 110 -10, 110 10, 90 10, 90 -10))");
        RouteNode root = node("root", 0, 0, true);
        RouteNode demand = node("demand", 100, 0, false);
        RouteEdge invalid = new RouteEdge(
                "edge",
                "root",
                "demand",
                100.0,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(100, 0)),
                List.of(new RouteSection(
                        "base", null, null,
                        List.of(new RouteCoordinate(0, 0), new RouteCoordinate(100, 0)), 100, null)),
                java.math.BigDecimal.TEN,
                100);

        OfficialRouteValidator validator = new OfficialRouteValidator(rules);

        assertThat(validator.validate(List.of(root, demand), List.of(invalid), List.of(ownOks)))
                .extracting(RouteValidationIssue::getCode)
                .contains("OKS_NORMAL_EGRESS_VIOLATION");
    }

    @Test
    void finalValidatorRejectsReentryIntoOwnOksBeforeTheMandatoryEgressLeg() throws Exception {
        ImportedOfficialFeature ownOks = restriction(
                "oks", "own-oks", "POLYGON ((90 -20, 110 -20, 110 20, 90 20, 90 -20))");
        RouteNode root = node("root", 0, 0, true);
        RouteNode demand = node("demand", 92, 0, false);
        Coordinate exit = router.normalEgress(List.of(ownOks), 100, new Coordinate(92, 0))
                .orElseThrow().exit();
        List<RouteCoordinate> coordinates = List.of(
                new RouteCoordinate(0, 0),
                new RouteCoordinate(105, 30),
                new RouteCoordinate(exit.x, exit.y),
                new RouteCoordinate(92, 0));
        double length = 0.0;
        for (int index = 1; index < coordinates.size(); index++) {
            length += coordinates.get(index - 1).toCoordinate().distance(coordinates.get(index).toCoordinate());
        }
        RouteEdge invalid = new RouteEdge(
                "edge",
                "root",
                "demand",
                length,
                coordinates,
                List.of(new RouteSection("base", null, null, coordinates, length, null)),
                java.math.BigDecimal.TEN,
                100);

        OfficialRouteValidator validator = new OfficialRouteValidator(rules);

        assertThat(validator.validate(List.of(root, demand), List.of(invalid), List.of(ownOks)))
                .extracting(RouteValidationIssue::getCode)
                .contains("FORBIDDEN_CLEARANCE_VIOLATION");
    }

    @Test
    void spatialIndexKeepsDenseConstraintLookupsBoundedWithoutChangingDecisions() throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        features.add(restriction(
                "water", "near", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))"));
        for (int row = 0; row < 25; row++) {
            for (int column = 0; column < 40; column++) {
                double x = 1000 + column * 10;
                double y = 1000 + row * 10;
                features.add(restriction(
                        "park",
                        "far-" + row + "-" + column,
                        "POLYGON ((" + x + " " + y + ", " + (x + 1) + " " + y + ", "
                                + (x + 1) + " " + (y + 1) + ", " + x + " " + (y + 1) + ", "
                                + x + " " + y + "))"));
            }
        }

        List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(features, 100);
        OfficialRouteGeometryRules.ConstraintIndex index = rules.index(constraints);
        Coordinate start = new Coordinate(0, 0);
        Coordinate blockedEnd = new Coordinate(100, 0);
        Coordinate clearEnd = new Coordinate(30, 0);

        assertThat(index.query(new Envelope(start, blockedEnd))).hasSize(1);
        assertThat(rules.segmentAllowed(start, blockedEnd, index))
                .isEqualTo(rules.segmentAllowed(start, blockedEnd, constraints))
                .isFalse();
        assertThat(rules.segmentAllowed(start, clearEnd, index))
                .isEqualTo(rules.segmentAllowed(start, clearEnd, constraints))
                .isTrue();

        assertTimeout(Duration.ofSeconds(5), () -> {
            for (int iteration = 0; iteration < 20_000; iteration++) {
                assertThat(rules.segmentAllowed(start, clearEnd, index)).isTrue();
            }
        });
    }

    @Test
    void reducedNavigationHullRemainsConnectedForDetailedConvexObstacle() throws Exception {
        StringBuilder polygon = new StringBuilder("POLYGON ((");
        for (int index = 0; index < 48; index++) {
            double angle = 2.0 * Math.PI * index / 48.0;
            if (index > 0) {
                polygon.append(", ");
            }
            polygon.append(50.0 + 10.0 * Math.cos(angle))
                    .append(' ')
                    .append(10.0 * Math.sin(angle));
        }
        polygon.append(", 60.0 0.0))");
        ImportedOfficialFeature obstacle = restriction("park", "detailed", polygon.toString());

        RoutePath route = router.find(
                new Coordinate(0, 0),
                new Coordinate(100, 0),
                100,
                List.of(obstacle),
                Collections.emptySet(),
                RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(rules.line(route.coordinates()).disjoint(obstacle.getMetricGeometry())).isTrue();
    }

    private void assertClearance(ImportedOfficialFeature building, int diameter, double expected) {
        RoutePath route = router.find(
                new Coordinate(0, 0),
                new Coordinate(100, 0),
                diameter,
                List.of(building),
                Collections.emptySet(),
                RoutePreference.SHORTEST);
        assertThat(route).isNotNull();
        assertThat(rules.line(route.coordinates()).distance(building.getMetricGeometry()))
                .isGreaterThanOrEqualTo(expected - 0.01);
    }

    private ImportedOfficialFeature restriction(String type, String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(
                id,
                "restriction",
                objectMapper.readTree("{\"restriction_type\":\"" + type + "\"}"),
                wktReader.read(wkt));
    }

    private RouteNode node(String id, double x, double y, boolean root) {
        return new RouteNode(
                id,
                root ? "new_tie_in_chamber" : "demand_connection",
                new RouteCoordinate(x, y),
                root,
                root,
                root ? 2 : 0,
                null);
    }
}
