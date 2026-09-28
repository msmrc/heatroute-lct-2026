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

    /**
     * Collision-free normalized input consumed by {@link #verify}. Derived sections, length,
     * economics and depth samples are deliberately excluded; only depth presence affects the
     * fallback parameters. Tokens are length-prefixed, so different inputs cannot alias.
     */
    static String inputSignature(JsonNode variant) {
        StringBuilder result = new StringBuilder();
        append(result, Integer.toString(variant.path("nodes").size()));
        for (JsonNode node : variant.path("nodes")) {
            RouteCoordinate coordinate = SavedRouteGeometry.coordinate(node.path("coordinate"));
            append(result, node.path("id").asText());
            append(result, node.path("node_type").asText());
            append(result, decimal(coordinate.getXM()));
            append(result, decimal(coordinate.getYM()));
            append(result, Boolean.toString(node.path("chamber").asBoolean()));
            append(result, Boolean.toString(node.path("root").asBoolean()));
            append(result, Integer.toString(node.path("base_incident_sections").asInt()));
            append(result, node.hasNonNull("target_id") ? node.path("target_id").asText() : "");
            append(result, node.hasNonNull("existing_incident_diameter")
                    ? Integer.toString(node.path("existing_incident_diameter").asInt()) : "");
        }
        boolean anyProfile = false;
        append(result, Integer.toString(variant.path("edges").size()));
        for (JsonNode edge : variant.path("edges")) {
            append(result, edge.path("id").asText());
            append(result, edge.path("upstream_node_id").asText());
            append(result, edge.path("downstream_node_id").asText());
            List<RouteCoordinate> points = compact(SavedRouteGeometry.points(edge.path("coordinates")));
            append(result, Integer.toString(points.size()));
            for (RouteCoordinate point : points) {
                append(result, decimal(point.getXM()));
                append(result, decimal(point.getYM()));
            }
            append(result, edge.path("flow_tph").isNumber()
                    ? decimal(edge.path("flow_tph").decimalValue())
                    : "");
            append(result, Integer.toString(edge.path("diameter").asInt()));
            anyProfile |= edge.hasNonNull("depth_profile");
        }
        append(result, Boolean.toString(anyProfile));
        append(result, Integer.toString(variant.path("connections").size()));
        for (JsonNode connection : variant.path("connections")) {
            append(result, connection.path("demand_id").asText());
            append(result, connection.path("connection_point_id").asText());
            append(result, connection.path("flow_tph").isNumber()
                    ? decimal(connection.path("flow_tph").decimalValue())
                    : "");
            append(result, connection.path("status").asText());
            append(result, connection.hasNonNull("reason") ? connection.path("reason").asText() : "");
        }
        return result.toString();
    }

    private static String decimal(java.math.BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

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
        AxisShiftAlternativeEvaluator.IndependentSession[] session = new AxisShiftAlternativeEvaluator.IndependentSession[1];
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
            if (session[0] == null) session[0] = evaluator.independentSession(features);
            return session[0].assess(replacement, variant.path("id").asText(),
                    variant.path("strategy").asText("saved"), connections, effective);
        });
        if (alternative != null) fail(variant, "EXPERT_UNNECESSARY_AXIS_SHIFT");
    }

    /**
     * Один проход: сохраняет изгибы и крайние лучи, удаляет лишь оцифровку прямого хода в пределах 2 мм.
     * Альтернативная геометрия проходит полный независимый допуск; исходную проверяют другие saved guards.
     */
    private static List<RouteCoordinate> compact(Iterable<RouteCoordinate> source) {
        List<RouteCoordinate> result = new ArrayList<>();
        for (RouteCoordinate point : source) {
            ensureActive();
            if (!result.isEmpty()
                    && result.get(result.size() - 1).toCoordinate().equals2D(point.toCoordinate())) {
                continue;
            }
            while (result.size() >= 2 && removableCollinear(
                    result.get(result.size() - 2), result.get(result.size() - 1), point)) {
                result.remove(result.size() - 1);
            }
            result.add(point);
        }
        return result;
    }

    private static boolean removableCollinear(
            RouteCoordinate first, RouteCoordinate middle, RouteCoordinate last) {
        Coordinate a = first.toCoordinate(), b = middle.toCoordinate(), c = last.toCoordinate();
        double abx = b.x - a.x, aby = b.y - a.y;
        double bcx = c.x - b.x, bcy = c.y - b.y;
        double ab = Math.hypot(abx, aby), bc = Math.hypot(bcx, bcy);
        if (ab == 0 || bc == 0) return true;
        double distance = Math.abs(abx * bcy - aby * bcx) / Math.hypot(c.x - a.x, c.y - a.y);
        double cosine = (abx * bcx + aby * bcy) / (ab * bc);
        return distance <= 0.002 && cosine >= Math.cos(Math.toRadians(0.5));
    }

    private static void fail(JsonNode variant, String code) {
        throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: recalculate variant " + variant.path("id").asText() + "; " + code);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Saved axis shift assessment cancelled");
    }
}
