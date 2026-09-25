package ru.lct.heatroute.domain.constraints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/** У частичного входящего ввода фиксирован конец у потребителя, а не наружный порт. */
class RoadTerminalDirectionTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final RoadCrossingClearance guard = new RoadCrossingClearance();

    @Test
    void incomingAngleDoesNotImposeTheOutgoingAngle() {
        Geometry road = polygon(4, 6.38350719, 10, 10);
        LineString incoming = line(20, 0);
        assertThat(suffix(incoming, road)).isTrue();
        assertThat(prefix(incoming.reverse(), road)).isFalse();
        assertThat(guard.assess(incoming, road, 1.755, 45, 3).isAllowed()).isTrue();
    }

    @Test
    void shallowIncomingEntryIsRejectedDespiteLegalOutgoingEntry() {
        Geometry road = polygon(4, 4, 10, 12.38350719);
        LineString incoming = line(20, 0);
        assertThat(suffix(incoming, road)).isFalse();
        assertThat(prefix(incoming.reverse(), road)).isTrue();
        assertThat(guard.assess(incoming, road, 1.755, 45, 3).getFailureCode())
                .isEqualTo("SPECIAL_CROSSING_ANGLE_VIOLATION");
    }

    @Test
    void entryBeyondThePortIsCheckedWithoutInventingACompleteRoute() {
        Geometry road = polygon(4, 4, 20, 20);
        assertThat(suffix(line(5, 0), road)).isTrue();
        assertThat(guard.assess(line(5, 0), road, 1.755, 45, 3).getFailureCode())
                .isEqualTo("SPECIAL_CROSSING_EXTENSION_MISSING");
        assertThat(guard.assess(line(30, 0), road, 1.755, 45, 3).isAllowed()).isTrue();
    }

    @Test
    void portInsideRoadCannotHideItsShallowFarEntry() {
        Geometry road = polygon(4, 4, 20, 22.38350719);
        assertThat(suffix(line(5, 0), road)).isFalse();
        assertThat(prefix(line(0, 5), road)).isTrue();
    }

    @Test
    void unrelatedFutureComponentDoesNotImposeItsEntryAngle() {
        Geometry first = polygon(4, 4, 10, 10);
        Geometry future = polygon(20, 20, 30, 32.38350719);
        Geometry road = factory.createMultiPolygon(new org.locationtech.jts.geom.Polygon[] {
                (org.locationtech.jts.geom.Polygon) first, (org.locationtech.jts.geom.Polygon) future});
        assertThat(suffix(line(12, 0), road)).isTrue();
        assertThat(guard.assess(line(15, 0), road, 1.755, 45, 3).isAllowed()).isTrue();
    }

    @Test
    void unavoidableSecondComponentInsideRequiredStraightExtensionIsChecked() {
        Geometry road = multi(polygon(4, 4, 10, 10), polygon(12.5, 12.5, 30, 32.38350719));
        assertThat(suffix(line(12, 0), road)).isFalse();
        assertThat(guard.assess(line(40, 0), road, 1.755, 45, 3).getFailureCode())
                .isEqualTo("SPECIAL_CROSSING_ANGLE_VIOLATION");
    }

    @Test
    void independentComponentsAfterTheRequiredStraightExtensionRemainOptional() {
        for (double near : new double[] {13.5, 20}) {
            Geometry road = multi(polygon(4, 4, 10, 10), polygon(near, near, 30, 32.38350719));
            assertThat(suffix(line(12, 0), road)).isTrue();
            assertThat(guard.assess(line(13, 0), road, 1.755, 45, 3).isAllowed()).isTrue();
        }
    }

    @Test
    void requiredStraightContinuationIncludesAChainOfThreeComponents() {
        Geometry road = multi(polygon(4, 4, 10, 10), polygon(12.5, 12.5, 20, 20),
                polygon(22.5, 22.5, 30, 32.38350719));
        assertThat(suffix(line(12, 0), road)).isFalse();
        assertThat(guard.assess(line(40, 0), road, 1.755, 45, 3).getFailureCode())
                .isEqualTo("SPECIAL_CROSSING_ANGLE_VIOLATION");
    }

    @Test
    void cannotInventProtectionPastTheDemandEndpoint() {
        Geometry road = polygon(2, 2, 10, 10);
        assertThat(suffix(line(20, 0), road)).isFalse();
        assertThat(prefix(line(0, 20), road)).isFalse();
    }

    @Test
    void exactDemandProtectionBoundaryIsAccepted() {
        Geometry road = polygon(3, 3, 10, 10);
        assertThat(suffix(line(20, 0), road)).isTrue();
        assertThat(guard.assess(line(20, 0), road, 1.755, 45, 3).isAllowed()).isTrue();
    }

    @Test
    void parallelUncrossedApproachCannotBorrowTheOpenPort() {
        Geometry road = factory.createPolygon(new Coordinate[] {
                p(0, 1), p(20, 1), p(20, 3), p(0, 3), p(0, 1)});
        assertThat(suffix(line(15, 5), road)).isFalse();
        assertThat(prefix(line(5, 15), road)).isFalse();
    }

    @Test
    void doesNotMutateSuffixOrObstacle() {
        Geometry road = polygon(4, 6.38350719, 10, 10);
        LineString incoming = line(20, 0);
        Geometry originalRoad = road.copy(), originalLine = incoming.copy();
        assertThat(suffix(incoming, road)).isTrue();
        assertThat(suffix(incoming, road)).isTrue();
        assertThat(incoming.equalsExact(originalLine)).isTrue();
        assertThat(road.equalsExact(originalRoad)).isTrue();
    }

    @Test
    void cancellationPreservesTheInterrupt() {
        LineString incoming = line(20, 0);
        Geometry road = polygon(4, 4, 10, 10);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> suffix(incoming, road)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private boolean suffix(LineString line, Geometry road) {
        return guard.terminalSuffixAllowed(line, road, 1.755, 45, 3);
    }

    private boolean prefix(LineString line, Geometry road) {
        return guard.terminalPrefixAllowed(line, road, 1.755, 45, 3);
    }

    private Geometry polygon(double nearLow, double nearHigh, double farLow, double farHigh) {
        return factory.createPolygon(new Coordinate[] {
                p(nearLow, -1), p(farLow, -1), p(farHigh, 1), p(nearHigh, 1), p(nearLow, -1)});
    }

    private LineString line(double fromX, double toX) {
        return factory.createLineString(new Coordinate[] {p(fromX, 0), p(toX, 0)});
    }

    private Geometry multi(Geometry... parts) {
        return factory.createMultiPolygon(java.util.Arrays.stream(parts)
                .map(part -> (org.locationtech.jts.geom.Polygon) part)
                .toArray(org.locationtech.jts.geom.Polygon[]::new));
    }

    private Coordinate p(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
}
