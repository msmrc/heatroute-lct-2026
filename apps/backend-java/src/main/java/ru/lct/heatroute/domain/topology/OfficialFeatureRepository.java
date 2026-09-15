package ru.lct.heatroute.domain.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OfficialFeatureRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OfficialFeatureRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ImportedOfficialFeature> findByImport(UUID importId) {
        return jdbcTemplate.query(
                "SELECT feature_id, object_type, attributes::text, ST_AsBinary(geometry_metric) "
                        + "FROM official_features WHERE import_id = ? ORDER BY feature_id",
                (resultSet, rowNumber) -> map(resultSet),
                importId);
    }

    private ImportedOfficialFeature map(ResultSet resultSet) throws SQLException {
        try {
            JsonNode attributes = objectMapper.readTree(resultSet.getString(3));
            return new ImportedOfficialFeature(
                    resultSet.getString(1),
                    resultSet.getString(2),
                    attributes,
                    new WKBReader().read(resultSet.getBytes(4)));
        } catch (IOException | ParseException exception) {
            throw new SQLException("Stored official feature cannot be decoded", exception);
        }
    }
}
