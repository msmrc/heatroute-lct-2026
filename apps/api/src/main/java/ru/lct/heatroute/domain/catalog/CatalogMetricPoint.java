package ru.lct.heatroute.domain.catalog;

import java.util.Objects;

/**
 * Неизменяемая метрическая координата каталога. Внешние исходные точки по-прежнему
 * создаются в миллиметрах, а технические пересечения могут храниться до микрометра,
 * чтобы две оси получили один точный топологический узел без ложного миллиметрового излома.
 */
public final class CatalogMetricPoint {
    private static final long MICROMETERS_PER_MILLIMETER = 1_000L;
    private static final double MICROMETERS_PER_METER = 1_000_000.0;
    private final long xMicrometers;
    private final long yMicrometers;

    public CatalogMetricPoint(long xMm, long yMm) {
        this(Math.multiplyExact(xMm, MICROMETERS_PER_MILLIMETER),
                Math.multiplyExact(yMm, MICROMETERS_PER_MILLIMETER), true);
    }

    private CatalogMetricPoint(long xMicrometers, long yMicrometers, boolean exact) {
        this.xMicrometers = xMicrometers;
        this.yMicrometers = yMicrometers;
    }

    public static CatalogMetricPoint fromMeters(double xM, double yM) {
        return new CatalogMetricPoint(toMillimeters(xM), toMillimeters(yM));
    }

    public static CatalogMetricPoint fromMillimeters(double xMm, double yMm) {
        return new CatalogMetricPoint(toMicrometers(xMm), toMicrometers(yMm), true);
    }

    /** Legacy millimetre projection for source data and coarse spatial ordering. */
    public long getXMm() { return Math.round(xMicrometers / 1_000.0); }
    public long getYMm() { return Math.round(yMicrometers / 1_000.0); }
    public long getXMicrometers() { return xMicrometers; }
    public long getYMicrometers() { return yMicrometers; }
    public double getXMillimeters() { return xMicrometers / 1_000.0; }
    public double getYMillimeters() { return yMicrometers / 1_000.0; }
    public double getXM() { return xMicrometers / MICROMETERS_PER_METER; }
    public double getYM() { return yMicrometers / MICROMETERS_PER_METER; }

    private static long toMillimeters(double meters) {
        double scaled = meters * 1000.0;
        if (!Double.isFinite(scaled) || scaled > Long.MAX_VALUE || scaled < Long.MIN_VALUE) {
            throw new IllegalArgumentException("Finite metric coordinate is required");
        }
        return Math.round(scaled);
    }

    private static long toMicrometers(double millimeters) {
        double scaled = millimeters * MICROMETERS_PER_MILLIMETER;
        if (!Double.isFinite(scaled) || scaled > Long.MAX_VALUE || scaled < Long.MIN_VALUE) {
            throw new IllegalArgumentException("Finite metric coordinate is required");
        }
        return Math.round(scaled);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CatalogMetricPoint)) return false;
        CatalogMetricPoint point = (CatalogMetricPoint) other;
        return xMicrometers == point.xMicrometers
                && yMicrometers == point.yMicrometers;
    }

    @Override
    public int hashCode() { return Objects.hash(xMicrometers, yMicrometers); }
}
