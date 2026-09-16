package ru.lct.heatroute.domain.engineering;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class OfficialEconomics {
    private static final BigDecimal SCORE_COST_DENOMINATOR = new BigDecimal("25000000");
    private static final BigDecimal SCORE_LENGTH_DENOMINATOR = new BigDecimal("100");
    private static final BigDecimal UNCONNECTED_BASE = new BigDecimal("100000000");
    private static final BigDecimal UNCONNECTED_FLOW_RATE = new BigDecimal("500000");

    public BigDecimal newNetworkCost(
            PipeCatalogEntry pipe,
            BigDecimal lengthM,
            SpecialCrossingType crossingType,
            BigDecimal averageDepthM) {
        requireNonNegative(lengthM, "length_m");
        return money(pipe.getNewConstructionRubPerM()
                .multiply(lengthM)
                .multiply(crossingType.getCostMultiplier())
                .multiply(depthMultiplier(averageDepthM)));
    }

    public BigDecimal reconstructionCost(PipeCatalogEntry pipe, BigDecimal lengthM) {
        requireNonNegative(lengthM, "length_m");
        return money(pipe.getReconstructionRubPerM().multiply(lengthM));
    }

    public BigDecimal unconnectedPenalty(BigDecimal flowTph) {
        requireNonNegative(flowTph, "flow_tph");
        return money(UNCONNECTED_BASE.add(UNCONNECTED_FLOW_RATE.multiply(flowTph)));
    }

    public BigDecimal score(BigDecimal calculatedCostRub, BigDecimal totalLengthM) {
        requireNonNegative(calculatedCostRub, "calculated_cost");
        requireNonNegative(totalLengthM, "length");
        return calculatedCostRub.divide(SCORE_COST_DENOMINATOR, 12, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("0.7"))
                .add(totalLengthM.divide(SCORE_LENGTH_DENOMINATOR, 12, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("0.3")))
                .setScale(9, RoundingMode.HALF_UP);
    }

    public BigDecimal depthMultiplier(BigDecimal depthM) {
        if (depthM == null || depthM.compareTo(new BigDecimal("0.7")) < 0) {
            throw new IllegalArgumentException("depth_m must be at least 0.7");
        }
        if (depthM.compareTo(new BigDecimal("3.0")) <= 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.add(depthM.subtract(new BigDecimal("3.0")).multiply(new BigDecimal("0.10")));
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private void requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
