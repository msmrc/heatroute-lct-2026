package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class RouteEdge {
    private final String id;
    private final String upstreamNodeId;
    private final String downstreamNodeId;
    private final BigDecimal lengthM;

    public RouteEdge(String id, String upstreamNodeId, String downstreamNodeId, double lengthM) {
        this.id = id;
        this.upstreamNodeId = upstreamNodeId;
        this.downstreamNodeId = downstreamNodeId;
        this.lengthM = BigDecimal.valueOf(lengthM).setScale(3, RoundingMode.HALF_UP);
    }

    public String getId() { return id; }
    public String getUpstreamNodeId() { return upstreamNodeId; }
    public String getDownstreamNodeId() { return downstreamNodeId; }
    public BigDecimal getLengthM() { return lengthM; }
}
