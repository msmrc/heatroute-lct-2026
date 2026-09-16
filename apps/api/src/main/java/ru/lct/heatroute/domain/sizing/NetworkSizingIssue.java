package ru.lct.heatroute.domain.sizing;

import com.fasterxml.jackson.annotation.JsonInclude;

public class NetworkSizingIssue {
    private final String code;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String edgeId;
    private final String message;

    public NetworkSizingIssue(String code, String edgeId, String message) {
        this.code = code;
        this.edgeId = edgeId;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getEdgeId() { return edgeId; }
    public String getMessage() { return message; }
}
