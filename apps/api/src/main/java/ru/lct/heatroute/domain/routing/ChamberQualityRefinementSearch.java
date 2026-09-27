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
 * Для уже допустимого seed запас 5% по цене/длине не накапливается между шагами.
 * Обязательное исправление нормали/подхода не отбрасывается лишь из-за стоимости обхода.
 * Итоговый selector отдельно решает, улучшает ли найденная сеть пользовательскую роль.
 */
final class ChamberQualityRefinementSearch {
    static final int MAX_PASSES = 3;
    static final int MAX_FINISHED_NEIGHBOURS = 6;
    private static final BigDecimal REPAIR_LIMIT = new BigDecimal("1.05");
    private static final EngineeringRouteEvaluator ENGINEERING = new EngineeringRouteEvaluator();
    private static final ExpertChamberRouteValidator CHAMBERS = new ExpertChamberRouteValidator();

    private ChamberQualityRefinementSearch() { }

    /** Expand возвращает не более шести полностью пересчитанных и геометрически проверенных сетей. */
    static RouteVariant improve(RouteVariant seed, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expand) {
        return search(seed, depthEnabled, expand).best;
    }

    /** Сохраняет до шести полностью исправленных соседей для итогового выбора ролей. */
    static List<RouteVariant> alternatives(RouteVariant seed, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expand) {
        return search(seed, depthEnabled, expand).alternatives;
    }

    /** Возвращает внутренний черновик для следующего ограниченного прохода; selector его не получает. */
    static RouteVariant advanceRepair(RouteVariant seed, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expand) {
        return search(seed, depthEnabled, expand).progress;
    }

    private static Result search(RouteVariant seed, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expand) {
        ensureActive();
        if (!repairableSeed(seed, depthEnabled)) return new Result(seed, seed, List.of());
        RouteVariant current = seed;
        RouteVariant completeBest = complete(seed, depthEnabled) ? seed : null;
        boolean mandatoryRepair = repairIssueCount(seed) > 0;
        List<RouteVariant> alternatives = new java.util.ArrayList<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            ensureActive();
            EngineeringRouteEvaluator.Evaluation before = ENGINEERING.evaluate(current.getEdges());
            int currentRepairIssues = repairIssueCount(current);
            if (before.irregularJunctionAngleCount() == 0 && currentRepairIssues == 0) break;
            List<RouteVariant> neighbours = List.copyOf(expand.apply(current));
            ensureActive();
            if (neighbours.size() > MAX_FINISHED_NEIGHBOURS) {
                throw new IllegalArgumentException("Chamber refinement exceeded its finished-neighbour budget");
            }
            RouteVariant best = null;
            for (RouteVariant candidate : neighbours) {
                ensureActive();
                int candidateRepairIssues = repairIssueCount(candidate);
                if (!repairableSeed(candidate, depthEnabled)
                        || !sameInputsAndRoots(seed, candidate)
                        || newChambers(candidate) > newChambers(seed) + (mandatoryRepair ? MAX_PASSES : 0)
                        || !mandatoryRepair && (candidate.getTotalLengthM().compareTo(
                                seed.getTotalLengthM().multiply(REPAIR_LIMIT)) > 0
                            || candidate.getEconomics().getCalculatedCost().compareTo(
                                    seed.getEconomics().getCalculatedCost().multiply(REPAIR_LIMIT)) > 0)
                        || candidateRepairIssues > currentRepairIssues
                        || candidateRepairIssues == currentRepairIssues
                            && !improvesRays(before, ENGINEERING.evaluate(candidate.getEdges()))) continue;
                if (complete(candidate, depthEnabled)) {
                    alternatives.add(candidate);
                    if (completeBest == null || order().compare(candidate, completeBest) < 0) completeBest = candidate;
                }
                if (best == null || order().compare(candidate, best) < 0) best = candidate;
            }
            if (best == null) break;
            current = best;
        }
        ensureActive();
        return new Result(completeBest == null ? seed : completeBest, current, alternatives);
    }

    private static final class Result {
        private final RouteVariant best;
        private final RouteVariant progress;
        private final List<RouteVariant> alternatives;
        private Result(RouteVariant best, RouteVariant progress, List<RouteVariant> alternatives) {
            this.best = best;
            this.progress = progress;
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
        return repairableSeed(variant, depthEnabled) && variant.isValid() && variant.getEngineeringIssues().isEmpty()
                && CHAMBERS.validate(variant.getNodes(), variant.getEdges()).isEmpty();
    }

    /** Допускает в поиск только два исправимых дефекта камер, не скрывая ошибки sizing/depth/геометрии. */
    static boolean repairableSeed(RouteVariant variant, boolean depthEnabled) {
        return variant != null && variant.getSizingIssues().isEmpty()
                && variant.getValidationIssues().stream().allMatch(ChamberQualityRefinementSearch::repairableIssue)
                && variant.getEngineeringIssues().stream().allMatch(ChamberQualityRefinementSearch::repairableIssue)
                && variant.getEconomics() != null && variant.getEconomics().isComplete()
                && variant.getEconomics().getCalculatedCost() != null
                && variant.getEconomics().getCalculatedCost().signum() >= 0
                && variant.getTotalLengthM().signum() > 0
                && ENGINEERING.evaluate(variant.getEdges()).isCompliant()
                && CHAMBERS.validate(variant.getNodes(), variant.getEdges()).stream()
                        .allMatch(ChamberQualityRefinementSearch::repairableIssue)
                && (!depthEnabled || variant.getEdges().stream().allMatch(edge -> edge.getDepthProfile() != null
                        && edge.getDepthProfile().isComplete() && edge.getDepthProfile().getIssues().isEmpty()));
    }

    static boolean repairableIssue(RouteValidationIssue issue) {
        return "EXPERT_CHAMBER_OBLIQUE_ENTRY".equals(issue.getCode())
                || "EXPERT_CHAMBER_BEND_TOO_CLOSE".equals(issue.getCode());
    }

    static int repairIssueCount(RouteVariant variant) {
        if (variant == null) return Integer.MAX_VALUE;
        return (int) java.util.stream.Stream.of(variant.getValidationIssues(), variant.getEngineeringIssues(),
                        CHAMBERS.validate(variant.getNodes(), variant.getEdges()))
                .flatMap(List::stream).filter(ChamberQualityRefinementSearch::repairableIssue)
                .map(issue -> issue.getCode() + "|" + issue.getSubjectId()).distinct().count();
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
        return Comparator.comparingInt(ChamberQualityRefinementSearch::repairIssueCount)
                .thenComparingInt(variant -> ENGINEERING.evaluate(variant.getEdges()).irregularJunctionAngleCount())
                .thenComparingDouble(variant -> ENGINEERING.evaluate(variant.getEdges()).excessJunctionAngleDeviation())
                .thenComparing(variant -> variant.getEconomics().getCalculatedCost())
                .thenComparing(RouteVariant::getTotalLengthM).thenComparing(RouteVariant::getId);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Chamber quality refinement cancelled");
    }
}
