package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Characterizes one-call query ownership while the indexed predicate is shared by constraints. */
class PreparedSegmentQueryTest {
    private static final GeometryFactory GF = new GeometryFactory();
    private static final WKTReader READER = new WKTReader(GF);

    @Test
    void preparedQueryMatchesLegacyPredicateAndJtsForHolesMultipartTouchesAndExtremes() throws Exception {
        List<Geometry> areas = List.of(
                read("POLYGON ((0 0,40 0,40 40,0 40,0 0),(10 10,10 30,30 30,30 10,10 10))"),
                read("POLYGON ((0 0,40 0,40 10,10 10,10 30,40 30,40 40,0 40,0 0))"),
                read("MULTIPOLYGON (((0 0,12 0,12 20,0 20,0 0),"
                        + "(4 4,4 16,8 16,8 4,4 4)),((20 0,32 0,32 20,20 20,20 0)))"),
                AffineTransformation.translationInstance(414000.123, 6173500.789).transform(
                        read("POLYGON ((0 0,40 0,40 40,0 40,0 0),(10 10,10 30,30 30,30 10,10 10))")),
                read("POLYGON ((0 0,1 0,1 1e-300,0 1e-300,0 0))"));

        for (Geometry area : areas) {
            PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(area);
            assertThat(predicate.usesIndex()).isTrue();
            for (LineString query : probes(area)) {
                PreparedGeometry oracle = PreparedGeometryFactory.prepare(area.copy());
                boolean expected = oracle.intersects(query);
                assertThat(predicate.intersects(query)).as("legacy %s", query).isEqualTo(expected);
                assertThat(predicate.intersectsPrepared(new PreparedSegmentIntersection.Query(query)))
                        .as("prepared %s", query).isEqualTo(expected);
            }
        }
    }

    @Test
    void queryReadsTwoEndpointCoordinatesOnceAcrossSeveralIndexedTargets() throws Exception {
        CountingSequence coordinates = new CountingSequence(new Coordinate[] {
                point(-5, 5), point(55, 5)});
        LineString line = new LineString(coordinates, GF);
        PreparedSegmentIntersection.Query query = new PreparedSegmentIntersection.Query(line);
        List<PreparedSegmentIntersection> targets = List.of(
                new PreparedSegmentIntersection(read("POLYGON ((0 0,10 0,10 10,0 10,0 0))")),
                new PreparedSegmentIntersection(read("POLYGON ((20 0,40 0,40 20,20 20,20 0),"
                        + "(25 4,25 16,35 16,35 4,25 4))")),
                new PreparedSegmentIntersection(read("MULTIPOLYGON (((45 0,50 0,50 10,45 10,45 0)),"
                        + "((70 0,80 0,80 10,70 10,70 0)))")));

        for (PreparedSegmentIntersection target : targets) {
            assertThat(target.usesIndex()).isTrue();
            assertThat(target.intersectsPrepared(query)).isTrue();
        }
        assertThat(coordinates.xyReads).as("one lazy Query preparation, not one per target").isEqualTo(4);
    }

    @Test
    void freshQueryObservesLaterLineMutationButNoContractIsClaimedForMutationDuringAGroup() throws Exception {
        PreparedSegmentIntersection target = new PreparedSegmentIntersection(
                read("POLYGON ((0 0,10 0,10 10,0 10,0 0))"));
        LineString line = segment(-5, 5, 15, 5);

        assertThat(target.intersectsPrepared(new PreparedSegmentIntersection.Query(line))).isTrue();
        CoordinateSequence sequence = line.getCoordinateSequence();
        sequence.setOrdinate(0, Coordinate.Y, 20);
        sequence.setOrdinate(1, Coordinate.Y, 20);
        line.geometryChanged();

        assertThat(target.intersectsPrepared(new PreparedSegmentIntersection.Query(line))).isFalse();
    }

    @Test
    void preparedQueryRetainsLegacyFallbackForNonIndexedTargetsPolylineEmptyAndNonFiniteQueries() throws Exception {
        List<Geometry> targets = List.of(
                GF.createPoint(point(0, 0)),
                read("LINESTRING (0 0,10 10,20 0)"),
                GF.createPolygon(),
                read("POLYGON ((0 0,10 10,0 10,10 0,0 0))"));
        List<LineString> queries = List.of(
                line(point(-5, 0), point(5, 0), point(15, 0)),
                GF.createLineString(),
                segment(Double.NaN, 0, 5, 0));

        for (Geometry target : targets) {
            PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(target);
            for (LineString query : queries) {
                assertThat(predicate.intersectsPrepared(new PreparedSegmentIntersection.Query(query)))
                        .as("fallback target=%s query=%s", target.getGeometryType(), query)
                        .isEqualTo(predicate.intersects(query));
            }
        }
    }

    @Test
    void preparedQueryStillChecksCancellationForEveryIndexedConstraintCall() throws Exception {
        PreparedSegmentIntersection target = new PreparedSegmentIntersection(
                read("POLYGON ((0 0,10 0,10 10,0 10,0 0))"));
        PreparedSegmentIntersection.Query query = new PreparedSegmentIntersection.Query(segment(-5, 5, 15, 5));
        assertThat(target.intersectsPrepared(query)).isTrue();

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> target.intersectsPrepared(query)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(target.intersectsPrepared(query)).isTrue();
    }

    @Test
    void segmentAllowedKeepsAReadOnlyQueryAcrossOverlappingNonBlockingForbiddenConstraints() throws Exception {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        List<ImportedOfficialFeature> features = List.of(
                restriction("park-a", "POLYGON ((-20 -20,20 -20,20 20,-20 20,-20 -20),"
                        + "(-15 -15,-15 15,15 15,15 -15,-15 -15))"),
                restriction("park-b", "POLYGON ((-18 -18,18 -18,18 18,-18 18,-18 -18),"
                        + "(-14 -14,-14 14,14 14,14 -14,-14 -14))"));
        List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(features, 100);
        OfficialRouteGeometryRules.ConstraintIndex index = rules.index(constraints);

        assertThat(index.query(new org.locationtech.jts.geom.Envelope(-10, 10, 0, 0))).hasSize(2);
        assertThat(rules.segmentAllowed(point(-10, 0), point(10, 0), index)).isTrue();
    }

    private static List<LineString> probes(Geometry area) {
        org.locationtech.jts.geom.Envelope bounds = area.getEnvelopeInternal();
        double span = Math.max(bounds.getWidth(), bounds.getHeight());
        if (span == 0.0) span = 1.0;
        double midX = (bounds.getMinX() + bounds.getMaxX()) / 2.0;
        double midY = (bounds.getMinY() + bounds.getMaxY()) / 2.0;
        List<LineString> result = new ArrayList<>();
        for (LineString query : List.of(
                segment(bounds.getMinX() - span, midY, bounds.getMaxX() + span, midY),
                segment(midX, bounds.getMinY() - span, midX, bounds.getMaxY() + span),
                segment(bounds.getMinX() - span, bounds.getMinY(), bounds.getMaxX() + span, bounds.getMinY()),
                segment(midX, midY, midX, midY),
                segment(bounds.getMinX() - span, Math.nextUp(bounds.getMinY()),
                        bounds.getMaxX() + span, Math.nextUp(bounds.getMinY())))) {
            result.add(query);
            result.add(segment(query.getCoordinateN(1).x, query.getCoordinateN(1).y,
                    query.getCoordinateN(0).x, query.getCoordinateN(0).y));
        }
        return result;
    }

    private static ImportedOfficialFeature restriction(String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction",
                new ObjectMapper().readTree("{\"restriction_type\":\"park\"}"), read(wkt));
    }

    private static Geometry read(String wkt) throws Exception { return READER.read(wkt); }
    private static LineString segment(double x0, double y0, double x1, double y1) {
        return line(point(x0, y0), point(x1, y1));
    }
    private static LineString line(Coordinate... points) { return GF.createLineString(points); }
    private static Coordinate point(double x, double y) { return new Coordinate(x, y); }

    private static final class CountingSequence extends CoordinateArraySequence {
        private int xyReads;

        private CountingSequence(Coordinate[] coordinates) { super(coordinates); }

        @Override public double getX(int index) {
            xyReads++;
            return super.getX(index);
        }

        @Override public double getY(int index) {
            xyReads++;
            return super.getY(index);
        }
    }
}
