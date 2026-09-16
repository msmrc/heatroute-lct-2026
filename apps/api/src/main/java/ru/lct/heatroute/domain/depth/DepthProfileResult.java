package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DepthProfileResult {
    private final boolean complete;
    private final List<DepthProfilePoint> points;
    private final List<DepthCrossingDecision> crossings;
    private final List<DepthProfileIssue> issues;
    private final BigDecimal profileLength3dM;
    private final BigDecimal depthAdjustedCostMeters;

    public DepthProfileResult(
            boolean complete,
            List<DepthProfilePoint> points,
            List<DepthCrossingDecision> crossings,
            List<DepthProfileIssue> issues,
            BigDecimal profileLength3dM,
            BigDecimal depthAdjustedCostMeters) {
        this.complete = complete;
        this.points = immutable(points);
        this.crossings = immutable(crossings);
        this.issues = immutable(issues);
        this.profileLength3dM = rounded(profileLength3dM);
        this.depthAdjustedCostMeters = rounded(depthAdjustedCostMeters);
    }

    public boolean isComplete() { return complete; }
    public List<DepthProfilePoint> getPoints() { return points; }
    public List<DepthCrossingDecision> getCrossings() { return crossings; }
    public List<DepthProfileIssue> getIssues() { return issues; }
    public BigDecimal getProfileLength3dM() { return profileLength3dM; }
    public BigDecimal getDepthAdjustedCostMeters() { return depthAdjustedCostMeters; }

    private static BigDecimal rounded(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
