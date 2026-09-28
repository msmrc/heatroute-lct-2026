package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatroute.domain.routing.AxisShiftAlternativeEvaluator;
import ru.lct.heatroute.domain.routing.RouteAxisShiftControl;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteVariant;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Независимо запрещает экспорт сети с доказанно устранимой ступенькой, включая старые valid=true. */
final class SavedAxisShiftAssessment {
    private SavedAxisShiftAssessment() { }

    static void verify(JsonNode variant, List<ImportedOfficialFeature> features, OfficialRunParameters parameters,
            AxisShiftAlternativeEvaluator evaluator) {
        List<RouteNode> nodes = new ArrayList<>();
        for (JsonNode node : variant.path("nodes")) {
            nodes.add(new RouteNode(node.path("id").asText(), node.path("node_type").asText(),
                    SavedRouteGeometry.coordinate(node.path("coordinate")), node.path("chamber").asBoolean(),
                    node.path("root").asBoolean(), node.path("base_incident_sections").asInt(),
                    node.hasNonNull("target_id") ? node.path("target_id").asText() : null,
                    node.hasNonNull("existing_incident_diameter") ? node.path("existing_incident_diameter").asInt() : null));
        }
        List<RouteEdge> edges = new ArrayList<>();
        boolean anyProfile = false;
        for (JsonNode edge : variant.path("edges")) {
            ensureActive();
            List<RouteCoordinate> points = compact(SavedRouteGeometry.points(edge.path("coordinates")));
            double length = 0;
            for (int i = 1; i < points.size(); i++) length += points.get(i - 1).toCoordinate().distance(points.get(i).toCoordinate());
            edges.add(new RouteEdge(edge.path("id").asText(), edge.path("upstream_node_id").asText(),
                    edge.path("downstream_node_id").asText(), length, points, List.of(),
                    edge.path("flow_tph").decimalValue(), edge.path("diameter").asInt()));
            anyProfile |= edge.hasNonNull("depth_profile");
        }
        List<RouteConnection> connections = new ArrayList<>();
        // Расходы и IDs берём из connections, а не выводим из координат или единственного датасета.
        for (JsonNode connection : variant.path("connections")) {
            connections.add(new RouteConnection(connection.path("demand_id").asText(),
                    connection.path("connection_point_id").asText(),
                    connection.path("flow_tph").isNumber() ? connection.path("flow_tph").decimalValue() : null,
                    connection.path("status").asText(), connection.hasNonNull("reason") ? connection.path("reason").asText() : null));
        }
        OfficialRunParameters effective = parameters == null ? new OfficialRunParameters(null,
                anyProfile ? OfficialRunParameters.APPLICATION_MAXIMUM_DEPTH_M : null, anyProfile) : parameters;
        RouteVariant alternative = new RouteAxisShiftControl().firstImprovement(nodes, edges, replacement -> {
            if (!variant.path("connections").isArray() || connections.stream().anyMatch(connection ->
                    "connected".equals(connection.getStatus()) && (connection.getFlowTph() == null
                            || connection.getFlowTph().signum() < 0))) {
                fail(variant, "AXIS_SHIFT_FLOW_UNCHECKABLE");
            }
            java.util.Set<String> demandIds = nodes.stream().filter(node -> "demand_connection".equals(node.getNodeType()))
                    .map(RouteNode::getId).collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> connectedIds = connections.stream().filter(connection -> "connected".equals(connection.getStatus()))
                    .map(connection -> "demand:" + connection.getDemandId()).collect(java.util.stream.Collectors.toSet());
            if (!connectedIds.equals(demandIds)) fail(variant, "AXIS_SHIFT_FLOW_UNCHECKABLE");
            return evaluator.assess(replacement, variant.path("id").asText(), variant.path("strategy").asText("saved"),
                    connections, features, effective);
        });
        if (alternative != null) fail(variant, "EXPERT_UNNECESSARY_AXIS_SHIFT");
    }

    /**
     * Один проход: сохраняет изгибы и крайние лучи, удаляет лишь оцифровку прямого хода в пределах 2 мм.
     * Альтернативная геометрия проходит полный независимый допуск; исходную проверяют другие saved guards.
     */
    private static List<RouteCoordinate> compact(Iterable<RouteCoordinate> source) {
        List<RouteCoordinate> result = new ArrayList<>();
        RouteCoordinate previous = null, penultimate = null, start = null;
        Coordinate direction = null;
        for (RouteCoordinate point : source) {
            ensureActive();
            if (previous != null && previous.toCoordinate().equals2D(point.toCoordinate())) continue;
            if (result.size() < 2) {
                result.add(point);
                start = point;
            } else if (direction == null) {
                direction = vector(previous, point);
            } else {
                Coordinate span = vector(start, point), next = vector(previous, point);
                double length = Math.hypot(direction.x, direction.y), nextLength = Math.hypot(next.x, next.y);
                double distance = Math.abs(span.x * direction.y - span.y * direction.x) / length;
                double cosine = (direction.x * next.x + direction.y * next.y) / (length * nextLength);
                if (distance > 0.002 || cosine < Math.cos(Math.toRadians(0.5))) {
                    add(result, previous);
                    start = previous;
                    direction = next;
                }
            }
            penultimate = previous;
            previous = point;
        }
        if (penultimate != null) add(result, penultimate);
        if (previous != null) add(result, previous);
        return result;
    }

    private static Coordinate vector(RouteCoordinate from, RouteCoordinate to) {
        Coordinate a = from.toCoordinate(), b = to.toCoordinate();
        return new Coordinate(b.x - a.x, b.y - a.y);
    }

    private static void add(List<RouteCoordinate> points, RouteCoordinate point) {
        if (points.isEmpty() || !points.get(points.size() - 1).toCoordinate().equals2D(point.toCoordinate())) points.add(point);
    }

    private static void fail(JsonNode variant, String code) {
        throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: recalculate variant " + variant.path("id").asText() + "; " + code);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Saved axis shift assessment cancelled");
    }
}
