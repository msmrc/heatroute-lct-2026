package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import org.locationtech.jts.geom.Coordinate;

/**
 * Предлагает перенос камеры на продолжение обязательной нормали ввода ОКС.
 * Кандидатами служат конец проверенного нормального выхода, проекция текущей камеры
 * и пересечения нормали с фактическими осями остальных примыканий. Это только
 * ограниченная эвристика: все подходы, отступы, ДУ и смета повторно проверяются planner.
 */
final class TerminalAlignedChamberRelocations {
    private static final int MAX_EDGE_COORDINATES = 512;
    private static final int MAX_CANDIDATES = 8;
    // Должен охватывать нормативный выход длинного terminal-ввода, но не превращать
    // локальную доводку в новый глобальный поиск.
    static final double MAX_RELOCATION_M = 120.0;
    private static final double ENDPOINT_TOLERANCE_M = 0.01;
    private static final double PARALLEL_EPSILON = 1e-8;
    private static final double[] NORMAL_CLEARANCE_OFFSETS_M = {
            EngineeringRouteEvaluator.MIN_BEND_SPACING_M + 0.1,
            2 * EngineeringRouteEvaluator.MIN_BEND_SPACING_M
    };

    private TerminalAlignedChamberRelocations() { }

    static List<Coordinate> build(RouteNode chamber, List<RouteEdge> incident,
            Set<String> terminalNodeIds, Predicate<Coordinate> pointAllowed) {
        ensureActive();
        require(chamber != null && !chamber.isRoot() && chamber.isChamber()
                && "new_branch_chamber".equals(chamber.getNodeType()) && named(chamber.getId()),
                "A non-root new branch chamber is required");
        require(incident != null && incident.size() >= 3 && incident.size() <= 4,
                "Three or four incident edges are required");
        require(terminalNodeIds != null && !terminalNodeIds.contains(chamber.getId()) && pointAllowed != null,
                "Terminal IDs and point predicate required");
        Coordinate center = coordinate(chamber.getCoordinate());
        Set<String> edgeIds = new HashSet<>(), outerIds = new HashSet<>();
        List<DirectedEdge> terminals = new ArrayList<>(), supports = new ArrayList<>();
        for (RouteEdge edge : incident) {
            ensureActive();
            require(edge != null && named(edge.getId()) && edgeIds.add(edge.getId()),
                    "Distinct named edges required");
            String from = edge.getUpstreamNodeId(), to = edge.getDownstreamNodeId();
            require(named(from) && named(to) && !from.equals(to)
                    && (from.equals(chamber.getId()) ^ to.equals(chamber.getId())),
                    "Every edge must touch the chamber exactly once");
            String outer = from.equals(chamber.getId()) ? to : from;
            require(outerIds.add(outer), "Distinct outer nodes required");
            require(edge.getCoordinates() != null && edge.getCoordinates().size() >= 2,
                    "Two geometry endpoints required");
            if (edge.getCoordinates().size() > MAX_EDGE_COORDINATES) return List.of();
            DirectedEdge directed = directed(edge, center, outer);
            (terminalNodeIds.contains(outer) ? terminals : supports).add(directed);
        }
        if (terminals.isEmpty() || supports.isEmpty()) return List.of();

        List<Candidate> candidates = new ArrayList<>();
        for (DirectedEdge terminal : terminals) {
            ensureActive();
            Coordinate target = terminal.points.get(terminal.points.size() - 1);
            Coordinate normalStart = previousDistinct(terminal.points, terminal.points.size() - 1);
            if (normalStart == null) continue;
            double nx = target.x - normalStart.x, ny = target.y - normalStart.y;
            double normalLength = Math.hypot(nx, ny);
            if (!(normalLength > ENDPOINT_TOLERANCE_M)) continue;
            nx /= normalLength; ny /= normalLength;

            // Конец уже проверенного нормального выхода обычно является ближайшим законным
            // положением камеры и сразу убирает весь лишний хвост перед точкой подключения.
            add(candidates, normalStart, center, 0, terminal.outerId);
            // Если ось ввода почти совпадает со стволом, камера на самом выходе образует
            // два луча в одну сторону. Позиции дальше от ОКС оставляют нормативные 2 м
            // для перпендикулярного примыкания без короткого поворота у камеры.
            for (double offset : NORMAL_CLEARANCE_OFFSETS_M) {
                add(candidates, new Coordinate(normalStart.x - offset * nx,
                        normalStart.y - offset * ny), center, 1, terminal.outerId);
            }
            double projection = (center.x - target.x) * nx + (center.y - target.y) * ny;
            add(candidates, new Coordinate(target.x + projection * nx, target.y + projection * ny),
                    center, 1, terminal.outerId);
            for (DirectedEdge support : supports) {
                Coordinate next = nextDistinct(support.points, 0);
                if (next == null) continue;
                double sx = next.x - center.x, sy = next.y - center.y;
                double supportLength = Math.hypot(sx, sy);
                if (!(supportLength > ENDPOINT_TOLERANCE_M)) continue;
                sx /= supportLength; sy /= supportLength;
                // Камера на оси ствола напротив уже проверенного выхода ОКС. Такой кандидат
                // сохраняет строго перпендикулярный луч камеры, а малое расхождение осей
                // фасада остаётся коллинеарной частью нормального ввода.
                double normalFoot = (normalStart.x - center.x) * sx
                        + (normalStart.y - center.y) * sy;
                Coordinate foot = new Coordinate(center.x + normalFoot * sx,
                        center.y + normalFoot * sy);
                add(candidates, foot, center, 2, terminal.outerId);
                double footToExitX = normalStart.x - foot.x;
                double footToExitY = normalStart.y - foot.y;
                double footToExit = Math.hypot(footToExitX, footToExitY);
                if (footToExit > ENDPOINT_TOLERANCE_M) {
                    for (double outward : new double[] {-0.1, 0.1}) {
                        add(candidates, new Coordinate(
                                foot.x - outward * footToExitX / footToExit,
                                foot.y - outward * footToExitY / footToExit),
                                center, 2, terminal.outerId);
                    }
                }
                double determinant = cross(sx, sy, nx, ny);
                if (Math.abs(determinant) <= PARALLEL_EPSILON) continue;
                double tx = target.x - center.x, ty = target.y - center.y;
                double alongSupport = cross(tx, ty, nx, ny) / determinant;
                add(candidates, new Coordinate(center.x + alongSupport * sx, center.y + alongSupport * sy),
                        center, 2, terminal.outerId);
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate value) -> value.priority)
                .thenComparingDouble(value -> value.movement)
                .thenComparing(value -> value.terminalId)
                .thenComparingDouble(value -> value.point.x)
                .thenComparingDouble(value -> value.point.y));
        List<Coordinate> accepted = new ArrayList<>();
        for (Candidate candidate : candidates) {
            ensureActive();
            if (accepted.stream().anyMatch(point -> point.equals2D(candidate.point))) continue;
            Coordinate copy = new Coordinate(candidate.point);
            if (pointAllowed.test(copy)) {
                ensureActive();
                accepted.add(new Coordinate(candidate.point));
                if (accepted.size() == MAX_CANDIDATES) break;
            }
        }
        return List.copyOf(accepted);
    }

    private static DirectedEdge directed(RouteEdge edge, Coordinate center, String outerId) {
        List<Coordinate> points = new ArrayList<>();
        for (RouteCoordinate point : edge.getCoordinates()) points.add(coordinate(point));
        boolean first = points.get(0).distance(center) <= ENDPOINT_TOLERANCE_M;
        boolean last = points.get(points.size() - 1).distance(center) <= ENDPOINT_TOLERANCE_M;
        require(first ^ last, "Exactly one geometry endpoint must match the chamber within 1 cm");
        if (last) java.util.Collections.reverse(points);
        return new DirectedEdge(List.copyOf(points), outerId);
    }

    private static Coordinate nextDistinct(List<Coordinate> points, int index) {
        Coordinate point = points.get(index);
        for (int next = index + 1; next < points.size(); next++) {
            if (point.distance(points.get(next)) > ENDPOINT_TOLERANCE_M) return points.get(next);
        }
        return null;
    }

    private static Coordinate previousDistinct(List<Coordinate> points, int index) {
        Coordinate point = points.get(index);
        for (int previous = index - 1; previous >= 0; previous--) {
            if (point.distance(points.get(previous)) > ENDPOINT_TOLERANCE_M) return points.get(previous);
        }
        return null;
    }

    private static void add(List<Candidate> candidates, Coordinate raw, Coordinate center,
            int priority, String terminalId) {
        if (raw == null || !Double.isFinite(raw.x) || !Double.isFinite(raw.y)) return;
        Coordinate point = new RouteCoordinate(raw.x, raw.y).toCoordinate();
        double movement = center.distance(point);
        if (!(movement > ENDPOINT_TOLERANCE_M) || movement > MAX_RELOCATION_M + 1e-8) return;
        if (candidates.stream().noneMatch(candidate -> candidate.point.equals2D(point))) {
            candidates.add(new Candidate(point, priority, movement, terminalId));
        }
    }

    private static Coordinate coordinate(RouteCoordinate value) {
        require(value != null, "Finite XY coordinates required");
        Coordinate point = value.toCoordinate();
        require(point != null && Double.isFinite(point.x) && Double.isFinite(point.y),
                "Finite XY coordinates required");
        return new Coordinate(point);
    }

    private static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }
    private static boolean named(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Terminal-aligned chamber relocation cancelled");
        }
    }

    private static final class DirectedEdge {
        private final List<Coordinate> points;
        private final String outerId;
        private DirectedEdge(List<Coordinate> points, String outerId) {
            this.points = points; this.outerId = outerId;
        }
    }

    private static final class Candidate {
        private final Coordinate point;
        private final int priority;
        private final double movement;
        private final String terminalId;
        private Candidate(Coordinate point, int priority, double movement, String terminalId) {
            this.point = point; this.priority = priority; this.movement = movement; this.terminalId = terminalId;
        }
    }
}
