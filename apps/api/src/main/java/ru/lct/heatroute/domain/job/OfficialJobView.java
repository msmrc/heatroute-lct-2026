package ru.lct.heatroute.domain.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public class OfficialJobView {
    private final UUID id;
    private final UUID importId;
    private final String jobType;
    private final String state;
    private final String phase;
    private final long progressCurrent;
    private final long progressTotal;
    private final int attempt;
    private final boolean cancellationRequested;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final JsonNode result;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String errorCode;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String errorMessage;
    private final OffsetDateTime createdAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final OffsetDateTime completedAt;

    public OfficialJobView(
            UUID id,
            UUID importId,
            String jobType,
            String state,
            String phase,
            long progressCurrent,
            long progressTotal,
            int attempt,
            boolean cancellationRequested,
            JsonNode result,
            String errorCode,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt) {
        this.id = id;
        this.importId = importId;
        this.jobType = jobType;
        this.state = state;
        this.phase = phase;
        this.progressCurrent = progressCurrent;
        this.progressTotal = progressTotal;
        this.attempt = attempt;
        this.cancellationRequested = cancellationRequested;
        this.result = result;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public UUID getId() { return id; }
    public UUID getImportId() { return importId; }
    public String getJobType() { return jobType; }
    public String getState() { return state; }
    public String getPhase() { return phase; }
    public long getProgressCurrent() { return progressCurrent; }
    public long getProgressTotal() { return progressTotal; }
    public int getAttempt() { return attempt; }
    public boolean isCancellationRequested() { return cancellationRequested; }
    public JsonNode getResult() { return result; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
