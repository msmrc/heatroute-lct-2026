package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * Перестраивает конец коридорной трубы прямым хвостом или осевым подходом к перенесённой камере.
 * Сохраняет проверяемый префикс существующей полилинии; все новые варианты проверяются целиком.
 */
final class CorridorLinkApproaches {
    private CorridorLinkApproaches() { }

    static List<RoutePath> build(RouteEdge edge, RouteNode outer, Coordinate junction, double angle,
            OfficialObstacleRouter router, OfficialRoutingEnvironment environment) {
        return build(edge, outer, junction, angle, router, environment, List.of());
    }

    /** Занятые лучи внешней камеры не включают заменяемое ребро; у корня берутся из исходной сети. */
    static List<RoutePath> build(RouteEdge edge, RouteNode outer, Coordinate junction, double angle,
            OfficialObstacleRouter router, OfficialRoutingEnvironment environment, List<Coordinate> fixedOuterRays) {
        return build(edge, outer, junction, angle, router, environment, fixedOuterRays, Set.of());
    }

    /** У перенастраиваемого корня снимается только локальный контакт с указанными существующими участками. */
    static List<RoutePath> build(RouteEdge edge, RouteNode outer, Coordinate junction, double angle,
            OfficialObstacleRouter router, OfficialRoutingEnvironment environment, List<Coordinate> fixedOuterRays,
            Set<String> junctionTargets) {
        return build(edge, outer, junction, angle, router, environment, fixedOuterRays, junctionTargets, false);
    }

    /** Внутренний ремонт может сохранить уже дефектный ввод соседней камеры до её отдельного исправления. */
    static List<RoutePath> build(RouteEdge edge, RouteNode outer, Coordinate junction, double angle,
            OfficialObstacleRouter router, OfficialRoutingEnvironment environment, List<Coordinate> fixedOuterRays,
            Set<String> junctionTargets, boolean preserveInvalidOuterApproach) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor approach cancelled");
        if (edge == null || outer == null || router == null || environment == null || edge.getDiameter() == null
                || fixedOuterRays == null || junctionTargets == null || !Double.isFinite(angle) || junction == null
                || !Double.isFinite(junction.x) || !Double.isFinite(junction.y)) {
            throw new IllegalArgumentException("Finite orientation and junction required");
        }
        if (!edge.getUpstreamNodeId().equals(outer.getId()) && !edge.getDownstreamNodeId().equals(outer.getId())) {
            throw new IllegalArgumentException("Outer node must be an edge endpoint");
        }
        List<Coordinate> source = edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                .collect(Collectors.toCollection(ArrayList::new));
        RouteTraversal traversal = edge.getUpstreamNodeId().equals(outer.getId())
                ? RouteTraversal.AS_GIVEN : RouteTraversal.REVERSED;
        if (traversal == RouteTraversal.REVERSED) Collections.reverse(source);
        if (source.size() < 2 || source.size() > 1000) return List.of();
        if (source.get(0).distance(outer.getCoordinate().toCoordinate()) > 0.01) {
            throw new IllegalArgumentException("Edge geometry misses its outer node");
        }
        double nx = Math.cos(angle), ny = Math.sin(angle);
        List<RoutePath> paths = new ArrayList<>();
        List<Integer> cuts = new ArrayList<>(List.of(source.size() - 2));
        if (source.size() > 2) cuts.add(0);
        for (int cut : cuts) {
            Coordinate start = source.get(cut);
            List<Coordinate> direct = new ArrayList<>(source.subList(0, cut + 1));
            // Проекция может создать миллиметровое колено или потерять точный конец.
            // Прямой хвост проверяем отдельно, сохраняя всю геометрию до места среза.
            if (!start.equals2D(new RouteCoordinate(junction.x, junction.y).toCoordinate())) {
                direct.add(new Coordinate(junction));
            }
            addCheckedPath(paths, direct, edge, outer, junction, router, environment, traversal, angle, fixedOuterRays, junctionTargets, preserveInvalidOuterApproach);
            List<Coordinate> approaches = new ArrayList<>(List.of(junction));
            for (double length : approachLengths(edge.getDiameter())) {
                for (int direction = 0; direction < 4; direction++) {
                    double bearing = angle + direction * Math.PI / 2;
                    approaches.add(new Coordinate(junction.x + length * Math.cos(bearing), junction.y + length * Math.sin(bearing)));
                }
            }
            for (Coordinate approach : approaches) {
                for (boolean primaryFirst : new boolean[] {true, false}) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor approach cancelled");
                    double x = primaryFirst ? nx : -ny, y = primaryFirst ? ny : nx;
                    double projection = (approach.x - start.x) * x + (approach.y - start.y) * y;
                    Coordinate elbow = new Coordinate(start.x + x * projection, start.y + y * projection);
                    List<Coordinate> coordinates = new ArrayList<>(source.subList(0, cut + 1));
                    append(coordinates, elbow); append(coordinates, approach); append(coordinates, junction);
                    addCheckedPath(paths, coordinates, edge, outer, junction, router, environment, traversal, angle, fixedOuterRays, junctionTargets, preserveInvalidOuterApproach);
                }
            }
        }
        if (!fixedOuterRays.isEmpty() || paths.isEmpty()) {
            addDoubleEndedPaths(paths, edge, outer, junction, angle, router, environment, traversal, fixedOuterRays, junctionTargets, preserveInvalidOuterApproach);
        }
        paths.sort(Comparator.comparingDouble(RoutePath::lengthM));
        List<RoutePath> result = new ArrayList<>();
        // Сначала лучший путь каждого конечного луча, затем дополнительные варианты обхода.
        for (RoutePath path : paths) {
            if (result.stream().noneMatch(previous -> sameRay(previous, path))) result.add(path);
            if (result.size() == 8) return List.copyOf(result);
        }
        for (RoutePath path : paths) {
            if (!result.contains(path)) result.add(path);
            if (result.size() == 8) break;
        }
        return List.copyOf(result);
    }

    private static void addDoubleEndedPaths(List<RoutePath> paths, RouteEdge edge, RouteNode outer,
            Coordinate junction, double angle, OfficialObstacleRouter router, OfficialRoutingEnvironment environment,
            RouteTraversal traversal, List<Coordinate> fixedOuterRays, Set<String> junctionTargets, boolean preserveInvalidOuterApproach) {
        Coordinate origin = outer.getCoordinate().toCoordinate();
        List<Coordinate> outerApproaches = new ArrayList<>();
        for (double length : approachLengths(edge.getDiameter())) {
            if (!fixedOuterRays.isEmpty()) {
                outerApproaches.addAll(new ChamberApproachCandidates().build(origin, fixedOuterRays, length, 180));
            } else {
                for (int direction = 0; direction < 4; direction++) outerApproaches.add(
                        along(origin, angle + direction * Math.PI / 2, length));
            }
        }
        for (Coordinate first : outerApproaches) {
            for (double length : approachLengths(edge.getDiameter())) {
                for (int direction = 0; direction < 4; direction++) {
                    Coordinate last = along(junction, angle + direction * Math.PI / 2, length);
                    for (int connector = 0; connector < 3; connector++) {
                        List<Coordinate> points = new ArrayList<>(List.of(origin, first));
                        if (connector != 0) {
                            double bearing = angle + (connector - 1) * Math.PI / 2;
                            double x = Math.cos(bearing), y = Math.sin(bearing);
                            double distance = (last.x - first.x) * x + (last.y - first.y) * y;
                            append(points, new Coordinate(first.x + distance * x, first.y + distance * y));
                        }
                        append(points, last); append(points, junction);
                        addCheckedPath(paths, points, edge, outer, junction, router, environment, traversal, angle, fixedOuterRays, junctionTargets, preserveInvalidOuterApproach);
                    }
                }
            }
        }
    }

    /** Два ограниченных удаления от камеры, оба превышают табличный минимум данного ДУ. */
    private static double[] approachLengths(int diameter) {
        double minimum = ExpertChamberGeometryRules.minimumBendDistanceM(diameter);
        return new double[] {minimum + 0.1, minimum + 3};
    }

    private static Coordinate along(Coordinate origin, double angle, double length) {
        return new Coordinate(origin.x + length * Math.cos(angle), origin.y + length * Math.sin(angle));
    }

    /** Один допуск для прямых и L-хвостов, включая сохранённый префикс и направление потока. */
    private static void addCheckedPath(List<RoutePath> paths, List<Coordinate> coordinates,
            RouteEdge edge, RouteNode outer, Coordinate junction, OfficialObstacleRouter router,
            OfficialRoutingEnvironment environment, RouteTraversal traversal, double angle, List<Coordinate> fixedOuterRays,
            Set<String> junctionTargets, boolean preserveInvalidOuterApproach) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor approach cancelled");
        if (coordinates.size() < 2) return;
        ExpertChamberGeometryRules.PolylineSummary summary = ExpertChamberGeometryRules.summarize(coordinates.stream()
                .map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()));
        if (summary == null || summary.hasInvalidBendAngle()
                || summary.getLastBendDistanceM() + 1e-7 < ExpertChamberGeometryRules.minimumBendDistanceM(edge.getDiameter())
                || !alignedWithFrame(summary.getLastDx(), summary.getLastDy(), angle)) return;
        if (outer.isChamber()) {
            if (summary.getActualLengthM() + 1e-7 < ExpertChamberRouteValidator.MIN_CHAMBER_SECTION_LENGTH_M) return;
            boolean normal = fixedOuterRays.isEmpty()
                    ? alignedWithFrame(summary.getFirstDx(), summary.getFirstDy(), angle)
                    : fixedOuterRays.stream().allMatch(ray -> ExpertChamberGeometryRules.compatibleRays(
                            summary.getFirstDx(), summary.getFirstDy(), ray.x, ray.y));
            boolean longEnough = summary.getFirstBendDistanceM() + 1e-7 >= ExpertChamberGeometryRules.minimumBendDistanceM(edge.getDiameter());
            if ((!normal || !longEnough) && !(preserveInvalidOuterApproach && !outer.isRoot()
                    && preservesOuterPrefix(edge, outer, coordinates))) return;
        }
        Envelope bounds = new Envelope();
        coordinates.forEach(bounds::expandToInclude);
        PreparedCorridor checks = router.prepareCorridor(edge.getDiameter(), environment, bounds,
                outer.getCoordinate().toCoordinate(), outer.isRoot() ? outer.getTargetId() : null, traversal,
                junctionTargets, junction);
        // Поиск всегда outer→камера, но вход в дорогу и special проверяем по потоку.
        // Исключение выхода из setback предназначено существующему корню, не новой камере.
        if (!checks.pointAllowed(junction)
                || !outer.isRoot() && !checks.pointAllowed(outer.getCoordinate().toCoordinate())) return;
        RoutePath path = checks.path(coordinates);
        if (path == null || !engineeringCompliant(path)) return;
        if (paths.stream().noneMatch(previous -> same(previous, path))) paths.add(path);
    }

    /** Сохраняет старый дефектный ввод до следующего ремонта; длина прямого префикса учитывает ДУ. */
    private static boolean preservesOuterPrefix(RouteEdge edge, RouteNode outer, List<Coordinate> candidate) {
        List<RouteCoordinate> source = new ArrayList<>(edge.getCoordinates());
        if (!edge.getUpstreamNodeId().equals(outer.getId())) Collections.reverse(source);
        ExpertChamberGeometryRules.PolylineSummary original = ExpertChamberGeometryRules.summarize(source);
        if (original == null) return false;
        List<RouteCoordinate> rounded = candidate.stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList());
        ExpertChamberGeometryRules.PolylineSummary replacement = ExpertChamberGeometryRules.summarize(rounded);
        if (replacement == null || !ExpertChamberGeometryRules.straightDirections(original.getFirstDx(), original.getFirstDy(),
                replacement.getFirstDx(), replacement.getFirstDy())) return false;
        double prefix = Math.min(original.getActualLengthM(), ExpertChamberGeometryRules.minimumBendDistanceM(edge.getDiameter())
                + (Double.isFinite(original.getFirstBendDistanceM()) ? original.getFirstBendDistanceM() : 0));
        if (replacement.getActualLengthM() + 1e-7 < prefix) return false;
        GeometryFactory geometries = new GeometryFactory();
        LineString before = geometries.createLineString(source.stream().map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
        LineString after = geometries.createLineString(rounded.stream().map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
        org.locationtech.jts.geom.Geometry expected = new org.locationtech.jts.linearref.LengthIndexedLine(before).extractLine(0, prefix);
        org.locationtech.jts.geom.Geometry actual = new org.locationtech.jts.linearref.LengthIndexedLine(after).extractLine(0, prefix);
        return org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance.distance(expected, actual) <= 0.001;
    }

    private static boolean alignedWithFrame(double dx, double dy, double angle) {
        double length = Math.hypot(dx, dy), x = length * Math.cos(angle), y = length * Math.sin(angle);
        return ExpertChamberGeometryRules.compatibleRays(dx, dy, x, y)
                || ExpertChamberGeometryRules.compatibleRays(dx, dy, -y, x);
    }

    private static void append(List<Coordinate> coordinates, Coordinate point) {
        if (coordinates.get(coordinates.size() - 1).distance(point) > 0.001) coordinates.add(point);
    }

    private static boolean engineeringCompliant(RoutePath path) {
        LineString line = new GeometryFactory().createLineString(path.coordinates().toArray(new Coordinate[0]));
        if (!line.isSimple() || line.isClosed()) return false;
        RouteEdge candidate = new RouteEdge("approach", "outer", "junction", path.lengthM(),
                path.coordinates().stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()),
                path.sections(), null, null);
        return new EngineeringRouteEvaluator().evaluate(List.of(candidate)).isCompliant();
    }

    private static boolean same(RoutePath a, RoutePath b) {
        if (a.coordinates().size() != b.coordinates().size()) return false;
        for (int i = 0; i < a.coordinates().size(); i++) {
            if (a.coordinates().get(i).distance(b.coordinates().get(i)) > 0.001) return false;
        }
        return true;
    }

    private static boolean sameRay(RoutePath a, RoutePath b) {
        Coordinate ae = a.coordinates().get(a.coordinates().size() - 1), ap = a.coordinates().get(a.coordinates().size() - 2);
        Coordinate be = b.coordinates().get(b.coordinates().size() - 1), bp = b.coordinates().get(b.coordinates().size() - 2);
        double cosine = ((ap.x - ae.x) * (bp.x - be.x) + (ap.y - ae.y) * (bp.y - be.y)) / ap.distance(ae) / bp.distance(be);
        return cosine > Math.cos(Math.toRadians(1));
    }
}
