package ru.lct.heatroute.domain.sizing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NetworkSizingResult {
    private final Map<String, SizedNetworkEdge> edges;
    private final List<NetworkSizingIssue> issues;

    public NetworkSizingResult(Map<String, SizedNetworkEdge> edges, List<NetworkSizingIssue> issues) {
        this.edges = Collections.unmodifiableMap(new LinkedHashMap<>(edges));
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
    }

    public Map<String, SizedNetworkEdge> getEdges() { return edges; }
    public List<NetworkSizingIssue> getIssues() { return issues; }
    public boolean isValid() { return issues.isEmpty(); }
}
