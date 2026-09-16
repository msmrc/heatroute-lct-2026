package ru.lct.heatroute.domain.depth;

public class DepthProfileIssue {
    private final String code;
    private final String crossingId;
    private final String message;

    public DepthProfileIssue(String code, String crossingId, String message) {
        this.code = code;
        this.crossingId = crossingId;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getCrossingId() { return crossingId; }
    public String getMessage() { return message; }
}
