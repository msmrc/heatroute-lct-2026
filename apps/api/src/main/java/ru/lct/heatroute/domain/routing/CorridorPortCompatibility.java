package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

/**
 * Находит несовместимые вводы выбранного коридорного дерева для повторного выбора портов.
 * Проверяет фактические полилинии порт → потребитель, не меняя входы; независимый итоговый
 * валидатор остаётся обязательным. Между вызовами геометрия не сохраняется.
 */
final class CorridorPortCompatibility {
    private static final int MAX_STUBS = 64;
    private static final int MAX_GRID_SECTIONS = 20_000;
    // Тот же позиционный допуск общего узла, что в OfficialRouteValidator; не допуск наложения.
    private static final double PORT_TOLERANCE_M = 0.01;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    /**
     * Возвращает {leafA, leafB}, {leaf, -1} для self/grid либо null. Порядок — возрастание leaf,
     * self/grid перед парами; оба map должны иметь одинаковые неотрицательные ключи.
     * Неверный ввод, более 64 вводов или 20000 участков сетки дают IllegalArgumentException.
     */
    static int[] firstConflict(Map<Integer, RoutePath> pathsByVirtualLeaf,
            Map<Integer, Integer> attachmentGridIndexByLeaf, List<LineString> selectedGridSegments) {
        ensureActive();
        require(pathsByVirtualLeaf != null && attachmentGridIndexByLeaf != null
                && selectedGridSegments != null, "Paths, attachments and grid sections are required");
        require(pathsByVirtualLeaf.size() <= MAX_STUBS && attachmentGridIndexByLeaf.size() <= MAX_STUBS,
                "At most 64 terminal stubs are supported");
        require(selectedGridSegments.size() <= MAX_GRID_SECTIONS, "At most 20000 grid sections are supported");
        require(pathsByVirtualLeaf.keySet().equals(attachmentGridIndexByLeaf.keySet()),
                "Every path requires exactly one attachment");
        List<Integer> leaves = new ArrayList<>(pathsByVirtualLeaf.keySet());
        for (Integer leaf : leaves) {
            ensureActive();
            require(leaf != null && leaf >= 0, "Virtual leaf index must be nonnegative");
            Integer port = attachmentGridIndexByLeaf.get(leaf);
            require(port != null && port >= 0, "Attachment grid index must be nonnegative");
        }
        Collections.sort(leaves);
        List<Stub> stubs = new ArrayList<>(leaves.size());
        for (int leaf : leaves) {
            RoutePath path = pathsByVirtualLeaf.get(leaf);
            require(path != null && path.coordinates().size() >= 2, "A stub requires at least two coordinates");
            Coordinate[] coordinates = new Coordinate[path.coordinates().size()];
            for (int i = 0; i < coordinates.length; i++) {
                ensureActive();
                Coordinate coordinate = path.coordinates().get(i);
                require(coordinate != null && Double.isFinite(coordinate.x) && Double.isFinite(coordinate.y),
                        "Stub XY coordinates must be finite");
                coordinates[i] = new Coordinate(coordinate);
            }
            LineString line = GEOMETRY_FACTORY.createLineString(coordinates);
            requirePositiveLength(line);
            stubs.add(new Stub(leaf, attachmentGridIndexByLeaf.get(leaf), line));
        }
        List<Envelope> gridEnvelopes = new ArrayList<>(selectedGridSegments.size());
        for (LineString grid : selectedGridSegments) {
            ensureActive();
            require(grid != null && grid.getNumPoints() >= 2, "A grid section requires at least two coordinates");
            CoordinateSequence coordinates = grid.getCoordinateSequence();
            for (int i = 0; i < coordinates.size(); i++) {
                ensureActive();
                require(Double.isFinite(coordinates.getX(i)) && Double.isFinite(coordinates.getY(i)),
                        "Grid XY coordinates must be finite");
            }
            requirePositiveLength(grid);
            gridEnvelopes.add(grid.getEnvelopeInternal());
        }
        for (int i = 0; i < stubs.size(); i++) {
            Stub stub = stubs.get(i);
            ensureActive();
            boolean selfConflict = stub.line.isClosed() || !stub.line.isSimple();
            ensureActive();
            if (selfConflict) return new int[] {stub.leaf, -1};
            for (int gridIndex = 0; gridIndex < selectedGridSegments.size(); gridIndex++) {
                ensureActive();
                if (!stub.envelope.intersects(gridEnvelopes.get(gridIndex))) continue;
                Geometry intersection = stub.line.intersection(selectedGridSegments.get(gridIndex));
                ensureActive();
                if (!intersection.isEmpty() && !onlyPortPoint(intersection, stub.start)) {
                    return new int[] {stub.leaf, -1};
                }
            }
            for (int j = i + 1; j < stubs.size(); j++) {
                ensureActive();
                Stub other = stubs.get(j);
                if (!stub.envelope.intersects(other.envelope)) continue;
                Geometry intersection = stub.line.intersection(other.line);
                ensureActive();
                if (!intersection.isEmpty() && (stub.port != other.port
                        || !onlyPortPoint(intersection, stub.start) || !onlyPortPoint(intersection, other.start))) {
                    return new int[] {stub.leaf, other.leaf};
                }
            }
        }
        ensureActive();
        return firstDeflectionConflict(stubs, selectedGridSegments);
    }

    /** Пересечения имеют прежний приоритет; затем проверяем ввод и продолжение без камеры. */
    private static int[] firstDeflectionConflict(List<Stub> stubs, List<LineString> grid) {
        for (Stub stub : stubs) {
            ensureActive();
            List<RouteCoordinate> points = new ArrayList<>();
            for (Coordinate point : stub.line.getCoordinates()) points.add(new RouteCoordinate(point.x, point.y));
            if (!OfficialRouteDeflectionRules.validatePolyline("port:" + stub.leaf, points).getIssues().isEmpty()) {
                return new int[] {stub.leaf, -1};
            }
            List<Stub> attached = stubs.stream().filter(other -> other.port == stub.port).collect(Collectors.toList());
            if (attached.size() > 2) continue;
            Coordinate first = endpointRay(stub.line, false);
            List<Coordinate> rays = new ArrayList<>();
            int otherLeaf = -1;
            for (Stub other : attached) {
                if (other == stub) continue;
                rays.add(endpointRay(other.line, false));
                otherLeaf = other.leaf;
            }
            for (LineString section : grid) {
                ensureActive();
                if (stub.start.distance(section.getCoordinateN(0)) <= PORT_TOLERANCE_M) {
                    rays.add(endpointRay(section, false));
                } else if (stub.start.distance(section.getCoordinateN(section.getNumPoints() - 1)) <= PORT_TOLERANCE_M) {
                    rays.add(endpointRay(section, true));
                }
                if (rays.size() > 1) break; // Степень >=3: углы между ветвями камеры не ограничены этим правилом.
            }
            if (rays.size() == 1 && !OfficialRouteDeflectionRules.allowsTurn(
                    -first.x, -first.y, rays.get(0).x, rays.get(0).y)) {
                return new int[] {stub.leaf, otherLeaf};
            }
        }
        return null;
    }

    private static Coordinate endpointRay(LineString line, boolean reversed) {
        int start = reversed ? line.getNumPoints() - 1 : 0;
        int step = reversed ? -1 : 1;
        Coordinate origin = line.getCoordinateN(start);
        for (int i = start + step; i >= 0 && i < line.getNumPoints(); i += step) {
            ensureActive();
            Coordinate point = line.getCoordinateN(i);
            if (!origin.equals2D(point)) return new Coordinate(point.x - origin.x, point.y - origin.y);
        }
        throw new IllegalArgumentException("Positive-length section has no endpoint direction");
    }

    private static boolean onlyPortPoint(Geometry intersection, Coordinate port) {
        // Даже субмиллиметровое линейное наложение и несколько отдельных касаний запрещены.
        return intersection instanceof Point && intersection.getCoordinate().distance(port) <= PORT_TOLERANCE_M;
    }

    private static void requirePositiveLength(LineString line) {
        double length = line.getLength();
        require(Double.isFinite(length) && length > 0, "Geometry length must be finite and positive");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Port compatibility check cancelled");
    }

    private static final class Stub {
        private final int leaf;
        private final int port;
        private final LineString line;
        private final Coordinate start;
        private final Envelope envelope;

        private Stub(int leaf, int port, LineString line) {
            this.leaf = leaf;
            this.port = port;
            this.line = line;
            this.start = line.getCoordinateN(0);
            this.envelope = line.getEnvelopeInternal();
        }
    }
}
