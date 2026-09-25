package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatroute.domain.routing.OfficialRouteDeflectionRules;
import ru.lct.heatroute.domain.routing.ExpertChamberGeometryRules;
import ru.lct.heatroute.domain.routing.RouteCoordinate;

/** Потоково сопоставляет фактически экспортируемые секции с проверенным ребром. */
final class SavedRouteGeometry {
    // Независимое округление точки разбиения до миллиметров; не общий отступ от препятствий.
    private static final double SPLIT_TOLERANCE_M = 0.002;
    private static final double NODE_TOLERANCE_M = 0.01;

    private SavedRouteGeometry() { }

    static OfficialRouteDeflectionRules.PolylineCheck verify(JsonNode edge,
            RouteCoordinate upstream, RouteCoordinate downstream) {
        String id = edge.path("id").asText();
        Iterable<RouteCoordinate> original = points(edge.path("coordinates"));
        OfficialRouteDeflectionRules.PolylineCheck originalCheck = check(id, original);
        JsonNode sections = edge.path("sections");
        if (!sections.isMissingNode() && !sections.isArray()) fail("Invalid sections array");
        Iterable<RouteCoordinate> emitted = sections.isEmpty() || sections.isMissingNode()
                ? original : () -> new SectionIterator(sections);
        OfficialRouteDeflectionRules.PolylineCheck actualCheck = emitted == original
                ? originalCheck : check(id, emitted);
        equivalent(original, emitted, upstream.toCoordinate(), downstream.toCoordinate());
        return actualCheck;
    }

    /** Ограниченная сводка для независимой проверки камер до выдачи первого байта экспорта. */
    static ExpertChamberGeometryRules.PolylineSummary chamberSummary(JsonNode edge, boolean emitted) {
        JsonNode sections = edge.path("sections");
        Iterable<RouteCoordinate> coordinates = emitted && sections.isArray() && !sections.isEmpty()
                ? () -> new SectionIterator(sections) : points(edge.path("coordinates"));
        return ExpertChamberGeometryRules.summarize(coordinates);
    }

    private static OfficialRouteDeflectionRules.PolylineCheck check(String id, Iterable<RouteCoordinate> points) {
        OfficialRouteDeflectionRules.PolylineCheck result = OfficialRouteDeflectionRules.validatePolyline(id, points);
        if (!result.getIssues().isEmpty()) fail(result.getIssues().get(0).getCode() + ": " + id);
        return result;
    }

    private static Iterable<RouteCoordinate> points(JsonNode array) {
        if (!array.isArray() || array.size() < 2) fail("Geometry requires at least two coordinates");
        return () -> new Iterator<RouteCoordinate>() {
            private final Iterator<JsonNode> source = array.elements();
            @Override public boolean hasNext() { return source.hasNext(); }
            @Override public RouteCoordinate next() { return coordinate(source.next()); }
        };
    }

    static RouteCoordinate coordinate(JsonNode point) {
        return new RouteCoordinate(ordinate(point, "xm"), ordinate(point, "ym"));
    }

    private static double ordinate(JsonNode point, String field) {
        JsonNode value = point.path(field);
        if (!value.isNumber()) throw new IllegalArgumentException("Missing metric coordinate: " + field);
        BigDecimal number = value.decimalValue();
        if (number.stripTrailingZeros().scale() > 3 || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Saved coordinates must have millimetre precision: " + field);
        }
        return number.doubleValue();
    }

    /** Два монотонных курсора допускают разбиение отрезка, но не замену пути или обратный ход. */
    private static void equivalent(Iterable<RouteCoordinate> original, Iterable<RouteCoordinate> emitted,
            Coordinate upstream, Coordinate downstream) {
        Cursor expected = new Cursor(original.iterator()), actual = new Cursor(emitted.iterator());
        if (expected.current.distance(actual.current) > SPLIT_TOLERANCE_M
                || actual.current.distance(upstream) > NODE_TOLERANCE_M) fail("Route start differs from declared node/edge");
        while (expected.next != null && actual.next != null) {
            ensureActive();
            if (expected.next.distance(actual.next) <= SPLIT_TOLERANCE_M) {
                expected.advance();
                actual.advance();
            } else if (expected.current.distance(expected.next) <= actual.current.distance(actual.next)) {
                actual.current = projection(expected.next, actual.current, actual.next);
                expected.advance();
            } else {
                expected.current = projection(actual.next, expected.current, expected.next);
                actual.advance();
            }
        }
        finishTail(expected, actual);
        finishTail(actual, expected);
        if (expected.current.distance(actual.current) > SPLIT_TOLERANCE_M
                || actual.current.distance(downstream) > NODE_TOLERANCE_M) fail("Route end differs from declared node/edge");
    }

    private static void finishTail(Cursor moving, Cursor ended) {
        if (moving.next == null) return;
        double length = Math.hypot(ended.lastDx, ended.lastDy);
        double previousStation = ((moving.current.x - ended.current.x) * ended.lastDx
                + (moving.current.y - ended.current.y) * ended.lastDy) / length;
        while (moving.next != null) {
            ensureActive();
            double station = ((moving.next.x - ended.current.x) * ended.lastDx
                    + (moving.next.y - ended.current.y) * ended.lastDy) / length;
            if (moving.next.distance(ended.current) > SPLIT_TOLERANCE_M
                    || station < previousStation - 1e-9) fail("Section path backtracks or differs from edge");
            previousStation = station;
            moving.advance();
        }
    }

    private static Coordinate projection(Coordinate point, Coordinate start, Coordinate end) {
        double dx = end.x - start.x, dy = end.y - start.y;
        double length = Math.hypot(dx, dy);
        double station = ((point.x - start.x) * dx + (point.y - start.y) * dy) / length;
        if (station < 0 || station > length) throw new IllegalArgumentException("Section path backtracks or differs from edge");
        Coordinate projected = new Coordinate(start.x + station * dx / length, start.y + station * dy / length);
        if (projected.distance(point) > SPLIT_TOLERANCE_M) fail("Section path differs from edge");
        return projected;
    }

    private static final class Cursor {
        private final Iterator<RouteCoordinate> points;
        private Coordinate current;
        private Coordinate next;
        private double lastDx;
        private double lastDy;
        private Cursor(Iterator<RouteCoordinate> points) {
            this.points = points;
            current = points.next().toCoordinate();
            readNext();
        }
        private void advance() {
            lastDx = next.x - current.x;
            lastDy = next.y - current.y;
            current = next;
            readNext();
        }
        private void readNext() {
            next = null;
            while (points.hasNext()) {
                ensureActive();
                Coordinate candidate = points.next().toCoordinate();
                if (!current.equals2D(candidate)) { next = candidate; return; }
            }
        }
    }

    private static final class SectionIterator implements Iterator<RouteCoordinate> {
        private final Iterator<JsonNode> sections;
        private Iterator<RouteCoordinate> current;
        private RouteCoordinate previous;
        private RouteCoordinate first;
        private boolean distinct;
        private SectionIterator(JsonNode sections) { this.sections = sections.elements(); }
        @Override public boolean hasNext() {
            while (current == null || !current.hasNext()) {
                ensureActive();
                if (current != null && !distinct) fail("Section requires distinct coordinates");
                if (!sections.hasNext()) return false;
                JsonNode coordinates = sections.next().path("coordinates");
                current = points(coordinates).iterator();
                first = coordinate(coordinates.get(0));
                distinct = false;
                if (previous != null && !same(previous, first)) fail("Discontinuous section boundary");
            }
            return true;
        }
        @Override public RouteCoordinate next() {
            if (!hasNext()) throw new NoSuchElementException();
            RouteCoordinate point = current.next();
            distinct |= !same(first, point);
            previous = point;
            return point;
        }
    }

    private static boolean same(RouteCoordinate left, RouteCoordinate right) {
        return left.getXM().compareTo(right.getXM()) == 0 && left.getYM().compareTo(right.getYM()) == 0;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Saved geometry validation cancelled");
    }

    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
