package ru.lct.heatroute.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;

class OfficialJsonSchemaContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OfficialGeoJsonInspector inspector = new OfficialGeoJsonInspector(objectMapper);

    @Test
    void compilesEveryPublishedSchemaAndAcceptsTheSuppliedOrganizerFile() throws Exception {
        Schema strictInput = ContractSchemaSupport.load("lct-2026-input.schema.json");
        Schema compatibilityInput = ContractSchemaSupport.load("lct-2026-provided-dataset.schema.json");
        ContractSchemaSupport.load("lct-2026-output.schema.json");
        JsonNode supplied = objectMapper.readTree(
                ContractSchemaSupport.readResource("/official/lct-2026.geojson"));

        assertThat(ContractSchemaSupport.validate(compatibilityInput, supplied)).isEmpty();
        assertThat(ContractSchemaSupport.validate(strictInput, supplied)).isNotEmpty();
    }

    @Test
    void strictSchemaAndStreamingInspectorAgreeOnACompleteSevenTypeInput() throws Exception {
        JsonNode input = objectMapper.readTree(strictInput());

        assertThat(ContractSchemaSupport.validate(
                ContractSchemaSupport.load("lct-2026-input.schema.json"), input)).isEmpty();
        assertThat(inspector.inspect(new ByteArrayInputStream(
                input.toString().getBytes(StandardCharsets.UTF_8))).getErrors()).isEmpty();
    }

    @Test
    void strictSchemaRejectsMissingEngineeringFields() throws Exception {
        JsonNode input = objectMapper.readTree("{\"type\":\"FeatureCollection\",\"features\":[{"
                + "\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\","
                + "\"coordinates\":[[37.6,55.7],[37.61,55.71]]},\"properties\":{"
                + "\"id\":\"network\",\"object_type\":\"heat_network\",\"diameter\":100}}]}");

        assertThat(ContractSchemaSupport.validate(
                ContractSchemaSupport.load("lct-2026-input.schema.json"), input)).isNotEmpty();
    }

    private String strictInput() {
        return "{\"type\":\"FeatureCollection\",\"features\":["
                + feature("source", "source", point(37.6000, 55.7000), "") + ","
                + feature("network", "heat_network", line(37.6000, 55.7000, 37.6010, 55.7000),
                        ",\"diameter\":100,\"flow_tph\":2,\"upstream_object_id\":\"source\"") + ","
                + feature("chamber", "heat_chamber", point(37.6010, 55.7000),
                        ",\"diameter\":100,\"upstream_object_id\":\"network\"") + ","
                + feature("oks", "oks_future", polygon(37.6015, 55.7005),
                        ",\"flow_tph\":4,\"heat_load\":3") + ","
                + feature("connection", "oks_connection_point", point(37.6015, 55.7005),
                        ",\"oks_id\":\"oks\"") + ","
                + feature("existing", "oks_existing", polygon(37.6020, 55.7010), "") + ","
                + feature("park", "restriction", polygon(37.6030, 55.7020),
                        ",\"restriction_type\":\"park\"")
                + "]}";
    }

    private String feature(String id, String objectType, String geometry, String extraProperties) {
        return "{\"type\":\"Feature\",\"geometry\":" + geometry + ",\"properties\":{"
                + "\"id\":\"" + id + "\",\"object_type\":\"" + objectType + "\""
                + extraProperties + "}}";
    }

    private String point(double longitude, double latitude) {
        return "{\"type\":\"Point\",\"coordinates\":[" + longitude + "," + latitude + "]}";
    }

    private String line(double x1, double y1, double x2, double y2) {
        return "{\"type\":\"LineString\",\"coordinates\":[[" + x1 + "," + y1 + "],["
                + x2 + "," + y2 + "]]}";
    }

    private String polygon(double longitude, double latitude) {
        double longitude2 = longitude + 0.0001;
        double latitude2 = latitude + 0.0001;
        return "{\"type\":\"Polygon\",\"coordinates\":[[[" + longitude + "," + latitude + "],["
                + longitude2 + "," + latitude + "],[" + longitude2 + "," + latitude2 + "],["
                + longitude + "," + latitude + "]]]}";
    }
}
