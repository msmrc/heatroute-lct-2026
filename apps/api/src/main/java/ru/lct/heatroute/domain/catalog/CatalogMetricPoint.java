package ru.lct.heatroute.domain.catalog;

import java.util.Objects;

/** Неизменяемая метрическая координата каталога с точностью один миллиметр. */
public final class CatalogMetricPoint {
    private final long xMm;
    private final long yMm;

    public CatalogMetricPoint(long xMm, long yMm) {
        this.xMm = xMm;
        this.yMm = yMm;
    }

    public static CatalogMetricPoint fromMeters(double xM, double yM) {
        return new CatalogMetricPoint(toMillimeters(xM), toMillimeters(yM));
    }

    public long getXMm() { return xMm; }
    public long getYMm() { return yMm; }
    public double getXM() { return xMm / 1000.0; }
    public double getYM() { return yMm / 1000.0; }

    private static long toMillimeters(double meters) {
        double scaled = meters * 1000.0;
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
        return xMm == point.xMm && yMm == point.yMm;
    }

    @Override
    public int hashCode() { return Objects.hash(xMm, yMm); }
}
