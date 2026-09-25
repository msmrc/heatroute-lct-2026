package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class OfficialGeoJsonStreamWriterPreflightTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialGeoJsonStreamWriter writer = new OfficialGeoJsonStreamWriter(mapper);

    @Test
    void failedPreflightDoesNotFlushEvenAnEmptyCollectionHeader() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> writer.writeFeatureCollection(output, consumer -> {
            throw new IllegalStateException("Invalid saved geometry");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Invalid saved geometry");
        assertThat(output.size()).isZero();
    }

    @Test
    void successfulEmptyProducerStillWritesACompleteCollection() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writer.writeFeatureCollection(output, consumer -> { });
        var collection = mapper.readTree(output.toByteArray());
        assertThat(collection.path("type").asText()).isEqualTo("FeatureCollection");
        assertThat(collection.path("features").isArray()).isTrue();
        assertThat(collection.path("features")).isEmpty();
    }
}
