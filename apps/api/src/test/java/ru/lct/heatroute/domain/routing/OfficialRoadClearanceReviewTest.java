package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Контрпримеры независимого review: conservative bounds, depth retry, ложный будущий вход. */
class OfficialRoadClearanceReviewTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test void rotatedPolygonBufferApproximationCannotHideInsufficientClearanceAtIndexThreshold() {
        double angle = Math.toRadians(11.25);
        double[][] local = {{-5, -1}, {5, -1}, {5, 1}, {-5, 1}, {-5, -1}};
        Coordinate[] corners = new Coordinate[local.length];
        for (int i = 0; i < corners.length; i++) corners[i] = c(
                local[i][0] * Math.cos(angle) - local[i][1] * Math.sin(angle),
                local[i][0] * Math.sin(angle) + local[i][1] * Math.cos(angle));
        Polygon polygon = factory.createPolygon(corners);
        double x = Math.round((polygon.getEnvelopeInternal().getMaxX() + 1.738) * 1000) / 1000.0;
        LineString route = line(new Coordinate(x, Math.rint((corners[1].y - 5) * 1000) / 1000),
                new Coordinate(x, Math.rint((corners[1].y + 5) * 1000) / 1000));
        assertThat(route.distance(polygon)).isBetween(1.737, 1.739);
        for (String type : List.of("road", "tram_tracks")) {
            List<ImportedOfficialFeature> features = new ArrayList<>(List.of(feature("near", type, polygon)));
            for (int i = 1; i < 128; i++) features.add(feature("far" + i, type, rectangle(100 + i, 100, 100.5 + i, 100.5)));
            var constraints = rules.baseConstraints(features, 100);
            assertThat(rules.index(constraints).query(route.getEnvelopeInternal()))
                    .extracting(OfficialRouteGeometryRules.Constraint::id).contains("near");
            assertThat(rules.lineAllowed(route, constraints)).isFalse();
        }
    }

    @Test void forbiddenDepthRetryDoesNotUseNullableCrossingAngleOrExtension() {
        for (String type : List.of("road", "tram_tracks")) {
            ImportedOfficialFeature road = feature("road", type, rectangle(0, 0, 10, 10));
            var avoided = rules.depthAvoidanceConstraints(List.of(road), Set.of("road"));
            LineString route = line(c(-10, 20), c(20, 20));
            assertThat(rules.sections(route, avoided)).singleElement()
                    .satisfies(section -> assertThat(section.getKind()).isEqualTo("base"));
            assertThat(rules.lineAllowed(route, avoided)).isTrue();
            assertThat(rules.specialTurnAllowed(c(-10, 20), c(0, 20), c(0, 30), rules.index(avoided))).isTrue();
            OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
            RoutePath path = router.findAvoidingDepthConflicts(c(-10, 20), c(20, 20), 100,
                    router.prepare(List.of(road)), Set.of(), Set.of("road"), List.of());
            assertThat(path).isNotNull();
            assertThat(path.lengthM()).isEqualTo(30);
        }
    }

    @Test void imaginaryExtensionCannotAddAnUncrossedDistantPolygonEntryAngle() {
        double slope = 1 / Math.tan(Math.toRadians(40));
        Polygon second = factory.createPolygon(new Coordinate[] {c(11.9 - 4 * slope, -4), c(25, -4),
                c(25, 4), c(11.9 + 4 * slope, 4), c(11.9 - 4 * slope, -4)});
        Geometry road = factory.createMultiPolygon(new Polygon[] {rectangle(0, -4, 6, 4), second});
        LineString route = line(c(-5, 0), c(9, 0));
        assertThat(road.isValid()).isTrue();
        assertThat(route.distance(second)).isGreaterThan(1.755);
        assertThat(new RoadCrossingClearance().assess(route, road, 1.755, 45, 3).isAllowed()).isTrue();
        var constraints = rules.baseConstraints(List.of(feature("road", "road", road)), 100);
        assertThat(rules.segmentAllowed(route.getCoordinateN(0), route.getCoordinateN(1), constraints)).isTrue();
        assertThat(rules.lineAllowed(route, constraints)).isTrue();
    }

    private ImportedOfficialFeature feature(String id, String type, Geometry geometry) {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", type), geometry);
    }
    private Polygon rectangle(double x1, double y1, double x2, double y2) {
        return factory.createPolygon(new Coordinate[] {c(x1, y1), c(x2, y1), c(x2, y2), c(x1, y2), c(x1, y1)});
    }
    private LineString line(Coordinate... coordinates) { return factory.createLineString(coordinates); }
    private Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
}
