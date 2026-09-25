package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Направление ввода сохраняется при поиске и последующем упрощении наружной трассы. */
class OfficialObstacleRouterHeadingTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);


    @Test
    void destinationBehindTheExitNeedsADetourInsteadOfAnImmediateReversal() {
        for (RoutePreference preference : RoutePreference.values()) {
            Coordinate previous = point(-2, 0), start = point(0, 0), end = point(-10, 0);
            RoutePath path = router.findAfter(previous, start, end, 50, router.prepare(List.of()),
                    Set.of(), preference, List.of());
            assertThat(path).as(preference.name()).isNotNull();
            assertLegal(previous, path);
            assertThat(path.lengthM()).isGreaterThan(start.distance(end));
        }
    }

    @Test
    void theSameEndpointsWithDifferentHeadingsMustNotReuseAnIncompatiblePath() {
        OfficialRoutingEnvironment environment = router.prepare(List.of());
        Coordinate start = point(0, 0), end = point(-10, 0);
        RoutePath straight = router.findAfter(point(2, 0), start, end, 50, environment,
                Set.of(), RoutePreference.SHORTEST, List.of());
        RoutePath detour = router.findAfter(point(-2, 0), start, end, 50, environment,
                Set.of(), RoutePreference.SHORTEST, List.of());
        assertThat(straight.coordinates()).containsExactly(start, end);
        assertLegal(point(-2, 0), detour);
        assertThat(detour.lengthM()).isGreaterThan(straight.lengthM());
    }

    @Test
    void prefixAdmissionRejectsHairpinsEvenWhenTheOutsideSegmentsAreVisible() throws Exception {
        var building = new ImportedOfficialFeature("own", "oks_existing", new ObjectMapper().createObjectNode(),
                new WKTReader().read("POLYGON ((-4 -3, 2 -3, 2 3, -4 3, -4 -3))"));
        var egress = rules.normalEgress(List.of(building), 50, point(0, 0)).orElseThrow();
        Coordinate start = egress.exit();
        RoutePath outside = new RoutePath(List.of(start, point(-10, 1)), List.of(), Math.hypot(10, 1));
        assertThat(router.withCheckedTerminalPrefix(egress, outside, 50, router.prepare(List.of()))).isNull();
    }

    @Test
    void directedSearchRejectsTheCheapIllegalFirstEdgeBeforeChoosingTheDestination() {
        List<Coordinate> nodes = List.of(point(0, 0), point(-10, 0), point(0, 4), point(-10, 4));
        Object result = ReflectionTestUtils.invokeMethod(router, "shortestPath", nodes, rules.index(List.of()),
                RoutePreference.SHORTEST, nodes.get(0), nodes.get(1), point(-2, 0));
        @SuppressWarnings("unchecked")
        List<Coordinate> coordinates = (List<Coordinate>) ReflectionTestUtils.getField(result, "coordinates");
        assertThat(coordinates).containsExactlyElementsOf(List.of(nodes.get(0), nodes.get(2), nodes.get(3), nodes.get(1)));
    }

    @Test
    void regularizationCannotShortcutAcrossTheMandatoryFirstDirection() {
        List<Coordinate> coordinates = List.of(point(0, 0), point(0, 4), point(-10, 4), point(-10, 0));
        RoutePath result = router.regularizeAfter(point(-2, 0), coordinates, 50, router.prepare(List.of()), Set.of(), List.of());
        assertLegal(point(-2, 0), result);
        assertThat(result.lengthM()).isGreaterThan(10);
    }

    @Test
    void headingIsRespectedAfterUtmRoundingAndRotation() {
        for (int degrees = 0; degrees < 360; degrees += 15) {
            double angle = Math.toRadians(degrees);
            Coordinate previous = transform(-2, 0, angle), start = transform(0, 0, angle), end = transform(-10, 1, angle);
            RoutePath result = router.findAfter(previous, start, end, 50, router.prepare(List.of()),
                    Set.of(), RoutePreference.ENGINEERING, List.of());
            assertLegal(previous, result);
        }
    }

    @Test
    void degenerateHeadingIsAnInputErrorRatherThanAFreeDirection() {
        for (Coordinate previous : List.of(point(0, 0), point(0.0001, 0), point(Double.NaN, 2))) {
            assertThatThrownBy(() -> router.findAfter(previous, point(0, 0), point(10, 0), 50,
                    router.prepare(List.of()), Set.of(), RoutePreference.SHORTEST, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void headingDetourStillAvoidsRealObstacles() throws Exception {
        var obstacle = new ImportedOfficialFeature("park", "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", "park"), new WKTReader().read("POLYGON ((-8 -3, -2 -3, -2 3, -8 3, -8 -3))"));
        var environment = router.prepare(List.of(obstacle));
        Coordinate previous = point(-1, 0), start = point(0, 0), end = point(-10, 0);
        RoutePath path = router.findAfter(previous, start, end, 50, environment, Set.of(), RoutePreference.SHORTEST, List.of());
        assertLegal(previous, path);
        assertThat(router.lineAllowed(path.coordinates(), 50, environment, Set.of(), List.of())).isTrue();
        assertThat(rules.line(path.coordinates()).intersects(obstacle.getMetricGeometry())).isFalse();
    }

    @Test
    void depthRecoveryMustRespectTheSameInitialDirection() {
        Coordinate previous = point(-2, 0), start = point(0, 0), end = point(-10, 0);
        RoutePath path = router.findAfterAvoidingDepthConflicts(previous, start, end, 50,
                router.prepare(List.of()), Set.of(), Set.of(), List.of());
        assertLegal(previous, path);
    }

    @Test
    void prefixCheckRejectsForeignEndpointSetbackAndFootprintButAcceptsLegalBoundary() throws Exception {
        var own = new ImportedOfficialFeature("own", "oks_existing", new ObjectMapper().createObjectNode(),
                new WKTReader().read("POLYGON ((0 0,10 0,10 10,0 10,0 0))"));
        var foreign = new ImportedOfficialFeature("other", "oks_existing", new ObjectMapper().createObjectNode(),
                new WKTReader().read("POLYGON ((-25 10,-15 10,-15 20,-25 20,-25 10))"));
        var environment = router.prepare(List.of(own, foreign));
        var egress = environment.normalEgress(50, point(1, 5)).orElseThrow();
        RoutePath outside = router.findAfter(egress.start(), egress.exit(), point(-20, 8), 50,
                environment, Set.of(), RoutePreference.SHORTEST, List.of());
        assertThat(outside).isNull();
        RoutePath insideSetback = new RoutePath(List.of(egress.exit(), point(-20, 8)), List.of(),
                egress.exit().distance(point(-20, 8)));
        assertThat(rules.line(insideSetback.coordinates()).intersects(foreign.getMetricGeometry())).isFalse();
        assertThat(router.withCheckedTerminalPrefix(egress, insideSetback, 50, environment)).isNull();
        RoutePath boundary = router.findAfter(egress.start(), egress.exit(), point(-20, 4.8), 50,
                environment, Set.of(), RoutePreference.SHORTEST, List.of());
        assertThat(boundary).isNotNull();
        assertThat(router.withCheckedTerminalPrefix(egress, boundary, 50, environment)).isNotNull();
        RoutePath crossingForeign = new RoutePath(List.of(egress.exit(), point(-20, 15)), List.of(), 30);
        assertThat(router.withCheckedTerminalPrefix(egress, crossingForeign, 50, environment)).isNull();
    }

    @Test
    void ownTerminalLegKeepsForeignSetbackInBothPrefixAndFullValidator() throws Exception {
        var own = new ImportedOfficialFeature("own", "oks_existing", new ObjectMapper().createObjectNode(),
                new WKTReader().read("POLYGON ((0 0,10 0,10 10,0 10,0 0))"));
        var foreign = new ImportedOfficialFeature("other", "oks_existing", new ObjectMapper().createObjectNode(),
                new WKTReader().read("POLYGON ((-4 8,-2 8,-2 10,-4 10,-4 8))"));
        var ownEnvironment = router.prepare(List.of(own));
        var egress = ownEnvironment.normalEgress(50, point(1, 5)).orElseThrow();
        RoutePath outside = router.findAfter(egress.start(), egress.exit(), point(-20, 5), 50,
                ownEnvironment, Set.of(), RoutePreference.SHORTEST, List.of());
        assertThat(outside).isNotNull();
        RoutePath complete = router.withCheckedTerminalPrefix(egress, outside, 50, ownEnvironment);
        assertThat(complete).isNotNull();
        assertThat(rules.line(complete.coordinates()).intersects(foreign.getMetricGeometry())).isFalse();
        assertThat(validateReversedPrefix(complete, List.of(own))).isEmpty();
        assertThat(router.withCheckedTerminalPrefix(egress, outside, 50, router.prepare(List.of(own, foreign)))).isNull();
        assertThat(validateReversedPrefix(complete, List.of(own, foreign)))
                .extracting(RouteValidationIssue::getCode).contains("FORBIDDEN_CLEARANCE_VIOLATION");

        var boundary = new ImportedOfficialFeature("other", "oks_existing", new ObjectMapper().createObjectNode(),
                new WKTReader().read("POLYGON ((-4 10.2,-2 10.2,-2 12.2,-4 12.2,-4 10.2))"));
        assertThat(router.withCheckedTerminalPrefix(egress, outside, 50, router.prepare(List.of(own, boundary)))).isNotNull();
        assertThat(validateReversedPrefix(complete, List.of(own, boundary))).isEmpty();
    }

    @Test
    void visibilityMustRejectRoundedInvalidEdgesSoAnotherPathCanWin() throws Exception {
        var park = new ImportedOfficialFeature("park", "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", "park"), new WKTReader().read("POLYGON ((-1 -3,1 -3,1 -0.9997,-1 -0.9997,-1 -3))"));
        var environment = router.prepare(List.of(park));
        Coordinate previous = point(-12, 0.0004), start = point(-10, 0.0004), end = point(10, 0.0004);
        RoutePath path = router.findAfter(previous, start, end, 50, environment, Set.of(), RoutePreference.SHORTEST, List.of());
        assertLegal(previous, path);
        assertThat(router.lineAllowed(path.coordinates(), 50, environment, Set.of(), List.of())).isTrue();
        assertThat(path.lengthM()).isGreaterThan(20);
    }

    @Test
    void cancellationDoesNotClearTheInterruptFlag() {
        var environment = router.prepare(List.of());
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> router.findAfter(point(-2, 0), point(0, 0), point(10, 0), 50,
                    environment, Set.of(), RoutePreference.SHORTEST, List.of())).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private Coordinate transform(double x, double y, double angle) {
        return point(414000.123 + x * Math.cos(angle) - y * Math.sin(angle),
                6173000.456 + x * Math.sin(angle) + y * Math.cos(angle));
    }

    private List<RouteValidationIssue> validateReversedPrefix(RoutePath outward, List<ImportedOfficialFeature> features) {
        List<RouteCoordinate> points = outward.coordinates().stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList());
        Collections.reverse(points);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", points.get(0), true, true, 1, null),
                new RouteNode("demand", "demand_connection", points.get(points.size() - 1), false, false, 0, null));
        RouteEdge edge = new RouteEdge("input", "root", "demand", outward.lengthM(), points, List.of(), BigDecimal.ONE, 50);
        return new OfficialRouteValidator(rules).validate(nodes, List.of(edge), features);
    }

    private void assertLegal(Coordinate previous, RoutePath path) {
        assertThat(path).isNotNull();
        List<Coordinate> full = new ArrayList<>(List.of(previous));
        full.addAll(path.coordinates());
        assertThat(OfficialRouteDeflectionRules.validatePolyline("heading", full.stream()
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList())).getIssues())
                .as("full path %s", full).isEmpty();
        assertThat(rules.line(full).isSimple()).isTrue();
    }

    private static Coordinate point(double x, double y) { return new Coordinate(x, y); }
}
