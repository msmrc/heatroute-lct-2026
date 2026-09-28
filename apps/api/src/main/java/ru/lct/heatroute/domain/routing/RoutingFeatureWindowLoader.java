package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.locationtech.jts.geom.Envelope;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Loads one deterministic bounded feature window for catalog generation and exact evaluation. */
@Component
public final class RoutingFeatureWindowLoader {
    public List<ImportedOfficialFeature> load(
            RoutingProblemSnapshot problem,
            Collection<ImportedOfficialFeature> calculationCore,
            RoutingFeatureSource source) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(calculationCore, "calculationCore");
        Objects.requireNonNull(source, "source");
        Envelope window = bounds(problem);
        window.expandBy(OfficialRoutingEnvironment.WINDOW_MARGIN_M);

        Map<String, ImportedOfficialFeature> byId = new LinkedHashMap<>();
        addDistinct(byId, calculationCore);
        addDistinct(byId, source.findInMetricWindow(window));
        List<ImportedOfficialFeature> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing(ImportedOfficialFeature::getFeatureId));
        return List.copyOf(result);
    }

    private static Envelope bounds(RoutingProblemSnapshot problem) {
        Envelope result = new Envelope();
        problem.getDemands().forEach(demand -> result.expandToInclude(
                demand.getLocation().getXM(), demand.getLocation().getYM()));
        problem.getRoots().forEach(root -> result.expandToInclude(
                root.getLocation().getXM(), root.getLocation().getYM()));
        if (result.isNull()) {
            throw new IllegalArgumentException("Routing problem has no demand/root bounds");
        }
        return result;
    }

    private static void addDistinct(Map<String, ImportedOfficialFeature> target,
            Collection<ImportedOfficialFeature> supplied) {
        if (supplied == null) throw new IllegalArgumentException("Feature window is required");
        for (ImportedOfficialFeature feature : supplied) {
            Objects.requireNonNull(feature, "feature");
            ImportedOfficialFeature previous = target.putIfAbsent(feature.getFeatureId(), feature);
            if (previous != null && previous != feature) {
                throw new IllegalArgumentException(
                        "Feature window contains duplicate ID: " + feature.getFeatureId());
            }
        }
    }
}
