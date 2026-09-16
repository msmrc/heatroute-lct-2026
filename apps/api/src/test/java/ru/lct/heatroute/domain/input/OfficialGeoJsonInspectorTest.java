package ru.lct.heatroute.domain.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OfficialGeoJsonInspectorTest {
    private final OfficialGeoJsonInspector inspector = new OfficialGeoJsonInspector(new ObjectMapper());

    @Test
    void acceptsAllSevenOfficialInputTypes() {
        OfficialInputReport report = inspect("{\n"
                + "  \"type\": \"FeatureCollection\",\n"
                + "  \"features\": [\n"
                + feature("source", "src", "{\"type\":\"Point\",\"coordinates\":[37.60,55.75]}", "") + ",\n"
                + feature("heat_network", "net", line(), ",\"diameter\":150,\"flow_tph\":20,\"upstream_object_id\":\"src\"") + ",\n"
                + feature("heat_chamber", "ch", point(37.601, 55.751), ",\"diameter\":150,\"upstream_object_id\":\"net\"") + ",\n"
                + feature("oks_future", "oks", polygon(), ",\"flow_tph\":8.3,\"heat_load\":1.2") + ",\n"
                + feature("oks_connection_point", "cp", point(37.603, 55.753), ",\"oks_id\":\"oks\"") + ",\n"
                + feature("oks_existing", "old", polygon(), "") + ",\n"
                + feature("restriction", "road", polygon(), ",\"restriction_type\":\"road\"") + "\n"
                + "  ]\n"
                + "}");

        assertThat(report.isValid()).isTrue();
        assertThat(report.getFeatureCount()).isEqualTo(7);
        assertThat(report.getFeatureCounts()).containsEntry("oks_future", 1L);
        assertThat(report.getSha256()).hasSize(64);
        assertThat(report.getInputProfile()).isEqualTo(OfficialGeoJsonInspector.STRICT_PROFILE);
    }

    @Test
    void reportsMissingRequiredFieldAndGeometryMismatch() {
        OfficialInputReport report = inspect("{\"type\":\"FeatureCollection\",\"features\":["
                + feature("heat_network", "bad", point(37.6, 55.7), ",\"flow_tph\":1,\"upstream_object_id\":\"src\"")
                + "]}");

        assertThat(report.isValid()).isFalse();
        assertThat(report.getErrors()).extracting(OfficialInputError::getCode)
                .contains("INVALID_GEOMETRY_TYPE", "INVALID_DIAMETER");
    }

    @Test
    void rejectsUnknownRestrictionType() {
        OfficialInputReport report = inspect("{\"type\":\"FeatureCollection\",\"features\":["
                + feature("restriction", "x", polygon(), ",\"restriction_type\":\"forest\"")
                + "]}");

        assertThat(report.getErrors()).extracting(OfficialInputError::getCode)
                .contains("INVALID_GEOMETRY_TYPE", "UNSUPPORTED_RESTRICTION_TYPE");
    }

    @Test
    void acceptsOfficialEndToEndFixtureWithoutLoadingTheCollectionTree() {
        InputStream input = getClass().getResourceAsStream("/fixtures/official-minimal.geojson");
        assertThat(input).isNotNull();

        OfficialInputReport report = inspector.inspect(input);

        assertThat(report.isValid()).isTrue();
        assertThat(report.getFeatureCount()).isEqualTo(8);
        assertThat(report.getFeatureCounts()).containsEntry("oks_future", 2L);
    }

    @Test
    void acceptsProvidedDatasetShapeWithExplicitCompatibilityWarnings() {
        InputStream input = getClass().getResourceAsStream("/fixtures/provided-dataset-compatibility.geojson");
        assertThat(input).isNotNull();

        OfficialInputReport report = inspector.inspect(input);

        assertThat(report.isValid()).isTrue();
        assertThat(report.getInputProfile()).isEqualTo(OfficialGeoJsonInspector.PROVIDED_DATASET_PROFILE);
        assertThat(report.getFeatureCount()).isEqualTo(5);
        assertThat(report.getWarnings()).extracting(OfficialInputWarning::getCode)
                .contains(
                        "NUMERIC_ID_NORMALIZED",
                        "CONNECTION_POINT_AS_DEMAND",
                        "COMPATIBILITY_RESTRICTION_ALIAS",
                        "MISSING_CHAMBER_DIAMETER",
                        "MISSING_EXISTING_NETWORK_VALUE",
                        "MISSING_EXISTING_NETWORK_LINK");
    }

    @Test
    void rejectsDuplicateIdsUnknownReferencesAndFutureOksWithoutConnectionPoint() {
        InputStream input = getClass().getResourceAsStream("/fixtures/official-invalid-references.geojson");
        assertThat(input).isNotNull();

        OfficialInputReport report = inspector.inspect(input);

        assertThat(report.isValid()).isFalse();
        assertThat(report.getErrors()).extracting(OfficialInputError::getCode)
                .contains(
                        "DUPLICATE_FEATURE_ID",
                        "UNKNOWN_REFERENCE",
                        "MISSING_OKS_CONNECTION_POINT");
    }

    private OfficialInputReport inspect(String json) {
        return inspector.inspect(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String feature(String objectType, String id, String geometry, String extraProperties) {
        return "{\"type\":\"Feature\",\"geometry\":" + geometry
                + ",\"properties\":{\"id\":\"" + id + "\",\"object_type\":\""
                + objectType + "\"" + extraProperties + "}}";
    }

    private String point(double x, double y) {
        return "{\"type\":\"Point\",\"coordinates\":[" + x + "," + y + "]}";
    }

    private String line() {
        return "{\"type\":\"LineString\",\"coordinates\":[[37.60,55.75],[37.601,55.751]]}";
    }

    private String polygon() {
        return "{\"type\":\"Polygon\",\"coordinates\":[[[37.60,55.75],[37.61,55.75],[37.61,55.76],[37.60,55.76],[37.60,55.75]]]}";
    }
}
