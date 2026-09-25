package ru.lct.heatroute.domain.topology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatroute.domain.routing.RouteNode;

/**
 * Считает примыкающие существующие участки по геометрии EPSG:32637: конец линии даёт один,
 * прохождение через камеру — два. Внутренние вершины полилинии не создают лишних примыканий.
 */
public final class ExistingNetworkIncidence {
    private static final double TOLERANCE_M = 0.01;
    private final STRtree index = new STRtree();

    public ExistingNetworkIncidence(Collection<ImportedOfficialFeature> features) {
        for (ImportedOfficialFeature feature : features) {
            ensureActive();
            if ("heat_network".equals(feature.getObjectType())
                    && feature.getMetricGeometry() instanceof LineString
                    && !feature.getMetricGeometry().isEmpty()) {
                index.insert(feature.getMetricGeometry().getEnvelopeInternal(), feature.getMetricGeometry());
            }
        }
        index.build();
    }

    public int countAt(Coordinate coordinate) {
        ensureActive();
        Envelope window = new Envelope(coordinate);
        window.expandBy(TOLERANCE_M);
        @SuppressWarnings("unchecked")
        List<LineString> nearby = index.query(window);
        int result = 0;
        for (LineString line : nearby) {
            ensureActive();
            Point point = line.getFactory().createPoint(coordinate);
            if (line.getLength() == 0 || line.distance(point) > TOLERANCE_M) continue;
            int lineCount = 0;
            for (int i = 1; i < line.getNumPoints(); i++) {
                Coordinate before = line.getCoordinateN(i - 1), after = line.getCoordinateN(i);
                if (before.equals2D(after)) continue;
                boolean startsAtChamber = before.distance(coordinate) <= TOLERANCE_M;
                boolean endsAtChamber = after.distance(coordinate) <= TOLERANCE_M;
                // Короткое техническое звено внутри snap-зоны не добавляет новый выход из камеры.
                if (startsAtChamber && endsAtChamber) continue;
                if (startsAtChamber || endsAtChamber) lineCount++;
                else if (new LineSegment(before, after).distance(coordinate) <= TOLERANCE_M) lineCount += 2;
            }
            if (lineCount == 0) {
                // Целиком короткий положительный участок всё равно занимает один конец или два прохода.
                boolean endpoint = line.getCoordinateN(0).distance(coordinate) <= TOLERANCE_M
                        || line.getCoordinateN(line.getNumPoints() - 1).distance(coordinate) <= TOLERANCE_M;
                lineCount = endpoint && !line.isClosed() ? 1 : 2;
            }
            result += lineCount;
        }
        return result;
    }

    public Map<String, Integer> countsByChamber(Collection<ImportedOfficialFeature> features) {
        Map<String, Integer> result = new HashMap<>();
        for (ImportedOfficialFeature feature : features) {
            if ("heat_chamber".equals(feature.getObjectType())) {
                result.put(feature.getFeatureId(), countAt(feature.getMetricGeometry().getCoordinate()));
            }
        }
        return result;
    }

    /**
     * Пересчитывает корни, для которых доступна исходная сеть. Неполные исторические входы
     * без опорной геометрии сохраняют прежний счётчик; исходный RouteNode не изменяется.
     */
    public RouteNode resolved(RouteNode node) {
        if (!node.isRoot() || !node.isChamber()) return node;
        int count = countAt(node.getCoordinate().toCoordinate());
        if (count == 0 || count == node.getBaseIncidentSections()) return node;
        return new RouteNode(node.getId(), node.getNodeType(), node.getCoordinate(), node.isChamber(), node.isRoot(),
                count, node.getTargetId(), node.getExistingIncidentDiameter());
    }

    public List<RouteNode> resolved(List<RouteNode> nodes) {
        List<RouteNode> result = new ArrayList<>(nodes.size());
        for (RouteNode node : nodes) result.add(resolved(node));
        return result;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Existing incidence lookup cancelled");
    }
}
