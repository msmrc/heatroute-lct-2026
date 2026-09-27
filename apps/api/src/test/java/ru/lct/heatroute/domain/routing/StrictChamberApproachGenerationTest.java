package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;

/** Генерация соблюдает нормаль камеры и табличный интервал до поворота до независимого допуска. */
class StrictChamberApproachGenerationTest {
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));

    @Test
    void aPermissiveCallerCannotReintroduceDiagonalChamberPorts() {
        var points = new ChamberApproachCandidates().build(new Coordinate(),
                List.of(new Coordinate(10, 0), new Coordinate(-10, 0)), 2.1, 180);
        assertThat(points).hasSize(2);
        assertThat(points).allSatisfy(point -> {
            assertThat(Math.abs(point.x)).isLessThan(1e-9);
            assertThat(Math.abs(Math.abs(point.y) - 2.1)).isLessThan(1e-9);
        });
        assertThat(new ChamberApproachCandidates().build(new Coordinate(),
                List.of(new Coordinate(10, 0), new Coordinate(10, 1)), 2.1, 7.5)).isEmpty();
    }

    @Test
    void choosesOrthogonalAlternativeInsteadOfTheShorterThreeDegreeEntry() {
        RoutePath west = path(-20, 0, 0, 0), north = path(0, 20, 0, 0);
        RoutePath oblique = path(20, 1, 0, 0);
        RoutePath normal = path(20, 1, 20, 0, 0, 0);
        assertThat(CorridorJunctionAssignment.choose(new Coordinate(),
                List.of(List.of(west), List.of(north), List.of(oblique, normal))))
                .containsExactly(west, north, normal);
    }

    @Test
    void aShorterPathWithAForbiddenBodyBendCannotDisplaceTheNormalAlternative() {
        RoutePath west = path(-20, 0, 0, 0);
        RoutePath sharp = path(10, 10, 0, 20, 0, 0);
        RoutePath legal = path(10, 10, 10, 20, 0, 20, 0, 0);
        assertThat(sharp.lengthM()).isLessThan(legal.lengthM());
        assertThat(CorridorJunctionAssignment.choose(new Coordinate(),
                List.of(List.of(west), List.of(sharp, legal)))).containsExactly(west, legal);
    }

    @Test
    void aShallowBodyBendCannotDisplaceTheLegalNormalAlternative() {
        RoutePath west = path(-20, 0, 0, 0);
        RoutePath shallow = path(10, 37.32, 0, 20, 0, 0);
        RoutePath longer = path(10, 37.32, 10, 20, 0, 20, 0, 0);
        assertThat(CorridorJunctionAssignment.choose(new Coordinate(),
                List.of(List.of(west), List.of(shallow, longer)))).containsExactly(west, longer);
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.999, 2.0, 3.0})
    void measuresTheRealBendPastCollinearTechnicalVertices(double distance) {
        RoutePath west = path(-20, 0, 0, 0);
        RoutePath north = path(10, distance, 0, distance, 0, 1, 0, 0.1, 0, 0);
        var result = CorridorJunctionAssignment.choose(new Coordinate(), List.of(List.of(west), List.of(north)));
        if (distance < 2) assertThat(result).isNull();
        else assertThat(result).containsExactly(west, north);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 0.37, 1.57, 3.8})
    void chamberLinkHasNormalPortsAndTwoMeterStraightsAtBothEndsAfterRounding(double angle) {
        Coordinate outer = transform(0, 0, angle), old = transform(20, 20, angle);
        Coordinate junction = transform(25, 20, angle);
        var source = edge(List.of(outer, transform(20, 0, angle), old));
        var node = new RouteNode("outer", "new_branch_chamber", rc(outer), true, false, 0, null);
        var paths = CorridorLinkApproaches.build(source, node, junction, angle, router, router.prepare(List.of()));
        assertThat(paths).hasSizeBetween(2, 8);
        for (RoutePath candidate : paths) {
            List<Coordinate> points = candidate.coordinates();
            Coordinate last = points.get(points.size() - 1), before = points.get(points.size() - 2);
            double rayAngle = Math.atan2(before.y - last.y, before.x - last.x);
            double deviation = Math.abs(Math.IEEEremainder(rayAngle - angle, Math.PI / 2));
            assertThat(Math.toDegrees(deviation)).isLessThan(0.1);
            assertFirstBendDistance(points);
            List<Coordinate> reverse = new ArrayList<>(points);
            java.util.Collections.reverse(reverse);
            assertFirstBendDistance(reverse);
            assertThat(candidate.lengthM()).isGreaterThanOrEqualTo(10);
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 0.37, 1.57})
    void doubleEndedLinkRespectsTheExistingRootAxisAndTheDifferentJunctionFrame(double angle) {
        Coordinate outer = transform(0, 0, angle), junction = transform(30, 20, angle);
        RouteNode node = new RouteNode("outer", "existing_chamber_tie_in", rc(outer), true, true, 2, "existing");
        Coordinate ray = new Coordinate(20 * Math.cos(angle + Math.PI / 4), 20 * Math.sin(angle + Math.PI / 4));
        List<Coordinate> occupied = List.of(ray, new Coordinate(-ray.x, -ray.y));
        var paths = CorridorLinkApproaches.build(edge(List.of(outer, junction)), node, junction, angle,
                router, router.prepare(List.of()), occupied);
        assertThat(paths).isNotEmpty();
        for (RoutePath path : paths) {
            var summary = ExpertChamberGeometryRules.summarize(path.coordinates().stream()
                    .map(StrictChamberApproachGenerationTest::rc).collect(Collectors.toList()));
            double dot = summary.getFirstDx() * ray.x + summary.getFirstDy() * ray.y;
            double cosine = dot / Math.hypot(summary.getFirstDx(), summary.getFirstDy()) / Math.hypot(ray.x, ray.y);
            assertThat(Math.abs(cosine)).isLessThan(0.001);
            assertFirstBendDistance(path.coordinates());
            List<Coordinate> reverse = new ArrayList<>(path.coordinates());
            java.util.Collections.reverse(reverse); assertFirstBendDistance(reverse);
        }
    }

    @Test
    void anObstacleOnOneNormalStillLeavesTheOtherCheckedRootExitAvailable() {
        Coordinate outer = new Coordinate(0, 0), junction = new Coordinate(30, 20);
        RouteNode node = new RouteNode("outer", "existing_chamber_tie_in", rc(outer), true, true, 2, "existing");
        var obstacle = new ImportedOfficialFeature("park", "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", "park"), new GeometryFactory().createPolygon(new Coordinate[] {
                    new Coordinate(4, -8), new Coordinate(9, -8), new Coordinate(9, -3),
                    new Coordinate(4, -3), new Coordinate(4, -8)}));
        var paths = CorridorLinkApproaches.build(edge(List.of(outer, junction)), node, junction, 0,
                router, router.prepare(List.of(obstacle)), List.of(new Coordinate(20, 20), new Coordinate(-20, -20)));
        assertThat(paths).isNotEmpty();
        assertThat(paths).allSatisfy(path -> assertThat(new GeometryFactory()
                .createLineString(path.coordinates().toArray(new Coordinate[0])).distance(obstacle.getMetricGeometry()))
                .isGreaterThanOrEqualTo(1.255 - 1e-6));
    }

    private void assertFirstBendDistance(List<Coordinate> points) {
        double travelled = 0;
        for (int i = 1; i < points.size(); i++) {
            Coordinate a = points.get(i - 1), b = points.get(i);
            travelled += a.distance(b);
            if (i + 1 == points.size()) return;
            Coordinate c = points.get(i + 1);
            double turn = Math.atan2((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x),
                    (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y));
            if (Math.abs(Math.toDegrees(turn)) > 0.1) {
                assertThat(travelled).isGreaterThanOrEqualTo(2 - 1e-7);
                return;
            }
        }
    }

    private RouteEdge edge(List<Coordinate> points) {
        return new RouteEdge("edge", "outer", "junction", new GeometryFactory()
                .createLineString(points.toArray(new Coordinate[0])).getLength(),
                points.stream().map(StrictChamberApproachGenerationTest::rc).collect(Collectors.toList()),
                List.of(), BigDecimal.ONE, 100);
    }
    private RoutePath path(double... xy) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new Coordinate(xy[i], xy[i + 1]));
        return new RoutePath(points, List.of(), new GeometryFactory().createLineString(points.toArray(new Coordinate[0])).getLength());
    }
    private Coordinate transform(double x, double y, double angle) {
        return new Coordinate(410000 + x * Math.cos(angle) - y * Math.sin(angle),
                6170000 + x * Math.sin(angle) + y * Math.cos(angle));
    }
    private static RouteCoordinate rc(Coordinate point) { return new RouteCoordinate(point.x, point.y); }
}
