package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Полный переход не теряется при построении и сохранении коридорного ввода через границу префикса. */
class CorridorTerminalRoadContinuationTest {
    private static final int DIAMETER = 100;
    private final GeometryFactory factory = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final Coordinate connection = c(-1, 0);
    private final Coordinate port = c(20, 0);
    private final ImportedOfficialFeature building = feature("own", "oks", rectangle(-10, -10, 0, 10));

    @Test
    void freshCorridorRouteCompletesRoadSplitAtMandatoryExit() {
        List<ImportedOfficialFeature> features = features("road");
        assertCompletedCrossing(corridor(features).route("demand", connection, port, DIAMETER), features);
    }

    @Test
    void freshCorridorAlternativesCompleteTramSplitAtMandatoryExit() {
        List<ImportedOfficialFeature> features = features("tram_tracks");
        assertThat(corridor(features).localAlternatives("demand", connection, port, DIAMETER))
                .anySatisfy(path -> assertCompletedCrossing(path, features));
    }

    @Test
    void retainedRoadApproachSurvivesSplitInsideCrossing() {
        List<ImportedOfficialFeature> features = features("road");
        assertThat(retained(List.of(connection, port), features, false))
                .singleElement().satisfies(path -> assertCompletedCrossing(path, features));
    }

    @Test
    void retainedTramApproachSurvivesReversedIncumbent() {
        List<ImportedOfficialFeature> features = features("tram_tracks");
        assertThat(retained(List.of(connection, port), features, true))
                .singleElement().satisfies(path -> assertCompletedCrossing(path, features));
    }

    @Test
    void corridorAndRetainedPathsWithoutRoadRemainAvailable() {
        List<ImportedOfficialFeature> features = List.of(building);
        assertThat(corridor(features).route("demand", connection, port, DIAMETER)).isNotNull();
        assertThat(retained(List.of(connection, port), features, false)).hasSize(1);
    }

    @Test
    void completeReferencePathsPassFinalValidationForRoadAndTram() {
        for (String type : List.of("road", "tram_tracks")) {
            List<ImportedOfficialFeature> features = features(type);
            OfficialRoutingEnvironment environment = router.prepare(features);
            var egress = environment.normalEgress(DIAMETER, connection).orElseThrow();
            RoutePath outside = new RoutePath(List.of(egress.exit(), port), List.of(), egress.exit().distance(port));
            assertCompletedCrossing(router.withCheckedCorridorTerminalPrefix(
                    egress, outside, DIAMETER, environment), features);
        }
    }

    @Test
    void ordinaryCorridorStillRejectsOutsideOnlyClippedCrossing() {
        for (String type : List.of("road", "tram_tracks")) {
            OfficialRoutingEnvironment environment = router.prepare(features(type));
            Coordinate exit = environment.normalEgress(DIAMETER, connection).orElseThrow().exit();
            assertThat(router.prepareCorridor(DIAMETER, environment, new Envelope(exit, port), exit, null)
                    .path(List.of(exit, port))).as(type).isNull();
        }
    }

    @Test
    void freshCorridorRejectsPortBeforeProtectiveExitEnds() {
        for (String type : List.of("road", "tram_tracks")) {
            assertThat(corridor(features(type)).route("demand", connection, c(8, 0), DIAMETER))
                    .as(type).isNull();
        }
    }

    @Test
    void retainedApproachRejectsShortProtectiveExit() {
        for (String type : List.of("road", "tram_tracks")) {
            assertThat(retained(List.of(connection, c(8, 0)), features(type), false)).as(type).isEmpty();
        }
    }

    @Test
    void retainedApproachRejectsTurnInsideRoad() {
        for (String type : List.of("road", "tram_tracks")) {
            List<ImportedOfficialFeature> features = features(type);
            Coordinate exit = router.prepare(features).normalEgress(DIAMETER, connection).orElseThrow().exit();
            assertThat(retained(List.of(connection, exit, new Coordinate(exit.x, connection.y + 20)), features, false))
                    .as(type).isEmpty();
        }
    }

    @Test
    void retainedPrefixAndOutsideStillRespectForeignForbiddenObjects() {
        for (Geometry obstacle : List.of(rectangle(0.5, -0.5, 1, 0.5), rectangle(12, -1, 14, 1))) {
            List<ImportedOfficialFeature> features = new ArrayList<>(features("road"));
            features.add(feature("foreign", "park", obstacle));
            assertThat(retained(List.of(connection, port), features, false)).isEmpty();
        }
    }

    @Test
    void completedPrefixCrossingCannotExemptLaterParallelClearanceViolation() {
        Geometry road = roadWithParallelComponent(1.0);
        var prefix = rules.line(List.of(connection, port));
        RoadCrossingClearance guard = new RoadCrossingClearance();
        // Первый crossing и обе его защиты полностью закончены до близкого второго компонента.
        assertThat(guard.assess(prefix, road, 1.755, 45, 3).getFailureCode())
                .isEqualTo("SPECIAL_PARALLEL_CLEARANCE_VIOLATION");
        assertThat(guard.terminalPrefixAllowed(prefix, road, 1.755, 45, 3))
                .as("a future suffix cannot repair clearance already violated by this prefix").isFalse();
    }

    @Test
    void completedPrefixCrossingWithEnoughLaterClearanceRemainsAllowed() {
        Geometry road = roadWithParallelComponent(2.0);
        var prefix = rules.line(List.of(connection, port));
        RoadCrossingClearance guard = new RoadCrossingClearance();
        assertThat(guard.assess(prefix, road, 1.755, 45, 3).isAllowed()).isTrue();
        assertThat(guard.terminalPrefixAllowed(prefix, road, 1.755, 45, 3)).isTrue();
    }

    @Test
    void completedCrossingMayBeFollowedByAnUnfinishedProtectiveEntry() {
        Geometry road = factory.createMultiPolygon(new Polygon[] {
                rectangle(4, -10, 6, 10), rectangle(21, -10, 23, 10)});
        RoadCrossingClearance guard = new RoadCrossingClearance();
        // Префикс заканчивается в защите второго crossing; реальное прямое продолжение допустимо.
        assertThat(guard.assess(rules.line(List.of(connection, c(30, 0))), road, 1.755, 45, 3).isAllowed())
                .isTrue();
        assertThat(guard.terminalPrefixAllowed(rules.line(List.of(connection, port)), road, 1.755, 45, 3))
                .as("a completed earlier crossing must not suppress the next unfinished entry").isTrue();
    }

    @Test
    void legalRoadCrossingCannotOverrideContainingSocialArea() {
        ImportedOfficialFeature site = feature("own-site", "social_area", rectangle(-10, -10, 20, 10));
        Geometry safeRoad = roadWithParallelComponent(2.0), unsafeRoad = roadWithParallelComponent(1.0);
        assertThat(router.prepare(List.of(building, feature("road", "road", safeRoad)))
                .normalEgress(DIAMETER, connection)).isPresent();
        for (Geometry road : List.of(safeRoad, unsafeRoad)) {
            assertThat(router.prepare(List.of(building, site, feature("road", "road", road)))
                    .normalEgress(DIAMETER, connection)).isEmpty();
        }
    }

    private CorridorTerminalRouter corridor(List<ImportedOfficialFeature> features) {
        // Локальная генерация должна построить этот прямой ввод сама; внешний fallback отсутствует.
        return new CorridorTerminalRouter(router, router.prepare(features),
                (id, target, diameter, avoidance) -> null, 0);
    }

    private List<RoutePath> retained(List<Coordinate> outward, List<ImportedOfficialFeature> features,
            boolean reverse) {
        List<Coordinate> oriented = new ArrayList<>(outward);
        if (reverse) Collections.reverse(oriented);
        RouteEdge incumbent = edge("incumbent", reverse ? "chamber" : "demand",
                reverse ? "demand" : "chamber", new RoutePath(oriented, List.of(), rules.line(oriented).getLength()));
        return CorridorRetainedTerminalApproaches.build(incumbent, demand(), outward.get(outward.size() - 1),
                0, router, router.prepare(features), List.of(), List.of());
    }

    private void assertCompletedCrossing(RoutePath path, List<ImportedOfficialFeature> features) {
        assertThat(path).isNotNull();
        assertThat(path.coordinates().get(0)).isEqualTo(connection);
        assertThat(path.coordinates().get(path.coordinates().size() - 1)).isEqualTo(port);
        assertThat(path.lengthM()).isCloseTo(21.0, offset(1e-6));
        assertThat(path.sections()).filteredOn(section -> "special".equals(section.getKind()))
                .singleElement().satisfies(section -> {
                    assertThat(section.getRestrictionId()).isEqualTo("crossing");
                    assertThat(section.getLengthM()).isEqualByComparingTo("8");
                });
        assertThat(path.sections().stream().mapToDouble(section -> section.getLengthM().doubleValue()).sum())
                .isCloseTo(path.lengthM(), offset(0.001));
        RouteEdge checked = edge("checked", "chamber", "demand", path.reversed());
        RouteNode root = new RouteNode("chamber", "existing_chamber_tie_in", coordinate(port), true, true, 2, null);
        assertThat(new OfficialRouteValidator(rules).validate(List.of(root, demand()), List.of(checked), features))
                .extracting(RouteValidationIssue::getCode).isEmpty();
        assertThat(new EngineeringRouteEvaluator().evaluate(List.of(checked)).isCompliant()).isTrue();
    }

    private RouteEdge edge(String id, String from, String to, RoutePath path) {
        return new RouteEdge(id, from, to, path.lengthM(), path.coordinates().stream()
                .map(this::coordinate).collect(Collectors.toList()), path.sections(), BigDecimal.ONE, DIAMETER);
    }

    private RouteNode demand() {
        return new RouteNode("demand", "demand_connection", coordinate(connection), false, false, 0, null);
    }

    private RouteCoordinate coordinate(Coordinate point) { return new RouteCoordinate(point.x, point.y); }

    private List<ImportedOfficialFeature> features(String type) {
        return List.of(building, feature("crossing", type, rectangle(4, -10, 6, 10)));
    }

    private Geometry roadWithParallelComponent(double clearance) {
        return factory.createMultiPolygon(new Polygon[] {
                rectangle(4, -10, 6, 10), rectangle(12, clearance, 14, clearance + 2)});
    }

    private ImportedOfficialFeature feature(String id, String type, Geometry geometry) {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", type), geometry);
    }

    private Polygon rectangle(double x1, double y1, double x2, double y2) {
        return factory.createPolygon(new Coordinate[] {c(x1, y1), c(x2, y1), c(x2, y2), c(x1, y2), c(x1, y1)});
    }

    private Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
}
