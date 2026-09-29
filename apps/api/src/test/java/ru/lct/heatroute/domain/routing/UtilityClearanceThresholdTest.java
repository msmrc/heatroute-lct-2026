package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;

class UtilityClearanceThresholdTest {
    private static final GeometryFactory FACTORY = new GeometryFactory();

    @Test
    void matchesExactDistanceAtBoundaryUlpsForLinesAndPolygonalShapes() throws Exception {
        List<Geometry> sources = List.of(
                read("LINESTRING (-100 0,100 0)"),
                read("MULTILINESTRING ((-80 -20,80 -20),(-60 30,60 45))"),
                read("POLYGON ((-60 -50,60 -50,60 50,-60 50,-60 -50),(-20 -15,-20 15,20 15,20 -15,-20 -15))"),
                read("MULTIPOLYGON (((-100 -40,-60 -40,-60 0,-100 0,-100 -40)),"
                        + " ((40 10,100 10,100 60,40 60,40 10)))"));
        Random random = new Random(0x6A09413L);
        for (Geometry source : sources) {
            for (int index = 0; index < 384; index++) {
                LineString segment = segment(
                        -150 + random.nextDouble() * 300,
                        -100 + random.nextDouble() * 200,
                        -150 + random.nextDouble() * 300,
                        -100 + random.nextDouble() * 200);
                double distance = segment.distance(source);
                for (double threshold : List.of(distance, Math.nextDown(distance), Math.nextUp(distance))) {
                    assertMatchesDistance(segment, source, threshold);
                }
            }
        }
    }

    @Test
    void preservesTheSeededEnvelopeRoundingCounterexample() throws Exception {
        Geometry source = read("LINESTRING (-100 0,100 0)");
        Random random = new Random(6_292_026L);
        boolean foundRoundedFalseAccept = false;
        for (int index = 0; index < 5_000; index++) {
            double startX = -150 + random.nextDouble() * 300;
            double startY = -100 + random.nextDouble() * 200;
            LineString segment = segment(startX, startY,
                    startX + random.nextDouble() * 50, startY + random.nextDouble() * 50);
            double distance = segment.distance(source);
            double cutoff = Math.nextUp(distance);
            assertMatchesDistance(segment, source, cutoff);
            // The equal-boundary adjustment used by a tempting early-exit implementation makes
            // this envelope rounding observable as a false utility-clearance acceptance.
            if (distance < cutoff && !segment.isWithinDistance(source, Math.nextDown(cutoff))) {
                foundRoundedFalseAccept = true;
                assertThat(OfficialRouteGeometryRules.meetsUtilityClearance(segment, source, cutoff)).isFalse();
            }
        }
        assertThat(foundRoundedFalseAccept).isTrue();
    }

    @Test
    void preservesDistanceFallbackForEmptyAndNonPositiveSubnormalAndNonfiniteThresholds() throws Exception {
        LineString segment = segment(-10, 3, 10, 3);
        for (Geometry source : List.of(FACTORY.createLineString(), read("POINT (0 0)"))) {
            for (double threshold : new double[] {0.0, -0.0, Double.MIN_VALUE, -1.0,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
                assertMatchesDistance(segment, source, threshold);
            }
        }
    }

    @Test
    void twoPointLineFastPathMatchesJtsAtDistanceBoundaries() {
        for (LinePair pair : List.of(
                new LinePair("crossing", segment(500_000, 6_170_000, 500_030, 6_170_030),
                        segment(500_000, 6_170_030, 500_030, 6_170_000)),
                new LinePair("parallel", segment(499_950, 6_170_010, 500_050, 6_170_010),
                        segment(499_950, 6_170_014, 500_050, 6_170_014)),
                new LinePair("endpoint", segment(500_000, 6_170_000, 500_010, 6_170_000),
                        segment(500_010, 6_170_003, 500_030, 6_170_003)),
                new LinePair("degenerate", segment(500_000, 6_170_000, 500_000, 6_170_000),
                        segment(500_003, 6_170_004, 500_010, 6_170_004)))) {
            assertDistanceAndNeighbouringUlps(pair.segment, pair.source);
        }
    }

    @Test
    void twoPointLineGuardBoundariesAndUnsafeCoordinatesKeepExactDistanceDecision() {
        double limit = Math.scalb(1.0, 400);
        assertDistanceAndNeighbouringUlps(
                segment(limit, 0, limit, limit / 2), segment(0, 0, 0, limit / 2));
        assertDistanceAndNeighbouringUlps(
                segment(Math.nextUp(limit), 0, Math.nextUp(limit), limit / 2),
                segment(0, 0, 0, limit / 2));

        double tiny = Math.scalb(1.0, -900);
        assertDistanceAndNeighbouringUlps(segment(0, 0, tiny, 0), segment(0, tiny, tiny, tiny));
        assertDistanceAndNeighbouringUlps(
                segment(0, 0, 1.0e200, 0), segment(0, 1.0e200, 1.0e200, 1.0e200));

        LineString ordinary = segment(0, 0, 10, 0);
        assertMatchesDistance(ordinary,
                segment(Double.NaN, 1, 10, 1), 1.0);
        assertMatchesDistance(ordinary,
                segment(Double.POSITIVE_INFINITY, 1, 10, 1), 1.0);
    }

    @Test
    void readsMutatedSourceGeometryOnEveryCall() {
        LineString source = segment(-20, 0, 20, 0);
        LineString segment = segment(-10, 1, 10, 1);
        assertThat(OfficialRouteGeometryRules.meetsUtilityClearance(segment, source, 2.0)).isFalse();
        source.apply((org.locationtech.jts.geom.CoordinateFilter) coordinate -> coordinate.y += 10);
        source.geometryChanged();
        assertThat(OfficialRouteGeometryRules.meetsUtilityClearance(segment, source, 2.0)).isTrue();
        assertMatchesDistance(segment, source, 11.0);
    }

    private static void assertMatchesDistance(LineString segment, Geometry source, double threshold) {
        boolean expected = segment.distance(source) >= threshold;
        assertThat(OfficialRouteGeometryRules.meetsUtilityClearance(segment, source, threshold))
                .as("segment=%s source=%s cutoff=%s", segment, source, threshold)
                .isEqualTo(expected);
    }

    private static void assertDistanceAndNeighbouringUlps(LineString segment, LineString source) {
        double distance = segment.distance(source);
        for (double threshold : new double[] {Math.nextDown(distance), distance, Math.nextUp(distance)}) {
            assertMatchesDistance(segment, source, threshold);
        }
    }

    private static LineString segment(double x0, double y0, double x1, double y1) {
        return FACTORY.createLineString(new Coordinate[] {new Coordinate(x0, y0), new Coordinate(x1, y1)});
    }

    private static Geometry read(String wkt) throws Exception {
        return new WKTReader(FACTORY).read(wkt);
    }

    private static final class LinePair {
        private final String name;
        private final LineString segment;
        private final LineString source;

        private LinePair(String name, LineString segment, LineString source) {
            this.name = name;
            this.segment = segment;
            this.source = source;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
