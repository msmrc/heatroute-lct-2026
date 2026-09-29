package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Пересчёт ДУ должен обходить новый отступ, сохраняя нормаль существующей камеры. */
class SizingChamberApproachTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @ParameterizedTest
    @ValueSource(doubles = {0, 27, 90})
    void diameterPromotionReroutesTheObstacleWithoutLosingTheRootNormal(double rotation) throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature(rotation, "heat_network", "network", "LINESTRING (0 -100,0 100)", "{\"diameter\":150}"),
                feature(rotation, "heat_chamber", "chamber", "POINT (0 0)", "{}"),
                feature(rotation, "oks_existing", "building", "POLYGON ((10 15.31,20 15.31,20 17.31,10 17.31,10 15.31))", "{}"));
        Coordinate root = transform(rotation, 0, 0), demand = transform(rotation, 30, 10);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", coordinate(root), true, true, 2, "chamber"),
                new RouteNode("demand:one", "demand_connection", coordinate(demand), false, false, 0, null));
        List<Coordinate> path = List.of(root, transform(rotation, 4, 0), transform(rotation, 4, 10), demand);
        RouteEdge original = edge(path, 125);
        OfficialRouteValidator validator = new OfficialRouteValidator(rules);
        assertThat(validator.validate(nodes, List.of(original), features)).isEmpty();
        assertThat(validator.validate(nodes, List.of(edge(path, 150)), features))
                .as("5.31 m clears DU125, but the DU150 outside envelope requires a detour")
                .extracting(RouteValidationIssue::getCode).contains("FORBIDDEN_CLEARANCE_VIOLATION");

        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        RouteVariant finished = new OfficialDatasetRoutingTest().planner().finish(
                "sized", "engineering", new RegressionRoutePlannerFixture.VariantDraft(nodes, List.of(original),
                        List.of(new RouteConnection("one", "one", new BigDecimal("50"), "connected", null))),
                features, OfficialRunParameters.defaults(), false, router.prepare(features));

        assertThat(finished.isValid()).as("%s", finished.getValidationIssues().stream()
                .map(issue -> issue.getCode() + ":" + issue.getSubjectId() + ":" + issue.getMessage())
                .collect(Collectors.toList())).isTrue();
        assertThat(finished.getConnectedDemandCount()).isEqualTo(1);
        assertThat(finished.getSizingIssues()).isEmpty();
        assertThat(finished.getEdges()).singleElement().satisfies(actual -> {
            assertThat(actual.getDiameter()).isEqualTo(150);
            assertThat(actual.getCoordinates()).isNotEqualTo(original.getCoordinates());
            var before = ExpertChamberGeometryRules.summarize(original.getCoordinates());
            var after = ExpertChamberGeometryRules.summarize(actual.getCoordinates());
            assertThat(after.getFirstBendDistanceM()).isGreaterThanOrEqualTo(2);
            assertThat(ExpertChamberGeometryRules.straightDirections(
                    before.getFirstDx(), before.getFirstDy(), after.getFirstDx(), after.getFirstDy())).isTrue();
        });
        assertThat(validator.validate(finished.getNodes(), finished.getEdges(), features)).isEmpty();
        assertThat(new ExpertChamberRouteValidator().validate(finished.getNodes(), finished.getEdges(),
                new ExistingNetworkSupportIndex(features)::existingDirections)).isEmpty();
        assertThat(ExpertRouteBendRules.validate(finished.getNodes(), finished.getEdges())).isEmpty();
    }

    private RouteEdge edge(List<Coordinate> path, int diameter) {
        var line = rules.line(path);
        return new RouteEdge("edge", "root", "demand:one", line.getLength(),
                path.stream().map(this::coordinate).collect(Collectors.toList()),
                rules.sections(line, List.of()), new BigDecimal("50"), diameter);
    }

    private ImportedOfficialFeature feature(double rotation, String type, String id, String wkt,
            String attributes) throws Exception {
        Geometry geometry = new WKTReader().read(wkt);
        geometry = AffineTransformation.rotationInstance(Math.toRadians(rotation)).transform(geometry);
        geometry = AffineTransformation.translationInstance(400000, 6000000).transform(geometry);
        return new ImportedOfficialFeature(id, type, new ObjectMapper().readTree(attributes), geometry);
    }

    private Coordinate transform(double rotation, double x, double y) {
        double angle = Math.toRadians(rotation);
        return coordinate(new Coordinate(400000 + x * Math.cos(angle) - y * Math.sin(angle),
                6000000 + x * Math.sin(angle) + y * Math.cos(angle))).toCoordinate();
    }

    private RouteCoordinate coordinate(Coordinate point) {
        return new RouteCoordinate(point.x, point.y);
    }
}
