package ru.lct.heatroute.domain.economics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VariantEconomics {
    private final boolean complete;
    private final BigDecimal constructionCost;
    private final BigDecimal chamberConstructionCost;
    private final BigDecimal tieInCost;
    private final BigDecimal reconstructionCost;
    private final BigDecimal chamberReconstructionCost;
    private final BigDecimal unconnectedPenalty;
    private final BigDecimal calculatedCost;
    private final BigDecimal newNetworkLength;
    private final BigDecimal reconstructionLength;
    private final BigDecimal length;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final BigDecimal score;
    private final List<String> incompleteReasons;

    public VariantEconomics(
            boolean complete,
            BigDecimal constructionCost,
            BigDecimal chamberConstructionCost,
            BigDecimal tieInCost,
            BigDecimal reconstructionCost,
            BigDecimal chamberReconstructionCost,
            BigDecimal unconnectedPenalty,
            BigDecimal calculatedCost,
            BigDecimal newNetworkLength,
            BigDecimal reconstructionLength,
            BigDecimal length,
            BigDecimal score,
            List<String> incompleteReasons) {
        this.complete = complete;
        this.constructionCost = constructionCost;
        this.chamberConstructionCost = chamberConstructionCost;
        this.tieInCost = tieInCost;
        this.reconstructionCost = reconstructionCost;
        this.chamberReconstructionCost = chamberReconstructionCost;
        this.unconnectedPenalty = unconnectedPenalty;
        this.calculatedCost = calculatedCost;
        this.newNetworkLength = newNetworkLength;
        this.reconstructionLength = reconstructionLength;
        this.length = length;
        this.score = score;
        this.incompleteReasons = Collections.unmodifiableList(new ArrayList<>(incompleteReasons));
    }

    public boolean isComplete() { return complete; }
    public BigDecimal getConstructionCost() { return constructionCost; }
    public BigDecimal getChamberConstructionCost() { return chamberConstructionCost; }
    public BigDecimal getTieInCost() { return tieInCost; }
    public BigDecimal getReconstructionCost() { return reconstructionCost; }
    public BigDecimal getChamberReconstructionCost() { return chamberReconstructionCost; }
    public BigDecimal getUnconnectedPenalty() { return unconnectedPenalty; }
    public BigDecimal getCalculatedCost() { return calculatedCost; }
    public BigDecimal getNewNetworkLength() { return newNetworkLength; }
    public BigDecimal getReconstructionLength() { return reconstructionLength; }
    public BigDecimal getLength() { return length; }
    public BigDecimal getScore() { return score; }
    public List<String> getIncompleteReasons() { return incompleteReasons; }
}
