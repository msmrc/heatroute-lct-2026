package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;

/** Настоящий JTS; счётчики фиксируют копирование/validity, а не подменяют геометрический ответ. */
class PreparedConstraintResourcesTest {
    private static final GeometryFactory GF = new GeometryFactory();

    @Test
    void unsupportedLineUsesExistingPreparedWithoutCopyingOrCreatingHelper() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        LineString source = new LineString(line(-20, 0, 20, 0).getCoordinateSequence(), GF) {
            @Override protected LineString copyInternal() { copies.incrementAndGet(); return super.copyInternal(); }
        };
        Constraint constraint = constraint(source);
        for (int i = 0; i < 3; i++) {
            assertThat(intersects(constraint, line(0, -10, 0, 10))).isTrue();
            assertThat(intersects(constraint, line(0, 5, 5, 5))).isFalse();
        }
        assertThat(copies).hasValue(0);
        assertThat(helper(constraint)).isNull();
        assertThat(constraint.preparedBlocked().getGeometry()).isSameAs(source);
    }

    @Test
    void oversizedPolygonUsesExistingPreparedWithoutCopyOrValidityScan() throws Exception {
        CountingPolygon source = counted(circle(16385));
        Constraint constraint = constraint(source);
        assertThat(intersects(constraint, line(-100, 0, 100, 0))).isTrue();
        assertThat(intersects(constraint, line(100, 100, 200, 200))).isFalse();
        assertThat(source.copies).hasValue(0);
        assertThat(source.validations).hasValue(0);
        assertThat(helper(constraint)).isNull();
    }

    @Test
    void inclusiveCapCopiesAndValidatesOnceThenReusesThePublishedIndex() throws Exception {
        CountingPolygon source = counted(circle(16384));
        Constraint constraint = constraint(source);
        assertThat(constraint.segmentIndexCoordinateReservation()).isEqualTo(3L * 16384);
        assertThat(source.copies).hasValue(0);
        assertThat(source.validations).hasValue(0);
        for (int i = 0; i < 4; i++) assertThat(intersects(constraint, line(-100, 0, 100, 0))).isTrue();
        assertThat(source.copies).hasValue(1);
        assertThat(source.validations).hasValue(1);
        assertThat(helper(constraint).usesIndex()).isTrue();
    }

    @Test
    void invalidPolygonDeclinesWithoutCopyAndRemembersTheNegativeDecision() throws Exception {
        CountingPolygon source = counted(GF.createPolygon(new Coordinate[] {
            new Coordinate(0, 0), new Coordinate(20, 20), new Coordinate(0, 20),
            new Coordinate(20, 0), new Coordinate(0, 0)}));
        Constraint constraint = constraint(source);
        assertThat(constraint.segmentIndexCoordinateReservation()).isEqualTo(15);
        assertThat(source.validations).hasValue(0);
        for (LineString query : List.of(line(-5, 10, 25, 10), line(30, 30, 40, 40), line(0, 0, 0, 0))) {
            assertThat(intersects(constraint, query)).isEqualTo(constraint.preparedBlocked().intersects(query));
        }
        assertThat(source.copies).hasValue(0);
        assertThat(source.validations).hasValue(1);
        assertThat(helper(constraint)).isNull();
    }

    @Test
    void fullPolylineKeepsOriginalPreparedAndNeverInitializesTheSegmentHelper() throws Exception {
        CountingPolygon source = counted(circle(30));
        Constraint constraint = constraint(source);
        LineString polyline = GF.createLineString(new Coordinate[] {
            new Coordinate(-100, 0), new Coordinate(0, 0), new Coordinate(100, 0)});
        assertThat(intersects(constraint, polyline)).isTrue();
        assertThat(source.copies).hasValue(0);
        assertThat(source.validations).hasValue(0);
        assertThat(helper(constraint)).isNull();
    }

    @Test
    void cancellationIsPreservedBeforeEveryFallbackIncludingFullPolyline() throws Exception {
        List<Geometry> targets = List.of(line(-20, 0, 20, 0), circle(16385), circle(30));
        for (Geometry target : targets) {
            Constraint constraint = constraint(target);
            for (LineString query : List.of(line(-100, 0, 100, 0), GF.createLineString(),
                    GF.createLineString(new Coordinate[] {new Coordinate(-100, 0), new Coordinate(), new Coordinate(100, 0)}))) {
                Thread.currentThread().interrupt();
                try {
                    assertThatThrownBy(() -> intersects(constraint, query)).isInstanceOf(CancellationException.class);
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                } finally { Thread.interrupted(); }
            }
            assertThat(helper(constraint)).isNull();
        }
    }

    @Test
    void interruptedValidityDoesNotCopyOrPublishAndCanRetry() throws Exception {
        CountingPolygon source = counted(circle(30));
        source.interruptValidity.set(true);
        Constraint constraint = constraint(source);
        try {
            assertThatThrownBy(() -> intersects(constraint, line(-100, 0, 100, 0)))
                    .isInstanceOf(CancellationException.class);
            assertThat(helper(constraint)).isNull();
            assertThat(source.copies).hasValue(0);
        } finally { Thread.interrupted(); }
        assertThat(intersects(constraint, line(-100, 0, 100, 0))).isTrue();
        assertThat(source.validations).hasValue(2);
        assertThat(source.copies).hasValue(1);
        assertThat(helper(constraint).usesIndex()).isTrue();
    }

    @Test
    void simultaneousColdQueriesPublishOneOwnedIndex() throws Exception {
        CountingPolygon source = counted(circle(256));
        Constraint constraint = constraint(source);
        CountDownLatch ready = new CountDownLatch(4), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start timeout");
                for (int i = 0; i < 30; i++) {
                    if (!intersects(constraint, line(-100, 0, 100, 0))) return false;
                    if (intersects(constraint, line(100, 100, 200, 200))) return false;
                }
                return true;
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Boolean> future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(source.copies).hasValue(1);
        assertThat(source.validations).hasValue(1);
        assertThat(helper(constraint).usesIndex()).isTrue();
    }

    private static Constraint constraint(Geometry blocked) throws Exception {
        Constructor<Constraint> constructor = Constraint.class.getDeclaredConstructor(
                String.class, String.class, Geometry.class, Geometry.class, SpatialConstraintRule.class);
        constructor.setAccessible(true);
        return constructor.newInstance("test", "park", blocked, blocked, new OfficialConstraintCatalog().find("park").orElseThrow());
    }

    private static PreparedSegmentIntersection helper(Constraint constraint) throws Exception {
        Field field = Constraint.class.getDeclaredField("segmentIntersection");
        field.setAccessible(true);
        return (PreparedSegmentIntersection) field.get(constraint);
    }

    private static boolean intersects(Constraint constraint, LineString line) throws Exception {
        Method method = Constraint.class.getDeclaredMethod("intersectsBlocked", LineString.class);
        method.setAccessible(true);
        try { return (Boolean) method.invoke(constraint, line); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) throw (RuntimeException) ex.getCause();
            if (ex.getCause() instanceof Error) throw (Error) ex.getCause();
            throw ex;
        }
    }

    private static CountingPolygon counted(Polygon polygon) {
        return new CountingPolygon(polygon, new AtomicInteger(), new AtomicInteger(), new AtomicBoolean());
    }

    private static Polygon circle(int coordinatesIncludingClosure) {
        Coordinate[] ring = new Coordinate[coordinatesIncludingClosure];
        for (int i = 0; i + 1 < ring.length; i++) {
            double angle = 2 * Math.PI * i / (ring.length - 1);
            ring[i] = new Coordinate(60 * Math.cos(angle), 60 * Math.sin(angle));
        }
        ring[ring.length - 1] = new Coordinate(ring[0]);
        return GF.createPolygon(ring);
    }

    private static LineString line(double x0, double y0, double x1, double y1) {
        return GF.createLineString(new Coordinate[] {new Coordinate(x0, y0), new Coordinate(x1, y1)});
    }

    private static final class CountingPolygon extends Polygon {
        private final AtomicInteger copies, validations;
        private final AtomicBoolean interruptValidity;
        private CountingPolygon(Polygon polygon, AtomicInteger copies, AtomicInteger validations, AtomicBoolean interruptValidity) {
            super(polygon.getExteriorRing(), null, GF);
            this.copies = copies; this.validations = validations; this.interruptValidity = interruptValidity;
        }
        @Override protected Polygon copyInternal() {
            copies.incrementAndGet();
            return new CountingPolygon(super.copyInternal(), copies, validations, interruptValidity);
        }
        @Override public boolean isValid() {
            validations.incrementAndGet();
            boolean result = super.isValid();
            if (interruptValidity.getAndSet(false)) Thread.currentThread().interrupt();
            return result;
        }
    }
}
