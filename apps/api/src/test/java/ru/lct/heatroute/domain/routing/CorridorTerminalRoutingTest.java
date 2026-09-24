package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Синтетические нормальные вводы: проверяем фасадную геометрию независимо от выбора сеточного порта. */
class CorridorTerminalRoutingTest {
    private static final double ROUNDING_TOLERANCE_M = 0.003;
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OrthogonalCorridorNetworkBuilder builder = new OrthogonalCorridorNetworkBuilder(router, pipes);

    @Test
    void diagonalPortUsesACompleteFacadeNormalThenOneRightAngleWithoutFallback() throws Exception {
        Fixture fixture = fixture(0, 0, 0);
        AtomicInteger fallbackCalls = new AtomicInteger();
        RoutePath path = terminalPath(fixture.demand, fixture.port, fixture.features, fallbackCalls, Math.toRadians(fixture.degrees));
        assertThat(fallbackCalls).hasValue(0);
        assertNormalL(path, fixture);
    }

    @Test
    void normalLRotatesAndTranslatesWithTheBuildingAndDoesNotMutateInput() throws Exception {
        for (double angle : new double[] {13, 71, 117, 203, 289}) {
            Fixture fixture = fixture(angle, 8200.50049, -4100.25049);
            Geometry before = fixture.features.get(0).getMetricGeometry().copy();
            Coordinate demandBefore = new Coordinate(fixture.demand), portBefore = new Coordinate(fixture.port);
            AtomicInteger fallbackCalls = new AtomicInteger();
            RoutePath path = terminalPath(fixture.demand, fixture.port, fixture.features, fallbackCalls, Math.toRadians(fixture.degrees));
            assertThat(fallbackCalls).as("fallback at %s degrees", angle).hasValue(0);
            assertNormalL(path, fixture);
            assertThat(fixture.features.get(0).getMetricGeometry().equalsExact(before)).isTrue();
            assertThat(fixture.demand.equals2D(demandBefore)).isTrue();
            assertThat(fixture.port.equals2D(portBefore)).isTrue();
        }
    }

    @Test
    void straightNormalRetainsTheWholeMandatoryPrefixAndConsistentSections() throws Exception {
        Fixture fixture = fixture(0, 0, 0);
        Coordinate port = new Coordinate(-30, 10);
        AtomicInteger fallbackCalls = new AtomicInteger();
        RoutePath path = terminalPath(fixture.demand, port, fixture.features, fallbackCalls);
        assertThat(fallbackCalls).hasValue(0);
        assertThat(path).isNotNull();
        assertThat(path.coordinates().get(0).distance(fixture.demand)).isLessThan(ROUNDING_TOLERANCE_M);
        assertThat(path.coordinates()).anyMatch(p -> p.distance(new Coordinate(-0.25, 10)) < ROUNDING_TOLERANCE_M);
        assertThat(path.coordinates().get(path.coordinates().size() - 1).distance(port)).isLessThan(ROUNDING_TOLERANCE_M);
        assertPathArithmetic(path);
        assertSafeOwnPrefix(path, fixture.features.get(0).getMetricGeometry());
        RouteEdge edge = edgeFromTerminalPath(path);
        assertThat(new OfficialRouteValidator(rules).validate(nodesFor(path), List.of(edge), fixture.features)).isEmpty();
        assertThat(new EngineeringRouteEvaluator().evaluate(List.of(edge)).bendCount()).isZero();
    }

    @Test
    void forbiddenObstacleCannotBeExemptedByMatchingTheTerminalOrOwnBuildingId() throws Exception {
        Fixture fixture = fixture(0, 0, 0);
        for (String id : List.of("terminal", "home")) {
            List<ImportedOfficialFeature> features = new ArrayList<>(fixture.features);
            features.add(restriction(id, "park", "POLYGON ((-24 -16,-10 -16,-10 14,-24 14,-24 -16))"));
            AtomicInteger fallbackCalls = new AtomicInteger();
            RoutePath path = terminalPath(fixture.demand, fixture.port, features, fallbackCalls);
            assertThat(path).as("forbidden obstacle ID=%s", id).isNull();
            assertThat(fallbackCalls).hasValue(1);
        }
    }

    @Test
    void aMandatoryPrefixMustNotCrossAnotherForbiddenFootprint() throws Exception {
        Fixture fixture = fixture(0, 0, 0);
        List<ImportedOfficialFeature> features = new ArrayList<>(fixture.features);
        features.add(restriction("prefix-obstacle", "prohibited_site",
                "POLYGON ((1.5 9.8,2.5 9.8,2.5 10.2,1.5 10.2,1.5 9.8))"));
        AtomicInteger fallbackCalls = new AtomicInteger();
        // Точка спроса и exit вне метрового буфера чужого запрета; пересекает его только префикс.
        Coordinate demand = new Coordinate(4, 10), exit = new Coordinate(-0.25, 10);
        Geometry forbidden = features.get(1).getMetricGeometry().buffer(1.0);
        assertThat(forbidden.covers(forbidden.getFactory().createPoint(demand))).isFalse();
        assertThat(forbidden.covers(forbidden.getFactory().createPoint(exit))).isFalse();
        assertThat(rules.line(List.of(demand, exit)).intersects(forbidden)).isTrue();
        RoutePath path = terminalPath(demand, new Coordinate(-30, 10), features, fallbackCalls);
        assertThat(path).as("the prefix must be checked against unrelated forbidden features; path=%s, official issues=%s",
                path == null ? null : path.coordinates(),
                path == null ? List.of() : new OfficialRouteValidator(rules)
                        .validate(nodesFor(path), List.of(edgeFromTerminalPath(path)), features).stream()
                        .map(RouteValidationIssue::getCode).collect(Collectors.toList())).isNull();
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    void leavingOwnBuildingDoesNotAllowTransitThroughAnotherPartOfItsFootprint() throws Exception {
        ImportedOfficialFeature own = restriction("home", "oks", "MULTIPOLYGON ("
                + "((0 0,20 0,20 20,0 20,0 0)),((-18 5,-12 5,-12 15,-18 15,-18 5)))");
        AtomicInteger fallbackCalls = new AtomicInteger();
        RoutePath path = terminalPath(new Coordinate(1, 10), new Coordinate(-30, 10), List.of(own), fallbackCalls);
        assertThat(path).isNull();
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    void incompatiblePortAxesUseFallbackInsteadOfReturningAnObliqueConnector() throws Exception {
        Fixture fixture = fixture(0, 0, 0);
        AtomicInteger fallbackCalls = new AtomicInteger();
        RoutePath path = terminalPath(fixture.demand, fixture.port, fixture.features, fallbackCalls, Math.toRadians(30));
        assertThat(path).isNull();
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    void portAxisToleranceIsHalfADegreeAndHasQuarterTurnSymmetry() throws Exception {
        Fixture fixture = fixture(0, 0, 0);
        for (double degrees : new double[] {-90.49, -0.49, 0.49, 90.49, 180.49, 270.49}) {
            AtomicInteger fallbackCalls = new AtomicInteger();
            RoutePath path = terminalPath(fixture.demand, fixture.port, fixture.features, fallbackCalls, Math.toRadians(degrees));
            assertThat(fallbackCalls).as("inside tolerance at %s degrees", degrees).hasValue(0);
            assertNormalL(path, fixture);
        }
        for (double degrees : new double[] {-90.51, -0.51, 0.51, 90.51, 180.51, 270.51}) {
            AtomicInteger fallbackCalls = new AtomicInteger();
            RoutePath path = terminalPath(fixture.demand, fixture.port, fixture.features, fallbackCalls, Math.toRadians(degrees));
            assertThat(path).as("outside tolerance at %s degrees", degrees).isNull();
            assertThat(fallbackCalls).hasValue(1);
        }
    }

    @Test
    void twoBuildingNetworkHasAValidatedOrthogonalCandidateWithLeafDemandsAndBoundedChambers() throws Exception {
        assertNetworkFixture(0, 0, 0, false);
    }

    @Test
    void networkSafetyAndFacadeAxesSurviveRotationTranslationAndInputPermutation() throws Exception {
        for (double angle : new double[] {31, 113, 227}) {
            assertNetworkFixture(angle, 7200.25049, -3300.50049, true);
        }
    }

    @Test
    void alternativesKeepTheControlAndOfferTwoElbowsWithOppositeFinalRays() throws Exception {
        Fixture fixture = fixture(0, 0, 0);
        CorridorTerminalRouter spurs = alternativeRouter(fixture, (id, port, diameter, avoid) -> null);
        RoutePath control = spurs.route("terminal", fixture.demand, fixture.port, 50);
        List<RoutePath> paths = spurs.alternatives("terminal", fixture.demand, fixture.port, 50);
        assertThat(paths).isNotEmpty().hasSizeLessThanOrEqualTo(8);
        assertThat(paths.get(0).coordinates()).isEqualTo(control.coordinates());
        assertThat(paths.get(0).lengthM()).isEqualTo(control.lengthM());
        Set<Integer> rays = new TreeSet<>();
        for (RoutePath path : paths) {
            assertCheckedAlternative(path, fixture);
            if (new EngineeringRouteEvaluator().evaluate(List.of(edgeFromTerminalPath(path))).bendCount() == 2) {
                rays.add(rayIndex(path, 0));
                // Все двухповоротные вводы сначала выходят наружу, не разворачиваются в собственный ОКС.
                assertThat(path.coordinates().get(2).x).isLessThan(path.coordinates().get(1).x);
            }
        }
        assertThat(rays).contains(0, 2);
    }

    @Test
    void alternativeSetRotatesAndTranslatesWithTheNormalFrame() throws Exception {
        Fixture base = fixture(0, 0, 0);
        List<RoutePath> expected = alternativeRouter(base, (id, port, diameter, avoid) -> null)
                .alternatives("terminal", base.demand, base.port, 50);
        for (double degrees : new double[] {13, 71, 117, 203, 289}) {
            Fixture moved = fixture(degrees, 8200.50049, -4100.25049);
            Geometry original = moved.features.get(0).getMetricGeometry().copy();
            List<RoutePath> actual = alternativeRouter(moved, (id, port, diameter, avoid) -> null)
                    .alternatives("terminal", moved.demand, moved.port, 50);
            assertThat(actual).hasSameSizeAs(expected);
            for (RoutePath path : actual) assertCheckedAlternative(path, moved);
            for (RoutePath path : expected) {
                List<Coordinate> transformed = path.coordinates().stream().map(p -> move(p, moved.transform)).collect(Collectors.toList());
                assertThat(actual).as("same geometry set after %s-degree rotation", degrees)
                        .anyMatch(candidate -> sameWithin(candidate.coordinates(), transformed, 0.003));
            }
            assertThat(moved.features.get(0).getMetricGeometry().equalsExact(original)).isTrue();
        }
    }

    @Test
    void alternativeLimitPreservesFourFinalDirectionsAndOnlyDeduplicatesIdenticalGeometry() throws Exception {
        Fixture base = fixture(0, 0, 0);
        Fixture centered = new Fixture(0, base.transform, new Coordinate(10, 10), new Coordinate(-40, -40), base.features);
        CorridorTerminalRouter spurs = alternativeRouter(centered, (id, port, diameter, avoid) -> null);
        List<RoutePath> paths = spurs.alternatives("terminal", centered.demand, centered.port, 50);
        assertThat(paths).hasSize(8);
        assertThat(paths.stream().map(path -> rayIndex(path, 0)).collect(Collectors.toSet())).containsExactlyInAnyOrder(0, 1, 2, 3);
        Set<String> keys = paths.stream().map(path -> path.coordinates().toString()).collect(Collectors.toSet());
        assertThat(keys).hasSize(paths.size());
        for (RoutePath path : paths) assertCheckedAlternative(path, centered);
        List<RoutePath> repeated = spurs.alternatives("terminal", centered.demand, centered.port, 50);
        assertThat(repeated.stream().map(path -> path.coordinates().toString()).collect(Collectors.toList()))
                .isEqualTo(paths.stream().map(path -> path.coordinates().toString()).collect(Collectors.toList()));
        assertThat(spurs.attempts()).isEqualTo(2); // Это два свежих контрольных поиска, не replay.
        assertThatThrownBy(() -> paths.add(paths.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void twoElbowAlternativesRespectActualBendSpacingAfterRounding() throws Exception {
        Fixture base = fixture(0, 0, 0);
        for (double separation : new double[] {0.5, 1.98, 2.1}) {
            Fixture fixture = new Fixture(0, base.transform, base.demand, new Coordinate(-30, 10 + separation), base.features);
            List<RoutePath> paths = alternativeRouter(fixture, (id, port, diameter, avoid) -> null)
                    .alternatives("terminal", fixture.demand, fixture.port, 50);
            assertThat(paths).isNotEmpty();
            long twoBends = 0;
            for (RoutePath path : paths) {
                assertCheckedAlternative(path, fixture);
                EngineeringRouteEvaluator.Evaluation evaluation = new EngineeringRouteEvaluator().evaluate(List.of(edgeFromTerminalPath(path)));
                assertThat(evaluation.insufficientSpacingCount()).isZero();
                if (evaluation.bendCount() == 2) twoBends++;
            }
            if (separation < 2) assertThat(twoBends).isZero();
            else assertThat(twoBends).isPositive();
        }
    }

    @Test
    void alternativesKeepStrictOutsideClearanceAndCheckForeignObstaclesOnTheWholePrefix() throws Exception {
        Fixture base = fixture(0, 0, 0);
        for (String foreignId : List.of("foreign", "home")) {
            List<ImportedOfficialFeature> features = new ArrayList<>(base.features);
            ImportedOfficialFeature prefix = restriction(foreignId, "prohibited_site",
                    "POLYGON ((1.5 9.8,2.5 9.8,2.5 10.2,1.5 10.2,1.5 9.8))");
            features.add(prefix);
            features.add(restriction("outside-obstacle", "park", "POLYGON ((-8 -7,-4 -7,-4 -3,-8 -3,-8 -7))"));
            Fixture fixture = new Fixture(0, base.transform, new Coordinate(4, 10), new Coordinate(-30, 10), features);
            List<RoutePath> paths = alternativeRouter(fixture, (id, port, diameter, avoid) -> null)
                    .alternatives("terminal", fixture.demand, fixture.port, 50);
            assertThat(paths).isNotEmpty();
            for (RoutePath path : paths) {
                assertCheckedAlternative(path, fixture);
                for (ImportedOfficialFeature obstacle : features.subList(1, features.size())) {
                    assertThat(rules.line(path.coordinates()).intersects(obstacle.getMetricGeometry().buffer(0.999))).isFalse();
                }
            }
        }
    }

    @Test
    void alternativesIncludeSpecialSectionsOfTheMandatoryPrefix() throws Exception {
        Fixture base = fixture(0, 0, 0);
        List<ImportedOfficialFeature> features = new ArrayList<>(base.features);
        features.add(new ImportedOfficialFeature("prefix-utility", "heat_network", new ObjectMapper().createObjectNode().put("diameter", 50),
                new WKTReader().read("LINESTRING (0.5 8,0.5 12)")));
        Fixture fixture = new Fixture(0, base.transform, base.demand, base.port, features);
        List<RoutePath> paths = alternativeRouter(fixture, (id, port, diameter, avoid) -> null)
                .alternatives("terminal", fixture.demand, fixture.port, 50);
        assertThat(paths).isNotEmpty();
        for (RoutePath path : paths) {
            assertCheckedAlternative(path, fixture);
            assertThat(path.sections()).anyMatch(section -> "prefix-utility".equals(section.getRestrictionId())
                    && "heat_network".equals(section.getRestrictionType()));
        }
    }

    @Test
    void alternativesRetainCheckedFallbackOnceWithoutChangingLegacyRouteBehavior() {
        Coordinate point = new Coordinate(0, 0), port = new Coordinate(20, 10);
        RoutePath fallbackPath = new RoutePath(List.of(point, port), rules.sections(rules.line(List.of(point, port)), List.of()), point.distance(port));
        AtomicInteger calls = new AtomicInteger();
        CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, router.prepare(List.of()), (id, target, diameter, avoid) -> {
            calls.incrementAndGet();
            return fallbackPath;
        }, 0);
        List<RoutePath> paths = spurs.alternatives("terminal", point, port, 50);
        assertThat(calls).hasValue(1);
        assertThat(paths).hasSizeBetween(2, 8);
        assertThat(paths.get(0).coordinates()).isEqualTo(fallbackPath.coordinates());
        assertThat(spurs.route("terminal", point, port, 50)).isSameAs(fallbackPath);
        assertThat(calls).hasValue(2);
    }

    @Test
    void alternativesRejectSelfCrossingOrBacktrackingFallbackButDoNotChangeTheControlAPI() {
        for (List<Coordinate> points : List.of(
                List.of(new Coordinate(0, 0), new Coordinate(4, 4), new Coordinate(0, 4), new Coordinate(4, 0), new Coordinate(10, 0)),
                List.of(new Coordinate(0, 0), new Coordinate(8, 0), new Coordinate(3, 0), new Coordinate(10, 0)))) {
            RoutePath invalid = new RoutePath(points, List.of(), rules.line(points).getLength());
            CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, router.prepare(List.of()), (id, port, diameter, avoid) -> invalid, 0);
            assertThat(spurs.route("terminal", points.get(0), points.get(points.size() - 1), 50)).isSameAs(invalid);
            List<RoutePath> paths = spurs.alternatives("terminal", points.get(0), points.get(points.size() - 1), 50);
            assertThat(paths).isNotEmpty().noneMatch(path -> path.coordinates().equals(invalid.coordinates()));
            for (RoutePath path : paths) assertFreePath(path, List.of());
        }
    }

    @Test
    void alternativesDoNotHandCallerCoordinatesToFallback() {
        Coordinate point = new Coordinate(0, 0), port = new Coordinate(20, 10);
        CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, router.prepare(List.of()), (id, target, diameter, avoid) -> {
            target.x = 999;
            return null;
        }, 0);
        List<RoutePath> paths = spurs.alternatives("terminal", point, port, 50);
        assertThat(paths).isNotEmpty();
        for (RoutePath path : paths) {
            assertThat(path.coordinates().get(0).equals2D(point)).isTrue();
            assertThat(path.coordinates().get(path.coordinates().size() - 1).equals2D(port)).isTrue();
        }
        assertThat(port.equals2D(new Coordinate(20, 10))).isTrue();
        assertThat(point.equals2D(new Coordinate(0, 0))).isTrue();
    }

    @Test
    void alternativesRejectInvalidMetricInputBeforeInvokingFallback() {
        AtomicInteger calls = new AtomicInteger();
        CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, router.prepare(List.of()), (id, port, diameter, avoid) -> {
            calls.incrementAndGet(); return null;
        }, 0);
        for (Coordinate invalid : new Coordinate[] {null, new Coordinate(Double.NaN, 0),
                new Coordinate(0, Double.POSITIVE_INFINITY), new Coordinate(1e100, 0)}) {
            assertThatThrownBy(() -> spurs.alternatives("terminal", invalid, new Coordinate(20, 10), 50)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> spurs.alternatives("terminal", new Coordinate(0, 0), invalid, 50)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> spurs.alternatives("", new Coordinate(0, 0), new Coordinate(20, 10), 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spurs.alternatives("terminal", new Coordinate(0, 0), new Coordinate(0, 0), 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spurs.alternatives("terminal", new Coordinate(0, 0), new Coordinate(20, 10), 51)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void alternativesPropagateCancellationBeforeSearchAndAfterNullFallback() {
        AtomicInteger calls = new AtomicInteger();
        CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, router.prepare(List.of()), (id, port, diameter, avoid) -> {
            calls.incrementAndGet(); Thread.currentThread().interrupt(); return null;
        }, 0);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> spurs.alternatives("terminal", new Coordinate(0, 0), new Coordinate(20, 10), 50))
                    .isInstanceOf(CancellationException.class);
            assertThat(calls).hasValue(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        try {
            assertThatThrownBy(() -> spurs.alternatives("terminal", new Coordinate(0, 0), new Coordinate(20, 10), 50))
                    .isInstanceOf(CancellationException.class);
            assertThat(calls).hasValue(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void freeSpaceTerminalGetsFourApproachRaysInsteadOfOnlyAnObliqueControl() {
        Coordinate point = new Coordinate(0, 0), port = new Coordinate(100, 20);
        RoutePath control = directPath(point, port);
        CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, router.prepare(List.of()),
                (id, at, diameter, avoidance) -> control, 0);
        List<RoutePath> paths = spurs.alternatives("demand:synthetic", point, port, 50);
        assertThat(paths).hasSize(8);
        assertThat(paths.get(0).coordinates()).isEqualTo(control.coordinates());
        assertThat(paths.subList(1, paths.size()).stream().map(path -> rayIndex(path, 0)).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(paths).anyMatch(path -> new EngineeringRouteEvaluator().evaluate(List.of(edgeFromTerminalPath(path))).bendCount() == 1);
        assertThat(paths).anyMatch(path -> new EngineeringRouteEvaluator().evaluate(List.of(edgeFromTerminalPath(path))).bendCount() == 2);
        for (int i = 0; i < paths.size(); i++) {
            assertFreePath(paths.get(i), List.of());
            if (i > 0) assertAxisAligned(paths.get(i).coordinates(), 0);
        }
    }

    @Test
    void freeSpaceApproachDirectionsRotateAndTranslateWithoutAnOwnOks() {
        for (double degrees : new double[] {13, 71, 117, 203, 289}) {
            AffineTransformation transform = transform(degrees, 8200.50049, -4100.25049);
            Coordinate point = move(new Coordinate(0, 0), transform), port = move(new Coordinate(100, 20), transform);
            Coordinate pointBefore = new Coordinate(point), portBefore = new Coordinate(port);
            RoutePath control = directPath(point, port);
            CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, router.prepare(List.of()),
                    (id, at, diameter, avoidance) -> control, Math.toRadians(degrees));
            List<RoutePath> paths = spurs.alternatives("outer", point, port, 50);
            assertThat(paths).hasSize(8);
            assertThat(paths.get(0).coordinates()).isEqualTo(control.coordinates());
            assertThat(paths.subList(1, paths.size()).stream().map(path -> rayIndex(path, degrees)).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder(0, 1, 2, 3);
            for (int i = 0; i < paths.size(); i++) {
                assertFreePath(paths.get(i), List.of());
                if (i > 0) assertAxisAligned(paths.get(i).coordinates(), degrees);
            }
            assertThat(point.equals2D(pointBefore)).isTrue();
            assertThat(port.equals2D(portBefore)).isTrue();
        }
    }

    @Test
    void freeSpaceAlternativesCheckForeignObstaclesAlongTheWholePath() throws Exception {
        Coordinate point = new Coordinate(0, 0), port = new Coordinate(100, 20);
        ImportedOfficialFeature obstacle = restriction("foreign", "park", "POLYGON ((40 5,60 5,60 15,40 15,40 5))");
        RoutePath unsafeControl = directPath(point, port);
        CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, router.prepare(List.of(obstacle)),
                (id, at, diameter, avoidance) -> unsafeControl, 0);
        List<RoutePath> paths = spurs.alternatives("foreign", point, port, 50);
        assertThat(paths).isNotEmpty().hasSizeLessThanOrEqualTo(8);
        assertThat(paths).noneMatch(path -> path.coordinates().equals(unsafeControl.coordinates()));
        for (RoutePath path : paths) {
            assertFreePath(path, List.of(obstacle));
            assertAxisAligned(path.coordinates(), 0);
            assertThat(rules.line(path.coordinates()).intersects(obstacle.getMetricGeometry().buffer(0.999))).isFalse();
        }
    }

    @Test
    void freeSpaceDemandInsideForeignSetbackDoesNotGetARootOrPrefixExemption() throws Exception {
        Coordinate point = new Coordinate(0, 0), port = new Coordinate(-20, 0);
        ImportedOfficialFeature obstacle = restriction("nearby-building", "oks", "POLYGON ((1 -2,5 -2,5 2,1 2,1 -2))");
        OfficialRoutingEnvironment environment = router.prepare(List.of(obstacle));
        assertThat(environment.normalEgressCandidates(50, point, port, 60)).isEmpty();
        CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, environment,
                (id, at, diameter, avoidance) -> directPath(point, port), 0);
        assertThat(spurs.alternatives("nearby-building", point, port, 50)).isEmpty();
    }

    private RoutePath directPath(Coordinate point, Coordinate port) {
        List<Coordinate> coordinates = List.of(new RouteCoordinate(point.x, point.y).toCoordinate(),
                new RouteCoordinate(port.x, port.y).toCoordinate());
        LineString line = rules.line(coordinates);
        return new RoutePath(coordinates, rules.sections(line, List.of()), line.getLength());
    }

    private void assertFreePath(RoutePath path, List<ImportedOfficialFeature> features) {
        assertThat(rules.line(path.coordinates()).isSimple()).isTrue();
        assertThat(rules.line(path.coordinates()).isClosed()).isFalse();
        assertPathArithmetic(path);
        RouteEdge edge = edgeFromTerminalPath(path);
        assertThat(new OfficialRouteValidator(rules).validate(nodesFor(path), List.of(edge), features)).isEmpty();
        assertThat(new EngineeringRouteEvaluator().evaluate(List.of(edge)).isCompliant()).isTrue();
    }

    private CorridorTerminalRouter alternativeRouter(Fixture fixture, SharedSpineNetworkBuilder.TerminalRouter fallback) {
        return new CorridorTerminalRouter(router, router.prepare(fixture.features), fallback, Math.toRadians(fixture.degrees));
    }

    private void assertCheckedAlternative(RoutePath path, Fixture fixture) {
        assertThat(path.coordinates().get(0).distance(fixture.demand)).isLessThan(0.001);
        assertThat(path.coordinates().get(path.coordinates().size() - 1).distance(fixture.port)).isLessThan(0.001);
        LineString line = rules.line(path.coordinates());
        assertThat(line.isSimple()).isTrue();
        assertThat(line.isClosed()).isFalse();
        List<Coordinate> points = path.coordinates();
        Coordinate previous = points.get(points.size() - 2), port = points.get(points.size() - 1);
        double finalAngle = Math.atan2(port.y - previous.y, port.x - previous.x) - Math.toRadians(fixture.degrees);
        assertThat(Math.abs(Math.sin(2 * finalAngle))).as("last approach follows corridor axes")
                .isLessThanOrEqualTo(Math.sin(Math.toRadians(1)) + 1e-8);
        assertPathArithmetic(path);
        assertSafeOwnPrefix(path, fixture.features.get(0).getMetricGeometry());
        RouteEdge edge = edgeFromTerminalPath(path);
        assertThat(new OfficialRouteValidator(rules).validate(nodesFor(path), List.of(edge), fixture.features)).isEmpty();
        assertThat(new EngineeringRouteEvaluator().evaluate(List.of(edge)).isCompliant()).isTrue();
    }

    private int rayIndex(RoutePath path, double degrees) {
        List<Coordinate> points = path.coordinates();
        Coordinate before = points.get(points.size() - 2), end = points.get(points.size() - 1);
        double angle = Math.atan2(end.y - before.y, end.x - before.x) - Math.toRadians(degrees);
        return Math.floorMod((int) Math.round(angle / (Math.PI / 2)), 4);
    }

    private boolean sameWithin(List<Coordinate> first, List<Coordinate> second, double tolerance) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (first.get(i).distance(second.get(i)) > tolerance) return false;
        return true;
    }

    private void assertNetworkFixture(double degrees, double dx, double dy, boolean reverse) throws Exception {
        AffineTransformation transform = transform(degrees, dx, dy);
        ImportedOfficialFeature left = movedBuilding("left", "POLYGON ((0 0,20 0,20 20,0 20,0 0))", transform);
        ImportedOfficialFeature right = movedBuilding("right", "POLYGON ((50 0,70 0,70 20,50 20,50 0))", transform);
        List<ImportedOfficialFeature> features = new ArrayList<>(List.of(left, right));
        List<OrthogonalCorridorNetworkBuilder.Terminal> terminals = new ArrayList<>(List.of(
                terminal("left-demand", move(new Coordinate(1, 10), transform)),
                terminal("right-demand", move(new Coordinate(51, 10), transform))));
        if (reverse) { Collections.reverse(features); Collections.reverse(terminals); }
        Coordinate rootPoint = move(new Coordinate(-30, -30), transform);
        RouteNode root = new RouteNode("root", "existing_chamber_tie_in",
                new RouteCoordinate(rootPoint.x, rootPoint.y), true, true, 2, null);
        List<Geometry> footprints = features.stream().map(ImportedOfficialFeature::getMetricGeometry).collect(Collectors.toList());
        List<OrthogonalCorridorNetworkBuilder.Network> networks = builder.build(terminals, root, 2, footprints,
                router.prepare(features), (id, port, diameter, avoidance) -> null);
        assertThat(networks).as("networks at %s degrees", degrees).isNotEmpty();
        List<OrthogonalCorridorNetworkBuilder.Network> accepted = new ArrayList<>();
        for (OrthogonalCorridorNetworkBuilder.Network network : networks) {
            Map<String, Integer> degree = new HashMap<>();
            assertThat(network.connections()).hasSize(2).allMatch(c -> "connected".equals(c.getStatus()));
            assertThat(network.edges()).hasSize(network.nodes().size() - 1);
            for (RouteEdge edge : network.edges()) {
                degree.merge(edge.getUpstreamNodeId(), 1, Integer::sum);
                degree.merge(edge.getDownstreamNodeId(), 1, Integer::sum);
            }
            for (RouteNode node : network.nodes()) {
                assertThat(degree.get(node.getId()) + node.getBaseIncidentSections()).isLessThanOrEqualTo(4);
                if ("demand_connection".equals(node.getNodeType())) assertThat(degree.get(node.getId())).isEqualTo(1);
                if ("new_branch_chamber".equals(node.getNodeType())) assertThat(degree.get(node.getId())).isBetween(3, 4);
            }
            EngineeringRouteEvaluator.Evaluation evaluation = new EngineeringRouteEvaluator().evaluate(network.edges());
            List<RouteValidationIssue> issues = new OfficialRouteValidator(rules).validate(network.nodes(), network.edges(), features);
            if (issues.isEmpty() && evaluation.isCompliant() && evaluation.irregularJunctionAngleCount() == 0) {
                accepted.add(network);
            }
        }
        // Builder возвращает кандидатов, а не окончательно допущенный portfolio: наличие пригодного
        // варианта проверяем независимыми official/engineering-проверками, без требования принять все.
        assertThat(accepted).as("independently accepted candidates at %s degrees", degrees).isNotEmpty();
        for (OrthogonalCorridorNetworkBuilder.Network network : accepted) {
            for (RouteEdge edge : network.edges()) {
                List<Coordinate> points = edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
                assertAxisAligned(points, degrees);
                for (ImportedOfficialFeature building : features) {
                    Geometry footprint = building.getMetricGeometry();
                    if (footprint.covers(footprint.getFactory().createPoint(points.get(points.size() - 1)))) {
                        List<Coordinate> reversed = new ArrayList<>(points);
                        Collections.reverse(reversed);
                        RoutePath demandOutward = new RoutePath(reversed, List.of(), rules.line(reversed).getLength());
                        assertSafeOwnPrefix(demandOutward, footprint);
                    } else {
                        assertThat(rules.line(points).intersection(footprint).getLength()).isLessThan(0.001);
                    }
                }
            }
        }
    }

    private void assertNormalL(RoutePath path, Fixture fixture) {
        assertThat(path).isNotNull();
        List<Coordinate> points = path.coordinates();
        assertThat(points.get(0).distance(fixture.demand)).isLessThan(ROUNDING_TOLERANCE_M);
        assertThat(points.get(points.size() - 1).distance(fixture.port)).isLessThan(ROUNDING_TOLERANCE_M);
        Coordinate expectedExit = move(new Coordinate(-0.25, 10), fixture.transform);
        Coordinate expectedElbow = move(new Coordinate(-30, 10), fixture.transform);
        assertThat(points).as("full facade-normal prefix must be preserved").anyMatch(p -> p.distance(expectedExit) < ROUNDING_TOLERANCE_M);
        assertThat(points).as("L elbow instead of a diagonal terminal connector").anyMatch(p -> p.distance(expectedElbow) < ROUNDING_TOLERANCE_M);
        assertThat(Math.abs(path.lengthM() - 61.0)).isLessThan(ROUNDING_TOLERANCE_M * 2);
        assertAxisAligned(points, fixture.degrees);
        assertPathArithmetic(path);
        assertSafeOwnPrefix(path, fixture.features.get(0).getMetricGeometry());
        RouteEdge edge = edgeFromTerminalPath(path);
        assertThat(new OfficialRouteValidator(rules).validate(nodesFor(path), List.of(edge), fixture.features)).isEmpty();
        EngineeringRouteEvaluator.Evaluation evaluation = new EngineeringRouteEvaluator().evaluate(List.of(edge));
        assertThat(evaluation.bendCount()).isEqualTo(1);
        assertThat(evaluation.invalidAngleCount()).isZero();
        assertThat(evaluation.insufficientSpacingCount()).isZero();
        assertThat(evaluation.irregularJunctionAngleCount()).isZero();
        assertThat(evaluation.totalAngleDeviation()).isLessThan(0.02);
    }

    private void assertSafeOwnPrefix(RoutePath outward, Geometry own) {
        List<Coordinate> points = outward.coordinates();
        Geometry inside = rules.line(points).intersection(own);
        assertThat(inside.getNumGeometries()).as("one contiguous entry into own building").isEqualTo(1);
        assertThat(inside.distance(own.getFactory().createPoint(points.get(0)))).isLessThan(ROUNDING_TOLERANCE_M);
        assertThat(own.covers(own.getFactory().createPoint(points.get(1)))).isFalse();
        if (points.size() > 2) {
            LineString afterPrefix = rules.line(points.subList(1, points.size()));
            assertThat(afterPrefix.intersection(own).getLength()).isLessThan(0.001);
        }
    }

    private void assertAxisAligned(List<Coordinate> points, double degrees) {
        double angle = Math.toRadians(degrees), c = Math.cos(angle), s = Math.sin(angle);
        for (int i = 1; i < points.size(); i++) {
            double dx = points.get(i).x - points.get(i - 1).x, dy = points.get(i).y - points.get(i - 1).y;
            assertThat(Math.min(Math.abs(c * dx + s * dy), Math.abs(-s * dx + c * dy)))
                    .as("segment %s follows a facade axis at %s degrees", i, degrees).isLessThan(ROUNDING_TOLERANCE_M);
        }
    }

    private void assertPathArithmetic(RoutePath path) {
        assertThat(Math.abs(path.lengthM() - rules.line(path.coordinates()).getLength())).isLessThan(0.000001);
        double sectionLength = path.sections().stream().mapToDouble(s -> s.getLengthM().doubleValue()).sum();
        assertThat(Math.abs(sectionLength - path.lengthM())).isLessThan(ROUNDING_TOLERANCE_M);
    }

    private RoutePath terminalPath(Coordinate demand, Coordinate port, List<ImportedOfficialFeature> features,
            AtomicInteger fallbackCalls) throws Exception {
        return terminalPath(demand, port, features, fallbackCalls, 0);
    }

    private RoutePath terminalPath(Coordinate demand, Coordinate port, List<ImportedOfficialFeature> features,
            AtomicInteger fallbackCalls, double orientation) {
        SharedSpineNetworkBuilder.TerminalRouter fallback = (id, at, diameter, avoidance) -> {
            fallbackCalls.incrementAndGet();
            return null;
        };
        return new CorridorTerminalRouter(router, router.prepare(features), fallback, orientation)
                .route("terminal", demand, port, 50);
    }

    private RouteEdge edgeFromTerminalPath(RoutePath path) {
        RoutePath reversed = path.reversed();
        return new RouteEdge("edge", "root", "demand", reversed.lengthM(), reversed.coordinates().stream()
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()), reversed.sections(), BigDecimal.ONE, 50);
    }

    private List<RouteNode> nodesFor(RoutePath path) {
        Coordinate demand = path.coordinates().get(0), root = path.coordinates().get(path.coordinates().size() - 1);
        return List.of(new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(root.x, root.y), true, true, 2, null),
                new RouteNode("demand", "demand_connection", new RouteCoordinate(demand.x, demand.y), false, false, 0, null));
    }

    private Fixture fixture(double degrees, double dx, double dy) throws Exception {
        AffineTransformation transform = transform(degrees, dx, dy);
        return new Fixture(degrees, transform, move(new Coordinate(1, 10), transform), move(new Coordinate(-30, -20), transform),
                List.of(movedBuilding("home", "POLYGON ((0 0,20 0,20 20,0 20,0 0))", transform)));
    }

    private ImportedOfficialFeature movedBuilding(String id, String wkt, AffineTransformation transform) throws Exception {
        return new ImportedOfficialFeature(id, "oks_existing", new ObjectMapper().createObjectNode(),
                transform.transform(new WKTReader().read(wkt)));
    }

    private ImportedOfficialFeature restriction(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode().put("restriction_type", type),
                new WKTReader().read(wkt));
    }

    private OrthogonalCorridorNetworkBuilder.Terminal terminal(String id, Coordinate point) {
        return new OrthogonalCorridorNetworkBuilder.Terminal(id, id, point, BigDecimal.ONE);
    }

    private AffineTransformation transform(double degrees, double dx, double dy) {
        double radians = Math.toRadians(degrees), c = Math.cos(radians), s = Math.sin(radians);
        return new AffineTransformation(c, -s, dx, s, c, dy);
    }

    private Coordinate move(Coordinate point, AffineTransformation transform) {
        return transform.transform(point, new Coordinate());
    }

    private static final class Fixture {
        final double degrees;
        final AffineTransformation transform;
        final Coordinate demand, port;
        final List<ImportedOfficialFeature> features;
        Fixture(double degrees, AffineTransformation transform, Coordinate demand, Coordinate port,
                List<ImportedOfficialFeature> features) {
            this.degrees = degrees; this.transform = transform; this.demand = demand; this.port = port; this.features = features;
        }
    }
}
