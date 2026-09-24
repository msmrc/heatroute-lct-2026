package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialSegmentIntersectionTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test
    void actualBufferedConstraintsAgreeWithJtsAtSeveralDiameters() throws Exception {
        List<ImportedOfficialFeature> features = new OfficialDatasetRoutingTest().loadOfficialFeatures();
        int comparisons = 0;
        for (int diameter : new int[] {50, 200, 500, 1000, 1400}) {
            for (Constraint constraint : forbidden(features, diameter)) {
                Geometry blocked = constraint.blocked();
                PreparedSegmentIntersection fast = new PreparedSegmentIntersection(blocked);
                PreparedGeometry oracle = PreparedGeometryFactory.prepare(blocked.copy());
                for (LineString line : probes(blocked, 526012L)) {
                    boolean expected = oracle.intersects(line);
                    assertThat(fast.intersects(line))
                            .as("DU%d / %s / %s", diameter, constraint.id(), line).isEqualTo(expected);
                    comparisons++;
                }
            }
        }
        assertThat(comparisons).isGreaterThan(50_000);
    }

    @Test
    void realSegmentAllowedPathAgreesWithUnchangedPreparedOracle() throws Exception {
        List<ImportedOfficialFeature> features = new OfficialDatasetRoutingTest().loadOfficialFeatures();
        List<Constraint> constraints = forbidden(features, 300);
        OfficialRouteGeometryRules.ConstraintIndex index = rules.index(constraints);
        List<PreparedGeometry> oracle = constraints.stream().map(c ->
                PreparedGeometryFactory.prepare(c.blocked().copy())).collect(Collectors.toList());
        int comparisons = 0;
        for (Constraint constraint : constraints) {
            for (LineString line : probes(constraint.blocked(), 560926L)) {
                Coordinate from = line.getCoordinateN(0), to = line.getCoordinateN(1);
                if (from.distance(to) <= OfficialRouteGeometryRules.EPSILON_M) continue;
                boolean expected = oracle.stream().noneMatch(p -> p.intersects(line));
                assertThat(rules.segmentAllowed(from, to, index))
                        .as("full constraint index / %s", line).isEqualTo(expected);
                comparisons++;
            }
        }
        assertThat(comparisons).isGreaterThan(5_000);
    }

    private List<Constraint> forbidden(List<ImportedOfficialFeature> features, int diameter) {
        return rules.baseConstraints(features, diameter).stream().filter(c -> c.rule().isForbidden())
                .filter(c -> c.blocked() != null && !c.blocked().isEmpty()).collect(Collectors.toList());
    }

    private List<LineString> probes(Geometry geometry, long seed) {
        Envelope bounds = new Envelope(geometry.getEnvelopeInternal());
        bounds.expandBy(Math.max(1, Math.max(bounds.getWidth(), bounds.getHeight()) * 0.1));
        Random random = new Random(seed);
        List<LineString> lines = new ArrayList<>();
        for (int i = 0; i < 96; i++) lines.add(line(point(bounds, random), point(bounds, random)));
        Coordinate[] vertices = geometry.getCoordinates();
        int step = Math.max(1, vertices.length / 16);
        for (int i = 0; i < vertices.length; i += step) {
            Coordinate vertex = vertices[i];
            Coordinate neighbour = vertices[(i + 1) % vertices.length];
            lines.add(line(vertex, neighbour));
            lines.add(line(vertex, vertex));
            lines.add(line(new Coordinate(Math.nextDown(vertex.x), vertex.y),
                    new Coordinate(Math.nextUp(vertex.x), vertex.y)));
            lines.add(line(new Coordinate(vertex.x - 1, vertex.y), new Coordinate(vertex.x + 1, vertex.y)));
        }
        return lines;
    }

    private Coordinate point(Envelope bounds, Random random) {
        return new Coordinate(bounds.getMinX() + random.nextDouble() * bounds.getWidth(),
                bounds.getMinY() + random.nextDouble() * bounds.getHeight());
    }

    private LineString line(Coordinate start, Coordinate end) {
        return factory.createLineString(new Coordinate[] {new Coordinate(start), new Coordinate(end)});
    }
}
