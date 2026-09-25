package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.linemerge.LineMerger;

/** Разрешает только начальный прямой контакт ветвей в заранее подтверждённом общем узле. */
final class JoinedRouteContact {
    private final LineString source;
    private final Coordinate joint;
    private final PreparedGeometry protectedRemainder;

    private JoinedRouteContact(LineString source, Coordinate joint, Geometry remainder) {
        this.source = source;
        this.joint = new Coordinate(joint);
        this.protectedRemainder = remainder == null ? null : PreparedGeometryFactory.prepare(remainder);
    }

    /** Source принадлежит новому avoidance-контексту и после создания не изменяется. */
    static JoinedRouteContact create(LineString source, Coordinate joint, double bufferM) {
        ensureActive();
        boolean forward = source.getCoordinateN(0).equals2D(joint);
        if (!forward && !source.getCoordinateN(source.getNumPoints() - 1).equals2D(joint)) return null;
        if (!source.isSimple()) return null;
        List<Coordinate> oriented = oriented(source, forward);
        int runEnd = straightRunEnd(oriented);
        if (runEnd < 1) return null;
        Geometry remainder = runEnd == oriented.size() - 1 ? null : source.getFactory().createLineString(
                oriented.subList(runEnd, oriented.size()).toArray(Coordinate[]::new)).buffer(bufferM, 2);
        return new JoinedRouteContact(source, joint, remainder);
    }

    boolean permitsPoint(Point point) {
        ensureActive();
        return point.getCoordinate().equals2D(joint)
                && (protectedRemainder == null || !protectedRemainder.covers(point));
    }

    boolean permitsContact(LineString candidate, Geometry blocked) {
        ensureActive();
        boolean forward = candidate.getCoordinateN(0).equals2D(joint);
        if (!forward && !candidate.getCoordinateN(candidate.getNumPoints() - 1).equals2D(joint)) return false;
        if (!candidate.isSimple() || protectedRemainder != null && protectedRemainder.intersects(candidate)) return false;
        Geometry actualContact = source.intersection(candidate);
        if (!(actualContact instanceof Point) || !actualContact.getCoordinate().equals2D(joint)) return false;
        List<Coordinate> oriented = oriented(candidate, forward);
        int runEnd = straightRunEnd(oriented);
        if (runEnd < 1) return false;
        if (runEnd < oriented.size() - 1 && blocked.intersects(candidate.getFactory().createLineString(
                oriented.subList(runEnd, oriented.size()).toArray(Coordinate[]::new)))) return false;
        LineString straight = candidate.getFactory().createLineString(new Coordinate[] {joint, oriented.get(runEnd)});
        // Пересекаем только исходный прямой участок. Повторный covers вычисленных overlay-точек
        // нестабилен на UTM: округление intersection может сдвинуть точку на доли нанометра.
        Geometry contact = straight.intersection(blocked);
        if (contact instanceof Point) return contact.getCoordinate().equals2D(joint);
        // Несколько несвязных касаний, включая повторный вход в буфер вдали от камеры, запрещены.
        if (!(contact instanceof LineString) && !(contact instanceof MultiLineString)) return false;
        LineMerger merger = new LineMerger();
        merger.add(contact);
        Collection<?> merged = merger.getMergedLineStrings();
        if (merged.size() != 1) return false;
        LineString connected = (LineString) merged.iterator().next();
        return connected.getCoordinateN(0).equals2D(joint)
                || connected.getCoordinateN(connected.getNumPoints() - 1).equals2D(joint);
    }

    private static List<Coordinate> oriented(LineString line, boolean forward) {
        List<Coordinate> result = new ArrayList<>(line.getNumPoints());
        for (int i = 0; i < line.getNumPoints(); i++) {
            ensureActive();
            Coordinate coordinate = line.getCoordinateN(forward ? i : line.getNumPoints() - 1 - i);
            if (result.isEmpty() || !result.get(result.size() - 1).equals2D(coordinate)) result.add(coordinate);
        }
        return result;
    }

    private static int straightRunEnd(List<Coordinate> coordinates) {
        if (coordinates.size() < 2) return -1;
        Coordinate start = coordinates.get(0), first = coordinates.get(1);
        int last = 1;
        for (int i = 2; i < coordinates.size(); i++) {
            ensureActive();
            Coordinate next = coordinates.get(i), previous = coordinates.get(i - 1);
            if (Orientation.index(start, first, next) != 0
                    || (next.x - previous.x) * (first.x - start.x)
                            + (next.y - previous.y) * (first.y - start.y) <= 0) break;
            last = i;
        }
        return last;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Shared junction check cancelled");
    }
}
