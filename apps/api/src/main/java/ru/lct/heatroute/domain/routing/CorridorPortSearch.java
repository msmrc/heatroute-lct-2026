package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * Перебирает ограниченное число альтернатив портов после геометрического конфликта вводов.
 * Исключается конкретный несовместимый ввод, не потребитель и не препятствие. Старые варианты
 * остаются отдельными контрольными кандидатами; отказ не доказывает отсутствие допустимой сети.
 */
final class CorridorPortSearch {
    private static final int MAX_ATTEMPTS = 8;
    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    private CorridorPortSearch() { }

    /**
     * Сначала сохраняет прежнее полное решение. Затем пробует совместные пути на уже
     * посещённых деревьях: без нового поиска дерева и не более одного дополнительного ответа.
     */
    static List<Selection> solveWithPaths(List<Coordinate> points, List<int[]> links, List<Double> lengths,
            int gridSize, Map<Integer, Map<Integer, RoutePath>> options,
            BiFunction<Integer, Integer, List<RoutePath>> alternatives,
            BiFunction<List<int[]>, List<Double>, List<int[]>> solver) {
        List<List<int[]>> attempted = new ArrayList<>();
        List<int[]> control = solve(points, links, lengths, gridSize, options, (allowed, weights) -> {
            List<int[]> tree = solver.apply(allowed, weights);
            if (tree != null) attempted.add(tree);
            return tree;
        });
        List<Selection> result = new ArrayList<>();
        if (control != null) result.add(new Selection(control, selectedPaths(control, gridSize, options)));
        for (List<int[]> tree : attempted) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor paths cancelled");
            Selection joint = assignPaths(points, tree, gridSize, options, alternatives);
            if (joint != null && tree == control && samePaths(joint.paths(), result.get(0).paths())) continue;
            if (joint != null) { result.add(joint); break; }
        }
        return List.copyOf(result);
    }

    private static boolean samePaths(Map<Integer, RoutePath> left, Map<Integer, RoutePath> right) {
        return left.keySet().equals(right.keySet()) && left.entrySet().stream()
                .allMatch(entry -> entry.getValue().coordinates().equals(right.get(entry.getKey()).coordinates()));
    }

    /** Выбирает фактические пути у фиксированного дерева; ни один потребитель не удаляется. */
    static Selection assignPaths(List<Coordinate> points, List<int[]> tree, int gridSize,
            Map<Integer, Map<Integer, RoutePath>> options,
            BiFunction<Integer, Integer, List<RoutePath>> alternatives) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor paths cancelled");
        Map<Integer, RoutePath> controls = selectedPaths(tree, gridSize, options);
        if (controls == null) return null;
        Map<Integer, Integer> attachments = new LinkedHashMap<>();
        List<LineString> sections = new ArrayList<>();
        for (int[] edge : tree) {
            int leaf = Math.max(edge[0], edge[1]), port = Math.min(edge[0], edge[1]);
            if (leaf < gridSize) {
                Coordinate a = points.get(edge[0]), b = points.get(edge[1]);
                sections.add(GEOMETRIES.createLineString(new Coordinate[] {
                    new RouteCoordinate(a.x, a.y).toCoordinate(), new RouteCoordinate(b.x, b.y).toCoordinate()}));
            } else {
                attachments.put(leaf, port);
            }
        }
        Map<Integer, RoutePath> chosen = CorridorJunctionAssignment.choosePortPaths(controls, attachments,
                sections, leaf -> alternatives.apply(leaf, attachments.get(leaf)));
        return chosen == null ? null : new Selection(tree, chosen);
    }

    private static Map<Integer, RoutePath> selectedPaths(List<int[]> tree, int gridSize,
            Map<Integer, Map<Integer, RoutePath>> options) {
        Map<Integer, RoutePath> paths = new LinkedHashMap<>();
        for (int[] edge : tree) {
            int leaf = Math.max(edge[0], edge[1]), port = Math.min(edge[0], edge[1]);
            if (leaf < gridSize) continue;
            Map<Integer, RoutePath> choices = options.get(leaf);
            if (choices == null || !choices.containsKey(port) || paths.put(leaf, choices.get(port)) != null) return null;
        }
        return paths.keySet().equals(options.keySet()) ? paths : null;
    }

    static List<int[]> solve(List<Coordinate> points, List<int[]> links, List<Double> lengths, int gridSize,
            Map<Integer, Map<Integer, RoutePath>> options,
            BiFunction<List<int[]>, List<Double>, List<int[]>> solver) {
        if (links.size() != lengths.size() || gridSize < 1 || gridSize > points.size() || options.size() > 64) {
            throw new IllegalArgumentException("Invalid corridor port graph");
        }
        Set<Long> excluded = new HashSet<>();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor ports cancelled");
            List<int[]> allowedLinks = new ArrayList<>();
            List<Double> allowedLengths = new ArrayList<>();
            for (int i = 0; i < links.size(); i++) {
                if (excluded.contains(key(links.get(i)[0], links.get(i)[1]))) continue;
                allowedLinks.add(links.get(i)); allowedLengths.add(lengths.get(i));
            }
            List<int[]> tree = solver.apply(allowedLinks, allowedLengths);
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor ports cancelled");
            if (tree == null) return null;
            Map<Integer, RoutePath> paths = new HashMap<>();
            Map<Integer, Integer> attachments = new HashMap<>();
            List<LineString> sections = new ArrayList<>();
            for (int[] edge : tree) {
                int leaf = Math.max(edge[0], edge[1]), port = Math.min(edge[0], edge[1]);
                if (leaf < gridSize) {
                    Coordinate a = points.get(edge[0]), b = points.get(edge[1]);
                    sections.add(GEOMETRIES.createLineString(new Coordinate[] {
                        new RouteCoordinate(a.x, a.y).toCoordinate(), new RouteCoordinate(b.x, b.y).toCoordinate()}));
                } else {
                    if (attachments.put(leaf, port) != null) return null;
                    paths.put(leaf, options.get(leaf).get(port));
                }
            }
            if (paths.size() != options.size()) return null;
            int[] conflict = CorridorPortCompatibility.firstConflict(paths, attachments, sections);
            if (conflict == null) return tree;
            int rejected = rejectable(conflict[0], attachments, options, excluded) ? conflict[0] : -1;
            if (conflict[1] >= 0 && rejectable(conflict[1], attachments, options, excluded)
                    && (rejected < 0 || paths.get(conflict[1]).lengthM() > paths.get(rejected).lengthM())) {
                rejected = conflict[1];
            }
            if (rejected < 0) return null;
            excluded.add(key(rejected, attachments.get(rejected)));
        }
        return null;
    }

    private static boolean rejectable(int leaf, Map<Integer, Integer> attachments,
            Map<Integer, Map<Integer, RoutePath>> options, Set<Long> excluded) {
        return options.get(leaf).keySet().stream().anyMatch(port -> port.intValue() != attachments.get(leaf)
                && !excluded.contains(key(leaf, port)));
    }

    private static long key(int a, int b) {
        return ((long) Math.max(a, b) << 32) | Math.min(a, b);
    }

    /** Геометрия — часть решения, поэтому компрессия не может заново выбрать короткий ввод. */
    static final class Selection {
        private final List<int[]> tree;
        private final Map<Integer, RoutePath> paths;

        private Selection(List<int[]> tree, Map<Integer, RoutePath> paths) {
            List<int[]> copy = new ArrayList<>();
            tree.forEach(edge -> copy.add(edge.clone()));
            this.tree = List.copyOf(copy);
            this.paths = Map.copyOf(paths);
        }

        List<int[]> tree() { return tree; }
        Map<Integer, RoutePath> paths() { return paths; }
    }
}
