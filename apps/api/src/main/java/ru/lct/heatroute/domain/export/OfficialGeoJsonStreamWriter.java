package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

public final class OfficialGeoJsonStreamWriter {
    private final ObjectMapper objectMapper;

    public OfficialGeoJsonStreamWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeFeatureCollection(
            OutputStream outputStream,
            Consumer<Consumer<ObjectNode>> featureProducer) throws IOException {
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(outputStream)) {
            boolean[] started = {false};
            try {
                featureProducer.accept(feature -> {
                    try {
                        // Производитель проверяет геометрию до первой feature; при отказе даже
                        // закрытие JsonGenerator не должно выдавать заголовок незавершённого экспорта.
                        if (!started[0]) { startCollection(generator); started[0] = true; }
                        generator.writeTree(feature);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
            if (!started[0]) startCollection(generator);
            generator.writeEndArray();
            generator.writeEndObject();
        }
    }

    private void startCollection(JsonGenerator generator) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("type", "FeatureCollection");
        generator.writeArrayFieldStart("features");
    }
}
