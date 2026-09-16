package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DepthCrossingDecision {
    private final String crossingId;
    private final String crossingType;
    private final String passage;
    private final BigDecimal depthM;
    private final BigDecimal rampStartM;
    private final BigDecimal plateauStartM;
    private final BigDecimal plateauEndM;
    private final BigDecimal rampEndM;
    private final BigDecimal verticalClearanceM;
    private final BigDecimal requiredClearanceM;

    public DepthCrossingDecision(
            String crossingId,
            String crossingType,
            String passage,
            BigDecimal depthM,
            BigDecimal rampStartM,
            BigDecimal plateauStartM,
            BigDecimal plateauEndM,
            BigDecimal rampEndM,
            BigDecimal verticalClearanceM,
            BigDecimal requiredClearanceM) {
        this.crossingId = crossingId;
        this.crossingType = crossingType;
        this.passage = passage;
        this.depthM = rounded(depthM);
        this.rampStartM = rounded(rampStartM);
        this.plateauStartM = rounded(plateauStartM);
        this.plateauEndM = rounded(plateauEndM);
        this.rampEndM = rounded(rampEndM);
        this.verticalClearanceM = rounded(verticalClearanceM);
        this.requiredClearanceM = rounded(requiredClearanceM);
    }

    public String getCrossingId() { return crossingId; }
    public String getCrossingType() { return crossingType; }
    public String getPassage() { return passage; }
    public BigDecimal getDepthM() { return depthM; }
    public BigDecimal getRampStartM() { return rampStartM; }
    public BigDecimal getPlateauStartM() { return plateauStartM; }
    public BigDecimal getPlateauEndM() { return plateauEndM; }
    public BigDecimal getRampEndM() { return rampEndM; }
    public BigDecimal getVerticalClearanceM() { return verticalClearanceM; }
    public BigDecimal getRequiredClearanceM() { return requiredClearanceM; }

    private static BigDecimal rounded(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
