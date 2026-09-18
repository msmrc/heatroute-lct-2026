package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

public class RouteConnection {
    private final String demandId;
    private final String connectionPointId;
    private final BigDecimal flowTph;
    private final String status;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String reason;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final RouteFailureDiagnostics diagnostics;

    public RouteConnection(
            String demandId,
            String connectionPointId,
            BigDecimal flowTph,
            String status,
            String reason) {
        this(demandId, connectionPointId, flowTph, status, reason, null);
    }

    public RouteConnection(
            String demandId,
            String connectionPointId,
            BigDecimal flowTph,
            String status,
            String reason,
            RouteFailureDiagnostics diagnostics) {
        this.demandId = demandId;
        this.connectionPointId = connectionPointId;
        this.flowTph = flowTph;
        this.status = status;
        this.reason = reason;
        this.diagnostics = diagnostics;
    }

    public String getDemandId() { return demandId; }
    public String getConnectionPointId() { return connectionPointId; }
    public BigDecimal getFlowTph() { return flowTph; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public RouteFailureDiagnostics getDiagnostics() { return diagnostics; }
}
