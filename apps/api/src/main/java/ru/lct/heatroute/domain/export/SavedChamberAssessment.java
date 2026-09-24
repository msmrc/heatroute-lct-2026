package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.routing.OfficialRouteDeflectionRules;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.sizing.OfficialChamberSizing;
import ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex;

/**
 * Проверяет ДУ и цену камер сохранённого варианта по исходному импорту, не доверяя valid=true.
 * Заголовки рёбер типизированы, полилинии проверяются потоково без копирования;
 * старые неверные сметы требуют перерасчёта.
 */
final class SavedChamberAssessment {
    private SavedChamberAssessment() { }

    static Map<String, Integer> verify(JsonNode variant, ExistingNetworkSupportIndex support,
            OfficialEconomics economics) {
        try {
            List<RouteNode> nodes = new ArrayList<>();
            Map<String, RouteNode> nodesById = new java.util.HashMap<>();
            Set<String> nodeIds = new HashSet<>();
            Set<String> existingRoots = new HashSet<>();
            for (JsonNode node : array(variant, "nodes")) {
                RouteNode typed = new RouteNode(text(node, "id"), text(node, "node_type"),
                        coordinate(node.path("coordinate")), bool(node, "chamber"), bool(node, "root"),
                        integer(node, "base_incident_sections"), node.path("target_id").isTextual()
                                ? node.path("target_id").asText() : null,
                        node.hasNonNull("existing_incident_diameter") ? integer(node, "existing_incident_diameter") : null);
                if (!nodeIds.add(typed.getId())) throw new IllegalArgumentException("Duplicate node ID");
                nodes.add(support.verified(typed));
                nodesById.put(typed.getId(), typed);
                if (typed.isRoot() && "existing_chamber_tie_in".equals(typed.getNodeType())) existingRoots.add(typed.getId());
            }
            List<RouteEdge> edges = new ArrayList<>();
            List<OfficialRouteDeflectionRules.EdgeEndpoints> endpoints = new ArrayList<>();
            Set<String> edgeIds = new HashSet<>();
            long existingRays = 0;
            BigDecimal length = BigDecimal.ZERO;
            for (JsonNode edge : array(variant, "edges")) {
                String id = text(edge, "id"), from = text(edge, "upstream_node_id"), to = text(edge, "downstream_node_id");
                if (!edgeIds.add(id) || !nodeIds.contains(from) || !nodeIds.contains(to)) {
                    throw new IllegalArgumentException("Invalid edge incidence: " + id);
                }
                BigDecimal edgeLength = number(edge, "length_m");
                OfficialRouteDeflectionRules.PolylineCheck check = SavedRouteGeometry.verify(edge,
                        nodesById.get(from).getCoordinate(), nodesById.get(to).getCoordinate());
                endpoints.add(check.endpoints(from, to));
                edges.add(new RouteEdge(id, from, to, edgeLength.doubleValue(), List.of(), List.of(),
                        number(edge, "flow_tph"), integer(edge, "diameter")));
                length = length.add(edgeLength);
                if (existingRoots.contains(from)) existingRays++;
            }
            List<ru.lct.heatroute.domain.routing.RouteValidationIssue> turns =
                    OfficialRouteDeflectionRules.validateDegreeTwoNodes(nodes, endpoints);
            if (!turns.isEmpty()) throw new IllegalArgumentException(turns.get(0).getCode() + ": " + turns.get(0).getSubjectId());
            Map<String, Integer> diameters = OfficialChamberSizing.diameters(nodes, edges);
            BigDecimal chamberCost = diameters.values().stream().map(economics::chamberCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            JsonNode stored = variant.path("economics");
            equal(number(stored, "chamber_construction_cost"), chamberCost, "chamber_construction_cost");
            equal(number(stored, "tie_in_cost"), economics.tieInCost().multiply(BigDecimal.valueOf(existingRays)), "tie_in_cost");
            equal(number(stored, "new_network_length"), length, "new_network_length");
            BigDecimal calculated = number(stored, "construction_cost").add(number(stored, "unconnected_penalty"));
            equal(number(stored, "calculated_cost"), calculated, "calculated_cost");
            equal(number(stored, "score"), economics.score(calculated, length), "score");
            return diameters;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: recalculate variant "
                    + variant.path("id").asText() + "; " + invalid.getMessage(), invalid);
        }
    }

    private static RouteCoordinate coordinate(JsonNode node) {
        return SavedRouteGeometry.coordinate(node);
    }

    private static Iterable<JsonNode> array(JsonNode node, String field) {
        if (!node.path(field).isArray()) throw new IllegalArgumentException("Missing array: " + field);
        return node.path(field);
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
            throw new IllegalArgumentException("Missing identifier/type: " + field);
        }
        return node.path(field).asText();
    }

    private static boolean bool(JsonNode node, String field) {
        if (!node.path(field).isBoolean()) throw new IllegalArgumentException("Missing boolean: " + field);
        return node.path(field).booleanValue();
    }

    private static int integer(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt()) {
            throw new IllegalArgumentException("Missing integer: " + field);
        }
        return node.path(field).intValue();
    }

    private static BigDecimal number(JsonNode node, String field) {
        if (!node.path(field).isNumber()) throw new IllegalArgumentException("Missing number: " + field);
        return node.path(field).decimalValue();
    }

    private static void equal(BigDecimal stored, BigDecimal expected, String field) {
        if (stored.compareTo(expected) != 0) throw new IllegalArgumentException("Stored " + field + " disagrees with verified components");
    }
}
