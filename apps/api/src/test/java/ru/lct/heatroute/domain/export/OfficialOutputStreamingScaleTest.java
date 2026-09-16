package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class OfficialOutputStreamingScaleTest {
    private static final long OUTPUT_BOUNDARY_BYTES = 500L * 1024L * 1024L;
    private static final int POSITIONS_PER_FEATURE = 50_000;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OfficialOutputContractValidator validator = new OfficialOutputContractValidator();
    private final OfficialGeoJsonStreamWriter writer = new OfficialGeoJsonStreamWriter(objectMapper);

    @Test
    @EnabledIfSystemProperty(named = "heatroute.scale.output", matches = "true")
    void streamsAndValidatesAtLeastFiveHundredMebibytesWithoutBufferingTheCollection() throws Exception {
        CountingOutputStream output = new CountingOutputStream();
        OfficialOutputContractValidator.ValidationSession validation = validator.begin();
        long started = System.nanoTime();

        writer.writeFeatureCollection(output, featureOutput -> produceFeatures(featureOutput, validation, output));

        List<String> issues = validation.finish();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        long peakHeapBytes = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
        assertThat(issues).isEmpty();
        assertThat(output.getCount()).isGreaterThanOrEqualTo(OUTPUT_BOUNDARY_BYTES);
        System.out.printf(
                "R9_OUTPUT_SCALE bytes=%d elapsed_ms=%d peak_heap_bytes=%d max_heap_bytes=%d%n",
                output.getCount(), elapsedMs, peakHeapBytes, Runtime.getRuntime().maxMemory());
    }

    private void produceFeatures(
            Consumer<ObjectNode> output,
            OfficialOutputContractValidator.ValidationSession validation,
            CountingOutputStream counter) {
        accept(output, validation, technicalNode("node-start", 37.60, 55.70));
        accept(output, validation, technicalNode("node-end", 37.61, 55.71));
        int index = 0;
        while (counter.getCount() < OUTPUT_BOUNDARY_BYTES) {
            accept(output, validation, network(index++));
        }
        accept(output, validation, summary());
    }

    private void accept(
            Consumer<ObjectNode> output,
            OfficialOutputContractValidator.ValidationSession validation,
            ObjectNode feature) {
        validation.accept(feature);
        output.accept(feature);
    }

    private ObjectNode network(int index) {
        ObjectNode properties = properties("network-" + index, "heat_network");
        properties.put("start_node_id", "node-start");
        properties.put("end_node_id", "node-end");
        properties.put("flow_tph", 10.0);
        properties.put("diameter", 100);
        properties.put("length", 100.0);
        properties.put("laying_method", "base");
        properties.put("cost", 1.0);
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "LineString");
        ArrayNode coordinates = geometry.putArray("coordinates");
        for (int position = 0; position < POSITIONS_PER_FEATURE; position++) {
            ArrayNode pair = coordinates.addArray();
            pair.add(position % 2 == 0 ? 37.60 : 37.61);
            pair.add(position % 2 == 0 ? 55.70 : 55.71);
        }
        return feature(geometry, properties);
    }

    private ObjectNode technicalNode(String id, double longitude, double latitude) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "Point");
        geometry.putArray("coordinates").add(longitude).add(latitude);
        return feature(geometry, properties(id, "technical_node"));
    }

    private ObjectNode summary() {
        ObjectNode properties = properties("summary-scale", "variant_summary");
        properties.put("rank", 1);
        for (String field : List.of(
                "construction_cost", "chamber_construction_cost", "tie_in_cost",
                "reconstruction_cost", "chamber_reconstruction_cost", "unconnected_penalty",
                "calculated_cost", "new_network_length", "reconstruction_length", "length", "score")) {
            properties.put(field, 0.0);
        }
        properties.putArray("unconnected_oks_ids");
        return feature(null, properties);
    }

    private ObjectNode properties(String id, String objectType) {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("id", id);
        properties.put("object_type", objectType);
        properties.put("variant_id", "scale");
        return properties;
    }

    private ObjectNode feature(ObjectNode geometry, ObjectNode properties) {
        ObjectNode feature = objectMapper.createObjectNode();
        feature.put("type", "Feature");
        if (geometry == null) {
            feature.putNull("geometry");
        } else {
            feature.set("geometry", geometry);
        }
        feature.set("properties", properties);
        return feature;
    }

    private static final class CountingOutputStream extends OutputStream {
        private long count;

        @Override
        public void write(int value) {
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (offset < 0 || length < 0 || offset + length > bytes.length) {
                throw new IndexOutOfBoundsException();
            }
            count += length;
        }

        long getCount() {
            return count;
        }
    }
}
