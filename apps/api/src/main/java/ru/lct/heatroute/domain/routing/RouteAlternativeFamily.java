package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatroute.domain.depth.DepthCrossingDecision;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Семантика завершённого леса: группы потребителей, исходные присоединения и реальные проходы. */
final class RouteAlternativeFamily {
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private final String decisions;
    private final Map<String, LineString> paths;

    private RouteAlternativeFamily(String decisions, Map<String, LineString> paths) {
        this.decisions = decisions;
        this.paths = paths;
    }

    boolean sameDecisions(RouteAlternativeFamily other) { return decisions.equals(other.decisions); }
    Map<String, LineString> paths() { return paths; }

    static RouteAlternativeFamily of(RouteVariant variant, Source source) {
        Graph graph = new Graph(variant);
        Map<String, String> subtree = new HashMap<>();
        List<String> roots = new ArrayList<>();
        Map<String, LineString> paths = new TreeMap<>();
        List<String> passages = new ArrayList<>();
        for (int i = graph.order.size() - 1; i >= 0; i--) {
            DistinctRouteAlternatives.ensureActive();
            RouteNode node = graph.order.get(i);
            List<String> children = new ArrayList<>();
            for (String child : graph.children.get(node.getId())) children.add(subtree.get(child));
            Collections.sort(children);
            String key;
            if ("demand_connection".equals(node.getNodeType())) {
                if (!children.isEmpty()) throw malformed();
                String demand = source.demand(node, variant.getConnections());
                key = tuple("demand", demand);
                Path path = graph.path(node.getId(), source);
                if (paths.put(demand, path.geometry) != null) throw malformed();
                passages.add(tuple(demand, path.passages(source)));
            } else {
                // Любой degree-2 узел лишь делит ту же трассу; его положение и имя не меняют семью.
                key = children.size() == 1 ? children.get(0) : tuple("branch", children);
            }
            subtree.put(node.getId(), key);
            if (node.isRoot()) roots.add(tuple(source.root(node), key));
        }
        List<String> connections = new ArrayList<>();
        for (RouteConnection connection : variant.getConnections()) {
            DistinctRouteAlternatives.ensureActive();
            connections.add(tuple(connection.getDemandId(), connection.getConnectionPointId(), connection.getStatus()));
        }
        Collections.sort(roots);
        Collections.sort(passages);
        Collections.sort(connections);
        boolean depth = variant.getEdges().stream().anyMatch(e -> e.getDepthProfile() != null);
        return new RouteAlternativeFamily(tuple(roots, connections, passages, depth), paths);
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Distinct alternatives require a finalized rooted forest with identified demands");
    }

    private static String tuple(Object... values) {
        StringBuilder out = new StringBuilder();
        for (Object value : values) {
            String text = value instanceof List<?> ? tuple(((List<?>) value).toArray()) : String.valueOf(value);
            out.append(text.length()).append(':').append(text);
        }
        return out.toString();
    }

    static final class Source {
        private final Map<String, ImportedOfficialFeature> features = new HashMap<>();
        private final Map<String, String> sourceKeys = new LinkedHashMap<>();

        Source(List<ImportedOfficialFeature> inputs) {
            for (ImportedOfficialFeature feature : inputs) {
                DistinctRouteAlternatives.ensureActive();
                if (feature != null) features.put(feature.getFeatureId(), feature);
            }
        }

        private String root(RouteNode node) {
            String target = node.getTargetId();
            if (target == null) throw malformed();
            ImportedOfficialFeature feature = features.get(target);
            String canonical = sourceKeys.get(target);
            if (canonical == null) {
                canonical = target;
                for (Map.Entry<String, String> entry : sourceKeys.entrySet()) {
                    DistinctRouteAlternatives.ensureActive();
                    ImportedOfficialFeature previous = features.get(entry.getKey());
                    if (feature != null && previous != null
                            && feature.getObjectType().equals(previous.getObjectType())
                            && feature.getMetricGeometry() != null && previous.getMetricGeometry() != null
                            && feature.getMetricGeometry().equalsTopo(previous.getMetricGeometry())) {
                        canonical = entry.getValue();
                        break;
                    }
                }
                sourceKeys.put(target, canonical);
            }
            return tuple(node.getNodeType(), canonical);
        }

        private String demand(RouteNode node, List<RouteConnection> connections) {
            List<String> matches = new ArrayList<>();
            for (RouteConnection connection : connections) {
                DistinctRouteAlternatives.ensureActive();
                if (!"connected".equals(connection.getStatus())) continue;
                ImportedOfficialFeature feature = features.get(connection.getConnectionPointId());
                if (feature != null && feature.getMetricGeometry() != null
                        && feature.getMetricGeometry().getDimension() == 0) {
                    Coordinate coordinate = feature.getMetricGeometry().getCoordinate();
                    // Тот же millimetre rounding, что у RouteCoordinate; это не допуск «малого сдвига».
                    if (coordinate != null && new RouteCoordinate(coordinate.x, coordinate.y).toCoordinate()
                            .equals2D(node.getCoordinate().toCoordinate())) matches.add(connection.getDemandId());
                }
            }
            if (matches.size() == 1) return matches.get(0);
            List<String> identified = new ArrayList<>();
            for (RouteConnection connection : connections) {
                if (!"connected".equals(connection.getStatus())) continue;
                if (connection.getConnectionPointId().equals(node.getTargetId())) identified.add(connection.getDemandId());
            }
            if (identified.size() == 1) return identified.get(0);
            for (RouteConnection connection : connections) if ("connected".equals(connection.getStatus())
                    && ("demand:" + connection.getDemandId()).equals(node.getId())) return connection.getDemandId();
            throw malformed();
        }

        private String crossingFeature(String id) {
            if (features.containsKey(id)) return id;
            int suffix = id == null ? -1 : id.lastIndexOf('#');
            if (suffix > 0 && id.substring(suffix + 1).matches("[0-9]+")
                    && features.containsKey(id.substring(0, suffix))) return id.substring(0, suffix);
            return id;
        }

        private List<String> sectionFeatures(RouteSection section) {
            String id = section.getRestrictionId();
            if (id == null) return List.of();
            if (features.containsKey(id)) return List.of(id);
            return List.of(id.split("\\+"));
        }
    }

    private static final class Graph {
        private final Map<String, RouteNode> nodes = new HashMap<>();
        private final Map<String, List<RouteEdge>> adjacent = new HashMap<>();
        private final Map<String, List<String>> children = new HashMap<>();
        private final Map<String, RouteEdge> parent = new HashMap<>();
        private final List<RouteNode> order = new ArrayList<>();

        private Graph(RouteVariant variant) {
            for (RouteNode node : variant.getNodes()) {
                DistinctRouteAlternatives.ensureActive();
                if (nodes.put(node.getId(), node) != null) throw malformed();
                adjacent.put(node.getId(), new ArrayList<>());
                children.put(node.getId(), new ArrayList<>());
            }
            for (RouteEdge edge : variant.getEdges()) {
                DistinctRouteAlternatives.ensureActive();
                if (!nodes.containsKey(edge.getUpstreamNodeId()) || !nodes.containsKey(edge.getDownstreamNodeId())) throw malformed();
                adjacent.get(edge.getUpstreamNodeId()).add(edge);
                adjacent.get(edge.getDownstreamNodeId()).add(edge);
            }
            Set<String> visited = new HashSet<>();
            for (RouteNode node : variant.getNodes()) if (node.isRoot()) {
                if (!visited.add(node.getId())) throw malformed();
                order.add(node);
            }
            for (int i = 0; i < order.size(); i++) {
                DistinctRouteAlternatives.ensureActive();
                RouteNode node = order.get(i);
                for (RouteEdge edge : adjacent.get(node.getId())) {
                    if (edge == parent.get(node.getId())) continue;
                    String other = other(edge, node.getId());
                    if (!visited.add(other)) throw malformed();
                    parent.put(other, edge);
                    children.get(node.getId()).add(other);
                    order.add(nodes.get(other));
                }
            }
            if (visited.size() != nodes.size()) throw malformed();
        }

        private Path path(String leaf, Source source) {
            List<String> chain = new ArrayList<>();
            String node = leaf;
            while (parent.containsKey(node)) {
                DistinctRouteAlternatives.ensureActive();
                chain.add(node);
                node = other(parent.get(node), node);
            }
            Collections.reverse(chain);
            Path path = new Path();
            for (String child : chain) {
                RouteEdge edge = parent.get(child);
                path.append(edge, edge.getUpstreamNodeId().equals(node), source);
                node = child;
            }
            path.complete();
            return path;
        }

        private String other(RouteEdge edge, String node) {
            return edge.getUpstreamNodeId().equals(node) ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId();
        }
    }

    private static final class Path {
        private final List<Coordinate> coordinates = new ArrayList<>();
        private final Set<String> specialFeatures = new HashSet<>();
        private final List<String> unknownSections = new ArrayList<>();
        private final List<Passage> depths = new ArrayList<>();
        private double lengthM;
        private LineString geometry;

        private void append(RouteEdge edge, boolean forward, Source source) {
            List<RouteCoordinate> points = edge.getCoordinates();
            if (points.size() < 2) throw malformed();
            double edgeLength = 0;
            for (int i = 0; i < points.size(); i++) {
                DistinctRouteAlternatives.ensureActive();
                Coordinate point = points.get(forward ? i : points.size() - 1 - i).toCoordinate();
                if (i > 0) edgeLength += point.distance(points.get(forward ? i - 1 : points.size() - i).toCoordinate());
                if (coordinates.isEmpty() || !coordinates.get(coordinates.size() - 1).equals2D(point)) coordinates.add(point);
            }
            List<RouteSection> sections = new ArrayList<>(edge.getSections());
            if (!forward) Collections.reverse(sections);
            for (RouteSection section : sections) if ("special".equals(section.getKind())) {
                for (String id : source.sectionFeatures(section)) {
                    if (source.features.containsKey(id)) specialFeatures.add(id);
                    else {
                        String key = tuple(section.getRestrictionType(), id);
                        if (unknownSections.isEmpty() || !unknownSections.get(unknownSections.size() - 1).equals(key)) unknownSections.add(key);
                    }
                }
            }
            if (edge.getDepthProfile() != null) for (DepthCrossingDecision decision : edge.getDepthProfile().getCrossings()) {
                DistinctRouteAlternatives.ensureActive();
                String id = source.crossingFeature(decision.getCrossingId());
                double station = (decision.getPlateauStartM().doubleValue() + decision.getPlateauEndM().doubleValue()) / 2;
                depths.add(new Passage(lengthM + (forward ? station : edgeLength - station), id, decision.getPassage()));
                specialFeatures.add(id);
            }
            lengthM += edgeLength;
        }

        private void complete() {
            if (coordinates.size() < 2) throw malformed();
            geometry = GEOMETRY.createLineString(coordinates.toArray(new Coordinate[0]));
        }

        private String passages(Source source) {
            List<Passage> passages = new ArrayList<>();
            LengthIndexedLine indexed = new LengthIndexedLine(geometry);
            for (String id : specialFeatures) {
                DistinctRouteAlternatives.ensureActive();
                ImportedOfficialFeature feature = source.features.get(id);
                if (feature == null || feature.getMetricGeometry() == null) {
                    for (Passage depth : depths) if (id.equals(depth.feature)) passages.add(depth);
                    continue;
                }
                Geometry intersection = geometry.intersection(feature.getMetricGeometry());
                List<Double> stations = new ArrayList<>();
                stations(intersection, indexed, stations);
                for (double station : stations) {
                    // Реальный проход из геометрии, поэтому technical splits/overlap sections не добавляют событий.
                    if (station <= 0 || station >= geometry.getLength()) continue;
                    Passage nearest = null;
                    for (Passage depth : depths) if (id.equals(depth.feature)
                            && (nearest == null || Math.abs(depth.station - station) < Math.abs(nearest.station - station))) nearest = depth;
                    passages.add(new Passage(station, id, nearest == null ? "special" : nearest.mode));
                }
            }
            passages.sort(Comparator.comparingDouble((Passage p) -> p.station).thenComparing(p -> p.feature).thenComparing(p -> p.mode));
            List<String> words = new ArrayList<>();
            for (Passage passage : passages) words.add(tuple(passage.feature, passage.mode));
            return tuple(words, unknownSections);
        }

        private void stations(Geometry part, LengthIndexedLine indexed, List<Double> result) {
            DistinctRouteAlternatives.ensureActive();
            if (part.isEmpty()) return;
            if (part.getNumGeometries() > 1) {
                for (int i = 0; i < part.getNumGeometries(); i++) stations(part.getGeometryN(i), indexed, result);
            } else {
                Coordinate[] points = part.getCoordinates();
                if (points.length > 0) result.add((indexed.project(points[0]) + indexed.project(points[points.length - 1])) / 2);
            }
        }
    }

    private static final class Passage {
        private final double station;
        private final String feature;
        private final String mode;

        private Passage(double station, String feature, String mode) {
            this.station = station;
            this.feature = feature;
            this.mode = mode;
        }
    }
}
