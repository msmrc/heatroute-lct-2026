package ru.lct.heatroute.domain.input;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OfficialImportRepository {
    private static final TypeReference<Map<String, Long>> COUNTS_TYPE =
            new TypeReference<Map<String, Long>>() {};
    private static final TypeReference<List<OfficialInputError>> ERRORS_TYPE =
            new TypeReference<List<OfficialInputError>>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OfficialImportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(
            UUID id,
            String state,
            String filename,
            long sizeBytes,
            OfficialInputReport report) {
        jdbcTemplate.update(
                "INSERT INTO official_imports "
                        + "(id, state, original_filename, raw_sha256, input_size_bytes, "
                        + "contract_version, feature_count, feature_counts, errors, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, "
                        + "CASE WHEN ? IN ('valid', 'invalid', 'failed', 'cancelled') THEN now() ELSE NULL END)",
                id,
                state,
                filename,
                report.getSha256(),
                sizeBytes,
                report.getContractVersion(),
                report.getFeatureCount(),
                json(report.getFeatureCounts()),
                json(report.getErrors()),
                state);
    }

    public void markValid(UUID id) {
        int updated = jdbcTemplate.update(
                "UPDATE official_imports SET state = 'valid', completed_at = now(), updated_at = now() "
                        + "WHERE id = ? AND state = 'validating'",
                id);
        if (updated != 1) {
            throw new IllegalStateException("Import cannot transition from validating to valid");
        }
    }

    public Optional<OfficialImportView> find(UUID id) {
        List<OfficialImportView> rows = jdbcTemplate.query(
                "SELECT id, state, original_filename, input_size_bytes, created_at, "
                        + "contract_version, raw_sha256, feature_count, "
                        + "feature_counts::text, errors::text "
                        + "FROM official_imports WHERE id = ?",
                (resultSet, rowNumber) -> map(resultSet),
                id);
        return rows.stream().findFirst();
    }

    private OfficialImportView map(ResultSet resultSet) throws SQLException {
        try {
            OfficialInputReport report = new OfficialInputReport(
                    resultSet.getString("contract_version"),
                    resultSet.getString("raw_sha256"),
                    resultSet.getLong("feature_count"),
                    objectMapper.readValue(resultSet.getString("feature_counts"), COUNTS_TYPE),
                    objectMapper.readValue(resultSet.getString("errors"), ERRORS_TYPE));
            return new OfficialImportView(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("state"),
                    resultSet.getString("original_filename"),
                    resultSet.getLong("input_size_bytes"),
                    resultSet.getObject("created_at", OffsetDateTime.class),
                    report);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored import report is not valid JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize import report", exception);
        }
    }
}
