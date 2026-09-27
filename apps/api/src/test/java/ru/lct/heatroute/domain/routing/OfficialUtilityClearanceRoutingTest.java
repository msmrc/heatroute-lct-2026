package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialUtilityClearanceRoutingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void searchUsesFullGasAxisClearanceAtEveryOfficialBoundary() {
        ImportedOfficialFeature gas = restriction("gas", "gas_pipeline", line(-20, 0, 20, 0));
        for (int diameter : List.of(50, 100, 400, 1400)) {
            double required = rules.preparationClearanceM(gas, diameter).doubleValue();
            assertThat(rules.lineAllowed(parallel(required), rules.baseConstraints(List.of(gas), diameter)))
                    .as("DU%d exact boundary", diameter).isTrue();
            assertThat(rules.lineAllowed(parallel(required - .001), rules.baseConstraints(List.of(gas), diameter)))
                    .as("DU%d one millimetre inside", diameter).isFalse();
        }
    }

    @Test
    void existingHeatDiameterChangesTheSearchClearance() {
        ImportedOfficialFeature small = heat("small", 50, line(-20, 0, 20, 0));
        ImportedOfficialFeature large = heat("large", 1400, line(-20, 0, 20, 0));
        double smallDistance = rules.preparationClearanceM(small, 100).doubleValue();
        double largeDistance = rules.preparationClearanceM(large, 100).doubleValue();

        assertThat(smallDistance).isEqualTo(1.455);
        assertThat(largeDistance).isEqualTo(2.98);
        assertThat(rules.lineAllowed(parallel(smallDistance), rules.baseConstraints(List.of(small), 100))).isTrue();
        assertThat(rules.lineAllowed(parallel(smallDistance), rules.baseConstraints(List.of(large), 100))).isFalse();
    }

    @Test
    void preparedSearchDoesNotReuseAnotherExistingHeatDiameter() {
        ImportedOfficialFeature small = heat("same", 50, line(-20, 0, 20, 0));
        ImportedOfficialFeature large = heat("same", 1400, line(-20, 0, 20, 0));
        PreparedRoutingConstraints prepared = new PreparedRoutingConstraints(rules);

        OfficialRouteGeometryRules.Constraint first = prepared.prepare(List.of(small), 100).get(0);
        OfficialRouteGeometryRules.Constraint second = prepared.prepare(List.of(large), 100).get(0);

        assertThat(first.clearanceM()).isEqualTo(1.455);
        assertThat(second.clearanceM()).isEqualTo(2.98);
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void utilityCrossingAnglesFollowThePublishedGasCableAndHeatBoundaries() {
        LineString perpendicular = line(0, -10, 0, 10);
        LineString sixty = line(-10, -17.3205080767, 10, 17.3205080767);
        LineString belowSixty = line(-10, -16.9, 10, 16.9);
        for (ImportedOfficialFeature utility : List.of(
                restriction("gas", "gas_pipeline", line(-20, 0, 20, 0)),
                restriction("cable", "power_cable", line(-20, 0, 20, 0)))) {
            assertThat(rules.lineAllowed(
                    perpendicular, rules.baseConstraints(List.of(utility), 100))).isTrue();
            assertThat(rules.lineAllowed(
                    sixty, rules.baseConstraints(List.of(utility), 100))).isTrue();
            assertThat(rules.lineAllowed(
                    belowSixty, rules.baseConstraints(List.of(utility), 100))).isFalse();
        }
        ImportedOfficialFeature heat = heat("heat", 400, line(-20, 0, 20, 0));
        assertThat(rules.lineAllowed(perpendicular, rules.baseConstraints(List.of(heat), 100))).isTrue();
        assertThat(rules.lineAllowed(sixty, rules.baseConstraints(List.of(heat), 100))).isFalse();
    }

    @Test
    void onlySelectedHeatNetworkTieInCanLeaveItsClearance() {
        ImportedOfficialFeature heat = heat("source", 100, line(0, -20, 0, 20));
        Coordinate start = new Coordinate(0, 0);
        Coordinate end = new Coordinate(10, 0);
        List<OfficialRouteGeometryRules.Constraint> base = rules.baseConstraints(List.of(heat), 100);

        assertThat(rules.segmentAllowed(start, end, base)).isFalse();
        assertThat(rules.segmentAllowed(start, end,
                rules.localTieInConstraints(base, Set.of("source"), start, start))).isTrue();
    }

    @Test
    void routerDetoursAroundAnOrdinaryGasNearPass() {
        ImportedOfficialFeature gas = restriction("gas", "gas_pipeline", line(40, 0, 60, 0));

        RoutePath route = router.find(
                new Coordinate(0, 2),
                new Coordinate(100, 2),
                100,
                List.of(gas),
                Set.of(),
                RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(route.coordinates()).hasSizeGreaterThan(2);
        LineString axis = rules.line(route.coordinates());
        assertThat(axis.distance(gas.getMetricGeometry())).isGreaterThanOrEqualTo(2.455 - 1e-6);
        assertThat(rules.lineAllowed(axis, rules.baseConstraints(List.of(gas), 100))).isTrue();
    }

    @Test
    void routerKeepsALegalPerpendicularUtilityCrossing() {
        ImportedOfficialFeature cable = restriction("cable", "power_cable", line(50, -20, 50, 20));

        RoutePath route = router.find(
                new Coordinate(0, 0),
                new Coordinate(100, 0),
                100,
                List.of(cable),
                Set.of(),
                RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(route.lengthM()).isCloseTo(100, offset(1e-9));
        assertThat(route.sections()).filteredOn(section -> "cable".equals(section.getRestrictionId()))
                .singleElement();
    }

    @Test
    void routerBuildsALegalPortalAcrossALongUtilityWhenTheDirectAngleIsTooSmall() {
        ImportedOfficialFeature cable = restriction(
                "cable", "power_cable", line(50, -1000, 50, 1000));

        RoutePath route = router.find(
                new Coordinate(0, -20),
                new Coordinate(100, 80),
                100,
                List.of(cable),
                Set.of(),
                RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(route.coordinates()).hasSizeGreaterThan(2);
        assertThat(route.sections()).filteredOn(section -> "cable".equals(section.getRestrictionId()))
                .singleElement()
                .satisfies(section -> assertThat(section.getCrossingAngleDegrees())
                        .isGreaterThanOrEqualTo(BigDecimal.valueOf(60)));
    }

    private ImportedOfficialFeature restriction(String id, String type, LineString geometry) {
        return new ImportedOfficialFeature(
                id,
                "restriction",
                mapper.createObjectNode().put("restriction_type", type),
                geometry);
    }

    private ImportedOfficialFeature heat(String id, int diameter, LineString geometry) {
        return new ImportedOfficialFeature(
                id,
                "heat_network",
                mapper.createObjectNode().put("diameter", diameter),
                geometry);
    }

    private LineString parallel(double distance) {
        return line(-20, distance, 20, distance);
    }

    private LineString line(double... xy) {
        Coordinate[] coordinates = new Coordinate[xy.length / 2];
        for (int index = 0; index < coordinates.length; index++) {
            coordinates[index] = new Coordinate(xy[2 * index], xy[2 * index + 1]);
        }
        return geometryFactory.createLineString(coordinates);
    }
}
