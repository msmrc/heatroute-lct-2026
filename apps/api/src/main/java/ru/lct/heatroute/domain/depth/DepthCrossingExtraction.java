package ru.lct.heatroute.domain.depth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DepthCrossingExtraction {
    private final List<DepthCrossing> crossings;
    private final List<DepthProfileIssue> issues;

    public DepthCrossingExtraction(List<DepthCrossing> crossings, List<DepthProfileIssue> issues) {
        this.crossings = immutable(crossings);
        this.issues = immutable(issues);
    }

    public List<DepthCrossing> getCrossings() { return crossings; }
    public List<DepthProfileIssue> getIssues() { return issues; }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
