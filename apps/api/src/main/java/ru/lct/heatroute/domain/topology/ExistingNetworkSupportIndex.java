package ru.lct.heatroute.domain.topology;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteNode;

/**
 * Адаптирует существующие участки импорта в индекс опор новой камеры.
 * Учитывает выбранный участок и связанные общим концом участки, но не соседние трубы.
 */
public final class ExistingNetworkSupportIndex {
    // Совместимо с миллиметровыми координатами RouteNode и топологическим допуском 1 см.
    private static final double TOLERANCE_M = 0.01;
    private final Map<String, Segment> byId = new HashMap<>();
    private final STRtree index = new STRtree();
    private final ExistingNetworkIncidence incidence;

    public ExistingNetworkSupportIndex(Collection<ImportedOfficialFeature> features) {
        incidence = new ExistingNetworkIncidence(features);
        OfficialPipeCatalog catalog = new OfficialPipeCatalog();
        for (ImportedOfficialFeature feature : features) {
            ensureActive();
            if (!"heat_network".equals(feature.getObjectType())) continue;
            JsonNode value = feature.getAttributes().path("diameter");
            Integer diameter = value.isIntegralNumber() && value.canConvertToInt()
                    && catalog.byDiameter(value.intValue()).isPresent() ? value.intValue() : null;
            LineString line = feature.getMetricGeometry() instanceof LineString
                    ? (LineString) feature.getMetricGeometry() : null;
            Segment segment = new Segment(feature.getFeatureId(), line, diameter);
            if (byId.putIfAbsent(segment.id, segment) != null) {
                throw new IllegalArgumentException("Duplicate existing network ID: " + segment.id);
            }
            if (line != null && !line.isEmpty()) index.insert(line.getEnvelopeInternal(), segment);
        }
        index.build();
    }

    /** Максимальный ДУ действительно примыкающих существующих участков в EPSG:32637. */
    public int maximumDiameter(String targetId, Coordinate at) {
        ensureActive();
        Segment selected = byId.get(targetId);
        if (selected == null || selected.line == null || selected.line.isEmpty() || at == null) {
            throw new IllegalArgumentException("Existing support is missing: " + targetId);
        }
        Point point = selected.line.getFactory().createPoint(at);
        if (selected.line.distance(point) > TOLERANCE_M) {
            throw new IllegalArgumentException("Tie-in does not lie on its existing support: " + targetId);
        }
        int maximum = diameter(selected);
        Envelope window = new Envelope(at);
        window.expandBy(TOLERANCE_M);
        @SuppressWarnings("unchecked")
        List<Segment> nearby = index.query(window);
        for (Segment other : nearby) {
            ensureActive();
            if (other == selected || other.line.distance(point) > TOLERANCE_M) continue;
            if (endpoint(selected.line, at) && endpoint(other.line, at)
                    && nearestEndpoint(selected.line, at).distance(nearestEndpoint(other.line, at)) <= TOLERANCE_M) {
                Geometry intersection = selected.line.intersection(other.line);
                if (!intersection.isEmpty() && !(intersection instanceof Point)) {
                    throw new IllegalArgumentException("Ambiguous overlapping existing support: " + other.id);
                }
                maximum = Math.max(maximum, diameter(other));
            } else {
                Geometry intersection = selected.line.intersection(other.line);
                if (!intersection.isEmpty() && intersection.distance(point) <= TOLERANCE_M) {
                    throw new IllegalArgumentException("Ambiguous crossing existing support: " + other.id);
                }
            }
        }
        return maximum;
    }

    /** Проверяет сохранённое значение; старые узлы без поля разрешает по исходному импорту. */
    public RouteNode verified(RouteNode node) {
        node = incidence.resolved(node);
        if (!node.isRoot() || !"new_tie_in_chamber".equals(node.getNodeType())) return node;
        int resolved = maximumDiameter(node.getTargetId(), node.getCoordinate().toCoordinate());
        if (node.getExistingIncidentDiameter() != null && node.getExistingIncidentDiameter() != resolved) {
            throw new IllegalArgumentException("Existing support diameter differs from the import: " + node.getId());
        }
        return node.withExistingIncidentDiameter(resolved);
    }

    private int diameter(Segment segment) {
        if (segment.diameter == null) throw new IllegalArgumentException("Existing support diameter is missing or invalid: " + segment.id);
        return segment.diameter;
    }

    private boolean endpoint(LineString line, Coordinate at) {
        return line.getCoordinateN(0).distance(at) <= TOLERANCE_M
                || line.getCoordinateN(line.getNumPoints() - 1).distance(at) <= TOLERANCE_M;
    }

    private Coordinate nearestEndpoint(LineString line, Coordinate at) {
        Coordinate first = line.getCoordinateN(0), last = line.getCoordinateN(line.getNumPoints() - 1);
        return first.distance(at) <= last.distance(at) ? first : last;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Existing support lookup cancelled");
    }

    private static final class Segment {
        private final String id;
        private final LineString line;
        private final Integer diameter;
        private Segment(String id, LineString line, Integer diameter) {
            this.id = id; this.line = line; this.diameter = diameter;
        }
    }
}
