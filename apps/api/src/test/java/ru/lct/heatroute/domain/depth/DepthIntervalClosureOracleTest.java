package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DepthIntervalClosureOracleTest {
    @Test
    void leastAndFreeCostTieMatchEveryTinyUnionAndAdjacentGap() {
        long checked = 0;
        for (int a = 1; a < 16; a++) for (int b = 1; b < 16; b++) for (int c = 1; c < 16; c++) {
            for (int firstGap : new int[]{0, 1, 3}) for (int secondGap : new int[]{0, 1, 3}) {
                verify(List.of(mask(a), mask(b), mask(c)), new int[]{firstGap, secondGap}, 3, 2);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(30375);
    }

    @Test
    void overlappingConstantPlateausIntersectDomainsBeforePropagation() {
        long checked = 0;
        for (int a = 1; a < 16; a++) for (int b = 1; b < 16; b++) for (int c = 1; c < 16; c++) {
            DepthIntervalClosure.Domain common = mask(a).intersection(mask(b));
            for (int gap : new int[]{0, 1, 3}) {
                verify(List.of(common, mask(c)), new int[]{gap}, 3, 2);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(10125);
    }

    @Test
    void forcedRiseJumpsAcrossForbiddenBandAndRaisesItsNeighborAgain() {
        List<DepthIntervalClosure.Domain> domains = List.of(
                DepthIntervalClosure.Domain.range(4, 4),
                new DepthIntervalClosure.Domain(List.of(new int[]{0, 1}, new int[]{5, 8})),
                DepthIntervalClosure.Domain.range(0, 8));
        int[] actual = DepthIntervalClosure.least(domains, new int[]{1, 1}).orElseThrow();
        assertThat(actual).containsExactly(4, 5, 4);
        assertThat(DepthIntervalClosure.preferOrdinary(domains, new int[]{1, 1}, actual, 3).orElseThrow())
                .containsExactly(4, 5, 4);
    }

    @Test
    void shallowTieCannotJumpIntoDeepIntervalOrMoveCostBearingNeighbor() {
        List<DepthIntervalClosure.Domain> domains = List.of(
                new DepthIntervalClosure.Domain(List.of(new int[]{0, 1}, new int[]{5, 8})),
                DepthIntervalClosure.Domain.range(4, 8));
        int[] least = DepthIntervalClosure.least(domains, new int[]{4}).orElseThrow();
        assertThat(least).containsExactly(0, 4);
        assertThat(DepthIntervalClosure.preferOrdinary(domains, new int[]{4}, least, 3).orElseThrow())
                .containsExactly(1, 4);
    }

    private void verify(List<DepthIntervalClosure.Domain> domains, int[] gaps, int maximum, int ordinary) {
        List<int[]> feasible = new ArrayList<>();
        enumerate(domains, gaps, maximum, new int[domains.size()], 0, feasible);
        Optional<int[]> result = DepthIntervalClosure.least(domains, gaps);
        if (result.isEmpty() != feasible.isEmpty()) throw new AssertionError("feasibility differs");
        if (feasible.isEmpty()) return;
        int[] expected = new int[domains.size()];
        Arrays.fill(expected, Integer.MAX_VALUE);
        for (int[] candidate : feasible) for (int i = 0; i < expected.length; i++) {
            expected[i] = Math.min(expected[i], candidate[i]);
        }
        if (!Arrays.equals(result.get(), expected)) throw new AssertionError("least vector differs");
        int[] preferred = DepthIntervalClosure.preferOrdinary(domains, gaps, expected, ordinary).orElseThrow();
        int[] expectedPreferred = expected.clone();
        for (int[] candidate : feasible) {
            boolean costEquivalent = true;
            for (int i = 0; i < candidate.length; i++) {
                costEquivalent &= expected[i] > ordinary ? candidate[i] == expected[i] : candidate[i] <= ordinary;
            }
            if (costEquivalent) for (int i = 0; i < candidate.length; i++) {
                expectedPreferred[i] = Math.max(expectedPreferred[i], candidate[i]);
            }
        }
        if (!Arrays.equals(preferred, expectedPreferred)) throw new AssertionError("ordinary preference differs");
        for (int i = 0; i < gaps.length; i++) {
            if (Math.abs(preferred[i] - preferred[i + 1]) > gaps[i]) throw new AssertionError("preferred slope invalid");
        }
    }

    private void enumerate(List<DepthIntervalClosure.Domain> domains, int[] gaps, int max,
            int[] vector, int index, List<int[]> output) {
        if (index == vector.length) { output.add(vector.clone()); return; }
        for (int value = 0; value <= max; value++) {
            if (!domains.get(index).contains(value) || index > 0 && Math.abs(value - vector[index - 1]) > gaps[index - 1]) continue;
            vector[index] = value;
            enumerate(domains, gaps, max, vector, index + 1, output);
        }
    }

    private DepthIntervalClosure.Domain mask(int mask) {
        List<int[]> intervals = new ArrayList<>();
        for (int value = 0; value < 4; value++) if ((mask & (1 << value)) != 0) intervals.add(new int[]{value, value});
        return new DepthIntervalClosure.Domain(intervals);
    }
}
