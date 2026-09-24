package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;

/** Выбирает одну наблюдаемую ось плотного семейства вводов; эвристика, не инженерный норматив. */
final class CorridorTerminalOrientation {
    private static final double PERIOD = Math.PI / 2;
    private static final double CLUSTER_RADIUS = Math.toRadians(6);
    private static final double SUPPORT_RADIUS = Math.toRadians(0.25);
    private static final double MIN_DIFFERENCE = Math.toRadians(0.1);

    private CorridorTerminalOrientation() { }

    static OptionalDouble alternative(double baseline, List<List<Double>> axesByTerminal) {
        ensureActive();
        if (!Double.isFinite(baseline) || axesByTerminal == null || axesByTerminal.size() > 64) {
            throw new IllegalArgumentException("Finite baseline and at most64 terminal families required");
        }
        List<Double> directions = new ArrayList<>();
        for (List<Double> family : axesByTerminal) {
            ensureActive();
            if (family == null || family.size() > 64) throw new IllegalArgumentException("At most64 axes per terminal required");
            List<Double> normalized = new ArrayList<>();
            for (Double angle : family) {
                if (angle == null || !Double.isFinite(angle)) throw new IllegalArgumentException("Finite axis required");
                normalized.add(normalize(angle));
            }
            Collections.sort(normalized);
            List<Double> unique = new ArrayList<>();
            for (double angle : normalized) {
                if (unique.stream().noneMatch(other -> Math.abs(delta(angle, other)) < 1e-6)) unique.add(angle);
            }
            for (double angle : unique) if (Math.abs(delta(angle, baseline)) <= CLUSTER_RADIUS) directions.add(angle);
        }
        if (directions.isEmpty()) return OptionalDouble.empty();
        directions.sort(Comparator.comparingDouble(angle -> delta(angle, baseline)));
        int bestSupport = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        double best = baseline;
        for (double candidate : directions) {
            ensureActive();
            int support = 0;
            double distance = 0;
            for (double other : directions) {
                double separation = Math.abs(delta(candidate, other));
                if (separation <= SUPPORT_RADIUS) support++;
                distance += separation;
            }
            if (support > bestSupport || support == bestSupport && distance < bestDistance - 1e-12) {
                best = candidate;
                bestSupport = support;
                bestDistance = distance;
            }
        }
        return Math.abs(delta(best, baseline)) < MIN_DIFFERENCE ? OptionalDouble.empty() : OptionalDouble.of(best);
    }

    private static double normalize(double angle) {
        double normalized = angle % PERIOD;
        return normalized < 0 ? normalized + PERIOD : normalized;
    }

    private static double delta(double a, double b) {
        double result = normalize(a) - normalize(b);
        if (result > PERIOD / 2) result -= PERIOD;
        if (result < -PERIOD / 2) result += PERIOD;
        return result;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Terminal orientation cancelled");
    }
}
