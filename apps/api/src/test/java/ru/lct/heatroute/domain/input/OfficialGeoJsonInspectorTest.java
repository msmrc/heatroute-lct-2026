package ru.lct.heatroute.domain.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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
        assertThat(report.getInputProfile()).isEqualTo(OfficialGeoJsonInspector.EXTENDED_INPUT_PROFILE);
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
    void acceptsUntouchedOfficialContestDatasetWithOnlyActionableWarnings() {
        InputStream input = getClass().getResourceAsStream("/official/lct-2026.geojson");
        assertThat(input).isNotNull();

        OfficialInputReport report = inspector.inspect(input);

        assertThat(report.isValid()).isTrue();
        assertThat(report.getInputProfile()).isEqualTo(OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE);
        assertThat(report.getSha256()).isEqualTo("cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130");
        assertThat(report.getFeatureCount()).isEqualTo(144);
        assertThat(report.getFeatureCounts())
                .containsEntry("source", 1L)
                .containsEntry("heat_network", 29L)
                .containsEntry("heat_chamber", 9L)
                .containsEntry("oks_connection_point", 17L)
                .containsEntry("restriction", 88L);
        assertThat(report.getWarnings()).hasSize(76);
        assertThat(report.getWarnings()).extracting(OfficialInputWarning::getCode)
                .contains(
                        "MISSING_CHAMBER_DIAMETER",
                        "MISSING_EXISTING_NETWORK_VALUE",
                        "MISSING_EXISTING_NETWORK_LINK")
                .doesNotContain(
                        "NUMERIC_ID_NORMALIZED",
                        "CONNECTION_POINT_AS_DEMAND",
                        "COMPATIBILITY_RESTRICTION_ALIAS",
                        "RAILWAY_RULE_PENDING");
    }

    @Test
    void rejectsDuplicateIdsUnknownReferencesAndFutureOksWithoutConnectionPoint() {
        OfficialInputReport report = inspect("{\"type\":\"FeatureCollection\",\"features\":["
                + feature("source", "src", point(37.60, 55.75), "") + ","
                + feature("oks_future", "oks", polygon(), ",\"flow_tph\":8.3,\"heat_load\":1.2") + ","
                + feature("oks_future", "oks", polygon(), ",\"flow_tph\":4.1,\"heat_load\":0.8") + ","
                + feature("oks_connection_point", "cp", point(37.603, 55.753), ",\"oks_id\":\"missing\"")
                + "]}");

        assertThat(report.isValid()).isFalse();
        assertThat(report.getErrors()).extracting(OfficialInputError::getCode)
                .contains(
                        "DUPLICATE_FEATURE_ID",
                        "UNKNOWN_REFERENCE",
                        "MISSING_OKS_CONNECTION_POINT");
    }

    @Test
    @EnabledIfSystemProperty(named = "heatroute.scale.input", matches = ".+")
    void streamsOptInByteBoundaryFixtureWithinTheConfiguredHeap() throws Exception {
        Path path = Path.of(System.getProperty("heatroute.scale.input"));
        long expectedBytes = Long.parseLong(System.getProperty("heatroute.scale.expectedBytes"));
        assertThat(Files.size(path)).isEqualTo(expectedBytes);

        long started = System.nanoTime();
        OfficialInputReport report;
        try (InputStream input = Files.newInputStream(path)) {
            report = inspector.inspect(input);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        long peakHeapBytes = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();

        assertThat(report.isValid()).isTrue();
        assertThat(report.getFeatureCount()).isEqualTo(144);
        assertThat(report.getSha256()).hasSize(64);
        System.out.printf(
                "R9_INPUT_SCALE bytes=%d elapsed_ms=%d peak_heap_bytes=%d max_heap_bytes=%d sha256=%s%n",
                expectedBytes,
                elapsedMs,
                peakHeapBytes,
                Runtime.getRuntime().maxMemory(),
                report.getSha256());
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
