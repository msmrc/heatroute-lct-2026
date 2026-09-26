package ru.lct.heatroute.domain.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.function.BiPredicate;
import org.locationtech.jts.geom.Coordinate;

/**
 * Выбирает общее дерево на переданном коридорном графе, резервируя места для потребительских stub.
 * Предлагает жадное присоединение терминалов и отдельный кандидат через метрическое замыкание;
 * null означает, что данный кандидат не удалось собрать, а не доказательство отсутствия дерева.
 */
final class CorridorTreeBuilder {
    private static final BiPredicate<Integer, Integer> UNRESTRICTED = (from, to) -> true;
    private final BiPredicate<Integer, Integer> junctionArmAllowed;
    private final TurnAdmission turnAdmission;

    /** Допуск фактического перехода через промежуточный узел графа. */
    @FunctionalInterface
    interface TurnAdmission {
        boolean allowed(int previous, int at, int next);
    }

    CorridorTreeBuilder() { this(UNRESTRICTED); }

    /** Проверяет лучи камер при жадном присоединении; метрическое замыкание остаётся отдельным кандидатом. */
    CorridorTreeBuilder(BiPredicate<Integer, Integer> junctionArmAllowed) {
        this(junctionArmAllowed, (previous, at, next) -> true);
    }

    /** Проверяет оба луча поворота во время поиска пути, до выбора дерева. */
    CorridorTreeBuilder(BiPredicate<Integer, Integer> junctionArmAllowed, TurnAdmission turnAdmission) {
        require(junctionArmAllowed != null, "Junction arm admission is required");
        require(turnAdmission != null, "Turn admission is required");
        this.junctionArmAllowed = junctionArmAllowed;
        this.turnAdmission = turnAdmission;
    }

    private static final int MAX_NODES = 100_000;
    private static final int MAX_LINKS = 400_000;
    private static final int MAX_METRIC_TERMINALS = 64;
    private static final int NODE_CAPACITY = 4;
    // Допуск направления компенсирует округление повёрнутой сетки в метрической системе координат.
    private static final double STRAIGHT_SINE_TOLERANCE = 1e-8;
    private static final double WEIGHT_LENGTH_EPSILON_M = 1e-6;

    /**
     * Возвращает неориентированные пары исходных индексов, не меняя входы. farthestFirst выбирает
     * только первый терминал по расстоянию до корня; остальные выбираются многоточечным поиском.
     * Лимиты входа явные: до 100000 узлов и 400000 links; неверный ввод вызывает IllegalArgumentException.
     */
    List<int[]> build(List<Coordinate> points, List<int[]> links, int root, int rootCapacity,
            Map<Integer, Integer> terminalStubCounts, boolean farthestFirst,
            double newJunctionPenaltyM, double bendPenaltyM) {
        return build(points, links, root, rootCapacity, terminalStubCounts, farthestFirst,
                newJunctionPenaltyM, bendPenaltyM, UNRESTRICTED);
    }

    /** Допуск относится к физическому движению от уже построенного дерева к новому терминалу. */
    List<int[]> build(List<Coordinate> points, List<int[]> links, int root, int rootCapacity,
            Map<Integer, Integer> terminalStubCounts, boolean farthestFirst,
            double newJunctionPenaltyM, double bendPenaltyM, BiPredicate<Integer, Integer> directionAllowed) {
        ensureNotCancelled();
        require(rootCapacity >= 0, "Root capacity must be nonnegative");
        require(Double.isFinite(newJunctionPenaltyM) && newJunctionPenaltyM >= 0,
                "Junction penalty must be finite and nonnegative");
        require(Double.isFinite(bendPenaltyM) && bendPenaltyM >= 0,
                "Bend penalty must be finite and nonnegative");
        Graph graph = new Graph(points, links, null, root, directionAllowed, junctionArmAllowed, turnAdmission);
        return buildGraph(graph, root, rootCapacity, terminalStubCounts, farthestFirst,
                newJunctionPenaltyM, bendPenaltyM);
    }

    /** Учитывает фактическую длину криволинейных подключений; направление берётся по хорде. */
    List<int[]> buildWeighted(List<Coordinate> points, List<int[]> links, List<Double> lengths,
            int root, int rootCapacity, Map<Integer, Integer> terminalStubCounts, boolean farthestFirst,
            double newJunctionPenaltyM, double bendPenaltyM) {
        return buildWeighted(points, links, lengths, root, rootCapacity, terminalStubCounts, farthestFirst,
                newJunctionPenaltyM, bendPenaltyM, UNRESTRICTED);
    }

    /** Взвешенные terminal-подключения и звенья ствола могут иметь разные допустимые направления. */
    List<int[]> buildWeighted(List<Coordinate> points, List<int[]> links, List<Double> lengths,
            int root, int rootCapacity, Map<Integer, Integer> terminalStubCounts, boolean farthestFirst,
            double newJunctionPenaltyM, double bendPenaltyM, BiPredicate<Integer, Integer> directionAllowed) {
        ensureNotCancelled();
        require(lengths != null, "Weighted links require lengths");
        require(rootCapacity >= 0, "Root capacity must be nonnegative");
        require(Double.isFinite(newJunctionPenaltyM) && newJunctionPenaltyM >= 0,
                "Junction penalty must be finite and nonnegative");
        require(Double.isFinite(bendPenaltyM) && bendPenaltyM >= 0,
                "Bend penalty must be finite and nonnegative");
        return buildGraph(new Graph(points, links, lengths, root, directionAllowed, junctionArmAllowed, turnAdmission), root, rootCapacity,
                terminalStubCounts, farthestFirst, newJunctionPenaltyM, bendPenaltyM);
    }

    /**
     * Начинает взвешенное дерево с указанного терминала, затем использует обычный многоточечный поиск.
     * Первый терминал должен отличаться от корня и иметь положительный резерв stub, иначе ошибка ввода.
     */
    List<int[]> buildWeightedFrom(List<Coordinate> points, List<int[]> links, List<Double> lengths,
            int root, int rootCapacity, Map<Integer, Integer> terminalStubCounts, int firstTerminal,
            double newJunctionPenaltyM, double bendPenaltyM) {
        return buildWeightedFrom(points, links, lengths, root, rootCapacity, terminalStubCounts, firstTerminal,
                newJunctionPenaltyM, bendPenaltyM, UNRESTRICTED);
    }

    /** Заданный первый терминал не меняет физическое направление поиска root → demand. */
    List<int[]> buildWeightedFrom(List<Coordinate> points, List<int[]> links, List<Double> lengths,
            int root, int rootCapacity, Map<Integer, Integer> terminalStubCounts, int firstTerminal,
            double newJunctionPenaltyM, double bendPenaltyM, BiPredicate<Integer, Integer> directionAllowed) {
        ensureNotCancelled();
        require(lengths != null, "Weighted links require lengths");
        require(rootCapacity >= 0, "Root capacity must be nonnegative");
        require(Double.isFinite(newJunctionPenaltyM) && newJunctionPenaltyM >= 0,
                "Junction penalty must be finite and nonnegative");
        require(Double.isFinite(bendPenaltyM) && bendPenaltyM >= 0,
                "Bend penalty must be finite and nonnegative");
        Graph graph = new Graph(points, links, lengths, root, directionAllowed, junctionArmAllowed, turnAdmission);
        Tree tree = new Tree(graph, root, rootCapacity, terminalStubCounts);
        require(firstTerminal >= 0 && firstTerminal < graph.x.length && tree.isRemainingTerminal(firstTerminal),
                "First terminal must be a non-root node with a positive stub count");
        if (!tree.reservationsFit()) return null;
        return buildTree(graph, tree, firstTerminal, newJunctionPenaltyM, bendPenaltyM);
    }

    private List<int[]> buildGraph(Graph graph, int root, int rootCapacity,
            Map<Integer, Integer> terminalStubCounts, boolean farthestFirst,
            double newJunctionPenaltyM, double bendPenaltyM) {
        Tree tree = new Tree(graph, root, rootCapacity, terminalStubCounts);
        if (!tree.reservationsFit()) return null;
        int first = firstTerminal(graph, tree, farthestFirst);
        return buildTree(graph, tree, first, newJunctionPenaltyM, bendPenaltyM);
    }

    private List<int[]> buildTree(Graph graph, Tree tree, int first,
            double newJunctionPenaltyM, double bendPenaltyM) {
        while (tree.remaining > 0) {
            ensureNotCancelled();
            List<Integer> path = shortestAttachment(graph, tree, first, newJunctionPenaltyM, bendPenaltyM);
            if (path == null) return null;
            tree.attach(path);
            first = -1;
        }
        ensureNotCancelled();
        return tree.edges(graph);
    }

    /**
     * Строит MST терминального замыкания, объединяет его кратчайшие пути, удаляет циклы
     * и нетерминальные листья, затем проверяет степени с резервами stub. Более 64 обязательных
     * узлов вместе с корнем или недопустимый итог дают null; существующий жадный кандидат не меняется.
     */
    List<int[]> buildMetricClosure(List<Coordinate> points, List<int[]> links, int root, int rootCapacity,
            Map<Integer, Integer> terminalStubCounts, double bendPenaltyM) {
        ensureNotCancelled();
        require(rootCapacity >= 0, "Root capacity must be nonnegative");
        require(Double.isFinite(bendPenaltyM) && bendPenaltyM >= 0,
                "Bend penalty must be finite and nonnegative");
        Graph graph = new Graph(points, links, null, root, UNRESTRICTED, junctionArmAllowed, turnAdmission);
        Tree reservations = new Tree(graph, root, rootCapacity, terminalStubCounts);
        if (!reservations.reservationsFit() || reservations.remaining + 1 > MAX_METRIC_TERMINALS) return null;
        if (reservations.remaining == 0) return new ArrayList<>();
        return new MetricClosure(graph, reservations, bendPenaltyM).build();
    }

    /** Метрическое замыкание с фактическими длинами и запретом транзита через зарезервированные листья. */
    List<int[]> buildMetricClosureWeighted(List<Coordinate> points, List<int[]> links, List<Double> lengths,
            int root, int rootCapacity, Map<Integer, Integer> terminalStubCounts, double bendPenaltyM) {
        ensureNotCancelled();
        require(lengths != null, "Weighted links require lengths");
        require(rootCapacity >= 0, "Root capacity must be nonnegative");
        require(Double.isFinite(bendPenaltyM) && bendPenaltyM >= 0,
                "Bend penalty must be finite and nonnegative");
        Graph graph = new Graph(points, links, lengths, root, UNRESTRICTED, junctionArmAllowed, turnAdmission);
        Tree reservations = new Tree(graph, root, rootCapacity, terminalStubCounts);
        if (!reservations.reservationsFit() || reservations.remaining + 1 > MAX_METRIC_TERMINALS) return null;
        if (reservations.remaining == 0) return new ArrayList<>();
        return new MetricClosure(graph, reservations, bendPenaltyM, true).build();
    }

    private int firstTerminal(Graph graph, Tree tree, boolean farthestFirst) {
        int best = -1;
        double bestDistance = 0;
        for (int node : graph.geometryOrder) {
            ensureNotCancelled();
            if (!tree.isRemainingTerminal(node)) continue;
            double distance = Math.hypot(graph.x[node] - graph.x[tree.root], graph.y[node] - graph.y[tree.root]);
            if (best < 0 || (farthestFirst ? distance > bestDistance : distance < bestDistance)) {
                best = node;
                bestDistance = distance;
            }
        }
        return best;
    }

    private List<Integer> shortestAttachment(Graph graph, Tree tree, int target,
            double junctionPenalty, double bendPenalty) {
        Search search = new Search(graph);
        for (int source : graph.geometryOrder) {
            ensureNotCancelled();
            if (!tree.inTree[source] || !tree.hasRoom(source, 1)) continue;
            double sourcePenalty = source != tree.root && tree.degree[source] + tree.stubs[source] == 2
                    ? junctionPenalty : 0;
            for (int arc : graph.outgoing[source]) {
                if (!graph.directionAllowed[arc]) continue;
                int next = graph.to[arc];
                if (tree.inTree[next] || !tree.hasRoom(next, 1) || !tree.junctionAllowed(graph, source, arc, -1)) continue;
                if (tree.parent[source] >= 0 && !graph.turnAllowed(tree.parent[source], source, next)) continue;
                double turn = tree.parent[source] >= 0
                        && graph.isBend(tree.parent[source], source, next) ? bendPenalty : 0;
                search.offer(arc, sourcePenalty + turn + graph.length[arc], -1);
            }
        }
        while (!search.queue.isEmpty()) {
            ensureNotCancelled();
            Label current = search.queue.poll();
            if (search.settled[current.arc] || current.cost != search.cost[current.arc]) continue;
            search.settled[current.arc] = true;
            int node = graph.to[current.arc];
            if (tree.isRemainingTerminal(node) && (target < 0 || node == target)
                    && tree.junctionAllowed(graph, node, current.arc ^ 1, -1)) {
                return search.path(current.arc);
            }
            // Не подключённые терминалы тоже резервируют stub: через порт с тремя stub идти нельзя.
            if (!tree.hasRoom(node, 2)) continue;
            for (int nextArc : graph.outgoing[node]) {
                if (!graph.directionAllowed[nextArc]) continue;
                int next = graph.to[nextArc];
                if (nextArc == (current.arc ^ 1) || tree.inTree[next] || !tree.hasRoom(next, 1)
                        || !tree.junctionAllowed(graph, node, current.arc ^ 1, nextArc)) continue;
                if (!graph.turnAllowed(graph.from[current.arc], node, next)) continue;
                double turn = graph.isBend(graph.from[current.arc], node, next) ? bendPenalty : 0;
                search.offer(nextArc, current.cost + graph.length[nextArc] + turn, current.arc);
            }
        }
        return null;
    }

    private static void ensureNotCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Corridor tree calculation was cancelled");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    /** Два прохода не удерживают T наборов предшественников: между поисками остаются лишь расстояния. */
    private static final class MetricClosure {
        private final Graph graph;
        private final Tree reservations;
        private final double bendPenalty;
        private final boolean reserveTransitSlots;
        private final int[] terminals;
        private final int[] terminalIndex;

        private MetricClosure(Graph graph, Tree reservations, double bendPenalty) {
            this(graph, reservations, bendPenalty, false);
        }

        private MetricClosure(Graph graph, Tree reservations, double bendPenalty, boolean reserveTransitSlots) {
            this.graph = graph;
            this.reservations = reservations;
            this.bendPenalty = bendPenalty;
            this.reserveTransitSlots = reserveTransitSlots;
            terminals = new int[reservations.remaining + 1];
            terminalIndex = new int[graph.x.length];
            Arrays.fill(terminalIndex, -1);
            int count = 0;
            for (int node : graph.geometryOrder) {
                if (node == reservations.root || reservations.stubs[node] > 0) {
                    terminalIndex[node] = count;
                    terminals[count++] = node;
                }
            }
        }

        private List<int[]> build() {
            List<MetricPair> pairs = new ArrayList<>();
            for (int source = 0; source + 1 < terminals.length; source++) {
                boolean[] targets = new boolean[terminals.length];
                Arrays.fill(targets, source + 1, targets.length, true);
                int[] arrivals = new int[terminals.length];
                Search search = search(source, targets, arrivals);
                if (search == null) return null;
                for (int target = source + 1; target < terminals.length; target++) {
                    pairs.add(new MetricPair(source, target, search.cost[arrivals[target]]));
                }
            }
            pairs.sort(Comparator.comparingDouble((MetricPair pair) -> pair.cost)
                    .thenComparingInt(pair -> pair.from).thenComparingInt(pair -> pair.to));
            DisjointSets terminalSets = new DisjointSets(terminals.length);
            List<MetricPair> spanning = new ArrayList<>();
            for (MetricPair pair : pairs) {
                ensureNotCancelled();
                if (terminalSets.join(pair.from, pair.to)) spanning.add(pair);
                if (spanning.size() == terminals.length - 1) break;
            }

            boolean[] union = new boolean[graph.from.length / 2];
            // Восстанавливаем только T-1 выбранных путей, группируя их по источнику.
            for (int source = 0; source + 1 < terminals.length; source++) {
                boolean[] targets = new boolean[terminals.length];
                boolean needed = false;
                for (MetricPair pair : spanning) {
                    if (pair.from == source) {
                        targets[pair.to] = true;
                        needed = true;
                    }
                }
                if (!needed) continue;
                int[] arrivals = new int[terminals.length];
                Search search = search(source, targets, arrivals);
                if (search == null) return null;
                for (int target = 0; target < targets.length; target++) {
                    if (!targets[target]) continue;
                    for (int arc = arrivals[target]; arc >= 0; arc = search.previous[arc]) {
                        ensureNotCancelled();
                        union[arc / 2] = true;
                    }
                }
            }
            return spanningTree(union);
        }

        private Search search(int source, boolean[] targets, int[] arrivals) {
            ensureNotCancelled();
            Arrays.fill(arrivals, -1);
            int remaining = 0;
            for (boolean target : targets) if (target) remaining++;
            Search search = new Search(graph);
            if (reserveTransitSlots && !reservations.hasRoom(terminals[source], 1)) return null;
            for (int arc : graph.outgoing[terminals[source]]) {
                if (!reserveTransitSlots || reservations.hasRoom(graph.to[arc], 1)) {
                    search.offer(arc, graph.length[arc], -1);
                }
            }
            while (!search.queue.isEmpty()) {
                ensureNotCancelled();
                Label current = search.queue.poll();
                if (search.settled[current.arc] || current.cost != search.cost[current.arc]) continue;
                search.settled[current.arc] = true;
                int node = graph.to[current.arc];
                int terminal = terminalIndex[node];
                if (terminal >= 0 && targets[terminal] && arrivals[terminal] < 0) {
                    arrivals[terminal] = current.arc;
                    if (--remaining == 0) return search;
                }
                // Virtual demand с тремя резервами может быть концом пути, но не проходным узлом.
                if (reserveTransitSlots && !reservations.hasRoom(node, 2)) continue;
                for (int nextArc : graph.outgoing[node]) {
                    int next = graph.to[nextArc];
                    if (nextArc == (current.arc ^ 1) || next == terminals[source]) continue;
                    if (reserveTransitSlots && !reservations.hasRoom(next, 1)) continue;
                    if (!graph.turnAllowed(graph.from[current.arc], node, next)) continue;
                    double turn = graph.isBend(graph.from[current.arc], node, next) ? bendPenalty : 0;
                    search.offer(nextArc, current.cost + graph.length[nextArc] + turn, current.arc);
                }
            }
            return null;
        }

        private List<int[]> spanningTree(boolean[] union) {
            List<Integer> edges = new ArrayList<>();
            for (int edge = 0; edge < union.length; edge++) {
                ensureNotCancelled();
                if (union[edge]) edges.add(edge);
            }
            // Здесь удаляются циклы по реальной длине рёбер, без повторного начисления поворотов.
            edges.sort(Comparator.comparingDouble((Integer edge) -> graph.length[2 * edge])
                    .thenComparingInt(edge -> edge));
            DisjointSets sets = new DisjointSets(graph.x.length);
            boolean[] selected = new boolean[union.length];
            int[] degree = new int[graph.x.length];
            for (int edge : edges) {
                ensureNotCancelled();
                int a = graph.from[2 * edge];
                int b = graph.to[2 * edge];
                if (sets.join(a, b)) {
                    selected[edge] = true;
                    degree[a]++;
                    degree[b]++;
                }
            }
            pruneLeaves(selected, degree);
            List<int[]> result = new ArrayList<>();
            DisjointSets finalSets = new DisjointSets(graph.x.length);
            for (int edge = 0; edge < selected.length; edge++) {
                ensureNotCancelled();
                if (!selected[edge]) continue;
                int a = graph.from[2 * edge];
                int b = graph.to[2 * edge];
                finalSets.join(a, b);
                result.add(new int[] {a, b});
            }
            for (int node = 0; node < degree.length; node++) {
                ensureNotCancelled();
                int capacity = node == reservations.root ? reservations.rootCapacity : NODE_CAPACITY;
                if ((long) degree[node] + reservations.stubs[node] > capacity) return null;
                if (terminalIndex[node] >= 0 && finalSets.find(node) != finalSets.find(reservations.root)) return null;
                // Объединение путей MST может создать иной поворот, чем каждый исходный путь.
                if (degree[node] == 2) {
                    int first = -1;
                    for (int arc : graph.outgoing[node]) {
                        if (!selected[arc / 2]) continue;
                        if (first < 0) first = graph.to[arc];
                        else if (!graph.turnAllowed(first, node, graph.to[arc])) return null;
                    }
                }
            }
            return result;
        }

        private void pruneLeaves(boolean[] selected, int[] degree) {
            ArrayDeque<Integer> leaves = new ArrayDeque<>();
            for (int node = 0; node < degree.length; node++) {
                if (degree[node] == 1 && terminalIndex[node] < 0) leaves.add(node);
            }
            while (!leaves.isEmpty()) {
                ensureNotCancelled();
                int node = leaves.remove();
                if (degree[node] != 1) continue;
                for (int arc : graph.outgoing[node]) {
                    if (!selected[arc / 2]) continue;
                    selected[arc / 2] = false;
                    degree[node]--;
                    int neighbor = graph.to[arc];
                    degree[neighbor]--;
                    if (degree[neighbor] == 1 && terminalIndex[neighbor] < 0) leaves.add(neighbor);
                    break;
                }
            }
        }
    }

    private static final class MetricPair {
        private final int from;
        private final int to;
        private final double cost;

        private MetricPair(int from, int to, double cost) {
            this.from = from;
            this.to = to;
            this.cost = cost;
        }
    }

    private static final class DisjointSets {
        private final int[] parent;
        private final int[] size;

        private DisjointSets(int count) {
            parent = new int[count];
            size = new int[count];
            for (int node = 0; node < count; node++) {
                parent[node] = node;
                size[node] = 1;
            }
        }

        private int find(int node) {
            while (parent[node] != node) {
                parent[node] = parent[parent[node]];
                node = parent[node];
            }
            return node;
        }

        private boolean join(int a, int b) {
            int left = find(a);
            int right = find(b);
            if (left == right) return false;
            if (size[left] < size[right]) {
                int swap = left;
                left = right;
                right = swap;
            }
            parent[right] = left;
            size[left] += size[right];
            return true;
        }
    }

    private static final class Graph {
        private final double[] x;
        private final double[] y;
        private final int[] rank;
        private final Integer[] geometryOrder;
        private final int[] from;
        private final int[] to;
        private final double[] length;
        private final int[][] outgoing;
        private final boolean[] directionAllowed;
        private final boolean[] junctionArmAllowed;
        private final TurnAdmission turnAdmission;

        private Graph(List<Coordinate> points, List<int[]> links, int root) {
            this(points, links, null, root);
        }

        private Graph(List<Coordinate> points, List<int[]> links, List<Double> lengths, int root) {
            this(points, links, lengths, root, UNRESTRICTED, UNRESTRICTED, (previous, at, next) -> true);
        }

        private Graph(List<Coordinate> points, List<int[]> links, List<Double> lengths, int root,
                BiPredicate<Integer, Integer> admission, BiPredicate<Integer, Integer> junctionAdmission,
                TurnAdmission turnAdmission) {
            this.turnAdmission = turnAdmission;
            require(admission != null, "Directed link admission is required");
            require(points != null && !points.isEmpty() && points.size() <= MAX_NODES,
                    "Graph must contain 1..100000 points");
            require(links != null && links.size() <= MAX_LINKS, "Graph must contain at most 400000 links");
            require(lengths == null || lengths.size() == links.size(), "Every link requires exactly one length");
            require(root >= 0 && root < points.size(), "Root index is outside graph");
            x = new double[points.size()];
            y = new double[points.size()];
            rank = new int[points.size()];
            geometryOrder = new Integer[points.size()];
            for (int node = 0; node < points.size(); node++) {
                ensureNotCancelled();
                Coordinate point = points.get(node);
                require(point != null && Double.isFinite(point.x) && Double.isFinite(point.y),
                        "Point XY coordinates must be finite");
                x[node] = point.x;
                y[node] = point.y;
                geometryOrder[node] = node;
            }
            Arrays.sort(geometryOrder, Comparator.comparingDouble((Integer node) -> x[node])
                    .thenComparingDouble(node -> y[node]).thenComparingInt(node -> node));
            for (int position = 0; position < rank.length; position++) rank[geometryOrder[position]] = position;
            long[] keys = new long[links.size()];
            Map<Long, Double> minimumWeights = lengths == null ? null : new HashMap<>();
            for (int index = 0; index < links.size(); index++) {
                ensureNotCancelled();
                int[] link = links.get(index);
                require(link != null && link.length == 2, "A link must contain two node indexes");
                require(link[0] >= 0 && link[0] < x.length && link[1] >= 0 && link[1] < x.length,
                        "Link endpoint is outside graph");
                double distance = Math.hypot(x[link[0]] - x[link[1]], y[link[0]] - y[link[1]]);
                if (lengths == null) {
                    require(Double.isFinite(distance) && distance > 0, "Link length must be finite and positive");
                } else {
                    require(Double.isFinite(distance) && link[0] != link[1],
                            "Link chord must be finite and endpoints distinct");
                }
                int low = Math.min(rank[link[0]], rank[link[1]]);
                int high = Math.max(rank[link[0]], rank[link[1]]);
                keys[index] = ((long) low << 32) | high;
                if (lengths != null) {
                    Double weight = lengths.get(index);
                    require(weight != null && Double.isFinite(weight) && weight > 0,
                            "Link weight must be finite and positive");
                    require(weight >= distance - WEIGHT_LENGTH_EPSILON_M,
                            "Link weight cannot be shorter than its chord (tolerance 1e-6 m)");
                    minimumWeights.merge(keys[index], weight, Math::min);
                }
            }
            Arrays.sort(keys);
            int unique = 0;
            for (long key : keys) {
                if (unique == 0 || key != keys[unique - 1]) keys[unique++] = key;
            }
            from = new int[2 * unique];
            to = new int[2 * unique];
            length = new double[2 * unique];
            directionAllowed = new boolean[2 * unique];
            junctionArmAllowed = new boolean[2 * unique];
            int[] counts = new int[x.length];
            for (int index = 0; index < unique; index++) {
                ensureNotCancelled();
                int a = geometryOrder[(int) (keys[index] >>> 32)];
                int b = geometryOrder[(int) keys[index]];
                int arc = 2 * index;
                from[arc] = to[arc + 1] = a;
                to[arc] = from[arc + 1] = b;
                directionAllowed[arc] = admission.test(a, b);
                directionAllowed[arc + 1] = admission.test(b, a);
                junctionArmAllowed[arc] = junctionAdmission.test(a, b);
                junctionArmAllowed[arc + 1] = junctionAdmission.test(b, a);
                length[arc] = length[arc + 1] = minimumWeights == null
                        ? Math.hypot(x[a] - x[b], y[a] - y[b]) : minimumWeights.get(keys[index]);
                counts[a]++;
                counts[b]++;
            }
            outgoing = new int[x.length][];
            for (int node = 0; node < x.length; node++) outgoing[node] = new int[counts[node]];
            Arrays.fill(counts, 0);
            // Порядок пар геометрический, поэтому и соседи заполняются в геометрическом порядке.
            for (int arc = 0; arc < from.length; arc++) outgoing[from[arc]][counts[from[arc]]++] = arc;
        }

        private boolean turnAllowed(int previous, int node, int next) {
            return !isBend(previous, node, next) || turnAdmission.allowed(previous, node, next);
        }

        private boolean isBend(int previous, int node, int next) {
            double incomingLength = Math.hypot(x[node] - x[previous], y[node] - y[previous]);
            double outgoingLength = Math.hypot(x[next] - x[node], y[next] - y[node]);
            if (incomingLength == 0 || outgoingLength == 0) return false;
            double ax = (x[node] - x[previous]) / incomingLength;
            double ay = (y[node] - y[previous]) / incomingLength;
            double bx = (x[next] - x[node]) / outgoingLength;
            double by = (y[next] - y[node]) / outgoingLength;
            return ax * bx + ay * by <= 0 || Math.abs(ax * by - ay * bx) > STRAIGHT_SINE_TOLERANCE;
        }
    }

    private static final class Tree {
        private final int root;
        private final int rootCapacity;
        private final int[] stubs;
        private final int[] degree;
        private final int[] parent;
        private final boolean[] inTree;
        private int remaining;

        private Tree(Graph graph, int root, int rootCapacity, Map<Integer, Integer> terminalStubCounts) {
            require(terminalStubCounts != null, "Terminal stub counts are required");
            this.root = root;
            this.rootCapacity = rootCapacity;
            stubs = new int[graph.x.length];
            degree = new int[graph.x.length];
            parent = new int[graph.x.length];
            inTree = new boolean[graph.x.length];
            Arrays.fill(parent, -1);
            inTree[root] = true;
            for (Map.Entry<Integer, Integer> entry : terminalStubCounts.entrySet()) {
                ensureNotCancelled();
                Integer node = entry.getKey();
                Integer count = entry.getValue();
                require(node != null && node >= 0 && node < stubs.length, "Terminal index is outside graph");
                require(count != null && count >= 0, "Terminal stub count must be nonnegative");
                stubs[node] = count;
                if (count > 0 && node != root) remaining++;
            }
        }

        private boolean reservationsFit() {
            for (int node = 0; node < stubs.length; node++) {
                if (!hasRoom(node, node == root || stubs[node] == 0 ? 0 : 1)) return false;
            }
            return true;
        }

        private boolean hasRoom(int node, int extraEdges) {
            int capacity = node == root ? rootCapacity : NODE_CAPACITY;
            return degree[node] <= capacity && extraEdges <= capacity - degree[node]
                    && stubs[node] <= capacity - degree[node] - extraEdges;
        }

        private boolean junctionAllowed(Graph graph, int node, int firstArc, int secondArc) {
            int added = secondArc < 0 ? 1 : 2;
            if (node != root && degree[node] + stubs[node] + added < 3) return true;
            if (!graph.junctionArmAllowed[firstArc]
                    || (secondArc >= 0 && !graph.junctionArmAllowed[secondArc])) return false;
            for (int arc : graph.outgoing[node]) {
                int neighbor = graph.to[arc];
                if ((parent[node] == neighbor || parent[neighbor] == node)
                        && !graph.junctionArmAllowed[arc]) return false;
            }
            return true;
        }

        private boolean isRemainingTerminal(int node) {
            return stubs[node] > 0 && !inTree[node];
        }

        private void attach(List<Integer> path) {
            for (int index = 1; index < path.size(); index++) {
                ensureNotCancelled();
                int previous = path.get(index - 1);
                int node = path.get(index);
                if (isRemainingTerminal(node)) remaining--;
                inTree[node] = true;
                parent[node] = previous;
                degree[previous]++;
                degree[node]++;
            }
        }

        private List<int[]> edges(Graph graph) {
            List<int[]> result = new ArrayList<>();
            for (int node : graph.geometryOrder) {
                if (parent[node] >= 0) {
                    int previous = parent[node];
                    result.add(graph.rank[previous] < graph.rank[node]
                            ? new int[] {previous, node} : new int[] {node, previous});
                }
            }
            result.sort(Comparator.comparingInt((int[] edge) -> graph.rank[edge[0]])
                    .thenComparingInt(edge -> graph.rank[edge[1]]));
            return result;
        }
    }

    private static final class Search {
        private final Graph graph;
        private final double[] cost;
        private final int[] previous;
        private final boolean[] settled;
        private final PriorityQueue<Label> queue;

        private Search(Graph graph) {
            this.graph = graph;
            cost = new double[graph.from.length];
            previous = new int[graph.from.length];
            settled = new boolean[graph.from.length];
            Arrays.fill(cost, Double.POSITIVE_INFINITY);
            Arrays.fill(previous, -1);
            queue = new PriorityQueue<>(Comparator.comparingDouble((Label label) -> label.cost)
                    .thenComparingInt(label -> graph.rank[graph.to[label.arc]])
                    .thenComparingInt(label -> graph.rank[graph.from[label.arc]]));
        }

        private void offer(int arc, double candidate, int predecessor) {
            require(Double.isFinite(candidate), "Path cost overflow");
            if (settled[arc] || candidate >= cost[arc]) return;
            cost[arc] = candidate;
            previous[arc] = predecessor;
            queue.add(new Label(arc, candidate));
        }

        private List<Integer> path(int lastArc) {
            List<Integer> reverse = new ArrayList<>();
            int arc = lastArc;
            while (arc >= 0) {
                ensureNotCancelled();
                reverse.add(graph.to[arc]);
                if (previous[arc] < 0) reverse.add(graph.from[arc]);
                arc = previous[arc];
            }
            int[] position = new int[graph.x.length];
            Arrays.fill(position, -1);
            List<Integer> simple = new ArrayList<>();
            for (int index = reverse.size() - 1; index >= 0; index--) {
                int node = reverse.get(index);
                // При машинном равенстве стоимости очень коротких рёбер возможен лишний обход.
                // Удаление замкнутого участка сохраняет рёбра, резервы и единственную точку врезки.
                if (position[node] >= 0) {
                    while (simple.size() > position[node] + 1) position[simple.remove(simple.size() - 1)] = -1;
                } else {
                    position[node] = simple.size();
                    simple.add(node);
                }
            }
            return simple;
        }
    }

    private static final class Label {
        private final int arc;
        private final double cost;

        private Label(int arc, double cost) {
            this.arc = arc;
            this.cost = cost;
        }
    }
}
