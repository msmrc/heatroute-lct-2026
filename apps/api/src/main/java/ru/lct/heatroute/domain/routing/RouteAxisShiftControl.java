package ru.lct.heatroute.domain.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import org.locationtech.jts.geom.Coordinate;

/**
 * Находит устранимую ступеньку между параллельными ходами, в том числе через камеры.
 * Переносит весь прямой ход и все его примыкания; обязательный допуск замены выполняет evaluator.
 * Оси берутся из фактической сети, поэтому наличие фоновой карты или дорожного слоя не требуется.
 */
public final class RouteAxisShiftControl {
    private static final double POSITION_TOLERANCE_M = 0.002;
    private static final double ANGLE_TOLERANCE = Math.toRadians(0.1);

    /** Возвращает первую полностью допустимую замену; null означает отсутствие доказанной замены. */
    public RouteVariant firstImprovement(List<RouteNode> nodes, List<RouteEdge> edges,
            Function<Replacement, RouteVariant> evaluator) {
        Graph graph = new Graph(nodes, edges);
        int bendsBefore = new EngineeringRouteEvaluator().evaluate(edges).bendCount();
        Function<Replacement, RouteVariant> improving = replacement -> {
            if (new EngineeringRouteEvaluator().evaluate(replacement.getEdges()).bendCount() >= bendsBefore) return null;
            RouteVariant assessed = evaluator.apply(replacement);
            return assessed != null && new EngineeringRouteEvaluator().evaluate(assessed.getEdges()).bendCount() < bendsBefore
                    ? assessed : null;
        };
        Set<String> attempted = new HashSet<>();
        for (Run connector : graph.runs) {
            ensureActive();
            if (connector.ends.size() != 2) continue;
            Vertex first = connector.ends.get(0), last = connector.ends.get(1);
            for (Run before : graph.incidentRuns(first)) {
                if (before == connector || !perpendicular(before, connector)) continue;
                for (Run after : graph.incidentRuns(last)) {
                    if (after == connector || after == before || !parallel(before, after)
                            || !perpendicular(after, connector)
                            || !opposite(before.outward(first), after.outward(last))) continue;
                    RouteVariant accepted = attempt(graph, after, before, connector, attempted, improving);
                    if (accepted != null) return accepted;
                    accepted = attempt(graph, before, after, connector, attempted, improving);
                    if (accepted != null) return accepted;
                }
            }
        }
        return null;
    }

    private RouteVariant attempt(Graph graph, Run moving, Run fixed, Run connector,
            Set<String> attempted, Function<Replacement, RouteVariant> evaluator) {
        if (!attempted.add(moving.id + ":" + fixed.id)) return null;
        Coordinate origin = fixed.segments.get(0).a.point;
        Coordinate at = moving.segments.get(0).a.point;
        double across = (at.x - origin.x) * -fixed.dy + (at.y - origin.y) * fixed.dx;
        if (Math.abs(across) <= POSITION_TOLERANCE_M) return null;
        Vertex from = connector.ends.stream().filter(moving.vertices::contains).findFirst().orElse(null);
        Vertex to = connector.ends.stream().filter(fixed.vertices::contains).findFirst().orElse(null);
        if (from == null || to == null) return null;
        Replacement replacement = graph.move(moving, connector, to.point,
                to.point.x - from.point.x, to.point.y - from.point.y, connector.segments.get(0).edge.getId());
        return replacement == null ? null : evaluator.apply(replacement);
    }

    private static boolean parallel(Run a, Run b) {
        return Math.abs(a.dx * b.dy - a.dy * b.dx) <= Math.sin(ANGLE_TOLERANCE);
    }

    private static boolean perpendicular(Run a, Run b) {
        return Math.abs(a.dx * b.dx + a.dy * b.dy) <= Math.sin(ANGLE_TOLERANCE);
    }

    private static boolean opposite(Coordinate a, Coordinate b) {
        return a != null && b != null && a.x * b.x + a.y * b.y < -Math.cos(ANGLE_TOLERANCE);
    }

    private static boolean sameAxis(Segment seed, Segment candidate) {
        if (Math.abs(seed.dx * candidate.dy - seed.dy * candidate.dx) > Math.sin(ANGLE_TOLERANCE)) return false;
        return distanceFromAxis(seed, candidate.a.point) <= POSITION_TOLERANCE_M
                && distanceFromAxis(seed, candidate.b.point) <= POSITION_TOLERANCE_M;
    }

    private static double distanceFromAxis(Segment axis, Coordinate point) {
        return Math.abs((point.x - axis.a.point.x) * axis.dy - (point.y - axis.a.point.y) * axis.dx);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Axis shift control cancelled");
    }

    /** Координаты кандидата без старых секций/глубины; их необходимо построить и проверить заново. */
    public static final class Replacement {
        private final List<RouteNode> nodes;
        private final List<RouteEdge> edges;
        private final Set<String> changedEdgeIds;
        private final String subjectId;

        private Replacement(List<RouteNode> nodes, List<RouteEdge> edges, Set<String> changedEdgeIds, String subjectId) {
            this.nodes = List.copyOf(nodes);
            this.edges = List.copyOf(edges);
            this.changedEdgeIds = Set.copyOf(changedEdgeIds);
            this.subjectId = subjectId;
        }

        public List<RouteNode> getNodes() { return nodes; }
        public List<RouteEdge> getEdges() { return edges; }
        public Set<String> getChangedEdgeIds() { return changedEdgeIds; }
        public String getSubjectId() { return subjectId; }
    }

    private static final class Graph {
        private final List<RouteNode> nodes;
        private final List<RouteEdge> edges;
        private final Map<String, Vertex> vertices = new LinkedHashMap<>();
        private final Map<String, List<Vertex>> paths = new LinkedHashMap<>();
        private final List<Segment> segments = new ArrayList<>();
        private final List<Run> runs = new ArrayList<>();

        private Graph(List<RouteNode> nodes, List<RouteEdge> edges) {
            this.nodes = nodes;
            this.edges = edges;
            for (RouteNode node : nodes) vertices.put(node.getId(), new Vertex(node.getCoordinate().toCoordinate(), node));
            List<RouteEdge> ordered = new ArrayList<>(edges);
            ordered.sort(Comparator.comparing(RouteEdge::getId));
            for (RouteEdge edge : ordered) {
                ensureActive();
                List<RouteCoordinate> coordinates = new ArrayList<>();
                for (RouteCoordinate point : edge.getCoordinates()) {
                    if (coordinates.isEmpty() || !coordinates.get(coordinates.size() - 1).toCoordinate().equals2D(point.toCoordinate())) {
                        coordinates.add(point);
                    }
                }
                List<Vertex> path = new ArrayList<>();
                for (int i = 0; i < coordinates.size(); i++) {
                    Vertex vertex = i == 0 ? vertices.get(edge.getUpstreamNodeId())
                            : i == coordinates.size() - 1 ? vertices.get(edge.getDownstreamNodeId())
                            : new Vertex(coordinates.get(i).toCoordinate(), null);
                    if (vertex == null) return;
                    // Конец полилинии должен быть именно в объявленном узле, а не возле него.
                    if (vertex.point.distance(coordinates.get(i).toCoordinate()) > POSITION_TOLERANCE_M) return;
                    path.add(vertex);
                    if (i > 0 && path.get(i - 1).point.distance(vertex.point) > 0) {
                        Segment segment = new Segment(path.get(i - 1), vertex, edge);
                        segments.add(segment);
                        segment.a.segments.add(segment);
                        segment.b.segments.add(segment);
                    }
                }
                paths.put(edge.getId(), path);
            }
            if (paths.size() != edges.size()) return;
            for (Segment seed : segments) {
                ensureActive();
                if (seed.run != null) continue;
                Run run = new Run(runs.size(), seed);
                ArrayDeque<Segment> pending = new ArrayDeque<>();
                seed.run = run;
                pending.add(seed);
                while (!pending.isEmpty()) {
                    ensureActive();
                    Segment segment = pending.removeFirst();
                    run.segments.add(segment);
                    for (Vertex at : List.of(segment.a, segment.b)) {
                        run.vertices.add(at);
                        for (Segment next : at.segments) {
                            if (next.run == null && sameAxis(seed, next)
                                    && continuesStraightThrough(at, seed)) {
                                next.run = run;
                                pending.add(next);
                            }
                        }
                    }
                }
                for (Vertex vertex : run.vertices) {
                    if (vertex.segments.stream().filter(segment -> segment.run == run).count() == 1) run.ends.add(vertex);
                }
                runs.add(run);
            }
        }

        /**
         * A collinear technical split belongs to one run, but a junction does not.  Crossing a
         * junction here used to absorb the short perpendicular connector into a longer branch
         * run, so the axis-shift pattern disappeared before it could be assessed.
         */
        private boolean continuesStraightThrough(Vertex vertex, Segment axis) {
            for (Segment incident : vertex.segments) {
                if (!sameAxis(axis, incident)) return false;
            }
            return true;
        }

        private Set<Run> incidentRuns(Vertex vertex) {
            Set<Run> result = new LinkedHashSet<>();
            for (Segment segment : vertex.segments) result.add(segment.run);
            return result;
        }

        private Replacement move(Run run, Run connector, Coordinate target, double dx, double dy, String subjectId) {
            if (paths.size() != edges.size()) return null;
            Map<Vertex, Coordinate> moved = new HashMap<>();
            for (Vertex vertex : run.vertices) {
                ensureActive();
                // Существующая врезка и исходные точки ОКС имеют неизменяемые координаты.
                if (vertex.node != null && (vertex.node.isRoot() || vertex.node.getTargetId() != null
                        || "demand_connection".equals(vertex.node.getNodeType()))) return null;
                moved.put(vertex, new RouteCoordinate(vertex.point.x + dx, vertex.point.y + dy).toCoordinate());
            }
            for (Vertex vertex : connector.vertices) {
                if (vertex.node != null && (vertex.node.isRoot() || vertex.node.getTargetId() != null
                        || "demand_connection".equals(vertex.node.getNodeType()))
                        && vertex.point.distance(target) > POSITION_TOLERANCE_M) return null;
                moved.put(vertex, target);
            }
            List<RouteNode> replacementNodes = new ArrayList<>();
            for (RouteNode node : nodes) {
                Coordinate point = moved.get(vertices.get(node.getId()));
                replacementNodes.add(point == null ? node : new RouteNode(node.getId(), node.getNodeType(),
                        new RouteCoordinate(point.x, point.y), node.isChamber(), node.isRoot(),
                        node.getBaseIncidentSections(), node.getTargetId(), node.getExistingIncidentDiameter()));
            }
            Set<String> changed = new LinkedHashSet<>();
            List<RouteEdge> replacementEdges = new ArrayList<>();
            for (RouteEdge edge : edges) {
                List<Vertex> path = paths.get(edge.getId());
                if (path.stream().noneMatch(moved::containsKey)) { replacementEdges.add(edge); continue; }
                List<RouteCoordinate> coordinates = new ArrayList<>();
                Coordinate previous = null;
                double length = 0;
                for (Vertex vertex : path) {
                    Coordinate point = moved.getOrDefault(vertex, vertex.point);
                    if (previous != null && previous.equals2D(point)) continue;
                    if (previous != null) length += previous.distance(point);
                    coordinates.add(new RouteCoordinate(point.x, point.y));
                    previous = point;
                }
                changed.add(edge.getId());
                replacementEdges.add(new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                        length, coordinates, List.of(), edge.getFlowTph(), edge.getDiameter()));
            }
            // Коллинеарное техническое разбиение не может спрятать ступеньку. Удаляем только
            // нулевые звенья через технические узлы степени 2; реальные камеры не объединяем.
            for (int i = 0; i < replacementEdges.size(); i++) {
                RouteEdge zero = replacementEdges.get(i);
                if (zero.getLengthM().signum() != 0) continue;
                RouteNode remove = removableTechnicalNode(zero.getUpstreamNodeId(), replacementNodes, replacementEdges);
                if (remove == null) remove = removableTechnicalNode(zero.getDownstreamNodeId(), replacementNodes, replacementEdges);
                if (remove == null) return null;
                String removedId = remove.getId();
                String retainedId = zero.getUpstreamNodeId().equals(removedId) ? zero.getDownstreamNodeId() : zero.getUpstreamNodeId();
                replacementEdges.remove(i--);
                replacementNodes.removeIf(node -> node.getId().equals(removedId));
                for (int j = 0; j < replacementEdges.size(); j++) {
                    RouteEdge edge = replacementEdges.get(j);
                    if (!edge.getUpstreamNodeId().equals(removedId) && !edge.getDownstreamNodeId().equals(removedId)) continue;
                    changed.add(edge.getId());
                    replacementEdges.set(j, new RouteEdge(edge.getId(),
                            edge.getUpstreamNodeId().equals(removedId) ? retainedId : edge.getUpstreamNodeId(),
                            edge.getDownstreamNodeId().equals(removedId) ? retainedId : edge.getDownstreamNodeId(),
                            edge.getLengthM().doubleValue(), edge.getCoordinates(), List.of(), edge.getFlowTph(), edge.getDiameter()));
                }
            }
            return new Replacement(replacementNodes, replacementEdges, changed, subjectId);
        }

        private RouteNode removableTechnicalNode(String id, List<RouteNode> nodes, List<RouteEdge> edges) {
            RouteNode node = nodes.stream().filter(at -> at.getId().equals(id)).findFirst().orElse(null);
            if (node == null || node.isRoot() || node.isChamber() || node.getTargetId() != null
                    || "demand_connection".equals(node.getNodeType())) return null;
            return edges.stream().filter(edge -> edge.getUpstreamNodeId().equals(id) || edge.getDownstreamNodeId().equals(id)).count() == 2
                    ? node : null;
        }
    }

    private static final class Vertex {
        private final Coordinate point;
        private final RouteNode node;
        private final List<Segment> segments = new ArrayList<>();

        private Vertex(Coordinate point, RouteNode node) { this.point = point; this.node = node; }
    }

    private static final class Segment {
        private final Vertex a, b;
        private final RouteEdge edge;
        private final double dx, dy;
        private Run run;

        private Segment(Vertex a, Vertex b, RouteEdge edge) {
            this.a = a; this.b = b; this.edge = edge;
            double length = a.point.distance(b.point);
            dx = (b.point.x - a.point.x) / length;
            dy = (b.point.y - a.point.y) / length;
        }
    }

    private static final class Run {
        private final int id;
        private final double dx, dy;
        private final List<Segment> segments = new ArrayList<>();
        private final Set<Vertex> vertices = new LinkedHashSet<>();
        private final List<Vertex> ends = new ArrayList<>();

        private Run(int id, Segment seed) { this.id = id; dx = seed.dx; dy = seed.dy; }

        private Coordinate outward(Vertex at) {
            for (Segment segment : at.segments) {
                if (segment.run == this) return segment.a == at
                        ? new Coordinate(segment.dx, segment.dy) : new Coordinate(-segment.dx, -segment.dy);
            }
            return null;
        }
    }
}
