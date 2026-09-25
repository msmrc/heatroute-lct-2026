package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialObstacleRouter {
    private static final double NAVIGATION_MARGIN_M = 0.25;
    private static final double SPECIAL_CROSSING_PORTAL_MARGIN_M = 0.25;
    private static final double MINIMUM_PREFERENCE_FACTOR = 0.985;
    private static final int MAX_VERTICES_PER_OBSTACLE = 12;
    private static final int MAX_POCKET_VERTICES_PER_OBSTACLE = 64;
    // До 17 МиБ примитивных данных; строки выделяются только для достигнутых состояний.
    private static final long MAX_DENSE_SEARCH_STATES = 1_048_576L;
    private static final double[] CORRIDOR_EXPANSIONS = {75.0, 200.0, 600.0};
    static final double MAXIMUM_SEARCH_CORRIDOR_M = 600.0;

    private final OfficialRouteGeometryRules rules;

    public OfficialObstacleRouter(OfficialRouteGeometryRules rules) {
        this.rules = rules;
    }

    OfficialRoutingEnvironment prepare(List<ImportedOfficialFeature> features) {
        List<ImportedOfficialFeature> core = features.stream()
                .filter(feature -> !isWindowedRoutingFeature(feature))
                .collect(java.util.stream.Collectors.toList());
        List<ImportedOfficialFeature> windowed = features.stream()
                .filter(this::isWindowedRoutingFeature)
                .collect(java.util.stream.Collectors.toList());
        return new OfficialRoutingEnvironment(
                core,
                new InMemoryRoutingFeatureSource(windowed),
                rules);
    }

    OfficialRoutingEnvironment prepare(List<ImportedOfficialFeature> features, RoutingFeatureSource source) {
        return new OfficialRoutingEnvironment(features, source, rules);
    }

    private boolean isWindowedRoutingFeature(ImportedOfficialFeature feature) {
        return "restriction".equals(feature.getObjectType())
                || "oks_existing".equals(feature.getObjectType());
    }

    PreparedCorridor prepareCorridor(int diameter, OfficialRoutingEnvironment environment,
            Envelope bounds, Coordinate root, String targetId) {
        return new PreparedCorridor(rules, environment.corridorConstraints(diameter, bounds), root, targetId);
    }

    double buildingClearanceM(int diameter) { return rules.preparationClearanceM("oks", diameter).doubleValue(); }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgress(
            List<ImportedOfficialFeature> features,
            int diameter,
            Coordinate connectionPoint) {
        return rules.normalEgress(features, diameter, connectionPoint);
    }

    RoutePath find(
            Coordinate start,
            Coordinate end,
            int diameter,
            List<ImportedOfficialFeature> features,
            Set<String> exemptFeatureIds,
            RoutePreference preference) {
        return find(start, end, diameter, prepare(features), exemptFeatureIds, preference);
    }

    /**
     * Ищет наружную часть после реального ввода previous → start, включая его в road/tram special.
     * Результат требует withCheckedTerminalPrefix: льгота своего ОКС здесь ещё не проверена.
     */
    RoutePath findAfter(Coordinate previous, Coordinate start, Coordinate end, int diameter,
            OfficialRoutingEnvironment environment, Set<String> exemptFeatureIds,
            RoutePreference preference, List<LineString> acceptedRoutes) {
        requireHeading(previous, start);
        return findUncached(start, end, diameter, environment, exemptFeatureIds, preference,
                acceptedRoutes, Collections.emptyList(), new Coordinate(previous));
    }

    List<String> directBlockingConstraintIds(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes) {
        List<Constraint> constraints = new ArrayList<>(environment.constraints(
                diameter, exemptFeatureIds, start, end));
        constraints.addAll(rules.applicableConstraints(
                rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(),
                start,
                end));
        LineString direct = rules.line(List.of(start, end));
        return constraints.stream()
                .filter(constraint -> constraint.rule().isForbidden())
                .filter(constraint -> constraint.blocked().intersects(direct))
                .map(constraint -> constraint.type() + ":" + constraint.id())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
    }

    RoutePath find(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            RoutePreference preference) {
        return find(
                start,
                end,
                diameter,
                environment,
                exemptFeatureIds,
                preference,
                Collections.emptyList());
    }

    /** Добавляет нормальный ввод, проверяя и тарифицируя весь префикс по остальным ограничениям. */
    RoutePath withCheckedTerminalPrefix(OfficialRouteGeometryRules.NormalEgress egress, RoutePath outside,
            int diameter, OfficialRoutingEnvironment environment) {
        return withCheckedTerminalPrefix(egress, outside, diameter, environment, Set.of(), List.of());
    }

    /** Сеточный порт — не существующая врезка: чужие отступы не ослабляются ни на одном конце. */
    RoutePath withCheckedCorridorTerminalPrefix(OfficialRouteGeometryRules.NormalEgress egress,
            RoutePath outside, int diameter, OfficialRoutingEnvironment environment) {
        RoutePath candidate = withCheckedTerminalPrefix(egress, outside, diameter, environment);
        if (candidate == null) return null;
        Envelope bounds = new Envelope();
        candidate.coordinates().forEach(bounds::expandToInclude);
        List<Constraint> constraints = environment.corridorConstraints(diameter, bounds).stream()
                .filter(constraint -> !egress.exempts(constraint))
                .collect(java.util.stream.Collectors.toList());
        return rules.lineAllowed(rules.line(candidate.coordinates()), rules.index(constraints)) ? candidate : null;
    }

    /** Льгота собственного ввода не освобождает наружную трассу от проверки препятствий. */
    RoutePath withCheckedTerminalPrefix(OfficialRouteGeometryRules.NormalEgress egress, RoutePath outside,
            int diameter, OfficialRoutingEnvironment environment, Set<String> exemptions,
            List<LineString> acceptedRoutes) {
        if (outside.coordinates().size() < 2 || outside.coordinates().get(0).distance(egress.exit()) > 0.001) return null;
        List<Coordinate> coordinates = new ArrayList<>();
        coordinates.add(egress.start());
        coordinates.addAll(outside.coordinates());
        org.locationtech.jts.geom.Envelope bounds = new org.locationtech.jts.geom.Envelope();
        coordinates.forEach(bounds::expandToInclude);
        List<Constraint> constraints = environment.corridorConstraints(diameter, bounds);
        Coordinate end = coordinates.get(coordinates.size() - 1);
        List<Constraint> terminalConstraints = rules.applicableConstraints(
                constraints, exemptions, egress.start(), end).stream()
                .filter(constraint -> !egress.exempts(constraint))
                .collect(java.util.stream.Collectors.toList());
        List<Constraint> avoidance = rules.applicableConstraints(rules.routeAvoidanceConstraints(acceptedRoutes),
                Set.of(), egress.start(), end);
        terminalConstraints.addAll(avoidance);
        RoutePath candidate = path(coordinates, constraints);
        LineString line = rules.line(candidate.coordinates());
        List<Coordinate> rounded = candidate.coordinates();
        List<Constraint> outsideConstraints = new ArrayList<>(rules.applicableConstraints(constraints,
                exemptions, rounded.get(1), end));
        outsideConstraints.addAll(avoidance);
        ConstraintIndex terminalIndex = rules.index(terminalConstraints);
        return line.isSimple() && turnsAllowed(candidate.coordinates(), null)
                && rules.provisionalSegmentsAllowed(rules.line(rounded.subList(0, 2)), terminalIndex)
                && rules.provisionalSegmentsAllowed(rules.line(rounded.subList(1, rounded.size())),
                        rules.index(outsideConstraints))
                && rules.completeRoadCrossingsAllowed(line, terminalIndex)
                ? candidate : null;
    }

    /** Проверяет новый конечный подход целиком и заново размечает его тарифные участки. */
    RoutePath withCheckedTerminalSuffix(RoutePath outside, Coordinate end, int diameter,
            OfficialRoutingEnvironment environment, Set<String> exemptFeatureIds, List<LineString> acceptedRoutes) {
        List<Coordinate> coordinates = TerminalSuffixGeometry.append(outside.coordinates(), end);
        if (coordinates.isEmpty()) return null;
        Envelope bounds = new Envelope();
        coordinates.forEach(bounds::expandToInclude);
        Coordinate start = coordinates.get(0);
        List<Constraint> constraints = new ArrayList<>(rules.applicableConstraints(
                environment.corridorConstraints(diameter, bounds), exemptFeatureIds, start, end));
        constraints.addAll(rules.applicableConstraints(rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(), start, end));
        RoutePath candidate = path(coordinates, constraints);
        LineString line = rules.line(candidate.coordinates());
        return line.isSimple() && rules.lineAllowed(line, rules.index(constraints)) ? candidate : null;
    }

    boolean lineAllowed(
            List<Coordinate> coordinates,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes) {
        return lineAllowed(coordinates, diameter, environment, exemptFeatureIds, acceptedRoutes, null);
    }

    /** Только после отдельной проверки наружного префикса: свой ОКС не скрывает другие запреты ввода. */
    boolean terminalApproachAllowed(
            Coordinate adjacent, Coordinate endpoint, int diameter,
            OfficialRoutingEnvironment environment, Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes, String ownOksId) {
        return lineAllowed(List.of(adjacent, endpoint), diameter, environment, exemptFeatureIds,
                acceptedRoutes, constraint -> "oks".equals(constraint.type())
                        && java.util.Objects.requireNonNull(ownOksId).equals(constraint.id()));
    }

    boolean terminalApproachAllowed(
            Coordinate adjacent, Coordinate endpoint, int diameter,
            OfficialRoutingEnvironment environment, Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes, OfficialRouteGeometryRules.NormalEgress egress) {
        return lineAllowed(List.of(adjacent, endpoint), diameter, environment, exemptFeatureIds,
                acceptedRoutes, egress::exempts);
    }

    /** Сохранение готового ввода: локальные льготы проверяются по частям, road/tram — по целому ребру. */
    boolean terminalRouteAllowed(List<Coordinate> coordinates, int diameter,
            OfficialRoutingEnvironment environment, Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes, OfficialRouteGeometryRules.NormalEgress egress) {
        ensureNotCancelled();
        if (coordinates.size() < 2) return false;
        Coordinate start = coordinates.get(0), end = coordinates.get(coordinates.size() - 1);
        Coordinate adjacent = coordinates.get(coordinates.size() - 2);
        Envelope bounds = new Envelope();
        coordinates.forEach(bounds::expandToInclude);
        // Техническая вершина не является существующей врезкой и не ослабляет чужой отступ.
        List<Constraint> outside = new ArrayList<>(rules.applicableConstraints(
                environment.corridorConstraints(diameter, bounds), exemptFeatureIds, start, end));
        outside.addAll(rules.applicableConstraints(rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(), start, end));
        ConstraintIndex outsideIndex = rules.index(outside);
        ConstraintIndex terminalIndex = rules.index(outside.stream().filter(constraint -> !egress.exempts(constraint))
                .collect(java.util.stream.Collectors.toList()));
        if (rules.pointInsideForbiddenClearance(start, outsideIndex)
                || rules.pointInsideForbiddenClearance(adjacent, outsideIndex)
                || rules.pointInsideForbiddenClearance(end, terminalIndex)) return false;
        LineString complete = rules.line(coordinates);
        return complete.isSimple()
                && (coordinates.size() == 2 || rules.provisionalSegmentsAllowed(
                        rules.line(coordinates.subList(0, coordinates.size() - 1)), outsideIndex))
                && rules.provisionalSegmentsAllowed(rules.line(List.of(adjacent, end)), terminalIndex)
                // Угол входа направленный: не разворачиваем готовое ребро ради проверки ввода.
                && rules.completeRoadCrossingsAllowed(complete, outsideIndex);
    }

    private boolean lineAllowed(
            List<Coordinate> coordinates,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes,
            java.util.function.Predicate<Constraint> terminalExemption) {
        if (coordinates.size() < 2) {
            return false;
        }
        Coordinate start = coordinates.get(0);
        Coordinate end = coordinates.get(coordinates.size() - 1);
        List<Constraint> constraints = new ArrayList<>(environment.constraints(
                diameter, exemptFeatureIds, start, end));
        if (terminalExemption != null) {
            constraints.removeIf(terminalExemption);
        }
        constraints.addAll(rules.applicableConstraints(
                rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(),
                start,
                end));
        ConstraintIndex index = rules.index(constraints);
        return !rules.pointInsideForbiddenClearance(start, index)
                && !rules.pointInsideForbiddenClearance(end, index)
                && rules.lineAllowed(rules.line(coordinates), index);
    }

    RoutePath findAvoidingDepthConflicts(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            Set<String> failedUtilityIds,
            List<LineString> acceptedRoutes) {
        List<Constraint> additional = environment.depthAvoidanceConstraints(failedUtilityIds);
        return find(
                start,
                end,
                diameter,
                environment,
                exemptFeatureIds,
                RoutePreference.SHORTEST,
                acceptedRoutes,
                additional);
    }

    /** Восстановительный поиск глубины тоже начинает с обязательного направления ввода. */
    RoutePath findAfterAvoidingDepthConflicts(Coordinate previous, Coordinate start, Coordinate end,
            int diameter, OfficialRoutingEnvironment environment, Set<String> exemptions,
            Set<String> failedUtilityIds, List<LineString> acceptedRoutes) {
        requireHeading(previous, start);
        return findUncached(start, end, diameter, environment, exemptions, RoutePreference.SHORTEST,
                acceptedRoutes, environment.depthAvoidanceConstraints(failedUtilityIds), new Coordinate(previous));
    }

    RoutePath find(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes) {
        return find(
                start,
                end,
                diameter,
                environment,
                exemptFeatureIds,
                preference,
                acceptedRoutes,
                Collections.emptyList());
    }

    private RoutePath find(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes,
            List<Constraint> additionalConstraints) {
        String key = routeCacheKey(
                start,
                end,
                diameter,
                exemptFeatureIds,
                preference,
                acceptedRoutes,
                additionalConstraints);
        return environment.cachedRoute(key, () -> findUncached(
                start, end, diameter, environment, exemptFeatureIds, preference,
                acceptedRoutes, additionalConstraints));
    }

    private RoutePath findUncached(
            Coordinate start,
            Coordinate end,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes,
            List<Constraint> additionalConstraints) {
        return findUncached(start, end, diameter, environment, exemptFeatureIds, preference,
                acceptedRoutes, additionalConstraints, null);
    }

    private RoutePath findUncached(
            Coordinate start, Coordinate end, int diameter, OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds, RoutePreference preference, List<LineString> acceptedRoutes,
            List<Constraint> additionalConstraints, Coordinate previous) {
        ensureNotCancelled();
        List<Constraint> constraints = searchConstraints(
                start, end, previous, diameter, environment, exemptFeatureIds);
        constraints.addAll(rules.applicableConstraints(
                rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(),
                start,
                end));
        constraints.addAll(rules.applicableConstraints(
                additionalConstraints,
                exemptFeatureIds,
                start,
                end));
        ConstraintIndex constraintIndex = rules.index(constraints);
        if (rules.pointInsideForbiddenClearance(start, constraintIndex)
                || rules.pointInsideForbiddenClearance(end, constraintIndex)) {
            return null;
        }
        if (rules.segmentAllowed(start, end, constraintIndex)) {
            RoutePath direct = headingCheckedPath(List.of(start, end), constraints, constraintIndex, previous);
            if (direct != null) return direct;
        }
        if (preference == RoutePreference.ENGINEERING) {
            RoutePath dogleg = directEngineeringDogleg(start, end, constraintIndex, constraints, previous);
            if (dogleg != null) {
                return dogleg;
            }
        }
        RoutePath specialCrossing = directSpecialCrossing(start, end, constraintIndex, constraints, previous);
        if (specialCrossing != null) return specialCrossing;
        // Карманы восстанавливают отсутствующий путь, но не заменяют уже допустимый hull-маршрут:
        // обычный коридор 200/600 м имеет приоритет над карманом в коридоре 75 м.
        List<List<Coordinate>> ordinaryGraphs = new ArrayList<>(CORRIDOR_EXPANSIONS.length);
        NavigationObstaclePreparation preparation = new NavigationObstaclePreparation(
                NAVIGATION_MARGIN_M, this::navigationCoordinates);
        for (boolean includePockets : new boolean[] {false, true}) {
            for (int corridor = 0; corridor < CORRIDOR_EXPANSIONS.length; corridor++) {
                double expansion = CORRIDOR_EXPANSIONS[corridor];
                List<Coordinate> nodes = navigationNodes(start, end, constraintIndex, expansion, includePockets, preparation);
                if (previous != null) nodes = headingNavigationNodes(nodes, previous, start, end);
                ensureNotCancelled();
                if (!includePockets) {
                    ordinaryGraphs.add(nodes);
                } else if (sameOrderedNavigationNodes(ordinaryGraphs.get(corridor), nodes)) {
                    // При тех же узлах, ограничениях и предпочтении повторится прежний отказ.
                    // Храним только три графа текущего вызова, а не результаты других расчётов.
                    continue;
                }
                SearchResult search = shortestPath(nodes, constraintIndex, preference, start, end, previous);
                environment.recordVisibilitySearch(nodes.size(), expansion, search.evaluatedPairCount, search.rejectedTurns);
                if (!search.coordinates.isEmpty()) {
                    List<Coordinate> normalized = normalize(search.coordinates, constraintIndex, previous);
                    List<Coordinate> constructible = snapConstructibleCorners(
                            normalized, constraintIndex, preference, previous);
                    RoutePath checked = headingCheckedPath(constructible, constraints, constraintIndex, previous);
                    if (checked != null) return checked;
                    // Не теряем найденный допустимый путь, если округление нового shortcut его испортило.
                    RoutePath control = headingCheckedPath(search.coordinates, constraints, constraintIndex, previous);
                    if (control != null) return control;
                }
            }
        }
        return null;
    }

    /**
     * Avoids building three visibility graphs when the direct line is blocked only by a shallow
     * road/tram crossing. Perpendicular portals are still validated against every constraint, so
     * any building, clearance or interacting crossing falls back to the complete search.
     */
    private RoutePath directSpecialCrossing(
            Coordinate start,
            Coordinate end,
            ConstraintIndex constraintIndex,
            List<Constraint> constraints,
            Coordinate previous) {
        LineString directLine = rules.line(List.of(start, end));
        Envelope corridor = directLine.getEnvelopeInternal();
        List<Coordinate> portals = new ArrayList<>();
        for (Constraint constraint : constraintIndex.query(corridor)) {
            if (constraint.rule().isForbidden()) {
                continue;
            }
            addSpecialCrossingPortals(portals, constraint, directLine, corridor);
        }
        if (portals.isEmpty()) {
            return null;
        }
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double denominator = dx * dx + dy * dy;
        portals.sort(Comparator
                .comparingDouble((Coordinate point) ->
                        ((point.x - start.x) * dx + (point.y - start.y) * dy) / denominator)
                .thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.y));
        List<Coordinate> candidate = new ArrayList<>();
        candidate.add(new Coordinate(start));
        for (Coordinate portal : portals) {
            if (candidate.get(candidate.size() - 1).distance(portal) > OfficialRouteGeometryRules.EPSILON_M) {
                candidate.add(new Coordinate(portal));
            }
        }
        candidate.add(new Coordinate(end));
        return headingCheckedPath(candidate, constraints, constraintIndex, previous);
    }

    /** Точное сравнение без округления; порядок сохраняет индексы и разрешение равенств поиска. */
    private boolean sameOrderedNavigationNodes(List<Coordinate> first, List<Coordinate> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            Coordinate left = first.get(index);
            Coordinate right = second.get(index);
            if (Double.doubleToLongBits(left.getX()) != Double.doubleToLongBits(right.getX())
                    || Double.doubleToLongBits(left.getY()) != Double.doubleToLongBits(right.getY())
                    || Double.doubleToLongBits(left.getZ()) != Double.doubleToLongBits(right.getZ())
                    || Double.doubleToLongBits(left.getM()) != Double.doubleToLongBits(right.getM())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tries a single constructible elbow before entering the visibility graph. A legal 90..135
     * degree dogleg is preferable to an obstacle-hugging path that overshoots the destination and
     * returns through a near-zero-degree hairpin.
     */
    private RoutePath directEngineeringDogleg(
            Coordinate start,
            Coordinate end,
            ConstraintIndex constraints,
            List<Constraint> sourceConstraints,
            Coordinate previous) {
        double directLength = start.distance(end);
        Coordinate best = null;
        double bestLength = Double.POSITIVE_INFINITY;
        for (Coordinate candidate : constructibleCornerCandidates(
                start, end, RoutePreference.ENGINEERING)) {
            if (candidate.distance(start) <= OfficialRouteGeometryRules.EPSILON_M
                    || candidate.distance(end) <= OfficialRouteGeometryRules.EPSILON_M
                    || previous != null && !roundedTurnAllowed(previous, start, candidate)
                    || !rules.segmentAllowed(start, candidate, constraints)
                    || !rules.segmentAllowed(candidate, end, constraints)) {
                continue;
            }
            double internalAngle = internalAngleDegrees(start, candidate, end);
            double candidateLength = start.distance(candidate) + candidate.distance(end);
            if (internalAngle + 0.5 < EngineeringRouteEvaluator.MIN_INTERNAL_ANGLE_DEGREES
                    || internalAngle > EngineeringRouteEvaluator.MAX_INTERNAL_ANGLE_DEGREES + 0.5
                    || candidateLength > directLength * 1.42
                    || candidateLength >= bestLength) {
                continue;
            }
            best = candidate;
            bestLength = candidateLength;
        }
        return best == null ? null : headingCheckedPath(List.of(start, best, end), sourceConstraints, constraints, previous);
    }

    private double internalAngleDegrees(
            Coordinate before,
            Coordinate at,
            Coordinate after) {
        double ax = before.x - at.x;
        double ay = before.y - at.y;
        double bx = after.x - at.x;
        double by = after.y - at.y;
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator <= 1e-9) {
            return 180.0;
        }
        double cosine = Math.max(-1.0, Math.min(1.0, (ax * bx + ay * by) / denominator));
        return Math.toDegrees(Math.acos(cosine));
    }

    private String routeCacheKey(
            Coordinate start,
            Coordinate end,
            int diameter,
            Set<String> exemptFeatureIds,
            RoutePreference preference,
            List<LineString> acceptedRoutes,
            List<Constraint> additionalConstraints) {
        String exemptions = exemptFeatureIds.stream().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return Math.round(start.x * 1000.0) + ":" + Math.round(start.y * 1000.0)
                + ">" + Math.round(end.x * 1000.0) + ":" + Math.round(end.y * 1000.0)
                + "|" + diameter + "|" + preference + "|" + exemptions
                + "|routes=" + geometrySetSignature(acceptedRoutes)
                + "|constraints=" + constraintSetSignature(additionalConstraints);
    }

    /**
     * Includes dynamic tree geometry in the cache identity. Repeated portfolio and engineering
     * probes often ask the same constrained routing question; caching it is safe only when the
     * complete avoidance context participates in the key.
     */
    private String geometrySetSignature(List<? extends Geometry> geometries) {
        return geometries.stream()
                .map(this::geometrySignature)
                .sorted()
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private String constraintSetSignature(List<Constraint> constraints) {
        return constraints.stream()
                .map(constraint -> constraint.id() + ":" + constraint.type()
                        + ":" + geometrySignature(constraint.source())
                        + ":" + geometrySignature(constraint.blocked()))
                .sorted()
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private String geometrySignature(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return "-";
        }
        StringBuilder result = new StringBuilder();
        for (Coordinate coordinate : geometry.getCoordinates()) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(Math.round(coordinate.x * 1000.0))
                    .append(':')
                    .append(Math.round(coordinate.y * 1000.0));
        }
        return result.toString();
    }

    RoutePath regularize(
            List<Coordinate> coordinates,
            int diameter,
            OfficialRoutingEnvironment environment,
            Set<String> exemptFeatureIds,
            List<LineString> acceptedRoutes) {
        return regularizeAfter(null, coordinates, diameter, environment, exemptFeatureIds, acceptedRoutes);
    }

    /** Упрощает наружный путь, сохраняя обязательный первый поворот после ввода. */
    RoutePath regularizeAfter(Coordinate previous, List<Coordinate> coordinates, int diameter,
            OfficialRoutingEnvironment environment, Set<String> exemptFeatureIds, List<LineString> acceptedRoutes) {
        if (coordinates.size() < 2) {
            return null;
        }
        if (previous != null) requireHeading(previous, coordinates.get(0));
        Coordinate start = coordinates.get(0);
        Coordinate end = coordinates.get(coordinates.size() - 1);
        List<Constraint> constraints = searchConstraints(
                start, end, previous, diameter, environment, exemptFeatureIds);
        constraints.addAll(rules.applicableConstraints(
                rules.routeAvoidanceConstraints(acceptedRoutes),
                Collections.emptySet(),
                start,
                end));
        ConstraintIndex constraintIndex = rules.index(constraints);
        List<Coordinate> normalized = normalize(coordinates, constraintIndex, previous);
        List<Coordinate> constructible = snapConstructibleCorners(
                normalized, constraintIndex, RoutePreference.ENGINEERING, previous);
        return headingCheckedPath(constructible, constraints, constraintIndex, previous);
    }

    /** Пространственное окно включает реальный ввод; льготы концов применяются только к наружной части. */
    private List<Constraint> searchConstraints(Coordinate start, Coordinate end, Coordinate previous,
            int diameter, OfficialRoutingEnvironment environment, Set<String> exemptions) {
        if (previous == null) return new ArrayList<>(environment.constraints(diameter, exemptions, start, end));
        Envelope bounds = new Envelope(start, end);
        bounds.expandToInclude(previous);
        return new ArrayList<>(rules.applicableConstraints(
                environment.corridorConstraints(diameter, bounds), exemptions, start, end));
    }

    private void requireHeading(Coordinate previous, Coordinate start) {
        if (previous == null || start == null || !Double.isFinite(previous.x) || !Double.isFinite(previous.y)
                || !Double.isFinite(start.x) || !Double.isFinite(start.y)
                || new RouteCoordinate(previous.x, previous.y).toCoordinate()
                        .equals2D(new RouteCoordinate(start.x, start.y).toCoordinate())) {
            throw new IllegalArgumentException("Distinct finite heading coordinates required");
        }
    }

    private RoutePath headingCheckedPath(List<Coordinate> coordinates, List<Constraint> constraints,
            ConstraintIndex index, Coordinate previous) {
        RoutePath candidate = path(coordinates, constraints);
        LineString line = rules.line(candidate.coordinates());
        if (!line.isSimple()) return null;
        if (previous == null) return rules.lineAllowed(line, index) ? candidate : null;
        if (!rules.provisionalSegmentsAllowed(line, index)) return null;
        Coordinate roundedPrevious = new RouteCoordinate(previous.x, previous.y).toCoordinate();
        if (!turnsAllowed(candidate.coordinates(), roundedPrevious)) return null;
        List<Coordinate> complete = new ArrayList<>();
        complete.add(roundedPrevious);
        complete.addAll(candidate.coordinates());
        LineString completeLine = rules.line(complete);
        return completeLine.isSimple() && rules.completeRoadCrossingsAllowed(completeLine, index) ? candidate : null;
    }

    private boolean turnsAllowed(List<Coordinate> points, Coordinate previous) {
        if (points.size() < 2) return false;
        if (previous != null && !roundedTurnAllowed(previous, points.get(0), points.get(1))) return false;
        return OfficialRouteDeflectionRules.validatePolyline("candidate", points.stream()
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(java.util.stream.Collectors.toList()))
                .getIssues().isEmpty();
    }

    /** Небольшая локальная сетка даёт путь разворота и в свободном месте без вершин препятствий. */
    private List<Coordinate> headingNavigationNodes(List<Coordinate> nodes, Coordinate previous,
            Coordinate start, Coordinate end) {
        double length = previous.distance(start);
        double nx = (start.x - previous.x) / length, ny = (start.y - previous.y) / length;
        double u = (end.x - start.x) * nx + (end.y - start.y) * ny;
        double v = -(end.x - start.x) * ny + (end.y - start.y) * nx;
        double margin = EngineeringRouteEvaluator.MIN_BEND_SPACING_M + 0.02;
        List<Coordinate> result = new ArrayList<>(nodes);
        for (double along : new double[] {0, margin, u}) {
            for (double across : new double[] {0, v, -margin, margin, v - margin, v + margin}) {
                result.add(new Coordinate(start.x + along * nx - across * ny,
                        start.y + along * ny + across * nx));
            }
        }
        return deduplicate(result);
    }

    private RoutePath path(List<Coordinate> coordinates, List<Constraint> constraints) {
        List<Coordinate> rounded = coordinates.stream()
                .map(coordinate -> new RouteCoordinate(coordinate.x, coordinate.y).toCoordinate())
                .collect(java.util.stream.Collectors.toList());
        LineString line = rules.line(rounded);
        return new RoutePath(rounded, rules.sections(line, constraints), line.getLength());
    }

    private List<Coordinate> navigationNodes(
            Coordinate start,
            Coordinate end,
            ConstraintIndex constraints,
            double expansionM) {
        return navigationNodes(start, end, constraints, expansionM, true);
    }

    private List<Coordinate> navigationNodes(
            Coordinate start,
            Coordinate end,
            ConstraintIndex constraints,
            double expansionM,
            boolean includePockets) {
        return navigationNodes(start, end, constraints, expansionM, includePockets,
                new NavigationObstaclePreparation(NAVIGATION_MARGIN_M, this::navigationCoordinates));
    }

    private List<Coordinate> navigationNodes(
            Coordinate start, Coordinate end, ConstraintIndex constraints, double expansionM,
            boolean includePockets, NavigationObstaclePreparation preparation) {
        List<Coordinate> result = new ArrayList<>();
        result.add(new Coordinate(start));
        result.add(new Coordinate(end));
        Envelope corridor = new Envelope(start, end);
        corridor.expandBy(expansionM);
        LineString directLine = rules.line(List.of(start, end));
        for (Constraint constraint : constraints.query(corridor)) {
            if (!constraint.rule().isForbidden()) {
                addSpecialCrossingPortals(result, constraint, directLine, corridor);
                if (constraint.blocked() == null) continue;
            }
            if (!constraint.blocked().getEnvelopeInternal().intersects(corridor)
                    || !constraint.blocked().isWithinDistance(directLine, expansionM)) {
                continue;
            }
            NavigationObstaclePreparation.Obstacle prepared = preparation.prepare(constraint);
            Geometry bufferedBoundary = prepared.bufferedBoundary();
            Geometry navigationGeometry = prepared.hull();
            Coordinate[] coordinates = prepared.support();
            int uniqueCount = coordinates.length > 1 && coordinates[0].equals2D(coordinates[coordinates.length - 1])
                    ? coordinates.length - 1
                    : coordinates.length;
            for (int index = 0; index < uniqueCount; index++) {
                result.add(new Coordinate(coordinates[index]));
            }
            if (includePockets) {
                addPocketNavigationNodes(result, start, end, constraint, bufferedBoundary,
                        navigationGeometry, coordinates);
            }
        }
        return deduplicate(result);
    }

    /**
     * Возвращает выходы из вогнутого двора, скрытого выпуклой оболочкой; старые опорные точки сохраняются.
     * Ограничиваем только дополнение: ближайшие точки реального контура и проекции на рёбра оболочки.
     */
    private void addPocketNavigationNodes(List<Coordinate> result, Coordinate start, Coordinate end,
            Constraint constraint, Geometry bufferedBoundary, Geometry hull, Coordinate[] support) {
        Envelope hullEnvelope = hull.getEnvelopeInternal();
        if (!hullEnvelope.covers(start) && !hullEnvelope.covers(end)) return;
        // Сам по себе навигационный запас возле выпуклой стены не образует карман.
        if (hull.getArea() - bufferedBoundary.getArea()
                <= OfficialRouteGeometryRules.EPSILON_M * OfficialRouteGeometryRules.EPSILON_M) return;
        List<Coordinate> anchors = new ArrayList<>(2);
        for (Coordinate endpoint : new Coordinate[] {start, end}) {
            Geometry point = hull.getFactory().createPoint(endpoint);
            if (hull.covers(point) && !constraint.preparedBlocked().covers(point)) anchors.add(endpoint);
        }
        if (anchors.isEmpty()) return;
        Comparator<Coordinate> proximity = Comparator.comparingDouble((Coordinate point) -> {
            double distance = point.distance(anchors.get(0));
            return anchors.size() == 1 ? distance : Math.min(distance, point.distance(anchors.get(1)));
        }).thenComparingDouble(point -> point.x).thenComparingDouble(point -> point.y);
        PriorityQueue<Coordinate> nearest = new PriorityQueue<>(MAX_POCKET_VERTICES_PER_OBSTACLE,
                proximity.reversed());
        for (int part = 0; part < bufferedBoundary.getNumGeometries(); part++) {
            Geometry geometry = bufferedBoundary.getGeometryN(part);
            if (!(geometry instanceof Polygon)) continue;
            Polygon polygon = (Polygon) geometry;
            for (int ringIndex = -1; ringIndex < polygon.getNumInteriorRing(); ringIndex++) {
                CoordinateSequence ring = (ringIndex < 0 ? polygon.getExteriorRing()
                        : polygon.getInteriorRingN(ringIndex)).getCoordinateSequence();
                for (int i = 0; i + 1 < ring.size(); i++) {
                    if ((i & 255) == 0) ensureNotCancelled();
                    Coordinate point = ring.getCoordinateCopy(i);
                    if (nearest.size() < MAX_POCKET_VERTICES_PER_OBSTACLE) nearest.add(point);
                    else if (proximity.compare(point, nearest.peek()) < 0) {
                        nearest.poll(); nearest.add(point);
                    }
                }
            }
        }
        while (!nearest.isEmpty()) result.add(nearest.poll());
        // У глубокого U-дворика обе вершины края оболочки скрыты крыльями, но середина выхода видима.
        for (int i = 0; i + 1 < support.length; i++) {
            LineSegment edge = new LineSegment(support[i], support[i + 1]);
            for (Coordinate anchor : anchors) result.add(new Coordinate(edge.closestPoint(anchor)));
        }
    }

    /**
     * Adds a bounded perpendicular crossing through a polygonal road or tram restriction. Without
     * these two nodes a shallow direct crossing is rejected, but the visibility graph has no legal
     * place from which to enter and leave the carriageway at the required angle.
     */
    private void addSpecialCrossingPortals(
            List<Coordinate> result,
            Constraint constraint,
            LineString directLine,
            Envelope corridor) {
        if (constraint.rule().getMinimumCrossingAngleDegrees() == null
                || constraint.source().getDimension() != 2
                || !constraint.source().getEnvelopeInternal().intersects(corridor)
                || !constraint.source().intersects(directLine)) {
            return;
        }
        Geometry rectangle = new MinimumDiameter(constraint.source()).getMinimumRectangle();
        Coordinate[] rectangleCoordinates = rectangle.getCoordinates();
        LineSegment axis = null;
        for (int index = 0; index + 1 < rectangleCoordinates.length; index++) {
            LineSegment candidate = new LineSegment(rectangleCoordinates[index], rectangleCoordinates[index + 1]);
            if (axis == null || candidate.getLength() > axis.getLength()) {
                axis = candidate;
            }
        }
        if (axis == null || axis.getLength() <= OfficialRouteGeometryRules.EPSILON_M) {
            return;
        }
        Geometry intersection = constraint.source().intersection(directLine);
        Coordinate anchor = intersection.isEmpty()
                ? constraint.source().getCentroid().getCoordinate()
                : intersection.getCentroid().getCoordinate();
        double normalX = -(axis.p1.y - axis.p0.y) / axis.getLength();
        double normalY = (axis.p1.x - axis.p0.x) / axis.getLength();
        double anchorProjection = anchor.x * normalX + anchor.y * normalY;
        double minimumProjection = Double.POSITIVE_INFINITY;
        double maximumProjection = Double.NEGATIVE_INFINITY;
        for (Coordinate coordinate : constraint.source().getCoordinates()) {
            double projection = coordinate.x * normalX + coordinate.y * normalY;
            minimumProjection = Math.min(minimumProjection, projection);
            maximumProjection = Math.max(maximumProjection, projection);
        }
        // Повороты не должны попадать внутрь прямого protective special за краем дороги.
        double protective = constraint.rule().getSpecialExtensionM() == null ? 0
                : constraint.rule().getSpecialExtensionM().doubleValue();
        double margin = Math.max(protective, constraint.clearanceM()) + SPECIAL_CROSSING_PORTAL_MARGIN_M;
        double before = minimumProjection - anchorProjection - margin;
        double after = maximumProjection - anchorProjection + margin;
        result.add(new Coordinate(anchor.x + normalX * before, anchor.y + normalY * before));
        result.add(new Coordinate(anchor.x + normalX * after, anchor.y + normalY * after));
    }

    /**
     * Keeps every vertex for a small hull. Larger hulls are replaced by a circumscribed regular
     * polygon built from support lines. Unlike sampling every Nth original vertex, its adjacent
     * edges stay outside the blocked convex geometry and therefore remain usable navigation edges.
     */
    private Coordinate[] navigationCoordinates(Geometry convexGeometry) {
        Coordinate[] coordinates = convexGeometry.getCoordinates();
        int uniqueCount = coordinates.length > 1 && coordinates[0].equals2D(coordinates[coordinates.length - 1])
                ? coordinates.length - 1
                : coordinates.length;
        if (uniqueCount <= MAX_VERTICES_PER_OBSTACLE) {
            return coordinates;
        }
        double[] normalX = new double[MAX_VERTICES_PER_OBSTACLE];
        double[] normalY = new double[MAX_VERTICES_PER_OBSTACLE];
        double[] support = new double[MAX_VERTICES_PER_OBSTACLE];
        java.util.Arrays.fill(support, Double.NEGATIVE_INFINITY);
        for (int index = 0; index < MAX_VERTICES_PER_OBSTACLE; index++) {
            double angle = 2.0 * Math.PI * index / MAX_VERTICES_PER_OBSTACLE;
            normalX[index] = Math.cos(angle);
            normalY[index] = Math.sin(angle);
            for (int coordinateIndex = 0; coordinateIndex < uniqueCount; coordinateIndex++) {
                Coordinate coordinate = coordinates[coordinateIndex];
                support[index] = Math.max(
                        support[index],
                        normalX[index] * coordinate.x + normalY[index] * coordinate.y);
            }
        }
        Coordinate[] result = new Coordinate[MAX_VERTICES_PER_OBSTACLE + 1];
        for (int index = 0; index < MAX_VERTICES_PER_OBSTACLE; index++) {
            int next = (index + 1) % MAX_VERTICES_PER_OBSTACLE;
            double determinant = normalX[index] * normalY[next] - normalY[index] * normalX[next];
            result[index] = new Coordinate(
                    (support[index] * normalY[next] - normalY[index] * support[next]) / determinant,
                    (normalX[index] * support[next] - support[index] * normalX[next]) / determinant);
        }
        result[MAX_VERTICES_PER_OBSTACLE] = new Coordinate(result[0]);
        return result;
    }

    private SearchResult shortestPath(
            List<Coordinate> nodes,
            ConstraintIndex constraints,
            RoutePreference preference,
            Coordinate start,
            Coordinate end) {
        return shortestPath(nodes, constraints, preference, start, end, null);
    }

    private SearchResult shortestPath(List<Coordinate> nodes, ConstraintIndex constraints,
            RoutePreference preference, Coordinate start, Coordinate end, Coordinate previous) {
        ensureNotCancelled();
        if (previous != null) {
            // Видимость должна проверять ту же миллиметровую геометрию, что попадёт в экспорт.
            // Иначе все повторные поиски снова выбирают более дешёвое, но недопустимое после округления ребро.
            nodes = nodes.stream().map(p -> new RouteCoordinate(p.x, p.y).toCoordinate())
                    .collect(java.util.stream.Collectors.toList());
        }
        int size = nodes.size();
        if (size < 2) {
            return new SearchResult(Collections.emptyList(), 0);
        }
        boolean[] blockedNodes = new boolean[size];
        double[] millimetresX = new double[size];
        double[] millimetresY = new double[size];
        for (int node = 0; node < size; node++) {
            ensureNotCancelled();
            blockedNodes[node] = rules.pointInsideForbiddenClearance(nodes.get(node), constraints);
            RouteCoordinate rounded = new RouteCoordinate(nodes.get(node).x, nodes.get(node).y);
            // Целые миллиметры устраняют вычитание близких больших UTM-ординат в каждом переходе.
            millimetresX[node] = rounded.getXM().movePointRight(3).doubleValue();
            millimetresY[node] = rounded.getYM().movePointRight(3).doubleValue();
        }
        if (blockedNodes[0] || blockedNodes[1]) {
            return new SearchResult(Collections.emptyList(), 0);
        }
        RouteCoordinate roundedPrevious = previous == null ? null : new RouteCoordinate(previous.x, previous.y);
        double initialX = previous == null ? 0
                : (millimetresX[0] - roundedPrevious.getXM().movePointRight(3).doubleValue()) / 1000.0;
        double initialY = previous == null ? 0
                : (millimetresY[0] - roundedPrevious.getYM().movePointRight(3).doubleValue()) / 1000.0;
        VisibilityCache visibility = new VisibilityCache(size, constraints.hasRoadCrossings());
        // Несвязность геометрического графа запрещает любой направленный путь. Проверяем
        // меньший фронт с двух концов, прежде чем раскрывать дорогие состояния направлений.
        if (!visibility.connectsEndpoints(nodes, constraints, blockedNodes)) {
            return new SearchResult(Collections.emptyList(), visibility.evaluatedPairCount);
        }
        SearchStates states = SearchStates.create(size);
        long startState = stateKey(-1, 0);
        states.improve(startState, 0.0, -1L);
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator
                .comparingDouble((State state) -> state.priority)
                .thenComparingDouble(state -> state.cost)
                .thenComparingInt(state -> state.node)
                .thenComparingInt(state -> state.previous));
        queue.add(new State(-1, 0, 0.0, heuristic(nodes.get(0), end)));
        State targetState = null;
        long rejectedTurns = 0;
        while (!queue.isEmpty()) {
            ensureNotCancelled();
            State state = queue.poll();
            long currentState = stateKey(state.previous, state.node);
            if (!states.visit(currentState)) {
                continue;
            }
            if (state.node == 1) {
                targetState = state;
                break;
            }
            Coordinate current = nodes.get(state.node);
            double incomingX = state.previous < 0 ? initialX : (millimetresX[state.node] - millimetresX[state.previous]) / 1000.0;
            double incomingY = state.previous < 0 ? initialY : (millimetresY[state.node] - millimetresY[state.previous]) / 1000.0;
            for (int next = 0; next < size; next++) {
                // segmentAllowed запрещает даже касание blocked-геометрии концом отрезка.
                // У такого узла нет допустимых рёбер; индексы сохраняем ради прежнего tie-breaking.
                if (next == state.node || next == state.previous || blockedNodes[next]
                        || visibility.isKnownBlocked(state.node, next)) {
                    continue;
                }
                Coordinate target = nodes.get(next);
                if ((state.previous >= 0 || previous != null) && !OfficialRouteDeflectionRules.allowsTurn(
                        incomingX, incomingY,
                        (millimetresX[next] - millimetresX[state.node]) / 1000.0,
                        (millimetresY[next] - millimetresY[state.node]) / 1000.0)) {
                    rejectedTurns++;
                    continue;
                }
                long nextState = stateKey(state.node, next);
                double currentBest = states.distance(nextState);
                double baseEdgeCost = current.distance(target)
                        * preferenceFactor(current, target, start, end, preference);
                // bendPenalty >= 1: нижняя граница не отсекает равенства в допуске 1e-9.
                if (state.cost + baseEdgeCost - currentBest > 1e-9) {
                    continue;
                }
                double candidate = state.cost + baseEdgeCost
                        * (state.previous < 0 && previous != null
                                ? bendPenalty(previous, current, target, preference)
                                : bendPenalty(nodes, state.previous, state.node, next, preference));
                long priorState = states.predecessor(nextState);
                if ((candidate + 1e-9 < currentBest
                        || (Math.abs(candidate - currentBest) <= 1e-9
                                && (priorState < 0 || currentState < priorState)))
                        && visibility.isVisible(state.node, next, nodes, constraints)
                        && (state.previous < 0 && previous == null || rules.specialTurnAllowed(
                                state.previous < 0 ? previous : nodes.get(state.previous), current, target, constraints))) {
                    states.improve(nextState, candidate, currentState);
                    queue.add(new State(
                            state.node,
                            next,
                            candidate,
                            candidate + heuristic(target, end)));
                }
            }
        }
        if (targetState == null) {
            return new SearchResult(Collections.emptyList(), visibility.evaluatedPairCount, rejectedTurns);
        }
        List<Coordinate> result = new ArrayList<>();
        long cursor = stateKey(targetState.previous, targetState.node);
        while (true) {
            int node = stateNode(cursor);
            result.add(new Coordinate(nodes.get(node)));
            if (cursor == startState) {
                break;
            }
            cursor = states.predecessor(cursor);
        }
        Collections.reverse(result);
        return new SearchResult(result, visibility.evaluatedPairCount, rejectedTurns);
    }

    private double heuristic(Coordinate coordinate, Coordinate end) {
        return MINIMUM_PREFERENCE_FACTOR * coordinate.distance(end);
    }

    private void ensureNotCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Route calculation was cancelled");
        }
    }

    private long stateKey(int previous, int node) {
        return ((long) (previous + 1) << 32) | (node & 0xffffffffL);
    }

    private int stateNode(long state) {
        return (int) state;
    }

    double bendPenalty(List<Coordinate> nodes, int previous, int current, int next) {
        return bendPenalty(nodes, previous, current, next, RoutePreference.SHORTEST);
    }

    private double bendPenalty(
            List<Coordinate> nodes,
            int previous,
            int current,
            int next,
            RoutePreference preference) {
        if (previous < 0) {
            return 1.0;
        }
        Coordinate before = nodes.get(previous);
        Coordinate at = nodes.get(current);
        Coordinate after = nodes.get(next);
        return bendPenalty(before, at, after, preference);
    }

    private double bendPenalty(Coordinate before, Coordinate at, Coordinate after, RoutePreference preference) {
        double ax = before.x - at.x;
        double ay = before.y - at.y;
        double bx = after.x - at.x;
        double by = after.y - at.y;
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator <= 1e-9) {
            return 1.0;
        }
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
                (ax * bx + ay * by) / denominator))));
        double deflection = Math.abs(180.0 - angle);
        if (deflection <= 1.0) {
            return 1.0;
        }
        if (preference == RoutePreference.ENGINEERING) {
            // Expert constructability rule: an internal bend angle must be 90..135 degrees,
            // equivalently a 45..90 degree deflection. This remains a search preference here;
            // the portfolio evaluator applies it as a hard admission rule to two variants.
            if (angle >= EngineeringRouteEvaluator.MIN_INTERNAL_ANGLE_DEGREES
                    && angle <= EngineeringRouteEvaluator.MAX_INTERNAL_ANGLE_DEGREES) {
                return 1.001;
            }
            double rangeDeviation = angle < EngineeringRouteEvaluator.MIN_INTERNAL_ANGLE_DEGREES
                    ? EngineeringRouteEvaluator.MIN_INTERNAL_ANGLE_DEGREES - angle
                    : angle - EngineeringRouteEvaluator.MAX_INTERNAL_ANGLE_DEGREES;
            return 1.15 + Math.min(0.35, rangeDeviation / 90.0 * 0.35);
        }
        double deviation = Math.min(
                Math.abs(deflection - 45.0),
                Math.min(Math.abs(deflection), Math.abs(deflection - 90.0)));
        // This is a bounded route-search preference, not a construction tariff. Straight, 45° and
        // 90° turns are construction-friendly; another angle must be materially shorter to win.
        double normalizedDeviation = Math.min(1.0, deviation / 22.5);
        return 1.003 + 0.037 * normalizedDeviation * normalizedDeviation;
    }

    private double preferenceFactor(
            Coordinate edgeStart,
            Coordinate edgeEnd,
            Coordinate routeStart,
            Coordinate routeEnd,
            RoutePreference preference) {
        if (preference == RoutePreference.SHORTEST || preference == RoutePreference.ENGINEERING) {
            return 1.0;
        }
        double dx = routeEnd.x - routeStart.x;
        double dy = routeEnd.y - routeStart.y;
        double mx = (edgeStart.x + edgeEnd.x) / 2.0 - routeStart.x;
        double my = (edgeStart.y + edgeEnd.y) / 2.0 - routeStart.y;
        double cross = dx * my - dy * mx;
        boolean preferredSide = preference == RoutePreference.LEFT ? cross >= 0 : cross <= 0;
        return preferredSide ? 0.985 : 1.015;
    }

    private List<Coordinate> normalize(List<Coordinate> path, ConstraintIndex constraints) {
        return normalize(path, constraints, null);
    }

    private List<Coordinate> normalize(List<Coordinate> path, ConstraintIndex constraints, Coordinate previous) {
        path = distinctAdjacentPoints(path);
        if (path.size() < 2) return path;
        List<Coordinate> normalized = new ArrayList<>();
        int current = 0;
        normalized.add(new Coordinate(path.get(0)));
        while (current < path.size() - 1) {
            ensureNotCancelled();
            int next = path.size() - 1;
            while (next > current + 1
                    && (!shortcutPreservesTurns(normalized, path, next, previous)
                            || !rules.segmentAllowed(path.get(current), path.get(next), constraints))) {
                ensureNotCancelled();
                next--;
            }
            normalized.add(new Coordinate(path.get(next)));
            current = next;
        }
        return normalized;
    }

    /** Выравнивает угол только при сохранении допустимых соседних поворотов и отступов. */
    private List<Coordinate> snapConstructibleCorners(
            List<Coordinate> coordinates,
            ConstraintIndex constraints,
            RoutePreference preference) {
        return snapConstructibleCorners(coordinates, constraints, preference, null);
    }

    private List<Coordinate> snapConstructibleCorners(List<Coordinate> coordinates, ConstraintIndex constraints,
            RoutePreference preference, Coordinate previous) {
        coordinates = distinctAdjacentPoints(coordinates);
        if (coordinates.size() < 3) {
            return coordinates;
        }
        List<Coordinate> result = coordinates.stream()
                .map(Coordinate::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (int index = 1; index + 1 < result.size(); index++) {
            ensureNotCancelled();
            Coordinate before = result.get(index - 1);
            Coordinate current = result.get(index);
            Coordinate after = result.get(index + 1);
            double currentLength = before.distance(current) + current.distance(after);
            double bestPenalty = constructibleAngleDeviation(before, current, after);
            Coordinate best = current;
            for (Coordinate candidate : constructibleCornerCandidates(before, after, preference)) {
                ensureNotCancelled();
                if (candidate.distance(before) <= OfficialRouteGeometryRules.EPSILON_M
                        || candidate.distance(after) <= OfficialRouteGeometryRules.EPSILON_M
                        || !cornerPreservesTurns(result, index, candidate)
                        || index == 1 && previous != null && !roundedTurnAllowed(previous, before, candidate)) {
                    continue;
                }
                double candidateLength = before.distance(candidate) + candidate.distance(after);
                double maximumLengthFactor = preference == RoutePreference.ENGINEERING ? 1.42 : 1.05;
                if (candidateLength > currentLength * maximumLengthFactor
                        || !rules.segmentAllowed(before, candidate, constraints)
                        || !rules.segmentAllowed(candidate, after, constraints)) {
                    continue;
                }
                double candidatePenalty = constructibleAngleDeviation(before, candidate, after);
                if (candidatePenalty + 1e-9 < bestPenalty
                        || (Math.abs(candidatePenalty - bestPenalty) <= 1e-9
                                && candidateLength + 1e-9
                                        < before.distance(best) + best.distance(after))) {
                    best = candidate;
                    bestPenalty = candidatePenalty;
                }
            }
            result.set(index, new Coordinate(best));
        }
        // Повторная далёкая вершина не даёт права соединить соседние части новым shortcut.
        return distinctAdjacentPoints(result);
    }

    /** Проверяем оба конца shortcut; правый угол остаётся допустимым при следующем шаге обхода. */
    private boolean shortcutPreservesTurns(List<Coordinate> prefix, List<Coordinate> source, int next, Coordinate previous) {
        Coordinate at = prefix.get(prefix.size() - 1), target = source.get(next);
        return (prefix.size() < 2 ? previous == null || roundedTurnAllowed(previous, at, target)
                        : roundedTurnAllowed(prefix.get(prefix.size() - 2), at, target))
                && (next + 1 == source.size() || roundedTurnAllowed(at, target, source.get(next + 1)));
    }

    private boolean cornerPreservesTurns(List<Coordinate> points, int index, Coordinate candidate) {
        Coordinate before = points.get(index - 1), after = points.get(index + 1);
        return roundedTurnAllowed(before, candidate, after)
                && (index < 2 || roundedTurnAllowed(points.get(index - 2), before, candidate))
                && (index + 2 >= points.size() || roundedTurnAllowed(candidate, after, points.get(index + 2)));
    }

    private boolean roundedTurnAllowed(Coordinate before, Coordinate at, Coordinate after) {
        RouteCoordinate a = new RouteCoordinate(before.x, before.y);
        RouteCoordinate b = new RouteCoordinate(at.x, at.y);
        RouteCoordinate c = new RouteCoordinate(after.x, after.y);
        return OfficialRouteDeflectionRules.allowsTurn(
                b.getXM().subtract(a.getXM()).doubleValue(), b.getYM().subtract(a.getYM()).doubleValue(),
                c.getXM().subtract(b.getXM()).doubleValue(), c.getYM().subtract(b.getYM()).doubleValue());
    }

    /** Убирает только соседние точки, совпадающие в экспортной миллиметровой точности. */
    private List<Coordinate> distinctAdjacentPoints(List<Coordinate> points) {
        List<Coordinate> result = new ArrayList<>();
        Coordinate previousRounded = null;
        for (Coordinate point : points) {
            ensureNotCancelled();
            Coordinate rounded = new RouteCoordinate(point.x, point.y).toCoordinate();
            if (previousRounded == null || !previousRounded.equals2D(rounded)) {
                result.add(new Coordinate(point));
                previousRounded = rounded;
            }
        }
        return result;
    }

    private List<Coordinate> constructibleCornerCandidates(
            Coordinate start,
            Coordinate end,
            RoutePreference preference) {
        List<Coordinate> result = new ArrayList<>();
        result.add(new Coordinate(start.x, end.y));
        result.add(new Coordinate(end.x, start.y));
        // These orientation-independent elbows are deliberately restricted to the final expert
        // pass. Adding them to every visibility search multiplies an already dominant hot path.
        if (preference == RoutePreference.ENGINEERING) {
            addExactInternalAngleCandidates(result, start, end, 90.0);
            addExactInternalAngleCandidates(result, start, end, 105.0);
            addExactInternalAngleCandidates(result, start, end, 120.0);
            addExactInternalAngleCandidates(result, start, end, 135.0);
        }
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double signX = Math.copySign(1.0, dx == 0.0 ? 1.0 : dx);
        double signY = Math.copySign(1.0, dy == 0.0 ? 1.0 : dy);
        double absX = Math.abs(dx);
        double absY = Math.abs(dy);
        if (absX >= absY) {
            result.add(new Coordinate(start.x + signX * absY, end.y));
            result.add(new Coordinate(end.x - signX * absY, start.y));
        }
        if (absY >= absX) {
            result.add(new Coordinate(end.x, start.y + signY * absX));
            result.add(new Coordinate(start.x, end.y - signY * absX));
        }
        return deduplicate(result);
    }

    /** Adds both isosceles elbows for the requested internal angle around the endpoint chord. */
    private void addExactInternalAngleCandidates(
            List<Coordinate> result,
            Coordinate start,
            Coordinate end,
            double internalAngleDegrees) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double chord = Math.hypot(dx, dy);
        if (chord <= OfficialRouteGeometryRules.EPSILON_M) {
            return;
        }
        double offset = chord / (2.0 * Math.tan(Math.toRadians(internalAngleDegrees / 2.0)));
        double middleX = (start.x + end.x) / 2.0;
        double middleY = (start.y + end.y) / 2.0;
        double normalX = -dy / chord;
        double normalY = dx / chord;
        result.add(new Coordinate(middleX + normalX * offset, middleY + normalY * offset));
        result.add(new Coordinate(middleX - normalX * offset, middleY - normalY * offset));
    }

    private double constructibleAngleDeviation(
            Coordinate before,
            Coordinate at,
            Coordinate after) {
        double ax = before.x - at.x;
        double ay = before.y - at.y;
        double bx = after.x - at.x;
        double by = after.y - at.y;
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator <= 1e-9) {
            return 0.0;
        }
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
                (ax * bx + ay * by) / denominator))));
        double deflection = Math.abs(180.0 - angle);
        return Math.min(
                Math.abs(deflection - 45.0),
                Math.min(Math.abs(deflection), Math.abs(deflection - 90.0)));
    }

    private List<Coordinate> deduplicate(List<Coordinate> coordinates) {
        List<Coordinate> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Coordinate coordinate : coordinates) {
            String key = Math.round(coordinate.x * 1000.0) + ":" + Math.round(coordinate.y * 1000.0);
            if (keys.add(key)) {
                result.add(coordinate);
            }
        }
        return result;
    }

    /**
     * Хранит только состояния одного поиска. Умеренные графы используют ленивые примитивные
     * строки, крупные — прежнее разреженное хранение без квадратичного выделения памяти.
     */
    abstract static class SearchStates {
        static SearchStates create(int nodeCount) {
            long stateCount = ((long) nodeCount + 1) * nodeCount;
            return stateCount <= MAX_DENSE_SEARCH_STATES
                    ? new DenseSearchStates(nodeCount)
                    : new SparseSearchStates();
        }

        abstract double distance(long state);

        abstract long predecessor(long state);

        abstract boolean visit(long state);

        abstract void improve(long state, double cost, long predecessor);
    }

    private static final class DenseSearchStates extends SearchStates {
        private final int nodeCount;
        private final StateRow[] rows;

        private DenseSearchStates(int nodeCount) {
            this.nodeCount = nodeCount;
            this.rows = new StateRow[nodeCount + 1];
        }

        @Override
        double distance(long state) {
            StateRow row = rows[(int) (state >>> 32)];
            return row == null ? Double.POSITIVE_INFINITY : row.distance[(int) state];
        }

        @Override
        long predecessor(long state) {
            StateRow row = rows[(int) (state >>> 32)];
            return row == null ? -1L : row.predecessor[(int) state];
        }

        @Override
        boolean visit(long state) {
            StateRow row = row(state);
            int node = (int) state;
            boolean firstVisit = !row.visited[node];
            row.visited[node] = true;
            return firstVisit;
        }

        @Override
        void improve(long state, double cost, long predecessor) {
            StateRow row = row(state);
            int node = (int) state;
            row.distance[node] = cost;
            row.predecessor[node] = predecessor;
        }

        private StateRow row(long state) {
            int rowIndex = (int) (state >>> 32);
            if (rows[rowIndex] == null) {
                rows[rowIndex] = new StateRow(nodeCount);
            }
            return rows[rowIndex];
        }
    }

    private static final class StateRow {
        private final double[] distance;
        private final long[] predecessor;
        private final boolean[] visited;

        private StateRow(int nodeCount) {
            distance = new double[nodeCount];
            Arrays.fill(distance, Double.POSITIVE_INFINITY);
            predecessor = new long[nodeCount];
            Arrays.fill(predecessor, -1L);
            visited = new boolean[nodeCount];
        }
    }

    private static final class SparseSearchStates extends SearchStates {
        private final Map<Long, Double> distance = new HashMap<>();
        private final Map<Long, Long> predecessor = new HashMap<>();
        private final Set<Long> visited = new HashSet<>();

        @Override
        double distance(long state) {
            return distance.getOrDefault(state, Double.POSITIVE_INFINITY);
        }

        @Override
        long predecessor(long state) {
            return predecessor.getOrDefault(state, -1L);
        }

        @Override
        boolean visit(long state) {
            return visited.add(state);
        }

        @Override
        void improve(long state, double cost, long priorState) {
            distance.put(state, cost);
            predecessor.put(state, priorState);
        }
    }

    private static final class State {
        private final int previous;
        private final int node;
        private final double cost;
        private final double priority;

        private State(int previous, int node, double cost, double priority) {
            this.previous = previous;
            this.node = node;
            this.cost = cost;
            this.priority = priority;
        }
    }

    private static final class SearchResult {
        private final List<Coordinate> coordinates;
        private final long evaluatedPairCount;
        private final long rejectedTurns;

        private SearchResult(List<Coordinate> coordinates, long evaluatedPairCount) {
            this(coordinates, evaluatedPairCount, 0);
        }

        private SearchResult(List<Coordinate> coordinates, long evaluatedPairCount, long rejectedTurns) {
            this.coordinates = coordinates;
            this.evaluatedPairCount = evaluatedPairCount;
            this.rejectedTurns = rejectedTurns;
        }
    }

    private final class VisibilityCache {
        private final int nodeCount;
        private final byte[] values;
        private final byte[] reverseValues;
        private long evaluatedPairCount;

        private VisibilityCache(int nodeCount, boolean directed) {
            this.nodeCount = nodeCount;
            this.values = new byte[nodeCount * (nodeCount - 1) / 2];
            // У road/tram важна точка входа: обратное направление проверяется отдельно.
            this.reverseValues = directed ? new byte[values.length] : values;
        }

        private boolean isKnownBlocked(int first, int second) {
            return (first < second ? values : reverseValues)[index(first, second)] == 2;
        }

        private boolean connectsEndpoints(List<Coordinate> nodes, ConstraintIndex constraints, boolean[] blockedNodes) {
            boolean[] fromStart = new boolean[nodeCount], fromEnd = new boolean[nodeCount];
            ArrayDeque<Integer> startFront = new ArrayDeque<>(), endFront = new ArrayDeque<>();
            fromStart[0] = true;
            fromEnd[1] = true;
            startFront.add(0);
            endFront.add(1);
            while (!startFront.isEmpty() && !endFront.isEmpty()) {
                ensureNotCancelled();
                boolean forward = startFront.size() < endFront.size();
                ArrayDeque<Integer> front = forward ? startFront : endFront;
                boolean[] own = forward ? fromStart : fromEnd, other = forward ? fromEnd : fromStart;
                int current = front.removeFirst();
                for (int next = 0; next < nodeCount; next++) {
                    if (blockedNodes[next] || own[next]) continue;
                    if (forward ? isVisible(current, next, nodes, constraints)
                            : isVisible(next, current, nodes, constraints)) {
                        if (other[next]) return true;
                        own[next] = true;
                        front.addLast(next);
                    }
                }
            }
            return false;
        }

        private int index(int first, int second) {
            int left = Math.min(first, second);
            int right = Math.max(first, second);
            return left * (2 * nodeCount - left - 1) / 2 + right - left - 1;
        }

        private boolean isVisible(
                int first,
                int second,
                List<Coordinate> nodes,
                ConstraintIndex constraints) {
            int left = Math.min(first, second);
            int right = Math.max(first, second);
            int index = index(left, right);
            byte[] direction = first < second ? values : reverseValues;
            byte cached = direction[index];
            if (cached == 0) {
                ensureNotCancelled();
                cached = rules.segmentAllowed(nodes.get(first), nodes.get(second), constraints)
                        ? (byte) 1
                        : (byte) 2;
                direction[index] = cached;
                evaluatedPairCount++;
            }
            return cached == 1;
        }
    }
}
