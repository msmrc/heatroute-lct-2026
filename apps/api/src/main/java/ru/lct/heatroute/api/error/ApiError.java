package ru.lct.heatroute.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ApiError {
    private final String code;
    private final String message;
    private final String requestId;
    private final boolean retryable;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final Map<String, Object> details;

    public ApiError(
            String code,
            String message,
            String requestId,
            boolean retryable,
            Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.requestId = requestId;
        this.retryable = retryable;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getRequestId() { return requestId; }
    public boolean isRetryable() { return retryable; }
    public Map<String, Object> getDetails() { return details; }
}
