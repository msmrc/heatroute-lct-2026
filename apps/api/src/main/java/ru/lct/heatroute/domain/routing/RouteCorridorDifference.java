package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Достаточный сертификат разных обходов, не полный решатель homotopy и не метрический порог. */
final class RouteCorridorDifference {
    // Ограничивает только доказательство дополнительной альтернативы; лучший S никогда не теряется.
    private static final long MAX_CERTIFICATE_WORK = 1_000_000;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private final STRtree obstacles = new STRtree();

    RouteCorridorDifference(List<ImportedOfficialFeature> features) {
        for (ImportedOfficialFeature feature : features) {
            DistinctRouteAlternatives.ensureActive();
            if (feature != null && ("restriction".equals(feature.getObjectType())
                    || "oks_existing".equals(feature.getObjectType()) || "oks_future".equals(feature.getObjectType()))) {
                add(feature.getMetricGeometry());
            }
        }
        obstacles.build();
    }

    private void add(Geometry geometry) {
        DistinctRouteAlternatives.ensureActive();
        if (geometry == null || geometry.isEmpty()) return;
        if (geometry instanceof Polygon) obstacles.insert(geometry.getEnvelopeInternal(), new Obstacle((Polygon) geometry));
        else if (geometry instanceof GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) add(geometry.getGeometryN(i));
        }
    }

    boolean provesDifferent(RouteAlternativeFamily first, RouteAlternativeFamily second) {
        long remaining = MAX_CERTIFICATE_WORK;
        for (Map.Entry<String, LineString> entry : first.paths().entrySet()) {
            DistinctRouteAlternatives.ensureActive();
            LineString other = second.paths().get(entry.getKey());
            if (other == null) continue;
            List<Coordinate> ring = new ArrayList<>();
            for (Coordinate coordinate : entry.getValue().getCoordinates()) ring.add(coordinate);
            Coordinate[] reverse = other.getCoordinates();
            for (int i = reverse.length - 1; i >= 0; i--) ring.add(reverse[i]);
            ring.add(ring.get(0));
            LineString walk = GEOMETRY.createLineString(ring.toArray(new Coordinate[0]));
            for (Object value : obstacles.query(walk.getEnvelopeInternal())) {
                DistinctRouteAlternatives.ensureActive();
                Obstacle obstacle = (Obstacle) value;
                remaining -= ring.size() + obstacle.polygon.getNumPoints();
                if (remaining < 0) return false;
                // Проверяются также замыкающие отрезки между сдвинутыми концами: иначе winding ложен.
                if (!obstacle.prepared().intersects(walk) && winding(ring, obstacle.interior) != 0) return true;
            }
        }
        return false;
    }

    private int winding(List<Coordinate> ring, Coordinate point) {
        int result = 0;
        for (int i = 1; i < ring.size(); i++) {
            DistinctRouteAlternatives.ensureActive();
            Coordinate a = ring.get(i - 1), b = ring.get(i);
            if (a.y <= point.y && b.y > point.y && Orientation.index(a, b, point) > 0) result++;
            else if (a.y > point.y && b.y <= point.y && Orientation.index(a, b, point) < 0) result--;
        }
        return result;
    }

    private static final class Obstacle {
        private final Polygon polygon;
        private final Coordinate interior;
        private PreparedGeometry prepared;

        private Obstacle(Polygon polygon) {
            this.polygon = polygon;
            this.interior = polygon.getInteriorPoint().getCoordinate();
        }

        private PreparedGeometry prepared() {
            if (prepared == null) prepared = PreparedGeometryFactory.prepare(polygon);
            return prepared;
        }
    }
}
