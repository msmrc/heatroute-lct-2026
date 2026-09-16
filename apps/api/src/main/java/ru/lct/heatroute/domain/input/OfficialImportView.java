package ru.lct.heatroute.domain.input;

import java.time.OffsetDateTime;
import java.util.UUID;

public class OfficialImportView {
    private final UUID id;
    private final String state;
    private final String originalFilename;
    private final long inputSizeBytes;
    private final OffsetDateTime createdAt;
    private final OfficialInputReport report;

    public OfficialImportView(
            UUID id,
            String state,
            String originalFilename,
            long inputSizeBytes,
            OffsetDateTime createdAt,
            OfficialInputReport report) {
        this.id = id;
        this.state = state;
        this.originalFilename = originalFilename;
        this.inputSizeBytes = inputSizeBytes;
        this.createdAt = createdAt;
        this.report = report;
    }

    public UUID getId() { return id; }
    public String getState() { return state; }
    public String getOriginalFilename() { return originalFilename; }
    public long getInputSizeBytes() { return inputSizeBytes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OfficialInputReport getReport() { return report; }
}
