package ru.lct.heatroute.domain.topology;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;

@Component
public class ExistingNetworkTopologyAnalyzer {
    private static final double CHAMBER_SNAP_DISTANCE_M = 10.0;
    private static final double COORDINATE_TOLERANCE_M = 0.01;
    private static final double GEOMETRIC_SNAP_DISTANCE_M = 1.0;
    private static final int MAX_CANDIDATES_PER_CONNECTION = 12;

    public TopologyAnalysis analyze(List<ImportedOfficialFeature> features) {
        Map<String, ImportedOfficialFeature> networkObjects = features.stream()
                .filter(this::isNetworkObject)
                .collect(Collectors.toMap(
                        ImportedOfficialFeature::getFeatureId,
                        feature -> feature,
                        (left, right) -> left,
                        HashMap::new));
        List<ImportedOfficialFeature> sources = byType(features, "source");
        List<ImportedOfficialFeature> segments = byType(features, "heat_network");
        List<ImportedOfficialFeature> chambers = byType(features, "heat_chamber");
        List<ImportedOfficialFeature> connectionPoints = byType(features, "oks_connection_point");
        List<TopologyIssue> issues = new ArrayList<>();

        if (sources.isEmpty()) {
            issues.add(new TopologyIssue("MISSING_SOURCE", null, "At least one source is required"));
        }
        boolean hasExplicitUpstream = networkObjects.values().stream()
                .filter(feature -> !"source".equals(feature.getObjectType()))
                .anyMatch(feature -> !feature.getAttributes().path("upstream_object_id").asText().isBlank());
        if (hasExplicitUpstream) {
            validateUpstreamChains(networkObjects, issues);
        } else {
            validateGeometricConnectivity(sources, segments, chambers, issues);
        }
        validateLineIntersections(segments, issues);

        Map<String, Integer> chamberIncidentCounts = incidentCounts(chambers, segments);
        List<TieInCandidate> candidates = createCandidates(
                connectionPoints, segments, chambers, chamberIncidentCounts);
        for (ImportedOfficialFeature connectionPoint : connectionPoints) {
            boolean found = candidates.stream().anyMatch(candidate ->
                    candidate.getConnectionPointId().equals(connectionPoint.getFeatureId()));
            if (!found) {
                issues.add(new TopologyIssue(
                        "NO_TIE_IN_CANDIDATE",
                        connectionPoint.getFeatureId(),
                        "No existing heat-network segment can accept a tie-in"));
            }
        }

        return new TopologyAnalysis(
                sources.size(), segments.size(), chambers.size(), issues, candidates);
    }

    private void validateGeometricConnectivity(
            List<ImportedOfficialFeature> sources,
            List<ImportedOfficialFeature> segments,
            List<ImportedOfficialFeature> chambers,
            List<TopologyIssue> issues) {
        Set<String> reachable = new HashSet<>();
        Deque<ImportedOfficialFeature> queue = new ArrayDeque<>();
        for (ImportedOfficialFeature segment : segments) {
            boolean touchesSource = sources.stream().anyMatch(source ->
                    source.getMetricGeometry().distance(segment.getMetricGeometry())
                            <= GEOMETRIC_SNAP_DISTANCE_M);
            if (touchesSource && reachable.add(segment.getFeatureId())) {
                queue.add(segment);
            }
        }

        while (!queue.isEmpty()) {
            ImportedOfficialFeature current = queue.removeFirst();
            for (ImportedOfficialFeature candidate : segments) {
                if (reachable.contains(candidate.getFeatureId())) {
                    continue;
                }
                if (current.getMetricGeometry().distance(candidate.getMetricGeometry())
                        <= GEOMETRIC_SNAP_DISTANCE_M) {
                    reachable.add(candidate.getFeatureId());
                    queue.addLast(candidate);
                }
            }
        }

        for (ImportedOfficialFeature segment : segments) {
            if (!reachable.contains(segment.getFeatureId())) {
                issues.add(new TopologyIssue(
                        "GEOMETRIC_NETWORK_DISCONNECTED",
                        segment.getFeatureId(),
                        "Existing segment is not geometrically connected to a source"));
            }
        }
        for (ImportedOfficialFeature chamber : chambers) {
            boolean onNetwork = segments.stream().anyMatch(segment ->
                    segment.getMetricGeometry().distance(chamber.getMetricGeometry())
                            <= GEOMETRIC_SNAP_DISTANCE_M);
            if (!onNetwork) {
                issues.add(new TopologyIssue(
                        "CHAMBER_OFF_NETWORK",
                        chamber.getFeatureId(),
                        "Existing chamber is not located on the existing network"));
            }
        }
    }

    private void validateUpstreamChains(
            Map<String, ImportedOfficialFeature> networkObjects,
            List<TopologyIssue> issues) {
        for (ImportedOfficialFeature feature : networkObjects.values()) {
            if ("source".equals(feature.getObjectType())) {
                continue;
            }
            Set<String> visited = new LinkedHashSet<>();
            ImportedOfficialFeature cursor = feature;
            while (!"source".equals(cursor.getObjectType())) {
                if (!visited.add(cursor.getFeatureId())) {
                    issues.add(new TopologyIssue(
                            "UPSTREAM_CYCLE",
                            feature.getFeatureId(),
                            "Upstream chain contains a cycle: " + String.join(" -> ", visited)));
                    break;
                }
                String upstreamId = cursor.getAttributes().path("upstream_object_id").asText();
                ImportedOfficialFeature upstream = networkObjects.get(upstreamId);
                if (upstream == null) {
                    issues.add(new TopologyIssue(
                            "UPSTREAM_CHAIN_BROKEN",
                            feature.getFeatureId(),
                            "Upstream chain does not reach a source"));
                    break;
                }
                cursor = upstream;
            }
        }
    }

    private void validateLineIntersections(
            List<ImportedOfficialFeature> segments,
            List<TopologyIssue> issues) {
        STRtree index = new STRtree();
        for (ImportedOfficialFeature segment : segments) {
            index.insert(segment.getMetricGeometry().getEnvelopeInternal(), segment);
        }
        index.build();
        Set<String> checkedPairs = new HashSet<>();
        for (ImportedOfficialFeature left : segments) {
            @SuppressWarnings("unchecked")
            List<ImportedOfficialFeature> nearby = index.query(left.getMetricGeometry().getEnvelopeInternal());
            for (ImportedOfficialFeature right : nearby) {
                if (left == right) {
                    continue;
                }
                String pair = left.getFeatureId().compareTo(right.getFeatureId()) < 0
                        ? left.getFeatureId() + "|" + right.getFeatureId()
                        : right.getFeatureId() + "|" + left.getFeatureId();
                if (!checkedPairs.add(pair)) {
                    continue;
                }
                Geometry intersection = left.getMetricGeometry().intersection(right.getMetricGeometry());
                if (!intersection.isEmpty() && !isSharedEndpointIntersection(left, right, intersection)) {
                    issues.add(new TopologyIssue(
                            "AMBIGUOUS_NETWORK_INTERSECTION",
                            left.getFeatureId(),
                            "Existing segments intersect outside one shared endpoint: " + right.getFeatureId()));
                }
            }
        }
    }

    private boolean isSharedEndpointIntersection(
            ImportedOfficialFeature left,
            ImportedOfficialFeature right,
            Geometry intersection) {
        if (!(left.getMetricGeometry() instanceof LineString)
                || !(right.getMetricGeometry() instanceof LineString)
                || !(intersection instanceof Point)) {
            return false;
        }
        Coordinate point = intersection.getCoordinate();
        return isEndpoint((LineString) left.getMetricGeometry(), point)
                && isEndpoint((LineString) right.getMetricGeometry(), point);
    }

    private boolean isEndpoint(LineString line, Coordinate point) {
        return line.getCoordinateN(0).distance(point) <= COORDINATE_TOLERANCE_M
                || line.getCoordinateN(line.getNumPoints() - 1).distance(point) <= COORDINATE_TOLERANCE_M;
    }

    private Map<String, Integer> incidentCounts(
            List<ImportedOfficialFeature> chambers,
            List<ImportedOfficialFeature> segments) {
        Map<String, Integer> result = new HashMap<>();
        for (ImportedOfficialFeature chamber : chambers) {
            int count = 0;
            for (ImportedOfficialFeature segment : segments) {
                if (segment.getMetricGeometry().distance(chamber.getMetricGeometry()) <= COORDINATE_TOLERANCE_M) {
                    count++;
                }
            }
            result.put(chamber.getFeatureId(), count);
        }
        return result;
    }

    private List<TieInCandidate> createCandidates(
            List<ImportedOfficialFeature> connectionPoints,
            List<ImportedOfficialFeature> segments,
            List<ImportedOfficialFeature> chambers,
            Map<String, Integer> chamberIncidentCounts) {
        List<TieInCandidate> result = new ArrayList<>();
        for (ImportedOfficialFeature connectionPoint : connectionPoints) {
            List<TieInCandidate> perPoint = new ArrayList<>();
            for (ImportedOfficialFeature segment : segments) {
                Coordinate[] nearest = DistanceOp.nearestPoints(
                        connectionPoint.getMetricGeometry(), segment.getMetricGeometry());
                ImportedOfficialFeature chamber = nearestEligibleChamber(
                        nearest[1], chambers, chamberIncidentCounts);
                if (chamber != null) {
                    perPoint.add(new TieInCandidate(
                            connectionPoint.getFeatureId(),
                            chamber.getFeatureId(),
                            "heat_chamber",
                            connectionPoint.getMetricGeometry().distance(chamber.getMetricGeometry()),
                            false));
                } else {
                    perPoint.add(new TieInCandidate(
                            connectionPoint.getFeatureId(),
                            segment.getFeatureId(),
                            "heat_network",
                            connectionPoint.getMetricGeometry().distance(segment.getMetricGeometry()),
                            true));
                }
            }
            perPoint.sort(Comparator
                    .comparing(TieInCandidate::getDistanceM)
                    .thenComparing(TieInCandidate::isNewChamberRequired)
                    .thenComparing(TieInCandidate::getTargetId));
            Set<String> seen = new HashSet<>();
            perPoint.stream()
                    .filter(candidate -> seen.add(candidate.getTargetType() + "|" + candidate.getTargetId()))
                    .limit(MAX_CANDIDATES_PER_CONNECTION)
                    .forEach(result::add);
        }
        return result;
    }

    private ImportedOfficialFeature nearestEligibleChamber(
            Coordinate tieInPoint,
            List<ImportedOfficialFeature> chambers,
            Map<String, Integer> chamberIncidentCounts) {
        return chambers.stream()
                .filter(chamber -> chamber.getMetricGeometry().getCoordinate().distance(tieInPoint)
                        <= CHAMBER_SNAP_DISTANCE_M)
                .filter(chamber -> chamberIncidentCounts.getOrDefault(chamber.getFeatureId(), 0) < 4)
                .min(Comparator
                        .comparingDouble((ImportedOfficialFeature chamber) ->
                                chamber.getMetricGeometry().getCoordinate().distance(tieInPoint))
                        .thenComparing(ImportedOfficialFeature::getFeatureId))
                .orElse(null);
    }

    private List<ImportedOfficialFeature> byType(
            List<ImportedOfficialFeature> features, String objectType) {
        return features.stream()
                .filter(feature -> objectType.equals(feature.getObjectType()))
                .collect(Collectors.toList());
    }

    private boolean isNetworkObject(ImportedOfficialFeature feature) {
        return "source".equals(feature.getObjectType())
                || "heat_network".equals(feature.getObjectType())
                || "heat_chamber".equals(feature.getObjectType());
    }
}
