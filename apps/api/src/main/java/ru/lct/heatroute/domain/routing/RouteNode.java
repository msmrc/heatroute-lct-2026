package ru.lct.heatroute.domain.routing;

public class RouteNode {
    private final String id;
    private final String nodeType;
    private final RouteCoordinate coordinate;
    private final boolean chamber;
    private final boolean root;
    private final int baseIncidentSections;
    private final String targetId;

    public RouteNode(
            String id,
            String nodeType,
            RouteCoordinate coordinate,
            boolean chamber,
            boolean root,
            int baseIncidentSections,
            String targetId) {
        this.id = id;
        this.nodeType = nodeType;
        this.coordinate = coordinate;
        this.chamber = chamber;
        this.root = root;
        this.baseIncidentSections = baseIncidentSections;
        this.targetId = targetId;
    }

    public String getId() { return id; }
    public String getNodeType() { return nodeType; }
    public RouteCoordinate getCoordinate() { return coordinate; }
    public boolean isChamber() { return chamber; }
    public boolean isRoot() { return root; }
    public int getBaseIncidentSections() { return baseIncidentSections; }
    public String getTargetId() { return targetId; }
}
