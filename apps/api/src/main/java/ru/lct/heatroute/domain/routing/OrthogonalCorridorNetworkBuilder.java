package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/**
 * Формирует общую сеть на графе свободных ортогональных коридоров и присоединяет нормальные вводы.
 * Камерами становятся только узлы ветвления; повороты и промежуточные точки сетки остаются
 * геометрией трубы. Итоговый sizing, экономика и независимый допуск принадлежат планировщику.
 */
final class OrthogonalCorridorNetworkBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrthogonalCorridorNetworkBuilder.class);
    private static final int MAX_CORRIDOR_TERMINALS = 64;
    private final OfficialObstacleRouter router;
    private final OfficialPipeCatalog pipes;

    OrthogonalCorridorNetworkBuilder(OfficialObstacleRouter router, OfficialPipeCatalog pipes) {
        this.router = router;
        this.pipes = pipes;
    }

    List<Network> build(List<Terminal> input, RouteNode root, int rootCapacity,
            List<Geometry> footprints, OfficialRoutingEnvironment environment,
            SharedSpineNetworkBuilder.TerminalRouter terminalRouter) {
        return buildInFrame(input, root, rootCapacity, footprints, environment, terminalRouter, null);
    }

    /** Сохраняет контрольную сетку и добавляет не более одной оси от допустимых вводов зданий. */
    List<Network> buildWithTerminalFrame(List<Terminal> input, RouteNode root, int rootCapacity,
            List<Geometry> footprints, OfficialRoutingEnvironment environment,
            SharedSpineNetworkBuilder.TerminalRouter terminalRouter) {
        List<Network> result = new ArrayList<>(build(input, root, rootCapacity, footprints, environment, terminalRouter));
        if (input.isEmpty() || input.size() > MAX_CORRIDOR_TERMINALS || rootCapacity < 1) return result;
        Integer diameter = diameter(input.stream().map(t -> t.flow).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (diameter == null) return result;
        double base = CorridorOrientation.angle(footprints,
                input.stream().map(t -> t.point).collect(Collectors.toList()), root.getCoordinate().toCoordinate());
        List<List<Double>> axesByTerminal = new ArrayList<>();
        for (Terminal terminal : input) {
            ensureActive();
            List<Double> axes = new ArrayList<>();
            for (int direction = 0; direction < 4; direction++) {
                double angle = base + direction * Math.PI / 2;
                Coordinate target = new Coordinate(terminal.point.x + 200 * Math.cos(angle), terminal.point.y + 200 * Math.sin(angle));
                List<OfficialRouteGeometryRules.NormalEgress> egresses = environment.normalEgressCandidates(
                        diameter, terminal.point, target, RoutePlannerTuning.stable().getEngineeringEgressExtraM());
                for (int i = 0; i < Math.min(16, egresses.size()); i++) {
                    Coordinate start = egresses.get(i).start(), exit = egresses.get(i).exit();
                    if (start.distance(exit) > 0.01) axes.add(Math.atan2(exit.y - start.y, exit.x - start.x));
                }
            }
            axesByTerminal.add(axes);
        }
        java.util.OptionalDouble alternative = CorridorTerminalOrientation.alternative(base, axesByTerminal);
        if (alternative.isPresent()) {
            LOGGER.info("Corridor alternative frame target={} base_deg={} alternative_deg={}",
                    root.getTargetId(), Math.toDegrees(base), Math.toDegrees(alternative.getAsDouble()));
            result.addAll(buildInFrame(input, root, rootCapacity, footprints, environment, terminalRouter, alternative.getAsDouble()));
        }
        return result;
    }

    private List<Network> buildInFrame(List<Terminal> input, RouteNode root, int rootCapacity,
            List<Geometry> footprints, OfficialRoutingEnvironment environment,
            SharedSpineNetworkBuilder.TerminalRouter terminalRouter, Double suppliedAngle) {
        ensureActive();
        // Большая группа остаётся основному поиску; ограничение этой кандидатной стратегии
        // не отбрасывает потребителей из общего расчёта.
        if (input.isEmpty() || input.size() > MAX_CORRIDOR_TERMINALS) return List.of();
        Set<String> ids = new HashSet<>();
        for (Terminal terminal : input) {
            if (!ids.add(terminal.id)) throw new IllegalArgumentException("Duplicate corridor terminal ID");
        }
        List<Terminal> terminals = new ArrayList<>(input);
        terminals.sort(Comparator.comparingDouble((Terminal t) -> t.point.x)
                .thenComparingDouble(t -> t.point.y).thenComparing(t -> t.id));
        BigDecimal totalFlow = terminals.stream().map(t -> t.flow).reduce(BigDecimal.ZERO, BigDecimal::add);
        Integer diameter = diameter(totalFlow);
        if (diameter == null || rootCapacity < 1) return List.of();
        Coordinate origin = root.getCoordinate().toCoordinate();
        double clearance = router.buildingClearanceM(diameter);
        List<Coordinate> anchors = new ArrayList<>();
        List<List<Coordinate>> terminalAnchors = new ArrayList<>();
        double orientation = suppliedAngle == null ? CorridorOrientation.angle(footprints,
                terminals.stream().map(terminal -> terminal.point).collect(Collectors.toList()), origin) : suppliedAngle;
        Envelope bounds = new Envelope(origin);
        for (Terminal terminal : terminals) {
            List<Coordinate> exits = new ArrayList<>();
            for (int direction = 0; direction < 4; direction++) {
                double angle = orientation + direction * Math.PI / 2;
                Coordinate target = new Coordinate(terminal.point.x + 200 * Math.cos(angle),
                        terminal.point.y + 200 * Math.sin(angle));
                Coordinate anchor = environment.normalEgressTowards(diameter, terminal.point, target,
                                RoutePlannerTuning.stable().getEngineeringEgressExtraM())
                        .map(egress -> outsideBuffer(egress, clearance)).orElse(terminal.point);
                if (exits.stream().noneMatch(existing -> existing.distance(anchor) < 0.01)) exits.add(anchor);
            }
            terminalAnchors.add(exits);
            anchors.addAll(exits);
            exits.forEach(bounds::expandToInclude);
        }
        bounds.expandBy(Math.max(30, clearance * 2));
        PreparedCorridor checks = router.prepareCorridor(diameter, environment, bounds, origin, root.getTargetId());
        OrthogonalCorridorGrid grid = OrthogonalCorridorGrid.build(origin, anchors, footprints, clearance,
                checks::pointAllowed, checks::edgeAllowed, orientation);
        CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, environment, terminalRouter, orientation);
        LOGGER.info("Corridor graph target={} nodes={} links={} demands={}", root.getTargetId(),
                grid.points().size(), grid.links().size(), terminals.size());
        Map<Integer, Integer> reservations = new HashMap<>();
        List<Port> ports = new ArrayList<>();
        for (int i = 0; i < terminals.size(); i++) {
            ensureActive();
            Terminal terminal = terminals.get(i);
            Port selected = null;
            for (int index : grid.portsNear(terminal.point, 12)) {
                if (reservations.getOrDefault(index, 0) >= 2) continue;
                RoutePath path = spurs.route(terminal.id, terminal.point, grid.points().get(index), diameter(terminal.flow));
                if (path == null || path.lengthM() <= 0.01) continue;
                selected = new Port(terminal, index, path.reversed());
                break;
            }
            if (selected == null) {
                LOGGER.info("Corridor port unavailable target={} demand={}", root.getTargetId(), terminal.id);
                break;
            }
            ports.add(selected);
            reservations.merge(selected.index, 1, Integer::sum);
        }
        List<Network> results = new ArrayList<>();
        for (boolean farthestFirst : ports.size() == terminals.size() ? new boolean[] {true, false} : new boolean[0]) {
            // Метры — поисковый штраф создания камеры, не подмена официальной сметы.
            for (double junctionPenalty : new double[] {0, 25}) {
                List<int[]> tree = new CorridorTreeBuilder().build(grid.points(), grid.links(), grid.rootIndex(),
                        rootCapacity, reservations, farthestFirst, junctionPenalty, 3.0);
                if (tree == null) {
                    LOGGER.info("Corridor tree unavailable target={} farthest={} junction_penalty={}",
                            root.getTargetId(), farthestFirst, junctionPenalty);
                    continue;
                }
                Network network = compress(tree, grid, ports, root, checks);
                LOGGER.info("Corridor tree target={} farthest={} junction_penalty={} grid_edges={} assembled={}",
                        root.getTargetId(), farthestFirst, junctionPenalty, tree.size(), network != null);
                if (network != null) results.add(network);
            }
        }
        for (double bendPenalty : ports.size() == terminals.size() ? new double[] {0, 3, 15} : new double[0]) {
            List<int[]> tree = new CorridorTreeBuilder().buildMetricClosure(grid.points(), grid.links(),
                    grid.rootIndex(), rootCapacity, reservations, bendPenalty);
            Network network = tree == null ? null : compress(tree, grid, ports, root, checks);
            LOGGER.info("Corridor metric tree target={} bend_penalty={} assembled={}",
                    root.getTargetId(), bendPenalty, network != null);
            if (network != null) results.add(network);
        }
        results.addAll(flexiblePortNetworks(terminals, terminalAnchors, grid, root,
                rootCapacity, orientation, spurs, checks, false));
        results.addAll(flexiblePortNetworks(terminals, terminalAnchors, grid, root,
                rootCapacity, orientation, spurs, checks, true));
        LOGGER.info("Corridor terminal paths target={} attempts={} axial_paths={}",
                root.getTargetId(), spurs.attempts(), spurs.axialPaths());
        return results;
    }

    /**
     * Сторона ввода выбирается одновременно с общим деревом. Виртуальный потребитель имеет
     * степень один; несколько допустимых вводов — альтернативы, а не транзит через здание.
     * Поисковый вес каждого ввода равен длине его проверенной полилинии, не расстоянию по прямой.
     */
    private List<Network> flexiblePortNetworks(List<Terminal> terminals, List<List<Coordinate>> anchors,
            OrthogonalCorridorGrid grid, RouteNode root, int rootCapacity,
            double orientation,
            CorridorTerminalRouter spurs, PreparedCorridor checks, boolean includeNeighborhood) {
        List<Coordinate> points = new ArrayList<>(grid.points());
        List<int[]> links = new ArrayList<>(grid.links());
        List<Double> lengths = grid.links().stream().map(link -> points.get(link[0]).distance(points.get(link[1])))
                .collect(Collectors.toCollection(ArrayList::new));
        Map<Integer, Integer> leafReservations = new HashMap<>();
        Map<Integer, Map<Integer, Port>> alternatives = new HashMap<>();
        Set<Integer> neighborhoodPorts = new LinkedHashSet<>();
        for (List<Coordinate> exits : anchors) {
            for (Coordinate anchor : exits) neighborhoodPorts.addAll(grid.portsNear(anchor, 1));
        }
        for (int i = 0; i < terminals.size(); i++) {
            ensureActive();
            Terminal terminal = terminals.get(i);
            int leaf = points.size();
            points.add(new Coordinate(terminal.point));
            leafReservations.put(leaf, 3); // Из четырёх мест одно остаётся реальному вводу.
            Set<Integer> candidates = new LinkedHashSet<>(grid.portsNear(terminal.point, 2));
            for (Coordinate anchor : anchors.get(i)) candidates.addAll(grid.portsNear(anchor, 2));
            double nearest = candidates.stream().mapToDouble(port -> grid.points().get(port).distance(terminal.point))
                    .min().orElse(0);
            // Общая камера соседних вводов может находиться дальше индивидуального ближайшего
            // порта. Даём дереву ограниченный выбор таких мест, сохраняя все исходные варианты.
            neighborhoodPorts.stream().filter(port -> includeNeighborhood && !candidates.contains(port))
                    .filter(port -> grid.points().get(port).distance(terminal.point) <= nearest + 30)
                    .sorted(Comparator.comparingDouble((Integer port) -> grid.points().get(port).distance(terminal.point))
                            .thenComparingInt(port -> port))
                    .limit(4).forEach(candidates::add);
            Map<Integer, Port> options = new LinkedHashMap<>();
            for (int port : candidates) {
                RoutePath path = spurs.route(terminal.id, terminal.point, grid.points().get(port), diameter(terminal.flow));
                if (path == null || path.lengthM() <= 0.01) continue;
                options.put(port, new Port(terminal, port, path.reversed()));
                links.add(new int[] {leaf, port});
                // Координаты графа ещё не округлены до миллиметра, а маршрут уже округлён.
                lengths.add(Math.max(path.lengthM(), points.get(leaf).distance(points.get(port))));
            }
            if (options.isEmpty()) return List.of();
            alternatives.put(leaf, options);
        }
        List<List<int[]>> trees = new ArrayList<>();
        CorridorTreeBuilder builder = new CorridorTreeBuilder();
        Map<Integer, Map<Integer, RoutePath>> paths = new LinkedHashMap<>();
        alternatives.forEach((leaf, choices) -> {
            Map<Integer, RoutePath> leafPaths = new LinkedHashMap<>();
            choices.forEach((port, choice) -> leafPaths.put(port, choice.path));
            paths.put(leaf, leafPaths);
        });
        for (boolean farthest : new boolean[] {false, true}) {
            for (double junctionPenalty : new double[] {0, 25}) {
                trees.add(CorridorPortSearch.solve(points, links, lengths, grid.points().size(), paths,
                        (allowed, weights) -> builder.buildWeighted(points, allowed, weights, grid.rootIndex(), rootCapacity,
                                leafReservations, farthest, junctionPenalty, 3)));
            }
        }
        // Разные первые ветви дают разные общие стволы. Берём геометрические экстремумы
        // вдоль осей квартала, а не IDs или координаты конкретного конкурсного примера.
        Set<Integer> seeds = new LinkedHashSet<>();
        for (int direction = 0; direction < 4; direction++) {
            double angle = orientation + direction * Math.PI / 2;
            double nx = Math.cos(angle), ny = Math.sin(angle);
            int seed = leafReservations.keySet().stream().max(Comparator
                    .comparingDouble((Integer leaf) -> points.get(leaf).x * nx + points.get(leaf).y * ny)
                    .thenComparingInt(leaf -> leaf)).orElseThrow();
            seeds.add(seed);
        }
        for (int seed : seeds) {
            trees.add(CorridorPortSearch.solve(points, links, lengths, grid.points().size(), paths,
                    (allowed, weights) -> builder.buildWeightedFrom(points, allowed, weights, grid.rootIndex(), rootCapacity,
                            leafReservations, seed, 25, 3)));
        }
        for (double bendPenalty : new double[] {3, 15}) {
            trees.add(CorridorPortSearch.solve(points, links, lengths, grid.points().size(), paths,
                    (allowed, weights) -> builder.buildMetricClosureWeighted(points, allowed, weights, grid.rootIndex(), rootCapacity,
                            leafReservations, bendPenalty)));
        }
        List<Network> results = new ArrayList<>();
        for (List<int[]> tree : trees) {
            if (tree == null) continue;
            Map<Integer, Port> chosen = new LinkedHashMap<>();
            List<int[]> corridor = new ArrayList<>();
            boolean valid = true;
            for (int[] link : tree) {
                int leaf = Math.max(link[0], link[1]);
                if (leaf < grid.points().size()) { corridor.add(link); continue; }
                int port = Math.min(link[0], link[1]);
                if (chosen.put(leaf, alternatives.get(leaf).get(port)) != null) { valid = false; break; }
            }
            if (!valid || chosen.size() != terminals.size() || chosen.containsValue(null)) continue;
            List<Port> ports = chosen.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue).collect(Collectors.toList());
            Network network = compress(corridor, grid, ports, root, checks);
            if (network != null) results.add(network);
        }
        LOGGER.info("Corridor flexible ports target={} options={} trees={}", root.getTargetId(),
                alternatives.values().stream().mapToInt(Map::size).sum(), results.size());
        return results;
    }

    private Coordinate outsideBuffer(OfficialRouteGeometryRules.NormalEgress egress, double clearance) {
        Coordinate start = egress.start(), exit = egress.exit();
        double length = start.distance(exit);
        if (length < 0.01) return exit;
        double offset = clearance + 0.5;
        return new Coordinate(exit.x + (exit.x - start.x) / length * offset,
                exit.y + (exit.y - start.y) / length * offset);
    }

    private Network compress(List<int[]> tree, OrthogonalCorridorGrid grid, List<Port> ports,
            RouteNode root, PreparedCorridor checks) {
        Map<Integer, List<Piece>> incident = new HashMap<>();
        for (int[] link : tree) {
            RoutePath path = checks.path(List.of(grid.points().get(link[0]), grid.points().get(link[1])));
            if (path == null) return null;
            add(incident, new Piece(link[0], link[1], path));
        }
        Map<Integer, Terminal> leaves = new HashMap<>();
        for (int i = 0; i < ports.size(); i++) {
            Port port = ports.get(i);
            int leaf = grid.points().size() + i;
            leaves.put(leaf, port.terminal);
            add(incident, new Piece(port.index, leaf, port.path));
        }
        Map<Integer, Integer> parent = new HashMap<>();
        List<Integer> order = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        parent.put(grid.rootIndex(), -1);
        queue.add(grid.rootIndex());
        while (!queue.isEmpty()) {
            ensureActive();
            int at = queue.remove();
            order.add(at);
            for (Piece piece : incident.getOrDefault(at, List.of())) {
                int next = piece.other(at);
                if (next == parent.get(at)) continue;
                if (parent.containsKey(next)) return null;
                parent.put(next, at); queue.add(next);
            }
        }
        if (!parent.keySet().containsAll(leaves.keySet())) return null;
        Map<Integer, BigDecimal> flows = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            int at = order.get(i);
            BigDecimal flow = flows.getOrDefault(at, BigDecimal.ZERO)
                    .add(leaves.containsKey(at) ? leaves.get(at).flow : BigDecimal.ZERO);
            flows.put(at, flow);
            if (parent.get(at) >= 0) flows.merge(parent.get(at), flow, BigDecimal::add);
        }
        Map<Integer, RouteNode> nodes = new LinkedHashMap<>();
        nodes.put(grid.rootIndex(), root);
        for (int at : order) {
            if (at == grid.rootIndex()) continue;
            Terminal terminal = leaves.get(at);
            if (terminal != null) {
                nodes.put(at, new RouteNode("demand:" + terminal.id, "demand_connection",
                        new RouteCoordinate(terminal.point.x, terminal.point.y), false, false, 0, null));
            } else if (incident.get(at).size() >= 3) {
                Coordinate point = grid.points().get(at);
                nodes.put(at, new RouteNode("corridor:" + root.getId() + ":" + at, "new_branch_chamber",
                        new RouteCoordinate(point.x, point.y), true, false, 0, null));
            }
        }
        List<RouteEdge> edges = new ArrayList<>();
        for (int from : nodes.keySet()) {
            for (Piece first : incident.getOrDefault(from, List.of())) {
                int next = first.other(from);
                if (parent.get(from) == next) continue;
                int previous = from;
                Piece current = first;
                List<Coordinate> points = new ArrayList<>();
                List<RouteSection> sections = new ArrayList<>();
                double length = 0;
                while (true) {
                    RoutePath path = current.from == previous ? current.path : current.path.reversed();
                    append(points, path.coordinates());
                    sections.addAll(path.sections()); length += path.lengthM();
                    if (nodes.containsKey(next)) break;
                    List<Piece> choices = incident.get(next);
                    if (choices.size() != 2) return null;
                    current = choices.get(0).other(next) == previous ? choices.get(1) : choices.get(0);
                    previous = next; next = current.other(next);
                }
                RouteNode fromNode = nodes.get(from), toNode = nodes.get(next);
                BigDecimal flow = flows.get(next);
                List<Coordinate> simplified = straightPointsRemoved(points);
                if (!new GeometryFactory().createLineString(simplified.toArray(new Coordinate[0])).isSimple()) return null;
                edges.add(new RouteEdge("corridor:" + fromNode.getId() + ":" + toNode.getId(),
                        fromNode.getId(), toNode.getId(), length,
                        simplified.stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()),
                        sections, flow, diameter(flow)));
            }
        }
        List<RouteConnection> connections = ports.stream().map(port -> new RouteConnection(
                port.terminal.id, port.terminal.connectionPointId, port.terminal.flow, "connected", null))
                .collect(Collectors.toList());
        return new Network(new ArrayList<>(nodes.values()), edges, connections);
    }

    private void add(Map<Integer, List<Piece>> incident, Piece piece) {
        incident.computeIfAbsent(piece.from, ignored -> new ArrayList<>()).add(piece);
        incident.computeIfAbsent(piece.to, ignored -> new ArrayList<>()).add(piece);
    }

    private void append(List<Coordinate> target, List<Coordinate> source) {
        for (Coordinate point : source) {
            if (target.isEmpty() || target.get(target.size() - 1).distance(point) > 0.001) target.add(point);
        }
    }

    private List<Coordinate> straightPointsRemoved(List<Coordinate> points) {
        List<Coordinate> result = new ArrayList<>();
        for (Coordinate point : points) {
            while (result.size() > 1) {
                Coordinate a = result.get(result.size() - 2), b = result.get(result.size() - 1);
                if (a.distance(b) + b.distance(point) - a.distance(point) > 1e-7) break;
                result.remove(result.size() - 1);
            }
            result.add(point);
        }
        // У длинной хорды меньше допуск миллиметрового округления. Не превращаем допустимый
        // исходный поворот в недопустимый и не добавляем фиктивные вершины ради допуска.
        List<RouteCoordinate> rounded = result.stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList());
        return OfficialRouteDeflectionRules.validatePolyline("corridor-compression", rounded).getIssues().isEmpty()
                ? result : new ArrayList<>(points);
    }

    private Integer diameter(BigDecimal flow) {
        return flow.signum() <= 0 ? null : pipes.minimumForFlow(flow).map(entry -> entry.getDiameter()).orElse(null);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor generation cancelled");
    }

    static final class Terminal {
        private final String id, connectionPointId;
        private final Coordinate point;
        private final BigDecimal flow;
        Terminal(String id, String connectionPointId, Coordinate point, BigDecimal flow) {
            if (id == null || id.isBlank() || connectionPointId == null || connectionPointId.isBlank()
                    || point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)
                    || flow == null || flow.signum() <= 0) {
                throw new IllegalArgumentException("Corridor terminal requires IDs, finite XY and positive flow");
            }
            this.id = id; this.connectionPointId = connectionPointId;
            this.point = new Coordinate(point); this.flow = flow;
        }
    }

    private static final class Port {
        private final Terminal terminal;
        private final int index;
        private final RoutePath path;
        private Port(Terminal terminal, int index, RoutePath path) {
            this.terminal = terminal; this.index = index; this.path = path;
        }
    }

    private static final class Piece {
        private final int from, to;
        private final RoutePath path;
        private Piece(int from, int to, RoutePath path) { this.from = from; this.to = to; this.path = path; }
        private int other(int at) { return at == from ? to : from; }
    }

    static final class Network {
        private final List<RouteNode> nodes;
        private final List<RouteEdge> edges;
        private final List<RouteConnection> connections;
        private Network(List<RouteNode> nodes, List<RouteEdge> edges, List<RouteConnection> connections) {
            this.nodes = List.copyOf(nodes); this.edges = List.copyOf(edges); this.connections = List.copyOf(connections);
        }
        List<RouteNode> nodes() { return nodes; }
        List<RouteEdge> edges() { return edges; }
        List<RouteConnection> connections() { return connections; }
    }
}
