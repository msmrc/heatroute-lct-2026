package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.math.BigDecimal;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Геометрическая граница обязательного ввода не должна обрывать допустимый прямой переход. */
class OfficialTerminalRoadContinuationTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final Coordinate connection = c(-1, 0);
    private final ImportedOfficialFeature building = polygon("own", "oks", -10, -10, 0, 10);
    private final ImportedOfficialFeature road = polygon("road", "road", 4, -10, 6, 10);

    @Test void nearestNormalIsNotDiscardedBecauseItsExitSplitsACrossing() {
        var expected = rules.normalEgress(List.of(building), 100, connection).orElseThrow();
        var actual = rules.normalEgress(List.of(building, road), 100, connection).orElseThrow();
        assertThat(actual.exit()).isEqualTo(expected.exit());
    }

    @Test void directedSearchUsesTheRealMandatoryPrefixForCrossingCompletion() {
        var egress = rules.normalEgress(List.of(building), 100, connection).orElseThrow();
        var environment = router.prepare(List.of(building, road));
        RoutePath outside = router.findAfter(egress.start(), egress.exit(), c(20, 0), 100,
                environment, Set.of(), RoutePreference.SHORTEST, List.of());
        assertThat(outside).isNotNull();
        assertThat(outside.coordinates()).hasSize(2);
        assertThat(router.withCheckedTerminalPrefix(egress, outside, 100, environment)).isNotNull();
    }

    @Test void combinedPrefixHasOneFullEightMetreSpecial() {
        var egress = rules.normalEgress(List.of(building), 100, connection).orElseThrow();
        var environment = router.prepare(List.of(building, road));
        List<Coordinate> points = List.of(egress.exit(), c(20, 0));
        RoutePath outside = new RoutePath(points, List.of(), egress.exit().distance(c(20, 0)));
        RoutePath complete = router.withCheckedTerminalPrefix(egress, outside, 100, environment);
        assertThat(complete).isNotNull();
        assertThat(complete.sections()).filteredOn(section -> "special".equals(section.getKind()))
                .singleElement().satisfies(section -> assertThat(section.getLengthM()).isEqualByComparingTo("8"));
    }

    @Test void prefixDoesNotAuthorizeATurnInsideTheRoadOrAShortProtectiveExit() {
        var egress = rules.normalEgress(List.of(building), 100, connection).orElseThrow();
        var environment = router.prepare(List.of(building, road));
        for (List<Coordinate> points : List.of(
                List.of(egress.exit(), c(5.505, 20)), List.of(egress.exit(), c(8, 0)))) {
            RoutePath outside = new RoutePath(points, List.of(), rules.line(points).getLength());
            assertThat(router.withCheckedTerminalPrefix(egress, outside, 100, environment)).isNull();
        }
    }

    @Test void roadAndTramContinueInEverySearchPreference() {
        for (String type : List.of("road", "tram_tracks")) {
            assertThat(rules.hasConstraintRule(type)).isTrue();
            var crossing = polygon("crossing", type, 4, -10, 6, 10);
            var environment = router.prepare(List.of(building, crossing));
            var egress = environment.normalEgress(100, connection).orElseThrow();
            for (RoutePreference preference : RoutePreference.values()) {
                RoutePath outside = router.findAfter(egress.start(), egress.exit(), c(20, 0), 100,
                        environment, Set.of(), preference, List.of());
                assertThat(outside).as("%s/%s", type, preference).isNotNull();
                assertThat(outside.coordinates()).containsExactly(egress.exit(), c(20, 0));
                assertThat(router.withCheckedTerminalPrefix(egress, outside, 100, environment)).isNotNull();
            }
        }
    }

    @Test void regularizationAndDepthRecoveryKeepTheRealPrefix() {
        var egress = rules.normalEgress(List.of(building), 100, connection).orElseThrow();
        var environment = router.prepare(List.of(building, road));
        RoutePath regularized = router.regularizeAfter(egress.start(),
                List.of(egress.exit(), c(10, 0), c(20, 0)), 100, environment, Set.of(), List.of());
        RoutePath recovered = router.findAfterAvoidingDepthConflicts(egress.start(), egress.exit(), c(20, 0),
                100, environment, Set.of(), Set.of(), List.of());
        assertThat(regularized).isNotNull();
        assertThat(recovered).isNotNull();
        for (RoutePath outside : List.of(regularized, recovered)) {
            assertThat(router.withCheckedTerminalPrefix(egress, outside, 100, environment)).isNotNull();
        }
        assertThat(router.findAfterAvoidingDepthConflicts(egress.start(), egress.exit(), c(20, 0),
                100, environment, Set.of(), Set.of("road"), List.of())).isNull();
    }

    @Test void completePathPassesIndependentGeometryInEitherDirection() {
        var egress = rules.normalEgress(List.of(building), 100, connection).orElseThrow();
        var environment = router.prepare(List.of(building, road));
        RoutePath outside = new RoutePath(List.of(egress.exit(), c(20, 0)), List.of(), 14.495);
        RoutePath complete = router.withCheckedTerminalPrefix(egress, outside, 100, environment);
        assertThat(complete).isNotNull();
        for (RoutePath path : List.of(complete, complete.reversed())) {
            var edge = new RouteEdge("edge", "from", "to", path.lengthM(), path.coordinates().stream()
                    .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()),
                    path.sections(), BigDecimal.ONE, 100);
            assertThat(rules.validate(edge, rules.line(path.coordinates()),
                    rules.baseConstraints(List.of(road), 100))).isEmpty();
        }
        assertThat(rules.lineAllowed(rules.line(outside.coordinates()), rules.baseConstraints(List.of(road), 100)))
                .as("outside-only is still not a completed crossing").isFalse();
    }

    @Test void foreignObstaclesAndLaterParallelClearanceAreNotExempted() {
        var egress = rules.normalEgress(List.of(building), 100, connection).orElseThrow();
        RoutePath straight = new RoutePath(List.of(egress.exit(), c(20, 0)), List.of(), 14.495);
        for (var foreign : List.of(polygon("foreign", "park", 0.5, -0.5, 1, 0.5),
                polygon("foreign", "park", 12, -1, 14, 1))) {
            assertThat(router.withCheckedTerminalPrefix(egress, straight, 100,
                    router.prepare(List.of(building, road, foreign)))).isNull();
        }
        List<Coordinate> laterParallel = List.of(egress.exit(), c(20, 0), c(20, 15), c(7.7, 15), c(7.7, 1));
        assertThat(router.withCheckedTerminalPrefix(egress,
                new RoutePath(laterParallel, List.of(), rules.line(laterParallel).getLength()),
                100, router.prepare(List.of(building, road)))).isNull();
    }

    @Test void spatialQueryIncludesRoadOnLongRealPrefix() {
        // Дорога вне окна наружного пути; защитное начало -1002 м отсутствует в настоящем префиксе.
        var distant = polygon("distant-road", "road", -999, -10, -998, 10);
        var environment = router.prepare(List.of(), new InMemoryRoutingFeatureSource(List.of(distant)));
        assertThat(router.regularizeAfter(c(-1000, 0), List.of(c(0, 0), c(20, 0)),
                100, environment, Set.of(), List.of())).isNull();
    }

    @Test void unfinishedExitCannotInventMissingProtectionBeforeTheConnection() {
        var tooClose = polygon("too-close", "road", 1, -10, 2, 10);
        // До входа в дорогу только 2 м: продолжение наружу не может добавить недостающий метр позади.
        var egress = rules.normalEgress(List.of(building, tooClose), 100, connection).orElseThrow();
        assertThat(egress.exit().x).isLessThan(connection.x);
    }

    @Test void finalNetworkValidatorAcceptsTheCompleteDemandApproach() {
        var environment = router.prepare(List.of(building, road));
        var egress = environment.normalEgress(100, connection).orElseThrow();
        var outside = router.findAfter(egress.start(), egress.exit(), c(20, 0), 100,
                environment, Set.of(), RoutePreference.SHORTEST, List.of());
        assertThat(outside).isNotNull();
        var complete = router.withCheckedTerminalPrefix(egress, outside, 100, environment);
        assertThat(complete).isNotNull();
        RoutePath path = complete.reversed();
        List<RouteCoordinate> coordinates = path.coordinates().stream()
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList());
        var nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", coordinates.get(0), true, true, 1, "root"),
                new RouteNode("demand", "demand_connection", coordinates.get(coordinates.size() - 1),
                        false, false, 0, "demand"));
        var edge = new RouteEdge("edge", "root", "demand", path.lengthM(), coordinates,
                path.sections(), BigDecimal.ONE, 100);
        assertThat(new OfficialRouteValidator(rules).validate(nodes, List.of(edge), List.of(building, road))).isEmpty();
    }

    @Test void terminalCandidateChecksCompletedPartsBeforeItsUnfinishedEnd() {
        var nearby = polygon("nearby", "road", 10, 1.7, 20, 5);
        var source = factory.createMultiPolygon(new org.locationtech.jts.geom.Polygon[] {
                (org.locationtech.jts.geom.Polygon) road.getMetricGeometry(),
                (org.locationtech.jts.geom.Polygon) nearby.getMetricGeometry()});
        assertThat(new RoadCrossingClearance().terminalPrefixAllowed(rules.line(List.of(connection, c(15, 0))),
                source, 1.755, 45, 3)).isFalse();
    }

    private ImportedOfficialFeature polygon(String id, String type, double x1, double y1, double x2, double y2) {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", type), factory.createPolygon(new Coordinate[] {
                        c(x1, y1), c(x2, y1), c(x2, y2), c(x1, y2), c(x1, y1)}));
    }
    private Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
}
