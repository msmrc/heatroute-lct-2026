package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ограниченный архив полностью допущенных решений, ранжируемых только по точным метрикам. */
public final class AcceptedSolutionArchive {
    private static final Comparator<AcceptedNetworkSolution> BEST_FIRST = Comparator
            .comparing(AcceptedSolutionArchive::score)
            .thenComparing(AcceptedSolutionArchive::cost)
            .thenComparing(AcceptedNetworkSolution::getTotalLengthM)
            .thenComparing(AcceptedNetworkSolution::getId);

    private final int capacity;
    private final Map<String, AcceptedNetworkSolution> byGeometryHash = new LinkedHashMap<>();

    public AcceptedSolutionArchive(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Archive capacity must be positive");
        this.capacity = capacity;
    }

    /** Возвращает true, если новая геометрия сохранилась в ограниченном best-first архиве. */
    public synchronized boolean add(AcceptedNetworkSolution solution) {
        Objects.requireNonNull(solution, "solution");
        requireRankable(solution);
        AcceptedNetworkSolution existing = byGeometryHash.get(solution.getGeometryHash());
        if (existing != null) {
            if (!sameGeometry(existing, solution)) {
                throw new IllegalStateException("Accepted solution geometry hash collision");
            }
            if (BEST_FIRST.compare(solution, existing) < 0) {
                byGeometryHash.put(solution.getGeometryHash(), solution);
                return true;
            }
            return false;
        }
        byGeometryHash.put(solution.getGeometryHash(), solution);
        if (byGeometryHash.size() > capacity) {
            AcceptedNetworkSolution worst = byGeometryHash.values().stream()
                    .max(BEST_FIRST).orElseThrow();
            byGeometryHash.remove(worst.getGeometryHash());
        }
        return byGeometryHash.get(solution.getGeometryHash()) == solution;
    }

    public synchronized List<AcceptedNetworkSolution> snapshot() {
        List<AcceptedNetworkSolution> result = new ArrayList<>(byGeometryHash.values());
        result.sort(BEST_FIRST);
        return List.copyOf(result);
    }

    public synchronized AcceptedNetworkSolution best() {
        return byGeometryHash.values().stream().min(BEST_FIRST).orElse(null);
    }

    public synchronized int size() { return byGeometryHash.size(); }
    public int capacity() { return capacity; }

    private static void requireRankable(AcceptedNetworkSolution solution) {
        if (!solution.getEconomics().isComplete() || solution.getEconomics().getScore() == null
                || solution.getEconomics().getCalculatedCost() == null) {
            throw new IllegalArgumentException("Accepted archive requires complete exact economics");
        }
    }

    private static BigDecimal score(AcceptedNetworkSolution solution) {
        return solution.getEconomics().getScore();
    }

    private static BigDecimal cost(AcceptedNetworkSolution solution) {
        return solution.getEconomics().getCalculatedCost();
    }

    private static boolean sameGeometry(AcceptedNetworkSolution left, AcceptedNetworkSolution right) {
        List<RouteNode> leftNodes = new ArrayList<>(left.getNodes());
        List<RouteNode> rightNodes = new ArrayList<>(right.getNodes());
        leftNodes.sort(Comparator.comparing(RouteNode::getId));
        rightNodes.sort(Comparator.comparing(RouteNode::getId));
        if (leftNodes.size() != rightNodes.size()) return false;
        for (int index = 0; index < leftNodes.size(); index++) {
            RouteNode a = leftNodes.get(index);
            RouteNode b = rightNodes.get(index);
            if (!Objects.equals(a.getId(), b.getId())
                    || !Objects.equals(a.getNodeType(), b.getNodeType())
                    || !Objects.equals(a.getCoordinate().getXM(), b.getCoordinate().getXM())
                    || !Objects.equals(a.getCoordinate().getYM(), b.getCoordinate().getYM())
                    || a.isRoot() != b.isRoot() || a.isChamber() != b.isChamber()
                    || a.getBaseIncidentSections() != b.getBaseIncidentSections()
                    || !Objects.equals(a.getTargetId(), b.getTargetId())) return false;
        }
        List<RouteEdge> leftEdges = new ArrayList<>(left.getEdges());
        List<RouteEdge> rightEdges = new ArrayList<>(right.getEdges());
        leftEdges.sort(Comparator.comparing(RouteEdge::getId));
        rightEdges.sort(Comparator.comparing(RouteEdge::getId));
        if (leftEdges.size() != rightEdges.size()) return false;
        for (int index = 0; index < leftEdges.size(); index++) {
            RouteEdge a = leftEdges.get(index);
            RouteEdge b = rightEdges.get(index);
            if (!Objects.equals(a.getId(), b.getId())
                    || !Objects.equals(a.getUpstreamNodeId(), b.getUpstreamNodeId())
                    || !Objects.equals(a.getDownstreamNodeId(), b.getDownstreamNodeId())
                    || !sameCoordinates(a.getCoordinates(), b.getCoordinates())) return false;
        }
        return true;
    }

    private static boolean sameCoordinates(List<RouteCoordinate> left, List<RouteCoordinate> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).getXM().equals(right.get(index).getXM())
                    || !left.get(index).getYM().equals(right.get(index).getYM())) return false;
        }
        return true;
    }
}
