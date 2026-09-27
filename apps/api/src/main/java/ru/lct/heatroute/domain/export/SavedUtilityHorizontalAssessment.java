package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import ru.lct.heatroute.domain.depth.OfficialUtilityHorizontalAssessment;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Проверяет исходную и фактически выдаваемую ось до записи первого байта экспорта. */
final class SavedUtilityHorizontalAssessment {
    private SavedUtilityHorizontalAssessment() {}

    static Result verify(
            JsonNode variant,
            List<ImportedOfficialFeature> features,
            OfficialPipeCatalog pipes) {
        try {
            Map<String, JsonNode> savedById = new HashMap<>();
            List<RouteEdge> original = edges(variant, savedById, pipes);
            List<RouteEdge> emitted = emittedEdges(original, savedById);
            Map<String, Set<String>> tieIns = tieIns(variant);
            OfficialUtilityHorizontalAssessment assessment =
                    new OfficialUtilityHorizontalAssessment(pipes);
            OfficialUtilityHorizontalAssessment.Result source =
                    assessment.assess(original, features, tieIns);
            OfficialUtilityHorizontalAssessment.Result output =
                    assessment.assessEmitted(original, emitted, features, tieIns);
            rejectOrdinary(variant, "SOURCE", source.getOrdinaryViolations());
            rejectOrdinary(variant, "EMITTED", output.getOrdinaryViolations());
            List<OfficialUtilityHorizontalAssessment.Finding> boundary = new ArrayList<>();
            boundary.addAll(source.getBoundaryFindings());
            boundary.addAll(output.getBoundaryFindings());
            return new Result(boundary);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "OFFICIAL_EXPORT_INCOMPLETE: recalculate variant "
                            + variant.path("id").asText()
                            + "; "
                            + invalid.getMessage(),
                    invalid);
        }
    }

    private static List<RouteEdge> edges(
            JsonNode variant,
            Map<String, JsonNode> savedById,
            OfficialPipeCatalog pipes) {
        List<RouteEdge> result = new ArrayList<>();
        for (JsonNode saved : variant.path("edges")) {
            active();
            String id = requiredText(saved, "id");
            if (savedById.put(id, saved) != null) {
                throw new IllegalArgumentException("UTILITY_HORIZONTAL_DUPLICATE_EDGE: " + id);
            }
            JsonNode diameter = saved.path("diameter");
            if (!diameter.isIntegralNumber() || pipes.byDiameter(diameter.intValue()).isEmpty()) {
                throw new IllegalArgumentException("UTILITY_HORIZONTAL_EDGE_DIAMETER: " + id);
            }
            List<RouteCoordinate> coordinates = coordinates(saved.path("coordinates"));
            BigDecimal flow = saved.path("flow_tph").isNumber()
                    ? saved.path("flow_tph").decimalValue()
                    : null;
            result.add(
                    new RouteEdge(
                            id,
                            requiredText(saved, "upstream_node_id"),
                            requiredText(saved, "downstream_node_id"),
                            saved.path("length_m").doubleValue(),
                            coordinates,
                            List.of(),
                            flow,
                            diameter.intValue()));
        }
        return result;
    }

    private static List<RouteEdge> emittedEdges(
            List<RouteEdge> original, Map<String, JsonNode> savedById) {
        List<RouteEdge> result = new ArrayList<>();
        for (RouteEdge edge : original) {
            active();
            JsonNode sections = savedById.get(edge.getId()).path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                result.add(edge);
                continue;
            }
            List<RouteCoordinate> points = new ArrayList<>();
            for (JsonNode section : sections) {
                for (RouteCoordinate point : coordinates(section.path("coordinates"))) {
                    if (points.isEmpty()
                            || !points.get(points.size() - 1)
                                    .toCoordinate()
                                    .equals2D(point.toCoordinate())) {
                        points.add(point);
                    }
                }
            }
            result.add(
                    new RouteEdge(
                            edge.getId(),
                            edge.getUpstreamNodeId(),
                            edge.getDownstreamNodeId(),
                            edge.getLengthM().doubleValue(),
                            points,
                            List.of(),
                            edge.getFlowTph(),
                            edge.getDiameter()));
        }
        return result;
    }

    private static Map<String, Set<String>> tieIns(JsonNode variant) {
        Map<String, Set<String>> result = new HashMap<>();
        for (JsonNode node : variant.path("nodes")) {
            if (!node.path("root").asBoolean()) continue;
            result.put(
                    requiredText(node, "id"),
                    node.hasNonNull("target_id")
                            ? Set.of(requiredText(node, "target_id"))
                            : Set.of());
        }
        return result;
    }

    private static List<RouteCoordinate> coordinates(JsonNode array) {
        if (!array.isArray() || array.size() < 2) {
            throw new IllegalArgumentException("UTILITY_HORIZONTAL_EDGE_GEOMETRY");
        }
        List<RouteCoordinate> result = new ArrayList<>();
        for (JsonNode point : array) result.add(SavedRouteGeometry.coordinate(point));
        return result;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("UTILITY_HORIZONTAL_MISSING_" + field);
        }
        return value.asText();
    }

    private static void rejectOrdinary(
            JsonNode variant,
            String geometry,
            List<OfficialUtilityHorizontalAssessment.Finding> findings) {
        if (findings.isEmpty()) return;
        OfficialUtilityHorizontalAssessment.Finding finding = findings.get(0);
        throw new IllegalStateException(
                String.format(
                        Locale.ROOT,
                        "OFFICIAL_EXPORT_INCOMPLETE: recalculate variant %s; "
                                + "UTILITY_HORIZONTAL_CLEARANCE_%s edge=%s source=%s "
                                + "actual=%.6f required=%s station=%.3f",
                        variant.path("id").asText(),
                        geometry,
                        finding.getEdgeId(),
                        finding.getSourceId(),
                        finding.getActualAxisDistanceM(),
                        finding.getRequiredAxisDistanceM().toPlainString(),
                        finding.getWitnessStationM()));
    }

    private static void active() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    static final class Result {
        private final List<OfficialUtilityHorizontalAssessment.Finding> boundaryFindings;

        Result(List<OfficialUtilityHorizontalAssessment.Finding> boundaryFindings) {
            this.boundaryFindings = List.copyOf(boundaryFindings);
        }

        List<OfficialUtilityHorizontalAssessment.Finding> getBoundaryFindings() {
            return boundaryFindings;
        }
    }
}
