package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Formatter;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Неизменяемый кандидат полной сети: evaluator может назначить производные flow/depth,
 * но не вправе менять topology или координаты.
 */
public final class FrozenNetworkCandidate {
    private final String id;
    private final String strategy;
    private final List<RouteNode> nodes;
    private final List<RouteEdge> edges;
    private final List<RouteConnection> connections;
    private final List<ImportedOfficialFeature> relevantFeatures;
    private final OfficialRunParameters parameters;
    private final boolean reconstructionRequired;
    private final String geometryHash;

    public FrozenNetworkCandidate(
            String id,
            String strategy,
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<RouteConnection> connections,
            List<ImportedOfficialFeature> relevantFeatures,
            OfficialRunParameters parameters,
            boolean reconstructionRequired) {
        this.id = required(id, "candidate id");
        this.strategy = required(strategy, "strategy");
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        this.edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        this.connections = List.copyOf(Objects.requireNonNull(connections, "connections"));
        this.relevantFeatures = freezeFeatures(relevantFeatures);
        this.parameters = Objects.requireNonNull(parameters, "parameters").validated();
        this.reconstructionRequired = reconstructionRequired;
        this.geometryHash = geometryHash(this.nodes, this.edges);
    }

    public String getId() { return id; }
    public String getStrategy() { return strategy; }
    public List<RouteNode> getNodes() { return nodes; }
    public List<RouteEdge> getEdges() { return edges; }
    public List<RouteConnection> getConnections() { return connections; }
    public List<ImportedOfficialFeature> getRelevantFeatures() { return relevantFeatures; }
    public OfficialRunParameters getParameters() { return parameters; }
    public boolean isReconstructionRequired() { return reconstructionRequired; }
    public String getGeometryHash() { return geometryHash; }

    static String geometryHash(List<RouteNode> nodes, List<RouteEdge> edges) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<RouteNode> orderedNodes = new ArrayList<>(nodes);
            orderedNodes.sort(Comparator.comparing(RouteNode::getId));
            for (RouteNode node : orderedNodes) {
                update(digest, "N", node.getId(), node.getNodeType(), node.getCoordinate().getXM().toPlainString(),
                        node.getCoordinate().getYM().toPlainString(), Boolean.toString(node.isRoot()),
                        Boolean.toString(node.isChamber()), Integer.toString(node.getBaseIncidentSections()),
                        Objects.toString(node.getTargetId(), ""));
            }
            List<RouteEdge> orderedEdges = new ArrayList<>(edges);
            orderedEdges.sort(Comparator.comparing(RouteEdge::getId));
            for (RouteEdge edge : orderedEdges) {
                update(digest, "E", edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId());
                for (RouteCoordinate coordinate : edge.getCoordinates()) {
                    update(digest, "P", coordinate.getXM().toPlainString(), coordinate.getYM().toPlainString());
                }
            }
            try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
                for (byte value : digest.digest()) formatter.format("%02x", value);
                return formatter.toString();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }

    private static List<ImportedOfficialFeature> freezeFeatures(List<ImportedOfficialFeature> features) {
        Objects.requireNonNull(features, "relevantFeatures");
        List<ImportedOfficialFeature> frozen = new ArrayList<>(features.size());
        for (ImportedOfficialFeature feature : features) {
            if (feature == null) throw new IllegalArgumentException("Relevant feature cannot be null");
            JsonNode attributes = feature.getAttributes();
            Geometry geometry = feature.getMetricGeometry();
            frozen.add(new ImportedOfficialFeature(
                    feature.getFeatureId(),
                    feature.getObjectType(),
                    attributes == null ? null : attributes.deepCopy(),
                    geometry == null ? null : geometry.copy()));
        }
        frozen.sort(Comparator.comparing(ImportedOfficialFeature::getFeatureId));
        return List.copyOf(frozen);
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
