package ru.lct.heatroute.domain.routing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

final class OfficialRoutingEnvironment {
    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialRoutingEnvironment.class);
    private static final double WINDOW_MARGIN_M = 610.0;
    private final List<ImportedOfficialFeature> features;
    private final RoutingFeatureSource source;
    private final OfficialRouteGeometryRules rules;
    private final Map<Integer, List<Constraint>> baseByDiameter = new HashMap<>();
    private final Map<String, java.util.Optional<RoutePath>> routeCache = new HashMap<>();
    private long visibilitySearches;
    private long visibilityNodes;
    private long visibilityPairChecks;

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
        all.addAll(rules.baseConstraints(source.findInMetricWindow(window(start, end)), diameter));
        return rules.applicableConstraints(all, exemptFeatureIds, start, end);
    }

    List<Constraint> depthAvoidanceConstraints(Set<String> featureIds) {
        List<ImportedOfficialFeature> matching = new java.util.ArrayList<>(features);
        matching.addAll(source.findByFeatureIds(featureIds));
        return rules.depthAvoidanceConstraints(matching, featureIds);
    }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgress(int diameter, Coordinate point) {
        return rules.normalEgress(featuresInWindow(point, point), diameter, point);
    }

    java.util.Optional<OfficialRouteGeometryRules.NormalEgress> normalEgressTowards(
            int diameter, Coordinate point, Coordinate target) {
        return rules.normalEgressTowards(featuresInWindow(point, target), diameter, point, target);
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

    boolean pointInsideForbiddenClearance(int diameter, Coordinate point) {
        List<Constraint> all = new java.util.ArrayList<>(baseByDiameter.computeIfAbsent(
                diameter,
                ignored -> rules.baseConstraints(features, diameter)));
        all.addAll(rules.baseConstraints(source.findInMetricWindow(window(point, point)), diameter));
        return rules.pointInsideForbiddenClearance(point, rules.index(all));
    }

    List<ImportedOfficialFeature> featuresInWindow(Coordinate start, Coordinate end) {
        List<ImportedOfficialFeature> result = new java.util.ArrayList<>(features);
        result.addAll(source.findInMetricWindow(window(start, end)));
        return result;
    }

    void recordVisibilitySearch(int nodeCount, double corridorExpansionM, long evaluatedPairCount) {
        visibilitySearches++;
        visibilityNodes += nodeCount;
        visibilityPairChecks += evaluatedPairCount;
        if (visibilitySearches == 1 || visibilitySearches % 25 == 0) {
            LOGGER.info(
                    "Routing visibility profile searches={} total_nodes={} evaluated_pairs={} last_nodes={} last_evaluated_pairs={} corridor_m={}",
                    visibilitySearches,
                    visibilityNodes,
                    visibilityPairChecks,
                    nodeCount,
                    evaluatedPairCount,
                    corridorExpansionM);
        }
    }

    void logVisibilitySummary(String phase) {
        LOGGER.info(
                "Routing phase profile phase={} searches={} total_nodes={} evaluated_pairs={}",
                phase,
                visibilitySearches,
                visibilityNodes,
                visibilityPairChecks);
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
