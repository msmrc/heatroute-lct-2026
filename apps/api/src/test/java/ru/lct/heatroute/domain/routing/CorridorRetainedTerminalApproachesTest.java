package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Регрессия потери нормального ввода, когда сохранённый первый сегмент уже содержит выход из ОКС. */
class CorridorRetainedTerminalApproachesTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void retainsRevalidatedTwoBendIncumbentEvenWhenFreshAlternativesLoseIt() throws Exception {
        Fixture f = fixture(0, false);
        OfficialRoutingEnvironment environment = router.prepare(f.features);
        RoutePath old = new RoutePath(f.points, List.of(), rules.line(f.points).getLength());
        List<RoutePath> fresh = new CorridorTerminalRouter(router, environment,
                (id, target, diameter, avoid) -> old, 0)
                .alternatives("demand", f.points.get(0), f.end(), 50);
        assertThat(fresh).noneMatch(path -> preservesElbows(path, f));
        List<RoutePath> paths = build(f, f.end(), fresh, List.of());
        assertThat(paths).isNotEmpty().hasSizeLessThanOrEqualTo(8);
        assertThat(preservesElbows(paths.get(0), f)).isTrue();
        assertThat(paths).anyMatch(path -> path.coordinates().equals(fresh.get(0).coordinates()));
        assertThat(Math.abs(paths.get(0).lengthM() - old.lengthM())).isLessThan(0.003);
        assertSafe(paths, f, f.end());
    }

    @Test
    void reversedEdgeHasTheSameDemandToChamberGeometry() throws Exception {
        Fixture forward = fixture(0, false), reverse = fixture(0, true);
        assertThat(keys(build(reverse, reverse.end(), List.of(), List.of())))
                .isEqualTo(keys(build(forward, forward.end(), List.of(), List.of())));
    }

    @Test
    void incumbentSurvivesRotationTranslationAndDoesNotMutateInputs() throws Exception {
        for (double angle : new double[] {13, 71, 117, 203, 289}) {
            Fixture f = fixture(angle, false);
            Geometry before = f.features.get(0).getMetricGeometry().copy();
            List<RouteCoordinate> coordinates = new ArrayList<>(f.edge.getCoordinates());
            List<RoutePath> paths = build(f, f.end(), List.of(), List.of());
            assertThat(paths).as("angle %s", angle).isNotEmpty();
            assertThat(preservesElbows(paths.get(0), f)).isTrue();
            assertSafe(paths, f, f.end());
            assertThat(f.edge.getCoordinates()).isEqualTo(coordinates);
            assertThat(f.features.get(0).getMetricGeometry().equalsExact(before)).isTrue();
        }
    }

    @Test
    void relocatedChamberKeepsTheDemandSidePrefix() throws Exception {
        Fixture f = fixture(0, false);
        Coordinate target = new Coordinate(20, -50);
        List<RoutePath> paths = build(f, target, List.of(), List.of());
        assertThat(paths).isNotEmpty().hasSizeLessThanOrEqualTo(8);
        for (RoutePath path : paths) {
            assertThat(path.coordinates()).anyMatch(p -> p.distance(f.points.get(1)) < 0.002);
        }
        assertSafe(paths, f, target);
    }

    @Test
    void blockedNewEndpointHasNoRetainedTail() throws Exception {
        Fixture f = fixture(0, false);
        f.features.add(feature("blocked", "park", "POLYGON ((15 -55,25 -55,25 -45,15 -45,15 -55))"));
        assertThat(build(f, new Coordinate(20, -50), List.of(), List.of())).isEmpty();
    }

    @Test
    void anObstacleBetweenFreeEndpointsStillBlocksEveryBoundedTail() throws Exception {
        Fixture f = fixture(0, false);
        f.features.add(feature("tail-barrier", "park", "POLYGON ((-100 -46,100 -46,100 -44,-100 -44,-100 -46))"));
        assertThat(build(f, new Coordinate(20, -50), List.of(), List.of())).isEmpty();
    }

    @Test
    void preservedPathStillNeedsACompatibleFourRayAssignment() throws Exception {
        Fixture f = fixture(0, false);
        List<RoutePath> choices = build(f, f.end(), List.of(), List.of());
        RoutePath east = straight(new Coordinate(80, -40), f.end());
        RoutePath north = straight(new Coordinate(20, -10), f.end());
        RoutePath south = straight(new Coordinate(20, -100), f.end());
        assertThat(CorridorJunctionAssignment.choose(f.end(), List.of(choices, List.of(east), List.of(north), List.of(south)))).isNotNull();
        RoutePath conflicting = straight(new Coordinate(-20, -40), f.end());
        assertThat(CorridorJunctionAssignment.choose(f.end(), List.of(choices, List.of(conflicting), List.of(north), List.of(south)))).isNull();
    }

    @Test
    void incumbentIsRecheckedAgainstForeignObstaclesOnThePrefix() throws Exception {
        Fixture f = fixture(0, false);
        f.features.add(feature("home", "prohibited_site", "POLYGON ((0.3 9.8,0.7 9.8,0.7 10.2,0.3 10.2,0.3 9.8))"));
        assertThat(build(f, f.end(), List.of(), List.of())).isEmpty();
    }

    @Test
    void blockedRetainedEdgesAreFilteredBeforeTheyOccupyTheEightSlots() throws Exception {
        Fixture f = fixture(0, false);
        RouteEdge barrier = edge("barrier", "a", "b", List.of(new Coordinate(-50, 0), new Coordinate(-30, 0)));
        assertThat(build(f, f.end(), List.of(), List.of(barrier))).isEmpty();
    }

    @Test
    void interruptedInvocationPreservesInterruptAndDoesNoWork() throws Exception {
        Fixture f = fixture(0, false);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> build(f, f.end(), List.of(), List.of())).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void coordinateOnlyReversalViolatesDeclaredIncidenceAndIsNotSilentlyRepaired() throws Exception {
        Fixture f = fixture(0, false);
        List<Coordinate> backwards = new ArrayList<>(f.points);
        Collections.reverse(backwards);
        RouteEdge malformed = edge("backwards", "demand", "chamber", backwards);
        assertThat(CorridorRetainedTerminalApproaches.build(malformed, f.terminal, f.end(), 0,
                router, router.prepare(f.features), List.of(), List.of())).isEmpty();
    }

    @Test
    void relocationUsesOnePreparedWindowAtMost38PrefixChecksAndEightChoicesWithoutGlobalSearch() throws Exception {
        Fixture f = fixture(0, false);
        CountingRouter bounded = new CountingRouter();
        Coordinate target = new Coordinate(20, -50);
        List<RoutePath> paths = CorridorRetainedTerminalApproaches.build(f.edge, f.terminal, target, 0,
                bounded, bounded.prepare(f.features), List.of(), List.of());
        assertThat(paths).hasSize(8);
        assertThat(bounded.preparations).isEqualTo(1);
        assertThat(bounded.prefixChecks).isBetween(1, 38);
        assertSafe(paths, f, target);
        assertThat(keys(paths)).doesNotHaveDuplicates();
        assertThatThrownBy(() -> paths.add(paths.get(0))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(keys(build(f, target, List.of(), List.of()))).isEqualTo(keys(paths));
    }

    @Test
    void identicalRoundedTailsReachExpensiveChecksOnlyOnce() throws Exception {
        Fixture f = fixture(0, false);
        CountingRouter counted = new CountingRouter();
        List<RoutePath> paths = CorridorRetainedTerminalApproaches.build(f.edge, f.terminal, new Coordinate(20, -50), 0,
                counted, counted.prepare(f.features), List.of(), List.of());
        assertThat(paths).hasSize(8);
        assertThat(counted.prefixChecks)
                .as("prefix checks=%s, distinct rounded outside paths=%s", counted.prefixChecks, counted.checkedPrefixes.size())
                .isEqualTo(counted.checkedPrefixes.size());
        // До дедупликации этот же fixture выполнял 38 проверок при 27 разных хвостах.
        assertThat(counted.prefixChecks).isEqualTo(27);
    }

    @Test
    void completeOrderedOutputsMatchTheSource52BaselineAcrossGeometryFixtures() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();
        for (double degrees : new double[] {0, 37, 113}) {
            for (boolean reverse : new boolean[] {false, true}) {
                for (String scenario : List.of("unchanged", "relocated", "blocked", "free")) {
                    Fixture f = fixture(degrees, reverse);
                    if (scenario.equals("free")) f.features.clear();
                    double angle = Math.toRadians(degrees);
                    Coordinate target = scenario.equals("unchanged") ? f.end()
                            : new Coordinate(f.end().x + 10 * Math.sin(angle), f.end().y - 10 * Math.cos(angle));
                    if (scenario.equals("blocked")) {
                        ImportedOfficialFeature blocker = feature("tail-obstacle", "park",
                                "POLYGON ((12 -49,18 -49,18 -45,12 -45,12 -49))");
                        AffineTransformation transform = AffineTransformation.rotationInstance(angle);
                        if (degrees != 0) transform.translate(414000.123, 6173000.456);
                        f.features.add(new ImportedOfficialFeature(blocker.getFeatureId(), blocker.getObjectType(),
                                blocker.getAttributes(), transform.transform(blocker.getMetricGeometry())));
                    }
                    actual.put(degrees + "/" + reverse + "/" + scenario, completeOutputDigest(build(f, target, List.of(), List.of())));
                }
            }
        }
        // Зафиксированы ДО оптимизации: сравниваем полный упорядоченный результат, не только длину/число путей.
        Map<String, String> baseline = Map.ofEntries(
                Map.entry("0.0/unchanged", "b99422a14c017ba1367cd1113e0de068207695af04abd3ee576afea9c92a940c"),
                Map.entry("0.0/relocated", "a286c71d022a6dc4a299eba9950af935a9af439ec70f0491c278fe0e43e025b3"),
                Map.entry("0.0/blocked", "b44c784c29ca60421f153d6bfc81ec9190e46ac1c0d85ddd9847a0f2eaa78042"),
                Map.entry("0.0/free", "981231c92c3e1c0c908469e7c6c0af0ca659a28c0a5f3722892c83d054e09128"),
                Map.entry("37.0/unchanged", "975a86eb116d216576db80353e8c8ec990abbb3290e19668aa9c02b1f761c72a"),
                Map.entry("37.0/relocated", "7cf7f4ccf5642efad7ab18da430365ce2feafb76be9d37d49ca4214af9b7cdad"),
                Map.entry("37.0/blocked", "870f479d2fe00700d4c9368b6af8877f4ff0d4436d5b86cde73a341d66db1924"),
                Map.entry("37.0/free", "5c0edf4659f5af897a9481c57707990d6be896cf338efb91fff5538f7cfba968"),
                Map.entry("113.0/unchanged", "09b3af08050a459c73f41de5751ae8c60bf7860959b3a5136d13dc2bb0509c0b"),
                Map.entry("113.0/relocated", "5e837aa83f93bb579ffed64fa5ad6e57e6e8d912b1685ed76efec85a37c5b36b"),
                Map.entry("113.0/blocked", "8c829ca066fe58e1a4c64e2dacbacbf6d9e79eebfe2935627c9e3bc870714075"),
                Map.entry("113.0/free", "02d90fc085216584e28b578d48e012c62f417c850c68ceae066b0236019aa394"));
        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(label -> expected.put(label, baseline.get(label.replace("/false/", "/").replace("/true/", "/"))));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void roundedTailIdentityPreservesEveryVertexOrderAndMillimetreDifference() {
        List<Coordinate> original = List.of(new Coordinate(414000.123, 6173000.456),
                new Coordinate(414010.123, 6173000.456), new Coordinate(414010.123, 6173010.456));
        List<Coordinate> submillimetre = original.stream()
                .map(p -> new Coordinate(p.x + 0.0001, p.y - 0.0001)).collect(Collectors.toList());
        assertThat(tailKey(submillimetre)).isEqualTo(tailKey(original));
        List<Coordinate> different = new ArrayList<>(original);
        different.set(1, new Coordinate(original.get(1).x + 0.001, original.get(1).y));
        assertThat(tailKey(different)).isNotEqualTo(tailKey(original));
        List<Coordinate> reverse = new ArrayList<>(original);
        Collections.reverse(reverse);
        assertThat(tailKey(reverse)).isNotEqualTo(tailKey(original));
        List<Coordinate> interpolated = new ArrayList<>(original);
        interpolated.add(1, new Coordinate(414005.123, 6173000.456));
        assertThat(tailKey(interpolated)).isNotEqualTo(tailKey(original));
        List<Coordinate> repeated = new ArrayList<>(original);
        repeated.add(1, original.get(0));
        assertThat(tailKey(repeated)).isNotEqualTo(tailKey(original));
    }

    private Object tailKey(List<Coordinate> points) {
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                CorridorRetainedTerminalApproaches.class, "roundedTailKey", points);
    }

    @Test
    void interruptionDuringPreparationStopsBeforePrefixChecks() throws Exception {
        Fixture f = fixture(0, false);
        CountingRouter bounded = new CountingRouter();
        bounded.interruptOnPrepare = true;
        try {
            assertThatThrownBy(() -> CorridorRetainedTerminalApproaches.build(f.edge, f.terminal, new Coordinate(20, -50), 0,
                    bounded, bounded.prepare(f.features), List.of(), List.of())).isInstanceOf(CancellationException.class);
            assertThat(bounded.prefixChecks).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void excessiveFreshChoicesAreRejectedBeforeGeometryWork() throws Exception {
        Fixture f = fixture(0, false);
        CountingRouter bounded = new CountingRouter();
        RoutePath path = new RoutePath(f.points, List.of(), rules.line(f.points).getLength());
        assertThatThrownBy(() -> CorridorRetainedTerminalApproaches.build(f.edge, f.terminal, f.end(), 0,
                bounded, bounded.prepare(f.features), Collections.nCopies(9, path), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bounded.preparations).isZero();
    }

    @Test
    void excessiveIncumbentPointsDoNotExpandTheTailBudget() throws Exception {
        Fixture f = fixture(0, false);
        List<Coordinate> many = new ArrayList<>();
        for (int i = 0; i < 513; i++) many.add(new Coordinate(1 - i, 10));
        CountingRouter bounded = new CountingRouter();
        assertThat(CorridorRetainedTerminalApproaches.build(edge("long", "demand", "chamber", many), f.terminal, f.end(), 0,
                bounded, bounded.prepare(f.features), List.of(), List.of())).isEmpty();
        assertThat(bounded.preparations).isZero();
    }

    @Test
    void egressMatchingInspectsAtMostSixteenCandidatesBeforeConservativeFallback() throws Exception {
        Fixture f = fixture(0, false);
        List<Coordinate> points = new ArrayList<>(f.points);
        points.set(0, new Coordinate(10, 10));
        List<OfficialRouteGeometryRules.NormalEgress> real = rules.normalEgressCandidates(f.features, 50,
                points.get(0), points.get(1), RoutePlannerTuning.stable().getEngineeringEgressExtraM());
        OfficialRouteGeometryRules.NormalEgress matching = real.stream().filter(e -> e.exit().x < 0).findFirst().orElseThrow();
        OfficialRouteGeometryRules.NormalEgress other = real.stream().filter(e -> e.exit().x >= 0).findFirst().orElseThrow();
        AtomicInteger inspected = new AtomicInteger();
        OfficialRouteGeometryRules boundedRules = new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry()) {
            @Override List<NormalEgress> normalEgressCandidates(List<ImportedOfficialFeature> features, int diameter,
                    Coordinate point, Coordinate target, double extra) {
                return new AbstractList<NormalEgress>() {
                    @Override public NormalEgress get(int index) { inspected.incrementAndGet(); return index == 16 ? matching : other; }
                    @Override public int size() { return 17; }
                };
            }
        };
        OfficialObstacleRouter bounded = new OfficialObstacleRouter(boundedRules);
        RouteNode terminal = new RouteNode("demand", "demand_connection", new RouteCoordinate(10, 10), false, false, 0, null);
        assertThat(CorridorRetainedTerminalApproaches.build(edge("bounded", "demand", "chamber", points), terminal, f.end(), 0,
                bounded, bounded.prepare(f.features), List.of(), List.of())).isEmpty();
        assertThat(inspected).hasValue(16);
    }

    @Test
    void retainedInputIsNotPermissionToTransitThroughAnotherPartOfOwnBuilding() throws Exception {
        Fixture f = fixture(0, false);
        f.features.clear();
        f.features.add(feature("home", "oks", "MULTIPOLYGON (((0 0,20 0,20 20,0 20,0 0)),"
                + "((-45 -15,-35 -15,-35 -5,-45 -5,-45 -15)))"));
        assertThat(build(f, f.end(), List.of(), List.of())).isEmpty();
    }

    @Test
    void noOksDoesNotGrantAFreeRootClearanceExemption() throws Exception {
        Fixture f = fixture(0, false);
        f.features.clear();
        f.features.add(feature("nearby", "oks", "POLYGON ((2 0,22 0,22 20,2 20,2 0))"));
        assertThat(build(f, f.end(), List.of(), List.of())).isEmpty();
    }

    private List<RoutePath> build(Fixture f, Coordinate target, List<RoutePath> fresh, List<RouteEdge> retained) {
        return CorridorRetainedTerminalApproaches.build(f.edge, f.terminal, target, Math.toRadians(f.degrees),
                router, router.prepare(f.features), fresh, retained);
    }

    private void assertSafe(List<RoutePath> paths, Fixture f, Coordinate target) {
        for (RoutePath path : paths) {
            RoutePath reversed = path.reversed();
            RouteEdge edge = new RouteEdge("checked", "chamber", "demand", reversed.lengthM(), reversed.coordinates().stream()
                    .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()), reversed.sections(), BigDecimal.ONE, 50);
            RouteNode end = new RouteNode("chamber", "existing_chamber_tie_in", new RouteCoordinate(target.x, target.y), true, true, 2, null);
            assertThat(new OfficialRouteValidator(rules).validate(List.of(f.terminal, end), List.of(edge), f.features))
                    .extracting(RouteValidationIssue::getCode).isEmpty();
            assertThat(OfficialRouteDeflectionRules.validatePolyline(edge.getId(), edge.getCoordinates()).getIssues()).isEmpty();
            assertThat(new EngineeringRouteEvaluator().evaluate(List.of(edge)).isCompliant()).isTrue();
            assertThat(Math.abs(path.lengthM() - rules.line(path.coordinates()).getLength())).isLessThan(0.000001);
            assertThat(Math.abs(path.sections().stream().mapToDouble(s -> s.getLengthM().doubleValue()).sum() - path.lengthM())).isLessThan(0.01);
            assertThat(path.coordinates().get(0).distance(f.points.get(0))).isLessThan(0.002);
            assertThat(path.coordinates().get(path.coordinates().size() - 1).distance(target)).isLessThan(0.002);
        }
    }

    private boolean preservesElbows(RoutePath path, Fixture f) {
        return path.coordinates().stream().anyMatch(p -> p.distance(f.points.get(1)) < 0.002)
                && path.coordinates().stream().anyMatch(p -> p.distance(f.points.get(2)) < 0.002);
    }

    private List<String> keys(List<RoutePath> paths) {
        return paths.stream().map(p -> p.coordinates().toString()).collect(Collectors.toList());
    }

    /** Порядок путей, точные double-длины, все координаты и все поля тарифных секций. */
    private String completeOutputDigest(List<RoutePath> paths) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var result = mapper.createArrayNode();
        for (RoutePath path : paths) {
            var row = result.addObject();
            row.put("lengthBits", Long.toHexString(Double.doubleToLongBits(path.lengthM())));
            var coordinates = row.putArray("coordinates");
            for (Coordinate point : path.coordinates()) coordinates.addArray().add(point.x).add(point.y);
            row.set("sections", mapper.valueToTree(path.sections()));
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(result.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }

    private RoutePath straight(Coordinate start, Coordinate end) {
        return new RoutePath(List.of(start, end), List.of(), start.distance(end));
    }

    private Fixture fixture(double degrees, boolean reverse) throws Exception {
        AffineTransformation transform = AffineTransformation.rotationInstance(Math.toRadians(degrees));
        if (degrees != 0) transform.translate(414000.123, 6173000.456);
        ImportedOfficialFeature home = feature("home", "oks", "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        Geometry moved = transform.transform(home.getMetricGeometry());
        List<Coordinate> points = new ArrayList<>();
        for (Coordinate p : List.of(new Coordinate(1, 10), new Coordinate(-40, 10), new Coordinate(-40, -40), new Coordinate(20, -40))) {
            Coordinate q = transform.transform(p, new Coordinate());
            points.add(new RouteCoordinate(q.x, q.y).toCoordinate());
        }
        List<Coordinate> oriented = new ArrayList<>(points);
        if (reverse) Collections.reverse(oriented);
        RouteEdge edge = edge("incumbent", reverse ? "chamber" : "demand", reverse ? "demand" : "chamber", oriented);
        return new Fixture(degrees, points, edge, new ArrayList<>(List.of(new ImportedOfficialFeature("home", "restriction", home.getAttributes(), moved))));
    }

    private RouteEdge edge(String id, String from, String to, List<Coordinate> points) {
        return new RouteEdge(id, from, to, rules.line(points).getLength(), points.stream()
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()), List.of(), new BigDecimal("1"), 50);
    }

    private ImportedOfficialFeature feature(String id, String kind, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode().put("restriction_type", kind), new WKTReader().read(wkt));
    }

    private final class CountingRouter extends OfficialObstacleRouter {
        int preparations;
        int prefixChecks;
        final Set<List<Coordinate>> checkedPrefixes = new HashSet<>();
        boolean interruptOnPrepare;
        CountingRouter() { super(rules); }
        @Override PreparedCorridor prepareCorridor(int diameter, OfficialRoutingEnvironment environment,
                Envelope bounds, Coordinate root, String targetId) {
            preparations++;
            PreparedCorridor result = super.prepareCorridor(diameter, environment, bounds, root, targetId);
            if (interruptOnPrepare) Thread.currentThread().interrupt();
            return result;
        }
        @Override RoutePath withCheckedTerminalPrefix(OfficialRouteGeometryRules.NormalEgress egress,
                RoutePath outside, int diameter, OfficialRoutingEnvironment environment) {
            prefixChecks++;
            checkedPrefixes.add(outside.coordinates());
            return super.withCheckedTerminalPrefix(egress, outside, diameter, environment);
        }
        @Override RoutePath find(Coordinate start, Coordinate end, int diameter, OfficialRoutingEnvironment environment,
                Set<String> exemptions, RoutePreference preference, List<LineString> accepted) {
            throw new AssertionError("Retained terminal tail must not run global search");
        }
    }

    private static final class Fixture {
        final double degrees;
        final List<Coordinate> points;
        final RouteEdge edge;
        final RouteNode terminal;
        final List<ImportedOfficialFeature> features;
        Fixture(double degrees, List<Coordinate> points, RouteEdge edge, List<ImportedOfficialFeature> features) {
            this.degrees = degrees;
            this.points = points;
            this.edge = edge;
            this.features = features;
            this.terminal = new RouteNode("demand", "demand_connection", new RouteCoordinate(points.get(0).x, points.get(0).y), false, false, 0, null);
        }
        Coordinate end() { return points.get(points.size() - 1); }
    }
}
