package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Владение, lazy publication и бюджеты road-подготовки проверяются на настоящей геометрии. */
class PreparedRoadConstraintResourcesTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final ImportedOfficialFeature road = new ImportedOfficialFeature("road", "restriction",
            new ObjectMapper().createObjectNode().put("restriction_type", "road"),
            factory.createPolygon(new Coordinate[] {p(0, 0), p(20, 0), p(20, 6), p(0, 6), p(0, 0)}));

    @Test void routingReservationIncludesRoadIndexBeforeLazyAllocationAtExactBudgetBoundary() {
        Constraint reference = rules.baseConstraints(List.of(road), 100).get(0);
        long weight = reference.source().getNumPoints() + bufferWeight(reference);
        assertThat(reference.roadCrossingCoordinateReservation()).isEqualTo(15);
        var prepared = new PreparedRoutingConstraints(rules, 2, weight);
        Constraint actual = prepared.prepare(List.of(road), 100).get(0);
        assertThat(prepared.retainedCoordinateCount()).isEqualTo(weight);
        assertThat(helper(actual)).isNull();
        assertThat(allowed(actual)).isTrue();
        assertThat(helper(actual)).isNotNull();
        assertThat(prepared.prepare(List.of(road), 100).get(0)).isSameAs(actual);
        assertThat(prepared.retainedCoordinateCount()).isEqualTo(weight);
        var undersized = new PreparedRoutingConstraints(rules, 2, weight - 1);
        assertThat(undersized.prepare(List.of(road), 100)).hasSize(1);
        assertThat(undersized.retainedEntryCount()).isZero();
    }

    @Test void validationBudgetAlsoIncludesReservedRoadCoordinates() {
        Constraint reference = rules.baseConstraints(List.of(road), 100).get(0);
        long weight = reference.source().getNumPoints() + 2 + bufferWeight(reference);
        var prepared = new PreparedValidationConstraints(rules, 2, 2, weight);
        Constraint actual = prepared.prepareIntersecting(List.of(road), 100, crossing().getEnvelopeInternal()).get(0);
        assertThat(prepared.retainedCoordinateCount()).isEqualTo(weight);
        assertThat(prepared.retainedBufferCount()).isEqualTo(1);
        assertThat(allowed(actual)).isTrue();
        assertThat(prepared.retainedCoordinateCount()).isEqualTo(weight);
        var undersized = new PreparedValidationConstraints(rules, 2, 2, weight - 1);
        assertThat(undersized.prepareIntersecting(List.of(road), 100, crossing().getEnvelopeInternal())).hasSize(1);
        assertThat(undersized.retainedBufferCount()).isZero();
        assertThat(undersized.retainedCoordinateCount()).isLessThanOrEqualTo(weight - 1);
    }

    @Test void concurrentColdQueriesPublishOneReadOnlyIndex() throws Exception {
        Constraint constraint = rules.baseConstraints(List.of(road), 100).get(0);
        var executor = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6), start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                assertThat(allowed(constraint)).isTrue();
                return helper(constraint);
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Object first = futures.get(0).get(5, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            for (Future<Object> future : futures) assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(first);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test void changingSourceOrDiameterDoesNotReuseAnIncompatibleIndex() {
        var prepared = new PreparedRoutingConstraints(rules);
        Constraint old = prepared.prepare(List.of(road), 100).get(0);
        assertThat(allowed(old)).isTrue();
        Object oldHelper = helper(old);
        Constraint otherDiameter = prepared.prepare(List.of(road), 400).get(0);
        assertThat(otherDiameter).isNotSameAs(old);
        assertThat(allowed(otherDiameter)).isTrue();
        assertThat(helper(otherDiameter)).isNotSameAs(oldHelper);
        road.getMetricGeometry().apply((CoordinateFilter) coordinate -> coordinate.x += 100);
        road.getMetricGeometry().geometryChanged();
        Constraint changed = prepared.prepare(List.of(road), 100).get(0);
        assertThat(changed).isNotSameAs(old);
        assertThat(old.source().getEnvelopeInternal().getMinX()).isEqualTo(500000);
        assertThat(changed.source().getEnvelopeInternal().getMinX()).isEqualTo(500100);
        assertThat(helper(changed)).isNull();
    }

    @Test void cancelledColdPreparationPreservesInterruptAndCanRetry() {
        Constraint constraint = rules.baseConstraints(List.of(road), 100).get(0);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> allowed(constraint)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(helper(constraint)).isNull();
        } finally {
            Thread.interrupted();
        }
        assertThat(allowed(constraint)).isTrue();
        assertThat(helper(constraint)).isNotNull();
    }

    @Test void finalAssessmentAndSectionsDoNotDependOnTheSearchIndex() {
        List<Constraint> constraints = rules.baseConstraints(List.of(road), 100);
        LineString route = crossing();
        var edge = new RouteEdge("edge", "from", "to", route.getLength(),
                List.of(new RouteCoordinate(500010, 6169990), new RouteCoordinate(500010, 6170020)),
                rules.sections(route, constraints), null, 100);
        assertThat(rules.validate(edge, route, constraints)).isEmpty();
        assertThat(helper(constraints.get(0))).isNull();
    }

    private long bufferWeight(Constraint constraint) {
        return constraint.blocked().getNumPoints() + constraint.segmentIndexCoordinateReservation()
                + constraint.roadCrossingCoordinateReservation();
    }
    private Object helper(Constraint constraint) { return ReflectionTestUtils.getField(constraint, "roadCrossings"); }
    private boolean allowed(Constraint constraint) {
        return rules.segmentAllowed(p(10, -10), p(10, 20), rules.index(List.of(constraint)));
    }
    private LineString crossing() { return factory.createLineString(new Coordinate[] {p(10, -10), p(10, 20)}); }
    private Coordinate p(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
}
