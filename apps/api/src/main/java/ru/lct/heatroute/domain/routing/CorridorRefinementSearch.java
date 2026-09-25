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
 * Ограниченное уточнение готовых коридорных сетей: три уровня, до двух состояний на уровень.
 * Сохраняет ветви по конкурсному score и стоимости; геометрические проверки выполняет expansion
 * через настоящий finish. Исходные варианты не заменяются: итоговый отбор принадлежит selector.
 */
final class CorridorRefinementSearch {
    private CorridorRefinementSearch() { }

    static List<RouteVariant> improve(List<RouteVariant> seeds, boolean depthEnabled,
            Function<RouteVariant, List<RouteVariant>> expansion) {
        if (seeds == null || expansion == null) throw new IllegalArgumentException("Seeds and expansion required");
        ensureActive();
        // Только текущий поиск: никакого хранения результатов между расчётами.
        Set<String> seedIdentities = new HashSet<>();
        Set<String> expanded = new HashSet<>();
        Set<String> returned = new HashSet<>();
        List<RouteVariant> eligible = new ArrayList<>();
        for (RouteVariant seed : seeds) {
            ensureActive();
            if (admitted(seed, depthEnabled) && firstVisit(seed, seedIdentities)) eligible.add(seed);
        }
        if (eligible.isEmpty()) return List.of();
        long coverage = eligible.stream().mapToLong(RouteVariant::getConnectedDemandCount).max().orElseThrow();
        List<RouteVariant> frontier = frontier(eligible, coverage);
        List<RouteVariant> result = new ArrayList<>();
        for (int pass = 0; pass < 3 && !frontier.isEmpty(); pass++) {
            List<RouteVariant> next = new ArrayList<>();
            Set<String> nextIdentities = new HashSet<>();
            for (RouteVariant current : frontier) {
                ensureActive();
                if (!firstVisit(current, expanded)) continue;
                List<RouteVariant> candidates = expansion.apply(current);
                ensureActive();
                if (candidates == null || candidates.size() > 6) throw new IllegalArgumentException("At most six completed expansions per state");
                for (RouteVariant candidate : candidates) {
                    ensureActive();
                    if (admitted(candidate, depthEnabled) && candidate.getConnectedDemandCount() == coverage
                            && chambers(candidate) < chambers(current) && notExpanded(candidate, expanded)
                            && firstVisit(candidate, nextIdentities)) {
                        next.add(candidate);
                        // Слишком близкие камеры ещё можно объединить на следующем уровне,
                        // но промежуточную сеть с нарушением не возвращаем как готовую.
                        if (candidate.isValid() && candidate.getEngineeringIssues().isEmpty() && firstVisit(candidate, returned)) {
                            result.add(candidate);
                        }
                    }
                }
            }
            // Отсеянный шириной фронта вариант ещё не исследован: его повторное обнаружение допустимо.
            // При этом состояние, раскрытое другим родителем в этом проходе, больше не занимает слот.
            next.removeIf(candidate -> !notExpanded(candidate, expanded));
            // Временный рост цены промежуточного состояния не обрывает последующее объединение.
            frontier = frontier(next, coverage);
        }
        return List.copyOf(result);
    }

    private static boolean firstVisit(RouteVariant variant, Set<String> visited) {
        // Неоднозначная инцидентность или слишком большой ключ не исключают вариант из поиска.
        return CorridorNetworkIdentity.of(variant).map(visited::add).orElse(true);
    }

    private static boolean notExpanded(RouteVariant variant, Set<String> expanded) {
        return CorridorNetworkIdentity.of(variant).map(key -> !expanded.contains(key)).orElse(true);
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

    private static boolean admitted(RouteVariant variant, boolean depthEnabled) {
        return variant != null && variant.getSizingIssues().isEmpty() && !variant.getEdges().isEmpty()
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
