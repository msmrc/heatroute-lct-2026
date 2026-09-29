package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.routing.RegressionRoutePlannerFixture.VariantDraft;

class AssessedRouteDraftTest {
    @Test
    void bothOrdersExactlyMatchLegacySelectionWithTiesDuplicatesAndBigDecimalScales() {
        Random random = new Random(57123);
        for (int size = 0; size <= 100; size++) {
            List<AssessedRouteDraft> candidates = new ArrayList<>();
            Map<VariantDraft, String> keys = new IdentityHashMap<>();
            for (int i = 0; i < size; i++) {
                VariantDraft draft = new VariantDraft();
                keys.put(draft, "geometry-" + random.nextInt(12));
                candidates.add(new AssessedRouteDraft(draft,
                        BigDecimal.valueOf(random.nextInt(8)).setScale(random.nextInt(4)),
                        random.nextInt(12), random.nextInt(7)));
            }
            List<AssessedRouteDraft> original = List.copyOf(candidates);
            assertThat(AssessedRouteDraft.byScoreAndLength(candidates, keys::get))
                    .containsExactlyElementsOf(legacy(candidates, keys, false));
            assertThat(AssessedRouteDraft.byScoreAndBends(candidates, keys::get))
                    .containsExactlyElementsOf(legacy(candidates, keys, true));
            assertThat(candidates).containsExactlyElementsOf(original);
        }
    }

    @Test
    void duplicateSlotsAreNotRefilledAfterTopThreeLimit() {
        List<AssessedRouteDraft> candidates = List.of(item(1, 1, 1), item(2, 2, 2),
                item(3, 3, 3), item(4, 4, 4));
        VariantDraft last = candidates.get(3).draft();
        assertThat(AssessedRouteDraft.byScoreAndLength(candidates,
                draft -> draft == last ? "different" : "same"))
                .containsExactly(candidates.get(0).draft());
    }

    @Test
    void secondaryBendOrderBreaksEqualBendCountsByScoreNotLength() {
        List<AssessedRouteDraft> candidates = List.of(item(1, 100, 9), item(2, 90, 9),
                item(3, 80, 9), item(5, 1, 0), item(4, 50, 0), item(6, 0, 0), item(7, 0, 0));
        Map<VariantDraft, String> keys = uniqueKeys(candidates);
        assertThat(AssessedRouteDraft.byScoreAndBends(candidates, keys::get)).containsExactly(
                candidates.get(0).draft(), candidates.get(1).draft(), candidates.get(2).draft(),
                candidates.get(4).draft(), candidates.get(3).draft(), candidates.get(5).draft());
    }

    @Test
    void stableEqualScoresKeepFirstEncounterNotSecondaryLength() {
        List<AssessedRouteDraft> candidates = List.of(item(1, 100, 0), item(1, 80, 0),
                item(1, 60, 0), item(1, 0, 0));
        assertThat(AssessedRouteDraft.byScoreAndLength(candidates, uniqueKeys(candidates)::get))
                .containsExactly(candidates.get(0).draft(), candidates.get(1).draft(),
                        candidates.get(2).draft(), candidates.get(3).draft());
    }

    @Test
    void aNewPassUsesItsNewAssessmentsEvenForTheSameDraftObjects() {
        List<AssessedRouteDraft> first = List.of(item(1, 1, 0), item(2, 2, 0), item(3, 3, 0), item(4, 4, 0));
        Map<VariantDraft, String> keys = uniqueKeys(first);
        List<AssessedRouteDraft> second = first.stream().map(c -> new AssessedRouteDraft(c.draft(),
                BigDecimal.TEN.subtract(c.score()), 10 - c.lengthM(), 0)).collect(Collectors.toList());
        assertThat(AssessedRouteDraft.byScoreAndLength(first, keys::get))
                .containsExactly(first.get(0).draft(), first.get(1).draft(), first.get(2).draft());
        assertThat(AssessedRouteDraft.byScoreAndLength(second, keys::get))
                .containsExactly(first.get(3).draft(), first.get(2).draft(), first.get(1).draft());
    }

    @Test
    void invalidAssessmentsFailBeforeSorting() {
        assertThatThrownBy(() -> new AssessedRouteDraft(null, BigDecimal.ONE, 1, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssessedRouteDraft(new VariantDraft(), null, 1, 0)).isInstanceOf(NullPointerException.class);
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new AssessedRouteDraft(new VariantDraft(), BigDecimal.ONE, bad, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AssessedRouteDraft(new VariantDraft(), BigDecimal.ONE, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationKeepsInterruptAndNeverReturnsPartialSelection() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> AssessedRouteDraft.byScoreAndLength(List.of(), draft -> "key"))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private AssessedRouteDraft item(int score, int length, int bends) {
        return new AssessedRouteDraft(new VariantDraft(), BigDecimal.valueOf(score), length, bends);
    }

    private Map<VariantDraft, String> uniqueKeys(List<AssessedRouteDraft> candidates) {
        Map<VariantDraft, String> keys = new IdentityHashMap<>();
        for (int i = 0; i < candidates.size(); i++) keys.put(candidates.get(i).draft(), "k" + i);
        return keys;
    }

    private List<VariantDraft> legacy(List<AssessedRouteDraft> candidates, Map<VariantDraft, String> keys, boolean bends) {
        Map<String, VariantDraft> selected = new LinkedHashMap<>();
        candidates.stream().sorted(Comparator.comparing(AssessedRouteDraft::score)).limit(3)
                .forEach(c -> selected.putIfAbsent(keys.get(c.draft()), c.draft()));
        Comparator<AssessedRouteDraft> secondary = bends
                ? Comparator.comparingInt(AssessedRouteDraft::bends).thenComparing(AssessedRouteDraft::score)
                : Comparator.comparingDouble(AssessedRouteDraft::lengthM);
        candidates.stream().sorted(secondary).limit(3)
                .forEach(c -> selected.putIfAbsent(keys.get(c.draft()), c.draft()));
        return new ArrayList<>(selected.values());
    }
}
