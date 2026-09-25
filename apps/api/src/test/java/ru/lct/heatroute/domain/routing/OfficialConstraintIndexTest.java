package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialConstraintIndexTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final ObjectMapper mapper = new ObjectMapper();
    private final WKTReader reader = new WKTReader();

    @Test
    void indexedQueriesRetainOriginalOrderIncludingDuplicateOccurrences() throws Exception {
        for (int count : new int[] {128, 144, 300}) {
            List<Constraint> constraints = fixture(count);
            Collections.shuffle(constraints, new Random(91));
            constraints.add(3, constraints.get(0));
            ConstraintIndex index = rules.index(constraints);

            for (Envelope envelope : List.of(
                    new Envelope(-20, 1000, -20, 2000),
                    new Envelope(50, 190, 40, 165),
                    new Envelope(110, 280, 100, 900))) {
                assertThat(index.query(envelope))
                        .as("%s constraints / %s", count, envelope)
                        .containsExactlyElementsOf(intersecting(constraints, envelope));
            }
        }
    }

    @Test
    void usesClearanceEnvelopeForForbiddenObjectsAndSourceForCrossings() throws Exception {
        List<Constraint> constraints = fixture(144);
        constraints.addAll(rules.baseConstraints(List.of(
                restriction("oks", "building", "POLYGON ((-100 0, -99 0, -99 1, -100 1, -100 0))"),
                restriction("road", "road", "POLYGON ((-80 0, -79 0, -79 1, -80 1, -80 0))")), 100));
        ConstraintIndex index = rules.index(constraints);

        assertThat(index.query(new Envelope(-95, -95, 0.5, 0.5)))
                .extracting(Constraint::id).containsExactly("building");
        assertThat(index.query(new Envelope(-75, -75, 0.5, 0.5))).isEmpty();
        assertThat(index.query(new Envelope(-80, -80, 0.5, 0.5)))
                .extracting(Constraint::id).containsExactly("road");
    }

    @Test
    void includesEnvelopeBoundaryTouchesAndReturnsEmptyForDisjointQueries() throws Exception {
        List<Constraint> constraints = fixture(144);
        Constraint first = constraints.get(0);
        Geometry indexed = first.rule().isForbidden() ? first.blocked() : first.source();
        Envelope bounds = indexed.getEnvelopeInternal();
        Envelope touch = new Envelope(bounds.getMaxX(), bounds.getMaxX(), bounds.getMinY(), bounds.getMinY());
        ConstraintIndex index = rules.index(constraints);

        assertThat(index.query(touch)).contains(first)
                .containsExactlyElementsOf(intersecting(constraints, touch));
        assertThat(index.query(new Envelope(-1000, -900, -1000, -900))).isEmpty();
        assertThat(index.query(new Envelope())).isEmpty();
    }

    @Test
    void retainsExactSegmentAndPointDecisionsAgainstLinearScan() throws Exception {
        List<Constraint> constraints = fixture(144);
        Collections.shuffle(constraints, new Random(27));
        ConstraintIndex indexed = rules.index(constraints);
        ConstraintIndex linear = linearIndex(constraints);
        Random random = new Random(9232026L);
        for (int iteration = 0; iteration < 1000; iteration++) {
            Coordinate start = new Coordinate(random.nextDouble() * 480 - 20, random.nextDouble() * 480 - 20);
            Coordinate end = new Coordinate(start.x + random.nextDouble() * 200 - 100,
                    start.y + random.nextDouble() * 200 - 100);
            assertThat(rules.segmentAllowed(start, end, indexed))
                    .as("segment %s", iteration).isEqualTo(rules.segmentAllowed(start, end, linear));
            assertThat(rules.pointInsideForbiddenClearance(start, indexed))
                    .as("point %s", iteration).isEqualTo(rules.pointInsideForbiddenClearance(start, linear));
        }
    }

    @Test
    void preservesNavigationNodeSequenceComparedWithLinearScan() throws Exception {
        List<Constraint> constraints = fixture(144);
        Collections.shuffle(constraints, new Random(27));
        ConstraintIndex indexed = rules.index(constraints);
        ConstraintIndex linear = linearIndex(constraints);
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        Coordinate start = new Coordinate(-20, 50);
        Coordinate end = new Coordinate(300, 220);

        for (double expansion : new double[] {75, 200, 600}) {
            List<Coordinate> indexedNodes = ReflectionTestUtils.invokeMethod(router, "navigationNodes",
                    start, end, indexed, expansion);
            List<Coordinate> linearNodes = ReflectionTestUtils.invokeMethod(router, "navigationNodes",
                    start, end, linear, expansion);
            assertThat(indexedNodes).containsExactlyElementsOf(linearNodes);
        }
    }

    @Test
    void snapshotsInputAndDoesNotReuseMutableQueryResults() throws Exception {
        List<Constraint> constraints = fixture(144);
        List<Constraint> snapshot = List.copyOf(constraints);
        Envelope envelope = new Envelope(0, 200, 0, 200);
        ConstraintIndex index = rules.index(constraints);
        constraints.clear();
        List<Constraint> firstResult = index.query(envelope);
        List<Constraint> secondResult = index.query(envelope);

        assertThat(firstResult).containsExactlyElementsOf(intersecting(snapshot, envelope));
        assertThat(secondResult).containsExactlyElementsOf(firstResult).isNotSameAs(firstResult);
        firstResult.clear();
        assertThat(index.query(envelope)).containsExactlyElementsOf(secondResult);
    }

    @Test
    void keepsSmallSetsOnTheLinearPath() throws Exception {
        for (int count : new int[] {15, 127}) {
            List<Constraint> constraints = fixture(count);
            ConstraintIndex index = rules.index(constraints);
            assertThat(ReflectionTestUtils.getField(index, "tree")).isNull();
            assertThat(index.query(new Envelope(-1000, -900, -1000, -900)))
                    .containsExactlyElementsOf(constraints);
        }
        assertThat(rules.index(List.of()).query(new Envelope())).isEmpty();
    }

    @Test
    void primitiveCollectionKeepsEveryGrowthBoundaryAndTheOriginalOrder() throws Exception {
        List<Constraint> constraints = separatedParks(4096);
        Collections.reverse(constraints);
        ConstraintIndex index = rules.index(constraints);
        for (int hits : new int[] {0, 1, 2, 3, 15, 16, 17, 23, 24, 25, 35, 36, 37, 127, 128, 129, 4096}) {
            Envelope query = firstParks(hits);
            assertThat(index.query(query)).as("hits=%s", hits).hasSize(hits)
                    .containsExactlyElementsOf(legacyQuery(index, constraints, query));
        }
    }

    @Test
    void primitiveCollectionMatchesLegacyTreeQueriesForMixedExpandedConstraints() throws Exception {
        List<Constraint> constraints = fixture(300);
        Collections.shuffle(constraints, new Random(802026));
        // Повтор одного объекта и разные позиции не должны схлопнуться при сортировке ordinal.
        constraints.add(7, constraints.get(230));
        constraints.add(8, constraints.get(230));
        ConstraintIndex index = rules.index(constraints);
        Random random = new Random(2026080);
        for (int iteration = 0; iteration < 1500; iteration++) {
            double x = random.nextDouble() * 500 - 50, y = random.nextDouble() * 1300 - 50;
            Envelope query = new Envelope(x, x + random.nextDouble() * 500,
                    y, y + random.nextDouble() * 1300);
            assertThat(index.query(query)).as("mixed query %s", iteration)
                    .containsExactlyElementsOf(legacyQuery(index, constraints, query));
        }
    }

    @Test
    void queryLocalCollectionDoesNotLeakAfterLargeOrEmptyQueries() throws Exception {
        List<Constraint> constraints = separatedParks(144);
        Collections.shuffle(constraints, new Random(80));
        ConstraintIndex index = rules.index(constraints);
        for (int hits : new int[] {144, 0, 1, 144, 2, 0, 3, 144, 1, 2, 3, 0}) {
            Envelope query = firstParks(hits);
            assertThat(index.query(query)).hasSize(hits)
                    .containsExactlyElementsOf(legacyQuery(index, constraints, query));
        }
    }

    @Test
    void smallResultsStayImmutableAndLargerResultsStayIndependent() throws Exception {
        List<Constraint> constraints = separatedParks(144);
        ConstraintIndex index = rules.index(constraints);
        for (int hits : new int[] {0, 1, 2}) {
            List<Constraint> result = index.query(firstParks(hits));
            assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        }
        List<Constraint> first = index.query(firstParks(3));
        List<Constraint> second = index.query(firstParks(3));
        assertThat(first).containsExactlyElementsOf(second).isNotSameAs(second);
        first.clear();
        assertThat(second).hasSize(3);
        assertThat(index.query(firstParks(3))).containsExactlyElementsOf(second);
    }

    @Test
    void concurrentQueriesUseSeparatePrimitiveCollectors() throws Exception {
        List<Constraint> constraints = separatedParks(300);
        Collections.shuffle(constraints, new Random(80));
        ConstraintIndex index = rules.index(constraints);
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                final int offset = worker;
                jobs.add(executor.submit(() -> {
                    for (int iteration = 0; iteration < 100; iteration++) {
                        Envelope query = firstParks((iteration * 17 + offset) % 301);
                        assertThat(index.query(query)).containsExactlyElementsOf(legacyQuery(index, constraints, query));
                    }
                }));
            }
            for (Future<?> job : jobs) job.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Контроль — прежнее тело query на том же дереве, включая реальные expanded envelopes. */
    @SuppressWarnings("unchecked")
    private List<Constraint> legacyQuery(ConstraintIndex index, List<Constraint> constraints, Envelope query) {
        STRtree tree = (STRtree) ReflectionTestUtils.getField(index, "tree");
        List<Integer> ordinals = (List<Integer>) tree.query(query);
        ordinals.sort(Integer::compare);
        return ordinals.stream().map(constraints::get).collect(Collectors.toList());
    }

    private Envelope firstParks(int count) {
        return count == 0 ? new Envelope(-10, -5, -10, -5) : new Envelope(0, (count - 1) * 10 + 4, 0, 4);
    }

    private List<Constraint> separatedParks(int count) throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int x = i * 10;
            features.add(restriction("park", "p-" + i, "POLYGON ((" + x + " 0, " + (x + 4)
                    + " 0, " + (x + 4) + " 4, " + x + " 4, " + x + " 0))"));
        }
        return new ArrayList<>(rules.baseConstraints(features, 100));
    }

    private ConstraintIndex linearIndex(List<Constraint> constraints) {
        ConstraintIndex index = rules.index(constraints);
        // Контроль использует прежний линейный путь самого класса, без подмены предикатов геометрии.
        ReflectionTestUtils.setField(index, "tree", null);
        return index;
    }

    private List<Constraint> intersecting(List<Constraint> constraints, Envelope envelope) {
        return constraints.stream().filter(constraint -> {
            Geometry geometry = constraint.rule().isForbidden() ? constraint.blocked() : constraint.source();
            return geometry != null && !geometry.isEmpty() && geometry.getEnvelopeInternal().intersects(envelope);
        }).collect(Collectors.toList());
    }

    private List<Constraint> fixture(int count) throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double x = index % 10 * 40;
            double y = index / 10 * 40;
            String type = index % 5 == 0 ? "road" : index % 3 == 0 ? "oks" : "park";
            features.add(restriction(type, "feature-" + (count - index),
                    "POLYGON ((" + x + " " + y + ", " + (x + 10) + " " + y + ", "
                            + (x + 10) + " " + (y + 20) + ", " + x + " " + (y + 20)
                            + ", " + x + " " + y + "))"));
        }
        return new ArrayList<>(rules.baseConstraints(features, 100));
    }

    private ImportedOfficialFeature restriction(String type, String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction",
                mapper.readTree("{\"restriction_type\":\"" + type + "\"}"), reader.read(wkt));
    }
}
