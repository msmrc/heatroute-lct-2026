package ru.lct.heatroute.domain.constraints;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;

/**
 * Проверяет осевой отступ road/tram и локальные прямые переходы по §4 технического приложения.
 * Защитные длины отмеряются вдоль трассы; сохранённая разметка special не даёт льготу сама по себе.
 */
public final class RoadCrossingClearance {
    private static final double EPSILON_M = 1e-6;
    // Только округление XY к ближайшему миллиметру; это не допуск уменьшения отступа.
    private static final double SECTION_ROUNDING_M = Math.sqrt(2) * 0.0005 + EPSILON_M;

    public static boolean supports(String type) {
        return "road".equals(type) || "tram_tracks".equals(type);
    }

    public Assessment assess(LineString route, Geometry source, double clearanceM,
            double minimumAngleDegrees, double extensionM) {
        return assess(route, source, clearanceM, minimumAngleDegrees, extensionM, EPSILON_M);
    }

    /**
     * Проверяет округлённые точки разбиения строго допустимой исходной оси.
     * Разрешено только субмиллиметровое отклонение прямоты; отступ/угол/длины защиты не ослабляются.
     * Сопоставление порядка и концов секций с исходным ребром остаётся обязанностью export preflight.
     */
    public Assessment assessRoundedSections(LineString original, LineString emitted, Geometry source,
            double clearanceM, double minimumAngleDegrees, double extensionM) {
        Assessment expected = assess(original, source, clearanceM, minimumAngleDegrees, extensionM);
        if (!expected.isAllowed()) return expected;
        for (Coordinate coordinate : emitted.getCoordinates()) {
            ensureActive();
            if (original.distance(original.getFactory().createPoint(coordinate)) > SECTION_ROUNDING_M) {
                return new Assessment("SPECIAL_CROSSING_SECTION_GEOMETRY_MISMATCH", List.of());
            }
        }
        // Хорда между двумя округлёнными концами сама смещена относительно исходной оси:
        // отклонения точки и хорды могут иметь противоположные знаки, поэтому максимум — 2δ.
        return assess(emitted, source, clearanceM, minimumAngleDegrees, extensionM, 2 * SECTION_ROUNDING_M);
    }

    private Assessment assess(LineString route, Geometry source, double clearanceM,
            double minimumAngleDegrees, double extensionM, double straightnessToleranceM) {
        ensureActive();
        if (farEnough(route, source, clearanceM)) return new Assessment(null, List.of());
        if (source.getDimension() != 2 || !route.isSimple()) {
            return new Assessment("SPECIAL_CROSSING_GEOMETRY_INVALID", List.of());
        }
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        List<Interval> crossings = crossings(route, source, indexed);
        List<Interval> special = new ArrayList<>();
        for (Interval crossing : crossings) {
            ensureActive();
            double start = crossing.startM - extensionM, end = crossing.endM + extensionM;
            if (start < -EPSILON_M || end > route.getLength() + EPSILON_M) {
                return new Assessment("SPECIAL_CROSSING_EXTENSION_MISSING", special);
            }
            Geometry extended = indexed.extractLine(Math.max(0, start), Math.min(route.getLength(), end));
            if (!straight(extended, straightnessToleranceM)) return new Assessment("SPECIAL_CROSSING_NOT_STRAIGHT", special);
            Coordinate first = indexed.extractPoint(crossing.startM);
            Coordinate last = indexed.extractPoint(crossing.endM);
            // §4 задаёт угол в точке входа, а не угол продольной оси/дополнительный угол выхода.
            double angle = boundaryAngle(source, first, first, last);
            if (!OfficialCrossingGeometry.satisfiesMinimumAngle(angle, minimumAngleDegrees)) {
                return new Assessment("SPECIAL_CROSSING_ANGLE_VIOLATION", special);
            }
            special.add(new Interval(Math.max(0, start), Math.min(route.getLength(), end), angle));
        }
        double cursor = 0;
        for (Interval interval : special) {
            if (interval.startM > cursor + EPSILON_M
                    && !farEnough(indexed.extractLine(cursor, interval.startM), source, clearanceM)) {
                return new Assessment("SPECIAL_PARALLEL_CLEARANCE_VIOLATION", special);
            }
            cursor = Math.max(cursor, interval.endM);
        }
        if (cursor < route.getLength() - EPSILON_M
                && !farEnough(indexed.extractLine(cursor, route.getLength()), source, clearanceM)) {
            return new Assessment("SPECIAL_PARALLEL_CLEARANCE_VIOLATION", special);
        }
        return new Assessment(null, special);
    }

    /**
     * Проверка отдельного звена графа допускает незавершённый прямой переход.
     * Повороты в защитной зоне проверяются отдельно, а готовая полилиния — только assess().
     */
    public boolean segmentAllowed(LineString segment, Geometry source, double clearanceM,
            double minimumAngleDegrees, double extensionM) {
        return segmentAllowed(segment, source, clearanceM, minimumAngleDegrees, extensionM, null);
    }

    /** Ускоренная видимость использует только подготовку источника; полный assess остаётся независимым. */
    boolean segmentAllowed(LineString segment, Geometry source, double clearanceM,
            double minimumAngleDegrees, double extensionM, PreparedRoadCrossings prepared) {
        if (farEnough(segment, source, clearanceM)) return true;
        if (source.getDimension() != 2) return false;
        Coordinate a = segment.getCoordinateN(0), b = segment.getCoordinateN(1);
        double length = a.distance(b);
        if (length <= EPSILON_M) return false;
        double dx = (b.x - a.x) / length, dy = (b.y - a.y) / length;
        // Небольшой отрезок может быть частью защитных 3 м, но параллельный ход не получает льготу.
        LineString extended = segment.getFactory().createLineString(new Coordinate[] {
                new Coordinate(a.x - dx * extensionM, a.y - dy * extensionM),
                new Coordinate(b.x + dx * extensionM, b.y + dy * extensionM)});
        LengthIndexedLine indexed = new LengthIndexedLine(extended);
        List<Interval> spans = prepared == null ? crossings(extended, source, indexed)
                : prepared.crossings(extended).orElseGet(() -> crossings(extended, source, indexed));
        if (spans.isEmpty()) return false;
        boolean hasActualCrossing = spans.stream().anyMatch(span ->
                span.endM > extensionM + EPSILON_M && span.startM < extensionM + length - EPSILON_M);
        for (Interval span : spans) {
            // За пределами фактического звена extension нужен только для поиска его возможного
            // protective продолжения. Он не добавляет угол следующего, ещё не пересечённого объекта.
            if (hasActualCrossing && (span.endM <= extensionM + EPSILON_M
                    || span.startM >= extensionM + length - EPSILON_M)) continue;
            // Продолжение уже начавшегося перехода не имеет новой точки входа в этом звене.
            if (span.startM <= EPSILON_M) continue;
            Coordinate hit = indexed.extractPoint(span.startM);
            if (!OfficialCrossingGeometry.satisfiesMinimumAngle(
                    boundaryAngle(source, hit, a, b), minimumAngleDegrees)) return false;
        }
        return true;
    }

    /** Кандидат прямого ввода: возможен незавершённый выход, но начало защиты не достраивается назад. */
    public boolean terminalPrefixAllowed(LineString prefix, Geometry source, double clearanceM,
            double minimumAngleDegrees, double extensionM) {
        return terminalPartAllowed(prefix, source, clearanceM, minimumAngleDegrees, extensionM, false);
    }

    /**
     * Входящий ввод outer→demand: продолжение возможно только перед outer, не за demand.
     * Встреченный переход продолжается по той же оси до реальной границы road/tram:
     * обрезка порта внутри дороги не скрывает недопустимый угол её дальнего входа.
     */
    public boolean terminalSuffixAllowed(LineString suffix, Geometry source, double clearanceM,
            double minimumAngleDegrees, double extensionM) {
        ensureActive();
        return terminalPartAllowed(suffix.reverse(), source, clearanceM, minimumAngleDegrees, extensionM, true);
    }

    private boolean terminalPartAllowed(LineString prefix, Geometry source, double clearanceM,
            double minimumAngleDegrees, double extensionM, boolean incoming) {
        ensureActive();
        if (farEnough(prefix, source, clearanceM)) return true;
        if (source.getDimension() != 2 || prefix.getNumPoints() != 2) return false;
        Coordinate a = prefix.getCoordinateN(0), b = prefix.getCoordinateN(1);
        double length = prefix.getLength();
        if (length <= EPSILON_M) return false;
        LengthIndexedLine indexed = new LengthIndexedLine(prefix);
        // До следующего входа возможна незавершённая защитная часть, в том числе
        // после уже законченного crossing. Позади реального начала продолжения нет.
        Coordinate extendedEnd = new Coordinate(b.x + (b.x - a.x) * extensionM / length,
                b.y + (b.y - a.y) * extensionM / length);
        if (incoming) {
            // Пересечение, уже задетое обязательным вводом, нельзя завершить поворотом внутри
            // road/special. Находим его дальнюю границу по исходному полигону, не по порту + 3 м.
            // Следующие независимые компоненты по-прежнему отсеиваются до проверки угла ниже.
            Envelope bounds = source.getEnvelopeInternal();
            double ux = (b.x - a.x) / length, uy = (b.y - a.y) / length;
            double farX = ux >= 0 ? bounds.getMaxX() : bounds.getMinX();
            double farY = uy >= 0 ? bounds.getMaxY() : bounds.getMinY();
            double forwardLength = Math.max(length + extensionM, (farX - a.x) * ux + (farY - a.y) * uy + extensionM);
            extendedEnd = new Coordinate(a.x + (b.x - a.x) * forwardLength / length,
                    a.y + (b.y - a.y) * forwardLength / length);
        }
        LineString forward = prefix.getFactory().createLineString(new Coordinate[] {a, extendedEnd});
        List<Interval> spans = crossings(forward, source, new LengthIndexedLine(forward));
        double cursor = 0;
        double forcedStraightReach = length;
        for (Interval crossing : spans) {
            // Не навязываем ещё не пересечённый объект, если фактический хвост уже допустим.
            double requiredReach = incoming ? forcedStraightReach : length;
            if (crossing.startM >= requiredReach - EPSILON_M && (cursor >= length - EPSILON_M
                    || farEnough(indexed.extractLine(cursor, length), source, clearanceM))) return true;
            // Начало ввода уже фиксировано: наружное продолжение может завершить выход,
            // но не добавить отсутствующий защитный отрезок позади подключения.
            if (crossing.startM < extensionM - EPSILON_M) return false;
            // Геометрия проверки направлена от фиксированного demand наружу. У входящего
            // ребра реальный вход — дальний конец crossing, а не его ближняя граница.
            double entry = incoming ? crossing.endM : crossing.startM;
            Coordinate hit = new Coordinate(a.x + (b.x - a.x) * entry / length,
                    a.y + (b.y - a.y) * entry / length);
            if (!OfficialCrossingGeometry.satisfiesMinimumAngle(
                    boundaryAngle(source, hit, a, b), minimumAngleDegrees)) return false;
            double start = Math.min(length, Math.max(0, crossing.startM - extensionM));
            if (start > cursor + EPSILON_M && !farEnough(indexed.extractLine(cursor, start), source, clearanceM)) {
                return false;
            }
            // Следующий компонент внутри обязательного прямого продолжения тоже неизбежен.
            // Не обрезаем эту границу портом; цепочка связанных crossings замыкается транзитивно.
            if (incoming) forcedStraightReach = Math.max(forcedStraightReach, crossing.endM + extensionM);
            cursor = Math.max(cursor, Math.min(length, crossing.endM + extensionM));
        }
        return cursor >= length - EPSILON_M || farEnough(indexed.extractLine(cursor, length), source, clearanceM);
    }

    /** Поворот запрещён внутри полигона или защитного прямого отрезка, включая короткие звенья. */
    public boolean turnAllowed(Coordinate before, Coordinate at, Coordinate after, Geometry source,
            double extensionM) {
        double incoming = before.distance(at), outgoing = at.distance(after);
        if (incoming <= EPSILON_M || outgoing <= EPSILON_M) return false;
        double ux = (at.x - before.x) / incoming, uy = (at.y - before.y) / incoming;
        double vx = (after.x - at.x) / outgoing, vy = (after.y - at.y) / outgoing;
        if (ux * vx + uy * vy > 0 && Math.abs(ux * vy - uy * vx) <= 1e-9) return true;
        Geometry point = source.getFactory().createPoint(at);
        if (point.distance(source) >= extensionM - EPSILON_M) return true;
        if (source.covers(point)) return false;
        double reach = Math.max(0, extensionM - EPSILON_M);
        for (Coordinate end : new Coordinate[] {
                new Coordinate(at.x - ux * reach, at.y - uy * reach),
                new Coordinate(at.x + vx * reach, at.y + vy * reach)}) {
            LineString ray = source.getFactory().createLineString(new Coordinate[] {at, end});
            if (!crossings(ray, source, new LengthIndexedLine(ray)).isEmpty()) return false;
        }
        return true;
    }

    private boolean farEnough(Geometry route, Geometry source, double clearanceM) {
        Envelope bounds = new Envelope(source.getEnvelopeInternal());
        bounds.expandBy(clearanceM);
        return !bounds.intersects(route.getEnvelopeInternal())
                || route.distance(source) >= clearanceM - EPSILON_M;
    }

    private List<Interval> crossings(LineString route, Geometry source, LengthIndexedLine indexed) {
        List<Interval> parts = new ArrayList<>();
        if (route.getEnvelopeInternal().intersects(source.getEnvelopeInternal())) {
            collect(route.intersection(source), source, indexed, parts);
        }
        parts.sort(Comparator.comparingDouble(part -> part.startM));
        List<Interval> merged = new ArrayList<>();
        for (Interval part : parts) {
            if (merged.isEmpty() || part.startM > merged.get(merged.size() - 1).endM + EPSILON_M) {
                merged.add(part);
            } else {
                Interval previous = merged.remove(merged.size() - 1);
                merged.add(new Interval(previous.startM, Math.max(previous.endM, part.endM), 0));
            }
        }
        return merged;
    }

    private void collect(Geometry intersection, Geometry source, LengthIndexedLine indexed,
            List<Interval> parts) {
        ensureActive();
        if (intersection instanceof LineString) {
            LineString line = (LineString) intersection;
            for (int i = 1; i < line.getNumPoints(); i++) {
                Coordinate a = line.getCoordinateN(i - 1), b = line.getCoordinateN(i);
                if (a.distance(b) <= EPSILON_M) continue;
                Coordinate middle = new Coordinate((a.x + b.x) / 2, (a.y + b.y) / 2);
                if (!source.contains(source.getFactory().createPoint(middle))) continue;
                double first = indexed.project(a), last = indexed.project(b);
                parts.add(new Interval(Math.min(first, last), Math.max(first, last), 0));
            }
        } else if (intersection instanceof GeometryCollection) {
            for (int i = 0; i < intersection.getNumGeometries(); i++) {
                collect(intersection.getGeometryN(i), source, indexed, parts);
            }
        }
    }

    private boolean straight(Geometry geometry, double toleranceM) {
        Coordinate[] points = geometry.getCoordinates();
        if (points.length < 2) return false;
        LineSegment chord = new LineSegment(points[0], points[points.length - 1]);
        if (chord.getLength() <= EPSILON_M
                || Math.abs(chord.getLength() - geometry.getLength()) > toleranceM * (points.length - 1)) return false;
        double previous = -EPSILON_M;
        for (Coordinate point : points) {
            if (chord.distance(point) > toleranceM) return false;
            double station = chord.projectionFactor(point) * chord.getLength();
            if (station < previous - EPSILON_M) return false;
            previous = station;
        }
        return true;
    }

    private double boundaryAngle(Geometry source, Coordinate hit, Coordinate from, Coordinate to) {
        Geometry boundary = source.getBoundary();
        double angle = 90;
        boolean found = false;
        for (int part = 0; part < boundary.getNumGeometries(); part++) {
            Coordinate[] coordinates = boundary.getGeometryN(part).getCoordinates();
            for (int i = 1; i < coordinates.length; i++) {
                if ((i & 255) == 0) ensureActive();
                LineSegment wall = new LineSegment(coordinates[i - 1], coordinates[i]);
                if (wall.getLength() <= EPSILON_M || wall.distance(hit) > EPSILON_M) continue;
                double dx = to.x - from.x, dy = to.y - from.y;
                double dot = Math.abs((dx * (wall.p1.x - wall.p0.x) + dy * (wall.p1.y - wall.p0.y))
                        / (Math.hypot(dx, dy) * wall.getLength()));
                angle = Math.min(angle, Math.toDegrees(Math.acos(Math.min(1, dot))));
                found = true;
            }
        }
        return found ? angle : 0;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Road crossing validation cancelled");
    }

    public static final class Assessment {
        private final String failureCode;
        private final List<Interval> intervals;
        private Assessment(String failureCode, List<Interval> intervals) {
            this.failureCode = failureCode;
            this.intervals = List.copyOf(intervals);
        }
        public boolean isAllowed() { return failureCode == null; }
        public String getFailureCode() { return failureCode; }
        public List<Interval> getIntervals() { return intervals; }
    }

    public static final class Interval {
        private final double startM, endM, angleDegrees;
        Interval(double startM, double endM, double angleDegrees) {
            this.startM = startM;
            this.endM = endM;
            this.angleDegrees = angleDegrees;
        }
        public double getStartM() { return startM; }
        public double getEndM() { return endM; }
        public double getAngleDegrees() { return angleDegrees; }
    }
}
