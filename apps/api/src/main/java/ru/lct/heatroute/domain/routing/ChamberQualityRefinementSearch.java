package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ограниченная доводка лучей камер у завершённой сети, без изменения официальных формул сметы.
 * Локальный запас 5% по цене/длине считается от одного seed и не накапливается между шагами.
 * Итоговый selector отдельно решает, улучшает ли найденная сеть пользовательскую роль.
 */
final class ChamberQualityRefinementSearch {
    static final int MAX_PASSES = 2;
    static final int MAX_FINISHED_NEIGHBOURS = 2;
    private static final BigDecimal REPAIR_LIMIT = new BigDecimal("1.05");
    private static final EngineeringRouteEvaluator ENGINEERING = new EngineeringRouteEvaluator();
    private static final ExpertChamberRouteValidator CHAMBERS = new ExpertChamberRouteValidator();

    private ChamberQualityRefinementSearch() { }

    /** Expand возвращает не более двух полностью пересчитанных и геометрически проверенных сетей. */
    static RouteVariant improve(RouteVariant seed, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expand) {
        return search(seed, depthEnabled, expand).best;
    }

    /** Сохраняет до четырёх допущенных соседей, в том числе промежуточные варианты для других ролей. */
    static List<RouteVariant> alternatives(RouteVariant seed, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expand) {
        return search(seed, depthEnabled, expand).alternatives;
    }

    private static Result search(RouteVariant seed, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expand) {
        ensureActive();
        if (!complete(seed, depthEnabled)) return new Result(seed, List.of());
        RouteVariant current = seed;
        List<RouteVariant> alternatives = new java.util.ArrayList<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            ensureActive();
            EngineeringRouteEvaluator.Evaluation before = ENGINEERING.evaluate(current.getEdges());
            if (before.irregularJunctionAngleCount() == 0) break;
            List<RouteVariant> neighbours = List.copyOf(expand.apply(current));
            ensureActive();
            if (neighbours.size() > MAX_FINISHED_NEIGHBOURS) {
                throw new IllegalArgumentException("Chamber refinement exceeded its finished-neighbour budget");
            }
            RouteVariant best = null;
            for (RouteVariant candidate : neighbours) {
                ensureActive();
                if (!complete(candidate, depthEnabled)
                        || !sameInputsAndRoots(seed, candidate)
                        || newChambers(candidate) > newChambers(seed)
                        || candidate.getTotalLengthM().compareTo(seed.getTotalLengthM().multiply(REPAIR_LIMIT)) > 0
                        || candidate.getEconomics().getCalculatedCost().compareTo(
                                seed.getEconomics().getCalculatedCost().multiply(REPAIR_LIMIT)) > 0
                        || !improvesRays(before, ENGINEERING.evaluate(candidate.getEdges()))) continue;
                alternatives.add(candidate);
                if (best == null || order().compare(candidate, best) < 0) best = candidate;
            }
            if (best == null) break;
            current = best;
        }
        ensureActive();
        return new Result(current, alternatives);
    }

    private static final class Result {
        private final RouteVariant best;
        private final List<RouteVariant> alternatives;
        private Result(RouteVariant best, List<RouteVariant> alternatives) {
            this.best = best;
            this.alternatives = List.copyOf(alternatives);
        }
    }

    /** Допустимые дополнительные колена оправданы только строгим улучшением пар лучей камеры. */
    static boolean improvesRays(EngineeringRouteEvaluator.Evaluation before,
            EngineeringRouteEvaluator.Evaluation after) {
        return after.isCompliant()
                && after.irregularJunctionAngleCount() < before.irregularJunctionAngleCount()
                && after.preservesJunctionQualityOf(before);
    }

    private static boolean complete(RouteVariant variant, boolean depthEnabled) {
        return variant != null && variant.isValid() && variant.getEngineeringIssues().isEmpty()
                && variant.getEconomics() != null && variant.getEconomics().isComplete()
                && variant.getEconomics().getCalculatedCost() != null
                && variant.getEconomics().getCalculatedCost().signum() >= 0
                && variant.getTotalLengthM().signum() > 0
                && ENGINEERING.evaluate(variant.getEdges()).isCompliant()
                && CHAMBERS.validate(variant.getNodes(), variant.getEdges()).isEmpty()
                && (!depthEnabled || variant.getEdges().stream().allMatch(edge -> edge.getDepthProfile() != null
                        && edge.getDepthProfile().isComplete() && edge.getDepthProfile().getIssues().isEmpty()));
    }

    private static boolean sameInputsAndRoots(RouteVariant before, RouteVariant after) {
        return before.getConnections().size() == after.getConnections().size()
                && demandKeys(before).equals(demandKeys(after))
                && fixedNodeKeys(before).equals(fixedNodeKeys(after));
    }

    private static Map<List<Object>, Long> demandKeys(RouteVariant variant) {
        return variant.getConnections().stream().map(connection -> java.util.Arrays.<Object>asList(
                connection.getDemandId(), connection.getConnectionPointId(), connection.getStatus(),
                connection.getFlowTph() == null ? null : connection.getFlowTph().stripTrailingZeros()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static Map<List<Object>, Long> fixedNodeKeys(RouteVariant variant) {
        return variant.getNodes().stream().filter(node -> node.isRoot() || "demand_connection".equals(node.getNodeType()))
                .map(node -> java.util.Arrays.<Object>asList(node.getId(), node.getNodeType(), node.isRoot(), node.isChamber(),
                        node.getCoordinate().getXM(), node.getCoordinate().getYM(), node.getTargetId(),
                        node.getBaseIncidentSections(), node.getExistingIncidentDiameter()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static long newChambers(RouteVariant variant) {
        return variant.getNodes().stream().filter(RouteNode::isChamber)
                .filter(node -> node.getNodeType().startsWith("new_")).count();
    }

    private static Comparator<RouteVariant> order() {
        return Comparator.comparingInt((RouteVariant variant) -> ENGINEERING.evaluate(variant.getEdges()).irregularJunctionAngleCount())
                .thenComparingDouble(variant -> ENGINEERING.evaluate(variant.getEdges()).totalJunctionAngleDeviation())
                .thenComparing(variant -> variant.getEconomics().getCalculatedCost())
                .thenComparing(RouteVariant::getTotalLengthM).thenComparing(RouteVariant::getId);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Chamber quality refinement cancelled");
    }
}
