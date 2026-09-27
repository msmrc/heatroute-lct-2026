package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class CorridorRoadGenerationTest {
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TYPES = List.of("road", "tram_tracks");
    private static final double[] ANGLES = {0, 23, 61, 137};
    private static final double[] OFFSETS = {0, 1234};

    @Test
    void movesTheBranchBeyondCompleteProtectionWhileKeepingTransitAndParallelArms() {
        for (String type : TYPES) {
            for (double angle : ANGLES) {
                for (double offset : OFFSETS) assertProtectedBranch(type, angle, offset);
            }
        }
    }

    private void assertProtectedBranch(String type, double angle, double offset) {
        List<Coordinate> points = List.of(
                point(0, -10, angle, offset), point(0, 5.9, angle, offset),
                point(0, 9, angle, offset), point(0, 14, angle, offset),
                point(10, 5.9, angle, offset), point(10, 9, angle, offset));
        List<int[]> links = List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {2, 3},
                new int[] {1, 4}, new int[] {2, 5}, new int[] {5, 4});
        PreparedCorridor checks = corridor(road(angle, offset), type, points.get(0));
        assertThat(checks.chamberRayAllowed(points.get(1), points.get(0))).isFalse();
        assertThat(checks.chamberRayAllowed(points.get(1), points.get(4)))
                .as("parallel arm remains available within 3 m of the road").isTrue();
        CorridorTreeBuilder builder = new CorridorTreeBuilder(
                (from, to) -> checks.chamberRayAllowed(points.get(from), points.get(to)));
        List<int[]> tree = builder.build(points, links, 0, 4, Map.of(3, 1, 4, 1), true, 0, 0);
        assertThat(tree).isNotNull();
        int[] degree = new int[points.size()];
        tree.forEach(edge -> { degree[edge[0]]++; degree[edge[1]]++; });
        assertThat(degree[1]).as("%s rotation=%s offset=%s: protected point remains transit",
                type, angle, offset).isEqualTo(2);
        assertThat(degree[2]).isEqualTo(3);
        List<Coordinate> trunk = CorridorGridPolyline.rounded(
                List.of(points.get(0), points.get(1), points.get(2)));
        assertThat(checks.completeCheckedAssembly(trunk)).isNotNull();
    }

    @Test
    void simplifiesStraightGridRunsBeforeMillimetreRounding() {
        RoadCrossingClearance guard = new RoadCrossingClearance();
        for (String type : TYPES) {
            for (double angle : ANGLES) {
                for (double offset : OFFSETS) {
                    List<Coordinate> raw = List.of(point(0, -10, angle, offset),
                            point(0, -5, angle, offset), point(0, 5, angle, offset),
                            point(0, 10, angle, offset));
                    Geometry source = road(angle, offset);
                    double minimum = "road".equals(type) ? 90 : 45;
                    assertThat(guard.assess(line(raw), source, 2.075, minimum, 3).isAllowed()).isTrue();
                    List<Coordinate> saved = CorridorGridPolyline.rounded(raw);
                    assertThat(corridor(source, type, raw.get(0)).completeCheckedAssembly(saved))
                            .as("%s rotation=%s offset=%s", type, angle, offset).isNotNull();
                    assertThat(guard.assess(line(saved), source, 2.075, minimum, 3).isAllowed()).isTrue();
                }
            }
        }
    }

    @Test
    void retainsRealBendsAndRejectsThemInsideTheProtectiveLength() {
        RoadCrossingClearance guard = new RoadCrossingClearance();
        for (double angle : new double[] {0, 23, 137}) {
            List<Coordinate> raw = List.of(point(0, -10, angle, 1234), point(0, -5, angle, 1234),
                    point(0.02, 5, angle, 1234), point(0, 10, angle, 1234));
            List<Coordinate> saved = CorridorGridPolyline.rounded(raw);
            assertThat(saved).hasSize(4);
            assertThat(guard.assess(line(saved), road(angle, 1234), 2.075, 45, 3).getFailureCode())
                    .isEqualTo("SPECIAL_CROSSING_NOT_STRAIGHT");
        }
        List<Coordinate> reversal = List.of(new Coordinate(0, 0), new Coordinate(0, 5), new Coordinate(0, 2));
        assertThat(CorridorGridPolyline.rounded(reversal)).hasSize(3);
    }

    private static Coordinate point(double x, double y, double angle, double offset) {
        double radians = Math.toRadians(angle);
        return new Coordinate(offset + x * Math.cos(radians) - y * Math.sin(radians),
                2 * offset + x * Math.sin(radians) + y * Math.cos(radians));
    }

    private static Geometry road(double angle, double offset) {
        return GEOMETRY.createPolygon(new Coordinate[] {
                point(-30, -3, angle, offset), point(30, -3, angle, offset),
                point(30, 3, angle, offset), point(-30, 3, angle, offset),
                point(-30, -3, angle, offset)});
    }

    private static PreparedCorridor corridor(Geometry road, String type, Coordinate root) {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        ImportedOfficialFeature feature = new ImportedOfficialFeature("synthetic", "restriction",
                JSON.createObjectNode().put("restriction_type", type), road);
        return router.prepareCorridor(300, router.prepare(List.of(feature)), road.getEnvelopeInternal(), root, null);
    }

    private static LineString line(List<Coordinate> points) {
        return GEOMETRY.createLineString(points.toArray(new Coordinate[0]));
    }
}
