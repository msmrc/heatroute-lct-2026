package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DepthProfilePoint {
    private final BigDecimal stationM;
    private final BigDecimal depthM;

    public DepthProfilePoint(BigDecimal stationM, BigDecimal depthM) {
        this.stationM = rounded(stationM);
        this.depthM = rounded(depthM);
    }

    public BigDecimal getStationM() { return stationM; }
    public BigDecimal getDepthM() { return depthM; }

    private static BigDecimal rounded(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("profile coordinate is required");
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
