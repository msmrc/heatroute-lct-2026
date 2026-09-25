package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
import org.locationtech.jts.geom.CoordinateSequenceFactory;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatroute.domain.constraints.PreparedRoadCrossings;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance.Interval;

/** Сверяет интервалы поиска с независимым JTS-overlay; проверяет владение, отказы и отмену. */
class PreparedRoadCrossingsTest {
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 32637);
    private static final double EPSILON_M = 1e-6;
    // Ошибка проекции на координатах UTM; существенно меньше допуска объединения интервалов.
    private static final double COMPARISON_M = 1e-8;
    private static final long SEED = 0x74_20260925L;

    @Test
    void shellInteriorAndClippedEndpointsHaveKnownDistancesInBothDirections() {
        Geometry source = rectangle(0, 0, 40, 40);
        known(source, segment(-5, 20, 45, 20), 5, 45);
        known(source, segment(2, 20, 8, 20), 0, 6);
        known(source, segment(0, 20, 5, 20), 0, 5);
        known(source, segment(20, 20, 45, 20), 0, 20);
        known(source, segment(45, 20, 20, 20), 5, 25);
        known(source, segment(-5, 20, 0, 20));
        assertThat(prepared(source).crossings(segment(-5, 20, 45, 20))).isPresent();
        assertThat(prepared(source).crossings(segment(2, 20, 8, 20))).isPresent();
        assertThat(prepared(source).crossings(segment(-5, -5, 45, -5))).hasValue(List.of());
    }

    @Test
    void tangenciesAndShellBoundaryOverlapsAreNotInteriorCrossings() {
        Geometry source = rectangle(0, 0, 40, 40);
        for (LineString query : List.of(segment(-5, 5, 5, -5), segment(-5, 0, 45, 0),
                segment(0, 5, 0, 35), segment(-5, 40, 45, 40),
                segment(-5, -0.0001, 45, -0.0001))) {
            known(source, query);
        }
        known(source, segment(-5, 0.0001, 45, 0.0001), 5, 45);
    }

    @Test
    void holesSplitTheCrossingAndHoleBoundaryPiecesAreExcluded() throws Exception {
        Geometry source = hole();
        known(source, segment(-5, 20, 45, 20), 5, 15, 35, 45);
        known(source, segment(-5, 10, 45, 10), 5, 15, 35, 45);
        known(source, segment(15, 20, 25, 20));
        known(source, segment(10, 15, 10, 25));
        known(source, segment(5, 20, 20, 20), 0, 5);
        known(source, segment(20, 20, 35, 20), 10, 15);
    }

    @Test
    void concaveRingHasSeparateEntriesAndCollinearDuplicateVerticesDoNotAddCrossings() throws Exception {
        known(concave(), segment(20, -5, 20, 45), 5, 15, 35, 45);
        known(concave(), segment(-5, 20, 45, 20), 5, 15);
        Geometry collinear = read("POLYGON ((0 0,10 0,20 0,20 0,40 0,40 20,40 40,0 40,0 20,0 0))");
        known(collinear, segment(-5, 20, 45, 20), 5, 45);
        known(collinear, segment(-5, 0, 45, 0));
        differential(collinear, generatedSegments(collinear, SEED));
    }

    @Test
    void multipartOrderingEmptyComponentsAndPointTouchingComponentsMatchOverlay() throws Exception {
        Polygon first = rectangle(0, 0, 20, 20);
        Geometry disjoint = GF.createMultiPolygon(new Polygon[] {
                rectangle(40, 0, 60, 20), GF.createPolygon(), first});
        known(disjoint, segment(-5, 10, 65, 10), 5, 25, 45, 65);
        Geometry touching = GF.createMultiPolygon(new Polygon[] {first, rectangle(20, 20, 40, 40)});
        LineString diagonal = segment(-10, -10, 50, 50);
        assertMatches(prepared(touching), touching, diagonal);
        assertThat(oracle(touching, diagonal)).hasSize(1);
        differential(disjoint, generatedSegments(disjoint, SEED));
        differential(touching, generatedSegments(touching, SEED));
    }

    @Test
    void submillimetreGapsMergeOnlyWithinOneMicrometre() {
        for (double gapM : new double[] {0.5e-6, 1e-6, 2e-6, 0.0002, 0.0009}) {
            Geometry source = GF.createMultiPolygon(new Polygon[] {
                    rectangle(0, 0, 10, 10), rectangle(10 + gapM, 0, 20, 10)});
            LineString query = segment(-5, 5, 25, 5);
            List<Range> expected = oracle(source, query);
            assertThat(expected).as("gap=%s", gapM).hasSize(gapM <= EPSILON_M ? 1 : 2);
            differential(source, List.of(query, (LineString) query.reverse()));
        }
    }

    @Test
    void tinyInteriorPiecesUseTheSameLengthCutoffAsOverlay() {
        for (double widthM : new double[] {0.5e-6, 1e-6, 2e-6, 0.0001}) {
            Geometry source = rectangle(0, 0, widthM, 1);
            LineString query = segment(-1, 0.5, 1, 0.5);
            assertThat(oracle(source, query)).hasSize(widthM <= EPSILON_M ? 0 : 1);
            differential(source, List.of(query, (LineString) query.reverse()));
        }
    }

    @Test
    void utmBoundaryOffsetsDoNotCreateOrEraseInterior() {
        double x = 414000.123, y = 6173500.789;
        Geometry source = rectangle(x, y, x + 40, y + 40);
        known(source, segment(x - 5, y, x + 45, y));
        known(source, segment(x - 5, Math.nextDown(y), x + 45, Math.nextDown(y)));
        known(source, segment(x - 5, Math.nextUp(y), x + 45, Math.nextUp(y)), 5, 45);
        known(source, segment(x - 5, y + 0.0001, x + 45, y + 0.0001), 5, 45);
    }

    @Test
    void seededSegmentsRotationsAndDirectionsMatchIndependentJtsIntervals() throws Exception {
        List<Geometry> shapes = List.of(rectangle(0, 0, 40, 40), hole(), concave(),
                concave().buffer(2.125, 8), radialPolygon(96, 0));
        for (Geometry shape : shapes) {
            for (double angle : new double[] {0, 0.37, Math.PI / 2, 2.7}) {
                Geometry source = move(shape, angle, 414000.123, 6173500.789);
                differential(source, generatedSegments(source, SEED));
            }
        }
        Geometry thin = read("POLYGON ((414000 6173500,414080 6173500.001,"
                + "414080 6173500.002,414000 6173500.001,414000 6173500))");
        differential(thin, generatedSegments(thin, SEED));
    }

    @Test
    void unsupportedAndEmptySourceTypesDeclineWithoutAnOwnedFallback() {
        for (Geometry source : Arrays.asList(null, GF.createPolygon(), GF.createMultiPolygon(),
                GF.createPoint(c(1, 1)), segment(0, 0, 1, 1),
                GF.createGeometryCollection(new Geometry[] {rectangle(0, 0, 10, 10)}))) {
            assertThat(PreparedRoadCrossings.additionalCoordinateReservation(source)).isZero();
            assertThat(PreparedRoadCrossings.forReadOnlyConstraint(source)).isNull();
        }
    }

    @Test
    void invalidAndNonfinitePolygonsDecline() throws Exception {
        for (Geometry source : List.of(
                read("POLYGON ((0 0,20 20,0 20,20 0,0 0))"),
                read("POLYGON ((0 0,20 0,20 20,0 20,0 0),(30 30,30 35,35 35,35 30,30 30))"),
                GF.createMultiPolygon(new Polygon[] {rectangle(0, 0, 20, 20), rectangle(10, 10, 30, 30)}))) {
            assertThat(source.isValid()).isFalse();
            assertThat(PreparedRoadCrossings.additionalCoordinateReservation(source)).isEqualTo(3L * source.getNumPoints());
            assertThat(PreparedRoadCrossings.forReadOnlyConstraint(source)).isNull();
        }
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (int ordinate : new int[] {0, 1}) {
                Polygon source = rectangle(0, 0, 20, 20);
                source.getExteriorRing().getCoordinateSequence().setOrdinate(1, ordinate, value);
                source.geometryChanged();
                assertThat(PreparedRoadCrossings.forReadOnlyConstraint(source)).isNull();
            }
        }
    }

    @Test
    void customGeometryFactoryRingsAndSequencesAndFixedPrecisionDecline() throws Exception {
        Polygon ordinary = rectangle(0, 0, 20, 20);
        GeometryFactory customFactory = new GeometryFactory() {};
        GeometryFactory fixedFactory = new GeometryFactory(new PrecisionModel(1000), 32637);
        GeometryFactory singleFactory = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING_SINGLE), 32637);
        LinearRing customRing = new LinearRing(ordinary.getExteriorRing().getCoordinateSequence(), GF) {};
        CoordinateArraySequence customSequence = new CoordinateArraySequence(ordinary.getCoordinates()) {};
        for (Geometry source : List.of(
                new Polygon(ordinary.getExteriorRing(), null, GF) {},
                new MultiPolygon(new Polygon[] {ordinary}, GF) {},
                customFactory.createPolygon(ordinary.getCoordinates()),
                fixedFactory.createPolygon(ordinary.getCoordinates()),
                singleFactory.createPolygon(ordinary.getCoordinates()),
                GF.createPolygon(customRing),
                GF.createPolygon(GF.createLinearRing(customSequence)),
                GF.createMultiPolygon(new Polygon[] {new Polygon(ordinary.getExteriorRing(), null, GF) {}}))) {
            assertThat(PreparedRoadCrossings.additionalCoordinateReservation(source)).isZero();
            assertThat(PreparedRoadCrossings.forReadOnlyConstraint(source)).as("%s", source.getClass()).isNull();
        }
        Polygon holed = (Polygon) hole();
        LinearRing customHole = new LinearRing(holed.getInteriorRingN(0).getCoordinateSequence(), GF) {};
        assertThat(PreparedRoadCrossings.forReadOnlyConstraint(
                GF.createPolygon(holed.getExteriorRing(), new LinearRing[] {customHole}))).isNull();
    }

    @Test
    void coordinateCapIncludesClosuresHolesAndAllComponents() {
        for (int total : new int[] {16384, 16385}) {
            Polygon ring = radialPolygon(total - 1, 0);
            Geometry multipart = GF.createMultiPolygon(new Polygon[] {
                    radialPolygon(8191, 0), radialPolygon(total - 8193, 200)});
            Polygon holed = GF.createPolygon(radialPolygon(total - 6, 0).getExteriorRing(),
                    new LinearRing[] {rectangle(-2, -2, 2, 2).getExteriorRing()});
            for (Geometry source : List.of(ring, multipart, holed)) {
                assertThat(source.getNumPoints()).isEqualTo(total);
                assertThat(source.isValid()).isTrue();
                if (total == 16384) {
                    assertThat(PreparedRoadCrossings.additionalCoordinateReservation(source)).isEqualTo(3L * total);
                    PreparedRoadCrossings indexed = prepared(source);
                    assertMatches(indexed, source, segment(-100, 1, 300, 1));
                } else {
                    assertThat(PreparedRoadCrossings.additionalCoordinateReservation(source)).isZero();
                    assertThat(PreparedRoadCrossings.forReadOnlyConstraint(source)).isNull();
                }
            }
        }
    }

    @Test
    void sourceMutationDoesNotChangeOwnedShellHolesOrComponentsAndPreservesMetadata() throws Exception {
        Polygon holed = (Polygon) hole();
        Geometry source = GF.createMultiPolygon(new Polygon[] {holed, rectangle(80, 0, 100, 20)});
        Object metadata = new Object();
        source.setUserData(metadata);
        holed.getExteriorRing().setUserData(metadata);
        holed.getInteriorRingN(0).setUserData(metadata);
        Geometry original = source.copy();
        PreparedRoadCrossings indexed = prepared(source);
        assertThat(source.equalsExact(original)).isTrue();
        assertThat(source.getUserData()).isSameAs(metadata);
        assertThat(holed.getExteriorRing().getUserData()).isSameAs(metadata);
        assertThat(holed.getInteriorRingN(0).getUserData()).isSameAs(metadata);
        source.apply((CoordinateFilter) point -> { point.x += 10000; point.y -= 10000; });
        source.geometryChanged();
        assertThat(source.equalsExact(original)).isFalse();
        for (LineString query : generatedSegments(original, SEED)) {
            assertMatches(indexed, original, query);
            assertGuardMatches(indexed, original, query, 1.755, 70, 3);
        }
        assertThat(reachableObjects(indexed).contains(metadata)).isFalse();
    }

    @Test
    void queriesAreNotMutatedRetainedOrUsedAsMutableResultStorage() throws Exception {
        Geometry source = hole();
        PreparedRoadCrossings indexed = prepared(source);
        List<Object> queryObjects = new ArrayList<>();
        for (LineString query : generatedSegments(source, SEED).subList(0, 64)) {
            Object metadata = new Object();
            query.setUserData(metadata);
            LineString original = (LineString) query.copy();
            List<Range> expected = oracle(source, original);
            List<Interval> result = indexed.crossings(query).orElseThrow();
            assertIntervals(result, expected, query.toText());
            assertThat(query.equalsExact(original)).isTrue();
            assertThat(query.getUserData()).isSameAs(metadata);
            queryObjects.add(query);
            queryObjects.add(query.getCoordinateSequence());
            queryObjects.add(query.getCoordinate());
            queryObjects.add(metadata);
            query.apply((CoordinateFilter) point -> point.y += 10000);
            query.geometryChanged();
            assertThat(indexed.crossings(query).orElseThrow()).isEmpty();
            assertIntervals(result, expected, "saved result after query mutation");
            assertMatches(indexed, source, original);
        }
        Set<Object> retained = reachableObjects(indexed);
        for (Object queryObject : queryObjects) assertThat(retained.contains(queryObject)).isFalse();
    }

    @Test
    void cancellationBeforePreparationAndQueryPreservesInterruptAndAllowsRetry() throws Exception {
        for (Geometry source : Arrays.asList(null, hole(), GF.createPolygon(),
                segment(0, 0, 1, 1), radialPolygon(16384, 0))) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> PreparedRoadCrossings.forReadOnlyConstraint(source))
                        .isInstanceOf(CancellationException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
        Geometry source = hole();
        PreparedRoadCrossings indexed = prepared(source);
        for (LineString query : Arrays.asList(null, GF.createLineString(), segment(-5, 20, 45, 20),
                segment(100, 100, 200, 200), segment(0, 0, 0, 0))) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> indexed.crossings(query)).isInstanceOf(CancellationException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
        assertMatches(indexed, source, segment(-5, 20, 45, 20));
    }

    @Test
    void concurrentReadersMatchPrecomputedOracleWithoutSharingMutableQueries() throws Exception {
        Geometry source = move(hole(), 0.37, 414000.123, 6173500.789);
        List<LineString> queries = generatedSegments(source, SEED).subList(0, 160);
        List<List<Range>> expected = new ArrayList<>();
        for (LineString query : queries) expected.add(oracle(source, query));
        PreparedRoadCrossings indexed = prepared(source);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4), start = new CountDownLatch(1);
        List<Future<Integer>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                final int shift = worker;
                tasks.add(executor.submit(() -> {
                    List<LineString> ownQueries = new ArrayList<>();
                    for (LineString query : queries) ownQueries.add((LineString) query.copy());
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
                    for (int pass = 0; pass < 3; pass++) {
                        for (int i = 0; i < ownQueries.size(); i++) {
                            int index = (i + 17 * shift + 31 * pass) % ownQueries.size();
                            assertIntervals(indexed.crossings(ownQueries.get(index)).orElseThrow(),
                                    expected.get(index), "concurrent query=" + index);
                        }
                    }
                    return ownQueries.size() * 3;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> task : tasks) assertThat(task.get(20, TimeUnit.SECONDS)).isEqualTo(queries.size() * 3);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }


    @Test
    void unsupportedQueriesDeclineWhileValidNoncrossingQueriesReturnAnEmptyList() throws Exception {
        PreparedRoadCrossings indexed = prepared(hole());
        LineString ordinary = segment(-5, 20, 45, 20);
        GeometryFactory customFactory = new GeometryFactory() {};
        GeometryFactory fixedFactory = new GeometryFactory(new PrecisionModel(1000), 32637);
        GeometryFactory singleFactory = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING_SINGLE), 32637);
        for (LineString query : Arrays.asList(null, GF.createLineString(), segment(0, 0, 0, 0),
                segment(0, 0, 0.5e-6, 0), segment(0, 0, 1e-6, 0),
                GF.createLineString(new Coordinate[] {c(-5, 20), c(20, 20), c(45, 20)}),
                GF.createLineString(new Coordinate[] {c(-5, 20), c(20, 25), c(45, 20)}),
                new LineString(ordinary.getCoordinateSequence(), GF) {},
                customFactory.createLineString(ordinary.getCoordinates()),
                fixedFactory.createLineString(ordinary.getCoordinates()),
                singleFactory.createLineString(ordinary.getCoordinates()),
                GF.createLineString(new CoordinateArraySequence(ordinary.getCoordinates()) {}))) {
            assertThat(indexed.crossings(query)).as("unsupported query").isEmpty();
        }
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (int endpoint : new int[] {0, 1}) for (int ordinate : new int[] {0, 1}) {
                LineString query = segment(-5, 20, 45, 20);
                query.getCoordinateSequence().setOrdinate(endpoint, ordinate, value);
                query.geometryChanged();
                assertThat(indexed.crossings(query)).isEmpty();
            }
        }
        assertThat(indexed.crossings(segment(-50, -50, -20, -20))).hasValue(List.of());
        assertThat(indexed.crossings(segment(15, 15, 25, 25))).hasValue(List.of());
        assertThat(indexed.crossings(segment(2, 2, 2 + 2e-6, 2))).isPresent();
    }

    @Test
    void collinearOverlapsExplicitlyUseFallbackIncludingHoleBoundaries() throws Exception {
        Geometry source = hole();
        PreparedRoadCrossings indexed = prepared(source);
        for (LineString query : List.of(segment(-5, 0, 45, 0), segment(0, 5, 0, 35),
                segment(-5, 10, 45, 10), segment(10, 15, 10, 25))) {
            assertThat(indexed.crossings(query)).isEmpty();
            assertGuardMatches(indexed, source, query, 1.755, 70, 3);
        }
    }

    @Test
    void unrepresentableMidpointUsesFallbackAndDoesNotPublishAnApproximateInterval() {
        double x = Math.scalb(1.0, 52);
        Geometry source = rectangle(x, 0, x + 4, 4);
        LineString query = segment(x + 1, 2, x + 2, 2);
        PreparedRoadCrossings indexed = prepared(source);
        assertThat(indexed.crossings(query)).isEmpty();
        assertMatches(indexed, source, query);
        assertGuardMatches(indexed, source, query, 1.755, 70, 0);
    }

    @Test
    void packedDoubleSequencesAreSupportedButCustomSequenceFactoriesAndPrecisionModelsDecline() {
        Coordinate[] points = rectangle(0, 0, 40, 40).getCoordinates();
        for (int type : new int[] {PackedCoordinateSequenceFactory.DOUBLE}) {
            GeometryFactory factory = new GeometryFactory(new PrecisionModel(), 32637,
                    new PackedCoordinateSequenceFactory(type));
            Polygon source = factory.createPolygon(points);
            LineString query = factory.createLineString(segment(-5, 20, 45, 20).getCoordinates());
            PreparedRoadCrossings indexed = prepared(source);
            assertThat(PreparedRoadCrossings.additionalCoordinateReservation(source)).isEqualTo(20);
            assertThat(indexed.crossings(query)).isPresent();
            assertMatches(indexed, source, query);
        }
        CoordinateSequenceFactory customSequences = new CoordinateSequenceFactory() {
            @Override public CoordinateSequence create(Coordinate[] coordinates) {
                return new CoordinateArraySequence(coordinates);
            }
            @Override public CoordinateSequence create(CoordinateSequence coordinates) {
                return new CoordinateArraySequence(coordinates);
            }
            @Override public CoordinateSequence create(int size, int dimension) {
                return new CoordinateArraySequence(size, dimension);
            }
        };
        for (GeometryFactory factory : List.of(new GeometryFactory(customSequences),
                new GeometryFactory(new PrecisionModel() {}, 32637))) {
            Polygon source = factory.createPolygon(points);
            assertThat(PreparedRoadCrossings.additionalCoordinateReservation(source)).isZero();
            assertThat(PreparedRoadCrossings.forReadOnlyConstraint(source)).isNull();
            assertThat(prepared(rectangle(0, 0, 40, 40)).crossings(
                    factory.createLineString(segment(-5, 20, 45, 20).getCoordinates()))).isEmpty();
        }
    }

    @Test
    void packedStorageReservesTheLocatorsAdditionalCoordinateObjects() {
        GeometryFactory packed = new GeometryFactory(new PrecisionModel(), 32637,
                PackedCoordinateSequenceFactory.DOUBLE_FACTORY);
        Polygon shell = packed.createPolygon(rectangle(0, 0, 40, 40).getCoordinates());
        assertThat(PreparedRoadCrossings.additionalCoordinateReservation(shell)).isEqualTo(20);
        LinearRing hole = GF.createLinearRing(new PackedCoordinateSequence.Double(
                rectangle(10, 10, 30, 30).getCoordinates(), 2));
        Polygon mixed = GF.createPolygon(rectangle(0, 0, 40, 40).getExteriorRing(), new LinearRing[] {hole});
        assertThat(PreparedRoadCrossings.additionalCoordinateReservation(mixed)).isEqualTo(35);
        assertMatches(prepared(mixed), mixed, segment(-5, 20, 45, 20));
    }

    @Test
    void packedFloatSourceDoesNotGainACrossingExemptionWithADoubleQuery() {
        GeometryFactory floats = new GeometryFactory(new PrecisionModel(), 32637,
                PackedCoordinateSequenceFactory.FLOAT_FACTORY);
        Geometry source = floats.createPolygon(rectangle(0, 0, 10, 100).getCoordinates());
        LineString query = segment(-5, 99.999998, 15, 99.999998);
        RoadCrossingClearance baseline = new RoadCrossingClearance();
        PreparedRoadCrossings indexed = PreparedRoadCrossings.forReadOnlyConstraint(source);
        assertThat(baseline.segmentAllowed(query, source, 1.755, 45, 3)).isFalse();
        boolean actual = indexed == null ? baseline.segmentAllowed(query, source, 1.755, 45, 3)
                : indexed.segmentAllowed(query, 1.755, 45, 3);
        assertThat(actual).isFalse();
        assertThat(indexed).isNull();
        assertThat(PreparedRoadCrossings.additionalCoordinateReservation(source)).isZero();
    }

    @Test
    void packedFloatFactoriesAndActualSequencesDeclineEvenWhenMixedWithDoubleStorage() {
        GeometryFactory floats = new GeometryFactory(new PrecisionModel(), 32637,
                PackedCoordinateSequenceFactory.FLOAT_FACTORY);
        Coordinate[] points = rectangle(0, 0, 10, 100).getCoordinates();
        for (Polygon source : List.of(
                floats.createPolygon(floats.createLinearRing(new CoordinateArraySequence(points))),
                GF.createPolygon(GF.createLinearRing(new PackedCoordinateSequence.Float(points, 2))))) {
            assertThat(PreparedRoadCrossings.additionalCoordinateReservation(source)).isZero();
            assertThat(PreparedRoadCrossings.forReadOnlyConstraint(source)).isNull();
        }
        Geometry source = rectangle(0, 0, 10, 100);
        PreparedRoadCrossings indexed = prepared(source);
        Coordinate[] segment = segment(-5, 99.999998, 15, 99.999998).getCoordinates();
        for (LineString query : List.of(floats.createLineString(new CoordinateArraySequence(segment)),
                GF.createLineString(new PackedCoordinateSequence.Float(segment, 2)),
                floats.createLineString(segment))) {
            assertThat(indexed.crossings(query)).isEmpty();
            assertGuardMatches(indexed, source, query, 1.755, 45, 3);
        }
    }

    @Test
    void extendedUtmBoundarySegmentDoesNotAcquireAnInteriorCrossingExemption() {
        Geometry source = move(rectangle(0, 0, 40, 40), 0.37, 414000.123, 6173500.789);
        LineString query = segment(414000.123, 6173500.789, 414037.41609382426, 6173515.253617278);
        PreparedRoadCrossings indexed = prepared(source);
        Coordinate a = query.getCoordinateN(0), b = query.getCoordinateN(1);
        double length = a.distance(b), dx = (b.x - a.x) / length, dy = (b.y - a.y) / length;
        LineString extended = segment(a.x - 3 * dx, a.y - 3 * dy, b.x + 3 * dx, b.y + 3 * dy);
        assertMatches(indexed, source, extended);
        assertThat(new RoadCrossingClearance().segmentAllowed(query, source, 1.755, 70, 3)).isFalse();
        assertThat(indexed.segmentAllowed(query, 1.755, 70, 3)).isFalse();
    }

    @Test
    void publicPreparedGuardPreservesKnownAllowedAndRejectedDecisions() {
        Geometry source = rectangle(0, 0, 40, 40);
        PreparedRoadCrossings indexed = prepared(source);
        for (LineString query : List.of(segment(-10, 20, 50, 20), segment(-2, 20, 2, 20),
                segment(-5, -2, 45, -2), segment(20, 20, 45, 20))) {
            assertThat(new RoadCrossingClearance().segmentAllowed(query, source, 1.755, 70, 3)).isTrue();
            assertThat(indexed.segmentAllowed(query, 1.755, 70, 3)).isTrue();
        }
        for (LineString query : List.of(segment(-5, -1.5, 45, -1.5), segment(-5, 0, 45, 0),
                segment(-10, -10, 50, 50), segment(-1, 1, 1, -1))) {
            assertThat(new RoadCrossingClearance().segmentAllowed(query, source, 1.755, 70, 3)).isFalse();
            assertThat(indexed.segmentAllowed(query, 1.755, 70, 3)).isFalse();
        }
    }

    @Test
    void publicPreparedGuardMatchesJtsAtAngleClearanceAndExtensionBoundaries() throws Exception {
        Geometry source = move(hole(), 0.37, 414000.123, 6173500.789);
        PreparedRoadCrossings indexed = prepared(source);
        for (LineString query : generatedSegments(source, SEED)) {
            for (double[] parameters : new double[][] {{1.755, 70, 3}, {5, 90, 0}, {0.25, 30, 1.5}}) {
                assertGuardMatches(indexed, source, query, parameters[0], parameters[1], parameters[2]);
            }
        }
        Geometry rectangle = rectangle(0, 0, 40, 40);
        PreparedRoadCrossings axisAligned = prepared(rectangle);
        for (double distance : new double[] {1.755 - 2e-6, 1.755 - 0.5e-6, 1.755, 1.755 + 2e-6}) {
            assertGuardMatches(axisAligned, rectangle, segment(-5, -distance, 45, -distance), 1.755, 70, 3);
        }
        for (double angle : new double[] {70 - 2e-7, 70 - 0.5e-7, 70, 70 + 2e-7}) {
            double dx = Math.sin(Math.toRadians(angle)), dy = Math.cos(Math.toRadians(angle));
            LineString query = segment(-10 * dx, 5 - 10 * dy, 50 * dx, 5 + 50 * dy);
            assertGuardMatches(axisAligned, rectangle, query, 1.755, 70, 3);
        }
        for (double start : new double[] {-3 - 2e-6, -3, -3 + 2e-6, 0, 3}) {
            assertGuardMatches(axisAligned, rectangle, segment(start, 20, 45, 20), 1.755, 70, 3);
        }
    }

    @Test
    void nearlyParallelBoundaryTablePreservesStrictDecisionsAcrossUtmRotationsAndDirections() throws Exception {
        for (Geometry shape : List.of(rectangle(0, 0, 40, 40), hole())) {
            for (double rotation : new double[] {0, 0.37, Math.PI / 2, 2.7}) {
                Geometry source = move(shape, rotation, 414000.123, 6173500.789);
                PreparedRoadCrossings indexed = prepared(source);
                double ulp = Math.ulp(6173500.789);
                for (double offset : new double[] {0, ulp, -ulp, 0.5e-6, -0.5e-6,
                        2e-6, -2e-6, 0.0001, -0.0001}) {
                    for (double slope : new double[] {0, 0.5e-10, -0.5e-10, 2e-10, -2e-10}) {
                        LineString query = (LineString) move(segment(0, offset, 40, offset + 40 * slope),
                                rotation, 414000.123, 6173500.789);
                        for (LineString direction : List.of(query, (LineString) query.reverse())) {
                            assertMatches(indexed, source, direction);
                            assertGuardMatches(indexed, source, direction, 1.755, 70, 3);
                        }
                    }
                }
            }
        }
    }

    private static void assertGuardMatches(PreparedRoadCrossings indexed, Geometry source, LineString query,
            double clearanceM, double angleDegrees, double extensionM) {
        boolean expected = new RoadCrossingClearance().segmentAllowed(query, source, clearanceM, angleDegrees, extensionM);
        assertThat(indexed.segmentAllowed(query, clearanceM, angleDegrees, extensionM))
                .as("guard: %s clearance=%s angle=%s extension=%s", query, clearanceM, angleDegrees, extensionM)
                .isEqualTo(expected);
    }

    private static PreparedRoadCrossings prepared(Geometry source) {
        assertThat(source.isValid()).as("valid test fixture").isTrue();
        PreparedRoadCrossings indexed = PreparedRoadCrossings.forReadOnlyConstraint(source);
        assertThat(indexed).as("supported test fixture: %s", source.getGeometryType()).isNotNull();
        return indexed;
    }

    private static void known(Geometry source, LineString query, double... endpoints) {
        List<Range> expected = new ArrayList<>();
        for (int i = 0; i < endpoints.length; i += 2) expected.add(new Range(endpoints[i], endpoints[i + 1]));
        PreparedRoadCrossings indexed = prepared(source);
        assertRanges(oracle(source, query), expected, "known JTS distances: " + query);
        assertRanges(crossingsWithFallback(indexed, source, query), expected, query.toText());
        differential(source, List.of(query, (LineString) query.reverse()));
    }

    private static void differential(Geometry source, List<LineString> queries) {
        PreparedRoadCrossings indexed = prepared(source);
        for (int i = 0; i < queries.size(); i++) {
            LineString query = queries.get(i);
            List<Range> expected = oracle(source, query);
            assertRanges(crossingsWithFallback(indexed, source, query), expected,
                    "query=" + i + " seed=" + SEED + " line=" + query + " source=" + source);
        }
    }

    private static void assertMatches(PreparedRoadCrossings indexed, Geometry source, LineString query) {
        List<Range> expected = oracle(source, query);
        assertRanges(crossingsWithFallback(indexed, source, query), expected, query.toText());
    }

    private static List<Range> crossingsWithFallback(PreparedRoadCrossings indexed, Geometry source, LineString query) {
        Optional<List<Interval>> fast = indexed.crossings(query);
        return fast.map(PreparedRoadCrossingsTest::numericRanges).orElseGet(() -> oracle(source, query));
    }

    private static List<Range> numericRanges(List<Interval> intervals) {
        List<Range> result = new ArrayList<>();
        for (Interval interval : intervals) {
            assertThat(interval.getAngleDegrees()).isZero();
            result.add(new Range(interval.getStartM(), interval.getEndM()));
        }
        return result;
    }

    private static void assertIntervals(List<Interval> actual, List<Range> expected, String context) {
        assertRanges(numericRanges(actual), expected, context);
    }

    private static void assertRanges(List<Range> actual, List<Range> expected, String context) {
        assertThat(actual).as(context).hasSize(expected.size());
        double previousEnd = -Double.MAX_VALUE;
        for (int i = 0; i < actual.size(); i++) {
            Range interval = actual.get(i);
            assertThat(interval.startM).as(context + " start[" + i + "]")
                    .isCloseTo(expected.get(i).startM, within(COMPARISON_M));
            assertThat(interval.endM).as(context + " end[" + i + "]")
                    .isCloseTo(expected.get(i).endM, within(COMPARISON_M));
            assertThat(interval.startM).as(context).isGreaterThanOrEqualTo(0);
            assertThat(interval.endM).as(context).isGreaterThan(interval.startM);
            assertThat(interval.startM).as(context).isGreaterThan(previousEnd + EPSILON_M);
            previousEnd = interval.endM;
        }
    }

    /** Oracle намеренно использует JTS overlay, contains и линейную проекцию, а не индекс границ. */
    private static List<Range> oracle(Geometry source, LineString query) {
        List<Range> pieces = new ArrayList<>();
        collectInteriorPieces(query.intersection(source), source, new LengthIndexedLine(query), pieces);
        pieces.sort(Comparator.comparingDouble(range -> range.startM));
        List<Range> merged = new ArrayList<>();
        for (Range piece : pieces) {
            if (merged.isEmpty() || piece.startM > merged.get(merged.size() - 1).endM + EPSILON_M) {
                merged.add(piece);
            } else {
                Range previous = merged.remove(merged.size() - 1);
                merged.add(new Range(previous.startM, Math.max(previous.endM, piece.endM)));
            }
        }
        return merged;
    }

    private static void collectInteriorPieces(Geometry intersection, Geometry source,
            LengthIndexedLine indexed, List<Range> result) {
        if (intersection instanceof LineString) {
            LineString part = (LineString) intersection;
            for (int i = 1; i < part.getNumPoints(); i++) {
                Coordinate first = part.getCoordinateN(i - 1), last = part.getCoordinateN(i);
                if (first.distance(last) <= EPSILON_M) continue;
                Coordinate middle = c((first.x + last.x) / 2, (first.y + last.y) / 2);
                if (!source.contains(source.getFactory().createPoint(middle))) continue;
                double a = indexed.project(first), b = indexed.project(last);
                result.add(new Range(Math.min(a, b), Math.max(a, b)));
            }
        } else if (intersection instanceof GeometryCollection) {
            for (int i = 0; i < intersection.getNumGeometries(); i++) {
                collectInteriorPieces(intersection.getGeometryN(i), source, indexed, result);
            }
        }
    }

    /** Фиксированный seed, оба направления, проходы через вершины и смещения границ ±ULP/±0,1 мм. */
    private static List<LineString> generatedSegments(Geometry source, long seed) {
        Envelope bounds = source.getEnvelopeInternal();
        double span = Math.max(bounds.getWidth(), bounds.getHeight());
        double cx = (bounds.getMinX() + bounds.getMaxX()) / 2;
        double cy = (bounds.getMinY() + bounds.getMaxY()) / 2;
        Random random = new Random(seed);
        List<LineString> queries = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            addBoth(queries, c(cx + (random.nextDouble() * 4 - 2) * span,
                    cy + (random.nextDouble() * 4 - 2) * span),
                    c(cx + (random.nextDouble() * 4 - 2) * span,
                            cy + (random.nextDouble() * 4 - 2) * span));
        }
        Geometry boundary = source.getBoundary();
        for (int part = 0; part < boundary.getNumGeometries(); part++) {
            Coordinate[] vertices = boundary.getGeometryN(part).getCoordinates();
            int stride = Math.max(1, (vertices.length - 1) / 24);
            for (int i = 1; i < vertices.length; i += stride) {
                Coordinate a = vertices[i - 1], b = vertices[i];
                double dx = b.x - a.x, dy = b.y - a.y, length = a.distance(b);
                if (length <= EPSILON_M) continue;
                addBoth(queries, c(a.x - span, a.y), c(a.x + span, a.y));
                addBoth(queries, c(a.x, a.y - span), c(a.x, a.y + span));
                double ulp = Math.max(Math.ulp(a.x), Math.ulp(a.y));
                for (double offset : new double[] {0, ulp, -ulp, 0.0001, -0.0001}) {
                    double ox = -dy / length * offset, oy = dx / length * offset;
                    addBoth(queries, c(a.x + ox, a.y + oy), c(b.x + ox, b.y + oy));
                    addBoth(queries, c(a.x - dx + ox, a.y - dy + oy),
                            c(b.x + dx + ox, b.y + dy + oy));
                }
            }
        }
        return queries;
    }

    /** Проверка достижимости без недетерминированных GC/WeakReference assertions. */
    private static Set<Object> reachableObjects(Object root) throws IllegalAccessException {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> pending = new ArrayList<>();
        pending.add(root);
        for (int i = 0; i < pending.size(); i++) {
            Object current = pending.get(i);
            if (current == null || !seen.add(current)) continue;
            Class<?> type = current.getClass();
            if (type.isArray() && !type.getComponentType().isPrimitive()) {
                for (int j = 0; j < Array.getLength(current); j++) pending.add(Array.get(current, j));
            } else if (current instanceof Map) {
                pending.addAll(((Map<?, ?>) current).keySet());
                pending.addAll(((Map<?, ?>) current).values());
            } else if (current instanceof Iterable) {
                for (Object element : (Iterable<?>) current) pending.add(element);
            } else {
                for (Class<?> owner = type; owner != null && (owner.getName().startsWith("ru.lct.")
                        || owner.getName().startsWith("org.locationtech.jts.")); owner = owner.getSuperclass()) {
                    for (Field field : owner.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                        field.setAccessible(true);
                        pending.add(field.get(current));
                    }
                }
            }
        }
        return seen;
    }

    private static void addBoth(List<LineString> queries, Coordinate first, Coordinate last) {
        queries.add(GF.createLineString(new Coordinate[] {new Coordinate(first), new Coordinate(last)}));
        queries.add(GF.createLineString(new Coordinate[] {new Coordinate(last), new Coordinate(first)}));
    }

    private static Polygon radialPolygon(int vertices, double translateX) {
        Coordinate[] points = new Coordinate[vertices + 1];
        for (int i = 0; i < vertices; i++) {
            double angle = 2 * Math.PI * i / vertices, radius = 60 * (1 + 0.2 * Math.sin(7 * angle));
            points[i] = c(translateX + radius * Math.cos(angle), radius * Math.sin(angle));
        }
        points[vertices] = new Coordinate(points[0]);
        return GF.createPolygon(points);
    }

    private static Polygon rectangle(double x0, double y0, double x1, double y1) {
        return GF.createPolygon(new Coordinate[] {c(x0, y0), c(x1, y0), c(x1, y1), c(x0, y1), c(x0, y0)});
    }

    private static Geometry hole() throws Exception {
        return read("POLYGON ((0 0,40 0,40 40,0 40,0 0),(10 10,10 30,30 30,30 10,10 10))");
    }

    private static Geometry concave() throws Exception {
        return read("POLYGON ((0 0,40 0,40 10,10 10,10 30,40 30,40 40,0 40,0 0))");
    }

    private static Geometry move(Geometry source, double angle, double x, double y) {
        return AffineTransformation.translationInstance(x, y).transform(
                AffineTransformation.rotationInstance(angle).transform(source));
    }

    private static Geometry read(String wkt) throws Exception { return new WKTReader(GF).read(wkt); }
    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
    private static LineString segment(double x0, double y0, double x1, double y1) {
        return GF.createLineString(new Coordinate[] {c(x0, y0), c(x1, y1)});
    }

    private static final class Range {
        private final double startM, endM;
        private Range(double startM, double endM) { this.startM = startM; this.endM = endM; }
    }
}
