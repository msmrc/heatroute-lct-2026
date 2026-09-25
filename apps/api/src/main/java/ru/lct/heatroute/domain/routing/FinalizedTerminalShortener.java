package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.depth.DepthProfileResult;

/** Укорачивает листья завершённой сети, сохраняя её топологию и исходный контрольный вариант. */
final class FinalizedTerminalShortener {
    private static final int PASSES = 2;
    private static final int LEAVES_PER_PASS = 24;
    private static final int PATHS_PER_EDGE = 8;
    private static final int FINISH_ATTEMPTS = 48;
    private static final int MAX_COORDINATES = 1000;
    private static final double TOLERANCE_M = 0.01;
    private static final BigDecimal MIN_SHORTENING_M = new BigDecimal("0.01");
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final EngineeringRouteEvaluator engineering = new EngineeringRouteEvaluator();

    /** Проверяет до 48 замен через полный finish; ошибки и отмену передаёт вызывающему коду. */
    RouteVariant improve(RouteVariant baseline, boolean depthEnabled,
            BiFunction<RouteVariant, RouteEdge, List<RoutePath>> alternatives,
            BiFunction<RouteVariant, List<RouteEdge>, RouteVariant> finish) {
        checkInterrupted();
        if (!complete(baseline, depthEnabled)) return baseline;
        EngineeringRouteEvaluator.Evaluation evaluation = engineering.evaluate(baseline.getEdges());
        if (!evaluation.isCompliant()) return baseline;
        RouteVariant current = baseline;
        int attempts = 0;
        for (int pass = 0; pass < PASSES; pass++) {
            checkInterrupted();
            for (String edgeId : terminalIds(current)) {
                checkInterrupted();
                if (attempts >= FINISH_ATTEMPTS) return current;
                RouteEdge edge = edgeById(current, edgeId);
                List<RoutePath> paths = alternatives.apply(current, edge);
                checkInterrupted();
                if (paths == null) continue;
                for (int index = 0; index < Math.min(PATHS_PER_EDGE, paths.size()); index++) {
                    checkInterrupted();
                    if (attempts >= FINISH_ATTEMPTS) return current;
                    RouteEdge replacement = replacement(current, edge, paths.get(index));
                    if (replacement == null) continue;
                    List<RouteEdge> edges = new ArrayList<>(current.getEdges());
                    edges.set(edges.indexOf(edge), replacement);
                    attempts++;
                    RouteVariant candidate = finish.apply(current, List.copyOf(edges));
                    checkInterrupted();
                    if (!complete(candidate, depthEnabled) || !sameNetwork(current, candidate, edges)
                            || candidate.getTotalLengthM().compareTo(current.getTotalLengthM()) >= 0
                            || candidate.getEconomics().getCalculatedCost()
                                    .compareTo(current.getEconomics().getCalculatedCost()) > 0) continue;
                    EngineeringRouteEvaluator.Evaluation next = engineering.evaluate(candidate.getEdges());
                    if (!next.isCompliant() || next.bendCount() > evaluation.bendCount()
                            || !next.preservesJunctionQualityOf(evaluation)) continue;
                    current = candidate;
                    evaluation = next;
                    break;
                }
            }
        }
        return current;
    }

    private boolean complete(RouteVariant variant, boolean depthEnabled) {
        if (variant == null || !variant.isValid() || !variant.getEngineeringIssues().isEmpty()
                || variant.getNodes().isEmpty() || variant.getEdges().isEmpty()
                || variant.getConnections().isEmpty()
                || variant.getConnectedDemandCount() != variant.getConnections().size()
                || variant.getTotalLengthM() == null || variant.getTotalLengthM().signum() <= 0
                || variant.getEconomics() == null || !variant.getEconomics().isComplete()
                || !variant.getEconomics().getIncompleteReasons().isEmpty()
                || variant.getEconomics().getCalculatedCost() == null
                || variant.getEconomics().getCalculatedCost().signum() < 0) return false;
        BigDecimal length = BigDecimal.ZERO;
        for (RouteEdge edge : variant.getEdges()) {
            checkInterrupted();
            if (edge.getDiameter() == null || edge.getDiameter() <= 0
                    || edge.getFlowTph() == null || edge.getFlowTph().signum() < 0
                    || edge.getLengthM().signum() <= 0
                    || (depthEnabled && !completeDepth(edge))) return false;
            length = length.add(edge.getLengthM());
        }
        return length.compareTo(variant.getTotalLengthM()) == 0;
    }

    private boolean completeDepth(RouteEdge edge) {
        DepthProfileResult depth = edge.getDepthProfile();
        if (depth == null || !depth.isComplete() || !depth.getIssues().isEmpty()
                || depth.getPoints().size() < 2) return false;
        return depth.getPoints().get(0).getStationM().signum() == 0
                && depth.getPoints().get(depth.getPoints().size() - 1).getStationM()
                        .subtract(edge.getLengthM()).abs().compareTo(MIN_SHORTENING_M) <= 0;
    }

    private List<String> terminalIds(RouteVariant variant) {
        Map<String, Integer> degree = new HashMap<>();
        Map<String, RouteNode> nodes = new HashMap<>();
        for (RouteNode node : variant.getNodes()) {
            checkInterrupted();
            nodes.put(node.getId(), node);
        }
        for (RouteEdge edge : variant.getEdges()) {
            checkInterrupted();
            degree.merge(edge.getUpstreamNodeId(), 1, Integer::sum);
            degree.merge(edge.getDownstreamNodeId(), 1, Integer::sum);
        }
        List<String> result = new ArrayList<>();
        for (RouteEdge edge : variant.getEdges()) {
            checkInterrupted();
            RouteNode downstream = nodes.get(edge.getDownstreamNodeId());
            if (downstream != null && "demand_connection".equals(downstream.getNodeType())
                    && !downstream.isRoot() && !downstream.isChamber()
                    && degree.getOrDefault(downstream.getId(), 0) == 1) result.add(edge.getId());
        }
        result.sort(Comparator.naturalOrder());
        return result.subList(0, Math.min(LEAVES_PER_PASS, result.size()));
    }

    private RouteEdge replacement(RouteVariant current, RouteEdge edge, RoutePath path) {
        if (path == null || !Double.isFinite(path.lengthM()) || path.lengthM() <= 0
                || path.coordinates().size() < 2 || path.coordinates().size() > MAX_COORDINATES
                || path.sections().isEmpty() || path.sections().size() > MAX_COORDINATES) return null;
        RouteNode upstream = nodeById(current, edge.getUpstreamNodeId());
        RouteNode downstream = nodeById(current, edge.getDownstreamNodeId());
        if (upstream == null || downstream == null
                || !validGeometry(path.coordinates(), upstream, downstream, path.lengthM())) return null;
        int sectionPoints = 0;
        for (RouteSection section : path.sections()) {
            checkInterrupted();
            if (section == null) return null;
            sectionPoints += section.getCoordinates().size();
            if (sectionPoints > MAX_COORDINATES * 2) return null;
        }
        List<RouteCoordinate> coordinates = path.coordinates().stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList());
        // Повторный допуск нужен после миллиметрового округления хранимой геометрии.
        List<Coordinate> rounded = coordinates.stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
        if (!validGeometry(rounded, upstream, downstream, path.lengthM())) return null;
        RouteEdge replacement = new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                path.lengthM(), coordinates, path.sections(), edge.getFlowTph(), edge.getDiameter());
        // Глубина старой полилинии не переносится: finish обязан рассчитать новый профиль.
        return edge.getLengthM().subtract(replacement.getLengthM()).compareTo(MIN_SHORTENING_M) > 0
                ? replacement : null;
    }

    private boolean validGeometry(List<Coordinate> points, RouteNode upstream, RouteNode downstream, double length) {
        for (Coordinate point : points) {
            checkInterrupted();
            if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) return false;
        }
        if (points.get(0).distance(upstream.getCoordinate().toCoordinate()) > TOLERANCE_M
                || points.get(points.size() - 1).distance(downstream.getCoordinate().toCoordinate()) > TOLERANCE_M) return false;
        LineString line = geometryFactory.createLineString(points.toArray(new Coordinate[0]));
        return Double.isFinite(line.getLength()) && Math.abs(line.getLength() - length) <= TOLERANCE_M
                && !line.isClosed() && line.isSimple();
    }

    private boolean sameNetwork(RouteVariant current, RouteVariant candidate, List<RouteEdge> proposed) {
        if (current.getNodes().size() != candidate.getNodes().size()
                || current.getEdges().size() != candidate.getEdges().size()
                || current.getConnections().size() != candidate.getConnections().size()) return false;
        Map<String, RouteNode> nodes = new HashMap<>();
        for (RouteNode node : candidate.getNodes()) {
            checkInterrupted();
            if (nodes.put(node.getId(), node) != null) return false;
        }
        for (RouteNode node : current.getNodes()) {
            checkInterrupted();
            RouteNode other = nodes.get(node.getId());
            if (other == null || !Objects.equals(node.getNodeType(), other.getNodeType())
                    || node.isRoot() != other.isRoot() || node.isChamber() != other.isChamber()
                    || node.getBaseIncidentSections() != other.getBaseIncidentSections()
                    || !Objects.equals(node.getTargetId(), other.getTargetId())
                    || !Objects.equals(node.getExistingIncidentDiameter(), other.getExistingIncidentDiameter())
                    || !sameCoordinate(node.getCoordinate(), other.getCoordinate())) return false;
        }
        Map<String, RouteConnection> connections = new HashMap<>();
        for (RouteConnection connection : candidate.getConnections()) {
            checkInterrupted();
            if (connections.put(connection.getDemandId(), connection) != null) return false;
        }
        for (RouteConnection connection : current.getConnections()) {
            checkInterrupted();
            RouteConnection other = connections.get(connection.getDemandId());
            if (other == null || !Objects.equals(connection.getConnectionPointId(), other.getConnectionPointId())
                    || !Objects.equals(connection.getStatus(), other.getStatus())
                    || !Objects.equals(connection.getFlowTph(), other.getFlowTph())) return false;
        }
        Map<String, RouteEdge> edges = new HashMap<>();
        for (RouteEdge edge : candidate.getEdges()) {
            checkInterrupted();
            if (edges.put(edge.getId(), edge) != null) return false;
        }
        for (RouteEdge edge : proposed) {
            checkInterrupted();
            RouteEdge other = edges.get(edge.getId());
            if (other == null || !Objects.equals(edge.getUpstreamNodeId(), other.getUpstreamNodeId())
                    || !Objects.equals(edge.getDownstreamNodeId(), other.getDownstreamNodeId())
                    || !Objects.equals(edge.getFlowTph(), other.getFlowTph())
                    || !Objects.equals(edge.getDiameter(), other.getDiameter())
                    || edge.getLengthM().compareTo(other.getLengthM()) != 0
                    || !sameCoordinates(edge.getCoordinates(), other.getCoordinates())) return false;
        }
        return true;
    }

    private boolean sameCoordinates(List<RouteCoordinate> left, List<RouteCoordinate> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            checkInterrupted();
            if (!sameCoordinate(left.get(index), right.get(index))) return false;
        }
        return true;
    }

    private boolean sameCoordinate(RouteCoordinate left, RouteCoordinate right) {
        return left.getXM().compareTo(right.getXM()) == 0 && left.getYM().compareTo(right.getYM()) == 0;
    }

    private RouteNode nodeById(RouteVariant variant, String id) {
        return variant.getNodes().stream().filter(node -> id.equals(node.getId())).findFirst().orElse(null);
    }

    private RouteEdge edgeById(RouteVariant variant, String id) {
        return variant.getEdges().stream().filter(edge -> id.equals(edge.getId())).findFirst().orElseThrow();
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Terminal shortening cancelled");
    }
}
