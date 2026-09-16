package ru.lct.heatroute.domain.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OfficialRunRepository {
    private static final String COLUMNS = "id, import_id, job_id, state, algorithm_version, input_sha256, "
            + "parameters::text AS parameters_json, "
            + "result::text AS result_json, error_code, error_message, created_at, completed_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OfficialRunRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public OfficialRunView create(
            UUID importId,
            String inputSha256,
            String algorithmVersion,
            OfficialRunParameters parameters) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO official_runs (id, import_id, state, algorithm_version, input_sha256, parameters) "
                        + "VALUES (?, ?, 'queued', ?, ?, ?::jsonb)",
                id,
                importId,
                algorithmVersion,
                inputSha256,
                json(objectMapper.valueToTree(parameters)));
        return find(id).orElseThrow(() -> new IllegalStateException("Created run cannot be read"));
    }

    public void attachJob(UUID runId, UUID jobId) {
        int updated = jdbcTemplate.update(
                "UPDATE official_runs SET job_id = ? WHERE id = ? AND job_id IS NULL",
                jobId,
                runId);
        if (updated != 1) {
            throw new IllegalStateException("Run cannot be attached to a job");
        }
    }

    public Optional<OfficialRunView> find(UUID id) {
        List<OfficialRunView> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM official_runs WHERE id = ?",
                (resultSet, rowNumber) -> map(resultSet),
                id);
        return rows.stream().findFirst();
    }

    public Optional<OfficialRunView> findLatestCompleted() {
        List<OfficialRunView> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM official_runs "
                        + "WHERE state = 'completed' AND result IS NOT NULL "
                        + "ORDER BY completed_at DESC, created_at DESC LIMIT 1",
                (resultSet, rowNumber) -> map(resultSet));
        return rows.stream().findFirst();
    }

    public void markRunning(UUID id) {
        int updated = jdbcTemplate.update(
                "UPDATE official_runs SET state = 'running', started_at = COALESCE(started_at, now()) "
                        + "WHERE id = ? AND state IN ('queued', 'running')",
                id);
        if (updated != 1) {
            throw new IllegalStateException("Run cannot transition to running");
        }
    }

    public void markCompleted(UUID id, JsonNode result) {
        terminalUpdate(
                "UPDATE official_runs SET state = 'completed', result = ?::jsonb, completed_at = now() "
                        + "WHERE id = ? AND state = 'running'",
                json(result),
                id);
    }

    public void markFailed(UUID id, String code, String message) {
        terminalUpdate(
                "UPDATE official_runs SET state = 'failed', error_code = ?, error_message = ?, completed_at = now() "
                        + "WHERE id = ? AND state IN ('queued', 'running')",
                code,
                message,
                id);
    }

    public void markCancelled(UUID id) {
        terminalUpdate(
                "UPDATE official_runs SET state = 'cancelled', completed_at = now() "
                        + "WHERE id = ? AND state IN ('queued', 'running')",
                id);
    }

    private OfficialRunView map(ResultSet resultSet) throws SQLException {
        OfficialRunParameters parameters;
        try {
            parameters = objectMapper.readValue(
                    resultSet.getString("parameters_json"), OfficialRunParameters.class).validated();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new SQLException("Stored run parameters are not valid JSON", exception);
        }
        String resultJson = resultSet.getString("result_json");
        JsonNode result = null;
        if (resultJson != null) {
            try {
                result = objectMapper.readTree(resultJson);
            } catch (JsonProcessingException exception) {
                throw new SQLException("Stored run result is not valid JSON", exception);
            }
        }
        return new OfficialRunView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("import_id", UUID.class),
                resultSet.getObject("job_id", UUID.class),
                resultSet.getString("state"),
                resultSet.getString("algorithm_version"),
                resultSet.getString("input_sha256"),
                parameters,
                result,
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("completed_at", OffsetDateTime.class));
    }

    private String json(JsonNode result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize run result", exception);
        }
    }

    private void terminalUpdate(String sql, Object... arguments) {
        int updated = jdbcTemplate.update(sql, arguments);
        if (updated != 1) {
            throw new IllegalStateException("Run cannot transition to a terminal state");
        }
    }
}
