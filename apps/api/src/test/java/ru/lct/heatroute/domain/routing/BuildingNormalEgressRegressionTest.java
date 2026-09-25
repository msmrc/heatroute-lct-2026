package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Регрессии по правилам ввода из материалов эксперта от 25.09.2026; не новая редакция СП. */
class BuildingNormalEgressRegressionTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsNormalToNearestWallRegardlessOfRemoteTarget() throws Exception {
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        var start = new Coordinate(2, 8);
        var exit = rules.normalEgressTowards(features, 50, start, new Coordinate(80, 70)).orElseThrow();
        assertThat(exit.exit().y).isCloseTo(8, offset(1e-8));
        assertThat(exit.exit().x).isLessThanOrEqualTo(-5.2);
    }

    @Test
    void continuesBeyondWallByClearanceIncludingHalfPairWidth() throws Exception {
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        var exit = rules.normalEgress(features, 50, new Coordinate(2, 8)).orElseThrow();
        assertThat(exit.exit().x).isLessThanOrEqualTo(-5.2);
        assertThat(exit.exit().y).isEqualTo(8);
    }

    @Test
    void doesNotWeakenOfficialSetbackForLargeDiameter() throws Exception {
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        var exit = rules.normalEgress(features, 900, new Coordinate(2, 8)).orElseThrow();
        assertThat(exit.exit().x).isLessThanOrEqualTo(-9);
    }

    @Test
    void triesNextNearestWallWhenOnlyExtendedLegMeetsForeignObstacle() throws Exception {
        var features = List.of(
                building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                restriction("park", "park", "POLYGON ((-6 5, -3 5, -3 11, -6 11, -6 5))"));
        var exit = rules.normalEgress(features, 50, new Coordinate(2, 8)).orElseThrow();
        assertThat(exit.exit().x).isCloseTo(2, offset(1e-8));
        assertThat(exit.exit().y).isLessThanOrEqualTo(-5.2);
    }

    @Test
    void returnsNoExitWhenEveryWallIsBlocked() throws Exception {
        var features = List.of(
                building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                restriction("park", "park", "POLYGON ((-20 -20, 40 -20, 40 40, -20 40, -20 -20))"));
        assertThat(rules.normalEgress(features, 50, new Coordinate(2, 8))).isEmpty();
        assertThat(rules.normalEgressCandidates(features, 50, new Coordinate(2, 8),
                new Coordinate(-30, 8), 60)).isEmpty();
    }

    @Test
    void supportsConnectionOnWallAndDoesNotAimDiagonallyThroughCorner() throws Exception {
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        var exit = rules.normalEgress(features, 50, new Coordinate(0, 8)).orElseThrow();
        assertThat(exit.exit().x).isLessThanOrEqualTo(-5.2);
        assertThat(exit.exit().y).isEqualTo(8);
    }

    @Test
    void finalValidationRejectsMissingLegalExitInsteadOfTreatingItAsFreeSpace() throws Exception {
        var features = List.of(
                building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                restriction("park", "park", "POLYGON ((-20 -20, 40 -20, 40 40, -20 40, -20 -20))"));
        assertThat(issues(features, new Coordinate(-30, 8), new Coordinate(2, 8))).isNotEmpty();
    }

    @Test
    void finalValidationRejectsOppositeCollinearDirection() throws Exception {
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        assertThat(issues(features, new Coordinate(40, 8), new Coordinate(2, 8))).isNotEmpty();
    }

    @Test
    void finalValidationRejectsEarlyTurnAndAcceptsFullNormal() throws Exception {
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        assertThat(issues(features, new Coordinate(-0.25, 8), new Coordinate(2, 8))).isNotEmpty();
        assertThat(issues(features, new Coordinate(-10, 8), new Coordinate(2, 8))).isEmpty();
        assertThat(issues(features, new Coordinate(-10, 9), new Coordinate(2, 8))).isNotEmpty();
    }

    @Test
    void finalValidationDoesNotInventNormalOutsideABuilding() {
        assertThat(issues(List.of(), new Coordinate(40, 8), new Coordinate(2, 8))).isEmpty();
    }

    @Test
    void actualTerminalLegCannotReenterAnotherPartOfOwnBuilding() throws Exception {
        var features = List.of(building("own", "MULTIPOLYGON (((0 0, 20 0, 20 20, 0 20, 0 0)), "
                + "((-30 0, -20 0, -20 20, -30 20, -30 0)))"));
        assertThat(rules.normalEgress(features, 50, new Coordinate(2, 8))).isPresent();
        assertThat(issues(features, new Coordinate(-10, 8), new Coordinate(2, 8))).isEmpty();
        assertThat(issues(features, new Coordinate(-40, 8), new Coordinate(2, 8)))
                .extracting(RouteValidationIssue::getCode).contains("OKS_NORMAL_EGRESS_VIOLATION");
        // Не только пересечение контура: продолжение ввода обязано сохранять полный отступ.
        assertThat(issues(features, new Coordinate(-16, 8), new Coordinate(2, 8)))
                .extracting(RouteValidationIssue::getCode).contains("OKS_NORMAL_EGRESS_VIOLATION");
    }

    @Test
    void socialAreaBlocksEveryNormalEvenForAnOwnMultipartBuilding() throws Exception {
        var features = List.of(
                building("own", "MULTIPOLYGON (((0 0, 20 0, 20 20, 0 20, 0 0)), "
                        + "((-30 0, -20 0, -20 20, -30 20, -30 0)))"),
                restriction("site", "social_area", "POLYGON ((-35 -5, 25 -5, 25 25, -35 25, -35 -5))"));
        assertThat(rules.normalEgress(features, 50, new Coordinate(2, 8))).isEmpty();
    }

    @Test
    void endpointRoundingCannotBypassNormalOwnedByDeclaredDemandNode() throws Exception {
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        var coordinates = List.of(new RouteCoordinate(-20, 10), new RouteCoordinate(-0.001, 8));
        var edge = new RouteEdge("edge", "root", "demand",
                coordinates.get(0).toCoordinate().distance(coordinates.get(1).toCoordinate()),
                coordinates, List.of(), null, 50);
        var nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", coordinates.get(0), true, true, 1, "root"),
                new RouteNode("demand", "demand_connection", new RouteCoordinate(0, 8),
                        false, true, 0, "demand"));
        assertThat(new OfficialRouteValidator(rules).validate(nodes, List.of(edge), features))
                .extracting(RouteValidationIssue::getCode).contains("OKS_NORMAL_EGRESS_VIOLATION");
    }

    @Test
    void navigationMarginIsNotAnAdditionalMandatorySetback() throws Exception {
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        assertThat(issues(features, new Coordinate(-5.2, 8), new Coordinate(2, 8))).isEmpty();
        assertThat(issues(features, new Coordinate(-5.199, 8), new Coordinate(2, 8))).isNotEmpty();
    }

    @Test
    void optionalNavigationMarginCannotDisqualifyNearestLegalWall() throws Exception {
        var features = List.of(
                building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                restriction("park", "park", "POLYGON ((-6.5 7, -6.4 7, -6.4 9, -6.5 9, -6.5 7))"));
        var exit = rules.normalEgress(features, 50, new Coordinate(2, 8)).orElseThrow();
        assertThat(exit.exit().x).isCloseTo(-5.2, offset(1e-8));
        assertThat(exit.exit().y).isEqualTo(8);
    }

    @Test
    void usesOriginalDemandOwnershipWhenBothExportedEndpointsRoundOutside() throws Exception {
        var demand = new ImportedOfficialFeature("demand", "oks_connection_point",
                mapper.createObjectNode(), new WKTReader().read("POINT (0.0004 8)"));
        var features = List.of(
                building("own", "POLYGON ((0.0004 0, 20 0, 20 20, 0.0004 20, 0.0004 0))"), demand);
        assertThat(validateRoundedWallInput(features, new RouteCoordinate(-20, 10)))
                .extracting(RouteValidationIssue::getCode).contains("OKS_NORMAL_EGRESS_VIOLATION");
        assertThat(validateRoundedWallInput(features, new RouteCoordinate(-20, 8))).isEmpty();
    }

    @Test
    void actualRoundedNormalMayReachClearanceSlightlyAfterCanonicalProjection() throws Exception {
        var features = List.of(building("own", "MULTIPOLYGON ("
                + "((400000 6000000, 400016 6000012, 400004 6000028, 399988 6000016, 400000 6000000)),"
                + "((399999.2 5999994.4, 400000 5999995, 399995.2 6000001.4,"
                + " 399994.4 6000000.8, 399999.2 5999994.4)))"));
        var start = new Coordinate(399996.800, 6000007.600);
        var adjacent = new Coordinate(399989.142, 6000001.856);
        assertThat(features.get(0).getMetricGeometry().distance(
                features.get(0).getMetricGeometry().getFactory().createPoint(adjacent))).isGreaterThan(5.2);
        assertThat(issues(features, adjacent, start)).isEmpty();
    }

    @Test
    void rejectsDemandMovedAwayFromItsOriginalInputEvenWhenTheLocalNormalLooksValid() throws Exception {
        var demand = new ImportedOfficialFeature("demand", "oks_connection_point",
                mapper.createObjectNode(), new WKTReader().read("POINT (0 8.02)"));
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"), demand);
        assertThat(validateRoundedWallInput(features, new RouteCoordinate(-20, 8)))
                .extracting(RouteValidationIssue::getCode).contains("DEMAND_CONNECTION_COORDINATE_MISMATCH");
    }

    private List<RouteValidationIssue> validateRoundedWallInput(
            List<ImportedOfficialFeature> features, RouteCoordinate adjacent) {
        var endpoint = new RouteCoordinate(0, 8);
        var edge = new RouteEdge("edge", "root", "demand",
                adjacent.toCoordinate().distance(endpoint.toCoordinate()),
                List.of(adjacent, endpoint), List.of(), null, 50);
        var nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", adjacent, true, true, 1, "root"),
                new RouteNode("demand", "demand_connection", endpoint, false, true, 0, "demand"));
        return new OfficialRouteValidator(rules).validate(nodes, List.of(edge), features);
    }

    @Test
    void finalNormalDoesNotExemptEarlierRouteFromOwnBuildingSetback() throws Exception {
        var features = List.of(building("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        var route = new WKTReader().read("LINESTRING (-20 30, -2 30, -2 24, -10 24, -10 8, 2 8)");
        var coordinates = java.util.Arrays.stream(route.getCoordinates())
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(java.util.stream.Collectors.toList());
        var edge = new RouteEdge("edge", "root", "demand", route.getLength(), coordinates, List.of(), null, 50);
        var nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", coordinates.get(0), true, true, 1, "root"),
                new RouteNode("demand", "demand_connection", coordinates.get(coordinates.size() - 1),
                        false, true, 0, "demand"));
        assertThat(rules.validateMandatoryEgress(edge, (org.locationtech.jts.geom.LineString) route, features, 50))
                .isEmpty();
        assertThat(new OfficialRouteValidator(rules).validate(nodes, List.of(edge), features))
                .extracting(RouteValidationIssue::getCode).contains("FORBIDDEN_CLEARANCE_VIOLATION");
    }

    private List<RouteValidationIssue> issues(List<ImportedOfficialFeature> features,
            Coordinate adjacent, Coordinate endpoint) {
        var route = rules.line(List.of(adjacent, endpoint));
        return rules.validateMandatoryEgress(new RouteEdge("edge", "root", "demand", route.getLength()),
                route, features, 50);
    }

    private ImportedOfficialFeature building(String id, String wkt) throws Exception {
        return restriction(id, "oks", wkt);
    }

    private ImportedOfficialFeature restriction(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction",
                mapper.createObjectNode().put("restriction_type", type), new WKTReader().read(wkt));
    }
}
