package ru.lct.heatroute.domain.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.lct.heatroute.domain.routing.RoutingFeatureSource;

@Repository
public class OfficialFeatureRepository {
    public static final int DEFAULT_PAGE_SIZE = 1_000;
    private static final int MAX_PAGE_SIZE = 10_000;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OfficialFeatureRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ImportedOfficialFeature> findByImport(UUID importId) {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        forEachByImport(importId, DEFAULT_PAGE_SIZE, features::add);
        return features;
    }

    /**
     * Reads one stable, keyset-paginated slice of an import. Callers processing large imports
     * should use {@link #forEachByImport(UUID, int, Consumer)} instead of one JDBC result for
     * the complete import.
     */
    public List<ImportedOfficialFeature> findPageByImport(
            UUID importId, String afterFeatureId, int pageSize) {
        requirePageSize(pageSize);
        if (afterFeatureId == null) {
            return jdbcTemplate.query(
                    "SELECT feature_id, object_type, attributes::text, ST_AsBinary(geometry_metric) "
                            + "FROM official_features WHERE import_id = ? "
                            + "ORDER BY feature_id LIMIT ?",
                    (resultSet, rowNumber) -> map(resultSet),
                    importId,
                    pageSize);
        }
        return jdbcTemplate.query(
                "SELECT feature_id, object_type, attributes::text, ST_AsBinary(geometry_metric) "
                        + "FROM official_features WHERE import_id = ? AND feature_id > ? "
                        + "ORDER BY feature_id LIMIT ?",
                (resultSet, rowNumber) -> map(resultSet),
                importId,
                afterFeatureId,
                pageSize);
    }

    /**
     * Visits an import in bounded database pages. The feature id is immutable within an import,
     * so a keyset cursor avoids the growing cost and unstable windows of OFFSET pagination.
     */
    public void forEachByImport(
            UUID importId, int pageSize, Consumer<ImportedOfficialFeature> consumer) {
        String afterFeatureId = null;
        while (true) {
            List<ImportedOfficialFeature> page = findPageByImport(importId, afterFeatureId, pageSize);
            if (page.isEmpty()) {
                return;
            }
            for (ImportedOfficialFeature feature : page) {
                consumer.accept(feature);
            }
            afterFeatureId = page.get(page.size() - 1).getFeatureId();
            if (page.size() < pageSize) {
                return;
            }
        }
    }

    /** Only the types needed globally by topology, demand selection and reconstruction. */
    public void forEachCalculationCoreByImport(
            UUID importId, int pageSize, Consumer<ImportedOfficialFeature> consumer) {
        requirePageSize(pageSize);
        String cursor = null;
        while (true) {
            List<ImportedOfficialFeature> page = cursor == null
                    ? jdbcTemplate.query("SELECT feature_id, object_type, attributes::text, ST_AsBinary(geometry_metric) "
                                    + "FROM official_features WHERE import_id = ? AND object_type IN "
                                    + "('source', 'heat_network', 'heat_chamber', 'oks_connection_point') "
                                    + "ORDER BY feature_id LIMIT ?", (rs, row) -> map(rs), importId, pageSize)
                    : jdbcTemplate.query("SELECT feature_id, object_type, attributes::text, ST_AsBinary(geometry_metric) "
                                    + "FROM official_features WHERE import_id = ? AND object_type IN "
                                    + "('source', 'heat_network', 'heat_chamber', 'oks_connection_point') "
                                    + "AND feature_id > ? ORDER BY feature_id LIMIT ?", (rs, row) -> map(rs), importId, cursor, pageSize);
            page.forEach(consumer);
            if (page.size() < pageSize) return;
            cursor = page.get(page.size() - 1).getFeatureId();
        }
    }

    public RoutingFeatureSource routingFeatureSource(UUID importId) {
        return new RoutingFeatureSource() {
            @Override public List<ImportedOfficialFeature> findInMetricWindow(Envelope window) {
                return findRoutingFeaturesInMetricWindow(importId, window);
            }
            @Override public List<ImportedOfficialFeature> findByFeatureIds(Set<String> ids) {
                if (ids.isEmpty()) return java.util.List.of();
                return jdbcTemplate.query("SELECT feature_id, object_type, attributes::text, ST_AsBinary(geometry_metric) "
                                + "FROM official_features WHERE import_id = ? AND feature_id = ANY (?::varchar[]) ORDER BY feature_id",
                        (resultSet, rowNumber) -> map(resultSet), importId, ids.toArray(new String[0]));
            }
        };
    }

    public List<ImportedOfficialFeature> findRoutingFeaturesInMetricWindow(UUID importId, Envelope window) {
        return jdbcTemplate.query("SELECT feature_id, object_type, attributes::text, ST_AsBinary(geometry_metric) "
                        + "FROM official_features WHERE import_id = ? AND object_type IN ('restriction', 'oks_existing') "
                        + "AND geometry_metric && ST_MakeEnvelope(?, ?, ?, ?, 32637) ORDER BY feature_id",
                (resultSet, rowNumber) -> map(resultSet), importId, window.getMinX(), window.getMinY(),
                window.getMaxX(), window.getMaxY());
    }

    public String findMapFeatures(
            UUID importId,
            double minLongitude,
            double minLatitude,
            double maxLongitude,
            double maxLatitude) {
        return jdbcTemplate.queryForObject(
                "WITH visible AS ("
                        + "SELECT feature_id, object_type, attributes, geometry_wgs84 FROM ("
                        + "SELECT feature_id, object_type, attributes, geometry_wgs84 "
                        + "FROM official_features "
                        + "WHERE import_id = ? AND geometry_wgs84 && ST_MakeEnvelope(?, ?, ?, ?, 4326) "
                        + "UNION ALL "
                        + "SELECT feature_id || ':clearance-5m', 'building_clearance_5m', "
                        + "jsonb_build_object('source_feature_id', feature_id, 'clearance_m', 5.0, "
                        + "'label', 'Зона 5 м от здания'), "
                        + "ST_Transform(ST_Difference(ST_Buffer(geometry_metric, 5.0), geometry_metric), 4326) "
                        + "FROM official_features "
                        + "WHERE import_id = ? AND (object_type = 'oks_existing' "
                        + "OR (object_type = 'restriction' AND attributes->>'restriction_type' = 'oks')) "
                        + "AND geometry_metric IS NOT NULL AND ST_Dimension(geometry_metric) = 2 "
                        + "AND geometry_wgs84 && ST_MakeEnvelope(?, ?, ?, ?, 4326)"
                        + ") candidates "
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
                maxLatitude,
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

    private void requirePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Official feature page size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
