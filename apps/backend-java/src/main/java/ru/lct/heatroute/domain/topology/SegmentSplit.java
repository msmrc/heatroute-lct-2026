package ru.lct.heatroute.domain.topology;

import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

public class SegmentSplit {
    private final LineString firstPart;
    private final LineString secondPart;
    private final Point tieInPoint;

    public SegmentSplit(LineString firstPart, LineString secondPart, Point tieInPoint) {
        this.firstPart = firstPart;
        this.secondPart = secondPart;
        this.tieInPoint = tieInPoint;
    }

    public LineString getFirstPart() { return firstPart; }
    public LineString getSecondPart() { return secondPart; }
    public Point getTieInPoint() { return tieInPoint; }
}
