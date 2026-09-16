package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.annotation.JsonInclude;

public class RouteValidationIssue {
    private final String code;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String subjectId;
    private final String message;

    public RouteValidationIssue(String code, String subjectId, String message) {
        this.code = code;
        this.subjectId = subjectId;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getSubjectId() { return subjectId; }
    public String getMessage() { return message; }
}
