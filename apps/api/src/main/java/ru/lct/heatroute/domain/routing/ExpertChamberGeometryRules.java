package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import org.locationtech.jts.geom.Coordinate;

/** Проверяет нормали камер и расстояния до поворотов по таблице ДУ от 27.09.2026. */
public final class ExpertChamberGeometryRules {
    /** Нижняя граница таблицы; для проверки конкретного участка нужен его фактический ДУ. */
    public static final double MIN_BEND_DISTANCE_M = 2.0;
    private static final double NUMERICAL_EPSILON_M = 1e-7;
    private static final double NODE_TOLERANCE_M = 0.01;
    private static final double MAX_ANGLE_TOLERANCE = Math.toRadians(0.1);
    private static final double COLLINEAR_TOLERANCE = Math.toRadians(0.5);

    private ExpertChamberGeometryRules() { }

    /** Минимум между поворотами и от камеры до ближайшего поворота участка. */
    public static double minimumBendDistanceM(int diameter) {
        switch (diameter) {
            case 50: case 65: case 80: case 100: case 125: case 150:
                return 2.0;
            case 200: case 250: case 300:
                return 3.0;
            case 400: case 500: case 600:
                return 4.0;
            case 700: case 800: case 900:
                return 5.0;
            case 1000: case 1200: case 1400:
                return 6.0;
            default:
                throw new IllegalArgumentException("Unsupported chamber approach diameter: " + diameter);
        }
    }

    /** Полная проверка метрических полилиний без зависимости от заявленных длин участков. */
    public static List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges,
            Function<RouteNode, List<Coordinate>> existingDirections) {
        Map<String, PolylineSummary> summaries = new HashMap<>();
        for (RouteEdge edge : edges) summaries.put(edge.getId(), summarize(edge.getCoordinates()));
        return validate(nodes, edges, edge -> summaries.get(edge.getId()), existingDirections);
    }

    /** Потоковый адаптер передаёт ограниченные сводки фактически выдаваемой геометрии. */
    public static List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges,
            Function<RouteEdge, PolylineSummary> summaries,
            Function<RouteNode, List<Coordinate>> existingDirections) {
        Objects.requireNonNull(summaries, "Geometry summaries are required");
        Objects.requireNonNull(existingDirections, "Existing directions provider is required");
        Map<String, RouteNode> byId = new HashMap<>();
        Map<String, List<RouteEdge>> incident = new HashMap<>();
        List<RouteValidationIssue> issues = new ArrayList<>();
        for (RouteNode node : nodes) {
            if (byId.putIfAbsent(node.getId(), node) != null) return List.of(undefined(node.getId()));
        }
        for (RouteEdge edge : edges) {
            if (!byId.containsKey(edge.getUpstreamNodeId()) || !byId.containsKey(edge.getDownstreamNodeId())) {
                return List.of(undefined(edge.getId()));
            }
            incident.computeIfAbsent(edge.getUpstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
            incident.computeIfAbsent(edge.getDownstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
        }
        for (RouteNode chamber : nodes) {
            ensureActive();
            List<RouteEdge> connected = incident.getOrDefault(chamber.getId(), List.of());
            if (!chamber.isChamber() || connected.isEmpty()) continue;
            List<RouteValidationIssue> chamberIssues = new ArrayList<>();
            List<Vector> rays = new ArrayList<>();
            for (Coordinate direction : existingDirections.apply(chamber)) {
                Vector ray = new Vector(direction.x, direction.y);
                if (!ray.finite()) add(chamberIssues, undefined(chamber.getId()));
                else rays.add(ray);
            }
            for (RouteEdge edge : connected) {
                Endpoint endpoint = endpoint(summaries.apply(edge), chamber);
                if (endpoint == null) { add(chamberIssues, undefined(chamber.getId())); continue; }
                rays.add(endpoint.ray);
                validateApproach(chamber, edge, byId, incident, summaries, chamberIssues);
            }
            for (int i = 0; i < rays.size(); i++) {
                for (int j = i + 1; j < rays.size(); j++) {
                    Vector a = rays.get(i), b = rays.get(j);
                    if (!compatibleRays(a.dx, a.dy, b.dx, b.dy)) {
                        add(chamberIssues, new RouteValidationIssue("EXPERT_CHAMBER_OBLIQUE_ENTRY", chamber.getId(),
                                "Примыкания в камере должны занимать разные лучи перпендикулярных осей"));
                    }
                }
            }
            issues.addAll(chamberIssues);
        }
        issues.sort(Comparator.comparing(RouteValidationIssue::getCode)
                .thenComparing(issue -> issue.getSubjectId() == null ? "" : issue.getSubjectId()));
        return List.copyOf(issues);
    }

    /** Разные выходы из камеры образуют 90° или 180°; совпадающие лучи недопустимы. */
    public static boolean compatibleRays(double ax, double ay, double bx, double by) {
        Vector a = new Vector(ax, ay), b = new Vector(bx, by);
        if (!a.finite() || !b.finite()) return false;
        double angle = angle(a, b), tolerance = tolerance(a, b);
        return Math.abs(angle - Math.PI / 2) <= tolerance || Math.abs(angle - Math.PI) <= tolerance;
    }

    /** Актуальное правило задаёт внутренний угол 90–120°, то есть изменение направления 60–90°. */
    public static boolean allowsBend(double ax, double ay, double bx, double by) {
        Vector a = new Vector(ax, ay), b = new Vector(bx, by);
        if (!a.finite() || !b.finite()) return false;
        double angle = angle(a, b), tolerance = tolerance(a, b);
        return angle + tolerance >= Math.PI / 3 && angle <= Math.PI / 2 + tolerance;
    }

    static boolean straightDirections(double ax, double ay, double bx, double by) {
        return sameDirection(new Vector(ax, ay), new Vector(bx, by));
    }

    /** Один проход, постоянная память: повторы и коллинеарные вершины не считаются поворотами. */
    public static PolylineSummary summarize(Iterable<RouteCoordinate> coordinates) {
        Objects.requireNonNull(coordinates, "Coordinates are required");
        RouteCoordinate first = null, previous = null, runStart = null;
        Vector firstRay = null;
        Vector previousRun = null;
        Vector collinearAxis = null;
        Vector lastSegment = null;
        double total = 0, firstBend = Double.POSITIVE_INFINITY, lastBend = Double.NaN;
        boolean invalidAngle = false;
        double minimumBendSpacingM = Double.POSITIVE_INFINITY;
        for (RouteCoordinate point : coordinates) {
            ensureActive();
            if (point == null) return null;
            if (previous == null) { first = point; previous = point; runStart = point; continue; }
            Vector segment = Vector.between(previous, point);
            if (segment.length == 0) continue;
            if (!segment.finite()) return null;
            Vector run = Vector.between(runStart, previous);
            boolean sameRoute = run.length > 0 && sameRouteDirection(run, segment);
            if (run.length > 0 && !sameRoute) {
                Vector completedRun = collinearAxis == null ? run : collinearAxis;
                if (previousRun != null && !allowsBend(
                        previousRun.dx, previousRun.dy, completedRun.dx, completedRun.dy)) invalidAngle = true;
                if (!Double.isFinite(firstBend)) {
                    firstBend = total;
                    firstRay = completedRun;
                }
                if (Double.isFinite(lastBend)) {
                    minimumBendSpacingM = Math.min(minimumBendSpacingM, total - lastBend);
                }
                lastBend = total;
                previousRun = completedRun;
                collinearAxis = null;
                runStart = previous;
            } else if (sameRoute && !sameDirection(run, segment) && collinearAxis == null) {
                // Сохраняем ось до единственного малого расхождения. Так оно не превращается
                // ни в третий поворот, ни в усреднённую ось, выходящую за допуск соседнего угла.
                collinearAxis = run;
            }
            total += segment.length;
            lastSegment = segment;
            previous = point;
        }
        if (first == null || total == 0 || !Double.isFinite(total)) return null;
        Vector lastRun = collinearAxis == null ? Vector.between(runStart, previous) : collinearAxis;
        if (previousRun != null && !allowsBend(previousRun.dx, previousRun.dy, lastRun.dx, lastRun.dy)) invalidAngle = true;
        if (firstRay == null) firstRay = lastRun;
        // Для строго прямого участка лучом служит весь ход: так миллиметровое округление
        // координат не расширяет допустимый угол камеры. Если внутри хода было единственное
        // малое расхождение оцифровки, у камеры сохраняем фактический последний сегмент.
        Vector lastRay = collinearAxis == null ? lastRun : lastSegment;
        return new PolylineSummary(first, previous, firstRay, lastRay, total, firstBend,
                Double.isNaN(lastBend) ? Double.POSITIVE_INFINITY : total - lastBend,
                invalidAngle, minimumBendSpacingM);
    }

    private static void validateApproach(RouteNode chamber, RouteEdge firstEdge,
            Map<String, RouteNode> nodes, Map<String, List<RouteEdge>> incident,
            Function<RouteEdge, PolylineSummary> summaries, List<RouteValidationIssue> issues) {
        if (firstEdge.getDiameter() == null) {
            add(issues, undefined(chamber.getId()));
            return;
        }
        final double minimumDistanceM;
        try {
            minimumDistanceM = minimumBendDistanceM(firstEdge.getDiameter());
        } catch (IllegalArgumentException unsupportedDiameter) {
            add(issues, undefined(chamber.getId()));
            return;
        }
        RouteNode current = chamber;
        RouteEdge edge = firstEdge;
        double distance = 0;
        Vector straightRun = new Vector(0, 0);
        Set<String> visited = new HashSet<>();
        while (distance + NUMERICAL_EPSILON_M < minimumDistanceM) {
            ensureActive();
            if (!visited.add(edge.getId())) { add(issues, undefined(chamber.getId())); return; }
            PolylineSummary summary = summaries.apply(edge);
            Endpoint near = endpoint(summary, current);
            if (near == null) { add(issues, undefined(chamber.getId())); return; }
            if (Double.isFinite(near.bendDistanceM)) {
                if (distance + near.bendDistanceM + NUMERICAL_EPSILON_M < minimumDistanceM) {
                    add(issues, tooClose(chamber.getId(), firstEdge.getDiameter(), minimumDistanceM));
                }
                return;
            }
            distance += summary.actualLengthM;
            straightRun = new Vector(straightRun.dx + near.ray.dx, straightRun.dy + near.ray.dy);
            if (distance + NUMERICAL_EPSILON_M >= minimumDistanceM) return;
            String nextId = edge.getUpstreamNodeId().equals(current.getId())
                    ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId();
            RouteNode next = nodes.get(nextId);
            if (next == null) { add(issues, undefined(chamber.getId())); return; }
            if (next.isChamber()) return;
            List<RouteEdge> continuations = incident.getOrDefault(nextId, List.of());
            if (continuations.size() <= 1) return; // Прямой короткий ввод ОКС разрешён.
            if (continuations.size() != 2) { add(issues, undefined(chamber.getId())); return; }
            RouteEdge continuation = continuations.get(0) == edge ? continuations.get(1) : continuations.get(0);
            Endpoint far = endpoint(summary, next), outgoing = endpoint(summaries.apply(continuation), next);
            if (far == null || outgoing == null) { add(issues, undefined(chamber.getId())); return; }
            if (!sameDirection(straightRun, outgoing.ray)) {
                add(issues, tooClose(chamber.getId(), firstEdge.getDiameter(), minimumDistanceM));
                return;
            }
            current = next;
            edge = continuation;
        }
    }

    private static Endpoint endpoint(PolylineSummary summary, RouteNode node) {
        if (summary == null) return null;
        double first = Vector.between(node.getCoordinate(), summary.first).length;
        double last = Vector.between(node.getCoordinate(), summary.last).length;
        if (first < last && first <= NODE_TOLERANCE_M) return new Endpoint(summary.firstRay, summary.firstBendDistanceM);
        if (last < first && last <= NODE_TOLERANCE_M) return new Endpoint(summary.lastRay.reversed(), summary.lastBendDistanceM);
        return null;
    }

    private static boolean sameDirection(Vector a, Vector b) {
        return a.finite() && b.finite() && angle(a, b) <= tolerance(a, b);
    }

    private static boolean sameRouteDirection(Vector a, Vector b) {
        if (!a.finite() || !b.finite()) return false;
        // Рядом хотя бы с одним конструктивным ходом не короче минимальных 2 м единичное
        // расхождение оцифрованных осей до 0,5° не создаёт фиктивный поворот. Короткая ломаная
        // сохраняет строгий допуск, чтобы последовательный дрейф нельзя было скрыть.
        double allowed = a.length + NUMERICAL_EPSILON_M >= MIN_BEND_DISTANCE_M
                || b.length + NUMERICAL_EPSILON_M >= MIN_BEND_DISTANCE_M
                        ? COLLINEAR_TOLERANCE + 1e-12
                        : tolerance(a, b);
        return angle(a, b) <= allowed;
    }

    private static double angle(Vector a, Vector b) {
        double ax = a.dx / a.length, ay = a.dy / a.length, bx = b.dx / b.length, by = b.dy / b.length;
        return Math.atan2(Math.abs(ax * by - ay * bx), ax * bx + ay * by);
    }

    private static double tolerance(Vector a, Vector b) {
        // Исходные участки одной существующей оси сами могут расходиться после миллиметровой
        // оцифровки. Единый предел 0,1° сохраняет транзитивную общую ось для всех лучей камеры.
        return MAX_ANGLE_TOLERANCE + 1e-12;
    }

    private static RouteValidationIssue tooClose(String chamberId, int diameter, double minimumDistanceM) {
        return new RouteValidationIssue("EXPERT_CHAMBER_BEND_TOO_CLOSE", chamberId,
                "Ближайший поворот участка ДУ" + diameter + " должен находиться не менее чем в "
                        + (int) minimumDistanceM + " м по трассе от тепловой камеры");
    }

    private static RouteValidationIssue undefined(String subject) {
        return new RouteValidationIssue("EXPERT_CHAMBER_GEOMETRY_UNCHECKABLE", subject,
                "Нельзя проверить направления и расстояние до поворота по фактической геометрии и ДУ участка камеры");
    }

    private static void add(List<RouteValidationIssue> issues, RouteValidationIssue issue) {
        for (RouteValidationIssue existing : issues) {
            if (existing.getCode().equals(issue.getCode()) && Objects.equals(existing.getSubjectId(), issue.getSubjectId())) return;
        }
        issues.add(issue);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Chamber geometry validation cancelled");
    }

    /** Не хранит полилинию; крайние векторы направлены по порядку её координат. */
    public static final class PolylineSummary {
        private final RouteCoordinate first;
        private final RouteCoordinate last;
        private final Vector firstRay;
        private final Vector lastRay;
        private final double actualLengthM;
        private final double firstBendDistanceM;
        private final double lastBendDistanceM;
        private final boolean invalidBendAngle;
        private final double minimumBendSpacingM;
        private PolylineSummary(RouteCoordinate first, RouteCoordinate last, Vector firstRay, Vector lastRay,
                double actualLengthM, double firstBendDistanceM, double lastBendDistanceM,
                boolean invalidBendAngle, double minimumBendSpacingM) {
            this.first = first; this.last = last; this.firstRay = firstRay; this.lastRay = lastRay;
            this.actualLengthM = actualLengthM; this.firstBendDistanceM = firstBendDistanceM;
            this.lastBendDistanceM = lastBendDistanceM;
            this.invalidBendAngle = invalidBendAngle; this.minimumBendSpacingM = minimumBendSpacingM;
        }
        public double getActualLengthM() { return actualLengthM; }
        public double getFirstBendDistanceM() { return firstBendDistanceM; }
        public double getLastBendDistanceM() { return lastBendDistanceM; }
        public double getFirstDx() { return firstRay.dx; }
        public double getFirstDy() { return firstRay.dy; }
        public double getLastDx() { return lastRay.dx; }
        public double getLastDy() { return lastRay.dy; }
        RouteCoordinate getFirstCoordinate() { return first; }
        RouteCoordinate getLastCoordinate() { return last; }
        boolean hasInvalidBendAngle() { return invalidBendAngle; }
        double getMinimumBendSpacingM() { return minimumBendSpacingM; }
        boolean hasShortBendSpacing() {
            return minimumBendSpacingM + NUMERICAL_EPSILON_M < MIN_BEND_DISTANCE_M;
        }
    }

    private static final class Vector {
        private final double dx;
        private final double dy;
        private final double length;
        private Vector(double dx, double dy) { this.dx = dx; this.dy = dy; this.length = Math.hypot(dx, dy); }
        private boolean finite() { return length > 0 && Double.isFinite(length); }
        private Vector reversed() { return new Vector(-dx, -dy); }
        private static Vector between(RouteCoordinate a, RouteCoordinate b) {
            return new Vector(b.getXM().subtract(a.getXM()).doubleValue(), b.getYM().subtract(a.getYM()).doubleValue());
        }
    }

    private static final class Endpoint {
        private final Vector ray;
        private final double bendDistanceM;
        private Endpoint(Vector ray, double bendDistanceM) { this.ray = ray; this.bendDistanceM = bendDistanceM; }
    }
}
