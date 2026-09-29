package ru.lct.heatroute.domain.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OfficialRunView {
    private final UUID id;
    private final UUID importId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final UUID jobId;
    private final String state;
    private final String algorithmVersion;
    private final String inputSha256;
    private final OfficialRunParameters parameters;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final JsonNode result;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String errorCode;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String errorMessage;
    private final OffsetDateTime createdAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final OffsetDateTime completedAt;

    public OfficialRunView(
            UUID id,
            UUID importId,
            UUID jobId,
            String state,
            String algorithmVersion,
            String inputSha256,
            OfficialRunParameters parameters,
            JsonNode result,
            String errorCode,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt) {
        this.id = id;
        this.importId = importId;
        this.jobId = jobId;
        this.state = state;
        this.algorithmVersion = algorithmVersion;
        this.inputSha256 = inputSha256;
        this.parameters = parameters;
        this.result = result;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public UUID getId() { return id; }
    public UUID getImportId() { return importId; }
    public UUID getJobId() { return jobId; }
    public String getState() { return state; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public String getInputSha256() { return inputSha256; }
    public OfficialRunParameters getParameters() { return parameters; }
    public JsonNode getResult() { return result; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
