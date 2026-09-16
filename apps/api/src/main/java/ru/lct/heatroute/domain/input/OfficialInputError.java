package ru.lct.heatroute.domain.input;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public class OfficialInputError {
    private final String code;
    private final long featureIndex;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String featureId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String field;

    private final String message;

    @JsonCreator
    public OfficialInputError(
            @JsonProperty("code") String code,
            @JsonProperty("feature_index") long featureIndex,
            @JsonProperty("feature_id") String featureId,
            @JsonProperty("field") String field,
            @JsonProperty("message") String message) {
        this.code = code;
        this.featureIndex = featureIndex;
        this.featureId = featureId;
        this.field = field;
        this.message = message;
    }

    public String getCode() { return code; }
    public long getFeatureIndex() { return featureIndex; }
    public String getFeatureId() { return featureId; }
    public String getField() { return field; }
    public String getMessage() { return message; }
}
