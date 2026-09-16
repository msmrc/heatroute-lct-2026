package ru.lct.heatroute.domain.routing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

final class OfficialRoutingEnvironment {
    private final List<ImportedOfficialFeature> features;
    private final OfficialRouteGeometryRules rules;
    private final Map<Integer, List<Constraint>> baseByDiameter = new HashMap<>();

    OfficialRoutingEnvironment(
            List<ImportedOfficialFeature> features,
            OfficialRouteGeometryRules rules) {
        this.features = features;
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
        return rules.applicableConstraints(base, exemptFeatureIds, start, end);
    }

    List<Constraint> depthAvoidanceConstraints(Set<String> featureIds) {
        return rules.depthAvoidanceConstraints(features, featureIds);
    }
}
