package ru.lct.heatroute.api.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Collections.emptyMap());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
