package ru.lct.heatroute.domain.topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TopologyAnalysis {
    private final int sourceCount;
    private final int networkSegmentCount;
    private final int chamberCount;
    private final List<TopologyIssue> issues;
    private final List<TieInCandidate> tieInCandidates;

    public TopologyAnalysis(
            int sourceCount,
            int networkSegmentCount,
            int chamberCount,
            List<TopologyIssue> issues,
            List<TieInCandidate> tieInCandidates) {
        this.sourceCount = sourceCount;
        this.networkSegmentCount = networkSegmentCount;
        this.chamberCount = chamberCount;
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        this.tieInCandidates = Collections.unmodifiableList(new ArrayList<>(tieInCandidates));
    }

    public int getSourceCount() { return sourceCount; }
    public int getNetworkSegmentCount() { return networkSegmentCount; }
    public int getChamberCount() { return chamberCount; }
    public List<TopologyIssue> getIssues() { return issues; }
    public List<TieInCandidate> getTieInCandidates() { return tieInCandidates; }
    public boolean isValid() { return issues.isEmpty(); }
}
