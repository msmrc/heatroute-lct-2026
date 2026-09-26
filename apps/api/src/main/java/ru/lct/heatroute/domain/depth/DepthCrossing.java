package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;

/** A linear-utility crossing projected to a station along one new-network edge. */
public class DepthCrossing {
    private final BigDecimal plateauStartM;
    private final BigDecimal plateauEndM;
    private final String id;
    private final String type;
    private final BigDecimal stationM;
    private final BigDecimal existingTopDepthM;
    private final BigDecimal existingHeightM;
    private final BigDecimal minimumVerticalClearanceM;
    private final BigDecimal specialCostMultiplier;

    public DepthCrossing(
            String id,
            String type,
            BigDecimal stationM,
            BigDecimal existingTopDepthM,
            BigDecimal existingHeightM,
            BigDecimal minimumVerticalClearanceM,
            BigDecimal specialCostMultiplier) {
        this(id, type, stationM, existingTopDepthM, existingHeightM, minimumVerticalClearanceM,
                specialCostMultiplier, stationM.subtract(new BigDecimal("2")), stationM.add(new BigDecimal("2")));
    }

    private DepthCrossing(String id, String type, BigDecimal stationM, BigDecimal existingTopDepthM,
            BigDecimal existingHeightM, BigDecimal minimumVerticalClearanceM, BigDecimal specialCostMultiplier,
            BigDecimal plateauStartM, BigDecimal plateauEndM) {
        if (plateauStartM.compareTo(plateauEndM) >= 0) throw new IllegalArgumentException("physical plateau must have positive length");
        this.plateauStartM = plateauStartM;
        this.plateauEndM = plateauEndM;
        this.id = required(id, "id");
        this.type = required(type, "type");
        this.stationM = positiveOrZero(stationM, "station_m");
        this.existingTopDepthM = positiveOrZero(existingTopDepthM, "existing_top_depth_m");
        this.existingHeightM = positive(existingHeightM, "existing_height_m");
        this.minimumVerticalClearanceM = positiveOrZero(
                minimumVerticalClearanceM, "minimum_vertical_clearance_m");
        this.specialCostMultiplier = positive(specialCostMultiplier, "special_cost_multiplier");
    }

    /** Source-derived bounds in stored chainage, covering two physical XY metres either side. */
    public DepthCrossing withPlateauInterval(BigDecimal startM, BigDecimal endM) {
        return new DepthCrossing(id, type, stationM, existingTopDepthM, existingHeightM,
                minimumVerticalClearanceM, specialCostMultiplier, startM, endM);
    }
    public BigDecimal getPlateauStartM() { return plateauStartM; }
    public BigDecimal getPlateauEndM() { return plateauEndM; }

    public String getId() { return id; }
    public String getType() { return type; }
    public BigDecimal getStationM() { return stationM; }
    public BigDecimal getExistingTopDepthM() { return existingTopDepthM; }
    public BigDecimal getExistingHeightM() { return existingHeightM; }
    public BigDecimal getMinimumVerticalClearanceM() { return minimumVerticalClearanceM; }
    public BigDecimal getSpecialCostMultiplier() { return specialCostMultiplier; }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static BigDecimal positiveOrZero(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(name + " must be non-negative");
        return value;
    }
}
