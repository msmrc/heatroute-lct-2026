package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.sizing.NetworkSizingIssue;

public class RouteVariant {
    private final String id;
    private final String strategy;
    private final List<RouteNode> nodes;
    private final List<RouteEdge> edges;
    private final List<RouteConnection> connections;
    private final BigDecimal totalLengthM;
    private final List<RouteValidationIssue> validationIssues;
    private final List<NetworkSizingIssue> sizingIssues;
    private final ExistingNetworkReconstructionResult reconstruction;

    public RouteVariant(
            String id,
            String strategy,
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<RouteConnection> connections,
            BigDecimal totalLengthM,
            List<RouteValidationIssue> validationIssues,
            List<NetworkSizingIssue> sizingIssues,
            ExistingNetworkReconstructionResult reconstruction) {
        this.id = id;
        this.strategy = strategy;
        this.nodes = immutable(nodes);
        this.edges = immutable(edges);
        this.connections = immutable(connections);
        this.totalLengthM = totalLengthM;
        this.validationIssues = immutable(validationIssues);
        this.sizingIssues = immutable(sizingIssues);
        this.reconstruction = reconstruction;
    }

    public String getId() { return id; }
    public String getStrategy() { return strategy; }
    public List<RouteNode> getNodes() { return nodes; }
    public List<RouteEdge> getEdges() { return edges; }
    public List<RouteConnection> getConnections() { return connections; }
    public BigDecimal getTotalLengthM() { return totalLengthM; }
    public List<RouteValidationIssue> getValidationIssues() { return validationIssues; }
    public List<NetworkSizingIssue> getSizingIssues() { return sizingIssues; }
    public ExistingNetworkReconstructionResult getReconstruction() { return reconstruction; }
    public boolean isValid() { return validationIssues.isEmpty(); }
    public long getConnectedDemandCount() {
        return connections.stream().filter(connection -> "connected".equals(connection.getStatus())).count();
    }
    public long getNoRouteDemandCount() {
        return connections.stream().filter(connection -> "no_route".equals(connection.getStatus())).count();
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
