package ru.lct.heatroute.domain.reconstruction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.lct.heatroute.domain.routing.RouteCoordinate;

public class NetworkReconstructionSection {
    private final String id;
    private final String existingFeatureId;
    private final List<RouteCoordinate> coordinates;
    private final BigDecimal lengthM;
    private final BigDecimal existingFlowTph;
    private final BigDecimal addedFlowTph;
    private final BigDecimal resultingFlowTph;
    private final int existingDiameter;
    private final int requiredDiameter;
    private final boolean partial;

    public NetworkReconstructionSection(
            String id,
            String existingFeatureId,
            List<RouteCoordinate> coordinates,
            double lengthM,
            BigDecimal existingFlowTph,
            BigDecimal addedFlowTph,
            BigDecimal resultingFlowTph,
            int existingDiameter,
            int requiredDiameter,
            boolean partial) {
        this.id = id;
        this.existingFeatureId = existingFeatureId;
        this.coordinates = Collections.unmodifiableList(new ArrayList<>(coordinates));
        this.lengthM = BigDecimal.valueOf(lengthM).setScale(3, RoundingMode.HALF_UP);
        this.existingFlowTph = scaled(existingFlowTph);
        this.addedFlowTph = scaled(addedFlowTph);
        this.resultingFlowTph = scaled(resultingFlowTph);
        this.existingDiameter = existingDiameter;
        this.requiredDiameter = requiredDiameter;
        this.partial = partial;
    }

    public String getId() { return id; }
    public String getExistingFeatureId() { return existingFeatureId; }
    public List<RouteCoordinate> getCoordinates() { return coordinates; }
    public BigDecimal getLengthM() { return lengthM; }
    public BigDecimal getExistingFlowTph() { return existingFlowTph; }
    public BigDecimal getAddedFlowTph() { return addedFlowTph; }
    public BigDecimal getResultingFlowTph() { return resultingFlowTph; }
    public int getExistingDiameter() { return existingDiameter; }
    public int getRequiredDiameter() { return requiredDiameter; }
    public boolean isPartial() { return partial; }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
