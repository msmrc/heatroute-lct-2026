package ru.lct.heatroute.domain.job;

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
public class OfficialJobRepository {
    private static final String SELECT_COLUMNS =
            "id, import_id, run_id, job_type, state, phase, progress_current, progress_total, attempt, "
                    + "cancellation_requested, result::text AS result_json, error_code, error_message, "
                    + "created_at, completed_at";
    private static final String RETURNING_COLUMNS =
            "job.id, job.import_id, job.run_id, job.job_type, job.state, job.phase, job.progress_current, "
                    + "job.progress_total, job.attempt, job.cancellation_requested, "
                    + "job.result::text AS result_json, "
                    + "job.error_code, job.error_message, job.created_at, job.completed_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OfficialJobRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public OfficialJobView createTopologyJob(UUID importId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO official_jobs (id, import_id, job_type, state, phase) "
                        + "VALUES (?, ?, 'topology_analysis', 'queued', 'queued')",
                id,
                importId);
        return find(id).orElseThrow(() -> new IllegalStateException("Created job cannot be read"));
    }

    public OfficialJobView createCalculationJob(UUID importId, UUID runId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO official_jobs (id, import_id, run_id, job_type, state, phase) "
                        + "VALUES (?, ?, ?, 'calculation', 'queued', 'queued')",
                id,
                importId,
                runId);
        return find(id).orElseThrow(() -> new IllegalStateException("Created job cannot be read"));
    }

    public Optional<OfficialJobView> find(UUID id) {
        List<OfficialJobView> rows = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM official_jobs WHERE id = ?",
                (resultSet, rowNumber) -> map(resultSet),
                id);
        return rows.stream().findFirst();
    }

    public Optional<OfficialJobView> claimNext(UUID workerId) {
        String sql = "WITH candidate AS ("
                + "SELECT id FROM official_jobs "
                + "WHERE cancellation_requested = false "
                + "AND (state = 'queued' OR (state = 'running' AND lease_until < now())) "
                + "ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1"
                + ") UPDATE official_jobs job SET "
                + "state = 'running', phase = job.job_type, attempt = attempt + 1, "
                + "lease_owner = ?, lease_until = now() + interval '5 minutes', "
                + "started_at = COALESCE(started_at, now()), heartbeat_at = now(), updated_at = now() "
                + "FROM candidate WHERE job.id = candidate.id RETURNING " + RETURNING_COLUMNS;
        List<OfficialJobView> rows = jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> map(resultSet),
                workerId);
        return rows.stream().findFirst();
    }

    public void finalizeRequestedCancellations() {
        jdbcTemplate.update(
                "UPDATE official_runs run SET state = 'cancelled', completed_at = COALESCE(run.completed_at, now()) "
                        + "FROM official_jobs job WHERE job.run_id = run.id "
                        + "AND job.cancellation_requested = true "
                        + "AND run.state IN ('queued', 'running')");
        jdbcTemplate.update(
                "UPDATE official_jobs SET state = 'cancelled', phase = 'cancelled', "
                        + "lease_owner = NULL, lease_until = NULL, completed_at = COALESCE(completed_at, now()), "
                        + "updated_at = now() WHERE cancellation_requested = true "
                        + "AND state IN ('queued', 'running')");
    }

    public boolean isCancellationRequested(UUID id) {
        Boolean value = jdbcTemplate.queryForObject(
                "SELECT cancellation_requested FROM official_jobs WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(value);
    }

    public boolean renewLease(UUID id, UUID workerId) {
        return jdbcTemplate.update(
                "UPDATE official_jobs SET lease_until = now() + interval '5 minutes', "
                        + "heartbeat_at = now(), updated_at = now() "
                        + "WHERE id = ? AND state = 'running' AND lease_owner = ?",
                id,
                workerId) == 1;
    }

    public void markCompleted(UUID id, JsonNode result) {
        terminalUpdate(
                "UPDATE official_jobs SET state = 'completed', phase = 'completed', "
                        + "progress_current = progress_total, result = ?::jsonb, lease_owner = NULL, "
                        + "lease_until = NULL, completed_at = now(), updated_at = now() "
                        + "WHERE id = ? AND state = 'running'",
                json(result),
                id);
    }

    public void markFailed(UUID id, String code, String message) {
        terminalUpdate(
                "UPDATE official_jobs SET state = 'failed', phase = 'failed', error_code = ?, "
                        + "error_message = ?, lease_owner = NULL, lease_until = NULL, "
                        + "completed_at = now(), updated_at = now() "
                        + "WHERE id = ? AND state = 'running'",
                code,
                message,
                id);
    }

    public void markCancelled(UUID id) {
        int updated = jdbcTemplate.update(
                "UPDATE official_jobs SET state = 'cancelled', phase = 'cancelled', "
                        + "lease_owner = NULL, lease_until = NULL, completed_at = now(), updated_at = now() "
                        + "WHERE id = ? AND state IN ('queued', 'running')",
                id);
        if (updated != 1 && !find(id).map(job -> "cancelled".equals(job.getState())).orElse(false)) {
            throw new IllegalStateException("Job cannot transition to cancelled");
        }
    }

    public Optional<OfficialJobView> requestCancellation(UUID id) {
        jdbcTemplate.update(
                "UPDATE official_jobs SET cancellation_requested = true, "
                        + "state = CASE WHEN state = 'queued' THEN 'cancelled' ELSE state END, "
                        + "phase = CASE WHEN state = 'queued' THEN 'cancelled' ELSE phase END, "
                        + "completed_at = CASE WHEN state = 'queued' THEN now() ELSE completed_at END, "
                        + "updated_at = now() WHERE id = ? AND state IN ('queued', 'running')",
                id);
        return find(id);
    }

    private OfficialJobView map(ResultSet resultSet) throws SQLException {
        String resultJson = resultSet.getString("result_json");
        JsonNode result = null;
        if (resultJson != null) {
            try {
                result = objectMapper.readTree(resultJson);
            } catch (JsonProcessingException exception) {
                throw new SQLException("Stored job result is not valid JSON", exception);
            }
        }
        return new OfficialJobView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("import_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getString("job_type"),
                resultSet.getString("state"),
                resultSet.getString("phase"),
                resultSet.getLong("progress_current"),
                resultSet.getLong("progress_total"),
                resultSet.getInt("attempt"),
                resultSet.getBoolean("cancellation_requested"),
                result,
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("completed_at", OffsetDateTime.class));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize job result", exception);
        }
    }

    private void terminalUpdate(String sql, Object... arguments) {
        int updated = jdbcTemplate.update(sql, arguments);
        if (updated != 1) {
            throw new IllegalStateException("Job cannot transition to a terminal state");
        }
    }
}
