package ru.lct.heatroute.domain.topology;

import com.fasterxml.jackson.annotation.JsonInclude;

public class TopologyIssue {
    private final String code;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String featureId;
    private final String message;

    public TopologyIssue(String code, String featureId, String message) {
        this.code = code;
        this.featureId = featureId;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getFeatureId() { return featureId; }
    public String getMessage() { return message; }
}
