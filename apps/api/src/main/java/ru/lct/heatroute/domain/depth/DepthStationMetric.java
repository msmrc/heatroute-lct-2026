package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Связь сохранённых миллиметров станции с фактической горизонтальной длиной в миллиметрах. */
final class DepthStationMetric {
    private static final BigDecimal TEN = BigDecimal.TEN;
    private final List<Integer> stored;
    private final List<BigDecimal> physical;

    DepthStationMetric(List<Integer> stored, List<BigDecimal> physical) {
        this.stored = List.copyOf(stored);
        this.physical = List.copyOf(physical);
        if (stored.size() < 2 || stored.size() != physical.size()) throw new IllegalArgumentException("invalid station metric");
        for (int i = 1; i < stored.size(); i++) if (stored.get(i) <= stored.get(i - 1)
                || physical.get(i).compareTo(physical.get(i - 1)) <= 0) throw new IllegalArgumentException("non-positive station interval");
    }

    static DepthStationMetric identity(int length) {
        return new DepthStationMetric(List.of(0, length), List.of(BigDecimal.ZERO, BigDecimal.valueOf(length)));
    }

    int allowedRise(int from, int to) {
        return distance(from, to).divide(TEN, 0, RoundingMode.FLOOR).intValueExact();
    }

    int ascendingEnd(int from, int to, int rise) {
        if (rise == 0) return from;
        BigDecimal required = BigDecimal.valueOf(rise).multiply(TEN);
        int low = from, high = to;
        if (distance(from, to).compareTo(required) < 0) return to + 1;
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (distance(from, mid).compareTo(required) >= 0) high = mid;
            else low = mid + 1;
        }
        return low;
    }

    int descendingStart(int from, int to, int rise) {
        if (rise == 0) return to;
        BigDecimal required = BigDecimal.valueOf(rise).multiply(TEN);
        int low = from, high = to;
        if (distance(from, to).compareTo(required) < 0) return from - 1;
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            if (distance(mid, to).compareTo(required) >= 0) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    private BigDecimal distance(int from, int to) {
        return position(to).subtract(position(from));
    }

    private BigDecimal position(int station) {
        for (int i = 1; i < stored.size(); i++) {
            if (station > stored.get(i)) continue;
            return physical.get(i - 1).add(physical.get(i).subtract(physical.get(i - 1))
                    .multiply(BigDecimal.valueOf(station - stored.get(i - 1)))
                    .divide(BigDecimal.valueOf(stored.get(i) - stored.get(i - 1)), 18, RoundingMode.HALF_UP));
        }
        throw new IllegalArgumentException("station outside physical metric");
    }
}
