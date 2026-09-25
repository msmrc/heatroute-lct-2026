package ru.lct.heatroute.domain.routing;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

final class OfficialRoutingEnvironment {
    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialRoutingEnvironment.class);
    private static final double WINDOW_MARGIN_M = 610.0;
    private final List<ImportedOfficialFeature> features;
    private final RoutingFeatureSource source;
    private final OfficialRouteGeometryRules rules;
    private final PreparedRoutingConstraints preparedWindowConstraints;
    private final Map<OfficialRouteValidator, OfficialRouteValidator.ValidationSession> validationSessions
            = new IdentityHashMap<>();
    private final ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex existingSupport;
    private final Map<Integer, List<Constraint>> baseByDiameter = new HashMap<>();
    private final Map<String, java.util.Optional<RoutePath>> routeCache = new HashMap<>();
    private long visibilitySearches;
    private long visibilityNodes;
    private long visibilityPairChecks;
    private long rejectedTurns;
    private final long startedNanos = System.nanoTime();
    private long phaseStartedNanos = startedNanos;

    OfficialRoutingEnvironment(
            List<ImportedOfficialFeature> features,
            OfficialRouteGeometryRules rules) {
        this(features, new InMemoryRoutingFeatureSource(java.util.Collections.emptyList()), rules);
    }

    OfficialRoutingEnvironment(
            List<ImportedOfficialFeature> features,
            RoutingFeatureSource source,
            OfficialRouteGeometryRules rules) {
        this.features = features;
        this.source = source;
        this.rules = rules;
        this.preparedWindowConstraints = new PreparedRoutingConstraints(rules);
        this.existingSupport = new ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex(features);
    }

    RouteNode verifiedRootSupport(RouteNode root) { return existingSupport.verified(root); }

    /** Сохраняет сессию точного экземпляра валидатора только на время этого окружения расчёта. */
    OfficialRouteValidator.ValidationSession validationFor(OfficialRouteValidator validator) {
        return validationSessions.computeIfAbsent(validator, OfficialRouteValidator::forCalculation);
    }

    List<Constraint> constraints(
            int diameter,
            Set<String> exemptFeatureIds,
            Coordinate start,
            Coordinate end) {
        List<Constraint> base = baseByDiameter.computeIfAbsent(
                diameter,
                ignored -> rules.baseConstraints(features, diameter));
        List<Constraint> all = new java.util.ArrayList<>(base);
        all.addAll(preparedWindowConstraints.prepare(source.findInMetricWindow(window(start, end)), diameter));
        return rules.applicableConstraints(all, exemptFeatureIds, start, end);
    }

    List<Constraint> depthAvoidanceConstraints(Set<String> featureIds) {
        List<ImportedOfficialFeature> matching = new java.util.ArrayList<>(features);
        matching.addAll(source.findByFeatureIds(featureIds));
        return rules.depthAvoidanceConstraints(matching, featureIds);
    }

    /** Исходные отступы всего локального коридора без послаблений для концов отдельных путей. */
    List<Constraint> corridorConstraints(int diameter, Envelope bounds) {
        Envelope query = new Envelope(bounds);
        query.expandBy(WINDOW_MARGIN_M);
        List<Constraint> all = new java.util.ArrayList<>(baseByDiameter.computeIfAbsent(
                diameter, ignored -> rules.baseConstraints(features, diameter)));
        all.addAll(preparedWindowConstraints.prepare(source.findInMetricWindow(query), diameter));
        return all;
    }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgress(int diameter, Coordinate point) {
        return rules.normalEgress(featuresInWindow(point, point), diameter, point);
    }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgress(
            int diameter, Coordinate point, RouteTraversal traversal) {
        return rules.normalEgress(featuresInWindow(point, point), diameter, point, traversal);
    }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgressTowards(
            int diameter, Coordinate point, Coordinate target) {
        return rules.normalEgressTowards(featuresInWindow(point, target), diameter, point, target);
    }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgressTowards(
            int diameter, Coordinate point, Coordinate target, RouteTraversal traversal) {
        return rules.normalEgressTowards(featuresInWindow(point, target), diameter, point, target, traversal);
    }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgressTowards(
            int diameter,
            Coordinate point,
            Coordinate target,
            double maximumAlternativeEgressExtraM) {
        return rules.normalEgressTowards(
                featuresInWindow(point, target),
                diameter,
                point,
                target,
                maximumAlternativeEgressExtraM);
    }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgressTowards(
            int diameter, Coordinate point, Coordinate target,
            double maximumAlternativeEgressExtraM, RouteTraversal traversal) {
        return rules.normalEgressTowards(featuresInWindow(point, target), diameter, point, target,
                maximumAlternativeEgressExtraM, traversal);
    }

    List<OfficialRouteGeometryRules.NormalEgress> normalEgressCandidates(
            int diameter,
            Coordinate point,
            Coordinate target,
            double maximumAlternativeEgressExtraM) {
        return rules.normalEgressCandidates(
                featuresInWindow(point, target),
                diameter,
                point,
                target,
                maximumAlternativeEgressExtraM);
    }

    List<OfficialRouteGeometryRules.NormalEgress> normalEgressCandidates(
            int diameter, Coordinate point, Coordinate target,
            double maximumAlternativeEgressExtraM, RouteTraversal traversal) {
        return rules.normalEgressCandidates(featuresInWindow(point, target), diameter, point, target,
                maximumAlternativeEgressExtraM, traversal);
    }

    boolean pointInsideForbiddenClearance(int diameter, Coordinate point) {
        List<Constraint> all = new java.util.ArrayList<>(baseByDiameter.computeIfAbsent(
                diameter,
                ignored -> rules.baseConstraints(features, diameter)));
        all.addAll(preparedWindowConstraints.prepare(source.findInMetricWindow(window(point, point)), diameter));
        return rules.pointInsideForbiddenClearance(point, rules.index(all));
    }

    /**
     * Подготавливает ограничения для серии точечных проверок одного ДУ без повторной буферизации.
     * Результат принадлежит вызывающей операции; исходное окно каждой точки сохраняется.
     */
    Predicate<Coordinate> preparePointClearance(int diameter, Envelope pointBounds) {
        if (pointBounds == null || pointBounds.isNull()) throw new IllegalArgumentException("Point bounds are required");
        Envelope bounds = new Envelope(pointBounds);
        Envelope query = new Envelope(bounds);
        query.expandBy(WINDOW_MARGIN_M);
        ConstraintIndex core = rules.index(baseByDiameter.computeIfAbsent(diameter,
                ignored -> rules.baseConstraints(features, diameter)));
        ConstraintIndex nearby = rules.index(preparedWindowConstraints.prepare(source.findInMetricWindow(query), diameter));
        GeometryFactory factory = new GeometryFactory();
        return point -> {
            if (!bounds.covers(point.x, point.y)) throw new IllegalArgumentException("Point outside prepared bounds");
            if (rules.pointInsideForbiddenClearance(point, core)) return true;
            Geometry geometry = factory.createPoint(point);
            Envelope pointWindow = window(point, point);
            for (Constraint constraint : nearby.query(geometry.getEnvelopeInternal())) {
                if (constraint.rule().isForbidden()
                        && constraint.source().getEnvelopeInternal().intersects(pointWindow)
                        && constraint.preparedBlocked().covers(geometry)) return true;
            }
            return false;
        };
    }

    List<ImportedOfficialFeature> featuresInWindow(Coordinate start, Coordinate end) {
        List<ImportedOfficialFeature> result = new java.util.ArrayList<>(features);
        result.addAll(source.findInMetricWindow(window(start, end)));
        return result;
    }

    List<org.locationtech.jts.geom.Geometry> buildingFootprints(Coordinate start, Coordinate end) {
        return featuresInWindow(start, end).stream().filter(rules::isBuildingFeature)
                .map(ImportedOfficialFeature::getMetricGeometry)
                .filter(geometry -> geometry != null && geometry.getDimension() == 2)
                .collect(java.util.stream.Collectors.toList());
    }

    void recordVisibilitySearch(int nodeCount, double corridorExpansionM, long evaluatedPairCount, long rejectedTurnCount) {
        visibilitySearches++;
        visibilityNodes += nodeCount;
        visibilityPairChecks += evaluatedPairCount;
        rejectedTurns += rejectedTurnCount;
        if (visibilitySearches == 1 || visibilitySearches % 25 == 0) {
            LOGGER.info(
                    "Routing visibility profile searches={} total_nodes={} evaluated_pairs={} angle_pruned={} last_nodes={} last_evaluated_pairs={} corridor_m={}",
                    visibilitySearches,
                    visibilityNodes,
                    visibilityPairChecks,
                    rejectedTurns,
                    nodeCount,
                    evaluatedPairCount,
                    corridorExpansionM);
        }
    }

    void logVisibilitySummary(String phase) {
        long now = System.nanoTime();
        LOGGER.info(
                "Routing phase profile phase={} searches={} total_nodes={} evaluated_pairs={} angle_pruned={} phase_ms={} elapsed_ms={}",
                phase,
                visibilitySearches,
                visibilityNodes,
                visibilityPairChecks,
                rejectedTurns,
                (now - phaseStartedNanos) / 1_000_000L,
                (now - startedNanos) / 1_000_000L);
        phaseStartedNanos = now;
    }

    RoutePath cachedRoute(String key, java.util.function.Supplier<RoutePath> calculation) {
        java.util.Optional<RoutePath> cached = routeCache.get(key);
        if (cached != null) return cached.orElse(null);
        RoutePath route = calculation.get();
        routeCache.put(key, java.util.Optional.ofNullable(route));
        return route;
    }

    private org.locationtech.jts.geom.Envelope window(Coordinate start, Coordinate end) {
        org.locationtech.jts.geom.Envelope result = new org.locationtech.jts.geom.Envelope(start, end);
        result.expandBy(WINDOW_MARGIN_M);
        return result;
    }
}
