package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
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
