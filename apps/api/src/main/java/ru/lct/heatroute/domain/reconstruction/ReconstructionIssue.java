package ru.lct.heatroute.domain.reconstruction;

public class ReconstructionIssue {
    private final String code;
    private final String subjectId;
    private final String message;

    public ReconstructionIssue(String code, String subjectId, String message) {
        this.code = code;
        this.subjectId = subjectId;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getSubjectId() { return subjectId; }
    public String getMessage() { return message; }
}
