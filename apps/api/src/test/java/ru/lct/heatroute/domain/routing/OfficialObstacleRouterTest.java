package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
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
    void checkedSuffixRejectsARepairCrossingAForbiddenAreaOrAnAcceptedRoute() throws Exception {
        RoutePath approach = new RoutePath(List.of(new Coordinate(900, 50), new Coordinate(50, 54)),
                List.of(), Math.hypot(850, 4));
        Coordinate end = new Coordinate(50, 50);
        OfficialRoutingEnvironment empty = router.prepare(List.of());
        RoutePath repaired = router.withCheckedTerminalSuffix(approach, end, 50, empty, Set.of(), List.of());
        assertThat(repaired).isNotNull();
        assertThat(repaired.lengthM()).isEqualTo(858);
        assertThat(repaired.sections()).isNotEmpty();
        assertThat(repaired.sections().stream().mapToDouble(section -> section.getLengthM().doubleValue()).sum())
                .isEqualTo(repaired.lengthM());

        ImportedOfficialFeature park = restriction("park", "park-on-repair",
                "POLYGON ((400 53, 420 53, 420 55, 400 55, 400 53))");
        assertThat(router.withCheckedTerminalSuffix(approach, end, 50, router.prepare(List.of(park)),
                Set.of(), List.of())).isNull();
        LineString accepted = (LineString) wktReader.read("LINESTRING (500 53, 500 55)");
        assertThat(router.withCheckedTerminalSuffix(approach, end, 50, empty, Set.of(), List.of(accepted))).isNull();
    }

    @Test
    void checkedSuffixRejectsTargetExemptionBeyondLocalHeatNetworkContact() throws Exception {
        ImportedOfficialFeature existing = new ImportedOfficialFeature(
                "network",
                "heat_network",
                objectMapper.readTree("{\"diameter\":100}"),
                wktReader.read("LINESTRING (0 0, 100 0)"));
        OfficialRoutingEnvironment environment = router.prepare(List.of(existing));
        RoutePath overlapping = new RoutePath(
                List.of(new Coordinate(0, 0), new Coordinate(90, 0)), List.of(), 90);

        assertThat(router.withCheckedTerminalSuffix(overlapping, new Coordinate(100, 0), 50,
                environment, Set.of("network"), List.of())).isNull();
    }

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
                    .isGreaterThanOrEqualTo(1.0 + 0.400 / 2 - 0.01);
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
    void appliesDynamicOksClearanceIncludingHalfPairWidth() throws Exception {
        ImportedOfficialFeature building = restriction(
                "oks", "building", "POLYGON ((40 -2, 60 -2, 60 2, 40 2, 40 -2))");

        assertClearance(building, 400, 5.0 + 1.370 / 2);
        assertClearance(building, 500, 7.0 + 1.670 / 2);
        assertClearance(building, 900, 9.0 + 2.450 / 2);
    }

    @Test
    void rejectsTargetInsideOksSetbackButRoutesAroundFootprintToTheExactLegalBoundary() throws Exception {
        ImportedOfficialFeature foreignBuilding = restriction(
                "oks", "foreign-building", "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))");

        RoutePath illegal = router.find(
                new Coordinate(0, 0),
                new Coordinate(62, 0),
                100,
                List.of(foreignBuilding),
                Collections.emptySet(),
                RoutePreference.SHORTEST);

        assertThat(illegal).isNull();
        // ДУ100: 60 + 5 + 0,510/2; путь всё ещё должен обойти лежащий между концами дом.
        RoutePath route = router.find(new Coordinate(0, 0), new Coordinate(65.255, 0), 100,
                List.of(foreignBuilding), Collections.emptySet(), RoutePreference.SHORTEST);
        assertThat(route).isNotNull();
        assertThat(route.coordinates()).hasSizeGreaterThan(2);
        assertThat(rules.line(route.coordinates()).distance(foreignBuilding.getMetricGeometry()))
                .isCloseTo(5.255, offset(1e-6));
        assertThat(rules.line(route.coordinates()).intersection(foreignBuilding.getMetricGeometry()).getLength())
                .isLessThanOrEqualTo(OfficialRouteGeometryRules.EPSILON_M);
    }

    @Test
    void normalEgressIncludesBuildingClearanceHalfPairWidthAndExteriorMargin() throws Exception {
        ImportedOfficialFeature building = restriction(
                "oks", "own-oks", "POLYGON ((90 -10, 110 -10, 110 10, 90 10, 90 -10))");

        OfficialRouteGeometryRules.NormalEgress egress = router.normalEgress(
                List.of(building), 500, new Coordinate(100, 0)).orElseThrow();

        // ДУ500: R = 7 м, W = 1.670 м; до первого поворота нужно ещё 0.25 м.
        assertRectangularWallNormal(egress, building, new Coordinate(100, 0),
                new Coordinate(90, 0), 7.0 + 1.670 / 2 + 0.25);
    }

    @Test
    void directionalEgressKeepsTheNearestWallDespiteAnObliqueTargetAcrossTheBuilding() throws Exception {
        ImportedOfficialFeature building = restriction(
                "oks", "own-oks", "POLYGON ((90 -10, 110 -10, 110 10, 90 10, 90 -10))");

        OfficialRouteGeometryRules.NormalEgress egress = rules.normalEgressTowards(
                List.of(building), 100, new Coordinate(104, 0), new Coordinate(0, 3)).orElseThrow();

        assertRectangularWallNormal(egress, building, new Coordinate(104, 0),
                new Coordinate(110, 0), 5.0 + 0.510 / 2 + 0.25);
    }

    @Test
    void directionalEgressDoesNotCrossTheWholeBuildingForADistantOppositeSide() throws Exception {
        ImportedOfficialFeature building = restriction(
                "oks", "own-oks", "POLYGON ((90 -10, 110 -10, 110 10, 90 10, 90 -10))");

        OfficialRouteGeometryRules.NormalEgress egress = rules.normalEgressTowards(
                List.of(building), 100, new Coordinate(108, 0), new Coordinate(0, 0)).orElseThrow();

        assertRectangularWallNormal(egress, building, new Coordinate(108, 0),
                new Coordinate(110, 0), 5.0 + 0.510 / 2 + 0.25);
    }

    @Test
    void engineeringEgressCandidatesIncludeAllAndOnlyEquallyNearestPermittedWallNormals() throws Exception {
        ImportedOfficialFeature building = restriction(
                "oks", "own-oks", "POLYGON ((90 -10, 110 -10, 110 10, 90 10, 90 -10))");

        List<OfficialRouteGeometryRules.NormalEgress> candidates = rules.normalEgressCandidates(
                List.of(building),
                100,
                new Coordinate(100, 0),
                new Coordinate(0, 0),
                60.0);

        double exteriorLength = 5.0 + 0.510 / 2 + 0.25;
        Coordinate start = new Coordinate(100, 0);
        List<Coordinate> walls = List.of(new Coordinate(90, 0), new Coordinate(100, -10),
                new Coordinate(100, 10), new Coordinate(110, 0));
        assertThat(candidates).hasSize(4);
        for (Coordinate wall : walls) {
            assertThat(candidates).anySatisfy(candidate ->
                    assertRectangularWallNormal(candidate, building, start, wall, exteriorLength));
        }
        assertRectangularWallNormal(candidates.get(0), building, start, walls.get(0), exteriorLength);

        // Бюджет альтернатив не разрешает дальние стены при единственной ближайшей нормали.
        List<OfficialRouteGeometryRules.NormalEgress> offCenter = rules.normalEgressCandidates(
                List.of(building), 100, new Coordinate(104, 0), new Coordinate(0, 3), 60.0);
        assertThat(offCenter).singleElement().satisfies(candidate ->
                assertRectangularWallNormal(candidate, building, new Coordinate(104, 0),
                        new Coordinate(110, 0), exteriorLength));
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
    void overlappingSpecialCrossingsSplitAtCommonFragmentBoundaries() throws Exception {
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
        assertThat(route.sections()).extracting(RouteSection::getKind)
                .containsExactly("base", "special", "special", "special", "base");
        assertThat(route.sections().subList(1, 4)).extracting(RouteSection::getRestrictionType)
                .containsExactly("road", "road+power_cable", "road");
        assertThat(route.sections().subList(1, 4)).extracting(RouteSection::getRestrictionId)
                .containsExactly("road-1", "road-1+cable-1", "road-1");
    }

    @Test
    void routeSearchPrefersStraightFortyFiveAndOrthogonalGeometryWithoutChangingOfficialTariffs() {
        List<Coordinate> nodes = List.of(
                new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(20, 10), new Coordinate(30, 15));

        double diagonalTurn = router.bendPenalty(nodes, 0, 1, 2);
        double shallowTurn = router.bendPenalty(nodes, 1, 2, 3);

        assertThat(diagonalTurn).isEqualTo(1.003);
        assertThat(shallowTurn).isGreaterThan(1.0);
        assertThat(shallowTurn).isGreaterThan(diagonalTurn);
        assertThat(router.bendPenalty(
                List.of(new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(20, 0)),
                0, 1, 2)).isEqualTo(1.0);
        assertThat(router.bendPenalty(
                List.of(new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(10, 10)),
                0, 1, 2)).isEqualTo(diagonalTurn);
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
    void acceptsPerpendicularRoadCrossingAndRejectsObliqueCrossing() throws Exception {
        ImportedOfficialFeature road = restriction(
                "road", "road-1", "POLYGON ((40 -100, 60 -100, 60 100, 40 100, 40 -100))");
        Coordinate start = new Coordinate(0, 0);
        Coordinate exact = new Coordinate(100, 0);
        Coordinate below = new Coordinate(100, 10);

        List<OfficialRouteGeometryRules.Constraint> exactConstraints = rules.constraints(
                List.of(road), 100, Set.of(), start, exact);
        List<OfficialRouteGeometryRules.Constraint> belowConstraints = rules.constraints(
                List.of(road), 100, Set.of(), start, below);

        assertThat(rules.segmentAllowed(start, exact, exactConstraints)).isTrue();
        assertThat(rules.segmentAllowed(start, below, belowConstraints)).isFalse();
    }

    @Test
    void findsLegalPortalWhenTheDirectRoadCrossingIsTooShallow() throws Exception {
        ImportedOfficialFeature road = restriction(
                "road", "road-1", "POLYGON ((40 -100, 60 -100, 60 100, 40 100, 40 -100))");

        RoutePath route = router.find(
                new Coordinate(0, -50),
                new Coordinate(100, 80),
                100,
                List.of(road),
                Collections.emptySet(),
                RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(route.coordinates()).hasSizeGreaterThan(2);
        RouteSection roadSection = route.sections().stream()
                .filter(section -> "road".equals(section.getRestrictionType()))
                .findFirst()
                .orElseThrow();
        // A shallow crossing takes the bounded perpendicular fast path instead of constructing
        // repeated visibility graphs; the written Google rule now requires the normal.
        assertThat(roadSection.getCrossingAngleDegrees()).isEqualByComparingTo("90.000");
    }

    @Test
    void keepsForbiddenDetoursWhileDeferringRoadCrossingAngles() throws Exception {
        ImportedOfficialFeature park = restriction(
                "park", "park-1", "POLYGON ((10 -10, 30 -10, 30 10, 10 10, 10 -10))");
        ImportedOfficialFeature road = restriction(
                "road", "road-1", "POLYGON ((40 -100, 60 -100, 60 100, 40 100, 40 -100))");

        RoutePath route = router.find(
                new Coordinate(0, 0),
                new Coordinate(100, 0),
                100,
                List.of(park, road),
                Collections.emptySet(),
                RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(rules.line(route.coordinates()).distance(park.getMetricGeometry()))
                .isGreaterThanOrEqualTo(1.0 + 0.510 / 2 - OfficialRouteGeometryRules.EPSILON_M);
        assertThat(route.sections())
                .filteredOn(section -> "road".equals(section.getRestrictionType()))
                .singleElement()
                .satisfies(section ->
                        assertThat(section.getCrossingAngleDegrees()).isEqualByComparingTo("90.000"));
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
    void finalValidatorAcceptsSingleBoundaryApproachToContainingOks() throws Exception {
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
                .isEmpty();
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

    private void assertRectangularWallNormal(OfficialRouteGeometryRules.NormalEgress egress,
            ImportedOfficialFeature building, Coordinate start, Coordinate wall, double exteriorLength) {
        double wallDistance = start.distance(wall);
        Coordinate expectedExit = new Coordinate(
                wall.x + (wall.x - start.x) / wallDistance * exteriorLength,
                wall.y + (wall.y - start.y) / wallDistance * exteriorLength);
        assertThat(egress.oksId()).isEqualTo(building.getFeatureId());
        assertThat(egress.start().distance(start)).isCloseTo(0, offset(1e-8));
        assertThat(egress.exit().distance(expectedExit)).isCloseTo(0, offset(1e-8));
        assertThat(building.getMetricGeometry().distance(
                building.getMetricGeometry().getFactory().createPoint(egress.exit())))
                .as("full exterior distance from the actual wall")
                .isCloseTo(exteriorLength, offset(1e-8));
        assertThat(rules.line(List.of(start, egress.exit())).intersection(building.getMetricGeometry()).getLength())
                .as("only the normal from the demand to its selected wall lies inside the building")
                .isCloseTo(wallDistance, offset(1e-8));
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
