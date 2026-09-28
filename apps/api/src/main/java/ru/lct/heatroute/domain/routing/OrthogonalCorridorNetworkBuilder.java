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
import java.util.function.BiPredicate;
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

    /**
     * One deterministic control frame for deadline-bound catalog seeding.  The full planner keeps
     * both common- and individual-diameter anchor frames; N03 can request the second frame later
     * through additive expansion instead of paying for both before its first exact solve.
     */
    List<Network> buildControl(List<Terminal> input, RouteNode root, int rootCapacity,
            List<Geometry> footprints, OfficialRoutingEnvironment environment,
            SharedSpineNetworkBuilder.TerminalRouter terminalRouter) {
        return buildWithAnchors(input, root, rootCapacity, footprints, environment,
                terminalRouter, null, false);
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
                        diameter, terminal.point, target, RoutePlannerTuning.stable().getEngineeringEgressExtraM(), RouteTraversal.REVERSED);
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
        // Новые anchors могут вытеснить старые координаты при прореживании осей сетки.
        // Контрольные сети строятся на неизменной сетке, расширение не заменяет их.
        List<Network> result = new ArrayList<>(buildWithAnchors(input, root, rootCapacity,
                footprints, environment, terminalRouter, suppliedAngle, false));
        result.addAll(buildWithAnchors(input, root, rootCapacity,
                footprints, environment, terminalRouter, suppliedAngle, true));
        return result;
    }

    /** Две ограниченные кандидатные сетки используют одни и те же проверки полного ДУ ствола. */
    private List<Network> buildWithAnchors(List<Terminal> input, RouteNode root, int rootCapacity,
            List<Geometry> footprints, OfficialRoutingEnvironment environment,
            SharedSpineNetworkBuilder.TerminalRouter terminalRouter, Double suppliedAngle,
            boolean includeIndividualAnchors) {
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
        List<Coordinate> existingRootRays = environment.existingDirections(root);
        double orientation = suppliedAngle != null ? suppliedAngle
                : existingRootRays.isEmpty() ? CorridorOrientation.angle(footprints,
                        terminals.stream().map(terminal -> terminal.point).collect(Collectors.toList()), origin)
                : Math.atan2(existingRootRays.get(0).y, existingRootRays.get(0).x);
        Envelope bounds = new Envelope(origin);
        boolean individualAnchorAdded = false;
        for (Terminal terminal : terminals) {
            List<Coordinate> exits = new ArrayList<>();
            Integer individualDiameter = diameter(terminal.flow);
            if (individualDiameter == null) return List.of();
            // Общий ДУ может заблокировать ближайшую стену, допустимую для ввода меньшего ДУ.
            // Сохраняем общие anchors и дополняем индивидуальными; проверки ствола остаются
            // по общему ДУ. Не более восьми геометрических anchors на потребителя.
            List<Integer> anchorDiameters = !includeIndividualAnchors || diameter.equals(individualDiameter)
                    ? List.of(diameter) : List.of(diameter, individualDiameter);
            for (int anchorDiameter : anchorDiameters) {
                for (int direction = 0; direction < 4; direction++) {
                    double angle = orientation + direction * Math.PI / 2;
                    Coordinate target = new Coordinate(terminal.point.x + 200 * Math.cos(angle),
                            terminal.point.y + 200 * Math.sin(angle));
                    Coordinate anchor = environment.normalEgressTowards(anchorDiameter, terminal.point, target,
                                    RoutePlannerTuning.stable().getEngineeringEgressExtraM(), RouteTraversal.REVERSED)
                            .map(OfficialRouteGeometryRules.NormalEgress::exit).orElse(terminal.point);
                    if (exits.stream().noneMatch(existing -> existing.distance(anchor) < 0.01)) {
                        exits.add(anchor);
                        individualAnchorAdded |= anchorDiameter != diameter;
                    }
                }
            }
            terminalAnchors.add(exits);
            anchors.addAll(exits);
            exits.forEach(bounds::expandToInclude);
        }
        // Если геометрия совпала, второй граф и деревья не нужны; готовые трассы не кешируются.
        if (includeIndividualAnchors && !individualAnchorAdded) return List.of();
        bounds.expandBy(Math.max(30, clearance * 2));
        PreparedCorridor checks = router.prepareCorridor(diameter, environment, bounds, origin, root.getTargetId());
        OrthogonalCorridorGrid grid = OrthogonalCorridorGrid.build(origin, anchors, footprints, clearance,
                checks::pointAllowed, checks::edgeAllowed, orientation);
        BiPredicate<Integer, Integer> directions = rootedDirections(grid, checks);
        BiPredicate<Integer, Integer> chamberArms = junctionArms(grid.points(), grid.points().size(), Map.of(), checks);
        CorridorTreeBuilder.TurnAdmission turns = gridTurns(grid, checks);
        CorridorTerminalRouter spurs = new CorridorTerminalRouter(router, environment, terminalRouter, orientation);
        LOGGER.info("Corridor graph target={} nodes={} links={} demands={} individual_anchors={}", root.getTargetId(),
                grid.points().size(), grid.links().size(), terminals.size(), includeIndividualAnchors);
        Map<Integer, Integer> reservations = new HashMap<>();
        List<Port> ports = new ArrayList<>();
        for (int i = 0; i < terminals.size(); i++) {
            ensureActive();
            Terminal terminal = terminals.get(i);
            Port selected = null;
            for (int index : grid.portsNear(terminal.point, 12)) {
                if (reservations.getOrDefault(index, 0) >= 2) continue;
                // A standalone terminal may itself be a grid vertex; it cannot form a distinct chamber spur.
                if (terminal.point.distance(grid.points().get(index)) <= 0.01) continue;
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
        List<Network> jointResults = new ArrayList<>();
        List<Double> gridLengths = grid.links().stream()
                .map(link -> grid.points().get(link[0]).distance(grid.points().get(link[1])))
                .collect(Collectors.toList());
        for (GraphEdges graph : graphViews(grid.links(), gridLengths, grid, checks)) {
            for (boolean farthestFirst : ports.size() == terminals.size() ? new boolean[] {true, false} : new boolean[0]) {
                // Метры — поисковый штраф создания камеры, не подмена официальной сметы.
                for (double junctionPenalty : new double[] {0, 25}) {
                    List<int[]> tree = new CorridorTreeBuilder(chamberArms, turns).build(grid.points(), graph.links, grid.rootIndex(),
                            rootCapacity, reservations, farthestFirst, junctionPenalty, 3.0, directions);
                    if (tree == null) {
                        LOGGER.info("Corridor tree unavailable target={} farthest={} junction_penalty={}",
                                root.getTargetId(), farthestFirst, junctionPenalty);
                        continue;
                    }
                    Network network = compress(tree, grid, ports, root, checks, environment);
                    LOGGER.info("Corridor tree target={} farthest={} junction_penalty={} grid_edges={} assembled={}",
                            root.getTargetId(), farthestFirst, junctionPenalty, tree.size(), network != null);
                    if (network != null) results.add(network);
                    addJointCandidate(jointResults, tree, grid, ports, root, checks, spurs, environment);
                }
            }
            for (double bendPenalty : ports.size() == terminals.size() ? new double[] {0, 3, 15} : new double[0]) {
                List<int[]> tree = new CorridorTreeBuilder(chamberArms, turns).buildMetricClosure(grid.points(), graph.links,
                        grid.rootIndex(), rootCapacity, reservations, bendPenalty);
                Network network = tree == null ? null : compress(tree, grid, ports, root, checks, environment);
                LOGGER.info("Corridor metric tree target={} bend_penalty={} assembled={}",
                        root.getTargetId(), bendPenalty, network != null);
                if (network != null) results.add(network);
                if (tree != null) addJointCandidate(jointResults, tree, grid, ports, root, checks, spurs, environment);
            }
        }
        results.addAll(flexiblePortNetworks(terminals, terminalAnchors, grid, root,
                rootCapacity, orientation, spurs, checks, false, jointResults, environment, directions));
        results.addAll(flexiblePortNetworks(terminals, terminalAnchors, grid, root,
                rootCapacity, orientation, spurs, checks, true, jointResults, environment, directions));
        // Контрольные полные сети сохраняют прежний порядок и не вытесняются ремонтами.
        results.addAll(jointResults);
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
            CorridorTerminalRouter spurs, PreparedCorridor checks, boolean includeNeighborhood,
            List<Network> jointResults, OfficialRoutingEnvironment environment,
            BiPredicate<Integer, Integer> directions) {
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
                if (terminal.point.distance(grid.points().get(port)) <= 0.01) continue;
                CorridorTerminalRouter.Choice choice = spurs.routeChoice(
                        terminal.id, terminal.point, grid.points().get(port), diameter(terminal.flow));
                RoutePath path = choice.path();
                if (path == null || path.lengthM() <= 0.01) continue;
                options.put(port, new Port(terminal, port, path.reversed(), choice.redirectedControl()));
                links.add(new int[] {leaf, port});
                // Координаты графа ещё не округлены до миллиметра, а маршрут уже округлён.
                lengths.add(Math.max(path.lengthM(), points.get(leaf).distance(points.get(port))));
            }
            // Ближайший геометрически порт может лежать позади обязательного ввода.
            // Добавляем до четырёх ближайших достижимых портов; исходные варианты сохраняются.
            int feasiblePorts = 0;
            for (Coordinate anchor : anchors.get(i)) {
                for (int port : grid.portsNear(anchor, 48)) {
                    ensureActive();
                    if (feasiblePorts >= 4) break;
                    if (options.containsKey(port)) continue;
                    if (terminal.point.distance(grid.points().get(port)) <= 0.01) continue;
                    RoutePath path = spurs.localAlternatives(terminal.id, terminal.point,
                            grid.points().get(port), diameter(terminal.flow)).stream()
                            .min(Comparator.comparingDouble(RoutePath::lengthM)).orElse(null);
                    if (path == null || path.lengthM() <= 0.01) continue;
                    options.put(port, new Port(terminal, port, path.reversed()));
                    links.add(new int[] {leaf, port});
                    lengths.add(Math.max(path.lengthM(), points.get(leaf).distance(points.get(port))));
                    feasiblePorts++;
                }
            }
            if (options.isEmpty()) return List.of();
            alternatives.put(leaf, options);
        }
        List<Network> results = new ArrayList<>();
        for (GraphEdges graph : graphViews(links, lengths, grid, checks)) {
            List<CorridorPortSearch.Selection> trees = new ArrayList<>();
            CorridorTreeBuilder builder = new CorridorTreeBuilder(
                    junctionArms(points, grid.points().size(), alternatives, checks), gridTurns(grid, checks));
            Map<Integer, Map<Integer, RoutePath>> paths = new LinkedHashMap<>();
            alternatives.forEach((leaf, choices) -> {
                Map<Integer, RoutePath> leafPaths = new LinkedHashMap<>();
                choices.forEach((port, choice) -> leafPaths.put(port, choice.path));
                paths.put(leaf, leafPaths);
            });
            for (boolean farthest : new boolean[] {false, true}) {
                for (double junctionPenalty : new double[] {0, 25}) {
                    trees.addAll(CorridorPortSearch.solveWithPaths(points, graph.links, graph.lengths, grid.points().size(), paths,
                            (leaf, port) -> jointPaths(alternatives.get(leaf).get(port), grid, spurs),
                            (allowed, weights) -> builder.buildWeighted(points, allowed, weights, grid.rootIndex(), rootCapacity,
                                    leafReservations, farthest, junctionPenalty, 3, directions)));
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
                trees.addAll(CorridorPortSearch.solveWithPaths(points, graph.links, graph.lengths, grid.points().size(), paths,
                        (leaf, port) -> jointPaths(alternatives.get(leaf).get(port), grid, spurs),
                        (allowed, weights) -> builder.buildWeightedFrom(points, allowed, weights, grid.rootIndex(), rootCapacity,
                                leafReservations, seed, 25, 3, directions)));
            }
            for (double bendPenalty : new double[] {3, 15}) {
                trees.addAll(CorridorPortSearch.solveWithPaths(points, graph.links, graph.lengths, grid.points().size(), paths,
                        (leaf, port) -> jointPaths(alternatives.get(leaf).get(port), grid, spurs),
                        (allowed, weights) -> builder.buildMetricClosureWeighted(points, allowed, weights, grid.rootIndex(), rootCapacity,
                                leafReservations, bendPenalty)));
            }
            // Замена недопустимого короткого ввода может увести жадный поиск в другую топологию.
            // Сохраняем основной поиск и добавляем до четырёх seed-попыток без таких замен.
            // Потребителей не удаляем: при пустом наборе хотя бы одного ввода этот проход не нужен.
            Map<Integer, Map<Integer, RoutePath>> stablePaths = new LinkedHashMap<>();
            alternatives.forEach((leaf, choices) -> {
                Map<Integer, RoutePath> stable = new LinkedHashMap<>();
                choices.forEach((port, choice) -> { if (!choice.redirectedControl) stable.put(port, choice.path); });
                stablePaths.put(leaf, stable);
            });
            int stableSeedCount = 0;
            if (!stablePaths.equals(paths) && stablePaths.values().stream().noneMatch(Map::isEmpty)) {
                List<int[]> stableLinks = new ArrayList<>();
                List<Double> stableLengths = new ArrayList<>();
                for (int i = 0; i < graph.links.size(); i++) {
                    ensureActive();
                    int[] link = graph.links.get(i);
                    int leaf = Math.max(link[0], link[1]), port = Math.min(link[0], link[1]);
                    if (leaf < grid.points().size() || stablePaths.get(leaf).containsKey(port)) {
                        stableLinks.add(link); stableLengths.add(graph.lengths.get(i));
                    }
                }
                for (int seed : seeds) {
                    stableSeedCount++;
                    trees.addAll(CorridorPortSearch.solveWithPaths(points, stableLinks, stableLengths,
                            grid.points().size(), stablePaths,
                            (leaf, port) -> jointPaths(alternatives.get(leaf).get(port), grid, spurs),
                            (allowed, weights) -> builder.buildWeightedFrom(points, allowed, weights,
                                    grid.rootIndex(), rootCapacity, leafReservations, seed, 25, 3, directions)));
                }
            }
            for (CorridorPortSearch.Selection selection : trees) {
                Map<Integer, Port> chosen = new LinkedHashMap<>();
                List<int[]> corridor = new ArrayList<>();
                boolean valid = true;
                boolean changed = false;
                for (int[] link : selection.tree()) {
                    int leaf = Math.max(link[0], link[1]);
                    if (leaf < grid.points().size()) { corridor.add(link); continue; }
                    int port = Math.min(link[0], link[1]);
                    Port control = alternatives.get(leaf).get(port);
                    RoutePath selected = selection.paths().get(leaf);
                    changed |= selected != control.path;
                    if (chosen.put(leaf, new Port(control.terminal, port, selected)) != null) { valid = false; break; }
                }
                if (!valid || chosen.size() != terminals.size() || chosen.containsValue(null)) continue;
                List<Port> ports = chosen.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue).collect(Collectors.toList());
                Network network = compress(corridor, grid, ports, root, checks, environment);
                if (network != null) (changed ? jointResults : results).add(network);
            }
            LOGGER.info("Corridor flexible ports target={} options={} trees={} stable_options={} stable_seeds={}",
                    root.getTargetId(), alternatives.values().stream().mapToInt(Map::size).sum(), results.size(),
                    stablePaths.values().stream().mapToInt(Map::size).sum(), stableSeedCount);
        }
        return results;
    }

    /** Графовый поворот сохраняет обе защитные части дорожного перехода. */
    private CorridorTreeBuilder.TurnAdmission gridTurns(OrthogonalCorridorGrid grid, PreparedCorridor checks) {
        List<Coordinate> points = grid.points().stream()
                .map(point -> new RouteCoordinate(point.x, point.y).toCoordinate()).collect(Collectors.toList());
        int count = points.size();
        Map<Long, Boolean> memo = new HashMap<>();
        return (previous, at, next) -> {
            // Виртуальный ввод проверяется по полной полилинии при совместном выборе вводов.
            if (previous >= count || at >= count || next >= count) return true;
            long key = ((long) previous * count + at) * count + next;
            return memo.computeIfAbsent(key, ignored -> checks.turnAllowed(
                    points.get(previous), points.get(at), points.get(next)));
        };
    }

    /** Проверяет лучи будущих камер по фактическому начальному направлению ввода. */
    private BiPredicate<Integer, Integer> junctionArms(List<Coordinate> points, int realPoints,
            Map<Integer, Map<Integer, Port>> alternatives, PreparedCorridor checks) {
        Map<Long, Boolean> memo = new HashMap<>();
        return (from, to) -> {
            if (from >= realPoints) return true; // Виртуальный потребитель не является физической камерой.
            return memo.computeIfAbsent(arcKey(from, to), ignored -> {
                Coordinate towards = points.get(to);
                if (to >= realPoints) {
                    List<Coordinate> path = alternatives.get(to).get(from).path.coordinates();
                    towards = path.stream().filter(p -> p.distance(points.get(from)) > 0.001)
                            .findFirst().orElse(points.get(from));
                }
                return checks.chamberRayAllowed(points.get(from), towards);
            });
        };
    }

    /** Фиксированные порты получают тот же совместный выбор путей, что и гибкий поиск. */
    private void addJointCandidate(List<Network> results, List<int[]> tree, OrthogonalCorridorGrid grid,
            List<Port> ports, RouteNode root, PreparedCorridor checks, CorridorTerminalRouter spurs,
            OfficialRoutingEnvironment environment) {
        List<int[]> withLeaves = new ArrayList<>(tree);
        Map<Integer, Map<Integer, RoutePath>> controls = new LinkedHashMap<>();
        for (int i = 0; i < ports.size(); i++) {
            int leaf = grid.points().size() + i;
            Port port = ports.get(i);
            withLeaves.add(new int[] {port.index, leaf});
            controls.put(leaf, Map.of(port.index, port.path));
        }
        CorridorPortSearch.Selection selected = CorridorPortSearch.assignPaths(grid.points(), withLeaves,
                grid.points().size(), controls,
                (leaf, port) -> jointPaths(ports.get(leaf - grid.points().size()), grid, spurs));
        if (selected == null) return;
        List<Port> joint = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < ports.size(); i++) {
            Port control = ports.get(i);
            RoutePath path = selected.paths().get(grid.points().size() + i);
            changed |= path != control.path;
            joint.add(new Port(control.terminal, control.index, path));
        }
        if (!changed) return;
        Network network = compress(tree, grid, joint, root, checks, environment);
        if (network != null) results.add(network);
    }

    /** Локальный кэш принадлежит одному построению: контроль плюс до семи иных геометрий. */
    private List<RoutePath> jointPaths(Port port, OrthogonalCorridorGrid grid, CorridorTerminalRouter spurs) {
        if (port.jointPaths == null) {
            List<RoutePath> paths = new ArrayList<>(List.of(port.path));
            for (RoutePath candidate : spurs.localAlternatives(port.terminal.id, port.terminal.point,
                    grid.points().get(port.index), diameter(port.terminal.flow))) {
                RoutePath reversed = candidate.reversed();
                if (paths.stream().noneMatch(old -> old.coordinates().equals(reversed.coordinates()))) paths.add(reversed);
                if (paths.size() == 8) break;
            }
            port.jointPaths = List.copyOf(paths);
        }
        return port.jointPaths;
    }

    /**
     * Новые односторонние дуги не должны вытеснять прежние допустимые деревья. Сохраняем
     * контрольный подграф только при отличии, не повторяя генерацию terminal-путей.
     * Любое дерево обоих подграфов проходит одинаковый полный rooted-допуск.
     */
    private List<GraphEdges> graphViews(List<int[]> links, List<Double> lengths,
            OrthogonalCorridorGrid grid, PreparedCorridor checks) {
        List<int[]> control = new ArrayList<>();
        List<Double> controlLengths = new ArrayList<>();
        for (int i = 0; i < links.size(); i++) {
            ensureActive();
            int[] link = links.get(i);
            if (link[0] >= grid.points().size() || link[1] >= grid.points().size()
                    || checks.edgeAllowed(grid.points().get(link[0]), grid.points().get(link[1]))) {
                control.add(link); controlLengths.add(lengths.get(i));
            }
        }
        GraphEdges full = new GraphEdges(links, lengths);
        return control.size() == links.size() ? List.of(full)
                : List.of(full, new GraphEdges(control, controlLengths));
    }

    private static final class GraphEdges {
        private final List<int[]> links;
        private final List<Double> lengths;
        private GraphEdges(List<int[]> links, List<Double> lengths) {
            this.links = List.copyOf(links); this.lengths = List.copyOf(lengths);
        }
    }

    /**
     * Направленные дуги принадлежат одному построению сетки. Дорогая геометрическая проверка
     * выполняется один раз, а не в каждом поиске терминала; готовых маршрутов здесь нет.
     * Виртуальный потребитель разрешён только как конец уже проверенного terminal spur.
     */
    private BiPredicate<Integer, Integer> rootedDirections(OrthogonalCorridorGrid grid, PreparedCorridor checks) {
        List<Coordinate> points = grid.points().stream()
                .map(point -> new RouteCoordinate(point.x, point.y).toCoordinate()).collect(Collectors.toList());
        Set<Long> allowed = new HashSet<>();
        for (int[] link : grid.links()) {
            ensureActive();
            int from = link[0], to = link[1];
            if (checks.edgeAllowed(points.get(from), points.get(to))) allowed.add(arcKey(from, to));
            if (checks.edgeAllowed(points.get(to), points.get(from))) allowed.add(arcKey(to, from));
        }
        int realPointCount = points.size();
        return (from, to) -> from < realPointCount
                && (to >= realPointCount || allowed.contains(arcKey(from, to)));
    }

    private static long arcKey(int from, int to) { return ((long) from << 32) | (to & 0xffffffffL); }

    private Network compress(List<int[]> tree, OrthogonalCorridorGrid grid, List<Port> ports,
            RouteNode root, PreparedCorridor checks, OfficialRoutingEnvironment environment) {
        Map<Integer, List<Piece>> incident = new HashMap<>();
        for (int[] link : tree) {
            // Дерево ещё не ориентировано. Техническое звено может содержать только часть
            // пересечения дороги; полный допуск выполняется после сборки физической полилинии.
            List<Coordinate> points = List.of(grid.points().get(link[0]), grid.points().get(link[1]));
            add(incident, new Piece(link[0], link[1], points, false));
        }
        Map<Integer, Terminal> leaves = new HashMap<>();
        for (int i = 0; i < ports.size(); i++) {
            Port port = ports.get(i);
            int leaf = grid.points().size() + i;
            leaves.put(leaf, port.terminal);
            add(incident, new Piece(port.index, leaf, port.path.coordinates(), true));
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
        if (!parent.keySet().containsAll(leaves.keySet()) || parent.size() != incident.size()) return null;
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
                List<Coordinate> gridRun = new ArrayList<>();
                while (true) {
                    List<Coordinate> piecePoints = new ArrayList<>(current.coordinates);
                    if (current.from != previous) java.util.Collections.reverse(piecePoints);
                    if (current.terminal) {
                        // Spur уже проверен в направлении port→demand и остаётся неизменным.
                        if (current.from != previous) return null;
                        append(points, CorridorGridPolyline.rounded(gridRun));
                        gridRun.clear();
                        append(points, piecePoints);
                    } else {
                        for (int i = 1; i < piecePoints.size(); i++) {
                            if (!checks.edgeAllowed(new RouteCoordinate(piecePoints.get(i - 1).x, piecePoints.get(i - 1).y).toCoordinate(),
                                    new RouteCoordinate(piecePoints.get(i).x, piecePoints.get(i).y).toCoordinate())) return null;
                        }
                        append(gridRun, piecePoints);
                    }
                    if (nodes.containsKey(next)) break;
                    List<Piece> choices = incident.get(next);
                    if (choices.size() != 2) return null;
                    current = choices.get(0).other(next) == previous ? choices.get(1) : choices.get(0);
                    previous = next; next = current.other(next);
                }
                append(points, CorridorGridPolyline.rounded(gridRun));
                RouteNode fromNode = nodes.get(from), toNode = nodes.get(next);
                BigDecimal flow = flows.get(next);
                List<Coordinate> simplified = straightPointsRemoved(points);
                if (!new GeometryFactory().createLineString(simplified.toArray(new Coordinate[0])).isSimple()) return null;
                Integer finalDiameter = diameter(flow);
                if (finalDiameter == null) return null;
                boolean rootEdge = fromNode.isRoot();
                if (rootEdge) {
                    simplified = withRootChamberApproach(simplified, finalDiameter,
                            environment.existingDirections(fromNode));
                    if (simplified == null) return null;
                }
                Envelope bounds = new Envelope();
                simplified.forEach(bounds::expandToInclude);
                // Секции и целый special проверяем по полной линии и ДУ этого ребра: terminal spur
                // может выйти за сетку. Отступы звеньев/ввода проверены выше, итоговый ДУ — в finish.
                RoutePath complete = router.completeCheckedCorridorAssembly(finalDiameter, environment, bounds,
                        root.getCoordinate().toCoordinate(), root.getTargetId(), simplified);
                if (complete == null) return null;
                edges.add(new RouteEdge("corridor:" + fromNode.getId() + ":" + toNode.getId(),
                        fromNode.getId(), toNode.getId(), complete.lengthM(),
                        complete.coordinates().stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()),
                        complete.sections(), flow, finalDiameter));
            }
        }
        List<RouteConnection> connections = ports.stream().map(port -> new RouteConnection(
                port.terminal.id, port.terminal.connectionPointId, port.terminal.flow, "connected", null))
                .collect(Collectors.toList());
        if (!OfficialRouteDeflectionRules.validate(new ArrayList<>(nodes.values()), edges).isEmpty()) return null;
        return new Network(new ArrayList<>(nodes.values()), edges, connections);
    }

    /** Первый поворот корневого ребра находится после табличного минимума и занимает свободный луч. */
    private List<Coordinate> withRootChamberApproach(List<Coordinate> source, int diameter,
            List<Coordinate> existingRays) {
        if (source.size() < 2 || existingRays.isEmpty()) return source;
        Coordinate root = source.get(0), towards = source.get(1);
        double originalLength = root.distance(towards);
        if (originalLength <= 0) return null;
        Coordinate axis = new Coordinate(existingRays.get(0));
        double axisLength = Math.hypot(axis.x, axis.y);
        if (axisLength <= 0 || !Double.isFinite(axisLength)) return null;
        axis.x /= axisLength; axis.y /= axisLength;
        double sumX = axis.x, sumY = axis.y;
        for (int i = 1; i < existingRays.size(); i++) {
            Coordinate ray = existingRays.get(i);
            double length = Math.hypot(ray.x, ray.y);
            if (length <= 0 || !Double.isFinite(length)) return null;
            double x = ray.x / length, y = ray.y / length;
            if (x * axis.x + y * axis.y < 0) { x = -x; y = -y; }
            sumX += x; sumY += y;
        }
        double averageLength = Math.hypot(sumX, sumY);
        if (averageLength <= 0) return null;
        double orientation = Math.atan2(sumY / averageLength, sumX / averageLength);
        double minimum = ExpertChamberGeometryRules.minimumBendDistanceM(diameter) + 0.1;
        double originalX = (towards.x - root.x) / originalLength;
        double originalY = (towards.y - root.y) / originalLength;
        Coordinate best = null;
        double bestDot = -Double.MAX_VALUE;
        for (int direction = 0; direction < 4; direction++) {
            double angle = orientation + direction * Math.PI / 2;
            Coordinate approach = new RouteCoordinate(root.x + minimum * Math.cos(angle),
                    root.y + minimum * Math.sin(angle)).toCoordinate();
            double dx = approach.x - root.x, dy = approach.y - root.y;
            if (existingRays.stream().anyMatch(ray -> !ExpertChamberGeometryRules.compatibleRays(
                    dx, dy, ray.x, ray.y))) continue;
            double dot = dx * originalX + dy * originalY;
            if (dot > bestDot) { bestDot = dot; best = approach; }
        }
        if (best == null) return null;
        List<Coordinate> result = new ArrayList<>();
        append(result, List.of(root, best));
        append(result, source.subList(1, source.size()));
        return result;
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
                // Малое изменение длины не означает одинаковую геометрию:
                // срезание миллиметрового изгиба может создать пересечение с соседней веткой.
                if (!sameRoundedStraightLine(a, b, point)) break;
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

    private boolean sameRoundedStraightLine(Coordinate a, Coordinate b, Coordinate c) {
        RouteCoordinate first = new RouteCoordinate(a.x, a.y), middle = new RouteCoordinate(b.x, b.y);
        RouteCoordinate last = new RouteCoordinate(c.x, c.y);
        BigDecimal ax = middle.getXM().subtract(first.getXM()), ay = middle.getYM().subtract(first.getYM());
        BigDecimal bx = last.getXM().subtract(middle.getXM()), by = last.getYM().subtract(middle.getYM());
        return ax.multiply(by).compareTo(ay.multiply(bx)) == 0
                && ax.multiply(bx).add(ay.multiply(by)).signum() >= 0;
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
        private final boolean redirectedControl;
        private List<RoutePath> jointPaths;
        private Port(Terminal terminal, int index, RoutePath path) {
            this(terminal, index, path, false);
        }
        private Port(Terminal terminal, int index, RoutePath path, boolean redirectedControl) {
            this.terminal = terminal; this.index = index; this.path = path;
            this.redirectedControl = redirectedControl;
        }
    }

    private static final class Piece {
        private final int from, to;
        private final List<Coordinate> coordinates;
        private final boolean terminal;
        private Piece(int from, int to, List<Coordinate> coordinates, boolean terminal) {
            this.from = from; this.to = to; this.coordinates = List.copyOf(coordinates); this.terminal = terminal;
        }
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
