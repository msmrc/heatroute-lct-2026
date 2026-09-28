package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.IntFunction;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

/**
 * Выбирает совместимые подходы к общей камере: разные лучи, без наложений и скрытых пересечений.
 * Камерные лучи занимают перпендикулярные оси; прямой подход определяется ДУ каждого участка.
 * Препятствия проверяет поставщик путей; итоговая сеть всё равно проходит независимый validator.
 */
final class CorridorJunctionAssignment {
    private static final GeometryFactory GEOMETRIES = new GeometryFactory();
    private static final double POSITION_EPSILON_M = 0.01;
    private static final int MAX_PORT_ASSIGNMENTS = 128;

    private CorridorJunctionAssignment() { }

    /** С неизменяемой частью сети допускается только касание своего внешнего узла по его ID. */
    static boolean clearsRetained(RoutePath path, String outerNodeId, List<RouteEdge> retained) {
        ensureActive();
        LineString line = GEOMETRIES.createLineString(path.coordinates().toArray(new Coordinate[0]));
        Coordinate outer = path.coordinates().get(0);
        for (RouteEdge edge : retained) {
            ensureActive();
            if (edge.getCoordinates().size() < 2) return false;
            LineString fixed = GEOMETRIES.createLineString(edge.getCoordinates().stream()
                    .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
            if (!line.getEnvelopeInternal().intersects(fixed.getEnvelopeInternal())) continue;
            Geometry intersection = line.intersection(fixed);
            if (intersection.isEmpty()) continue;
            boolean sharesOuter = outerNodeId.equals(edge.getUpstreamNodeId()) || outerNodeId.equals(edge.getDownstreamNodeId());
            if (!sharesOuter || !(intersection instanceof Point)
                    || intersection.getCoordinate().distance(outer) > POSITION_EPSILON_M) return false;
        }
        return true;
    }

    /**
     * Предварительный геометрический выбор без sizing использует общий нижний предел 2 м.
     * Готовые участки обязаны вызывать перегрузку с ДУ; порядок внешних узлов сохраняется.
     */
    static List<RoutePath> choose(Coordinate junction, List<List<RoutePath>> alternatives) {
        return chooseWithMinimums(junction, alternatives, Collections.nCopies(
                alternatives == null ? 0 : alternatives.size(), ExpertChamberGeometryRules.MIN_BEND_DISTANCE_M));
    }

    /** Готовые участки проверяются по собственным ДУ, без максимума по соседним лучам. */
    static List<RoutePath> choose(Coordinate junction, List<List<RoutePath>> alternatives, List<Integer> diameters) {
        if (alternatives == null || diameters == null || diameters.size() != alternatives.size()
                || diameters.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("One actual diameter per branch is required");
        }
        List<Double> minimums = new ArrayList<>();
        for (int diameter : diameters) minimums.add(ExpertChamberGeometryRules.minimumBendDistanceM(diameter));
        return chooseWithMinimums(junction, alternatives, minimums);
    }

    private static List<RoutePath> chooseWithMinimums(Coordinate junction, List<List<RoutePath>> alternatives,
            List<Double> minimums) {
        ensureActive();
        if (junction == null || !Double.isFinite(junction.x) || !Double.isFinite(junction.y)
                || alternatives == null || alternatives.size() < 2 || alternatives.size() > 4) {
            throw new IllegalArgumentException("Finite junction and two to four branches required");
        }
        List<List<Approach>> choices = new ArrayList<>();
        for (int branchIndex = 0; branchIndex < alternatives.size(); branchIndex++) {
            List<RoutePath> branch = alternatives.get(branchIndex);
            if (branch == null || branch.size() > 8) throw new IllegalArgumentException("At most eight paths per branch");
            List<Approach> paths = new ArrayList<>();
            for (RoutePath path : branch) {
                ensureActive();
                Approach approach = prepare(junction, path, minimums.get(branchIndex));
                if (approach != null) paths.add(approach);
            }
            if (paths.isEmpty()) return null;
            paths.sort(Comparator.comparingDouble(p -> p.line.getLength()));
            choices.add(paths);
        }
        // Максимум 8^4 комбинаций. Парные отказы и нижняя граница длины сокращают поиск.
        Search search = new Search(junction, choices);
        search.visit(new ArrayList<>(), 0.0);
        if (search.best == null) return null;
        List<RoutePath> result = new ArrayList<>();
        for (Approach approach : search.best) result.add(approach.path);
        return List.copyOf(result);
    }

    /** Геометрический выбор без sizing; окончательная доводка передаёт ДУ каждого участка. */
    static List<RoutePath> choosePrecise(Coordinate junction, List<List<RoutePath>> alternatives) {
        return choose(junction, alternatives);
    }

    /** Точный совместный выбор сохраняет табличный подход каждого фактического ДУ. */
    static List<RoutePath> choosePrecise(Coordinate junction, List<List<RoutePath>> alternatives, List<Integer> diameters) {
        return choose(junction, alternatives, diameters);
    }

    /**
     * Сохраняет контрольные вводы, меняя только конфликтующие пути выбранного дерева.
     * Пути направлены порт → потребитель. До 128 состояний, до восьми путей на ввод;
     * проверка каждого состояния видит все вводы и фактическую степень портов.
     */
    static Map<Integer, RoutePath> choosePortPaths(Map<Integer, RoutePath> controls,
            Map<Integer, Integer> attachments, List<LineString> grid,
            IntFunction<List<RoutePath>> alternatives) {
        ensureActive();
        if (controls == null || attachments == null || grid == null || alternatives == null
                || controls.size() > 64 || !controls.keySet().equals(attachments.keySet())) {
            throw new IllegalArgumentException("Complete bounded terminal controls and attachments required");
        }
        return new PortSearch(controls, attachments, grid, alternatives).choose();
    }

    /** Оценивает целую замороженную сетку с вводами, не меняя ни одну полилинию. */
    static EngineeringRouteEvaluator.Evaluation evaluatePortGeometry(
            Map<Integer, RoutePath> selected, List<LineString> grid) {
        List<RouteEdge> edges = new ArrayList<>();
        for (int index = 0; index < grid.size(); index++) {
            LineString line = grid.get(index);
            edges.add(scoringEdge("grid:" + index, Arrays.asList(line.getCoordinates()), line.getLength(), false));
        }
        selected.forEach((leaf, path) -> edges.add(scoringEdge("leaf:" + leaf,
                path.coordinates(), path.lengthM(), true)));
        return new EngineeringRouteEvaluator().evaluate(edges);
    }

    private static RouteEdge scoringEdge(String id, List<Coordinate> points, double length, boolean terminal) {
        List<RouteCoordinate> coordinates = new ArrayList<>();
        for (Coordinate point : points) coordinates.add(new RouteCoordinate(point.x, point.y));
        String downstream = terminal ? id : nodeKey(coordinates.get(coordinates.size() - 1));
        return new RouteEdge(id, nodeKey(coordinates.get(0)), downstream, length,
                coordinates, List.of(), null, null);
    }

    private static String nodeKey(RouteCoordinate point) {
        return "port:" + point.getXM() + ":" + point.getYM();
    }

    private static Approach prepare(Coordinate junction, RoutePath path, double minimum) {
        if (path == null || path.coordinates().size() < 2 || path.coordinates().size() > 1000) {
            throw new IllegalArgumentException("A path requires 2..1000 coordinates");
        }
        List<Coordinate> coordinates = path.coordinates();
        for (Coordinate c : coordinates) {
            if (c == null || !Double.isFinite(c.x) || !Double.isFinite(c.y)) {
                throw new IllegalArgumentException("Finite path coordinates required");
            }
        }
        Coordinate endpoint = coordinates.get(coordinates.size() - 1);
        if (endpoint.distance(junction) > POSITION_EPSILON_M) throw new IllegalArgumentException("Path misses junction");
        LineString line = GEOMETRIES.createLineString(coordinates.toArray(new Coordinate[0]));
        if (!line.isSimple() || line.isClosed() || !Double.isFinite(line.getLength()) || line.getLength() <= 0) return null;
        ExpertChamberGeometryRules.PolylineSummary summary = ExpertChamberGeometryRules.summarize(coordinates.stream()
                .map(c -> new RouteCoordinate(c.x, c.y)).collect(java.util.stream.Collectors.toList()));
        if (summary == null || summary.hasInvalidBendAngle()
                || summary.getLastBendDistanceM() + 1e-7 < minimum) return null;
        return new Approach(path, line, -summary.getLastDx(), -summary.getLastDy());
    }

    private static boolean compatible(Coordinate junction, Approach a, Approach b) {
        if (!ExpertChamberGeometryRules.compatibleRays(a.dx, a.dy, b.dx, b.dy)) return false;
        Geometry intersection = a.line.intersection(b.line);
        return intersection.isEmpty() || intersection instanceof Point
                && intersection.getCoordinate().distance(junction) <= POSITION_EPSILON_M;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Junction assignment cancelled");
    }

    private static final class Search {
        private final Coordinate junction;
        private final List<List<Approach>> choices;
        private List<Approach> best;
        private double bestLength = Double.POSITIVE_INFINITY;

        private Search(Coordinate junction, List<List<Approach>> choices) {
            this.junction = junction;
            this.choices = choices;
        }

        private void visit(List<Approach> accepted, double length) {
            ensureActive();
            if (length >= bestLength) return;
            if (accepted.size() == choices.size()) {
                if (accepted.size() == 2) {
                    Approach a = accepted.get(0), b = accepted.get(1);
                    if (!OfficialRouteDeflectionRules.allowsJunctionContinuation(
                            -a.dx, -a.dy, b.dx, b.dy)) return;
                }
                best = List.copyOf(accepted); bestLength = length; return;
            }
            for (Approach candidate : choices.get(accepted.size())) {
                ensureActive();
                if (accepted.stream().anyMatch(previous -> !compatible(junction, previous, candidate))) continue;
                accepted.add(candidate);
                visit(accepted, length + candidate.line.getLength());
                accepted.remove(accepted.size() - 1);
            }
        }
    }

    private static final class Approach {
        private final RoutePath path;
        private final LineString line;
        private final double dx;
        private final double dy;

        private Approach(RoutePath path, LineString line, double dx, double dy) {
            this.path = path; this.line = line; this.dx = dx; this.dy = dy;
        }
    }

    private static final class PortSearch {
        private final Map<Integer, RoutePath> controls;
        private final Map<Integer, RoutePath> selected = new LinkedHashMap<>();
        private final Map<Integer, Integer> attachments;
        private final List<LineString> grid;
        private final IntFunction<List<RoutePath>> alternatives;
        private final List<Integer> leaves;
        private final Map<Integer, List<RoutePath>> choices = new HashMap<>();
        private final Set<String> visited = new HashSet<>();
        private final int[] indices;
        private Map<Integer, RoutePath> best;
        private double bestLength = Double.POSITIVE_INFINITY;
        private int bestBends = Integer.MAX_VALUE;

        private PortSearch(Map<Integer, RoutePath> controls, Map<Integer, Integer> attachments,
                List<LineString> grid, IntFunction<List<RoutePath>> alternatives) {
            this.controls = controls; this.attachments = attachments;
            this.grid = grid; this.alternatives = alternatives;
            leaves = new ArrayList<>(controls.keySet());
            leaves.sort(Integer::compareTo);
            leaves.forEach(leaf -> selected.put(leaf, controls.get(leaf)));
            indices = new int[leaves.size()];
        }

        private Map<Integer, RoutePath> choose() {
            visit();
            return best;
        }

        private void visit() {
            ensureActive();
            if (visited.size() >= MAX_PORT_ASSIGNMENTS || !visited.add(Arrays.toString(indices))) return;
            int[] conflict = CorridorPortCompatibility.firstConflict(selected, attachments, grid);
            EngineeringRouteEvaluator.Evaluation geometry = null;
            if (conflict == null) {
                geometry = evaluateGeometry();
                if (!geometry.isCompliant()) {
                    Set<String> offenders = geometry.nonCompliantEdgeIds();
                    conflict = leaves.stream().filter(leaf -> offenders.contains("leaf:" + leaf))
                            .mapToInt(Integer::intValue).toArray();
                    // Ошибка только внутри неизменяемой сетки не исправляется заменой ввода.
                    if (conflict.length == 0) return;
                } else if (geometry.irregularJunctionAngleCount() > 0) {
                    Set<String> offenders = geometry.irregularJunctionEdgeIds();
                    conflict = leaves.stream().filter(leaf -> offenders.contains("leaf:" + leaf))
                            .mapToInt(Integer::intValue).toArray();
                    // Ортогональная сетка неизменяема; исправим только косой terminal-подход.
                    if (conflict.length == 0) return;
                }
            }
            if (conflict == null) {
                double length = selected.values().stream().mapToDouble(RoutePath::lengthM).sum();
                if (length <= bestLength) {
                    int bends = geometry.bendCount();
                    if (length < bestLength || bends < bestBends) {
                        best = Map.copyOf(selected); bestLength = length; bestBends = bends;
                    }
                }
                return;
            }
            // Хотя бы один участник текущего конфликта обязан сменить геометрию.
            // Возврат к прежним индексам разрешён: другой ввод мог уже сменить путь.
            for (int leaf : conflict) {
                if (leaf < 0) continue;
                int position = leaves.indexOf(leaf), previous = indices[position];
                List<RoutePath> paths = choices.computeIfAbsent(leaf, this::pathsFor);
                for (int index = 0; index < paths.size(); index++) {
                    ensureActive();
                    if (index == previous) continue;
                    indices[position] = index;
                    selected.put(leaf, paths.get(index));
                    visit();
                    indices[position] = previous;
                    selected.put(leaf, paths.get(previous));
                }
            }
        }

        /** Оцениваем целую сеть до компрессии: разрез на порту не скрывает изгиб или короткую полку. */
        private EngineeringRouteEvaluator.Evaluation evaluateGeometry() {
            return evaluatePortGeometry(selected, grid);
        }

        private List<RoutePath> pathsFor(int leaf) {
            List<RoutePath> supplied = alternatives.apply(leaf);
            ensureActive();
            if (supplied == null || supplied.size() > 8) {
                throw new IllegalArgumentException("At most eight terminal alternatives required");
            }
            List<RoutePath> result = new ArrayList<>(List.of(controls.get(leaf)));
            for (RoutePath path : supplied) {
                if (path == null) throw new IllegalArgumentException("Terminal alternative cannot be null");
                if (result.stream().noneMatch(old -> old.coordinates().equals(path.coordinates()))) result.add(path);
            }
            if (result.size() > 8) throw new IllegalArgumentException("At most eight paths including control required");
            result.subList(1, result.size()).sort(Comparator.comparingDouble(RoutePath::lengthM));
            return List.copyOf(result);
        }
    }
}
