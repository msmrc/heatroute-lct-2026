package ru.lct.heatroute.domain.topology;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.Geometry;

public class ImportedOfficialFeature {
    private final String featureId;
    private final String objectType;
    private final JsonNode attributes;
    private final Geometry metricGeometry;

    public ImportedOfficialFeature(
            String featureId,
            String objectType,
            JsonNode attributes,
            Geometry metricGeometry) {
        this.featureId = featureId;
        this.objectType = objectType;
        this.attributes = attributes;
        this.metricGeometry = metricGeometry;
    }

    public String getFeatureId() { return featureId; }
    public String getObjectType() { return objectType; }
    public JsonNode getAttributes() { return attributes; }
    public Geometry getMetricGeometry() { return metricGeometry; }
}
