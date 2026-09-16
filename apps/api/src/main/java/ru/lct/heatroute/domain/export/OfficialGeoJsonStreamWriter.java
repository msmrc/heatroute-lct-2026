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
            generator.writeStartObject();
            generator.writeStringField("type", "FeatureCollection");
            generator.writeArrayFieldStart("features");
            try {
                featureProducer.accept(feature -> {
                    try {
                        generator.writeTree(feature);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
            generator.writeEndArray();
            generator.writeEndObject();
        }
    }
}
