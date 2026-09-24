package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/**
 * Сначала прокладывает общую магистраль группы, затем её конечные ответвления.
 * Результат — только кандидат: планировщик повторно проверяет ДУ, геометрию и стоимость всей сети.
 */
final class SharedSpineNetworkBuilder {
    private static final double MAX_STATION_SHIFT_M = 80.0;
    // Только численная погрешность метрических координат: проектный интервал остаётся 2 м.
    private static final double STATION_NUMERIC_EPSILON_M = 1e-7;
    private final OfficialObstacleRouter router;
    private final OfficialPipeCatalog pipes;
    private final GeometryFactory geometries = new GeometryFactory();

    SharedSpineNetworkBuilder(OfficialObstacleRouter router, OfficialPipeCatalog pipes) {
        this.router = router;
        this.pipes = pipes;
    }

    Network build(SharedSpineCandidateBuilder.SpineCandidate candidate, RouteNode root,
            Map<String, Terminal> terminals, OfficialRoutingEnvironment environment,
            TerminalRouter terminalRouter, RootRouter rootRouter) {
        return build(candidate, root, terminals, environment, List.of(), terminalRouter, rootRouter);
    }

    /** Сохранённые участки участвуют в обходах, но не входят в возвращаемую новую сеть. */
    Network build(SharedSpineCandidateBuilder.SpineCandidate candidate, RouteNode root,
            Map<String, Terminal> terminals, OfficialRoutingEnvironment environment,
            List<RouteEdge> retainedEdges, TerminalRouter terminalRouter, RootRouter rootRouter) {
        ensureNotCancelled();
        List<SharedSpineCandidateBuilder.Junction> junctions = candidate.junctions();
        int rootIndex = candidate.rootJunctionIndex();
        BigDecimal[] localFlows = new BigDecimal[junctions.size()];
        BigDecimal totalFlow = BigDecimal.ZERO;
        for (int i = 0; i < junctions.size(); i++) {
            localFlows[i] = BigDecimal.ZERO;
            for (String id : junctions.get(i).terminalIds()) {
                Terminal terminal = terminals.get(id);
                if (terminal == null || terminal.flowTph.signum() <= 0) return null;
                localFlows[i] = localFlows[i].add(terminal.flowTph);
            }
            totalFlow = totalFlow.add(localFlows[i]);
        }
        if (diameter(totalFlow) == null) return null;
        Network network = new Network(retainedEdges);
        network.nodes.add(root);
        List<Coordinate> stations = junctions.stream().map(SharedSpineCandidateBuilder.Junction::coordinate)
                .collect(Collectors.toCollection(ArrayList::new));
        Envelope stationBounds = new Envelope();
        stations.forEach(stationBounds::expandToInclude);
        stationBounds.expandBy(MAX_STATION_SHIFT_M);
        Map<Integer, Predicate<Coordinate>> pointChecks = new HashMap<>();
        for (int i = 0; i < junctions.size(); i++) {
            BigDecimal flow = i < rootIndex ? sum(localFlows, 0, i + 1)
                    : i > rootIndex ? sum(localFlows, i, localFlows.length) : totalFlow;
            Integer du = diameter(flow);
            if (du == null) return null;
            Predicate<Coordinate> blocked = pointChecks.computeIfAbsent(du,
                    diameter -> environment.preparePointClearance(diameter, stationBounds));
            Coordinate coordinate = freeStation(stations, i, root, terminals, blocked);
            if (coordinate == null) return null;
            stations.set(i, coordinate);
            network.nodes.add(new RouteNode("spine:" + root.getId() + ":" + i,
                    "new_branch_chamber", new RouteCoordinate(coordinate.x, coordinate.y),
                    true, false, 0, null));
        }
        RouteNode rootJunction = network.nodes.get(rootIndex + 1);
        RoutePath rootPath = rootRouter.route(rootJunction.getCoordinate().toCoordinate(), totalFlow,
                diameter(totalFlow), avoidance(network, root.getId(), rootJunction.getId()));
        if (!addEdge(network, root, rootJunction, rootPath == null ? null : rootPath.reversed(), totalFlow)) {
            return null;
        }
        for (int i = rootIndex - 1; i >= 0; i--) {
            if (!connect(network, network.nodes.get(i + 2), network.nodes.get(i + 1),
                    sum(localFlows, 0, i + 1), environment)) return null;
        }
        for (int i = rootIndex + 1; i < junctions.size(); i++) {
            if (!connect(network, network.nodes.get(i), network.nodes.get(i + 1),
                    sum(localFlows, i, localFlows.length), environment)) return null;
        }
        // Магистраль уже существует целиком: терминальные ветки не определяют её положение.
        for (int i = 0; i < junctions.size(); i++) {
            RouteNode junction = network.nodes.get(i + 1);
            List<Terminal> branches = junctions.get(i).terminalIds().stream()
                    .map(terminals::get)
                    .sorted(Comparator.comparing((Terminal terminal) -> terminal.flowTph).reversed()
                            .thenComparingDouble(terminal -> terminal.coordinate.x)
                            .thenComparingDouble(terminal -> terminal.coordinate.y)
                            .thenComparing(terminal -> terminal.id))
                    .collect(Collectors.toList());
            for (Terminal terminal : branches) {
                ensureNotCancelled();
                RouteNode leaf = new RouteNode("demand:" + terminal.id, "demand_connection",
                        new RouteCoordinate(terminal.coordinate.x, terminal.coordinate.y),
                        false, false, 0, null);
                RoutePath branch = terminalRouter.route(terminal.id, junction.getCoordinate().toCoordinate(),
                        diameter(terminal.flowTph), avoidance(network, junction.getId(), leaf.getId()));
                if (!addEdge(network, junction, leaf, branch == null ? null : branch.reversed(),
                        terminal.flowTph)) return null;
                network.nodes.add(leaf);
                network.connections.add(new RouteConnection(terminal.id, terminal.connectionPointId,
                        terminal.flowTph, "connected", null));
            }
        }
        ensureNotCancelled();
        return network;
    }

    /** Сдвигает камеру вдоль коридора в свободный проход, сохраняя порядок и интервал между узлами. */
    private Coordinate freeStation(List<Coordinate> stations, int index, RouteNode root,
            Map<String, Terminal> terminals, Predicate<Coordinate> blocked) {
        Coordinate origin = stations.get(index);
        Coordinate first = stations.get(0);
        Coordinate last = stations.get(stations.size() - 1);
        double length = first.distance(last);
        double ux = length > 0.01 ? (last.x - first.x) / length : 1;
        double uy = length > 0.01 ? (last.y - first.y) / length : 0;
        for (double offset : new double[] {0, 2, -2, 5, -5, 10, -10, 20, -20, 40, -40,
                MAX_STATION_SHIFT_M, -MAX_STATION_SHIFT_M}) {
            ensureNotCancelled();
            Coordinate point = new Coordinate(origin.x + ux * offset, origin.y + uy * offset);
            if (index > 0 && projection(stations.get(index - 1), point, ux, uy) + STATION_NUMERIC_EPSILON_M < 2.0) continue;
            if (index + 1 < stations.size()
                    && projection(point, stations.get(index + 1), ux, uy) + STATION_NUMERIC_EPSILON_M < 2.0) continue;
            if (point.distance(root.getCoordinate().toCoordinate()) <= 0.01
                    || terminals.values().stream().anyMatch(terminal -> point.distance(terminal.coordinate) <= 0.01)) continue;
            if (!blocked.test(point)) return point;
        }
        return null;
    }

    private double projection(Coordinate start, Coordinate end, double ux, double uy) {
        return (end.x - start.x) * ux + (end.y - start.y) * uy;
    }

    private boolean connect(Network network, RouteNode from, RouteNode to, BigDecimal flow,
            OfficialRoutingEnvironment environment) {
        ensureNotCancelled();
        RoutePath path = router.find(from.getCoordinate().toCoordinate(), to.getCoordinate().toCoordinate(),
                diameter(flow), environment, Set.of(), RoutePreference.ENGINEERING,
                avoidance(network, from.getId(), to.getId()));
        return addEdge(network, from, to, path, flow);
    }

    private boolean addEdge(Network network, RouteNode from, RouteNode to, RoutePath path, BigDecimal flow) {
        ensureNotCancelled();
        if (path == null || path.lengthM() <= 0.01) return false;
        network.edges.add(new RouteEdge("spine:" + from.getId() + ":" + to.getId(),
                from.getId(), to.getId(), path.lengthM(), path.coordinates().stream()
                        .map(coordinate -> new RouteCoordinate(coordinate.x, coordinate.y))
                        .collect(Collectors.toList()), path.sections(), flow, diameter(flow)));
        return true;
    }

    private List<LineString> avoidance(Network network, String fromId, String toId) {
        return Stream.concat(network.edges.stream(), network.retainedEdges.stream())
                .filter(edge -> edge.getCoordinates().size() >= 2)
                .filter(edge -> !edge.getUpstreamNodeId().equals(fromId) && !edge.getDownstreamNodeId().equals(fromId)
                        && !edge.getUpstreamNodeId().equals(toId) && !edge.getDownstreamNodeId().equals(toId))
                .map(edge -> geometries.createLineString(edge.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new)))
                .collect(Collectors.toList());
    }

    private BigDecimal sum(BigDecimal[] flows, int start, int end) {
        BigDecimal result = BigDecimal.ZERO;
        for (int i = start; i < end; i++) result = result.add(flows[i]);
        return result;
    }

    private Integer diameter(BigDecimal flow) {
        return flow.signum() <= 0 ? null : pipes.minimumForFlow(flow)
                .map(entry -> entry.getDiameter()).orElse(null);
    }

    private void ensureNotCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Route calculation was cancelled");
    }

    @FunctionalInterface
    interface TerminalRouter {
        RoutePath route(String demandId, Coordinate junction, int diameter, List<LineString> avoidance);
    }

    @FunctionalInterface
    interface RootRouter {
        RoutePath route(Coordinate junction, BigDecimal flow, int diameter, List<LineString> avoidance);
    }

    static final class Terminal {
        private final String id;
        private final String connectionPointId;
        private final Coordinate coordinate;
        private final BigDecimal flowTph;

        Terminal(String id, String connectionPointId, Coordinate coordinate, BigDecimal flowTph) {
            this.id = id;
            this.connectionPointId = connectionPointId;
            this.coordinate = new Coordinate(coordinate);
            this.flowTph = flowTph;
        }
    }

    static final class Network {
        private final List<RouteEdge> retainedEdges;
        private final List<RouteNode> nodes = new ArrayList<>();
        private final List<RouteEdge> edges = new ArrayList<>();
        private final List<RouteConnection> connections = new ArrayList<>();

        private Network(List<RouteEdge> retainedEdges) { this.retainedEdges = List.copyOf(retainedEdges); }

        List<RouteNode> nodes() { return List.copyOf(nodes); }
        List<RouteEdge> edges() { return List.copyOf(edges); }
        List<RouteConnection> connections() { return List.copyOf(connections); }
    }
}
