package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;

/** Minimum cover on the actual road/tram polygon interval; no constant-depth requirement. */
public final class DepthFloorInterval {
    private final String featureId;
    private final BigDecimal startM;
    private final BigDecimal endM;
    private final BigDecimal minimumDepthM;

    public DepthFloorInterval(String featureId, BigDecimal startM, BigDecimal endM, BigDecimal minimumDepthM) {
        if (featureId == null || startM == null || endM == null || minimumDepthM == null
                || startM.signum() < 0 || endM.compareTo(startM) < 0
                || minimumDepthM.compareTo(new BigDecimal(".7")) < 0
                || minimumDepthM.compareTo(new BigDecimal("3")) > 0) {
            throw new IllegalArgumentException("invalid road/tram depth floor");
        }
        this.featureId = featureId;
        this.startM = startM;
        this.endM = endM;
        this.minimumDepthM = minimumDepthM;
    }

    public String getFeatureId() { return featureId; }
    public BigDecimal getStartM() { return startM; }
    public BigDecimal getEndM() { return endM; }
    public BigDecimal getMinimumDepthM() { return minimumDepthM; }
}
