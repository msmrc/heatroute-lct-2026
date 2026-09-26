package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

/**
 * Предлагает до четырёх вводов, когда локальным L/Z-путям не хватает свободного луча камеры.
 * Каждый поиск фиксирует прямой подход камеры и реальную нормаль ОКС; препятствия неизменяемой
 * сети учитываются полностью. Ответ направлен потребитель→камера для совместного выбора лучей.
 */
final class ChamberTerminalFallback {
    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    private ChamberTerminalFallback() { }

    /** retained не содержит перестраиваемые подходы: их взаимные пересечения проверяет assignment. */
    static List<RoutePath> build(RouteEdge edge, RouteNode terminal, Coordinate chamber, double orientation,
            OfficialObstacleRouter router, OfficialRoutingEnvironment environment, List<RouteEdge> retained) {
        if (edge == null || terminal == null || chamber == null || router == null || environment == null
                || retained == null || edge.getDiameter() == null || !Double.isFinite(orientation)
                || !Double.isFinite(chamber.x) || !Double.isFinite(chamber.y)
                || !terminal.getId().equals(edge.getDownstreamNodeId()) || terminal.isChamber()) {
            throw new IllegalArgumentException("Finite chamber and downstream demand required");
        }
        double minimum = ExpertChamberGeometryRules.minimumBendDistanceM(edge.getDiameter());
        double approachM = minimum + 0.1;
        Coordinate target = terminal.getCoordinate().toCoordinate();
        OfficialRouteGeometryRules.NormalEgress normal = environment.normalEgressTowards(
                edge.getDiameter(), target, chamber, RouteTraversal.REVERSED).orElse(null);
        RouteAvoidance avoidance = new RouteAvoidance(retained.stream()
                .map(other -> GEOMETRIES.createLineString(other.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new)))
                .collect(Collectors.toList()), List.of(), false);
        List<RoutePath> result = new ArrayList<>();
        for (int ray = 0; ray < 4; ray++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Chamber terminal search cancelled");
            double angle = orientation + ray * Math.PI / 2;
            Coordinate approach = new RouteCoordinate(chamber.x + approachM * Math.cos(angle),
                    chamber.y + approachM * Math.sin(angle)).toCoordinate();
            RoutePath path = router.findChamberTerminalApproach(chamber, approach, target, edge.getDiameter(),
                    environment, avoidance, normal, candidate -> validApproach(candidate, minimum));
            if (path != null) result.add(path.reversed());
        }
        return List.copyOf(result);
    }

    private static boolean validApproach(RoutePath path, double minimum) {
        ExpertChamberGeometryRules.PolylineSummary summary = ExpertChamberGeometryRules.summarize(
                path.coordinates().stream().map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()));
        return summary != null && !summary.hasInvalidBendAngle()
                && summary.getFirstBendDistanceM() + 1e-7 >= minimum;
    }
}
