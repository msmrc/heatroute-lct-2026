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

    public String findMapFeatures(
            UUID importId,
            double minLongitude,
            double minLatitude,
            double maxLongitude,
            double maxLatitude) {
        return jdbcTemplate.queryForObject(
                "WITH visible AS ("
                        + "SELECT feature_id, object_type, attributes, geometry_wgs84 "
                        + "FROM official_features "
                        + "WHERE import_id = ? AND geometry_wgs84 && ST_MakeEnvelope(?, ?, ?, ?, 4326) "
                        + "ORDER BY feature_id LIMIT 10001"
                        + "), numbered AS ("
                        + "SELECT *, row_number() OVER () AS row_number FROM visible"
                        + ") SELECT jsonb_build_object("
                        + "'type', 'FeatureCollection', "
                        + "'features', COALESCE(jsonb_agg(jsonb_build_object("
                        + "'type', 'Feature', "
                        + "'id', feature_id, "
                        + "'geometry', ST_AsGeoJSON(geometry_wgs84)::jsonb, "
                        + "'properties', attributes || jsonb_build_object("
                        + "'feature_id', feature_id, 'object_type', object_type)"
                        + ") ORDER BY feature_id) FILTER (WHERE row_number <= 10000), '[]'::jsonb), "
                        + "'truncated', COALESCE(bool_or(row_number > 10000), false)"
                        + ")::text FROM numbered",
                String.class,
                importId,
                minLongitude,
                minLatitude,
                maxLongitude,
                maxLatitude);
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
