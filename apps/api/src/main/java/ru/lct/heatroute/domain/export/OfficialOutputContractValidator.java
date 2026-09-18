package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OfficialOutputContractValidator {
    private static final Map<String, Set<String>> REQUIRED = requiredFields();
    private static final Map<String, Set<String>> OPTIONAL = optionalFields();
    private static final Map<String, String> GEOMETRY = geometryTypes();

    public List<String> validate(JsonNode collection) {
        return validate(collection, false);
    }

    public List<String> validate(JsonNode collection, boolean allowMissingTieInDiameter) {
        if (!"FeatureCollection".equals(collection.path("type").asText())
                || !collection.path("features").isArray()) {
            return List.of("Root must be a GeoJSON FeatureCollection");
        }
        ValidationSession session = begin(allowMissingTieInDiameter);
        collection.path("features").forEach(session::accept);
        return session.finish();
    }

    public ValidationSession begin() {
        return begin(false);
    }

    public ValidationSession begin(boolean allowMissingTieInDiameter) {
        return new ValidationSession(allowMissingTieInDiameter);
    }

    public final class ValidationSession {
        private final boolean allowMissingTieInDiameter;
        private final List<String> issues = new ArrayList<>();
        private final Set<String> ids = new HashSet<>();
        private final Set<String> variants = new HashSet<>();
        private final Map<String, Integer> summaries = new HashMap<>();
        private final List<String[]> references = new ArrayList<>();
        private int index;
        private boolean finished;

        private ValidationSession(boolean allowMissingTieInDiameter) {
            this.allowMissingTieInDiameter = allowMissingTieInDiameter;
        }

        public void accept(JsonNode feature) {
            if (finished) {
                throw new IllegalStateException("Output validation session is already finished");
            }
            int featureIndex = index++;
            JsonNode properties = feature.path("properties");
            String objectType = properties.path("object_type").asText();
            Set<String> configuredRequired = REQUIRED.get(objectType);
            if (configuredRequired == null) {
                issues.add("Feature " + featureIndex + " has unsupported object_type " + objectType);
                return;
            }
            Set<String> required = new HashSet<>(configuredRequired);
            if (allowMissingTieInDiameter && "tie_in".equals(objectType)) {
                required.remove("existing_diameter");
            }
            if (!"Feature".equals(feature.path("type").asText())) {
                issues.add("Feature " + featureIndex + " must have type Feature");
            }
            Set<String> actual = new HashSet<>();
            properties.fieldNames().forEachRemaining(actual::add);
            for (String field : required) {
                if (!properties.has(field)
                        || properties.get(field).isNull()
                        || properties.get(field).isMissingNode()) {
                    issues.add("Feature " + featureIndex + " is missing " + field);
                }
            }
            Set<String> allowed = new HashSet<>(required);
            allowed.addAll(OPTIONAL.getOrDefault(objectType, Set.of()));
            if (allowMissingTieInDiameter && "tie_in".equals(objectType)) {
                allowed.add("existing_diameter");
            }
            List<String> forbidden = actual.stream()
                    .filter(field -> !allowed.contains(field))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            for (String field : forbidden) {
                issues.add("Feature " + featureIndex + " has forbidden field " + field);
            }
            validatePropertyTypes(featureIndex, properties, issues);
            String expectedGeometry = GEOMETRY.get(objectType);
            if (expectedGeometry == null) {
                if (!feature.path("geometry").isNull()) {
                    issues.add("Feature " + featureIndex + " must have null geometry");
                }
            } else if (!expectedGeometry.equals(feature.path("geometry").path("type").asText())) {
                issues.add("Feature " + featureIndex + " must have " + expectedGeometry + " geometry");
            } else {
                validateCoordinates(featureIndex, expectedGeometry,
                        feature.path("geometry").path("coordinates"), issues);
            }
            String id = properties.path("id").asText();
            if (!id.isBlank() && !ids.add(id)) {
                issues.add("Duplicate output id " + id);
            }
            String variant = properties.path("variant_id").asText();
            if (!variant.isBlank()) {
                variants.add(variant);
                if ("variant_summary".equals(objectType)) {
                    summaries.merge(variant, 1, Integer::sum);
                }
            }
            if ("heat_network".equals(objectType)) {
                for (String field : List.of("start_node_id", "end_node_id")) {
                    references.add(new String[]{field, properties.path(field).asText()});
                }
            }
        }

        public List<String> finish() {
            if (!finished) {
                for (String[] reference : references) {
                    if (!ids.contains(reference[1])) {
                        issues.add("Unknown " + reference[0] + " reference " + reference[1]);
                    }
                }
                for (String variant : variants) {
                    if (summaries.getOrDefault(variant, 0) != 1) {
                        issues.add("Variant " + variant + " must have exactly one summary");
                    }
                }
                finished = true;
            }
            return new ArrayList<>(issues);
        }
    }

    private void validatePropertyTypes(int index, JsonNode properties, List<String> issues) {
        Set<String> integerFields = Set.of("diameter", "existing_diameter", "required_diameter", "rank");
        Set<String> numericFields = Set.of(
                "flow_tph", "existing_flow_tph", "added_flow_tph", "calculated_flow_tph",
                "length", "cost", "depth_start", "depth_end", "construction_cost",
                "chamber_construction_cost", "tie_in_cost", "reconstruction_cost",
                "chamber_reconstruction_cost", "unconnected_penalty", "calculated_cost",
                "new_network_length", "reconstruction_length", "score");
        properties.fields().forEachRemaining(field -> {
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (integerFields.contains(name) && !value.isIntegralNumber()) {
                issues.add("Feature " + index + " field " + name + " must be an integer");
            } else if (numericFields.contains(name) && !value.isNumber()) {
                issues.add("Feature " + index + " field " + name + " must be numeric");
            } else if ("unconnected_oks_ids".equals(name)) {
                if (!value.isArray() || !value.isEmpty() && !allTextual(value)) {
                    issues.add("Feature " + index + " field " + name + " must be a string array");
                }
            } else if (!integerFields.contains(name)
                    && !numericFields.contains(name)
                    && !"unconnected_oks_ids".equals(name)
                    && !value.isTextual()) {
                issues.add("Feature " + index + " field " + name + " must be a string");
            }
        });
    }

    private boolean allTextual(JsonNode values) {
        for (JsonNode value : values) {
            if (!value.isTextual()) return false;
        }
        return true;
    }

    private void validateCoordinates(
            int index,
            String geometryType,
            JsonNode coordinates,
            List<String> issues) {
        if ("Point".equals(geometryType)) {
            if (!validPosition(coordinates)) {
                issues.add("Feature " + index + " has invalid WGS84 Point coordinates");
            }
            return;
        }
        if (!coordinates.isArray() || coordinates.size() < 2) {
            issues.add("Feature " + index + " LineString must contain at least two positions");
            return;
        }
        for (JsonNode position : coordinates) {
            if (!validPosition(position)) {
                issues.add("Feature " + index + " has invalid WGS84 LineString coordinates");
                return;
            }
        }
    }

    private boolean validPosition(JsonNode position) {
        if (!position.isArray() || position.size() < 2
                || !position.path(0).isNumber() || !position.path(1).isNumber()) {
            return false;
        }
        double longitude = position.path(0).asDouble();
        double latitude = position.path(1).asDouble();
        return Double.isFinite(longitude) && Double.isFinite(latitude)
                && longitude >= -180.0 && longitude <= 180.0
                && latitude >= -90.0 && latitude <= 90.0;
    }

    private static Map<String, Set<String>> requiredFields() {
        Map<String, Set<String>> result = new HashMap<>();
        result.put("heat_network", Set.of("id", "object_type", "variant_id", "start_node_id",
                "end_node_id", "flow_tph", "diameter", "length", "laying_method", "cost"));
        result.put("tie_in", Set.of("id", "object_type", "variant_id", "existing_object_id",
                "existing_object_type", "existing_diameter", "required_diameter", "cost"));
        result.put("heat_network_reconstruction", Set.of("id", "object_type", "variant_id",
                "existing_object_id", "existing_flow_tph", "added_flow_tph", "calculated_flow_tph",
                "existing_diameter", "required_diameter", "length", "cost"));
        result.put("heat_chamber", Set.of("id", "object_type", "variant_id", "diameter", "cost"));
        result.put("heat_chamber_reconstruction", Set.of("id", "object_type", "variant_id",
                "existing_object_id", "existing_diameter", "required_diameter", "cost"));
        result.put("technical_node", Set.of("id", "object_type", "variant_id"));
        result.put("variant_summary", Set.of("id", "object_type", "variant_id", "rank",
                "construction_cost", "chamber_construction_cost", "tie_in_cost",
                "reconstruction_cost", "chamber_reconstruction_cost", "unconnected_penalty",
                "calculated_cost", "new_network_length", "reconstruction_length", "length", "score",
                "unconnected_oks_ids"));
        return result;
    }

    private static Map<String, Set<String>> optionalFields() {
        Map<String, Set<String>> result = new HashMap<>();
        result.put("heat_network", Set.of("depth_start", "depth_end"));
        return result;
    }

    private static Map<String, String> geometryTypes() {
        Map<String, String> result = new HashMap<>();
        result.put("heat_network", "LineString");
        result.put("tie_in", "Point");
        result.put("heat_network_reconstruction", "LineString");
        result.put("heat_chamber", "Point");
        result.put("heat_chamber_reconstruction", "Point");
        result.put("technical_node", "Point");
        result.put("variant_summary", null);
        return result;
    }
}
