package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class DepthFloorExtractionTest {
    private final OfficialDepthFloorExtractor extractor = new OfficialDepthFloorExtractor();
    private final ObjectMapper mapper = new ObjectMapper();
    private final WKTReader reader = new WKTReader();

    @Test
    void actualPolygonHoleAndMultipleComponentsDoNotBecomeOneArtificialContinuousFloor()
            throws Exception {
        var road =
                feature(
                        "road",
                        "road",
                        "POLYGON ((10 -3,40 -3,40 3,10 3,10 -3),(20 -1,20 1,30 1,30 -1,20 -1))");
        var tram =
                feature(
                        "tram",
                        "tram_tracks",
                        "MULTIPOLYGON (((60 -3,65 -3,65 3,60 3,60 -3)),((75 -3,80 -3,80 3,75 3,75 -3)))");
        var spans =
                extractor.extract(
                        edge(List.of(new RouteCoordinate(0, 0), new RouteCoordinate(100, 0))),
                        List.of(road, tram));
        assertThat(spans).hasSize(4);
        assertSpan(spans.get(0), "road", "10", "20", "1");
        assertSpan(spans.get(1), "road", "30", "40", "1");
        assertSpan(spans.get(2), "tram", "60", "65", "1.2");
        assertSpan(spans.get(3), "tram", "75", "80", "1.2");
    }

    @Test
    void repeatedTraversalsKeepDistinctStationsInsteadOfCollapsingToFirstProjection()
            throws Exception {
        var road = feature("road", "road", "POLYGON ((10 -3,20 -3,20 3,10 3,10 -3))");
        var spans =
                extractor.extract(
                        edge(
                                List.of(
                                        new RouteCoordinate(0, 0),
                                        new RouteCoordinate(30, 0),
                                        new RouteCoordinate(0, 0))),
                        List.of(road));
        assertThat(spans).hasSize(2);
        assertSpan(spans.get(0), "road", "10", "20", "1");
        assertSpan(spans.get(1), "road", "40", "50", "1");
    }

    @Test
    void bentRouteSubdivisionsDoNotDuplicateTouchingIntervalsAndNoThreeMetreFloorExtensionIsAdded()
            throws Exception {
        var road = feature("road", "road", "POLYGON ((10 -3,20 -3,20 3,10 3,10 -3))");
        var spans =
                extractor.extract(
                        edge(
                                List.of(
                                        new RouteCoordinate(0, 0),
                                        new RouteCoordinate(15, 0),
                                        new RouteCoordinate(30, 0))),
                        List.of(road));
        assertThat(spans).hasSize(1);
        assertSpan(spans.get(0), "road", "10", "20", "1");
    }

    @Test
    void translatedRotatedPhysicalCrossingPreservesMetricStationsWithinStoragePrecision()
            throws Exception {
        Geometry road = reader.read("POLYGON ((10 -3,20 -3,20 3,10 3,10 -3))");
        AffineTransformation transform = AffineTransformation.rotationInstance(Math.toRadians(37));
        transform.translate(430000, 6170000);
        Coordinate first = new Coordinate();
        Coordinate last = new Coordinate();
        transform.transform(new Coordinate(0, 0), first);
        transform.transform(new Coordinate(30, 0), last);
        var feature =
                new ImportedOfficialFeature(
                        "unrelated-id",
                        "restriction",
                        mapper.readTree("{\"restriction_type\":\"road\"}"),
                        transform.transform(road));
        var spans =
                extractor.extract(
                        edge(
                                List.of(
                                        new RouteCoordinate(first.x, first.y),
                                        new RouteCoordinate(last.x, last.y))),
                        List.of(feature));
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getStartM().subtract(new BigDecimal("10")).abs())
                .isLessThan(new BigDecimal(".001"));
        assertThat(spans.get(0).getEndM().subtract(new BigDecimal("20")).abs())
                .isLessThan(new BigDecimal(".001"));
    }

    private void assertSpan(
            DepthFloorInterval span, String id, String start, String end, String floor) {
        assertThat(span.getFeatureId()).isEqualTo(id);
        assertThat(span.getStartM()).isEqualByComparingTo(start);
        assertThat(span.getEndM()).isEqualByComparingTo(end);
        assertThat(span.getMinimumDepthM()).isEqualByComparingTo(floor);
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(
                id,
                "restriction",
                mapper.readTree("{\"restriction_type\":\"" + type + "\"}"),
                reader.read(wkt));
    }

    private RouteEdge edge(List<RouteCoordinate> coordinates) {
        double length = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            length +=
                    coordinates
                            .get(i)
                            .toCoordinate()
                            .distance(coordinates.get(i - 1).toCoordinate());
        }
        return new RouteEdge(
                "edge", "start", "end", length, coordinates, List.of(), BigDecimal.ONE, 50);
    }
}
