package ru.lct.heatroute.domain.constraints;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryComponentFilter;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.CoordinateArraySequenceFactory;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.index.strtree.AbstractNode;
import org.locationtech.jts.index.strtree.Boundable;
import org.locationtech.jts.index.strtree.ItemBoundable;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance.Interval;

/**
 * Извлекает внутренние интервалы прямого звена по индексированной границе road/tram.
 * Владеет ограниченной копией полигона; не хранит запросы и маршруты. Неоднозначность
 * возвращает Optional.empty для исходного JTS overlay, а не пустой список пересечений.
 */
public final class PreparedRoadCrossings {
    private static final int MAX_COORDINATES = 16_384;
    private static final double EPSILON_M = 1e-6;
    private final Geometry source;
    private final RoadCrossingClearance guard = new RoadCrossingClearance();
    private final Envelope bounds;
    private final IndexedPointInAreaLocator locator;
    private final AbstractNode root;

    /** Копия, концы рёбер и распаковка для locator: 3N…4N координат, не всех байтов/JTS-объектов. */
    public static long additionalCoordinateReservation(Geometry source) {
        if (source == null || (source.getClass() != Polygon.class && source.getClass() != MultiPolygon.class)
                || source.isEmpty() || !ordinary(source)) return 0;
        int count = source.getNumPoints();
        return count <= MAX_COORDINATES ? 3L * count + packedCoordinateCount(source) : 0;
    }

    /** null означает штатный отказ от ускорения; исходный geometry guard остаётся обязательным. */
    public static PreparedRoadCrossings forReadOnlyConstraint(Geometry source) {
        ensureActive();
        if (additionalCoordinateReservation(source) == 0) return null;
        for (Coordinate point : source.getCoordinates()) {
            ensureActive();
            if (!finite(point)) return null;
        }
        if (!source.isValid()) return null;
        ensureActive();
        return new PreparedRoadCrossings(source);
    }

    private PreparedRoadCrossings(Geometry source) {
        Geometry owned = source.copy();
        owned.apply((GeometryComponentFilter) item -> item.setUserData(null));
        this.source = owned;
        bounds = new Envelope(owned.getEnvelopeInternal());
        STRtree tree = new STRtree();
        for (int i = 0; i < owned.getNumGeometries(); i++) {
            Polygon polygon = (Polygon) owned.getGeometryN(i);
            addRing(tree, polygon.getExteriorRing());
            for (int j = 0; j < polygon.getNumInteriorRing(); j++) addRing(tree, polygon.getInteriorRingN(j));
        }
        tree.build();
        root = tree.getRoot();
        materialize(root);
        locator = new IndexedPointInAreaLocator(owned);
        locator.locate(owned.getCoordinate());
        ensureActive();
    }

    /** Вся проверка использует одну принадлежащую подготовке версию полигона. */
    public boolean segmentAllowed(LineString segment, double clearanceM, double minimumAngleDegrees, double extensionM) {
        // Проверяем исходное звено до создания extended: копирование может скрыть custom sequence/type.
        return guard.segmentAllowed(segment, source, clearanceM, minimumAngleDegrees, extensionM,
                supportedQuery(segment) ? this : null);
    }

    /** Только обычный двухточечный отрезок; станции и midpoint повторяют семантику общего guard. */
    public Optional<List<Interval>> crossings(LineString query) {
        ensureActive();
        if (!supportedQuery(query)) return Optional.empty();
        Coordinate a = query.getCoordinateN(0), b = query.getCoordinateN(1);
        double length = a.distance(b);
        if (!finite(a) || !finite(b) || !Double.isFinite(length) || length <= EPSILON_M) return Optional.empty();
        Envelope window = new Envelope(a, b);
        if (!bounds.intersects(window)) return Optional.of(List.of());
        LengthIndexedLine indexed = new LengthIndexedLine(query);
        List<Cut> cuts = new ArrayList<>();
        cuts.add(new Cut(a, indexed.project(a)));
        cuts.add(new Cut(b, indexed.project(b)));
        if (!collect(root, window, a, b, indexed, new RobustLineIntersector(), cuts)) return Optional.empty();
        cuts.sort(Comparator.comparingDouble(cut -> cut.station));
        List<Interval> result = new ArrayList<>();
        for (int i = 1; i < cuts.size(); i++) {
            ensureActive();
            Cut before = cuts.get(i - 1), after = cuts.get(i);
            if (before.point.equals2D(after.point)) continue;
            if (before.station == after.station) return Optional.empty();
            if (before.point.distance(after.point) <= EPSILON_M) continue;
            Coordinate middle = new Coordinate((before.point.x + after.point.x) / 2,
                    (before.point.y + after.point.y) / 2);
            if (!finite(middle) || middle.equals2D(before.point) || middle.equals2D(after.point)) return Optional.empty();
            if (locator.locate(middle) != Location.INTERIOR) continue;
            if (result.isEmpty() || before.station > result.get(result.size() - 1).getEndM() + EPSILON_M) {
                result.add(new Interval(before.station, after.station, 0));
            } else {
                Interval previous = result.remove(result.size() - 1);
                result.add(new Interval(previous.getStartM(), Math.max(previous.getEndM(), after.station), 0));
            }
        }
        return Optional.of(result);
    }

    private static boolean collect(Boundable item, Envelope window, Coordinate a, Coordinate b,
            LengthIndexedLine indexed, RobustLineIntersector intersector, List<Cut> cuts) {
        ensureActive();
        if (!((Envelope) item.getBounds()).intersects(window)) return true;
        if (item instanceof ItemBoundable) {
            Edge edge = (Edge) ((ItemBoundable) item).getItem();
            if (ambiguousParallel(a, b, edge)) return false;
            intersector.computeIntersection(a, b, edge.a, edge.b);
            int count = intersector.getIntersectionNum();
            // Overlay имеет собственное восстановление snapping: совпадающие границы не ускоряем.
            if (count == 2) return false;
            if (count == 0) return true;
            Coordinate hit = intersector.getIntersection(0);
            if (!finite(hit) || (intersector.isProper() && (hit.equals2D(a) || hit.equals2D(b)
                    || hit.equals2D(edge.a) || hit.equals2D(edge.b)))) return false;
            if (new LineSegment(a, b).distance(hit) > EPSILON_M
                    || new LineSegment(edge.a, edge.b).distance(hit) > EPSILON_M) return false;
            double station = indexed.project(hit);
            if (!Double.isFinite(station)) return false;
            cuts.add(new Cut(hit, station));
            return true;
        }
        for (Object child : ((AbstractNode) item).getChildBoundables()) {
            if (!collect((Boundable) child, window, a, b, indexed, intersector, cuts)) return false;
        }
        return true;
    }

    /** Почти совпавшие после UTM-округления прямые требуют того же snapping, что исходный overlay. */
    private static boolean ambiguousParallel(Coordinate a, Coordinate b, Edge edge) {
        double queryLength = a.distance(b), edgeLength = edge.a.distance(edge.b);
        if (!Double.isFinite(edgeLength)) return true;
        if (edgeLength <= EPSILON_M) return false;
        double cross = ((b.x - a.x) / queryLength) * ((edge.b.y - edge.a.y) / edgeLength)
                - ((b.y - a.y) / queryLength) * ((edge.b.x - edge.a.x) / edgeLength);
        if (!Double.isFinite(cross)) return true;
        if (Math.abs(cross) > 1e-10) return false;
        LineSegment query = new LineSegment(a, b);
        double first = query.distancePerpendicular(edge.a), second = query.distancePerpendicular(edge.b);
        return !Double.isFinite(first) || !Double.isFinite(second) || Math.min(first, second) <= EPSILON_M;
    }

    private static void addRing(STRtree tree, LineString ring) {
        for (int i = 1; i < ring.getNumPoints(); i++) {
            ensureActive();
            Coordinate a = new Coordinate(ring.getCoordinateN(i - 1)), b = new Coordinate(ring.getCoordinateN(i));
            tree.insert(new Envelope(a, b), new Edge(a, b));
        }
    }

    private static void materialize(AbstractNode node) {
        ensureActive();
        for (Object child : node.getChildBoundables()) if (child instanceof AbstractNode) materialize((AbstractNode) child);
        node.getBounds();
    }

    private static boolean ordinary(Geometry geometry) {
        Class<?> type = geometry.getClass();
        if (type != Polygon.class && type != MultiPolygon.class && type != LinearRing.class && type != LineString.class) return false;
        GeometryFactory factory = geometry.getFactory();
        Class<?> sequences = factory.getCoordinateSequenceFactory().getClass();
        if (factory.getClass() != GeometryFactory.class || factory.getPrecisionModel().getClass() != PrecisionModel.class
                || factory.getPrecisionModel().getType() != PrecisionModel.FLOATING
                || (sequences != CoordinateArraySequenceFactory.class && sequences != PackedCoordinateSequenceFactory.class)) return false;
        // JTS создаёт midpoint через factory источника; float округляет его иначе, чем locator.
        if (sequences == PackedCoordinateSequenceFactory.class
                && ((PackedCoordinateSequenceFactory) factory.getCoordinateSequenceFactory()).getType()
                        != PackedCoordinateSequenceFactory.DOUBLE) return false;
        if (geometry instanceof LineString) {
            Class<?> sequence = ((LineString) geometry).getCoordinateSequence().getClass();
            if (sequence != CoordinateArraySequence.class && sequence != PackedCoordinateSequence.Double.class) return false;
        }
        if (geometry instanceof Polygon) {
            Polygon polygon = (Polygon) geometry;
            if (!ordinary(polygon.getExteriorRing())) return false;
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) if (!ordinary(polygon.getInteriorRingN(i))) return false;
        } else if (geometry instanceof GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) if (!ordinary(geometry.getGeometryN(i))) return false;
        }
        return true;
    }

    private static boolean supportedQuery(LineString query) {
        return query != null && query.getClass() == LineString.class && query.getNumPoints() == 2 && ordinary(query);
    }

    /** Locator удерживает распакованные Coordinate отдельно от double[] собственной копии. */
    private static long packedCoordinateCount(Geometry geometry) {
        if (geometry instanceof LineString) {
            LineString line = (LineString) geometry;
            return line.getCoordinateSequence() instanceof PackedCoordinateSequence ? line.getNumPoints() : 0;
        }
        long result = 0;
        if (geometry instanceof Polygon) {
            Polygon polygon = (Polygon) geometry;
            result = packedCoordinateCount(polygon.getExteriorRing());
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) result += packedCoordinateCount(polygon.getInteriorRingN(i));
        } else {
            for (int i = 0; i < geometry.getNumGeometries(); i++) result += packedCoordinateCount(geometry.getGeometryN(i));
        }
        return result;
    }

    private static boolean finite(Coordinate point) { return Double.isFinite(point.x) && Double.isFinite(point.y); }
    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Road crossing preparation cancelled");
    }
    private static final class Cut {
        private final Coordinate point;
        private final double station;
        private Cut(Coordinate point, double station) { this.point = new Coordinate(point); this.station = station; }
    }
    private static final class Edge {
        private final Coordinate a, b;
        private Edge(Coordinate a, Coordinate b) { this.a = a; this.b = b; }
    }
}
