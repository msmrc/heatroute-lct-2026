package ru.lct.heatroute.domain.depth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Ignored prototype: least feasible bounded depths under adjacent Lipschitz constraints. */
final class DepthIntervalClosure {
    private DepthIntervalClosure() { }

    static Optional<int[]> least(List<Domain> domains, int[] maximumDifferences) {
        check(domains, maximumDifferences);
        return leastGraph(domains, chain(maximumDifferences));
    }

    static Optional<int[]> leastGraph(List<Domain> domains, List<int[]> links) {
        int[] values = new int[domains.size()];
        for (int i = 0; i < values.length; i++) {
            Integer first = domains.get(i).ceiling(Integer.MIN_VALUE);
            if (first == null) return Optional.empty();
            values[i] = first;
        }
        return close(domains, links, values, true);
    }

    static Optional<int[]> preferOrdinary(List<Domain> domains, int[] differences, int[] least, int ordinary) {
        check(domains, differences);
        return preferOrdinaryGraph(domains, chain(differences), least, ordinary);
    }

    static Optional<int[]> preferOrdinaryGraph(List<Domain> domains, List<int[]> links, int[] least, int ordinary) {
        if (least.length != domains.size()) throw new IllegalArgumentException("least size");
        List<Domain> costEquivalent = new ArrayList<>();
        int[] values = new int[least.length];
        for (int i = 0; i < least.length; i++) {
            // A free-cost tie can never jump into a more expensive band above ordinary depth.
            int upper = least[i] > ordinary ? least[i] : ordinary;
            Domain restricted = domains.get(i).intersection(Domain.range(least[i], upper));
            Integer last = restricted.floor(Integer.MAX_VALUE);
            if (last == null) return Optional.empty();
            costEquivalent.add(restricted);
            values[i] = last;
        }
        return close(costEquivalent, links, values, false);
    }

    private static Optional<int[]> close(List<Domain> domains, List<int[]> links, int[] values, boolean upwards) {
        List<List<int[]>> neighbors = new ArrayList<>();
        for (int i = 0; i < values.length; i++) neighbors.add(new ArrayList<>());
        for (int[] link : links) {
            if (link.length != 3 || link[0] < 0 || link[0] >= values.length || link[1] < 0
                    || link[1] >= values.length || link[2] < 0) throw new IllegalArgumentException("invalid slope link");
            neighbors.get(link[0]).add(new int[]{link[1], link[2]});
            neighbors.get(link[1]).add(new int[]{link[0], link[2]});
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        boolean[] queued = new boolean[values.length];
        for (int i = 0; i < values.length; i++) { queue.add(i); queued[i] = true; }
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int current = queue.remove();
            queued[current] = false;
            for (int[] adjacent : neighbors.get(current)) {
                int neighbor = adjacent[0], gap = adjacent[1];
                long bound = upwards ? (long) values[current] - gap : (long) values[current] + gap;
                if (upwards ? values[neighbor] >= bound : values[neighbor] <= bound) continue;
                Integer next = upwards
                        ? domains.get(neighbor).ceiling((int) bound)
                        : domains.get(neighbor).floor((int) bound);
                if (next == null) return Optional.empty();
                values[neighbor] = next;
                if (!queued[neighbor]) { queue.add(neighbor); queued[neighbor] = true; }
            }
        }
        return Optional.of(values);
    }

    private static List<int[]> chain(int[] gaps) {
        List<int[]> links = new ArrayList<>();
        for (int i = 0; i < gaps.length; i++) links.add(new int[]{i, i + 1, gaps[i]});
        return links;
    }

    private static void check(List<Domain> domains, int[] gaps) {
        if (domains == null || gaps.length != Math.max(0, domains.size() - 1)) {
            throw new IllegalArgumentException("domain/gap size");
        }
        if (Arrays.stream(gaps).anyMatch(g -> g < 0)) throw new IllegalArgumentException("negative gap");
    }

    static final class Domain {
        private final List<int[]> intervals;

        Domain(List<int[]> input) {
            List<int[]> sorted = new ArrayList<>();
            input.stream().filter(r -> r[0] <= r[1]).forEach(r -> sorted.add(r.clone()));
            sorted.sort(Comparator.comparingInt(r -> r[0]));
            intervals = new ArrayList<>();
            for (int[] range : sorted) {
                int[] last = intervals.isEmpty() ? null : intervals.get(intervals.size() - 1);
                if (last != null && (long) range[0] <= (long) last[1] + 1) last[1] = Math.max(last[1], range[1]);
                else intervals.add(range);
            }
        }

        static Domain range(int low, int high) { return new Domain(List.of(new int[]{low, high})); }

        Domain intersection(Domain other) {
            List<int[]> common = new ArrayList<>();
            for (int[] a : intervals) for (int[] b : other.intervals) {
                int low = Math.max(a[0], b[0]), high = Math.min(a[1], b[1]);
                if (low <= high) common.add(new int[]{low, high});
            }
            return new Domain(common);
        }

        Integer ceiling(int value) {
            for (int[] range : intervals) if (range[1] >= value) return Math.max(range[0], value);
            return null;
        }

        Integer floor(int value) {
            for (int i = intervals.size() - 1; i >= 0; i--) {
                int[] range = intervals.get(i);
                if (range[0] <= value) return Math.min(range[1], value);
            }
            return null;
        }

        boolean contains(int value) { Integer next = ceiling(value); return next != null && next == value; }
    }
}
