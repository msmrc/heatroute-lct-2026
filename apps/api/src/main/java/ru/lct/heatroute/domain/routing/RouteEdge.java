package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteEdge {
    private final String id;
    private final String upstreamNodeId;
    private final String downstreamNodeId;
    private final BigDecimal lengthM;
    private final List<RouteCoordinate> coordinates;
    private final List<RouteSection> sections;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final BigDecimal flowTph;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Integer diameter;

    public RouteEdge(String id, String upstreamNodeId, String downstreamNodeId, double lengthM) {
        this(id, upstreamNodeId, downstreamNodeId, lengthM, Collections.emptyList(),
                Collections.emptyList(), null, null);
    }

    public RouteEdge(
            String id,
            String upstreamNodeId,
            String downstreamNodeId,
            double lengthM,
            List<RouteCoordinate> coordinates,
            List<RouteSection> sections,
            BigDecimal flowTph,
            Integer diameter) {
        this.id = id;
        this.upstreamNodeId = upstreamNodeId;
        this.downstreamNodeId = downstreamNodeId;
        this.lengthM = BigDecimal.valueOf(lengthM).setScale(3, RoundingMode.HALF_UP);
        this.coordinates = Collections.unmodifiableList(new ArrayList<>(coordinates));
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        this.flowTph = flowTph == null ? null : flowTph.setScale(3, RoundingMode.HALF_UP);
        this.diameter = diameter;
    }

    public String getId() { return id; }
    public String getUpstreamNodeId() { return upstreamNodeId; }
    public String getDownstreamNodeId() { return downstreamNodeId; }
    public BigDecimal getLengthM() { return lengthM; }
    public List<RouteCoordinate> getCoordinates() { return coordinates; }
    public List<RouteSection> getSections() { return sections; }
    public BigDecimal getFlowTph() { return flowTph; }
    public Integer getDiameter() { return diameter; }
}
