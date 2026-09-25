package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ограниченное уточнение готовых коридорных сетей: до двух состояний на уровень.
 * Основной поиск использует три уровня, после обязательного ремонта допускается ещё один.
 * Сохраняет ветви по конкурсному score и стоимости; геометрические проверки выполняет expansion
 * через настоящий finish. Исходные варианты не заменяются: итоговый отбор принадлежит selector.
 */
final class CorridorRefinementSearch {
    private CorridorRefinementSearch() { }

    static List<RouteVariant> improve(List<RouteVariant> seeds, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expansion) {
        return improve(seeds, depthEnabled, expansion, 3, false);
    }

    /** Один уровень после обязательного ремонта: только допустимые seeds, без повторения одинаковых ролей. */
    static List<RouteVariant> improveRepaired(List<RouteVariant> seeds, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expansion) {
        return improve(seeds, depthEnabled, expansion, 1, true);
    }

    private static List<RouteVariant> improve(List<RouteVariant> seeds, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expansion, int passes, boolean strict) {
        if (seeds == null || expansion == null) throw new IllegalArgumentException("Seeds and expansion required");
        ensureActive();
        // Только текущий поиск: никакого хранения результатов между расчётами.
        Set<String> seedIdentities = new HashSet<>();
        Set<String> expanded = new HashSet<>();
        Set<String> returned = new HashSet<>();
        List<RouteVariant> eligible = new ArrayList<>();
        for (RouteVariant seed : seeds) {
            ensureActive();
            if (admitted(seed, depthEnabled, strict) && firstVisit(seed, seedIdentities, strict)) eligible.add(seed);
        }
        if (eligible.isEmpty()) return List.of();
        long coverage = eligible.stream().mapToLong(RouteVariant::getConnectedDemandCount).max().orElseThrow();
        List<RouteVariant> frontier = frontier(eligible, coverage);
        List<RouteVariant> result = new ArrayList<>();
        for (int pass = 0; pass < passes && !frontier.isEmpty(); pass++) {
            List<RouteVariant> next = new ArrayList<>();
            Set<String> nextIdentities = new HashSet<>();
            for (RouteVariant current : frontier) {
                ensureActive();
                if (!firstVisit(current, expanded, strict)) continue;
                List<RouteVariant> candidates = expansion.apply(current);
                ensureActive();
                if (candidates == null || candidates.size() > 6) throw new IllegalArgumentException("At most six completed expansions per state");
                for (RouteVariant candidate : candidates) {
                    ensureActive();
                    if (admitted(candidate, depthEnabled, strict) && candidate.getConnectedDemandCount() == coverage
                            && chambers(candidate) < chambers(current) && notExpanded(candidate, expanded, strict)
                            && firstVisit(candidate, nextIdentities, strict)) {
                        next.add(candidate);
                        // Слишком близкие камеры ещё можно объединить на следующем уровне,
                        // но промежуточную сеть с нарушением не возвращаем как готовую.
                        if (candidate.isValid() && candidate.getEngineeringIssues().isEmpty() && firstVisit(candidate, returned, strict)) {
                            result.add(candidate);
                        }
                    }
                }
            }
            // Отсеянный шириной фронта вариант ещё не исследован: его повторное обнаружение допустимо.
            // При этом состояние, раскрытое другим родителем в этом проходе, больше не занимает слот.
            next.removeIf(candidate -> !notExpanded(candidate, expanded, strict));
            // Временный рост цены промежуточного состояния не обрывает последующее объединение.
            frontier = frontier(next, coverage);
        }
        return List.copyOf(result);
    }

    private static boolean firstVisit(RouteVariant variant, Set<String> visited, boolean normalizeRoles) {
        // Неоднозначная инцидентность или слишком большой ключ не исключают вариант из поиска.
        return identity(variant, normalizeRoles).map(visited::add).orElse(true);
    }

    private static boolean notExpanded(RouteVariant variant, Set<String> expanded, boolean normalizeRoles) {
        return identity(variant, normalizeRoles).map(key -> !expanded.contains(key)).orElse(true);
    }

    private static java.util.Optional<String> identity(RouteVariant variant, boolean normalizeRoles) {
        if (!normalizeRoles) return CorridorNetworkIdentity.of(variant);
        RouteVariant normalized = new RouteVariant(variant.getId(), "engineering", variant.getNodes(), variant.getEdges(),
                variant.getConnections(), variant.getTotalLengthM(), variant.getValidationIssues(), variant.getEngineeringIssues(),
                variant.getSizingIssues(), variant.getReconstruction(), variant.getEconomics(), null);
        return CorridorNetworkIdentity.of(normalized);
    }

    private static List<RouteVariant> frontier(List<RouteVariant> candidates, long coverage) {
        Comparator<RouteVariant> byScore = Comparator.comparing((RouteVariant v) -> v.getEconomics().getScore())
                .thenComparing(v -> v.getEconomics().getCalculatedCost()).thenComparing(RouteVariant::getTotalLengthM)
                .thenComparing(RouteVariant::getId);
        List<RouteVariant> ordered = candidates.stream().filter(v -> v.getConnectedDemandCount() == coverage)
                .sorted(byScore).collect(Collectors.toList());
        if (ordered.isEmpty()) return List.of();
        List<RouteVariant> selected = new ArrayList<>(List.of(ordered.get(0)));
        RouteVariant cheapest = ordered.stream().min(Comparator
                .comparing((RouteVariant v) -> v.getEconomics().getCalculatedCost()).thenComparing(byScore)).orElseThrow();
        if (cheapest != selected.get(0)) selected.add(cheapest);
        else if (ordered.size() > 1) selected.add(ordered.get(1));
        return selected;
    }

    private static boolean admitted(RouteVariant variant, boolean depthEnabled, boolean strict) {
        return variant != null && (!strict || (variant.isValid() && variant.getEngineeringIssues().isEmpty()))
                && variant.getSizingIssues().isEmpty() && !variant.getEdges().isEmpty()
                && variant.getValidationIssues().stream()
                        .allMatch(issue -> "EXPERT_CHAMBER_SPACING_TOO_SHORT".equals(issue.getCode()))
                && variant.getEngineeringIssues().stream()
                        .allMatch(issue -> "EXPERT_CHAMBER_SPACING_TOO_SHORT".equals(issue.getCode()))
                && variant.getEconomics() != null
                && variant.getEconomics().isComplete() && variant.getEconomics().getCalculatedCost() != null
                && variant.getEconomics().getScore() != null
                && (!depthEnabled || variant.getEdges().stream().allMatch(e -> e.getDepthProfile() != null
                        && e.getDepthProfile().isComplete() && e.getDepthProfile().getIssues().isEmpty()));
    }

    private static long chambers(RouteVariant variant) {
        return variant.getNodes().stream().filter(n -> "new_branch_chamber".equals(n.getNodeType())).count();
    }
    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor refinement cancelled");
    }
}
