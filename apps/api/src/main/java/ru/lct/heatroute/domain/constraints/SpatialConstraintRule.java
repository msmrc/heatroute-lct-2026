package ru.lct.heatroute.domain.constraints;

import java.math.BigDecimal;

public class SpatialConstraintRule {
    private final String type;
    private final boolean forbidden;
    private final BigDecimal horizontalClearanceM;
    private final BigDecimal verticalClearanceM;
    private final BigDecimal minimumCrossingAngleDegrees;
    private final BigDecimal specialExtensionM;
    private final BigDecimal minimumTopBelowSurfaceM;
    private final BigDecimal costMultiplier;

    public SpatialConstraintRule(
            String type,
            boolean forbidden,
            String horizontalClearanceM,
            String verticalClearanceM,
            String minimumCrossingAngleDegrees,
            String specialExtensionM,
            String minimumTopBelowSurfaceM,
            String costMultiplier) {
        this.type = type;
        this.forbidden = forbidden;
        this.horizontalClearanceM = decimal(horizontalClearanceM);
        this.verticalClearanceM = decimal(verticalClearanceM);
        this.minimumCrossingAngleDegrees = decimal(minimumCrossingAngleDegrees);
        this.specialExtensionM = decimal(specialExtensionM);
        this.minimumTopBelowSurfaceM = decimal(minimumTopBelowSurfaceM);
        this.costMultiplier = decimal(costMultiplier);
    }

    public String getType() { return type; }
    public boolean isForbidden() { return forbidden; }
    public BigDecimal getHorizontalClearanceM() { return horizontalClearanceM; }
    public BigDecimal getVerticalClearanceM() { return verticalClearanceM; }
    public BigDecimal getMinimumCrossingAngleDegrees() { return minimumCrossingAngleDegrees; }
    public BigDecimal getSpecialExtensionM() { return specialExtensionM; }
    public BigDecimal getMinimumTopBelowSurfaceM() { return minimumTopBelowSurfaceM; }
    public BigDecimal getCostMultiplier() { return costMultiplier; }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
