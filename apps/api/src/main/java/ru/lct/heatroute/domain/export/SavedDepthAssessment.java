package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import ru.lct.heatroute.domain.depth.DepthCrossing;
import ru.lct.heatroute.domain.depth.DepthNetworkAssessment;
import ru.lct.heatroute.domain.depth.DepthCrossingDecision;
import ru.lct.heatroute.domain.depth.DepthProfileIssue;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Адаптирует сохранённый JSON к независимому геометрическому допуску глубины.
 * Параметры должны происходить из записи запуска. Legacy-вызов без них проверяет присутствующий
 * профиль в пределах приложения, но не может обнаружить удаление всех профилей depth-enabled run.
 */
final class SavedDepthAssessment {
    private static final BigDecimal THREE = new BigDecimal("3");
    private static final BigDecimal TWO = new BigDecimal("2");

    private SavedDepthAssessment() { }

    static void verify(JsonNode variant, List<ImportedOfficialFeature> features, OfficialPipeCatalog pipes,
            OfficialRunParameters parameters) {
        try {
            if (parameters != null) parameters.validated();
            boolean anyProfile = false;
            for (JsonNode edge : variant.path("edges")) anyProfile |= edge.hasNonNull("depth_profile");
            boolean required = parameters == null ? anyProfile : parameters.isDepthEnabled();
            if (parameters != null && !required && anyProfile) fail("DEPTH_MODE_MISMATCH");
            if (!required) return;
            BigDecimal min = parameters == null ? OfficialRunParameters.PUBLISHED_MINIMUM_DEPTH_M : parameters.getMinimumDepthM();
            BigDecimal max = parameters == null ? OfficialRunParameters.APPLICATION_MAXIMUM_DEPTH_M : parameters.getMaximumDepthM();
            List<RouteEdge> edges = new ArrayList<>();
            for (JsonNode saved : variant.path("edges")) {
                active();
                edges.add(edge(saved));
            }
            Map<String, Set<String>> nodeTieIns = new HashMap<>();
            for (JsonNode node : variant.path("nodes")) {
                active();
                if (node.path("root").asBoolean() && node.hasNonNull("target_id"))
                    nodeTieIns.put(text(node, "id"), Set.of(text(node, "target_id")));
            }
            DepthNetworkAssessment.Result assessed = new DepthNetworkAssessment(pipes)
                    .assess(edges, features, min, max, nodeTieIns, Map.of());
            check(assessed.getIssues());
            for (RouteEdge edge : edges) {
                active();
                diagnostics(edge.getDepthProfile(), assessed.getCrossingsByEdge().getOrDefault(edge.getId(), List.of()));
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: recalculate variant "
                    + variant.path("id").asText() + "; " + invalid.getMessage(), invalid);
        }
    }

    private static RouteEdge edge(JsonNode saved) {
        JsonNode json = saved.path("depth_profile");
        if (!json.isObject() || !json.path("complete").isBoolean() || !json.path("complete").booleanValue()
                || !json.path("issues").isArray() || !json.path("issues").isEmpty()) fail("DEPTH_PROFILE_INCOMPLETE");
        List<DepthProfilePoint> points = new ArrayList<>();
        for (JsonNode point : array(json, "points")) {
            active();
            points.add(new DepthProfilePoint(metric(point, "station_m"), metric(point, "depth_m")));
        }
        List<DepthCrossingDecision> decisions = new ArrayList<>();
        for (JsonNode crossing : array(json, "crossings")) {
            decisions.add(new DepthCrossingDecision(text(crossing, "crossing_id"), text(crossing, "crossing_type"),
                    text(crossing, "passage"), metric(crossing, "depth_m"), metric(crossing, "ramp_start_m"),
                    metric(crossing, "plateau_start_m"), metric(crossing, "plateau_end_m"), metric(crossing, "ramp_end_m"),
                    metric(crossing, "vertical_clearance_m"), metric(crossing, "required_clearance_m")));
        }
        DepthProfileResult profile = new DepthProfileResult(true, points, decisions, List.of(),
                metric(json, "profile_length_3d_m"), metric(json, "depth_adjusted_cost_meters"));
        List<RouteCoordinate> coordinates = new ArrayList<>();
        for (JsonNode point : array(saved, "coordinates")) coordinates.add(SavedRouteGeometry.coordinate(point));
        JsonNode diameter = saved.path("diameter");
        if (!diameter.isIntegralNumber() || !diameter.canConvertToInt()) fail("DEPTH_DIAMETER_INVALID");
        return new RouteEdge(text(saved,"id"), text(saved,"upstream_node_id"), text(saved,"downstream_node_id"),
                metric(saved,"length_m").doubleValue(), coordinates, List.of(), null, diameter.intValue(), profile);
    }

    /** Диагностика 3D не участвует в цене: цена интегрируется только по горизонтальной станции. */
    private static void diagnostics(DepthProfileResult profile, List<DepthCrossing> crossings) {
        List<DepthProfilePoint> points = profile.getPoints();
        BigDecimal length3d = BigDecimal.ZERO;
        TreeSet<BigDecimal> cuts = new TreeSet<>();
        for (int i = 0; i < points.size(); i++) {
            active();
            DepthProfilePoint b = points.get(i);
            cuts.add(b.getStationM());
            if (i == 0) continue;
            DepthProfilePoint a = points.get(i - 1);
            BigDecimal run = b.getStationM().subtract(a.getStationM());
            BigDecimal rise = b.getDepthM().subtract(a.getDepthM());
            length3d = length3d.add(BigDecimal.valueOf(Math.hypot(run.doubleValue(), rise.doubleValue())));
            BigDecimal left = a.getDepthM().subtract(THREE), right = b.getDepthM().subtract(THREE);
            if (left.signum() * right.signum() < 0)
                cuts.add(a.getStationM().add(run.multiply(left.negate()).divide(right.subtract(left), 18, RoundingMode.HALF_UP)));
        }
        equalMetric(length3d, profile.getProfileLength3dM(), "DEPTH_3D_LENGTH_MISMATCH");
        Map<String, BigDecimal> multipliers = new HashMap<>();
        crossings.forEach(c -> multipliers.put(c.getId(), c.getSpecialCostMultiplier()));
        profile.getCrossings().forEach(c -> { cuts.add(c.getPlateauStartM()); cuts.add(c.getPlateauEndM()); });
        List<BigDecimal> ordered = new ArrayList<>(cuts);
        BigDecimal weighted = BigDecimal.ZERO;
        for (int i = 1; i < ordered.size(); i++) {
            active();
            BigDecimal a = ordered.get(i - 1), b = ordered.get(i);
            BigDecimal special = BigDecimal.ONE;
            for (DepthCrossingDecision c : profile.getCrossings()) {
                if (a.compareTo(c.getPlateauStartM()) >= 0 && b.compareTo(c.getPlateauEndM()) <= 0)
                    special = special.max(multipliers.get(c.getCrossingId()));
            }
            BigDecimal mean = multiplier(interpolate(points,a)).add(multiplier(interpolate(points,b))).divide(TWO);
            weighted = weighted.add(b.subtract(a).multiply(mean).multiply(special));
        }
        // Historical field is utility-weighted only; road/tram money is independently priced via RouteSections.
        equalMetric(weighted, profile.getDepthAdjustedCostMeters(), "DEPTH_WEIGHTED_METERS_MISMATCH");
    }

    private static BigDecimal interpolate(List<DepthProfilePoint> points, BigDecimal station) {
        for (int i = 1; i < points.size(); i++) {
            DepthProfilePoint a = points.get(i - 1), b = points.get(i);
            if (station.compareTo(a.getStationM()) >= 0 && station.compareTo(b.getStationM()) <= 0)
                return a.getDepthM().add(b.getDepthM().subtract(a.getDepthM()).multiply(station.subtract(a.getStationM()))
                        .divide(b.getStationM().subtract(a.getStationM()), 18, RoundingMode.HALF_UP));
        }
        throw new IllegalArgumentException("DEPTH_STATION_OUTSIDE_PROFILE");
    }

    private static BigDecimal multiplier(BigDecimal depth) {
        return BigDecimal.ONE.add(depth.subtract(THREE).max(BigDecimal.ZERO).multiply(new BigDecimal(".1")));
    }

    private static void equalMetric(BigDecimal actual, BigDecimal reported, String code) {
        if (actual.setScale(3, RoundingMode.HALF_UP).compareTo(reported) != 0) fail(code);
    }

    private static JsonNode array(JsonNode node, String key) {
        JsonNode result = node.path(key);
        if (!result.isArray()) fail("DEPTH_FIELD_INVALID: " + key);
        return result;
    }

    private static BigDecimal metric(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isNumber()) fail("DEPTH_FIELD_INVALID: " + key);
        BigDecimal result = value.decimalValue();
        if (!Double.isFinite(result.doubleValue()) || result.compareTo(result.setScale(3, RoundingMode.HALF_UP)) != 0)
            fail("DEPTH_FIELD_INVALID: " + key);
        return result;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.textValue().isBlank()) fail("DEPTH_FIELD_INVALID: " + key);
        return value.textValue();
    }

    private static void check(List<DepthProfileIssue> issues) {
        if (!issues.isEmpty()) fail(issues.stream().map(DepthProfileIssue::getCode).collect(Collectors.joining(", ")));
    }

    private static void active() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Saved depth admission interrupted");
    }

    private static void fail(String code) { throw new IllegalArgumentException(code); }
}
