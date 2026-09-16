package ru.lct.heatroute.domain.reconstruction;

import java.math.BigDecimal;
import ru.lct.heatroute.domain.routing.RouteCoordinate;

public class TieInLoad {
    private final String targetId;
    private final RouteCoordinate coordinate;
    private final BigDecimal addedFlowTph;

    public TieInLoad(String targetId, RouteCoordinate coordinate, BigDecimal addedFlowTph) {
        this.targetId = targetId;
        this.coordinate = coordinate;
        this.addedFlowTph = addedFlowTph;
    }

    public String getTargetId() { return targetId; }
    public RouteCoordinate getCoordinate() { return coordinate; }
    public BigDecimal getAddedFlowTph() { return addedFlowTph; }
}
