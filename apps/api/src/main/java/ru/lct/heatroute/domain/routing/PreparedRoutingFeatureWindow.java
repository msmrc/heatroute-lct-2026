package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Владеет одной копией окна объектов и одним повторно используемым routing-окружением. */
public final class PreparedRoutingFeatureWindow {
    private final List<ImportedOfficialFeature> features;
    private final OfficialRoutingEnvironment environment;

    static PreparedRoutingFeatureWindow prepare(OfficialObstacleRouter router,
            Collection<ImportedOfficialFeature> supplied) {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(supplied, "relevantFeatures");
        List<ImportedOfficialFeature> frozen = new ArrayList<>(supplied.size());
        for (ImportedOfficialFeature feature : supplied) {
            Objects.requireNonNull(feature, "relevant feature");
            JsonNode attributes = feature.getAttributes();
            Geometry geometry = feature.getMetricGeometry();
            frozen.add(new ImportedOfficialFeature(feature.getFeatureId(), feature.getObjectType(),
                    attributes == null ? null : attributes.deepCopy(),
                    geometry == null ? null : geometry.copy()));
        }
        frozen.sort(Comparator.comparing(ImportedOfficialFeature::getFeatureId));
        List<ImportedOfficialFeature> owned = List.copyOf(frozen);
        return new PreparedRoutingFeatureWindow(owned, router.prepare(owned));
    }

    private PreparedRoutingFeatureWindow(List<ImportedOfficialFeature> features,
            OfficialRoutingEnvironment environment) {
        this.features = features;
        this.environment = environment;
    }

    List<ImportedOfficialFeature> features() { return features; }
    OfficialRoutingEnvironment environment() { return environment; }
    public int size() { return features.size(); }
}
