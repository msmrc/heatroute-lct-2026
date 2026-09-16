package ru.lct.heatroute.domain.engineering;

import java.math.BigDecimal;

public enum SpecialCrossingType {
    BASE("1.00"),
    ROAD("1.60"),
    TRAM_TRACKS("1.75"),
    GAS_PIPELINE("1.25"),
    POWER_CABLE("1.15"),
    HEAT_NETWORK("1.05");

    private final BigDecimal costMultiplier;

    SpecialCrossingType(String costMultiplier) {
        this.costMultiplier = new BigDecimal(costMultiplier);
    }

    public BigDecimal getCostMultiplier() {
        return costMultiplier;
    }
}
