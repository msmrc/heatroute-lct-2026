package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;

/** Сравнивает boolean-предикат с независимым JTS-oracle, включая граничные отрезки. */
class PreparedSegmentBooleanPredicateTest {
    private static final GeometryFactory FACTORY = new GeometryFactory();

    @Test
    void exhaustiveLatticeIncludesCrossingsTouchesOverlapsAndZeroLengthSegments() throws Exception {
        for (String wkt : List.of(
                "POLYGON ((0 0,4 0,4 4,0 4,0 0))",
                "POLYGON ((0 0,4 0,4 4,0 4,0 0),(1 1,1 3,3 3,3 1,1 1))",
                "POLYGON ((0 0,4 0,4 1,1 1,1 4,0 4,0 0))",
                "MULTIPOLYGON (((0 0,1 0,1 1,0 1,0 0)),((2 2,4 2,4 4,2 4,2 2)))")) {
            Geometry source = new WKTReader(FACTORY).read(wkt);
            for (double angle : new double[] {0, .372641}) {
                var transform = AffineTransformation.rotationInstance(angle);
                transform.translate(414000.123, 6173500.789);
                Geometry area = transform.transform(source);
                PreparedGeometry oracle = PreparedGeometryFactory.prepare(area.copy());
                PreparedSegmentIntersection actual = new PreparedSegmentIntersection(area);
                List<Coordinate> points = lattice(transform);
                for (Coordinate start : points) {
                    for (Coordinate end : points) {
                        LineString query = FACTORY.createLineString(new Coordinate[] {start, end});
                        assertThat(actual.intersects(query)).as("%s / %s", area, query)
                                .isEqualTo(oracle.intersects(query));
                    }
                }
            }
        }
    }

    @Test
    void adjacentDoubleCoordinatesAroundThinBoundaryMatchOracle() throws Exception {
        for (double origin : new double[] {0, 414000.123, 6173500.789, 1e12}) {
            double upper = Math.nextUp(Math.nextUp(origin));
            Geometry area = FACTORY.createPolygon(new Coordinate[] {
                    new Coordinate(origin, origin), new Coordinate(origin + 8, origin),
                    new Coordinate(origin + 8, upper), new Coordinate(origin, upper),
                    new Coordinate(origin, origin)});
            PreparedGeometry oracle = PreparedGeometryFactory.prepare(area.copy());
            PreparedSegmentIntersection actual = new PreparedSegmentIntersection(area);
            for (double startY : new double[] {Math.nextDown(origin), origin, Math.nextUp(origin),
                    upper, Math.nextUp(upper)}) {
                for (double endY : new double[] {Math.nextDown(origin), origin, Math.nextUp(origin),
                        upper, Math.nextUp(upper)}) {
                    LineString query = FACTORY.createLineString(new Coordinate[] {
                            new Coordinate(origin - 2, startY), new Coordinate(origin + 10, endY)});
                    assertThat(actual.intersects(query)).as("%s", query)
                            .isEqualTo(oracle.intersects(query));
                }
            }
        }
    }

    @Test
    void validFinitePolygonRetainsOriginalAnswerForSubnormalQuery() {
        double halfNormal = Double.MIN_NORMAL / 2;
        Geometry area = FACTORY.createPolygon(new Coordinate[] {
                new Coordinate(halfNormal, 0), new Coordinate(0, -0.0),
                new Coordinate(-1, -1), new Coordinate(halfNormal, 0)});
        LineString query = FACTORY.createLineString(new Coordinate[] {
                new Coordinate(-0.0, Double.MIN_VALUE),
                new Coordinate(-halfNormal, -Double.MIN_VALUE)});
        assertThat(area.isValid()).isTrue();
        PreparedSegmentIntersection predicate = new PreparedSegmentIntersection(area);
        assertThat(predicate.usesIndex()).isTrue();
        assertThat(predicate.intersects(query)).isFalse();
    }

    private List<Coordinate> lattice(AffineTransformation transform) {
        List<Coordinate> result = new ArrayList<>();
        for (int x = -1; x <= 5; x++) {
            for (int y = -1; y <= 5; y++) {
                result.add(transform.transform(new Coordinate(x, y), new Coordinate()));
            }
        }
        return result;
    }
}
