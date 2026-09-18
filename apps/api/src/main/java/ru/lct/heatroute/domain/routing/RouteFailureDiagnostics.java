package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteFailureDiagnostics {
    private final int candidateCount;
    private final int attemptedCandidateCount;
    private final List<String> attemptedTargetIds;
    private final List<String> directBlockers;
    private final double maximumSearchCorridorM;

    public RouteFailureDiagnostics(
            int candidateCount,
            int attemptedCandidateCount,
            List<String> attemptedTargetIds,
            List<String> directBlockers,
            double maximumSearchCorridorM) {
        this.candidateCount = candidateCount;
        this.attemptedCandidateCount = attemptedCandidateCount;
        this.attemptedTargetIds = immutable(attemptedTargetIds);
        this.directBlockers = immutable(directBlockers);
        this.maximumSearchCorridorM = maximumSearchCorridorM;
    }

    public int getCandidateCount() { return candidateCount; }
    public int getAttemptedCandidateCount() { return attemptedCandidateCount; }
    public List<String> getAttemptedTargetIds() { return attemptedTargetIds; }
    public List<String> getDirectBlockers() { return directBlockers; }
    public double getMaximumSearchCorridorM() { return maximumSearchCorridorM; }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
