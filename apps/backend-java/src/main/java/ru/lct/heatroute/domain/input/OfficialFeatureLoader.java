package ru.lct.heatroute.domain.input;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OfficialFeatureLoader {
    private static final int BATCH_SIZE = 500;
    private static final String INSERT_SQL =
            "INSERT INTO official_features "
                    + "(id, import_id, feature_id, object_type, attributes, geometry_wgs84, geometry_metric) "
                    + "VALUES (?, ?, ?, ?, ?::jsonb, "
                    + "ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), "
                    + "ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), 32637))";

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public OfficialFeatureLoader(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public long load(UUID importId, InputStream input) {
        long loaded = 0;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        try (JsonParser parser = objectMapper.getFactory().createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new OfficialInputFormatException("GeoJSON root must be an object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (!"features".equals(field)) {
                    parser.skipChildren();
                    continue;
                }
                if (value != JsonToken.START_ARRAY) {
                    throw new OfficialInputFormatException("features must be an array");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode feature = objectMapper.readTree(parser);
                    JsonNode properties = feature.path("properties");
                    String geometryJson = objectMapper.writeValueAsString(feature.path("geometry"));
                    batch.add(new Object[] {
                            UUID.randomUUID(),
                            importId,
                            properties.path("id").asText(),
                            properties.path("object_type").asText(),
                            objectMapper.writeValueAsString(properties),
                            geometryJson,
                            geometryJson
                    });
                    loaded++;
                    if (batch.size() == BATCH_SIZE) {
                        flush(batch);
                    }
                }
            }
        } catch (IOException exception) {
            throw new OfficialInputFormatException("Cannot reread validated GeoJSON", exception);
        }
        flush(batch);
        return loaded;
    }

    private void flush(List<Object[]> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, batch);
        batch.clear();
    }
}
