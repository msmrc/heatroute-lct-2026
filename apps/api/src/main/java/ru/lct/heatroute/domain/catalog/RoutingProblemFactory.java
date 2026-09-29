package ru.lct.heatroute.domain.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RoutingExecutionContext;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/** Собирает малый неизменяемый input next-generation solver из production-данных импорта. */
@Component
public final class RoutingProblemFactory {
    public static final String SNAPSHOT_CODE_VERSION = "nextgen-network-6";
    public static final String ACTIVE_RULE_ID = "lct2026-official-routing";
    public static final String ACTIVE_RULE_VERSION = "source102";
    public static final String COST_CATALOG_VERSION = "official-cost-catalog-1";
    private static final long DIRECTION_SCALE_MM = 1_000_000L;

    public RoutingProblemSnapshot create(
            RoutingExecutionContext context,
            OfficialRunParameters parameters,
            Collection<ImportedOfficialFeature> suppliedFeatures,
            TopologyAnalysis topology) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(topology, "topology");
        List<ImportedOfficialFeature> features = immutableFeatures(suppliedFeatures);
        Map<String, ImportedOfficialFeature> byId = index(features);
        List<RoutingProblemSnapshot.Demand> demands = demands(features, byId);
        List<RoutingProblemSnapshot.RootCandidate> roots = roots(
                features, byId, topology.getTieInCandidates(), demands);
        return new RoutingProblemSnapshot(
                context.getImportId(), context.getSourceHash(), context.getInputProfile(),
                SNAPSHOT_CODE_VERSION, ACTIVE_RULE_ID, ACTIVE_RULE_VERSION,
                COST_CATALOG_VERSION,
                context.getInputContractVersion() + ":" + context.getSourceHash(),
                parameters, demands, roots);
    }

    private static List<RoutingProblemSnapshot.Demand> demands(
            List<ImportedOfficialFeature> features,
            Map<String, ImportedOfficialFeature> byId) {
        List<RoutingProblemSnapshot.Demand> result = new ArrayList<>();
        for (ImportedOfficialFeature connection : features) {
            ensureActive();
            if (!"oks_connection_point".equals(connection.getObjectType())) continue;
            JsonNode attributes = requiredAttributes(connection);
            String linkedOksId = optionalText(attributes, "oks_id");
            BigDecimal flow = decimal(attributes, "flow_tph");
            if (flow == null && linkedOksId != null) {
                ImportedOfficialFeature oks = byId.get(linkedOksId);
                if (oks != null) flow = decimal(requiredAttributes(oks), "flow_tph");
            }
            if (flow == null) {
                throw new IllegalArgumentException(
                        "Missing mandatory flow_tph for connection point: "
                                + connection.getFeatureId());
            }
            Coordinate location = coordinate(connection, "connection point");
            result.add(new RoutingProblemSnapshot.Demand(
                    connection.getFeatureId(), flow,
                    CatalogMetricPoint.fromMeters(location.x, location.y),
                    connection.getFeatureId(), linkedOksId));
        }
        result.sort(Comparator.comparing(RoutingProblemSnapshot.Demand::getId));
        return List.copyOf(result);
    }

    private static List<RoutingProblemSnapshot.RootCandidate> roots(
            List<ImportedOfficialFeature> features,
            Map<String, ImportedOfficialFeature> byId,
            Collection<TieInCandidate> suppliedCandidates,
            List<RoutingProblemSnapshot.Demand> demands) {
        if (suppliedCandidates == null) {
            throw new IllegalArgumentException("Tie-in candidates are required");
        }
        Set<String> demandIds = new LinkedHashSet<>();
        demands.forEach(demand -> demandIds.add(demand.getId()));
        ExistingNetworkSupportIndex supports = new ExistingNetworkSupportIndex(features);
        Map<String, RoutingProblemSnapshot.RootCandidate> result = new LinkedHashMap<>();
        List<TieInCandidate> candidates = new ArrayList<>(suppliedCandidates);
        if (candidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Tie-in candidate cannot be null");
        }
        candidates.sort(Comparator.comparing(TieInCandidate::getConnectionPointId)
                .thenComparing(TieInCandidate::getTargetType)
                .thenComparing(TieInCandidate::getTargetId)
                .thenComparing(candidate -> candidate.hasFixedTieIn()
                        ? Math.round(candidate.getTieInXm() * 1_000.0) : Long.MIN_VALUE)
                .thenComparing(candidate -> candidate.hasFixedTieIn()
                        ? Math.round(candidate.getTieInYm() * 1_000.0) : Long.MIN_VALUE));
        for (TieInCandidate candidate : candidates) {
            ensureActive();
            if (!demandIds.contains(candidate.getConnectionPointId())) {
                throw new IllegalArgumentException(
                        "Tie-in candidate references an unknown connection point: "
                                + candidate.getConnectionPointId());
            }
            ImportedOfficialFeature target = byId.get(candidate.getTargetId());
            if (target == null || !candidate.getTargetType().equals(target.getObjectType())) {
                throw new IllegalArgumentException(
                        "Tie-in target is missing or has another type: " + candidate.getTargetId());
            }
            boolean existingChamber = "heat_chamber".equals(candidate.getTargetType());
            if (!existingChamber && !"heat_network".equals(candidate.getTargetType())) {
                throw new IllegalArgumentException(
                        "Unsupported tie-in target type: " + candidate.getTargetType());
            }
            Coordinate point = candidate.hasFixedTieIn()
                    ? new Coordinate(candidate.getTieInXm(), candidate.getTieInYm())
                    : coordinate(target, "tie-in target");
            String rootId = rootId(candidate, point, existingChamber);
            RouteNode verified = supports.verified(new RouteNode(
                    rootId,
                    existingChamber ? "existing_chamber_tie_in" : "new_tie_in_chamber",
                    new RouteCoordinate(point.x, point.y), true, true,
                    existingChamber ? 0 : 2, candidate.getTargetId()));
            List<RoutingProblemSnapshot.DirectionVector> directions =
                    directionVectors(supports.existingDirections(verified));
            if (directions.size() != verified.getBaseIncidentSections()) {
                throw new IllegalArgumentException(
                        "Existing root incidence differs from its rays: " + rootId);
            }
            RoutingProblemSnapshot.RootRealization realization =
                    new RoutingProblemSnapshot.RootRealization(
                            verified.getNodeType(), true, verified.getBaseIncidentSections(),
                            verified.getTargetId(), verified.getExistingIncidentDiameter());
            RoutingProblemSnapshot.RootCandidate root =
                    new RoutingProblemSnapshot.RootCandidate(
                            rootId, CatalogMetricPoint.fromMeters(point.x, point.y),
                            directions, realization);
            RoutingProblemSnapshot.RootCandidate previous = result.putIfAbsent(rootId, root);
            if (previous != null && !sameRoot(previous, root)) {
                throw new IllegalArgumentException("Ambiguous tie-in root: " + rootId);
            }
        }
        return List.copyOf(result.values());
    }

    private static String rootId(
            TieInCandidate candidate, Coordinate point, boolean existingChamber) {
        if (existingChamber) return "root:chamber:" + candidate.getTargetId();
        CatalogMetricPoint metric = CatalogMetricPoint.fromMeters(point.x, point.y);
        return "root:segment:" + candidate.getTargetId()
                + ":" + metric.getXMm() + ":" + metric.getYMm();
    }

    private static List<RoutingProblemSnapshot.DirectionVector> directionVectors(
            Collection<Coordinate> supplied) {
        List<RoutingProblemSnapshot.DirectionVector> result = new ArrayList<>();
        for (Coordinate direction : supplied) {
            double length = Math.hypot(direction.x, direction.y);
            if (!Double.isFinite(length) || length == 0.0) {
                throw new IllegalArgumentException("Existing root direction must be finite and nonzero");
            }
            long dx = Math.round(direction.x / length * DIRECTION_SCALE_MM);
            long dy = Math.round(direction.y / length * DIRECTION_SCALE_MM);
            result.add(new RoutingProblemSnapshot.DirectionVector(dx, dy));
        }
        result.sort(Comparator.comparingLong(RoutingProblemSnapshot.DirectionVector::getDeltaXMm)
                .thenComparingLong(RoutingProblemSnapshot.DirectionVector::getDeltaYMm));
        return List.copyOf(result);
    }

    private static boolean sameRoot(
            RoutingProblemSnapshot.RootCandidate left,
            RoutingProblemSnapshot.RootCandidate right) {
        return left.getLocation().equals(right.getLocation())
                && left.getExistingDirections().equals(right.getExistingDirections())
                && sameRealization(left.getRealization(), right.getRealization());
    }

    private static boolean sameRealization(
            RoutingProblemSnapshot.RootRealization left,
            RoutingProblemSnapshot.RootRealization right) {
        return left.getNodeType().equals(right.getNodeType())
                && left.isChamber() == right.isChamber()
                && left.getBaseIncidentSections() == right.getBaseIncidentSections()
                && left.getTargetId().equals(right.getTargetId())
                && Objects.equals(left.getExistingIncidentDiameter(),
                        right.getExistingIncidentDiameter());
    }

    private static List<ImportedOfficialFeature> immutableFeatures(
            Collection<ImportedOfficialFeature> supplied) {
        if (supplied == null) throw new IllegalArgumentException("Calculation features are required");
        List<ImportedOfficialFeature> result = new ArrayList<>(supplied);
        if (result.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Calculation feature cannot be null");
        }
        result.sort(Comparator.comparing(ImportedOfficialFeature::getFeatureId));
        return List.copyOf(result);
    }

    private static Map<String, ImportedOfficialFeature> index(
            List<ImportedOfficialFeature> features) {
        Map<String, ImportedOfficialFeature> result = new LinkedHashMap<>();
        for (ImportedOfficialFeature feature : features) {
            String id = required(feature.getFeatureId(), "feature ID");
            if (result.put(id, feature) != null) {
                throw new IllegalArgumentException("Duplicate calculation feature ID: " + id);
            }
        }
        return result;
    }

    private static Coordinate coordinate(ImportedOfficialFeature feature, String label) {
        Geometry geometry = feature.getMetricGeometry();
        if (geometry == null || geometry.isEmpty() || geometry.getCoordinate() == null) {
            throw new IllegalArgumentException(label + " geometry is missing: " + feature.getFeatureId());
        }
        return new Coordinate(geometry.getCoordinate());
    }

    private static JsonNode requiredAttributes(ImportedOfficialFeature feature) {
        JsonNode attributes = feature.getAttributes();
        if (attributes == null || !attributes.isObject()) {
            throw new IllegalArgumentException(
                    "Feature attributes are missing: " + feature.getFeatureId());
        }
        return attributes;
    }

    private static BigDecimal decimal(JsonNode attributes, String field) {
        JsonNode value = attributes.get(field);
        return value == null || value.isNull() || !value.isNumber()
                ? null : value.decimalValue();
    }

    private static String optionalText(JsonNode attributes, String field) {
        JsonNode value = attributes.get(field);
        if (value == null || value.isNull() || !value.isTextual()) return null;
        String result = value.asText().trim();
        return result.isEmpty() ? null : result;
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Routing problem preparation cancelled");
        }
    }
}
