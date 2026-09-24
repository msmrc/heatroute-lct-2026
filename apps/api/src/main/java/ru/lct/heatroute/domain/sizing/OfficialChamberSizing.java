package ru.lct.heatroute.domain.sizing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;

/** Максимальный ДУ всех примыканий новой камеры по §3.2 приложения, без реконструкции старой сети. */
public final class OfficialChamberSizing {
    private OfficialChamberSizing() { }

    public static Map<String, Integer> diameters(List<RouteNode> nodes, List<RouteEdge> edges) {
        Map<String, Integer> newDiameters = new HashMap<>();
        for (RouteEdge edge : edges) {
            if (edge.getDiameter() == null || edge.getDiameter() <= 0) {
                throw new IllegalArgumentException("Final edge diameter is required: " + edge.getId());
            }
            newDiameters.merge(edge.getUpstreamNodeId(), edge.getDiameter(), Math::max);
            newDiameters.merge(edge.getDownstreamNodeId(), edge.getDiameter(), Math::max);
        }
        Map<String, Integer> result = new HashMap<>();
        for (RouteNode node : nodes) {
            if (!node.isChamber() || !node.getNodeType().startsWith("new_")) continue;
            Integer diameter = newDiameters.get(node.getId());
            if (diameter == null) throw new IllegalArgumentException("New chamber has no incident new section: " + node.getId());
            if ("new_tie_in_chamber".equals(node.getNodeType())) {
                if (!node.isRoot() || node.getExistingIncidentDiameter() == null) {
                    throw new IllegalArgumentException("Existing support diameter is required for a new tie-in chamber: " + node.getId());
                }
                diameter = Math.max(diameter, node.getExistingIncidentDiameter());
            }
            result.put(node.getId(), diameter);
        }
        return result;
    }
}
