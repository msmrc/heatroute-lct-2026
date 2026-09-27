package ru.lct.heatroute.domain.topology;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LineSegment;
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
    // Исходные линии официального набора расходятся с общей осью камеры до 2,06°.
    // Исправляем только малую погрешность оцифровки; произвольную косую геометрию не принимаем.
    private static final double MAX_DIGITIZED_AXIS_CORRECTION_RADIANS = Math.toRadians(2.5);
    private static final double QUARTER_TURN_RADIANS = Math.PI / 2.0;
    private static final double FULL_TURN_RADIANS = Math.PI * 2.0;
    private static final double NUMERICAL_EPSILON = 1e-12;
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

    /** Векторы существующих примыканий направлены от исходной камеры; проходящая линия даёт два луча. */
    public List<Coordinate> existingDirections(RouteNode node) {
        if (!node.isRoot() || !node.isChamber()) return List.of();
        Coordinate at = node.getCoordinate().toCoordinate();
        Envelope window = new Envelope(at);
        window.expandBy(TOLERANCE_M);
        @SuppressWarnings("unchecked")
        List<Segment> nearby = index.query(window);
        List<Coordinate> directions = new ArrayList<>();
        for (Segment segment : nearby) {
            ensureActive();
            LineString line = segment.line;
            if (line.distance(line.getFactory().createPoint(at)) > TOLERANCE_M) continue;
            for (int i = 1; i < line.getNumPoints(); i++) {
                Coordinate before = line.getCoordinateN(i - 1), after = line.getCoordinateN(i);
                if (before.equals2D(after)) continue;
                boolean starts = before.distance(at) <= TOLERANCE_M;
                boolean ends = after.distance(at) <= TOLERANCE_M;
                if (starts && ends) continue;
                if (starts) directions.add(new Coordinate(after.x - before.x, after.y - before.y));
                else if (ends) directions.add(new Coordinate(before.x - after.x, before.y - after.y));
                else if (new LineSegment(before, after).distance(at) <= TOLERANCE_M) {
                    directions.add(new Coordinate(before.x - after.x, before.y - after.y));
                    directions.add(new Coordinate(after.x - before.x, after.y - before.y));
                }
            }
        }
        return normalizedChamberAxes(directions);
    }

    /**
     * Восстанавливает ориентацию условного квадратного плана камеры из измеренных линий.
     * Каждый исходный луч сохраняет свою занятую сторону, но малые независимые ошибки
     * оцифровки не делают свободную перпендикулярную сторону математически невозможной.
     */
    private List<Coordinate> normalizedChamberAxes(List<Coordinate> measured) {
        if (measured.isEmpty()) return List.of();
        double cosine = 0.0, sine = 0.0;
        for (Coordinate ray : measured) {
            double angle = Math.atan2(ray.y, ray.x);
            cosine += Math.cos(4.0 * angle);
            sine += Math.sin(4.0 * angle);
        }
        if (Math.hypot(cosine, sine) <= NUMERICAL_EPSILON) return copied(measured);
        double base = Math.atan2(sine, cosine) / 4.0;
        boolean[] occupied = new boolean[4];
        List<Coordinate> normalized = new ArrayList<>(measured.size());
        for (Coordinate ray : measured) {
            double angle = Math.atan2(ray.y, ray.x);
            int quadrant = nearestQuadrant(angle, base);
            double snapped = base + quadrant * QUARTER_TURN_RADIANS;
            if (occupied[quadrant]
                    || angularSeparation(angle, snapped) > MAX_DIGITIZED_AXIS_CORRECTION_RADIANS
                            + NUMERICAL_EPSILON) {
                return copied(measured);
            }
            occupied[quadrant] = true;
            normalized.add(new Coordinate(Math.cos(snapped), Math.sin(snapped)));
        }
        return List.copyOf(normalized);
    }

    private int nearestQuadrant(double angle, double base) {
        int nearest = 0;
        double minimum = Double.POSITIVE_INFINITY;
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            double separation = angularSeparation(angle, base + quadrant * QUARTER_TURN_RADIANS);
            if (separation < minimum) {
                minimum = separation;
                nearest = quadrant;
            }
        }
        return nearest;
    }

    private double angularSeparation(double left, double right) {
        double difference = Math.abs(left - right) % FULL_TURN_RADIANS;
        return Math.min(difference, FULL_TURN_RADIANS - difference);
    }

    private List<Coordinate> copied(List<Coordinate> measured) {
        List<Coordinate> result = new ArrayList<>(measured.size());
        measured.forEach(ray -> result.add(new Coordinate(ray)));
        return List.copyOf(result);
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
