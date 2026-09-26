package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Road/tram minimum cover applies on the actual polygon, independently of special-price extensions. */
public final class OfficialDepthFloorExtractor {
    private final GeometryFactory geometries = new GeometryFactory();

    public List<DepthFloorInterval> extract(RouteEdge edge, List<ImportedOfficialFeature> features) {
        double physicalLength = 0;
        for (int i = 1; i < edge.getCoordinates().size(); i++) physicalLength += edge.getCoordinates().get(i - 1)
                .toCoordinate().distance(edge.getCoordinates().get(i).toCoordinate());
        final double total = physicalLength;
        return extract(edge, features, station -> total == 0 ? 0 : station * edge.getLengthM().doubleValue() / total);
    }

    List<DepthFloorInterval> extract(RouteEdge edge, List<ImportedOfficialFeature> features,
            java.util.function.DoubleUnaryOperator storedStation) {
        List<DepthFloorInterval> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            if (!"restriction".equals(feature.getObjectType())) continue;
            String type = feature.getAttributes().path("restriction_type").asText();
            BigDecimal floor = "road".equals(type) ? new BigDecimal("1.0")
                    : "tram_tracks".equals(type) ? new BigDecimal("1.2") : null;
            Geometry polygon = feature.getMetricGeometry();
            if (floor == null || polygon == null || polygon.isEmpty() || polygon.getDimension() != 2) continue;
            List<BigDecimal[]> spans = new ArrayList<>();
            double cumulative = 0;
            for (int i = 1; i < edge.getCoordinates().size(); i++) {
                Coordinate a = edge.getCoordinates().get(i - 1).toCoordinate();
                Coordinate b = edge.getCoordinates().get(i).toCoordinate();
                double segmentLength = a.distance(b);
                if (segmentLength == 0) continue;
                LineString segment = geometries.createLineString(new Coordinate[]{a, b});
                if (segment.getEnvelopeInternal().intersects(polygon.getEnvelopeInternal())) {
                    Geometry intersection = segment.intersection(polygon);
                    collect(intersection, new LengthIndexedLine(segment), cumulative, edge.getLengthM(), spans, storedStation);
                }
                cumulative += segmentLength;
            }
            spans.sort(Comparator.comparing(span -> span[0]));
            List<BigDecimal[]> merged = new ArrayList<>();
            for (BigDecimal[] span : spans) {
                BigDecimal[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && span[0].compareTo(last[1]) <= 0) last[1] = last[1].max(span[1]);
                else merged.add(span.clone());
            }
            for (BigDecimal[] span : merged) result.add(new DepthFloorInterval(feature.getFeatureId(), span[0], span[1], floor));
        }
        result.sort(Comparator.comparing(DepthFloorInterval::getStartM)
                .thenComparing(DepthFloorInterval::getEndM).thenComparing(DepthFloorInterval::getFeatureId));
        return List.copyOf(result);
    }

    private void collect(Geometry intersection, LengthIndexedLine indexed, double cumulative,
            BigDecimal length, List<BigDecimal[]> output, java.util.function.DoubleUnaryOperator storedStation) {
        if (intersection.isEmpty()) return;
        if (intersection.getNumGeometries() > 1 || intersection instanceof org.locationtech.jts.geom.GeometryCollection) {
            for (int i = 0; i < intersection.getNumGeometries(); i++) collect(intersection.getGeometryN(i), indexed, cumulative, length, output, storedStation);
            return;
        }
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (Coordinate coordinate : intersection.getCoordinates()) {
            double station = cumulative + indexed.project(coordinate);
            low = Math.min(low, station); high = Math.max(high, station);
        }
        if (!Double.isFinite(low)) return;
        // Micrometre normalization removes JTS arithmetic noise, not a depth-rule tolerance.
        BigDecimal start = BigDecimal.valueOf(storedStation.applyAsDouble(low)).setScale(6, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(length);
        BigDecimal end = BigDecimal.valueOf(storedStation.applyAsDouble(high)).setScale(6, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(length);
        output.add(new BigDecimal[]{start, end});
    }
}
