package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;

/** Нет переноса камеры: исправляется последний луч с сохранением внешних концов. */
class StationaryChamberApproachesTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @ParameterizedTest
    @ValueSource(doubles = {0, 31, 90, 217})
    void offersAnAxisAlignedRetainedTailWithoutMovingTheCamera(double degrees) {
        var camera = point(degrees, 0, 0);
        var original = path(degrees, 88, 20, 98, 20, 98, 2, 0, 0);
        var terminal = new RouteNode("demand", "demand_connection", coordinate(original.coordinates().get(0)), false, false, 0, null);
        for (boolean reversed : List.of(false, true)) {
            List<Coordinate> points = new ArrayList<>(original.coordinates());
            if (reversed) Collections.reverse(points);
            var edge = edge(reversed ? "camera" : "demand", reversed ? "demand" : "camera", points);
            var env = router.prepare(List.of());
            var ordinary = CorridorRetainedTerminalApproaches.build(edge, terminal, camera, Math.toRadians(degrees),
                    router, env, List.of(), List.of());
            assertThat(ordinary).hasSize(1);
            var quality = CorridorRetainedTerminalApproaches.buildForChamberQuality(edge, terminal, camera, Math.toRadians(degrees),
                    router, env, List.of(), List.of());
            assertThat(quality).hasSizeLessThanOrEqualTo(8);
            assertThat(quality).anyMatch(p -> p.coordinates().equals(ordinary.get(0).coordinates()));
            var fixed = List.of(List.of(path(degrees, -100, 0, 0, 0)), List.of(path(degrees, 0, 50, 0, 0)), quality);
            var chosen = CorridorJunctionAssignment.choosePrecise(camera, fixed);
            assertThat(chosen).isNotNull().hasSize(3);
            assertThat(new EngineeringRouteEvaluator().evaluate(asEdges(chosen)).irregularJunctionAngleCount()).isZero();
            assertThat(new EngineeringRouteEvaluator().evaluate(asEdges(chosen)).isCompliant()).isTrue();
            assertThat(chosen.get(2).coordinates().get(0)).isEqualTo(original.coordinates().get(0));
            assertThat(chosen.get(2).coordinates().get(chosen.get(2).coordinates().size() - 1)).isEqualTo(camera);
            assertThat(edge.getCoordinates()).usingRecursiveFieldByFieldElementComparator()
                    .containsExactlyElementsOf(points.stream().map(this::coordinate).collect(Collectors.toList()));
            assertThat(CorridorRetainedTerminalApproaches.buildForChamberQuality(edge, terminal, camera, Math.toRadians(degrees),
                    router, env, List.of(), List.of()).stream().map(RoutePath::coordinates).collect(Collectors.toList()))
                    .isEqualTo(quality.stream().map(RoutePath::coordinates).collect(Collectors.toList()));
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 31, 90, 217})
    void preciseSearchDoesNotLetShorterObliqueRayDisplaceAnOrthogonalChoice(double degrees) {
        var oblique = path(degrees, 98, 2, 0, 0);
        var orthogonal = path(degrees, 98, 2, 98, 0, 0, 0);
        var fixed = List.of(List.of(path(degrees, -100, 0, 0, 0)), List.of(path(degrees, 0, 50, 0, 0)),
                List.of(oblique, orthogonal));
        assertThat(CorridorJunctionAssignment.choose(point(degrees, 0, 0), fixed).get(2)).isSameAs(orthogonal);
        assertThat(CorridorJunctionAssignment.choosePrecise(point(degrees, 0, 0), fixed).get(2)).isSameAs(orthogonal);
        assertThat(CorridorJunctionAssignment.choosePrecise(point(degrees, 0, 0),
                List.of(fixed.get(0), fixed.get(1), List.of(oblique)))).isNull();
    }

    @Test
    void bothSearchesPermitOnlyCoordinateRoundingAtAdjacentAndOppositeRays() {
        var junction = point(0, 0, 0);
        for (double delta : new double[] {-0.001, -0.0001, 0.0001, 0.001}) {
            double angle = Math.toRadians(90 + delta);
            var tilted = path(0, 1000 * Math.cos(angle), 1000 * Math.sin(angle), 0, 0);
            var east = path(0, 1000, 0, 0, 0);
            var west = path(0, -1000, 0, 0, 0);
            var choices = List.of(List.of(east), List.of(west), List.of(tilted));
            var ordinary = CorridorJunctionAssignment.choose(junction, choices);
            var precise = CorridorJunctionAssignment.choosePrecise(junction, choices);
            if (Math.abs(delta) <= 0.001) {
                assertThat(precise).hasSize(3); assertThat(ordinary).hasSize(3);
            } else {
                assertThat(precise).isNull(); assertThat(ordinary).isNull();
            }
            double opposite = Math.toRadians(180 + delta);
            var almostWest = path(0, 1000 * Math.cos(opposite), 1000 * Math.sin(opposite), 0, 0);
            var pair = CorridorJunctionAssignment.choosePrecise(junction, List.of(List.of(east), List.of(almostWest)));
            if (Math.abs(delta) < 0.001) assertThat(pair).hasSize(2);
            else assertThat(pair).isNull();
        }
    }

    @Test
    void qualityRankingIgnoresDeviationWithinRoundingToleranceButRetainsActualExcess() {
        for (double delta : new double[] {0.4, 0.6}) {
            double angle = Math.toRadians(90 + delta);
            var evaluation = new EngineeringRouteEvaluator().evaluate(asEdges(List.of(
                    path(0, 1000, 0, 0, 0), path(0, -1000, 0, 0, 0),
                    path(0, 1000 * Math.cos(angle), 1000 * Math.sin(angle), 0, 0))));
            assertThat(evaluation.totalJunctionAngleDeviation()).isPositive();
            if (delta < 0.5) {
                assertThat(evaluation.irregularJunctionAngleCount()).isZero();
                assertThat(evaluation.excessJunctionAngleDeviation()).isZero();
            } else {
                assertThat(evaluation.irregularJunctionAngleCount()).isEqualTo(2);
                assertThat(evaluation.excessJunctionAngleDeviation()).isBetween(0.199, 0.201);
            }
        }
    }

    @Test
    void preciseSearchRetainsCancellationAndIntersectionRejection() {
        var north = path(0, 0, 50, 0, 0);
        var crossing = path(0, 10, 20, -10, 20, -10, -20, 0, -20, 0, 0);
        assertThat(CorridorJunctionAssignment.choosePrecise(point(0, 0, 0), List.of(List.of(north), List.of(crossing)))).isNull();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> CorridorJunctionAssignment.choosePrecise(point(0, 0, 0), List.of(List.of(north), List.of(crossing))))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private List<RouteEdge> asEdges(List<RoutePath> paths) {
        List<RouteEdge> result = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) result.add(edge("outer-" + i, "camera", paths.get(i).coordinates()));
        return result;
    }
    private RouteEdge edge(String from, String to, List<Coordinate> points) {
        return new RouteEdge(from, from, to, rules.line(points).getLength(),
                points.stream().map(this::coordinate).collect(Collectors.toList()), rules.sections(rules.line(points), List.of()), null, 50);
    }
    private RouteCoordinate coordinate(Coordinate p) { return new RouteCoordinate(p.x, p.y); }
    private RoutePath path(double degrees, double... xy) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(point(degrees, xy[i], xy[i + 1]));
        return new RoutePath(points, rules.sections(rules.line(points), List.of()), rules.line(points).getLength());
    }
    private Coordinate point(double degrees, double x, double y) {
        double a = Math.toRadians(degrees);
        return new RouteCoordinate(414000 + x * Math.cos(a) - y * Math.sin(a),
                6173000 + x * Math.sin(a) + y * Math.cos(a)).toCoordinate();
    }
}
