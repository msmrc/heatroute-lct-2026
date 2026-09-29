package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import ru.lct.heatroute.domain.routing.OfficialRoutePlanner.VariantDraft;

/**
 * Историческая оценка неизменяемого на время отбора черновика; не готовый проверенный маршрут.
 * Живёт только в одном проходе: после изменения сети sizing и оценки вычисляются заново.
 */
final class AssessedRouteDraft {
    private final VariantDraft draft;
    private final BigDecimal score;
    private final double lengthM;
    private final int bends;

    AssessedRouteDraft(VariantDraft draft, BigDecimal score, double lengthM, int bends) {
        this.draft = Objects.requireNonNull(draft, "draft");
        this.score = Objects.requireNonNull(score, "score");
        if (!Double.isFinite(lengthM) || lengthM < 0 || bends < 0) {
            throw new IllegalArgumentException("Draft metrics must be finite and nonnegative");
        }
        this.lengthM = lengthM;
        this.bends = bends;
    }

    VariantDraft draft() { return draft; }
    BigDecimal score() { return score; }
    double lengthM() { return lengthM; }
    int bends() { return bends; }

    static List<VariantDraft> byScoreAndLength(List<AssessedRouteDraft> candidates,
            Function<VariantDraft, String> duplicateKey) {
        return select(candidates, Comparator.comparingDouble(AssessedRouteDraft::lengthM), duplicateKey);
    }

    static List<VariantDraft> byScoreAndBends(List<AssessedRouteDraft> candidates,
            Function<VariantDraft, String> duplicateKey) {
        return select(candidates, Comparator.comparingInt(AssessedRouteDraft::bends)
                .thenComparing(AssessedRouteDraft::score), duplicateKey);
    }

    private static List<VariantDraft> select(List<AssessedRouteDraft> candidates,
            Comparator<AssessedRouteDraft> secondary, Function<VariantDraft, String> duplicateKey) {
        ensureActive();
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(duplicateKey, "duplicateKey");
        Map<String, VariantDraft> selected = new LinkedHashMap<>();
        appendTopThree(candidates, Comparator.comparing(AssessedRouteDraft::score), duplicateKey, selected);
        appendTopThree(candidates, secondary, duplicateKey, selected);
        return new ArrayList<>(selected.values());
    }

    private static void appendTopThree(List<AssessedRouteDraft> candidates,
            Comparator<AssessedRouteDraft> order, Function<VariantDraft, String> duplicateKey,
            Map<String, VariantDraft> selected) {
        List<AssessedRouteDraft> sorted = new ArrayList<>(candidates);
        // Stable sort сохраняет исходный порядок при равных метриках. Лимит ДО dedup — старый контракт.
        sorted.sort(order);
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            ensureActive();
            VariantDraft draft = sorted.get(i).draft;
            selected.putIfAbsent(Objects.requireNonNull(duplicateKey.apply(draft), "duplicate key"), draft);
        }
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Draft selection cancelled");
    }
}
