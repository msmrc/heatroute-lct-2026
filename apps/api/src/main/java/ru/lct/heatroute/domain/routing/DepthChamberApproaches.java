package ru.lct.heatroute.domain.routing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;

/** Сохраняет нормали и прямые подходы камер при обходе непроходимого пересечения по глубине. */
final class DepthChamberApproaches {

    private DepthChamberApproaches() { }

    static RoutePath find(RouteEdge edge, Map<String, RouteNode> nodes, OfficialObstacleRouter router,
            OfficialRoutingEnvironment environment, Set<String> exemptions, Set<String> failedUtilities,
            List<RouteEdge> acceptedEdges) {
        RouteNode upstream = nodes.get(edge.getUpstreamNodeId()), downstream = nodes.get(edge.getDownstreamNodeId());
        ExpertChamberGeometryRules.PolylineSummary original = ExpertChamberGeometryRules.summarize(edge.getCoordinates());
        if (upstream == null || downstream == null || original == null || edge.getDiameter() == null) return null;
        double minimum = ExpertChamberGeometryRules.minimumBendDistanceM(edge.getDiameter());
        Coordinate first = upstream.getCoordinate().toCoordinate(), last = downstream.getCoordinate().toCoordinate();
        OfficialRouteGeometryRules.NormalEgress egress = downstream.isChamber() ? null
                : environment.normalEgressTowards(edge.getDiameter(), last, first, RouteTraversal.REVERSED).orElse(null);
        RouteAvoidance avoidance = router.avoidanceFor(edge, acceptedEdges.stream()
                .filter(other -> !other.getId().equals(edge.getId())).collect(Collectors.toList()), nodes);
        // Короткий законный подход берётся по ДУ; более длинные остаются ограниченными альтернативами.
        for (double stubM : new double[] {minimum + 0.01, minimum + 2, minimum + 6}) {
            Coordinate start = upstream.isChamber() ? offset(first, original.getFirstDx(), original.getFirstDy(), stubM) : first;
            Coordinate end = downstream.isChamber() ? offset(last, -original.getLastDx(), -original.getLastDy(), stubM)
                    : egress == null ? last : egress.exit();
            RoutePath candidate = router.findDepthDetourBetweenHeadings(upstream.isChamber() ? first : null, start,
                    end, downstream.isChamber() || egress != null ? last : null, edge.getDiameter(), environment,
                    exemptions, failedUtilities, avoidance, egress,
                    path -> keepsApproaches(path, upstream, downstream, original, minimum));
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static boolean keepsApproaches(RoutePath path, RouteNode upstream, RouteNode downstream,
            ExpertChamberGeometryRules.PolylineSummary original, double minimum) {
        List<RouteCoordinate> coordinates = path.coordinates().stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList());
        ExpertChamberGeometryRules.PolylineSummary actual = ExpertChamberGeometryRules.summarize(coordinates);
        if (actual == null || actual.hasInvalidBendAngle()) return false;
        if (upstream.isChamber() && (actual.getFirstBendDistanceM() + 1e-7 < minimum
                || !ExpertChamberGeometryRules.straightDirections(actual.getFirstDx(), actual.getFirstDy(),
                        original.getFirstDx(), original.getFirstDy()))) return false;
        return !downstream.isChamber() || actual.getLastBendDistanceM() + 1e-7 >= minimum
                && ExpertChamberGeometryRules.straightDirections(actual.getLastDx(), actual.getLastDy(),
                        original.getLastDx(), original.getLastDy());
    }

    private static Coordinate offset(Coordinate point, double dx, double dy, double lengthM) {
        double length = Math.hypot(dx, dy);
        return new RouteCoordinate(point.x + lengthM * dx / length, point.y + lengthM * dy / length).toCoordinate();
    }
}
