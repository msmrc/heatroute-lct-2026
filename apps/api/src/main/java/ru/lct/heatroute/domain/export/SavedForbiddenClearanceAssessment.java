package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.TopologyException;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules;
import ru.lct.heatroute.domain.routing.OfficialRouteValidator;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteValidationIssue;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Повторно проверяет запрещённые препятствия и вводы ОКС сохранённого ребра с фактическим ДУ.
 * Не доверяет valid=true; исходная и выдаваемая линии проверяются до первой feature.
 * В памяти находится геометрия только текущего ребра, не копия всей сохранённой сети.
 */
final class SavedForbiddenClearanceAssessment {
    private final OfficialRouteValidator.ForbiddenClearanceSession validation;

    SavedForbiddenClearanceAssessment(List<ImportedOfficialFeature> inputs) {
        validation = new OfficialRouteValidator(new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry())).forForbiddenClearanceValidation(inputs);
    }

    void verify(JsonNode variant) {
        try {
            Map<String, RouteNode> nodes = new HashMap<>();
            for (JsonNode node : variant.path("nodes")) {
                ensureActive();
                nodes.put(node.path("id").asText(), new RouteNode(node.path("id").asText(),
                        node.path("node_type").asText(), SavedRouteGeometry.coordinate(node.path("coordinate")),
                        node.path("chamber").asBoolean(), node.path("root").asBoolean(),
                        node.path("base_incident_sections").intValue(),
                        node.path("target_id").isTextual() ? node.path("target_id").asText() : null));
            }
            for (JsonNode edge : variant.path("edges")) {
                ensureActive();
                List<RouteCoordinate> original = new ArrayList<>();
                append(original, edge.path("coordinates"));
                verify(edge, original, nodes);
                if (!edge.path("sections").isEmpty()) {
                    List<RouteCoordinate> emitted = new ArrayList<>();
                    for (JsonNode section : edge.path("sections")) append(emitted, section.path("coordinates"));
                    verify(edge, emitted, nodes);
                }
            }
        } catch (IllegalArgumentException | TopologyException invalid) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: recalculate variant "
                    + variant.path("id").asText() + "; " + invalid.getMessage(), invalid);
        }
    }

    private void verify(JsonNode saved, List<RouteCoordinate> coordinates, Map<String, RouteNode> nodes) {
        double lengthM = 0;
        for (int index = 1; index < coordinates.size(); index++) {
            lengthM += coordinates.get(index - 1).toCoordinate().distance(coordinates.get(index).toCoordinate());
        }
        RouteEdge edge = new RouteEdge(saved.path("id").asText(), saved.path("upstream_node_id").asText(),
                saved.path("downstream_node_id").asText(), lengthM, coordinates, List.of(),
                saved.path("flow_tph").decimalValue(), saved.path("diameter").intValue());
        List<RouteValidationIssue> issues = validation.validate(
                nodes.get(edge.getUpstreamNodeId()), nodes.get(edge.getDownstreamNodeId()), edge);
        if (!issues.isEmpty()) {
            RouteValidationIssue issue = issues.get(0);
            throw new IllegalArgumentException(issue.getCode() + ": " + issue.getSubjectId());
        }
    }

    private static void append(List<RouteCoordinate> target, JsonNode points) {
        for (JsonNode point : points) {
            ensureActive();
            RouteCoordinate next = SavedRouteGeometry.coordinate(point);
            if (!target.isEmpty() && target.get(target.size() - 1).toCoordinate().equals2D(next.toCoordinate())) continue;
            // Секция на прямом вводе не создаёт новый поворот и не укорачивает финальный ввод.
            while (target.size() >= 2 && redundant(target.get(target.size() - 2), target.get(target.size() - 1), next)) {
                target.remove(target.size() - 1);
            }
            target.add(next);
        }
    }

    private static boolean redundant(RouteCoordinate a, RouteCoordinate b, RouteCoordinate c) {
        BigDecimal abX = b.getXM().subtract(a.getXM()), abY = b.getYM().subtract(a.getYM());
        BigDecimal bcX = c.getXM().subtract(b.getXM()), bcY = c.getYM().subtract(b.getYM());
        // Только строго сонаправленные коллинеарные отрезки; миллиметровый сдвиг не скрывается.
        return abX.multiply(bcX).add(abY.multiply(bcY)).signum() > 0
                && abX.multiply(bcY).compareTo(abY.multiply(bcX)) == 0;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Saved spatial validation cancelled");
    }
}
