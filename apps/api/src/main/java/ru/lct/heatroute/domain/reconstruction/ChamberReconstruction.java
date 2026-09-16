package ru.lct.heatroute.domain.reconstruction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import ru.lct.heatroute.domain.routing.RouteCoordinate;

public class ChamberReconstruction {
    private final String existingFeatureId;
    private final RouteCoordinate coordinate;
    private final BigDecimal addedFlowTph;
    private final BigDecimal resultingFlowTph;
    private final int existingDiameter;
    private final int requiredDiameter;

    public ChamberReconstruction(
            String existingFeatureId,
            RouteCoordinate coordinate,
            BigDecimal addedFlowTph,
            BigDecimal resultingFlowTph,
            int existingDiameter,
            int requiredDiameter) {
        this.existingFeatureId = existingFeatureId;
        this.coordinate = coordinate;
        this.addedFlowTph = addedFlowTph.setScale(3, RoundingMode.HALF_UP);
        this.resultingFlowTph = resultingFlowTph.setScale(3, RoundingMode.HALF_UP);
        this.existingDiameter = existingDiameter;
        this.requiredDiameter = requiredDiameter;
    }

    public String getExistingFeatureId() { return existingFeatureId; }
    public RouteCoordinate getCoordinate() { return coordinate; }
    public BigDecimal getAddedFlowTph() { return addedFlowTph; }
    public BigDecimal getResultingFlowTph() { return resultingFlowTph; }
    public int getExistingDiameter() { return existingDiameter; }
    public int getRequiredDiameter() { return requiredDiameter; }
}
