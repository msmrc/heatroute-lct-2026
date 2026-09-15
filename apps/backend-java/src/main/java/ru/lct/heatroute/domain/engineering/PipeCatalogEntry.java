package ru.lct.heatroute.domain.engineering;

import java.math.BigDecimal;

public class PipeCatalogEntry {
    private final int diameter;
    private final BigDecimal maxFlowTph;
    private final int maxContinuousLengthM;
    private final BigDecimal newConstructionRubPerM;
    private final BigDecimal reconstructionRubPerM;
    private final BigDecimal outerDiameterM;
    private final BigDecimal shellGapM;
    private final BigDecimal pairWidthM;
    private final BigDecimal envelopeHeightM;

    public PipeCatalogEntry(
            int diameter,
            String maxFlowTph,
            int maxContinuousLengthM,
            int newConstructionRubPerM,
            int reconstructionRubPerM,
            String outerDiameterM,
            String shellGapM,
            String pairWidthM,
            String envelopeHeightM) {
        this.diameter = diameter;
        this.maxFlowTph = new BigDecimal(maxFlowTph);
        this.maxContinuousLengthM = maxContinuousLengthM;
        this.newConstructionRubPerM = BigDecimal.valueOf(newConstructionRubPerM);
        this.reconstructionRubPerM = BigDecimal.valueOf(reconstructionRubPerM);
        this.outerDiameterM = new BigDecimal(outerDiameterM);
        this.shellGapM = new BigDecimal(shellGapM);
        this.pairWidthM = new BigDecimal(pairWidthM);
        this.envelopeHeightM = new BigDecimal(envelopeHeightM);
    }

    public int getDiameter() { return diameter; }
    public BigDecimal getMaxFlowTph() { return maxFlowTph; }
    public int getMaxContinuousLengthM() { return maxContinuousLengthM; }
    public BigDecimal getNewConstructionRubPerM() { return newConstructionRubPerM; }
    public BigDecimal getReconstructionRubPerM() { return reconstructionRubPerM; }
    public BigDecimal getOuterDiameterM() { return outerDiameterM; }
    public BigDecimal getShellGapM() { return shellGapM; }
    public BigDecimal getPairWidthM() { return pairWidthM; }
    public BigDecimal getEnvelopeHeightM() { return envelopeHeightM; }
}
