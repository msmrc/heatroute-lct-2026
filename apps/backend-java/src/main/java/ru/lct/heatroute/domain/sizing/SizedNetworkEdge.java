package ru.lct.heatroute.domain.sizing;

import java.math.BigDecimal;

public class SizedNetworkEdge {
    private final String edgeId;
    private final BigDecimal flowTph;
    private final Integer diameter;
    private final BigDecimal continuousSameDiameterLengthM;

    public SizedNetworkEdge(
            String edgeId,
            BigDecimal flowTph,
            Integer diameter,
            BigDecimal continuousSameDiameterLengthM) {
        this.edgeId = edgeId;
        this.flowTph = flowTph;
        this.diameter = diameter;
        this.continuousSameDiameterLengthM = continuousSameDiameterLengthM;
    }

    public String getEdgeId() { return edgeId; }
    public BigDecimal getFlowTph() { return flowTph; }
    public Integer getDiameter() { return diameter; }
    public BigDecimal getContinuousSameDiameterLengthM() { return continuousSameDiameterLengthM; }
}
