package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;

class ChamberApproachDiameterGenerationTest {
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));
    private final OfficialRoutingEnvironment environment = router.prepare(List.of());

    @Test
    void rootApproachDoesNotAdmitTheOldTwoMetreStubForLargerDiameter() {
        Coordinate chamber = new Coordinate(0, 0);
        List<Coordinate> rays = List.of(new Coordinate(10, 0), new Coordinate(-10, 0));
        for (int diameter : new int[] {200, 300, 400, 600, 700, 900, 1000, 1400}) {
            RoutePath path = RootChamberApproaches.best(new Coordinate(20, 2.5), chamber, rays,
                    diameter, router, environment, Set.of(), List.of());
            if (path != null) assertThat(summary(path).getLastBendDistanceM())
                    .as("DU%s root approach", diameter).isGreaterThanOrEqualTo(minimum(diameter) - 1e-7);
            RoutePath positive = RootChamberApproaches.best(new Coordinate(20, minimum(diameter) + 0.5),
                    chamber, rays, diameter, router, environment, Set.of(), List.of());
            assertThat(positive).as("DU%s lawful positive", diameter).isNotNull();
        }
    }

    @Test
    void fallbackUsesTheDiameterOfTheRebuiltEdge() {
        RouteNode terminal = new RouteNode("demand:x", "demand_connection", new RouteCoordinate(20, 20),
                false, false, 0, null);
        for (int diameter : new int[] {50, 300, 600, 900, 1400}) {
            RouteEdge edge = new RouteEdge("test", "chamber", terminal.getId(), Math.hypot(20, 20),
                    List.of(new RouteCoordinate(0, 0), terminal.getCoordinate()), List.of(), BigDecimal.ONE, diameter);
            List<RoutePath> paths = ChamberTerminalFallback.build(edge, terminal, new Coordinate(0, 0), 0,
                    router, environment, List.of());
            assertThat(paths).isNotEmpty();
            for (RoutePath path : paths) assertThat(summary(path).getLastBendDistanceM())
                    .as("DU%s fallback", diameter).isGreaterThanOrEqualTo(minimum(diameter) - 1e-7);
        }
    }

    @Test
    void depthDetourPreservesLongerDiameterSpecificApproachWithoutRestrictingTheDemandTail() {
        RouteNode chamber = new RouteNode("chamber", "new_branch_chamber", new RouteCoordinate(0, 0),
                true, false, 0, null);
        RouteNode terminal = new RouteNode("demand:x", "demand_connection", new RouteCoordinate(20, 2.5),
                false, false, 0, null);
        RouteEdge original = new RouteEdge("reroute", chamber.getId(), terminal.getId(), 22.5,
                List.of(chamber.getCoordinate(), new RouteCoordinate(0, 2.5), terminal.getCoordinate()),
                List.of(), BigDecimal.ONE, 300);
        RoutePath replacement = DepthChamberApproaches.find(original,
                Map.of(chamber.getId(), chamber, terminal.getId(), terminal), router, environment,
                Set.of(), Set.of(), List.of());
        assertThat(replacement).isNotNull();
        assertThat(summary(replacement).getFirstBendDistanceM()).isGreaterThanOrEqualTo(3 - 1e-7);
        assertThat(summary(replacement).hasInvalidBendAngle()).isFalse();
    }

    @Test
    void assignmentUsesEachBranchDiameterAndAllowsShortInteriorLegs() {
        Coordinate chamber = new Coordinate(0, 0);
        RoutePath small = path(new Coordinate(-10, 2.5), new Coordinate(0, 2.5), chamber);
        RoutePath large = path(new Coordinate(20, 1), new Coordinate(6.5, 1), new Coordinate(6.5, 0), chamber);
        List<List<RoutePath>> choices = List.of(List.of(small), List.of(large));
        assertThat(CorridorJunctionAssignment.choose(chamber, choices, List.of(50, 1400))).hasSize(2);
        assertThat(CorridorJunctionAssignment.choose(chamber, choices, List.of(1400, 1400))).isNull();
    }

    @Test
    void normalTransitionCanUseAShortMiddleAndAFullSixMetreChamberTail() {
        Coordinate start = new Coordinate(0, 0);
        List<List<Coordinate>> candidates = NormalCorridorTransitions.build(start, new Coordinate(5, 0),
                new Coordinate(20, 1), 0, 6.1);
        assertThat(candidates).anySatisfy(points -> {
            assertThat(points).hasSize(4);
            assertThat(points.get(1).distance(points.get(2))).isLessThan(2);
            assertThat(points.get(2).distance(points.get(3))).isGreaterThanOrEqualTo(6.1 - 1e-7);
            RoutePath candidate = path(start, points.get(0), points.get(1), points.get(2), points.get(3));
            assertThat(summary(candidate).hasInvalidBendAngle()).isFalse();
        });
    }

    @Test
    void diameterApproachesRemainValidAfterRotationAndMetricTranslation() {
        for (double rotation : new double[] {0, 0.37, 1.1}) {
            for (double offset : new double[] {0, 410000}) {
                Coordinate chamber = transform(0, 0, rotation, offset);
                for (int diameter : new int[] {150, 200, 400, 700, 1000}) {
                    Coordinate target = transform(20, 20, rotation, offset);
                    RouteNode terminal = new RouteNode("demand:x", "demand_connection",
                            new RouteCoordinate(target.x, target.y), false, false, 0, null);
                    RouteEdge edge = new RouteEdge("rotated", "chamber", terminal.getId(),
                            chamber.distance(target), List.of(new RouteCoordinate(chamber.x, chamber.y),
                                    terminal.getCoordinate()), List.of(), BigDecimal.ONE, diameter);
                    List<RoutePath> alternatives = ChamberTerminalFallback.build(edge, terminal, chamber,
                            rotation, router, environment, List.of());
                    assertThat(alternatives).as("DU%s, rotation=%s, offset=%s", diameter, rotation, offset)
                            .isNotEmpty();
                    for (RoutePath candidate : alternatives) {
                        ExpertChamberGeometryRules.PolylineSummary actual = summary(candidate);
                        assertThat(actual.getLastBendDistanceM()).isGreaterThanOrEqualTo(minimum(diameter));
                        assertThat(actual.hasInvalidBendAngle()).isFalse();
                        assertThat(ExpertChamberGeometryRules.compatibleRays(actual.getLastDx(),
                                actual.getLastDy(), Math.cos(rotation), Math.sin(rotation))
                                || ExpertChamberGeometryRules.compatibleRays(actual.getLastDx(),
                                actual.getLastDy(), -Math.sin(rotation), Math.cos(rotation))).isTrue();
                    }
                }
            }
        }
    }

    private static Coordinate transform(double x, double y, double rotation, double offset) {
        return new Coordinate(offset + x * Math.cos(rotation) - y * Math.sin(rotation),
                offset + x * Math.sin(rotation) + y * Math.cos(rotation));
    }

    private static RoutePath path(Coordinate... points) {
        double length = 0;
        for (int index = 1; index < points.length; index++) length += points[index - 1].distance(points[index]);
        return new RoutePath(List.of(points), List.of(), length);
    }

    private static ExpertChamberGeometryRules.PolylineSummary summary(RoutePath path) {
        return ExpertChamberGeometryRules.summarize(path.coordinates().stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()));
    }

    private static double minimum(int diameter) {
        if (diameter <= 150) return 2;
        if (diameter <= 300) return 3;
        if (diameter <= 600) return 4;
        if (diameter <= 900) return 5;
        return 6;
    }
}
