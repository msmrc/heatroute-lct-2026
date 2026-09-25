package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Отбирает до трёх содержательных альтернатив по §6 актуального приложения: роли и небольшие
 * сдвиги одной сети не создают новый вариант. В каждой неразличимой группе сохраняет минимум S;
 * исходные объекты, геометрию, ID и ранги не меняет. Вызывается после полного допуска вариантов.
 */
public final class DistinctRouteAlternatives {
    private static final int MAX_ALTERNATIVES = 3;
    private static final Comparator<RouteVariant> BEST_FIRST = Comparator
            .comparing((RouteVariant v) -> v.getEconomics().getScore())
            .thenComparing(v -> v.getEconomics().getCalculatedCost())
            .thenComparing(RouteVariant::getTotalLengthM)
            .thenComparing(RouteVariant::getId)
            .thenComparing(RouteVariant::getStrategy);

    /** Отдельные одинаковые роли сворачиваются; пустой результат с no_route и полной сметой допустим. */
    public List<RouteVariant> select(List<RouteVariant> finalizedRoles, List<ImportedOfficialFeature> features) {
        Objects.requireNonNull(finalizedRoles, "Finalized roles are required");
        Objects.requireNonNull(features, "Source features are required");
        ensureActive();
        List<RouteVariant> ordered = new ArrayList<>();
        for (RouteVariant variant : finalizedRoles) {
            ensureActive();
            if (eligible(variant)) ordered.add(variant);
        }
        ordered.sort(BEST_FIRST);
        RouteAlternativeFamily.Source source = new RouteAlternativeFamily.Source(features);
        RouteCorridorDifference corridors = new RouteCorridorDifference(features);
        List<RouteVariant> selected = new ArrayList<>();
        List<RouteAlternativeFamily> families = new ArrayList<>();
        for (RouteVariant variant : ordered) {
            ensureActive();
            RouteAlternativeFamily family = RouteAlternativeFamily.of(variant, source);
            boolean distinct = true;
            for (RouteAlternativeFamily retained : families) {
                if (family.sameDecisions(retained) && !corridors.provesDifferent(family, retained)) {
                    distinct = false;
                    break;
                }
            }
            // Не используем транзитивное объединение: отсутствие сертификата различия не транзитивно.
            if (distinct) {
                selected.add(variant);
                families.add(family);
                if (selected.size() == MAX_ALTERNATIVES) break;
            }
        }
        return List.copyOf(selected);
    }

    private boolean eligible(RouteVariant variant) {
        return variant != null && variant.isValid() && variant.getEngineeringIssues().isEmpty()
                && variant.getEconomics() != null && variant.getEconomics().isComplete()
                && variant.getEconomics().getScore() != null
                && variant.getEconomics().getCalculatedCost() != null;
    }

    static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Alternative selection cancelled");
    }
}
