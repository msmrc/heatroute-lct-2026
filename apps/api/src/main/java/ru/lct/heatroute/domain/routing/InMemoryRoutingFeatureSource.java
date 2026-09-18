package ru.lct.heatroute.domain.routing;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Compatibility source for planner unit tests and callers that already own feature objects. */
final class InMemoryRoutingFeatureSource implements RoutingFeatureSource {
    private final List<ImportedOfficialFeature> features;

    InMemoryRoutingFeatureSource(List<ImportedOfficialFeature> features) { this.features = features; }

    @Override public List<ImportedOfficialFeature> findInMetricWindow(Envelope window) {
        return features.stream().filter(feature -> feature.getMetricGeometry().getEnvelopeInternal().intersects(window))
                .collect(Collectors.toList());
    }

    @Override public List<ImportedOfficialFeature> findByFeatureIds(Set<String> ids) {
        return features.stream().filter(feature -> ids.contains(feature.getFeatureId())).collect(Collectors.toList());
    }
}
