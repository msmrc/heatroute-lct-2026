package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Назначает роли уже завершённым вариантам по фактической длине и стоимости, не меняя их геометрию.
 * Все роли сохраняют максимальный охват среди допустимых вариантов; ранги назначает вызывающий код.
 * При наличии сети без экспертных нарушений инженерные роли выбираются только среди таких сетей.
 * Обычный balanced улучшает score без роста цены; лишние колена требуют лучшей геометрии камер.
 * Только явный selectChamberQuality допускает рост цены до 5% с одним бюджетом цены/длины. Это
 * инженерное предпочтение; cheapest и официальная формула score остаются экономическими.
 */
public final class FinishedRouteVariantSelector {
    private static final BigDecimal BALANCED_LENGTH_LIMIT = new BigDecimal("1.05");
    // Только погрешность вычисления углов в double, не дополнительный инженерный допуск.
    private static final double ANGULAR_COMPARISON_EPSILON = 1e-7;
    private final EngineeringRouteEvaluator engineering = new EngineeringRouteEvaluator();
    private final ExpertChamberRouteValidator chambers = new ExpertChamberRouteValidator();

    /**
     * Возвращает доступные роли в порядке balanced, shortest, cheapest. Одна сеть может занимать
     * несколько ролей; роль без допустимого кандидата пропускается, а не заполняется худшим охватом.
     */
    public List<RouteVariant> select(List<RouteVariant> finalized) {
        return select(finalized, false);
    }

    /**
     * При включённом расчёте глубины допускает только варианты с полным профилем каждого ребра
     * без ошибок. Отсев выполняется до сравнения охвата, чтобы негодный 3D-вариант не вытеснил обход.
     */
    public List<RouteVariant> select(List<RouteVariant> finalized, boolean depthEnabled) {
        return select(finalized, depthEnabled, null);
    }

    /** Только финальная доводка камер: один явный anchor сохраняет бюджет при повторном отборе. */
    List<RouteVariant> selectChamberQuality(List<RouteVariant> finalized, boolean depthEnabled, RouteVariant anchor) {
        if (anchor == null) throw new IllegalArgumentException("Original chamber-quality budget anchor is required");
        return select(finalized, depthEnabled, anchor);
    }

    private List<RouteVariant> select(List<RouteVariant> finalized, boolean depthEnabled, RouteVariant qualityAnchor) {
        List<RouteVariant> inputs = List.copyOf(finalized);
        requireUniqueIds(inputs);
        List<Candidate> valid = inputs.stream().filter(RouteVariant::isValid)
                .filter(variant -> !depthEnabled || hasValidDepthProfiles(variant))
                .filter(variant -> chambers.validate(variant.getNodes(), variant.getEdges()).isEmpty())
                .map(variant -> new Candidate(variant, engineering.evaluate(variant.getEdges())))
                .collect(Collectors.toList());
        if (valid.isEmpty()) return List.of();

        long maximumCoverage = valid.stream().mapToLong(candidate -> candidate.variant.getConnectedDemandCount())
                .max().orElseThrow();
        List<Candidate> maximumCoverageCandidates = valid.stream()
                .filter(candidate -> candidate.variant.getConnectedDemandCount() == maximumCoverage)
                .collect(Collectors.toList());
        List<Candidate> engineeringCandidates = maximumCoverageCandidates.stream()
                .filter(candidate -> !"cheapest".equals(candidate.variant.getStrategy()) || candidate.evaluation.isCompliant())
                .collect(Collectors.toList());
        List<Candidate> compliantCandidates = engineeringCandidates.stream()
                .filter(candidate -> candidate.evaluation.isCompliant()).collect(Collectors.toList());
        // Раннее неудачное назначение роли не должно навсегда сохранять её нарушения,
        // если finish() уже получил полноценную сеть того же охвата без этих нарушений.
        if (!compliantCandidates.isEmpty()) engineeringCandidates = compliantCandidates;

        Candidate qualityBudget = qualityAnchor == null ? null
                : new Candidate(qualityAnchor, engineering.evaluate(qualityAnchor.getEdges()));
        List<Candidate> balancedCandidates = qualityBudget != null && hasComparableScore(qualityBudget)
                ? withinChamberBudget(qualityBudget, engineeringCandidates) : engineeringCandidates;
        Candidate balanced = byId(balancedCandidates, "balanced");
        if (balanced == null) {
            balanced = balancedCandidates.stream().min(Comparator
                    .comparing((Candidate candidate) -> !candidate.evaluation.isCompliant())
                    .thenComparing(candidate -> !"engineering".equals(candidate.variant.getStrategy()))
                    .thenComparing(candidate -> candidate.variant.getId())).orElse(null);
        } else {
            balanced = improveBalanced(balanced, balancedCandidates);
        }
        if (balanced != null && qualityBudget != null && hasComparableScore(qualityBudget)) {
            balanced = preferChamberQuality(qualityBudget, balanced, balancedCandidates);
        }

        Candidate originalShortest = byId(valid, "shortest");
        Candidate shortest = engineeringCandidates.stream()
                .filter(candidate -> noEngineeringRegression(candidate, originalShortest))
                .min(Comparator.comparing((Candidate candidate) -> candidate.variant.getTotalLengthM())
                        .thenComparing(this::compareKnownCosts)
                        .thenComparing(candidate -> candidate.variant.getId()))
                .orElse(null);
        Candidate cheapest = maximumCoverageCandidates.stream()
                .filter(this::fullyCosted)
                .min(Comparator.comparing((Candidate candidate) -> candidate.variant.getEconomics().getCalculatedCost())
                        .thenComparing(candidate -> candidate.variant.getTotalLengthM())
                        .thenComparing(candidate -> candidate.variant.getId()))
                .orElse(null);

        List<RouteVariant> result = new ArrayList<>();
        if (balanced != null) result.add(asRole(balanced.variant, "balanced", "engineering"));
        if (shortest != null) result.add(asRole(shortest.variant, "shortest", "shortest"));
        if (cheapest != null) result.add(asRole(cheapest.variant, "cheapest", "cheapest"));
        return List.copyOf(result);
    }

    /** Сравнивает всех претендентов с одним исходным balanced: коридор 5% не накапливается. */
    private Candidate improveBalanced(Candidate baseline, List<Candidate> candidates) {
        if (!hasComparableScore(baseline)) return baseline;
        BigDecimal lengthLimit = baseline.variant.getTotalLengthM().multiply(BALANCED_LENGTH_LIMIT);
        return candidates.stream()
                .filter(this::hasComparableScore)
                .filter(candidate -> candidate.variant.getEconomics().getScore()
                        .compareTo(baseline.variant.getEconomics().getScore()) < 0)
                .filter(candidate -> candidate.variant.getEconomics().getCalculatedCost()
                        .compareTo(baseline.variant.getEconomics().getCalculatedCost()) <= 0)
                .filter(candidate -> candidate.variant.getTotalLengthM().compareTo(lengthLimit) <= 0)
                .filter(candidate -> admissibleBalancedGeometry(candidate, baseline))
                .min(Comparator.comparing((Candidate candidate) -> candidate.variant.getEconomics().getScore())
                        .thenComparing(candidate -> candidate.variant.getEconomics().getCalculatedCost())
                        .thenComparing(candidate -> candidate.variant.getTotalLengthM())
                        .thenComparing(candidate -> candidate.variant.getId()))
                .orElse(baseline);
    }

    /** Бюджеты привязаны к исходному balanced, а не растут вслед за перебором кандидатов. */
    private List<Candidate> withinChamberBudget(Candidate baseline, List<Candidate> candidates) {
        BigDecimal costLimit = baseline.variant.getEconomics().getCalculatedCost().multiply(BALANCED_LENGTH_LIMIT);
        BigDecimal lengthLimit = baseline.variant.getTotalLengthM().multiply(BALANCED_LENGTH_LIMIT);
        return candidates.stream().filter(this::hasComparableScore)
                .filter(candidate -> candidate.variant.getEconomics().getCalculatedCost().signum() >= 0)
                .filter(candidate -> candidate.variant.getEconomics().getCalculatedCost().compareTo(costLimit) <= 0)
                .filter(candidate -> candidate.variant.getTotalLengthM().compareTo(lengthLimit) <= 0)
                .collect(Collectors.toList());
    }

    private Candidate preferChamberQuality(Candidate baseline, Candidate economicImprovement, List<Candidate> candidates) {
        return candidates.stream()
                .filter(candidate -> ChamberQualityRefinementSearch.improvesRays(
                        economicImprovement.evaluation, candidate.evaluation))
                .filter(candidate -> admissibleBalancedGeometry(candidate, baseline))
                .min(Comparator.comparingInt((Candidate candidate) -> candidate.evaluation.irregularJunctionAngleCount())
                        .thenComparingDouble(candidate -> candidate.evaluation.excessJunctionAngleDeviation())
                        .thenComparing(candidate -> candidate.variant.getEconomics().getScore())
                        .thenComparing(candidate -> candidate.variant.getEconomics().getCalculatedCost())
                        .thenComparing(candidate -> candidate.variant.getTotalLengthM())
                        .thenComparing(candidate -> candidate.variant.getId()))
                .orElse(economicImprovement);
    }

    private boolean hasComparableScore(Candidate candidate) {
        return fullyCosted(candidate) && candidate.variant.getEconomics().getScore() != null;
    }

    private boolean admissibleBalancedGeometry(Candidate candidate, Candidate baseline) {
        EngineeringRouteEvaluator.Evaluation current = candidate.evaluation;
        EngineeringRouteEvaluator.Evaluation control = baseline.evaluation;
        // Лишнее колено само по себе не улучшение. Но исправление камеры может потребовать
        // колен для разделения выходов камеры: обязательные углы/интервалы и все лучи сохраняем.
        boolean repairedJunctions = current.isCompliant()
                && current.irregularJunctionAngleCount() < control.irregularJunctionAngleCount()
                && current.preservesJunctionQualityOf(control);
        return current.invalidAngleCount() <= control.invalidAngleCount()
                && current.insufficientSpacingCount() <= control.insufficientSpacingCount()
                && (current.bendCount() <= control.bendCount() || repairedJunctions)
                && current.irregularJunctionAngleCount() <= control.irregularJunctionAngleCount()
                // Нулевой счётчик уже учитывает штатный допуск evaluator 0,5° после округления координат.
                && (current.invalidAngleCount() == 0
                        || noAngularRegression(current.totalAngleDeviation(), control.totalAngleDeviation()))
                && (noAngularRegression(current.preferredAngleDeviation(), control.preferredAngleDeviation())
                        || repairedJunctions)
                && noAngularRegression(current.totalJunctionAngleDeviation(), control.totalJunctionAngleDeviation())
                && candidate.newChamberCount <= baseline.newChamberCount;
    }

    private boolean noAngularRegression(double current, double baseline) {
        return Double.isFinite(current) && Double.isFinite(baseline)
                && current <= baseline + ANGULAR_COMPARISON_EPSILON;
    }

    private boolean hasValidDepthProfiles(RouteVariant variant) {
        return variant.getEdges().stream().allMatch(edge -> edge.getDepthProfile() != null
                && edge.getDepthProfile().isComplete() && edge.getDepthProfile().getIssues().isEmpty());
    }

    private boolean fullyCosted(Candidate candidate) {
        return candidate.variant.getEconomics() != null && candidate.variant.getEconomics().isComplete()
                && candidate.variant.getEconomics().getCalculatedCost() != null;
    }

    private int compareKnownCosts(Candidate left, Candidate right) {
        boolean leftCosted = fullyCosted(left);
        boolean rightCosted = fullyCosted(right);
        if (leftCosted != rightCosted) return leftCosted ? -1 : 1;
        return leftCosted ? left.variant.getEconomics().getCalculatedCost()
                .compareTo(right.variant.getEconomics().getCalculatedCost()) : 0;
    }

    private boolean noEngineeringRegression(Candidate candidate, Candidate baseline) {
        if (baseline == null) return candidate.evaluation.isCompliant();
        // Не меняем предупреждения на худшие ради длины. Для compliant baseline оба порога равны нулю.
        return candidate.evaluation.invalidAngleCount() <= baseline.evaluation.invalidAngleCount()
                && candidate.evaluation.insufficientSpacingCount() <= baseline.evaluation.insufficientSpacingCount();
    }

    private Candidate byId(List<Candidate> candidates, String id) {
        return candidates.stream().filter(candidate -> id.equals(candidate.variant.getId())).findFirst().orElse(null);
    }

    private void requireUniqueIds(List<RouteVariant> variants) {
        Set<String> ids = new HashSet<>();
        for (RouteVariant variant : variants) {
            if (variant.getId() == null || variant.getId().isBlank() || !ids.add(variant.getId())) {
                throw new IllegalArgumentException("Finalized variants must have unique nonblank IDs");
            }
        }
    }

    private RouteVariant asRole(RouteVariant source, String id, String strategy) {
        return new RouteVariant(id, strategy, source.getNodes(), source.getEdges(), source.getConnections(),
                source.getTotalLengthM(), source.getValidationIssues(), source.getEngineeringIssues(), source.getSizingIssues(),
                source.getReconstruction(), source.getEconomics(), null);
    }

    private static final class Candidate {
        private final RouteVariant variant;
        private final EngineeringRouteEvaluator.Evaluation evaluation;
        private final long newChamberCount;

        private Candidate(RouteVariant variant, EngineeringRouteEvaluator.Evaluation evaluation) {
            this.variant = variant;
            this.evaluation = evaluation;
            this.newChamberCount = variant.getNodes().stream().filter(RouteNode::isChamber)
                    .filter(node -> node.getNodeType().startsWith("new_")).count();
        }
    }
}
