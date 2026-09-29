package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

/** Ограниченные нормальные вводы в существующую камеру с проверкой всей трассы и выхода ОКС. */
final class RootChamberApproaches {
    private RootChamberApproaches() { }

    static RoutePath best(Coordinate demand, Coordinate chamber, List<Coordinate> existingRays, int diameter,
            OfficialObstacleRouter router, OfficialRoutingEnvironment environment, Set<String> exemptions,
            List<LineString> avoidance) {
        if (existingRays.isEmpty()) return null;
        double minimum = ExpertChamberGeometryRules.minimumBendDistanceM(diameter);
        double orientation = Math.atan2(existingRays.get(0).y, existingRays.get(0).x);
        List<List<Coordinate>> offers = new ArrayList<>();
        offers.add(List.of(demand, chamber));
        for (int direction = 0; direction < 2; direction++) {
            double angle = orientation + direction * Math.PI / 2;
            double x = Math.cos(angle), y = Math.sin(angle);
            double projection = (demand.x - chamber.x) * x + (demand.y - chamber.y) * y;
            Coordinate elbow = new Coordinate(chamber.x + x * projection, chamber.y + y * projection);
            if (elbow.distance(demand) > 0.01 && elbow.distance(chamber) >= minimum + 0.01) {
                offers.add(List.of(demand, elbow, chamber));
            }
        }
        for (OfficialRouteGeometryRules.NormalEgress egress : environment.normalEgressCandidates(
                diameter, demand, chamber, HeatRouteEngineeringRules.ENGINEERING_EGRESS_EXTRA_M, RouteTraversal.REVERSED)) {
            for (List<Coordinate> outside : NormalCorridorTransitions.build(
                    egress.start(), egress.exit(), chamber, orientation, minimum + 0.1)) {
                List<Coordinate> points = new ArrayList<>(List.of(egress.start()));
                points.addAll(outside);
                offers.add(points);
            }
        }
        offers.sort(Comparator.comparingDouble(RootChamberApproaches::length));
        for (List<Coordinate> points : offers) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Root approach cancelled");
            List<Coordinate> prefix = points.subList(0, points.size() - 1);
            if (prefix.size() == 1) prefix = points;
            RoutePath offered = new RoutePath(prefix, List.of(), length(prefix));
            RoutePath checked = router.withCheckedDemandSuffix(offered, chamber, diameter, environment, exemptions, avoidance);
            if (checked == null) continue;
            List<RouteCoordinate> rounded = checked.coordinates().stream()
                    .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList());
            ExpertChamberGeometryRules.PolylineSummary summary = ExpertChamberGeometryRules.summarize(rounded);
            if (summary == null || summary.getLastBendDistanceM() + 1e-7 < minimum
                    || existingRays.stream().anyMatch(ray -> !ExpertChamberGeometryRules.compatibleRays(
                            -summary.getLastDx(), -summary.getLastDy(), ray.x, ray.y))) continue;
            RouteEdge edge = new RouteEdge("root-approach", "demand", "root", checked.lengthM(),
                    rounded, checked.sections(), null, diameter);
            if (new EngineeringRouteEvaluator().evaluate(List.of(edge)).isCompliant()) return checked;
        }
        return null;
    }

    private static double length(List<Coordinate> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) length += points.get(i - 1).distance(points.get(i));
        return length;
    }
}
