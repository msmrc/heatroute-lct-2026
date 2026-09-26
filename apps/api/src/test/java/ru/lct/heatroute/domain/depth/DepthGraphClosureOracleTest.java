package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DepthGraphClosureOracleTest {
    @Test
    void cyclesAndSharedNodesMatchExhaustiveLeastAndCostEquivalentGreatestVectors() {
        long checked = 0;
        for (int a = 1; a < 16; a++) for (int b = 1; b < 16; b++) for (int c = 1; c < 16; c++) {
            List<DepthIntervalClosure.Domain> domains = List.of(mask(a), mask(b), mask(c));
            for (int ab : new int[]{0, 1, 3}) for (int bc : new int[]{0, 1, 3}) for (int ca : new int[]{0, 1, 3}) {
                verify(domains, List.of(new int[]{0, 1, ab}, new int[]{1, 2, bc}, new int[]{2, 0, ca}));
                checked++;
            }
        }
        assertThat(checked).isEqualTo(91125);
    }

    private void verify(List<DepthIntervalClosure.Domain> domains, List<int[]> links) {
        List<int[]> feasible = new ArrayList<>();
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) for (int c = 0; c < 4; c++) {
            int[] vector = {a, b, c};
            boolean valid = true;
            for (int i = 0; i < 3; i++) valid &= domains.get(i).contains(vector[i]);
            for (int[] link : links) valid &= Math.abs(vector[link[0]] - vector[link[1]]) <= link[2];
            if (valid) feasible.add(vector);
        }
        var actual = DepthIntervalClosure.leastGraph(domains, links);
        if (actual.isEmpty() != feasible.isEmpty()) throw new AssertionError("graph feasibility mismatch");
        if (feasible.isEmpty()) return;
        int[] least = {4, 4, 4};
        for (int[] vector : feasible) for (int i = 0; i < 3; i++) least[i] = Math.min(least[i], vector[i]);
        if (!Arrays.equals(least, actual.get())) throw new AssertionError("graph least mismatch");
        int[] greatest = least.clone();
        for (int[] vector : feasible) {
            boolean equivalent = true;
            for (int i = 0; i < 3; i++) equivalent &= least[i] > 2 ? vector[i] == least[i] : vector[i] <= 2;
            if (equivalent) for (int i = 0; i < 3; i++) greatest[i] = Math.max(greatest[i], vector[i]);
        }
        if (!Arrays.equals(greatest, DepthIntervalClosure.preferOrdinaryGraph(domains, links, least, 2).orElseThrow())) {
            throw new AssertionError("graph tie mismatch");
        }
    }

    private DepthIntervalClosure.Domain mask(int mask) {
        List<int[]> intervals = new ArrayList<>();
        for (int value = 0; value < 4; value++) if ((mask & (1 << value)) != 0) intervals.add(new int[]{value, value});
        return new DepthIntervalClosure.Domain(intervals);
    }
}
