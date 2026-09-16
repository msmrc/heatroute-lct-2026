package ru.lct.heatroute.domain.reconstruction;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialExistingNetworkReconstructor {
    private static final double EPSILON_M = 0.001;

    private final OfficialPipeCatalog pipeCatalog;

    public OfficialExistingNetworkReconstructor(OfficialPipeCatalog pipeCatalog) {
        this.pipeCatalog = pipeCatalog;
    }

    public ExistingNetworkReconstructionResult reconstruct(
            List<ImportedOfficialFeature> features,
            List<TieInLoad> loads) {
        if (loads.isEmpty()) {
            return ExistingNetworkReconstructionResult.empty();
        }
        Map<String, ImportedOfficialFeature> network = features.stream()
                .filter(this::isNetworkObject)
                .collect(Collectors.toMap(
                        ImportedOfficialFeature::getFeatureId,
                        feature -> feature,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<ReconstructionIssue> issues = new ArrayList<>();
        Map<String, List<SegmentContribution>> segmentContributions = new LinkedHashMap<>();
        Map<String, BigDecimal> chamberLoads = new LinkedHashMap<>();

        for (TieInLoad load : loads.stream()
                .sorted(Comparator.comparing(TieInLoad::getTargetId))
                .collect(Collectors.toList())) {
            Trace trace = trace(load, network, issues);
            if (trace != null) {
                trace.segmentContributions.forEach((featureId, contribution) ->
                        segmentContributions.computeIfAbsent(featureId, ignored -> new ArrayList<>())
                                .add(contribution));
                trace.chamberIds.forEach(chamberId ->
                        chamberLoads.merge(chamberId, load.getAddedFlowTph(), BigDecimal::add));
            }
        }

        List<NetworkReconstructionSection> sections = buildSections(
                network, segmentContributions, issues);
        List<ChamberReconstruction> chambers = buildChambers(network, chamberLoads, issues);
        issues.sort(Comparator.comparing(ReconstructionIssue::getCode)
                .thenComparing(issue -> issue.getSubjectId() == null ? "" : issue.getSubjectId()));
        return new ExistingNetworkReconstructionResult(sections, chambers, deduplicate(issues));
    }

    private Trace trace(
            TieInLoad load,
            Map<String, ImportedOfficialFeature> network,
            List<ReconstructionIssue> issues) {
        ImportedOfficialFeature target = network.get(load.getTargetId());
        if (target == null || "source".equals(target.getObjectType())) {
            issues.add(unavailable(load.getTargetId(), "Tie-in target is not an existing network object"));
            return null;
        }
        Trace trace = new Trace();
        Set<String> visited = new LinkedHashSet<>();
        ImportedOfficialFeature cursor = target;
        boolean first = true;
        while (!"source".equals(cursor.getObjectType())) {
            if (!visited.add(cursor.getFeatureId())) {
                issues.add(new ReconstructionIssue(
                        "RECONSTRUCTION_UPSTREAM_CYCLE",
                        cursor.getFeatureId(),
                        "Existing-network upstream chain contains a cycle"));
                return null;
            }
            String upstreamId = text(cursor.getAttributes(), "upstream_object_id");
            ImportedOfficialFeature upstream = upstreamId == null ? null : network.get(upstreamId);
            if (upstream == null) {
                issues.add(unavailable(cursor.getFeatureId(),
                        "upstream_object_id is absent or does not lead to an existing source"));
                return null;
            }
            if ("heat_network".equals(cursor.getObjectType())) {
                if (!hasPositiveNumber(cursor.getAttributes(), "flow_tph")
                        || !hasPositiveNumber(cursor.getAttributes(), "diameter")) {
                    issues.add(unavailable(cursor.getFeatureId(),
                            "Existing heat-network flow_tph and diameter are required"));
                    return null;
                }
                LineString line = asLine(cursor);
                if (line == null) {
                    issues.add(unavailable(cursor.getFeatureId(), "Existing heat-network geometry must be a LineString"));
                    return null;
                }
                double start = 0.0;
                double end = line.getLength();
                if (first) {
                    LengthIndexedLine indexed = new LengthIndexedLine(line);
                    double tieIndex = indexed.project(load.getCoordinate().toCoordinate());
                    boolean upstreamAtStart = upstreamAtStart(line, upstream.getMetricGeometry());
                    start = upstreamAtStart ? 0.0 : tieIndex;
                    end = upstreamAtStart ? tieIndex : line.getLength();
                }
                if (end - start > EPSILON_M) {
                    trace.segmentContributions.put(cursor.getFeatureId(),
                            new SegmentContribution(start, end, load.getAddedFlowTph()));
                }
            } else if ("heat_chamber".equals(cursor.getObjectType())) {
                if (!hasPositiveNumber(cursor.getAttributes(), "diameter")) {
                    issues.add(unavailable(cursor.getFeatureId(),
                            "Existing heat-chamber diameter is required"));
                    return null;
                }
                trace.chamberIds.add(cursor.getFeatureId());
            } else {
                issues.add(unavailable(cursor.getFeatureId(), "Unsupported object in upstream chain"));
                return null;
            }
            cursor = upstream;
            first = false;
        }
        return trace;
    }

    private List<NetworkReconstructionSection> buildSections(
            Map<String, ImportedOfficialFeature> network,
            Map<String, List<SegmentContribution>> contributionsBySegment,
            List<ReconstructionIssue> issues) {
        List<NetworkReconstructionSection> result = new ArrayList<>();
        for (Map.Entry<String, List<SegmentContribution>> entry : contributionsBySegment.entrySet()) {
            ImportedOfficialFeature feature = network.get(entry.getKey());
            LineString line = asLine(feature);
            if (line == null) {
                continue;
            }
            BigDecimal existingFlow = decimal(feature.getAttributes(), "flow_tph");
            int existingDiameter = decimal(feature.getAttributes(), "diameter").intValueExact();
            Set<Double> cutSet = new HashSet<>();
            for (SegmentContribution contribution : entry.getValue()) {
                cutSet.add(contribution.startIndex);
                cutSet.add(contribution.endIndex);
            }
            List<Double> cuts = cutSet.stream().sorted().collect(Collectors.toList());
            LengthIndexedLine indexed = new LengthIndexedLine(line);
            for (int index = 0; index < cuts.size() - 1; index++) {
                double start = cuts.get(index);
                double end = cuts.get(index + 1);
                if (end - start <= EPSILON_M) {
                    continue;
                }
                double midpoint = (start + end) / 2.0;
                BigDecimal added = entry.getValue().stream()
                        .filter(contribution -> midpoint >= contribution.startIndex - EPSILON_M
                                && midpoint <= contribution.endIndex + EPSILON_M)
                        .map(contribution -> contribution.addedFlow)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (added.signum() <= 0) {
                    continue;
                }
                BigDecimal resulting = existingFlow.add(added);
                Optional<PipeCatalogEntry> required = pipeCatalog.minimumForFlow(resulting);
                if (required.isEmpty()) {
                    issues.add(new ReconstructionIssue(
                            "RECONSTRUCTION_FLOW_EXCEEDS_CATALOG",
                            feature.getFeatureId(),
                            "No official diameter can carry resulting flow " + resulting.toPlainString() + " t/h"));
                    continue;
                }
                if (required.get().getDiameter() <= existingDiameter) {
                    continue;
                }
                Geometry extracted = indexed.extractLine(start, end);
                List<RouteCoordinate> coordinates = List.of(extracted.getCoordinates()).stream()
                        .map(coordinate -> new RouteCoordinate(coordinate.x, coordinate.y))
                        .collect(Collectors.toList());
                result.add(new NetworkReconstructionSection(
                        "reconstruction:" + feature.getFeatureId() + ":" + index,
                        feature.getFeatureId(),
                        coordinates,
                        extracted.getLength(),
                        existingFlow,
                        added,
                        resulting,
                        existingDiameter,
                        required.get().getDiameter(),
                        extracted.getLength() < line.getLength() - EPSILON_M));
            }
        }
        result.sort(Comparator.comparing(NetworkReconstructionSection::getExistingFeatureId)
                .thenComparing(NetworkReconstructionSection::getId));
        return result;
    }

    private List<ChamberReconstruction> buildChambers(
            Map<String, ImportedOfficialFeature> network,
            Map<String, BigDecimal> chamberLoads,
            List<ReconstructionIssue> issues) {
        List<ChamberReconstruction> result = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : chamberLoads.entrySet()) {
            ImportedOfficialFeature chamber = network.get(entry.getKey());
            int existingDiameter = decimal(chamber.getAttributes(), "diameter").intValueExact();
            String upstreamId = text(chamber.getAttributes(), "upstream_object_id");
            ImportedOfficialFeature upstream = network.get(upstreamId);
            BigDecimal existingFlow = upstream != null && "heat_network".equals(upstream.getObjectType())
                    ? decimal(upstream.getAttributes(), "flow_tph")
                    : BigDecimal.ZERO;
            if (existingFlow == null) {
                issues.add(unavailable(chamber.getFeatureId(),
                        "Upstream existing heat-network flow_tph is required for chamber sizing"));
                continue;
            }
            BigDecimal resulting = existingFlow.add(entry.getValue());
            Optional<PipeCatalogEntry> required = pipeCatalog.minimumForFlow(resulting);
            if (required.isEmpty()) {
                issues.add(new ReconstructionIssue(
                        "RECONSTRUCTION_FLOW_EXCEEDS_CATALOG",
                        chamber.getFeatureId(),
                        "No official diameter can carry resulting chamber flow " + resulting.toPlainString() + " t/h"));
            } else if (required.get().getDiameter() > existingDiameter) {
                Coordinate coordinate = chamber.getMetricGeometry().getCoordinate();
                result.add(new ChamberReconstruction(
                        chamber.getFeatureId(),
                        new RouteCoordinate(coordinate.x, coordinate.y),
                        entry.getValue(),
                        resulting,
                        existingDiameter,
                        required.get().getDiameter()));
            }
        }
        result.sort(Comparator.comparing(ChamberReconstruction::getExistingFeatureId));
        return result;
    }

    private boolean upstreamAtStart(LineString line, Geometry upstream) {
        Coordinate start = line.getCoordinateN(0);
        Coordinate end = line.getCoordinateN(line.getNumPoints() - 1);
        return upstream.distance(line.getFactory().createPoint(start))
                <= upstream.distance(line.getFactory().createPoint(end));
    }

    private LineString asLine(ImportedOfficialFeature feature) {
        return feature != null && feature.getMetricGeometry() instanceof LineString
                ? (LineString) feature.getMetricGeometry()
                : null;
    }

    private boolean isNetworkObject(ImportedOfficialFeature feature) {
        return "source".equals(feature.getObjectType())
                || "heat_network".equals(feature.getObjectType())
                || "heat_chamber".equals(feature.getObjectType());
    }

    private boolean hasPositiveNumber(JsonNode attributes, String field) {
        BigDecimal value = decimal(attributes, field);
        return value != null && value.signum() > 0;
    }

    private BigDecimal decimal(JsonNode attributes, String field) {
        JsonNode value = attributes.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.decimalValue();
    }

    private String text(JsonNode attributes, String field) {
        JsonNode value = attributes.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private ReconstructionIssue unavailable(String subjectId, String message) {
        return new ReconstructionIssue("RECONSTRUCTION_INPUT_UNAVAILABLE", subjectId, message);
    }

    private List<ReconstructionIssue> deduplicate(List<ReconstructionIssue> issues) {
        Map<String, ReconstructionIssue> unique = new LinkedHashMap<>();
        for (ReconstructionIssue issue : issues) {
            unique.putIfAbsent(issue.getCode() + "|" + issue.getSubjectId() + "|" + issue.getMessage(), issue);
        }
        return new ArrayList<>(unique.values());
    }

    private static class SegmentContribution {
        private final double startIndex;
        private final double endIndex;
        private final BigDecimal addedFlow;

        private SegmentContribution(double startIndex, double endIndex, BigDecimal addedFlow) {
            this.startIndex = Math.min(startIndex, endIndex);
            this.endIndex = Math.max(startIndex, endIndex);
            this.addedFlow = addedFlow;
        }
    }

    private static class Trace {
        private final Map<String, SegmentContribution> segmentContributions = new LinkedHashMap<>();
        private final List<String> chamberIds = new ArrayList<>();
    }
}
