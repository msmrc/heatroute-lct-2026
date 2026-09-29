package ru.lct.heatroute.domain.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialRunViewSchemaTest {
    @Test
    void exposesTheSameSnakeCaseNamesInOpenApiAndJson() throws Exception {
        Map<String, Schema> schemas = ModelConverters.getInstance().read(OfficialRunView.class);
        Schema<?> schema = schemas.get("OfficialRunView");

        assertThat(schema).isNotNull();
        assertThat(schema.getProperties()).containsKeys(
                "id", "import_id", "job_id", "state", "algorithm_version",
                "input_sha256", "parameters", "result", "error_code",
                "error_message", "created_at", "completed_at");
        assertThat(schema.getProperties()).doesNotContainKeys(
                "importId", "jobId", "algorithmVersion", "inputSha256",
                "errorCode", "errorMessage", "createdAt", "completedAt");
        Schema<?> parametersSchema = ModelConverters.getInstance()
                .read(OfficialRunParameters.class).values().stream()
                .findFirst().orElse(null);
        assertThat(parametersSchema).isNotNull();
        assertThat(parametersSchema.getProperties()).containsOnlyKeys(
                "minimum_depth_m", "maximum_depth_m", "depth_enabled");

        OfficialRunView view = new OfficialRunView(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                null, "queued", "heatroute-network-6", "input-sha",
                OfficialRunParameters.defaults(), null, null, null,
                OffsetDateTime.parse("2026-09-29T18:00:00Z"), null);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(view);

        assertThat(json).contains(
                "\"import_id\"", "\"algorithm_version\"", "\"input_sha256\"",
                "\"created_at\"");
        assertThat(json).doesNotContain(
                "\"importId\"", "\"algorithmVersion\"", "\"inputSha256\"",
                "\"createdAt\"");
    }
}
