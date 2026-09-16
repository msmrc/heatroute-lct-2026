package ru.lct.heatroute.domain.topology;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class TieInCandidate {
    private final String connectionPointId;
    private final String targetId;
    private final String targetType;
    private final BigDecimal distanceM;
    private final boolean newChamberRequired;

    public TieInCandidate(
            String connectionPointId,
            String targetId,
            String targetType,
            double distanceM,
            boolean newChamberRequired) {
        this.connectionPointId = connectionPointId;
        this.targetId = targetId;
        this.targetType = targetType;
        this.distanceM = BigDecimal.valueOf(distanceM).setScale(3, RoundingMode.HALF_UP);
        this.newChamberRequired = newChamberRequired;
    }

    public String getConnectionPointId() { return connectionPointId; }
    public String getTargetId() { return targetId; }
    public String getTargetType() { return targetType; }
    public BigDecimal getDistanceM() { return distanceM; }
    public boolean isNewChamberRequired() { return newChamberRequired; }
}
