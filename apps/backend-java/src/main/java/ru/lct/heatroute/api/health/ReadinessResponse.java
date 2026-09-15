package ru.lct.heatroute.api.health;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReadinessResponse {
    private final String status;
    private final Map<String, DependencyStatus> checks;

    public ReadinessResponse(String status, Map<String, DependencyStatus> checks) {
        this.status = status;
        this.checks = Collections.unmodifiableMap(new LinkedHashMap<>(checks));
    }

    public String getStatus() {
        return status;
    }

    public Map<String, DependencyStatus> getChecks() {
        return checks;
    }

    @JsonIgnore
    public boolean isReady() {
        return "ready".equals(status);
    }
}
