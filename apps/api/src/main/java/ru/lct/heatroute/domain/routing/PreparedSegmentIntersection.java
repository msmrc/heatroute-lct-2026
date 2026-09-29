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
    private final BoundaryIndex boundaryIndex;
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
            boundaryIndex = null;
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
        boundaryIndex = BoundaryIndex.flatten(tree.getRoot());
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
        if (!envelope.intersects(bounds) || boundaryIndex == null) return false;
        // Если начало снаружи, вход в полигон/касание возможны только через границу любого кольца.
        if (locator.locate(start) != Location.EXTERIOR) return true;
        return boundaryIndex.intersects(bounds, start, end);
    }

    boolean usesIndex() { return indexed; }

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

    /**
     * Неизменяемый preorder-снимок STRtree. В hot path нет рекурсии, Boundable и ленивых bounds JTS;
     * skipAfterSubtree сохраняет исходный DFS-порядок и позволяет пропустить целое непересекающееся поддерево.
     */
    private static final class BoundaryIndex {
        // Координаты в этом диапазоне дают representable разности ≥ 2^-452 и произведения
        // далеко от underflow/overflow. Mixed-scale значения отключают только shortcut.
        private static final double MIN_SAFE_ORIENTATION_COORDINATE = 0x1.0p-400;
        private static final double MAX_SAFE_ORIENTATION_COORDINATE = 0x1.0p400;
        // Две ориентации окупаются при отсечении большого поддерева, но не десятка листьев.
        private static final int MIN_LINE_SHORTCUT_ENTRIES = 64;
        private final double[] minX;
        private final double[] minY;
        private final double[] maxX;
        private final double[] maxY;
        private final int[] skipAfterSubtree;
        private final RingEdge[] edges;
        private final boolean[] lineShortcutSafe;

        private BoundaryIndex(int entries) {
            minX = new double[entries];
            minY = new double[entries];
            maxX = new double[entries];
            maxY = new double[entries];
            skipAfterSubtree = new int[entries];
            edges = new RingEdge[entries];
            lineShortcutSafe = new boolean[entries];
        }

        private static BoundaryIndex flatten(AbstractNode root) {
            int entries = entryCount(root);
            BoundaryIndex index = new BoundaryIndex(entries);
            int next = write(root, index, 0);
            if (next != entries) throw new IllegalStateException("Incomplete STRtree flattening");
            return index;
        }

        private static int entryCount(Object boundable) {
            ensureActive();
            if (boundable instanceof ItemBoundable) return 1;
            int count = 1;
            AbstractNode node = (AbstractNode) boundable;
            for (Object child : node.getChildBoundables()) count += entryCount(child);
            return count;
        }

        private static int write(Object boundable, BoundaryIndex index, int entry) {
            ensureActive();
            Envelope bounds = (Envelope) ((boundable instanceof ItemBoundable)
                    ? ((ItemBoundable) boundable).getBounds() : ((AbstractNode) boundable).getBounds());
            index.minX[entry] = bounds.getMinX();
            index.minY[entry] = bounds.getMinY();
            index.maxX[entry] = bounds.getMaxX();
            index.maxY[entry] = bounds.getMaxY();
            int next = entry + 1;
            if (boundable instanceof ItemBoundable) {
                index.edges[entry] = (RingEdge) ((ItemBoundable) boundable).getItem();
            } else {
                for (Object child : ((AbstractNode) boundable).getChildBoundables()) {
                    next = write(child, index, next);
                }
            }
            index.skipAfterSubtree[entry] = next;
            index.lineShortcutSafe[entry] = next - entry >= MIN_LINE_SHORTCUT_ENTRIES
                    && hasSafeCoordinates(index.minX[entry], index.minY[entry], index.maxX[entry], index.maxY[entry]);
            return next;
        }

        private boolean intersects(Envelope bounds, Coordinate start, Coordinate end) {
            // Ранний выход по первому пересечению без списков кандидатов или обхода JTS Boundable.
            boolean queryLineShortcutSafe = edges.length >= MIN_LINE_SHORTCUT_ENTRIES
                    && hasSafeCoordinates(start.x, start.y, end.x, end.y);
            Coordinate corner = queryLineShortcutSafe ? new Coordinate() : null;
            for (int entry = 0; entry < edges.length;) {
                if (!intersectsEnvelope(entry, bounds)) {
                    entry = skipAfterSubtree[entry];
                    continue;
                }
                RingEdge edge = edges[entry];
                if (edge == null) {
                    if (queryLineShortcutSafe && lineShortcutSafe[entry]
                            && strictlyOnOneSideOfSegmentLine(entry, start, end, corner)) {
                        entry = skipAfterSubtree[entry];
                        continue;
                    }
                    ensureActive();
                    entry++;
                } else {
                    if (intersectsEdge(start, end, edge)) return true;
                    entry++;
                }
            }
            return false;
        }

        private boolean intersectsEnvelope(int entry, Envelope bounds) {
            return minX[entry] <= bounds.getMaxX() && maxX[entry] >= bounds.getMinX()
                    && minY[entry] <= bounds.getMaxY() && maxY[entry] >= bounds.getMinY();
        }

        private static boolean hasSafeCoordinates(double first, double second, double third, double fourth) {
            return hasSafeCoordinate(first) && hasSafeCoordinate(second)
                    && hasSafeCoordinate(third) && hasSafeCoordinate(fourth);
        }

        private static boolean hasSafeCoordinate(double value) {
            if (value == 0.0) return true;
            double magnitude = Math.abs(value);
            return magnitude >= MIN_SAFE_ORIENTATION_COORDINATE
                    && magnitude <= MAX_SAFE_ORIENTATION_COORDINATE;
        }

        /**
         * Прямоугольник выпуклый: два support-угла дают min/max знаки affine orientation.
         * Если они строго одинаковы, ни одно ребро поддерева не пересечёт отрезок. Нулевой знак
         * отключает отсечение, поэтому касания и крайние finite координаты сохраняют exact path.
         */
        private boolean strictlyOnOneSideOfSegmentLine(
                int entry, Coordinate start, Coordinate end, Coordinate corner) {
            // sign(dx) chooses y-support; sign(dy) has opposite x-support in cross product.
            corner.x = end.y > start.y ? maxX[entry] : minX[entry];
            corner.y = end.x > start.x ? minY[entry] : maxY[entry];
            int minimumSide = Orientation.index(start, end, corner);
            if (minimumSide == 0) return false;
            corner.x = end.y > start.y ? minX[entry] : maxX[entry];
            corner.y = end.x > start.x ? maxY[entry] : minY[entry];
            return sameNonzeroSign(minimumSide, Orientation.index(start, end, corner));
        }
    }
}
