package ru.lct.heatroute.domain.routing;

import java.util.Objects;

/** A bounded NextGen execution ended without an exactly validated publishable result. */
public final class NextGenerationPlanningIncompleteException extends IllegalStateException {
    private final AdaptiveCatalogNetworkSearch.Outcome outcome;
    private final String reason;
    private final String snapshotHash;

    public NextGenerationPlanningIncompleteException(
            NextGenerationRoutePlanner.Execution execution) {
        super(message(Objects.requireNonNull(execution, "execution")));
        this.outcome = execution.getOutcome();
        this.reason = execution.getReason();
        this.snapshotHash = execution.getSnapshotHash();
    }

    private static String message(NextGenerationRoutePlanner.Execution execution) {
        return "Next-generation planning did not produce an accepted result"
                + " (outcome=" + execution.getOutcome()
                + ", reason=" + execution.getReason()
                + ", snapshot=" + execution.getSnapshotHash()
                + ", features=" + execution.getFeatureCount()
                + ", refinement_runs=" + execution.getRefinementRuns()
                + ", conflicts=" + execution.getConflictCount()
                + ", archive=" + execution.getArchiveSize() + ")";
    }

    public AdaptiveCatalogNetworkSearch.Outcome getOutcome() { return outcome; }
    public String getReason() { return reason; }
    public String getSnapshotHash() { return snapshotHash; }
}
