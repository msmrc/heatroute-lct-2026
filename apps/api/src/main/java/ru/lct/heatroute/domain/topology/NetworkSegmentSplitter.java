package ru.lct.heatroute.domain.topology;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;

@Component
public class NetworkSegmentSplitter {
    private static final double ENDPOINT_TOLERANCE_M = 0.01;

    public SegmentSplit split(LineString segment, Point requestedTieInPoint) {
        if (segment == null || requestedTieInPoint == null || segment.isEmpty() || requestedTieInPoint.isEmpty()) {
            throw new IllegalArgumentException("segment and tie-in point are required");
        }
        LengthIndexedLine indexed = new LengthIndexedLine(segment);
        double index = indexed.project(requestedTieInPoint.getCoordinate());
        double end = indexed.getEndIndex();
        if (index <= ENDPOINT_TOLERANCE_M || end - index <= ENDPOINT_TOLERANCE_M) {
            throw new IllegalArgumentException("tie-in point must lie inside the network segment");
        }
        Geometry first = indexed.extractLine(indexed.getStartIndex(), index);
        Geometry second = indexed.extractLine(index, end);
        if (!(first instanceof LineString) || !(second instanceof LineString)) {
            throw new IllegalStateException("segment split did not produce two LineStrings");
        }
        Point snapped = segment.getFactory().createPoint(indexed.extractPoint(index));
        snapped.setSRID(segment.getSRID());
        return new SegmentSplit((LineString) first, (LineString) second, snapped);
    }
}
