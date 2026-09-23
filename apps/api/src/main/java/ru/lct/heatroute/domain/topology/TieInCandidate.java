package ru.lct.heatroute.domain.topology;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class TieInCandidate {
    private final String connectionPointId;
    private final String targetId;
    private final String targetType;
    private final BigDecimal distanceM;
    private final boolean newChamberRequired;
    private final Double tieInXm;
    private final Double tieInYm;

    public TieInCandidate(
            String connectionPointId,
            String targetId,
            String targetType,
            double distanceM,
            boolean newChamberRequired) {
        this(connectionPointId, targetId, targetType, distanceM, newChamberRequired, null, null);
    }

    public TieInCandidate(
            String connectionPointId,
            String targetId,
            String targetType,
            double distanceM,
            boolean newChamberRequired,
            Double tieInXm,
            Double tieInYm) {
        this.connectionPointId = connectionPointId;
        this.targetId = targetId;
        this.targetType = targetType;
        this.distanceM = BigDecimal.valueOf(distanceM).setScale(3, RoundingMode.HALF_UP);
        this.newChamberRequired = newChamberRequired;
        this.tieInXm = tieInXm;
        this.tieInYm = tieInYm;
    }

    public String getConnectionPointId() { return connectionPointId; }
    public String getTargetId() { return targetId; }
    public String getTargetType() { return targetType; }
    public BigDecimal getDistanceM() { return distanceM; }
    public boolean isNewChamberRequired() { return newChamberRequired; }
    public boolean hasFixedTieIn() { return tieInXm != null && tieInYm != null; }
    public Double getTieInXm() { return tieInXm; }
    public Double getTieInYm() { return tieInYm; }
}
