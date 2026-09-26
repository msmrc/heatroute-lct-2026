package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class CorridorRoadTurnAdmissionTest {
    private static final GeometryFactory FACTORY = new GeometryFactory();

    @Test
    void searchesBeyondTheCheaperTurnInsideRoadProtection() throws Exception {
        for (double angle : new double[] {0, 35, 119}) {
            for (double offset : new double[] {0, 414000}) {
                for (double jitter : new double[] {0, 2e-9, -2e-9}) {
                    AffineTransformation transform = AffineTransformation.rotationInstance(Math.toRadians(angle));
                    transform.translate(offset, offset == 0 ? 0 : 6173000);
                    List<Coordinate> points = new ArrayList<>();
                    for (Coordinate point : List.of(new Coordinate(0, -10), new Coordinate(0, 8),
                            new Coordinate(10, 8), new Coordinate(10, 15), new Coordinate(-5, -10),
                            new Coordinate(-5, 10), new Coordinate(10, 10))) {
                        Coordinate transformed = transform.transform(point, new Coordinate());
                        transformed.x += jitter;
                        points.add(transformed);
                    }
                    Geometry road = transform.transform(new WKTReader().read(
                            "POLYGON ((-100 0,100 0,100 6,-100 6,-100 0))"));
                    RoadCrossingClearance crossing = new RoadCrossingClearance();
                    PreparedCorridor checks = corridor(road, points.get(0));
                    List<Coordinate> rounded = new ArrayList<>();
                    for (Coordinate point : points) rounded.add(new RouteCoordinate(point.x, point.y).toCoordinate());
                    CorridorTreeBuilder builder = new CorridorTreeBuilder(
                            (from, to) -> checks.chamberRayAllowed(rounded.get(from), rounded.get(to)),
                            (before, at, after) -> checks.turnAllowed(
                                    rounded.get(before), rounded.get(at), rounded.get(after)));
                    List<int[]> links = List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {2, 3},
                            new int[] {0, 4}, new int[] {4, 5}, new int[] {5, 6}, new int[] {6, 3});
                    List<Double> weights = new ArrayList<>();
                    for (int[] link : links) weights.add(points.get(link[0]).distance(points.get(link[1])));
                    List<List<int[]>> trees = List.of(
                            builder.build(points, links, 0, 1, Map.of(3, 1), true, 0, 0),
                            builder.buildWeighted(points, links, weights, 0, 1, Map.of(3, 1), true, 0, 0),
                            builder.buildMetricClosure(points, links, 0, 1, Map.of(3, 1), 0),
                            builder.buildMetricClosureWeighted(points, links, weights, 0, 1, Map.of(3, 1), 0));
                    for (List<int[]> tree : trees) {
                        LineString path = path(points, tree);
                        RoadCrossingClearance.Assessment assessment = crossing.assess(path, road, 1.8, 45, 3);
                        if (!assessment.isAllowed()) {
                            throw new AssertionError("Rejected selected tree angle=" + angle + " offset=" + offset
                                    + " jitter=" + jitter + " code=" + assessment.getFailureCode() + " " + path);
                        }
                        if (tree.stream().anyMatch(edge -> edge[0] == 1 || edge[1] == 1)) {
                            throw new AssertionError("Illegal short branch was selected");
                        }
                        assertThat(checks.completeCheckedAssembly(CorridorGridPolyline.rounded(Arrays.asList(path.getCoordinates())))).isNotNull();
                    }
                }
            }
        }
    }

    @Test
    void keepsCollinearTransitAndLawfulParallelSegmentsAvailable() throws Exception {
        Geometry road = new WKTReader().read("POLYGON ((-100 0,100 0,100 6,-100 6,-100 0))");
        for (List<Coordinate> points : List.of(
                List.of(new Coordinate(0, -10), new Coordinate(0, 2), new Coordinate(0, 10)),
                List.of(new Coordinate(-10, -2.5), new Coordinate(0, -2.5), new Coordinate(10, -2.5)))) {
            PreparedCorridor checks = corridor(road, points.get(0));
            CorridorTreeBuilder builder = new CorridorTreeBuilder((from, to) -> true,
                    (before, at, after) -> checks.turnAllowed(points.get(before), points.get(at), points.get(after)));
            List<int[]> tree = builder.build(points, List.of(new int[] {0, 1}, new int[] {1, 2}),
                    0, 1, Map.of(2, 1), true, 0, 0);
            assertThat(tree).hasSize(2);
            assertThat(checks.completeCheckedAssembly(CorridorGridPolyline.rounded(points))).isNotNull();
        }
    }

    @Test
    void refusesAnIllegalContinuationOfAnAlreadyAttachedLeaf() {
        List<Coordinate> points = List.of(new Coordinate(0, 0), new Coordinate(0, 5),
                new Coordinate(5, 5));
        CorridorTreeBuilder builder = new CorridorTreeBuilder((from, to) -> true,
                (before, at, after) -> !(before == 0 && at == 1 && after == 2));
        assertThat(builder.build(points, List.of(new int[] {0, 1}, new int[] {1, 2}),
                0, 1, Map.of(1, 1, 2, 1), false, 0, 0)).isNull();
    }

    private static PreparedCorridor corridor(Geometry road, Coordinate root) {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        ImportedOfficialFeature feature = new ImportedOfficialFeature("road-fixture", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "road"), road);
        return router.prepareCorridor(50, router.prepare(List.of(feature)), road.getEnvelopeInternal(), root, null);
    }

    private static LineString path(List<Coordinate> points, List<int[]> tree) {
        Map<Integer, List<Integer>> neighbors = new HashMap<>();
        for (int[] edge : tree) {
            neighbors.computeIfAbsent(edge[0], ignored -> new ArrayList<>()).add(edge[1]);
            neighbors.computeIfAbsent(edge[1], ignored -> new ArrayList<>()).add(edge[0]);
        }
        List<Coordinate> result = new ArrayList<>();
        int previous = -1;
        int at = 0;
        result.add(points.get(at));
        while (at != 3) {
            int next = -1;
            for (int neighbor : neighbors.get(at)) if (neighbor != previous) next = neighbor;
            if (next < 0) throw new AssertionError("Disconnected tree");
            result.add(points.get(next));
            previous = at;
            at = next;
        }
        return FACTORY.createLineString(result.toArray(new Coordinate[0]));
    }
}
