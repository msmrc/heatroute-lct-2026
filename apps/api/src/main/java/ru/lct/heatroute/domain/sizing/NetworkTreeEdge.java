package ru.lct.heatroute.domain.sizing;

import java.math.BigDecimal;

public class NetworkTreeEdge {
    private final String id;
    private final String upstreamNodeId;
    private final String downstreamNodeId;
    private final BigDecimal lengthM;

    public NetworkTreeEdge(String id, String upstreamNodeId, String downstreamNodeId, BigDecimal lengthM) {
        if (id == null || upstreamNodeId == null || downstreamNodeId == null) {
            throw new IllegalArgumentException("edge and node IDs are required");
        }
        if (lengthM == null || lengthM.signum() <= 0) {
            throw new IllegalArgumentException("edge length must be positive");
        }
        this.id = id;
        this.upstreamNodeId = upstreamNodeId;
        this.downstreamNodeId = downstreamNodeId;
        this.lengthM = lengthM;
    }

    public String getId() { return id; }
    public String getUpstreamNodeId() { return upstreamNodeId; }
    public String getDownstreamNodeId() { return downstreamNodeId; }
    public BigDecimal getLengthM() { return lengthM; }
}
