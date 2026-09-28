package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.optimization.CpSatChoiceOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.optimization.DiscreteChoiceProblem;

/**
 * Первый next-generation срез: совместно выбирает геометрии вводов фиксированного дерева.
 * Он не используется legacy stable и не заменяет итоговый validator замороженной сети.
 */
final class CpSatPortAssignment {
    private static final double LENGTH_SCALE = 1_000_000.0;
    private static final double GEOMETRY_EPSILON_M = 0.01;
    private static final int MAX_LEAVES = 64;
    private static final int MAX_PATHS_PER_LEAF = 8;
    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    private CpSatPortAssignment() { }

    static Result solve(Map<Integer, List<RoutePath>> candidates,
            Map<Integer, Integer> attachments, List<LineString> grid,
            CpSatRuntime runtime, double timeLimitSeconds) {
        if (candidates == null || attachments == null || grid == null || runtime == null
                || candidates.isEmpty() || candidates.size() > MAX_LEAVES
                || !candidates.keySet().equals(attachments.keySet())) {
            throw new IllegalArgumentException("Complete bounded port catalog required");
        }
        Map<String, Map<String, RoutePath>> paths = new LinkedHashMap<>();
        List<DiscreteChoiceProblem.Group> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<RoutePath>> entry : new TreeMap<>(candidates).entrySet()) {
            int leaf = entry.getKey();
            List<RoutePath> unique = uniquePaths(entry.getValue());
            String groupId = Integer.toString(leaf);
            Map<String, RoutePath> groupPaths = new LinkedHashMap<>();
            List<DiscreteChoiceProblem.Alternative> alternatives = new ArrayList<>();
            for (int index = 0; index < unique.size(); index++) {
                RoutePath path = unique.get(index);
                String alternativeId = String.format(java.util.Locale.ROOT, "%03d", index);
                groupPaths.put(alternativeId, path);
                alternatives.add(new DiscreteChoiceProblem.Alternative(alternativeId, scaledLength(path)));
            }
            paths.put(groupId, Map.copyOf(groupPaths));
            groups.add(new DiscreteChoiceProblem.Group(groupId, alternatives));
        }
        DiscreteChoiceProblem problem = new DiscreteChoiceProblem(groups, List.of());
        CpSatChoiceOptimizer.Result optimized = new CpSatChoiceOptimizer(runtime).solve(problem,
                assignment -> evaluate(assignment, paths, attachments, grid), timeLimitSeconds, 2026);
        Map<Integer, RoutePath> selected = selectedPaths(optimized.getAssignment(), paths);
        return new Result(optimized.getStatus(), selected, optimized.getIterations(),
                optimized.getLearnedConflicts(), optimized.getReason());
    }

    private static List<RoutePath> uniquePaths(List<RoutePath> supplied) {
        if (supplied == null || supplied.isEmpty() || supplied.size() > MAX_PATHS_PER_LEAF) {
            throw new IllegalArgumentException("One to eight paths per leaf required");
        }
        List<RoutePath> result = new ArrayList<>();
        Coordinate expectedStart = null;
        Coordinate expectedEnd = null;
        for (RoutePath path : supplied) {
            if (path == null || path.coordinates().size() < 2 || !Double.isFinite(path.lengthM())
                    || path.lengthM() <= 0.0) {
                throw new IllegalArgumentException("Finite positive terminal path required");
            }
            LineString line = GEOMETRIES.createLineString(path.coordinates().toArray(new Coordinate[0]));
            if (Math.abs(line.getLength() - path.lengthM()) > GEOMETRY_EPSILON_M) {
                throw new IllegalArgumentException("Terminal path length must match its frozen geometry");
            }
            Coordinate start = path.coordinates().get(0);
            Coordinate end = path.coordinates().get(path.coordinates().size() - 1);
            if (expectedStart == null) {
                expectedStart = start;
                expectedEnd = end;
            } else if (expectedStart.distance(start) > GEOMETRY_EPSILON_M
                    || expectedEnd.distance(end) > GEOMETRY_EPSILON_M) {
                throw new IllegalArgumentException("Alternatives of one leaf must keep fixed endpoints");
            }
            if (result.stream().noneMatch(old -> old.coordinates().equals(path.coordinates()))) result.add(path);
        }
        result.sort(Comparator.comparingDouble(RoutePath::lengthM)
                .thenComparing(path -> path.coordinates().toString()));
        return List.copyOf(result);
    }

    private static long scaledLength(RoutePath path) {
        double scaled = path.lengthM() * LENGTH_SCALE;
        if (!Double.isFinite(scaled) || scaled > Long.MAX_VALUE) {
            throw new IllegalArgumentException("Path length exceeds the CP-SAT integer range");
        }
        return Math.round(scaled);
    }

    private static CpSatChoiceOptimizer.Evaluation evaluate(Map<String, String> assignment,
            Map<String, Map<String, RoutePath>> paths, Map<Integer, Integer> attachments,
            List<LineString> grid) {
        Map<Integer, RoutePath> selected = selectedPaths(assignment, paths);
        int[] conflict = CorridorPortCompatibility.firstConflict(selected, attachments, grid);
        if (conflict != null) {
            return CpSatChoiceOptimizer.Evaluation.rejected(selectedChoices(conflict, assignment),
                    conflict.length > 1 && conflict[1] >= 0 ? "port_pair_conflict" : "port_path_conflict");
        }
        EngineeringRouteEvaluator.Evaluation geometry =
                CorridorJunctionAssignment.evaluatePortGeometry(selected, grid);
        if (!geometry.isCompliant()) {
            Set<Integer> offenders = geometry.nonCompliantEdgeIds().stream()
                    .filter(id -> id.startsWith("leaf:"))
                    .map(id -> Integer.parseInt(id.substring("leaf:".length())))
                    .collect(Collectors.toSet());
            if (offenders.isEmpty()) {
                return CpSatChoiceOptimizer.Evaluation.unknown("retained_grid_not_compliant");
            }
            List<DiscreteChoiceProblem.Choice> choices = offenders.stream().sorted()
                    .map(leaf -> selectedChoice(leaf, assignment)).collect(Collectors.toList());
            return CpSatChoiceOptimizer.Evaluation.rejected(choices, "engineering_geometry_conflict");
        }
        return CpSatChoiceOptimizer.Evaluation.accepted("frozen_port_geometry_ok");
    }

    private static Collection<DiscreteChoiceProblem.Choice> selectedChoices(
            int[] conflict, Map<String, String> assignment) {
        List<DiscreteChoiceProblem.Choice> result = new ArrayList<>();
        for (int leaf : conflict) {
            if (leaf >= 0) result.add(selectedChoice(leaf, assignment));
        }
        return result;
    }

    private static DiscreteChoiceProblem.Choice selectedChoice(int leaf, Map<String, String> assignment) {
        String groupId = Integer.toString(leaf);
        String alternativeId = assignment.get(groupId);
        if (alternativeId == null) throw new IllegalStateException("Evaluator conflict references an absent leaf");
        return new DiscreteChoiceProblem.Choice(groupId, alternativeId);
    }

    private static Map<Integer, RoutePath> selectedPaths(Map<String, String> assignment,
            Map<String, Map<String, RoutePath>> paths) {
        if (assignment.isEmpty()) return Map.of();
        Map<Integer, RoutePath> result = new LinkedHashMap<>();
        new TreeMap<>(assignment).forEach((groupId, alternativeId) -> {
            RoutePath path = paths.getOrDefault(groupId, Map.of()).get(alternativeId);
            if (path == null) throw new IllegalStateException("CP-SAT returned an unknown port path");
            result.put(Integer.parseInt(groupId), path);
        });
        return Map.copyOf(result);
    }

    static final class Result {
        private final CpSatChoiceOptimizer.Status status;
        private final Map<Integer, RoutePath> paths;
        private final int iterations;
        private final int learnedConflicts;
        private final String reason;

        private Result(CpSatChoiceOptimizer.Status status, Map<Integer, RoutePath> paths,
                int iterations, int learnedConflicts, String reason) {
            this.status = Objects.requireNonNull(status, "status");
            this.paths = Map.copyOf(paths);
            this.iterations = iterations;
            this.learnedConflicts = learnedConflicts;
            this.reason = reason;
        }

        CpSatChoiceOptimizer.Status status() { return status; }
        Map<Integer, RoutePath> paths() { return paths; }
        int iterations() { return iterations; }
        int learnedConflicts() { return learnedConflicts; }
        String reason() { return reason; }
    }
}
