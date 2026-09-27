package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Направление проходит через поиск, восстановление и сборку; готовое ребро проверяется независимо. */
class OfficialObstacleRouterDirectionTest {
    private static final int DIAMETER = 100;
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);
    private final GeometryFactory factory = new GeometryFactory();

    @Test
    void noEgressFindKeepsDirectionsSeparateWhenAsGivenRunsFirst() {
        assertFindOrder(false, false);
    }

    @Test
    void noEgressFindKeepsDirectionsSeparateWhenReversedRunsFirst() {
        assertFindOrder(true, false);
    }

    @Test
    void cachedNullAsGivenCannotHideALegalReversedRoute() {
        assertFindOrder(false, true);
    }

    @Test
    void reversedResultCannotPopulateTheAsGivenNullCache() {
        assertFindOrder(true, true);
    }

    @Test
    void explicitLineChecksUsePhysicalDirectionWithoutChangingDefault() {
        Fixture fixture = new Fixture(false, 90, 40);
        OfficialRoutingEnvironment environment = router.prepare(List.of(fixture.road));
        List<Coordinate> outward = List.of(fixture.demand(), fixture.root());
        List<Coordinate> incoming = List.of(fixture.root(), fixture.demand());
        assertThat(router.lineAllowed(outward, DIAMETER, environment, Set.of(), List.of())).isFalse();
        assertThat(router.lineAllowed(outward, DIAMETER, environment, Set.of(), List.of(), RouteTraversal.REVERSED))
                .isTrue();
        assertThat(router.lineAllowed(outward, DIAMETER, environment, Set.of(), List.of(), RouteTraversal.AS_GIVEN))
                .isFalse();
        assertThat(router.lineAllowed(incoming, DIAMETER, environment, Set.of(), List.of())).isTrue();
        assertThat(router.lineAllowed(incoming, DIAMETER, environment, Set.of(), List.of(), RouteTraversal.REVERSED))
                .isFalse();
    }

    @Test
    void regularizeAfterCarriesIncomingDirectionAcrossTheRealPrefix() {
        Fixture fixture = new Fixture(true, 90, 40);
        var normal = fixture.incomingNormal();
        var environment = router.prepare(fixture.features);
        List<Coordinate> outside = List.of(normal.exit(), c(20, 0), fixture.root());
        assertThat(router.regularizeAfter(normal.start(), outside, DIAMETER, environment,
                Set.of(), List.of(), RouteTraversal.AS_GIVEN)).isNull();
        RoutePath repaired = router.regularizeAfter(normal.start(), outside, DIAMETER, environment,
                Set.of(), List.of(), RouteTraversal.REVERSED);

        assertIncomingComplete(fixture, normal, repaired, environment);
    }

    @Test
    void depthRecoveryCarriesDirectionAndStillAppliesFailedObstacleIds() {
        Fixture fixture = new Fixture(true, 90, 40);
        var normal = fixture.incomingNormal();
        var environment = router.prepare(fixture.features);
        RoutePath recovered = router.findAfterAvoidingDepthConflicts(normal.start(), normal.exit(), fixture.root(),
                DIAMETER, environment, Set.of(), Set.of(), List.of(), RouteTraversal.REVERSED);
        assertIncomingComplete(fixture, normal, recovered, environment);
        assertThat(router.findAfterAvoidingDepthConflicts(normal.start(), normal.exit(), fixture.root(), DIAMETER,
                environment, Set.of(), Set.of("road"), List.of(), RouteTraversal.REVERSED)).isNull();
        assertThat(router.findAfterAvoidingDepthConflicts(normal.start(), normal.exit(), fixture.root(), DIAMETER,
                environment, Set.of(), Set.of(), List.of(), RouteTraversal.AS_GIVEN)).isNull();
    }

    @Test
    void preparedTerminalCorridorCarriesDirectionThroughBothIndicesAndFullPrefix() {
        Fixture fixture = new Fixture(true, 90, 40);
        var normal = fixture.incomingNormal();
        var environment = router.prepare(fixture.features);
        for (Coordinate corridorRoot : List.of(fixture.root(), c(200, 20))) {
            // Exercise rootIndex and strictIndex, not undirected shared-corridor trunk planning.
            PreparedCorridor incoming = corridor(fixture, environment, corridorRoot, RouteTraversal.REVERSED);
            PreparedCorridor outgoing = corridor(fixture, environment, corridorRoot, RouteTraversal.AS_GIVEN);
            List<Coordinate> points = List.of(normal.exit(), fixture.root());
            assertThat(outgoing.pathAfter(normal.start(), points)).isNull();
            RoutePath outside = incoming.pathAfter(normal.start(), points);
            assertIncomingComplete(fixture, normal, outside, environment);
            RoutePath complete = router.withCheckedCorridorTerminalPrefix(
                    normal, outside, DIAMETER, environment, RouteTraversal.REVERSED);
            assertComplete(complete, RouteTraversal.REVERSED, fixture.features);
            assertThat(router.withCheckedCorridorTerminalPrefix(
                    normal, outside, DIAMETER, environment, RouteTraversal.AS_GIVEN)).isNull();
        }
    }

    @Test
    void storedRetentionStaysAsGivenEvenWithAnIncomingSelectedNormal() {
        Fixture fixture = new Fixture(true, 90, 40);
        var normal = fixture.incomingNormal();
        var environment = router.prepare(fixture.features);
        List<Coordinate> stored = List.of(fixture.root(), normal.exit(), normal.start());
        assertThat(router.terminalRouteAllowed(stored, DIAMETER, environment, Set.of(), List.of(), normal)).isTrue();
        assertComplete(path(stored, fixture.features), RouteTraversal.AS_GIVEN, fixture.features);
        List<Coordinate> outward = List.of(normal.start(), normal.exit(), fixture.root());
        assertThat(router.terminalRouteAllowed(outward, DIAMETER, environment, Set.of(), List.of(), normal)).isFalse();
    }

    @Test
    void checkedTerminalSuffixCarriesDirectionAndRebuildsSections() {
        Fixture fixture = new Fixture(false, 90, 40);
        List<ImportedOfficialFeature> features = List.of(fixture.road);
        var environment = router.prepare(features);
        RoutePath outside = path(List.of(fixture.demand(), c(100, 0)), features);
        assertThat(router.withCheckedTerminalSuffix(outside, fixture.root(), DIAMETER, environment,
                Set.of(), List.of(), RouteTraversal.AS_GIVEN)).isNull();
        RoutePath complete = router.withCheckedTerminalSuffix(outside, fixture.root(), DIAMETER, environment,
                Set.of(), List.of(), RouteTraversal.REVERSED);
        assertComplete(complete, RouteTraversal.REVERSED, features);
        assertThat(complete.lengthM()).isCloseTo(151, within(1e-6));
    }

    @Test
    void demandSuffixRetainsActualOwnNormalAndRebuildsIncomingSections() {
        for (boolean terminalRoad : new boolean[] {false, true}) {
            Fixture fixture = new Fixture(terminalRoad, 90, 40);
            var environment = router.prepare(fixture.features);
            RoutePath approach = demandApproach(fixture, c(100, 0));

            RoutePath extended = router.withCheckedDemandSuffix(approach, fixture.root(), DIAMETER,
                    environment, Set.of(), List.of());

            assertComplete(extended, RouteTraversal.REVERSED, fixture.features);
            assertThat(extended.coordinates()).containsExactly(fixture.demand(), fixture.incomingNormal().exit(),
                    c(100, 0), fixture.root());
            assertThat(extended.lengthM()).isCloseTo(151, within(1e-6));
            assertThat(extended.sections()).filteredOn(section -> "special".equals(section.getKind())).hasSize(1);
        }
    }

    @Test
    void demandSuffixDoesNotExemptForeignObstaclesOnThePrefixOrExtension() {
        Fixture fixture = new Fixture(false, 90, 40);
        RoutePath approach = demandApproach(fixture, c(100, 0));
        for (ImportedOfficialFeature foreign : List.of(
                rectangle("foreign", "park", 0.5, -0.5, 1.5, 0.5),
                rectangle("foreign", "park", 120, -1, 125, 1))) {
            var environment = router.prepare(List.of(fixture.own, fixture.road, foreign));

            assertThat(router.withCheckedDemandSuffix(approach, fixture.root(), DIAMETER,
                    environment, Set.of(), List.of())).isNull();
        }
    }

    @Test
    void demandSuffixRejectsAChangedMandatoryNormalEvenWithLegalBendAngles() {
        Fixture fixture = new Fixture(false, 90, 40);
        var normal = fixture.incomingNormal();
        double offset = normal.start().distance(normal.exit());
        Coordinate shiftedExit = new Coordinate(normal.start().x, normal.start().y + offset);
        Coordinate end = c(150, offset);
        RoutePath changed = path(List.of(normal.start(), shiftedExit, c(100, offset)), fixture.features);
        List<Coordinate> extended = TerminalSuffixGeometry.append(changed.coordinates(), end);
        assertThat(extended).as("The changed normal uses only legal right-angle bends").isNotEmpty();
        RoutePath stored = path(extended, fixture.features).reversed();
        RouteEdge edge = edge(stored, "edge", "root", "demand");
        assertThat(validator.validate(List.of(node("root", end, true), node("demand", normal.start(), false)),
                List.of(edge), fixture.features)).extracting(RouteValidationIssue::getCode)
                .contains("OKS_NORMAL_EGRESS_VIOLATION");

        assertThat(router.withCheckedDemandSuffix(changed, end, DIAMETER,
                router.prepare(fixture.features), Set.of(), List.of())).isNull();
    }

    @Test
    void demandSuffixWithoutOwnOksStillUsesIncomingDirection() {
        Fixture fixture = new Fixture(false, 90, 40);
        List<ImportedOfficialFeature> features = List.of(fixture.road);
        var environment = router.prepare(features);
        RoutePath approach = router.find(fixture.demand(), c(100, 0), DIAMETER, environment,
                Set.of(), RoutePreference.SHORTEST, List.of(), RouteTraversal.REVERSED);
        assertComplete(approach, RouteTraversal.REVERSED, features);

        RoutePath extended = router.withCheckedDemandSuffix(approach, fixture.root(), DIAMETER,
                environment, Set.of(), List.of());

        assertComplete(extended, RouteTraversal.REVERSED, features);
        assertThat(extended.lengthM()).isCloseTo(151, within(1e-6));
    }

    @Test
    void neitherTraversalInventsMissingProtectionBeyondTheFixedDemand() {
        Fixture fixture = new Fixture(true, 90, 90);
        var normal = fixture.ownOnlyNormal();
        ImportedOfficialFeature closeRoad = rectangle("road", "road", 1, -1, 10, 1);
        List<ImportedOfficialFeature> features = List.of(fixture.own, closeRoad);
        var environment = router.prepare(features);
        List<Coordinate> points = List.of(normal.exit(), fixture.root());
        RoutePath outside = path(points, features);
        for (RouteTraversal traversal : RouteTraversal.values()) {
            assertThat(router.regularizeAfter(normal.start(), points, DIAMETER, environment,
                    Set.of(), List.of(), traversal)).isNull();
            assertThat(router.findAfterAvoidingDepthConflicts(normal.start(), normal.exit(), fixture.root(),
                    DIAMETER, environment, Set.of(), Set.of(), List.of(), traversal)).isNull();
            assertThat(router.withCheckedTerminalPrefix(normal, outside, DIAMETER, environment, traversal)).isNull();
            assertThat(corridor(fixture, environment, fixture.root(), traversal)
                    .pathAfter(normal.start(), points)).isNull();
        }
    }

    @Test
    void reversedRecoveryStillQueriesObstaclesOnTheRealPreviousLeg() {
        ImportedOfficialFeature distant = rectangle("distant", "road", -999, -10, -998, 10);
        var environment = router.prepare(List.of(), new InMemoryRoutingFeatureSource(List.of(distant)));
        for (RouteTraversal traversal : RouteTraversal.values()) {
            assertThat(router.regularizeAfter(c(-1000, 0), List.of(c(0, 0), c(20, 0)), DIAMETER,
                    environment, Set.of(), List.of(), traversal)).isNull();
        }
    }

    @Test
    void explicitIncompleteNormalCannotBypassTheWholeRouteEntryCheck() {
        Fixture fixture = new Fixture(true, 40, 90);
        var normal = fixture.ownOnlyNormal();
        var environment = router.prepare(fixture.features);
        // Even an explicitly supplied old normal cannot authorize its 40-degree incoming boundary.
        RoutePath outside = path(List.of(normal.exit(), fixture.root()), fixture.features);
        assertThat(router.withCheckedTerminalPrefix(normal, outside, DIAMETER, environment,
                Set.of(), List.of(), RouteTraversal.REVERSED)).isNull();
        assertThat(router.findAfter(normal.start(), normal.exit(), fixture.root(), DIAMETER, environment,
                Set.of(), RoutePreference.SHORTEST, List.of(), RouteTraversal.REVERSED)).isNull();
    }

    @Test
    void reversedHeadingAndPrefixPreserveTheKnownSharedJunctionException() {
        assertJoinedHeading(false);
    }

    @Test
    void reversedHeadingCannotTreatACoincidentForeignNodeAsShared() {
        assertJoinedHeading(true);
    }

    private void assertFindOrder(boolean reversedFirst, boolean enclosed) {
        Fixture fixture = new Fixture(false, 90, 40);
        List<ImportedOfficialFeature> features = new ArrayList<>(List.of(fixture.road));
        if (enclosed) {
            // A narrow closed room admits the horizontal crossing but no 45-degree detour.
            features.add(rectangle("top", "park", -10, 1.5, 170, 20));
            features.add(rectangle("bottom", "park", -10, -20, 170, -1.5));
            features.add(rectangle("left", "park", -10, -20, -8, 20));
            features.add(rectangle("right", "park", 168, -20, 170, 20));
        }
        var environment = router.prepare(features);
        RoutePath reverse = reversedFirst ? find(fixture, environment, RouteTraversal.REVERSED) : null;
        RoutePath given = find(fixture, environment, RouteTraversal.AS_GIVEN);
        if (!reversedFirst) reverse = find(fixture, environment, RouteTraversal.REVERSED);
        assertComplete(reverse, RouteTraversal.REVERSED, features);
        assertThat(reverse.coordinates()).containsExactly(fixture.demand(), fixture.root());
        assertThat(reverse.lengthM()).isCloseTo(151, within(1e-6));
        if (enclosed) assertThat(given).isNull();
        else {
            assertComplete(given, RouteTraversal.AS_GIVEN, features);
            assertThat(given.lengthM()).isGreaterThan(151);
        }
        assertThat(find(fixture, environment, RouteTraversal.AS_GIVEN)).isSameAs(given);
        assertThat(router.find(fixture.demand(), fixture.root(), DIAMETER, environment,
                Set.of(), RoutePreference.SHORTEST, List.of())).isSameAs(given);
        assertComplete(find(fixture, environment, RouteTraversal.REVERSED), RouteTraversal.REVERSED, features);
    }

    private RoutePath find(Fixture fixture, OfficialRoutingEnvironment environment, RouteTraversal traversal) {
        return router.find(fixture.demand(), fixture.root(), DIAMETER, environment,
                Set.of(), RoutePreference.SHORTEST, List.of(), traversal);
    }

    private RoutePath demandApproach(Fixture fixture, Coordinate waypoint) {
        var normal = fixture.incomingNormal();
        var environment = router.prepare(fixture.features);
        RoutePath outside = router.findAfter(normal.start(), normal.exit(), waypoint, DIAMETER,
                environment, Set.of(), RoutePreference.SHORTEST, List.of(), RouteTraversal.REVERSED);
        assertThat(outside).isNotNull();
        RoutePath approach = router.withCheckedTerminalPrefix(normal, outside, DIAMETER,
                environment, Set.of(), List.of(), RouteTraversal.REVERSED);
        assertComplete(approach, RouteTraversal.REVERSED, fixture.features);
        assertThat(approach.coordinates()).containsExactly(normal.start(), normal.exit(), waypoint);
        return approach;
    }

    private void assertIncomingComplete(Fixture fixture, OfficialRouteGeometryRules.NormalEgress normal,
            RoutePath outside, OfficialRoutingEnvironment environment) {
        assertThat(outside).isNotNull();
        for (RoutePath complete : new RoutePath[] {
                router.withCheckedTerminalPrefix(normal, outside, DIAMETER, environment, RouteTraversal.REVERSED),
                router.withCheckedTerminalPrefix(normal, outside, DIAMETER, environment,
                        Set.of(), List.of(), RouteTraversal.REVERSED)}) {
            assertComplete(complete, RouteTraversal.REVERSED, fixture.features);
            assertThat(complete.lengthM()).isCloseTo(151, within(1e-6));
        }
    }

    private PreparedCorridor corridor(Fixture fixture, OfficialRoutingEnvironment environment,
            Coordinate root, RouteTraversal traversal) {
        Envelope bounds = new Envelope(fixture.demand(), fixture.root());
        bounds.expandBy(30);
        return router.prepareCorridor(DIAMETER, environment, bounds, root, null, traversal);
    }

    private void assertJoinedHeading(boolean foreign) {
        Fixture fixture = new Fixture(false, 90, 40);
        var normal = fixture.incomingNormal();
        var environment = router.prepare(fixture.features);
        RouteEdge candidate = edge(path(List.of(fixture.root(), normal.exit(), fixture.demand()), fixture.features),
                "candidate", "root", "demand");
        RouteEdge accepted = edge(path(List.of(fixture.root(), c(150, 20)), fixture.features),
                "accepted", foreign ? "foreign" : "root", "other");
        Map<String, RouteNode> nodes = Map.of("root", node("root", fixture.root(), true),
                "demand", node("demand", fixture.demand(), false), "other", node("other", c(150, 20), false),
                "foreign", node("foreign", fixture.root(), true));
        RouteAvoidance avoidance = rules.routeAvoidance(candidate, List.of(accepted), nodes);
        RoutePath outside = router.findAfter(normal.start(), normal.exit(), fixture.root(), DIAMETER,
                environment, Set.of(), RoutePreference.SHORTEST, avoidance, RouteTraversal.REVERSED);
        if (foreign) {
            assertThat(outside).isNull();
            return;
        }
        assertThat(outside).isNotNull();
        RoutePath complete = router.withCheckedTerminalPrefix(normal, outside, DIAMETER, environment,
                Set.of(), avoidance, RouteTraversal.REVERSED);
        assertComplete(complete, RouteTraversal.REVERSED, fixture.features);
        assertThat(validator.validate(List.of(nodes.get("root"), nodes.get("demand"), nodes.get("other")),
                List.of(accepted, edge(complete.reversed(), "candidate", "root", "demand")), fixture.features))
                .isEmpty();
    }

    private void assertComplete(RoutePath constructed, RouteTraversal traversal,
            List<ImportedOfficialFeature> features) {
        assertThat(constructed).isNotNull();
        RoutePath physical = traversal == RouteTraversal.AS_GIVEN ? constructed : constructed.reversed();
        RouteEdge edge = edge(physical, "edge", "root", "demand");
        List<Coordinate> points = edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                .collect(Collectors.toList());
        assertThat(validator.validate(List.of(node("root", points.get(0), true),
                node("demand", points.get(points.size() - 1), false)), List.of(edge), features)).isEmpty();
        assertThat(physical.sections()).usingRecursiveComparison()
                .isEqualTo(rules.sections(rules.line(points), rules.baseConstraints(features, DIAMETER)));
    }

    private RoutePath path(List<Coordinate> points, List<ImportedOfficialFeature> features) {
        LineString line = rules.line(points);
        return new RoutePath(points, rules.sections(line, rules.baseConstraints(features, DIAMETER)), line.getLength());
    }

    private RouteEdge edge(RoutePath path, String id, String from, String to) {
        return new RouteEdge(id, from, to, path.lengthM(), path.coordinates().stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()),
                path.sections(), BigDecimal.ONE, DIAMETER);
    }

    private RouteNode node(String id, Coordinate point, boolean root) {
        return new RouteNode(id, root ? "existing_chamber_tie_in" : "demand_connection",
                new RouteCoordinate(point.x, point.y), root, root, root ? 2 : 0, null);
    }

    private ImportedOfficialFeature rectangle(String id, String type, double left, double bottom,
            double right, double top) {
        return polygon(id, type, c(left, bottom), c(right, bottom), c(right, top), c(left, top), c(left, bottom));
    }

    private ImportedOfficialFeature polygon(String id, String type, Coordinate... points) {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", type), factory.createPolygon(points));
    }

    private Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }

    private final class Fixture {
        private final ImportedOfficialFeature own = rectangle("own", "oks", -2, -10, 0, 10);
        private final ImportedOfficialFeature road;
        private final List<ImportedOfficialFeature> features;

        private Fixture(boolean terminal, double entryAngle, double exitAngle) {
            double left = terminal ? 4 : 30, right = terminal ? 10 : 70, half = terminal ? 1 : 3;
            road = polygon("road", "road", c(left, -half), c(right, -half),
                    c(right + 2 * half / Math.tan(Math.toRadians(entryAngle)), half),
                    c(left + 2 * half / Math.tan(Math.toRadians(exitAngle)), half), c(left, -half));
            features = List.of(own, road);
        }

        private Coordinate demand() { return c(-1, 0); }
        private Coordinate root() { return c(150, 0); }
        private OfficialRouteGeometryRules.NormalEgress incomingNormal() {
            return router.prepare(features).normalEgressTowards(DIAMETER, demand(), root(), RouteTraversal.REVERSED)
                    .orElseThrow();
        }
        private OfficialRouteGeometryRules.NormalEgress ownOnlyNormal() {
            return router.prepare(List.of(own)).normalEgressTowards(DIAMETER, demand(), root()).orElseThrow();
        }
    }
}
