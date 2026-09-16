package ru.lct.heatroute.api.error;

public class ApiErrorResponse {
    private final ApiError error;

    public ApiErrorResponse(ApiError error) {
        this.error = error;
    }

    public ApiError getError() {
        return error;
    }
}
