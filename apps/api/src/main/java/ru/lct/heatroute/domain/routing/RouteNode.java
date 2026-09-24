package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.annotation.JsonInclude;

public class RouteNode {
    private final String id;
    private final String nodeType;
    private final RouteCoordinate coordinate;
    private final boolean chamber;
    private final boolean root;
    private final int baseIncidentSections;
    private final String targetId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Integer existingIncidentDiameter;

    public RouteNode(
            String id,
            String nodeType,
            RouteCoordinate coordinate,
            boolean chamber,
            boolean root,
            int baseIncidentSections,
            String targetId) {
        this(id, nodeType, coordinate, chamber, root, baseIncidentSections, targetId, null);
    }

    public RouteNode(String id, String nodeType, RouteCoordinate coordinate, boolean chamber,
            boolean root, int baseIncidentSections, String targetId, Integer existingIncidentDiameter) {
        if (existingIncidentDiameter != null && existingIncidentDiameter <= 0) {
            throw new IllegalArgumentException("Existing incident diameter must be positive");
        }
        this.id = id;
        this.nodeType = nodeType;
        this.coordinate = coordinate;
        this.chamber = chamber;
        this.root = root;
        this.baseIncidentSections = baseIncidentSections;
        this.targetId = targetId;
        this.existingIncidentDiameter = existingIncidentDiameter;
    }

    public String getId() { return id; }
    public String getNodeType() { return nodeType; }
    public RouteCoordinate getCoordinate() { return coordinate; }
    public boolean isChamber() { return chamber; }
    public boolean isRoot() { return root; }
    public int getBaseIncidentSections() { return baseIncidentSections; }
    public String getTargetId() { return targetId; }
    public Integer getExistingIncidentDiameter() { return existingIncidentDiameter; }

    public RouteNode withExistingIncidentDiameter(int diameter) {
        return new RouteNode(id, nodeType, coordinate, chamber, root, baseIncidentSections, targetId, diameter);
    }
}
