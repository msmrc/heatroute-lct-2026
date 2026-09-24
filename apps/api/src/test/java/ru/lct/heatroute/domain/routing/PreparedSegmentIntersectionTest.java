package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;

/** Точный JTS-oracle, владение геометрией и конкурентное чтение без тайминговых performance-assertions. */
class PreparedSegmentIntersectionTest {
    private static final GeometryFactory GF = new GeometryFactory();
    private static final long SEED = 0x56320260924L;

    @Test
    void shellTouchesTangenciesAndCollinearOverlapsCountAsIntersections() throws Exception {
        Geometry area = hole();
        check(area, true, -5, 0, 0, 0);
        check(area, true, -5, 5, 5, -5);
        check(area, true, -5, 0, 45, 0);
        check(area, true, -5, 20, 45, 20);
        check(area, true, 2, 2, 3, 3);
        check(area, false, -5, -0.001, 45, -0.001);
        check(area, false, -5, -Double.MIN_VALUE, 45, -Double.MIN_VALUE);
    }

    @Test
    void holeInteriorIsOutsideButItsBoundaryStillIntersects() throws Exception {
        Geometry area = hole();
        check(area, false, 15, 15, 25, 25);
        check(area, true, 10, 15, 10, 25);
        check(area, true, 15, 15, 10, 15);
        check(area, true, 5, 20, 35, 20);
        check(area, false, Math.nextUp(10.0), 15, Math.nextUp(10.0), 25);
        check(area, true, Math.nextDown(10.0), 15, Math.nextDown(10.0), 25);
    }

    @Test
    void zeroLengthSegmentsUseTheSameBoundaryAndInteriorSemantics() throws Exception {
        Geometry area = hole();
        check(area, true, 0, 20, 0, 20);
        check(area, true, 10, 20, 10, 20);
        check(area, true, 2, 2, 2, 2);
        check(area, false, 20, 20, 20, 20);
        check(area, false, -1, 20, -1, 20);
    }

    @Test
    void utmBoundaryDoesNotGainEvenOneUlpOfClearance() throws Exception {
        double x = 414000.123, y = 6173500.789;
        Geometry area = move(hole(), 0, x, y);
        check(area, true, x - 5, y, x + 45, y);
        check(area, false, x - 5, Math.nextDown(y), x + 45, Math.nextDown(y));
        check(area, true, x - 5, Math.nextUp(y), x + 45, Math.nextUp(y));
        check(area, true, x + 10, y + 15, x + 10, y + 25);
        check(area, false, Math.nextUp(x + 10), y + 15, Math.nextUp(x + 10), y + 25);
        check(area, true, Math.nextDown(x + 10), y + 15, Math.nextDown(x + 10), y + 25);
    }

    @Test
    void seededRectangleConcaveHoleAndBufferedQueriesMatchPreparedGeometry() throws Exception {
        for (Geometry area : List.of(square(), hole(), concave(), concave().buffer(3.125, 8))) {
            differential(area, queries(area, SEED));
        }
    }

    @Test
    void rotatedTranslatedAndThinUtmQueriesMatchPreparedGeometry() throws Exception {
        for (double angle : new double[] {0, 0.37, Math.PI / 2, 2.7}) {
            Geometry area = move(hole(), angle, 414000.123, 6173500.789);
            differential(area, queries(area, SEED));
        }
        Geometry thin = read("POLYGON ((414000 6173500,414080 6173500.001,414080 6173500.002,414000 6173500.001,414000 6173500))");
        differential(thin, queries(thin, SEED));
    }

    @Test
    void complexRingNearVertexAndAlmostCollinearQueriesMatchPreparedGeometry() {
        Geometry area = move(star(256), 0.3687, 414000.123, 6173500.789);
        differential(area, queries(area, SEED));
    }

    @Test
    void verySmallAndLargeFiniteCoordinatesMatchPreparedGeometry() throws Exception {
        for (Geometry area : List.of(AffineTransformation.scaleInstance(1e-9, 1e-9).transform(hole()),
                move(hole(), 1e-7, 1e12, 1e12))) {
            differential(area, queries(area, SEED));
        }
    }

    @Test
    void multipolygonHolesTouchingComponentsAndEmptyFirstComponentMatchOracle() throws Exception {
        Polygon holed = (Polygon) hole(), square = (Polygon) square();
        for (Geometry area : List.of(
                GF.createMultiPolygon(new Polygon[] {holed, (Polygon) move(holed, .37, 80, -10)}),
                GF.createMultiPolygon(new Polygon[] {square, (Polygon) move(square, 0, 20, 20)}),
                GF.createMultiPolygon(new Polygon[] {GF.createPolygon(), square}))) {
            differential(area, queries(area, SEED));
        }
    }

    @Test
    void duplicateRingVerticesDoNotChangeThePredicate() throws Exception {
        Geometry area = read("POLYGON ((0 0,20 0,20 0,20 20,0 20,0 0))");
        differential(area, queries(area, SEED));
    }

    @Test
    void indexCapCountsTheClosingVertexAndLargerRingRetainsExactFallback() {
        for (int coordinateCount : new int[] {16384, 16385}) {
            Polygon area = star(coordinateCount - 1);
            assertThat(area.getNumPoints()).isEqualTo(coordinateCount);
            assertThat(area.isValid()).isTrue();
            PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(area);
            assertThat(predicate.usesIndex()).isEqualTo(coordinateCount == 16384);
            PreparedGeometry oracle = PreparedGeometryFactory.prepare(area.copy());
            for (LineString query : List.of(segment(-100, 0, 100, 0), segment(0, 0, 1, 1),
                    segment(100, 100, 200, 200), line(area.getCoordinate(), area.getCoordinate()),
                    line(c(-100, 0), c(0, 0), c(100, 0)))) {
                assertThat(predicate.intersects(query)).isEqualTo(oracle.intersects(query));
            }
        }
    }

    @Test
    void indexReservationCountsAllComponentsRingsAndClosuresAtTheSameCap() throws Exception {
        for (Geometry geometry : List.of(hole(), GF.createMultiPolygon(new Polygon[] {
                GF.createPolygon(), (Polygon) hole(), (Polygon) move(square(), 0, 100, 0)}))) {
            assertThat(PreparedSegmentIntersection.additionalCoordinateReservation(geometry))
                    .isEqualTo(3L * geometry.getNumPoints());
        }
        for (int total : new int[] {16384, 16385}) {
            Geometry geometry = GF.createMultiPolygon(new Polygon[] {star(8191),
                    (Polygon) move(star(total - 8193), 0, 200, 0)});
            assertThat(geometry.getNumPoints()).isEqualTo(total);
            assertThat(PreparedSegmentIntersection.additionalCoordinateReservation(geometry))
                    .isEqualTo(total == 16384 ? 3L * total : 0);
        }
    }

    @Test
    void constraintFactoryDeclinesUnsupportedEmptyInvalidAndOversizedWithoutOwnedFallback() throws Exception {
        assertThat(PreparedSegmentIntersection.additionalCoordinateReservation(null)).isZero();
        assertThat(PreparedSegmentIntersection.forReadOnlyConstraint(null)).isNull();
        for (Geometry geometry : List.of(GF.createPolygon(), GF.createMultiPolygon(), read("POINT (20 20)"),
                read("LINESTRING (0 0,20 20)"), star(16384),
                GF.createGeometryCollection(new Geometry[] {square()}))) {
            assertThat(PreparedSegmentIntersection.additionalCoordinateReservation(geometry)).isZero();
            assertThat(PreparedSegmentIntersection.forReadOnlyConstraint(geometry)).isNull();
        }
        Geometry invalid = read("POLYGON ((0 0,20 20,0 20,20 0,0 0))");
        assertThat(PreparedSegmentIntersection.additionalCoordinateReservation(invalid)).isEqualTo(15);
        assertThat(PreparedSegmentIntersection.forReadOnlyConstraint(invalid)).isNull();
    }

    @Test
    void acceptedConstraintFactoryStillOwnsCoordinatesAndDoesNotModifySourceMetadata() throws Exception {
        Polygon source = (Polygon) hole();
        Object metadata = new Object();
        source.setUserData(metadata);
        source.getInteriorRingN(0).setUserData(metadata);
        Geometry original = source.copy();
        PreparedSegmentIntersection predicate = PreparedSegmentIntersection.forReadOnlyConstraint(source);
        assertThat(predicate).isNotNull();
        assertThat(predicate.usesIndex()).isTrue();
        assertThat(source.getUserData()).isSameAs(metadata);
        assertThat(source.getInteriorRingN(0).getUserData()).isSameAs(metadata);
        source.apply((CoordinateFilter) point -> { point.x += 10000; point.y -= 10000; });
        source.geometryChanged();
        PreparedGeometry oracle = PreparedGeometryFactory.prepare(original);
        for (LineString query : queries(original, SEED)) {
            assertThat(predicate.intersects(query)).isEqualTo(oracle.intersects(query));
        }
    }

    @Test
    void interruptedConstraintFactoryCancelsEvenForNonIndexableInputs() throws Exception {
        for (Geometry geometry : List.of(hole(), GF.createPolygon(), read("POINT (20 20)"), star(16384))) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> PreparedSegmentIntersection.forReadOnlyConstraint(geometry))
                        .isInstanceOf(CancellationException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
    }

    @Test
    void emptyTargetsAndEmptyQueriesNeverIntersect() throws Exception {
        for (Geometry area : List.of(GF.createPolygon(), GF.createMultiPolygon(), GF.createGeometryCollection())) {
            PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(area);
            assertThat(predicate.intersects(segment(0, 0, 10, 10))).isFalse();
            assertThat(predicate.intersects(segment(0, 0, 0, 0))).isFalse();
            assertThat(predicate.intersects(GF.createLineString())).isFalse();
        }
        assertThat(new PreparedSegmentIntersection(hole()).intersects(GF.createLineString())).isFalse();
    }

    @Test
    void nonPolygonAndInvalidPolygonFallbacksMatchPreparedGeometry() throws Exception {
        for (Geometry geometry : List.of(read("LINESTRING (0 0,20 20,40 0)"), read("POINT (20 20)"),
                GF.createGeometryCollection(new Geometry[] {square(), read("LINESTRING (60 0,60 60)")}),
                read("POLYGON ((0 0,20 20,0 20,20 0,0 0))"))) {
            differential(geometry, queries(geometry, SEED));
        }
    }

    @Test
    void longerQueryPolylinesUseAllSegmentsIncludingInteriorVertices() throws Exception {
        Geometry area = hole();
        List<LineString> queries = List.of(
                line(c(-5, -5), c(5, 5), c(-5, -5)),
                line(c(15, 15), c(25, 25), c(15, 25)),
                line(c(-5, -5), c(-5, 45), c(45, 45)),
                line(c(20, 20), c(5, 5), c(20, 20)),
                line(c(0, 0), c(0, 0), c(0, 0)));
        differential(area, queries);
        assertThat(new PreparedSegmentIntersection(area).intersects(queries.get(0))).isTrue();
    }

    @Test
    void sourceMutationCannotAlterOwnedPolygonOrFallbackGeometry() throws Exception {
        for (Geometry source : List.of(hole(), read("LINESTRING (0 0,20 20,40 0)"))) {
            Geometry original = source.copy();
            PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(source);
            assertThat(source.equalsExact(original)).isTrue();
            source.apply((CoordinateFilter) point -> { point.x += 10000; point.y -= 10000; });
            source.geometryChanged();
            assertThat(source.equalsExact(original)).isFalse();
            PreparedGeometry oracle = PreparedGeometryFactory.prepare(original);
            for (LineString query : queries(original, SEED)) {
                assertThat(predicate.intersects(query)).isEqualTo(oracle.intersects(query));
            }
        }
    }

    @Test
    void clearingOwnedUserDataMustNotModifyTheCallerOrItsRings() throws Exception {
        Polygon area = (Polygon) hole();
        Object polygonData = new Object(), shellData = new Object(), holeData = new Object();
        area.setUserData(polygonData);
        area.getExteriorRing().setUserData(shellData);
        area.getInteriorRingN(0).setUserData(holeData);
        new PreparedSegmentIntersection(area);
        assertThat(area.getUserData()).isSameAs(polygonData);
        assertThat(area.getExteriorRing().getUserData()).isSameAs(shellData);
        assertThat(area.getInteriorRingN(0).getUserData()).isSameAs(holeData);
    }

    @Test
    void queriesAreNotMutatedOrRetainedAsResults() throws Exception {
        PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(hole());
        LineString query = segment(-5, 20, 45, 20);
        Geometry original = query.copy();
        assertThat(predicate.intersects(query)).isTrue();
        assertThat(query.equalsExact(original)).isTrue();
        query.apply((CoordinateFilter) point -> point.y += 10000);
        query.geometryChanged();
        assertThat(predicate.intersects(query)).isFalse();
        assertThat(predicate.intersects((LineString) original)).isTrue();
    }

    @Test
    void simultaneousThreadsShareOnlyTheIndexAndUsePrecomputedOracleAnswers() throws Exception {
        Geometry area = move(star(256).buffer(1.25, 4), .37, 414000.123, 6173500.789);
        List<LineString> queries = queries(area, SEED);
        PreparedGeometry oracle = PreparedGeometryFactory.prepare(area.copy());
        boolean[] expected = new boolean[queries.size()];
        for (int i = 0; i < queries.size(); i++) expected[i] = oracle.intersects(queries.get(i));
        PreparedSegmentIntersection shared = new PreparedSegmentIntersection(area);
        int workers = 4;
        CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Integer>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                final int shift = worker;
                tasks.add(executor.submit(() -> {
                    // Даже lazy envelope самой query не разделяется между потоками. Oracle здесь не вызываем.
                    List<LineString> own = new ArrayList<>();
                    for (LineString query : queries) own.add((LineString) query.copy());
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
                    int checked = 0;
                    for (int pass = 0; pass < 4; pass++) for (int i = 0; i < own.size(); i++) {
                        int index = (i + shift * 17 + pass * 31) % own.size();
                        if (shared.intersects(own.get(index)) != expected[index]) {
                            throw new AssertionError("Concurrent result mismatch: worker=" + shift + " query=" + index);
                        }
                        checked++;
                    }
                    return checked;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> task : tasks) assertThat(task.get(10, TimeUnit.SECONDS)).isEqualTo(queries.size() * 4);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptedConstructionCancelsAndPreservesInterruptStatus() throws Exception {
        Geometry source = hole();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new PreparedSegmentIntersection(source)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void interruptionAfterConstructionEntryStillCancelsPreparation() throws Exception {
        Polygon shape = (Polygon) hole();
        // Детерминированная точка доставки interrupt, настоящая геометрия/индекс не подменяются.
        Polygon interruptAfterCopy = new Polygon(shape.getExteriorRing(), null, GF) {
            @Override protected Polygon copyInternal() {
                Polygon copy = super.copyInternal();
                Thread.currentThread().interrupt();
                return copy;
            }
        };
        try {
            assertThatThrownBy(() -> new PreparedSegmentIntersection(interruptAfterCopy))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void interruptionAfterQueryEntryIsObservedByBoundaryIndexScan() throws Exception {
        PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(hole());
        LineString interruptBeforeScan = new LineString(segment(-5, 20, 45, 20).getCoordinateSequence(), GF) {
            @Override public CoordinateSequence getCoordinateSequence() {
                Thread.currentThread().interrupt();
                return super.getCoordinateSequence();
            }
        };
        try {
            assertThatThrownBy(() -> predicate.intersects(interruptBeforeScan)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(predicate.intersects(segment(-5, 20, 45, 20))).isTrue();
    }

    @Test
    void interruptedQueryCancelsBeforeIndexedEmptyOrFallbackFastReturns() throws Exception {
        for (Geometry geometry : List.of(hole(), GF.createPolygon(), read("POINT (20 20)"))) {
            PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(geometry);
            for (LineString query : List.of(segment(100, 100, 200, 200), GF.createLineString(), segment(20, 20, 20, 20))) {
                Thread.currentThread().interrupt();
                try {
                    assertThatThrownBy(() -> predicate.intersects(query)).isInstanceOf(CancellationException.class);
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                } finally { Thread.interrupted(); }
            }
            assertThat(predicate.intersects(segment(0, 0, 40, 40)))
                    .isEqualTo(PreparedGeometryFactory.prepare(geometry).intersects(segment(0, 0, 40, 40)));
        }
    }

    private static void check(Geometry area, boolean expected, double x0, double y0, double x1, double y1) {
        PreparedGeometry oracle = PreparedGeometryFactory.prepare(area.copy());
        PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(area);
        for (LineString query : List.of(segment(x0, y0, x1, y1), segment(x1, y1, x0, y0))) {
            assertThat(oracle.intersects(query)).as("Known JTS boundary semantics: %s", query).isEqualTo(expected);
            assertThat(predicate.intersects(query)).as("Indexed boundary semantics: %s", query).isEqualTo(expected);
        }
    }

    private static void differential(Geometry area, List<LineString> queries) {
        PreparedGeometry oracle = PreparedGeometryFactory.prepare(area.copy());
        PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(area);
        PreparedSegmentIntersection constrained = PreparedSegmentIntersection.forReadOnlyConstraint(area);
        for (int i = 0; i < queries.size(); i++) {
            LineString query = queries.get(i);
            boolean expected = oracle.intersects(query), actual = predicate.intersects(query);
            if (actual != expected) throw new AssertionError("JTS mismatch at query=" + i
                    + " expected=" + expected + " actual=" + actual + " query=" + query + " target=" + area);
            if (constrained != null && constrained.intersects(query) != expected) {
                throw new AssertionError("Constraint factory JTS mismatch at query=" + i + " target=" + area);
            }
        }
    }

    /** Небольшой фиксированный набор: случайные сегменты плюс вершины, коллинеарность, ±мм и ±ULP. */
    private static List<LineString> queries(Geometry geometry, long seed) {
        Envelope bounds = geometry.getEnvelopeInternal();
        if (bounds.isNull()) bounds = new Envelope(-20, 60, -20, 60);
        double span = Math.max(1e-12, Math.max(bounds.getWidth(), bounds.getHeight()));
        double cx = (bounds.getMinX() + bounds.getMaxX()) / 2, cy = (bounds.getMinY() + bounds.getMaxY()) / 2;
        Random random = new Random(seed);
        List<LineString> result = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            Coordinate a = c(cx + (random.nextDouble() * 4 - 2) * span, cy + (random.nextDouble() * 4 - 2) * span);
            Coordinate b = c(cx + (random.nextDouble() * 4 - 2) * span, cy + (random.nextDouble() * 4 - 2) * span);
            addBoth(result, a, b);
            if (i % 10 == 0) addBoth(result, a, a);
        }
        Coordinate[] vertices = geometry.getCoordinates();
        for (int i = 0; i < vertices.length; i += Math.max(1, vertices.length / 32)) {
            Coordinate p = vertices[i];
            addBoth(result, p, p);
            addBoth(result, c(p.x - span, p.y), c(p.x + span, p.y));
            addBoth(result, c(p.x, p.y - span), c(p.x, p.y + span));
            if (i + 1 == vertices.length) continue;
            Coordinate q = vertices[i + 1];
            double dx = q.x - p.x, dy = q.y - p.y, length = Math.hypot(dx, dy);
            addBoth(result, p, q);
            addBoth(result, c(p.x - dx, p.y - dy), c(q.x + dx, q.y + dy));
            if (length == 0) continue;
            double ulp = Math.max(Math.ulp(p.x), Math.ulp(p.y));
            for (double offset : new double[] {0, 0.001, -0.001, ulp, -ulp}) {
                double ox = -dy / length * offset, oy = dx / length * offset;
                addBoth(result, c(p.x + ox, p.y + oy), c(q.x + ox, q.y + oy));
                Coordinate middle = c(p.x + dx / 2 + ox, p.y + dy / 2 + oy);
                addBoth(result, middle, middle);
            }
        }
        result.add(GF.createLineString());
        result.add(line(c(cx - span, cy), c(cx, cy + span), c(cx + span, cy)));
        return result;
    }

    private static void addBoth(List<LineString> queries, Coordinate a, Coordinate b) {
        queries.add(line(a, b)); queries.add(line(b, a));
    }

    private static Geometry square() throws Exception { return read("POLYGON ((0 0,20 0,20 20,0 20,0 0))"); }
    private static Geometry hole() throws Exception { return read("POLYGON ((0 0,40 0,40 40,0 40,0 0),(10 10,10 30,30 30,30 10,10 10))"); }
    private static Geometry concave() throws Exception { return read("POLYGON ((0 0,40 0,40 10,10 10,10 30,40 30,40 40,0 40,0 0))"); }
    private static Geometry read(String text) throws Exception { return new WKTReader(GF).read(text); }

    private static Polygon star(int count) {
        Coordinate[] points = new Coordinate[count + 1];
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count, radius = 60 * (1 + .25 * Math.sin(11 * angle));
            points[i] = c(radius * Math.cos(angle), radius * Math.sin(angle));
        }
        points[count] = new Coordinate(points[0]);
        return GF.createPolygon(points);
    }

    private static Geometry move(Geometry geometry, double angle, double x, double y) {
        return AffineTransformation.translationInstance(x, y).transform(AffineTransformation.rotationInstance(angle).transform(geometry));
    }

    private static LineString segment(double x0, double y0, double x1, double y1) { return line(c(x0, y0), c(x1, y1)); }
    private static LineString line(Coordinate... points) {
        Coordinate[] copies = new Coordinate[points.length];
        for (int i = 0; i < points.length; i++) copies[i] = new Coordinate(points[i]);
        return GF.createLineString(copies);
    }
    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
}
