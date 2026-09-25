package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Сверяет подготовку одного поиска с независимой пересборкой узлов по действующему контракту. */
class OfficialObstacleRouterPreparationTest {
    private static final double[] EXPANSIONS = {75, 200, 600};
    private static final String BLOCK = "POLYGON ((40 -10,60 -10,60 10,40 10,40 -10))";
    private static final String COURTYARD =
            "POLYGON ((0 0,100 0,100 100,60 100,60 20,40 20,40 100,0 100,0 0))";
    private static final String CLOSED =
            "POLYGON ((0 0,100 0,100 100,0 100,0 0),(30 30,30 70,70 70,70 30,30 30))";
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void preservesEveryOrderedNavigationPassIncludingPocketsAndSpecialPortals() throws Exception {
        for (Scenario scenario : scenarios()) {
            ConstraintIndex index = rules.index(constraints(scenario));
            NavigationObstaclePreparation preparation = preparation();
            for (boolean pockets : new boolean[] {false, true}) {
                for (double expansion : EXPANSIONS) {
                    List<Coordinate> expected = legacyNodes(scenario.start, scenario.end, index, expansion, pockets);
                    List<Coordinate> actual = preparedNodes(scenario.start, scenario.end, index, expansion, pockets, preparation);
                    assertExactCoordinates(expected, actual);
                }
            }
            // Результирующие списки не владеют сохранёнными опорами и не могут их испортить.
            List<Coordinate> first = preparedNodes(scenario.start, scenario.end, index, 75, true, preparation);
            if (first.size() > 2) first.get(2).x += 1000;
            assertExactCoordinates(legacyNodes(scenario.start, scenario.end, index, 75, true),
                    preparedNodes(scenario.start, scenario.end, index, 75, true, preparation));
        }
    }

    @Test
    void roadAndTramPortalsIncludeProtectiveStraightAndFinalDiameterClearance() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            for (int diameter : new int[] {100, 1400}) {
                Constraint constraint = rules.baseConstraints(List.of(feature(type, type,
                        read("POLYGON ((75 -100,80 -100,80 100,75 100,75 -100))"))), diameter).get(0);
                Coordinate start = new Coordinate(0, 0), end = new Coordinate(100, 70);
                Envelope corridor = new Envelope(start, end);
                corridor.expandBy(75);
                List<Coordinate> portals = new ArrayList<>();
                call("addSpecialCrossingPortals", portals, constraint, rules.line(List.of(start, end)), corridor);
                // max(3, R+W/2)+0.25: ДУ100 ограничен прямыми 3 м, ДУ1400 — отступом 3.225 м.
                double margin = diameter == 100 ? 3.25 : 3.475;
                assertExactCoordinates(List.of(new Coordinate(80 + margin, 54.25),
                        new Coordinate(75 - margin, 54.25)), portals);
                ConstraintIndex index = rules.index(List.of(constraint));
                assertExactCoordinates(legacyNodes(start, end, index, 75, true),
                        preparedNodes(start, end, index, 75, true, preparation()));
            }
        }
    }

    @Test
    void findAndFindAfterPreserveExactLegacyRoutesAndSections() throws Exception {
        int successfulPaths = 0;
        for (Scenario scenario : scenarios()) {
            for (Coordinate previous : Arrays.asList(null, scenario.previous)) {
                RoutePath expected = legacyFind(scenario, previous);
                OfficialRoutingEnvironment environment = router.prepare(scenario.features);
                RoutePath actual = previous == null
                        ? router.find(scenario.start, scenario.end, 100, environment, Set.of(), RoutePreference.SHORTEST)
                        : router.findAfter(previous, scenario.start, scenario.end, 100,
                                environment, Set.of(), RoutePreference.SHORTEST, List.of());
                assertExactPath(expected, actual);
                if (expected != null) successfulPaths++;
            }
        }
        assertThat(successfulPaths).isGreaterThanOrEqualTo(2);
    }

    @Test
    void pocketSelectionStillUsesTheCurrentEndpoints() throws Exception {
        Scenario scenario = scenarios().get(1);
        ConstraintIndex index = rules.index(constraints(scenario));
        NavigationObstaclePreparation preparation = preparation();
        List<Coordinate> original = preparedNodes(scenario.start, scenario.end, index, 75, true, preparation);
        Coordinate moved = new Coordinate(51, 40);
        List<Coordinate> expected = legacyNodes(moved, scenario.end, index, 75, true);
        assertExactCoordinates(expected, preparedNodes(moved, scenario.end, index, 75, true, preparation));
        assertThat(expected.subList(2, expected.size())).isNotEqualTo(original.subList(2, original.size()));
        assertExactCoordinates(original, preparedNodes(scenario.start, scenario.end, index, 75, true, preparation));
    }

    @Test
    void actuallyPreparesEachAdmittedObstacleOnceAcrossSixExpansions() throws Exception {
        List<Counts> counts = new ArrayList<>();
        List<ImportedOfficialFeature> features = new ArrayList<>();
        for (double offset : new double[] {0, 150, 450, 700}) {
            Counts counter = new Counts();
            counts.add(counter);
            Geometry polygon = AffineTransformation.translationInstance(0, offset).transform(read(BLOCK));
            features.add(feature("obstacle-" + offset, "park", new CountingPolygon((Polygon) polygon, counter, false)));
        }
        ConstraintIndex index = rules.index(rules.baseConstraints(features, 100));
        Coordinate start = new Coordinate(0, 0), end = new Coordinate(100, 0);
        List<List<Coordinate>> expected = new ArrayList<>();
        for (boolean pockets : new boolean[] {false, true}) {
            for (double expansion : EXPANSIONS) expected.add(legacyNodes(start, end, index, expansion, pockets));
        }
        for (int i = 0; i < counts.size(); i++) {
            assertThat(counts.get(i).buffers).isEqualTo(new int[] {6, 4, 2, 0}[i]);
            assertThat(counts.get(i).hulls).isEqualTo(counts.get(i).buffers);
            counts.get(i).reset();
        }
        NavigationObstaclePreparation preparation = preparation();
        int pass = 0;
        for (boolean pockets : new boolean[] {false, true}) {
            for (double expansion : EXPANSIONS) {
                assertExactCoordinates(expected.get(pass++), preparedNodes(start, end, index, expansion, pockets, preparation));
            }
        }
        for (int i = 0; i < counts.size(); i++) {
            assertThat(counts.get(i).buffers).isEqualTo(i < 3 ? 1 : 0);
            assertThat(counts.get(i).hulls).isEqualTo(counts.get(i).buffers);
        }
    }

    @Test
    void fullSearchSharesPreparationButTheNextRequestPreparesAgain() throws Exception {
        Counts counts = new Counts();
        Scenario scenario = new Scenario(List.of(feature("closed", "oks",
                new CountingPolygon((Polygon) read(CLOSED), counts, false))),
                new Coordinate(50, 50), new Coordinate(50, -40), new Coordinate(50, 49));
        // Core features preserve the counting geometry; no fake buffer/hull implementation.
        OfficialRoutingEnvironment environment = new OfficialRoutingEnvironment(scenario.features, rules);
        assertThat(legacyFind(scenario, null, environment)).isNull();
        assertThat(counts.buffers).isEqualTo(6);
        assertThat(counts.hulls).isEqualTo(6);
        counts.reset();
        assertThat(router.find(scenario.start, scenario.end, 100, environment, Set.of(), RoutePreference.SHORTEST)).isNull();
        assertThat(counts.buffers).isEqualTo(1);
        assertThat(counts.hulls).isEqualTo(1);
        for (int request = 2; request <= 3; request++) {
            assertThat(router.findAfter(scenario.previous, scenario.start, scenario.end, 100,
                    environment, Set.of(), RoutePreference.SHORTEST, List.of())).isNull();
            assertThat(counts.buffers).isEqualTo(request);
            assertThat(counts.hulls).isEqualTo(request);
        }
    }

    @Test
    void keysByConstraintIdentityIncludingSameIdRootTransformations() throws Exception {
        Constraint original = rules.baseConstraints(List.of(feature("same", "oks", read(BLOCK))), 100).get(0);
        Constraint transformed = rules.applicableConstraints(List.of(original), Set.of(),
                new Coordinate(0, 0), new Coordinate(62, 0)).get(0);
        Constraint other = rules.baseConstraints(List.of(feature("same", "oks", read(COURTYARD))), 100).get(0);
        assertThat(transformed).isNotSameAs(original);
        assertThat(transformed.blocked()).isSameAs(transformed.source());
        NavigationObstaclePreparation preparation = preparation();
        for (Constraint constraint : List.of(original, transformed, other)) {
            NavigationObstaclePreparation.Obstacle prepared = preparation.prepare(constraint);
            assertThat(preparation.prepare(constraint)).isSameAs(prepared);
            assertThat(prepared.bufferedBoundary().equalsExact(constraint.blocked().buffer(0.25, 2))).isTrue();
            assertThat(prepared.hull().equalsExact(constraint.blocked().buffer(0.25, 2).convexHull())).isTrue();
        }
        assertThat(retained(preparation)).hasSize(3);
    }

    @Test
    void retentionBudgetsRecomputeWithoutChangingGeometryOrNodes() throws Exception {
        Scenario scenario = scenarios().get(1);
        Constraint constraint = constraints(scenario).get(0);
        NavigationObstaclePreparation.Obstacle expected = preparation().prepare(constraint);
        long weight = (long) expected.bufferedBoundary().getNumPoints()
                + expected.hull().getNumPoints() + expected.support().length;
        for (NavigationObstaclePreparation limited : List.of(
                new NavigationObstaclePreparation(0.25, this::support, 0, Long.MAX_VALUE),
                new NavigationObstaclePreparation(0.25, this::support, 128, weight - 1))) {
            NavigationObstaclePreparation.Obstacle first = limited.prepare(constraint);
            NavigationObstaclePreparation.Obstacle second = limited.prepare(constraint);
            assertThat(second).isNotSameAs(first);
            assertThat(second.bufferedBoundary().equalsExact(expected.bufferedBoundary())).isTrue();
            assertThat(second.hull().equalsExact(expected.hull())).isTrue();
            assertExactCoordinates(Arrays.asList(expected.support()), Arrays.asList(second.support()));
            ConstraintIndex index = rules.index(List.of(constraint));
            assertExactCoordinates(legacyNodes(scenario.start, scenario.end, index, 75, true),
                    preparedNodes(scenario.start, scenario.end, index, 75, true, limited));
            assertThat(retained(limited)).isEmpty();
        }
        Constraint another = rules.baseConstraints(scenario.features, 100).get(0);
        for (NavigationObstaclePreparation exact : List.of(
                new NavigationObstaclePreparation(0.25, this::support, 1, Long.MAX_VALUE),
                new NavigationObstaclePreparation(0.25, this::support, 128, weight))) {
            assertThat(exact.prepare(constraint)).isSameAs(exact.prepare(constraint));
            assertThat(exact.prepare(another)).isNotSameAs(exact.prepare(another));
            assertThat(retained(exact)).hasSize(1);
            assertThat((Long) ReflectionTestUtils.getField(exact, "retainedCoordinates")).isEqualTo(weight);
        }
    }

    @Test
    void cancellationDoesNotPublishPartialPreparationOrIgnoreInterruptOnReuse() throws Exception {
        Constraint constraint = constraints(scenarios().get(0)).get(0);
        AtomicBoolean interrupt = new AtomicBoolean(true);
        NavigationObstaclePreparation preparation = new NavigationObstaclePreparation(0.25, hull -> {
            Coordinate[] coordinates = support(hull);
            if (interrupt.getAndSet(false)) Thread.currentThread().interrupt();
            return coordinates;
        });
        try {
            assertThatThrownBy(() -> preparation.prepare(constraint)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(retained(preparation)).isEmpty();
        } finally {
            Thread.interrupted();
        }
        NavigationObstaclePreparation.Obstacle completed = preparation.prepare(constraint);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> preparation.prepare(constraint)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(preparation.prepare(constraint)).isSameAs(completed);
    }

    // Независимая пересборка buffer/hull на каждом проходе сохраняет точный порядок узлов.
    // G2 добавляет обход road-buffer после порталов с выносом max(3,clearance)+0.25.
    private List<Coordinate> legacyNodes(Coordinate start, Coordinate end, ConstraintIndex constraints,
            double expansion, boolean pockets) {
        List<Coordinate> result = new ArrayList<>(List.of(new Coordinate(start), new Coordinate(end)));
        Envelope corridor = new Envelope(start, end);
        corridor.expandBy(expansion);
        LineString direct = rules.line(List.of(start, end));
        for (Constraint constraint : constraints.query(corridor)) {
            if (!constraint.rule().isForbidden()) {
                call("addSpecialCrossingPortals", result, constraint, direct, corridor);
                if (constraint.blocked() == null) continue;
            }
            if (!constraint.blocked().getEnvelopeInternal().intersects(corridor)
                    || !constraint.blocked().isWithinDistance(direct, expansion)) continue;
            Geometry boundary = constraint.blocked().buffer(0.25, 2);
            Geometry hull = boundary.convexHull();
            Coordinate[] coordinates = support(hull);
            int unique = coordinates.length > 1 && coordinates[0].equals2D(coordinates[coordinates.length - 1])
                    ? coordinates.length - 1 : coordinates.length;
            for (int i = 0; i < unique; i++) result.add(new Coordinate(coordinates[i]));
            if (pockets) call("addPocketNavigationNodes", result, start, end, constraint, boundary, hull, coordinates);
        }
        return call("deduplicate", result);
    }

    private RoutePath legacyFind(Scenario scenario, Coordinate previous) {
        return legacyFind(scenario, previous, router.prepare(scenario.features));
    }

    // The source66 SHORTEST fast paths, fallback order and admission with only legacyNodes substituted.
    private RoutePath legacyFind(Scenario scenario, Coordinate previous, OfficialRoutingEnvironment environment) {
        Coordinate start = scenario.start, end = scenario.end;
        List<Constraint> constraints = environment.constraints(100, Set.of(), start, end);
        ConstraintIndex index = rules.index(constraints);
        if (rules.pointInsideForbiddenClearance(start, index) || rules.pointInsideForbiddenClearance(end, index)) return null;
        if (rules.segmentAllowed(start, end, index)) {
            RoutePath direct = call("headingCheckedPath", List.of(start, end), constraints, index, previous);
            if (direct != null) return direct;
        }
        RoutePath crossing = call("directSpecialCrossing", start, end, index, constraints);
        if (crossing != null) {
            RoutePath checked = call("headingCheckedPath", crossing.coordinates(), constraints, index, previous);
            if (checked != null) return checked;
        }
        List<List<Coordinate>> ordinary = new ArrayList<>();
        for (boolean pockets : new boolean[] {false, true}) {
            for (int corridor = 0; corridor < EXPANSIONS.length; corridor++) {
                List<Coordinate> nodes = legacyNodes(start, end, index, EXPANSIONS[corridor], pockets);
                if (previous != null) nodes = call("headingNavigationNodes", nodes, previous, start, end);
                if (!pockets) ordinary.add(nodes);
                else if (Boolean.TRUE.equals(call("sameOrderedNavigationNodes", ordinary.get(corridor), nodes))) continue;
                Object search = call("shortestPath", nodes, index, RoutePreference.SHORTEST, start, end, previous);
                @SuppressWarnings("unchecked")
                List<Coordinate> found = (List<Coordinate>) ReflectionTestUtils.getField(search, "coordinates");
                if (found.isEmpty()) continue;
                List<Coordinate> normalized = call("normalize", found, index, previous);
                List<Coordinate> constructible = call("snapConstructibleCorners", normalized, index, RoutePreference.SHORTEST, previous);
                if (rules.lineAllowed(rules.line(constructible), index)) {
                    RoutePath checked = call("headingCheckedPath", constructible, constraints, index, previous);
                    if (checked != null) return checked;
                }
                if (previous != null) {
                    RoutePath control = call("headingCheckedPath", found, constraints, index, previous);
                    if (control != null) return control;
                }
            }
        }
        return null;
    }

    private List<Scenario> scenarios() throws Exception {
        AffineTransformation utm = AffineTransformation.rotationInstance(0.37).translate(410000.1234, 6180000.4567);
        return List.of(
                new Scenario(List.of(feature("block", "park", read(BLOCK))),
                        new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(-5, 0)),
                new Scenario(List.of(feature("courtyard", "oks", read(COURTYARD))),
                        new Coordinate(50, 35), new Coordinate(50, -40), new Coordinate(50, 34)),
                new Scenario(List.of(feature("multi", "park", read("MULTIPOLYGON (((40 -10,45 -10,45 10,40 10,40 -10)),"
                        + "((55 -10,60 -10,60 10,55 10,55 -10)))"))),
                        new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(-5, 0)),
                new Scenario(List.of(feature("utm", "oks", utm.transform(read(COURTYARD)))),
                        utm.transform(new Coordinate(50, 35), new Coordinate()),
                        utm.transform(new Coordinate(50, -40), new Coordinate()),
                        utm.transform(new Coordinate(50, 34), new Coordinate())),
                new Scenario(List.of(feature("root-setback", "oks", read(BLOCK))),
                        new Coordinate(0, 0), new Coordinate(62, 0), new Coordinate(-5, 0)),
                new Scenario(List.of(feature("block", "park", read(BLOCK)), feature("road", "road",
                        read("POLYGON ((75 -100,80 -100,80 100,75 100,75 -100))"))),
                        new Coordinate(0, 0), new Coordinate(100, 70), new Coordinate(-5, 0)));
    }

    private List<Constraint> constraints(Scenario scenario) {
        return router.prepare(scenario.features).constraints(100, Set.of(), scenario.start, scenario.end);
    }

    private NavigationObstaclePreparation preparation() {
        return new NavigationObstaclePreparation(0.25, this::support);
    }

    private Coordinate[] support(Geometry hull) { return call("navigationCoordinates", hull); }

    private List<Coordinate> preparedNodes(Coordinate start, Coordinate end, ConstraintIndex index,
            double expansion, boolean pockets, NavigationObstaclePreparation preparation) {
        return call("navigationNodes", start, end, index, expansion, pockets, preparation);
    }

    private <T> T call(String method, Object... arguments) {
        return ReflectionTestUtils.invokeMethod(router, method, arguments);
    }

    private Map<?, ?> retained(NavigationObstaclePreparation preparation) {
        return (Map<?, ?>) ReflectionTestUtils.getField(preparation, "retained");
    }

    private void assertExactPath(RoutePath expected, RoutePath actual) {
        if (expected == null) {
            assertThat(actual).isNull();
            return;
        }
        assertThat(actual).isNotNull();
        assertExactCoordinates(expected.coordinates(), actual.coordinates());
        assertThat(Double.doubleToLongBits(actual.lengthM())).isEqualTo(Double.doubleToLongBits(expected.lengthM()));
        JsonNode actualSections = mapper.valueToTree(actual.sections());
        JsonNode expectedSections = mapper.valueToTree(expected.sections());
        assertThat(actualSections).isEqualTo(expectedSections);
    }

    private void assertExactCoordinates(List<Coordinate> expected, List<Coordinate> actual) {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            Coordinate left = expected.get(i), right = actual.get(i);
            assertThat(new long[] {bits(right.x), bits(right.y), bits(right.getZ()), bits(right.getM())})
                    .as("coordinate %s", i)
                    .containsExactly(bits(left.x), bits(left.y), bits(left.getZ()), bits(left.getM()));
        }
    }

    private long bits(double value) { return Double.doubleToLongBits(value); }
    private Geometry read(String wkt) throws Exception { return new WKTReader().read(wkt); }
    private ImportedOfficialFeature feature(String id, String type, Geometry geometry) {
        return new ImportedOfficialFeature(id, "restriction", mapper.createObjectNode().put("restriction_type", type), geometry);
    }

    private static final class Scenario {
        private final List<ImportedOfficialFeature> features;
        private final Coordinate start, end, previous;
        private Scenario(List<ImportedOfficialFeature> features, Coordinate start, Coordinate end, Coordinate previous) {
            this.features = features; this.start = start; this.end = end; this.previous = previous;
        }
    }

    private static final class Counts {
        private int buffers, hulls;
        private void reset() { buffers = 0; hulls = 0; }
    }

    /** Counts real JTS operations; holes are preserved for the six-pass closed courtyard. */
    private static final class CountingPolygon extends Polygon {
        private final Counts counts;
        private final boolean navigationBoundary;
        private CountingPolygon(Polygon source, Counts counts, boolean navigationBoundary) {
            super(source.getExteriorRing(), holes(source), source.getFactory());
            this.counts = counts;
            this.navigationBoundary = navigationBoundary;
        }
        @Override public Geometry buffer(double distance, int quadrantSegments) {
            boolean navigation = distance == 0.25 && quadrantSegments == 2;
            if (navigation) counts.buffers++;
            Geometry buffered = super.buffer(distance, quadrantSegments);
            return new CountingPolygon((Polygon) buffered, counts, navigation);
        }
        @Override public Geometry convexHull() {
            if (navigationBoundary) counts.hulls++;
            return super.convexHull();
        }
        private static LinearRing[] holes(Polygon source) {
            LinearRing[] holes = new LinearRing[source.getNumInteriorRing()];
            for (int i = 0; i < holes.length; i++) holes[i] = source.getInteriorRingN(i);
            return holes;
        }
    }
}
