package ru.lct.heatroute.api.health;

import com.fasterxml.jackson.annotation.JsonInclude;

public class DependencyStatus {
    private final String status;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String detail;

    public DependencyStatus(String status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    public static DependencyStatus ok(String detail) {
        return new DependencyStatus("ok", detail);
    }

    public static DependencyStatus error(String detail) {
        return new DependencyStatus("error", detail);
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }
}
