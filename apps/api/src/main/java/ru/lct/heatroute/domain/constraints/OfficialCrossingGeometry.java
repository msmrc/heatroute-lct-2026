package ru.lct.heatroute.domain.constraints;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;

@Component
public class OfficialCrossingGeometry {
    /**
     * Нормальное пересечение публикуется как 90°. После поворота и округления метрических
     * координат до миллиметра оно может отличаться на сотые доли градуса, поэтому для 90°
     * действует допуск 0,1°, а у нижних табличных границ остаётся только численная погрешность.
     */
    public static boolean satisfiesMinimumAngle(double actualDegrees, double minimumDegrees) {
        double tolerance = minimumDegrees >= 89.9 ? 0.1 : 1e-7;
        return actualDegrees + tolerance >= minimumDegrees;
    }

    public LineString specialSegment(LineString route, Geometry crossedObject, double extensionM) {
        if (route == null || crossedObject == null || route.isEmpty() || crossedObject.isEmpty()) {
            throw new IllegalArgumentException("route and crossed object are required");
        }
        if (extensionM < 0) {
            throw new IllegalArgumentException("special extension must be non-negative");
        }
        Geometry intersection = route.intersection(crossedObject);
        if (intersection.isEmpty()) {
            throw new IllegalArgumentException("route does not cross the specified object");
        }
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (org.locationtech.jts.geom.Coordinate coordinate : intersection.getCoordinates()) {
            double index = indexed.project(coordinate);
            minimum = Math.min(minimum, index);
            maximum = Math.max(maximum, index);
        }
        double start = Math.max(indexed.getStartIndex(), minimum - extensionM);
        double end = Math.min(indexed.getEndIndex(), maximum + extensionM);
        Geometry extracted = indexed.extractLine(start, end);
        if (!(extracted instanceof LineString)) {
            throw new IllegalStateException("special crossing extraction did not produce a LineString");
        }
        return (LineString) extracted;
    }

    public double acuteCrossingAngleDegrees(LineString route, LineString crossedLine) {
        Geometry intersection = route.intersection(crossedLine);
        if (intersection.isEmpty()) {
            throw new IllegalArgumentException("lines do not cross");
        }
        org.locationtech.jts.geom.Coordinate crossing = intersection.getCoordinate();
        double routeAngle = localAngle(route, crossing);
        double crossedAngle = localAngle(crossedLine, crossing);
        double difference = Math.abs(Math.toDegrees(routeAngle - crossedAngle)) % 180.0;
        return difference > 90.0 ? 180.0 - difference : difference;
    }

    public boolean meetsMinimumAngle(LineString route, LineString crossedLine, double minimumDegrees) {
        return satisfiesMinimumAngle(acuteCrossingAngleDegrees(route, crossedLine), minimumDegrees);
    }

    private double localAngle(LineString line, org.locationtech.jts.geom.Coordinate crossing) {
        double bestDistance = Double.POSITIVE_INFINITY;
        double result = 0;
        for (int index = 0; index < line.getNumPoints() - 1; index++) {
            org.locationtech.jts.geom.Coordinate start = line.getCoordinateN(index);
            org.locationtech.jts.geom.Coordinate end = line.getCoordinateN(index + 1);
            org.locationtech.jts.geom.LineSegment segment = new org.locationtech.jts.geom.LineSegment(start, end);
            double distance = segment.distance(crossing);
            if (distance < bestDistance) {
                bestDistance = distance;
                result = Math.atan2(end.y - start.y, end.x - start.x);
            }
        }
        return result;
    }
}
