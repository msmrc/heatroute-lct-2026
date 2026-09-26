package ru.lct.heatroute.domain.routing;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryComponentFilter;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.AbstractNode;
import org.locationtech.jts.index.strtree.Boundable;
import org.locationtech.jts.index.strtree.ItemBoundable;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * Индексирует границы полигона для точной проверки двухточечных отрезков, включая касания.
 * Владеет копией препятствия; не хранит запросы и маршруты. Остальные случаи проверяет JTS.
 */
final class PreparedSegmentIntersection {
    // Ограничивает дополнительный индекс; большие геометрии сохраняют исходный JTS-путь.
    private static final int MAX_INDEX_COORDINATES = 16_384;
    private final PreparedGeometry fallback;
    private final Envelope envelope;
    private final IndexedPointInAreaLocator locator;
    private final AbstractNode root;
    private final boolean indexed;

    PreparedSegmentIntersection(Geometry source) {
        this(source, false);
    }

    /**
     * Консервативный резерв явных координат: собственная копия N + два конца каждого ребра ≤ 2N.
     * Без копирования, проверки validity и построения индекса; не учитывает байты/JTS-объекты.
     */
    static long additionalCoordinateReservation(Geometry source) {
        if (!(source instanceof Polygon || source instanceof MultiPolygon) || source.isEmpty()) return 0;
        int coordinates = source.getNumPoints();
        return coordinates <= MAX_INDEX_COORDINATES ? 3L * coordinates : 0;
    }

    /**
     * Для принадлежащего Constraint read-only препятствия: проверяет пригодность до копирования.
     * null означает использование уже существующего prepared fallback без второй копии геометрии.
     */
    static PreparedSegmentIntersection forReadOnlyConstraint(Geometry source) {
        ensureActive();
        if (additionalCoordinateReservation(source) == 0) return null;
        boolean supported = finite(source) && source.isValid();
        ensureActive();
        return supported ? new PreparedSegmentIntersection(source, true) : null;
    }

    private PreparedSegmentIntersection(Geometry source, boolean validated) {
        ensureActive();
        Geometry owned = Objects.requireNonNull(source, "geometry").copy();
        owned.apply((GeometryComponentFilter) geometry -> geometry.setUserData(null));
        fallback = PreparedGeometryFactory.prepare(owned);
        envelope = new Envelope(owned.getEnvelopeInternal());
        indexed = validated || ((owned instanceof Polygon || owned instanceof MultiPolygon)
                && owned.getNumPoints() <= MAX_INDEX_COORDINATES && finite(owned) && owned.isValid());
        ensureActive();
        if (!indexed || owned.isEmpty()) {
            locator = null;
            root = null;
            return;
        }
        STRtree tree = new STRtree();
        for (int i = 0; i < owned.getNumGeometries(); i++) {
            ensureActive();
            Polygon polygon = (Polygon) owned.getGeometryN(i);
            addRing(tree, polygon.getExteriorRing());
            for (int j = 0; j < polygon.getNumInteriorRing(); j++) addRing(tree, polygon.getInteriorRingN(j));
        }
        tree.build();
        root = tree.getRoot();
        materializeBounds(root);
        locator = new IndexedPointInAreaLocator(owned);
        // Завершаем ленивую подготовку до публикации: последующие запросы не меняют индекс.
        locator.locate(owned.getCoordinate());
        ensureActive();
    }

    boolean intersects(LineString query) {
        ensureActive();
        Objects.requireNonNull(query, "query");
        if (!indexed || query.getNumPoints() != 2) return fallback.intersects(query);
        CoordinateSequence points = query.getCoordinateSequence();
        Coordinate start = new Coordinate(points.getX(0), points.getY(0));
        Coordinate end = new Coordinate(points.getX(1), points.getY(1));
        if (!finite(start) || !finite(end)) return fallback.intersects(query);
        Envelope bounds = new Envelope(start, end);
        if (!envelope.intersects(bounds) || root == null) return false;
        // Если начало снаружи, вход в полигон/касание возможны только через границу любого кольца.
        if (locator.locate(start) != Location.EXTERIOR) return true;
        return intersectsBoundary(root, bounds, start, end);
    }

    boolean usesIndex() { return indexed; }

    private static boolean intersectsBoundary(Boundable item, Envelope bounds,
            Coordinate start, Coordinate end) {
        if (!((Envelope) item.getBounds()).intersects(bounds)) return false;
        if (item instanceof ItemBoundable) {
            RingEdge edge = (RingEdge) ((ItemBoundable) item).getItem();
            return intersectsEdge(start, end, edge);
        }
        ensureActive();
        // Ранний выход по первому пересечению, без списка всех рёбер в envelope запроса.
        for (Object child : ((AbstractNode) item).getChildBoundables()) {
            if (intersectsBoundary((Boundable) child, bounds, start, end)) return true;
        }
        return false;
    }

    /**
     * После пересечения envelopes достаточно точных знаков ориентации JTS.
     * Координата пересечения не нужна: касания и коллинеарное наложение также запрещены.
     */
    private static boolean intersectsEdge(Coordinate start, Coordinate end, RingEdge edge) {
        int first = Orientation.index(start, end, edge.start);
        int second = Orientation.index(start, end, edge.end);
        if (sameNonzeroSign(first, second)) return false;
        int third = Orientation.index(edge.start, edge.end, start);
        int fourth = Orientation.index(edge.start, edge.end, end);
        if (sameNonzeroSign(third, fourth)) return false;
        if (first == 0 && second == 0 && third == 0 && fourth == 0) {
            // При underflow finite координат нулевые знаки ещё не доказывают коллинеарность.
            // Сохраняем точный прежний JTS-ответ для этой редкой ветки и граничных наложений.
            RobustLineIntersector fallback = new RobustLineIntersector();
            fallback.computeIntersection(start, end, edge.start, edge.end);
            return fallback.hasIntersection();
        }
        return true;
    }

    private static boolean sameNonzeroSign(int first, int second) {
        return first > 0 && second > 0 || first < 0 && second < 0;
    }

    private static void addRing(STRtree tree, LineString ring) {
        CoordinateSequence points = ring.getCoordinateSequence();
        for (int i = 1; i < points.size(); i++) {
            if ((i & 1023) == 0) ensureActive();
            Coordinate start = points.getCoordinateCopy(i - 1), end = points.getCoordinateCopy(i);
            tree.insert(new Envelope(start, end), new RingEdge(start, end));
        }
    }

    private static void materializeBounds(AbstractNode node) {
        ensureActive();
        for (Object child : node.getChildBoundables()) {
            if (child instanceof AbstractNode) materializeBounds((AbstractNode) child);
        }
        node.getBounds();
    }

    private static boolean finite(Geometry geometry) {
        Coordinate[] points = geometry.getCoordinates();
        for (int i = 0; i < points.length; i++) {
            if ((i & 1023) == 0) ensureActive();
            if (!finite(points[i])) return false;
        }
        return true;
    }

    private static boolean finite(Coordinate point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Segment intersection cancelled");
    }

    private static final class RingEdge {
        private final Coordinate start;
        private final Coordinate end;
        private RingEdge(Coordinate start, Coordinate end) { this.start = start; this.end = end; }
    }
}
