package ru.lct.heatroute.domain.input;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.springframework.stereotype.Service;

@Service
public class OfficialGeoJsonInspector {
    public static final String CONTRACT_VERSION = "lct-2026-official-input-v2";
    public static final String STRICT_PROFILE = "strict_official";
    public static final String PROVIDED_DATASET_PROFILE = "provided_dataset_compatibility";
    private static final int MAX_REPORTED_ERRORS = 1_000;
    private static final Set<Integer> OFFICIAL_DIAMETERS = Set.of(
            50, 65, 80, 100, 125, 150, 200, 250, 300,
            400, 500, 600, 700, 800, 900, 1000, 1200, 1400);

    private final ObjectMapper objectMapper;

    public OfficialGeoJsonInspector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OfficialInputReport inspect(InputStream input) {
        MessageDigest digest = sha256();
        Map<OfficialObjectType, Long> counts = new EnumMap<>(OfficialObjectType.class);
        Map<String, OfficialObjectType> identities = new HashMap<>();
        List<PendingReference> references = new ArrayList<>();
        Set<String> futureOksIds = new HashSet<>();
        Set<String> connectedOksIds = new HashSet<>();
        List<OfficialInputError> errors = new ArrayList<>();
        List<OfficialInputWarning> warnings = new ArrayList<>();
        long featureCount = 0;
        boolean featureCollection = false;
        boolean featuresSeen = false;

        try (DigestInputStream digestInput = new DigestInputStream(input, digest);
                JsonParser parser = objectMapper.getFactory().createParser(digestInput)) {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "GeoJSON root must be an object");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("type".equals(fieldName)) {
                    featureCollection = valueToken == JsonToken.VALUE_STRING
                            && "FeatureCollection".equals(parser.getValueAsString());
                } else if ("features".equals(fieldName)) {
                    requireToken(valueToken, JsonToken.START_ARRAY, "features must be an array");
                    featuresSeen = true;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode feature = objectMapper.readTree(parser);
                        validateFeature(
                                feature,
                                featureCount,
                                counts,
                                identities,
                                references,
                                futureOksIds,
                                connectedOksIds,
                                errors,
                                warnings);
                        featureCount++;
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw new OfficialInputFormatException("Unexpected content after GeoJSON root object");
            }
        } catch (IOException exception) {
            throw new OfficialInputFormatException("Malformed or unreadable GeoJSON", exception);
        }

        if (!featureCollection) {
            addError(errors, new OfficialInputError(
                    "INVALID_COLLECTION_TYPE", -1, null, "type", "Root type must be FeatureCollection"));
        }
        if (!featuresSeen) {
            addError(errors, new OfficialInputError(
                    "MISSING_FEATURES", -1, null, "features", "Root features array is required"));
        }
        validateReferences(identities, references, futureOksIds, connectedOksIds, errors);

        Map<String, Long> wireCounts = new LinkedHashMap<>();
        Arrays.stream(OfficialObjectType.values()).forEach(type ->
                wireCounts.put(type.getWireName(), counts.getOrDefault(type, 0L)));
        String inputProfile = counts.getOrDefault(OfficialObjectType.OKS_FUTURE, 0L) > 0
                ? STRICT_PROFILE
                : PROVIDED_DATASET_PROFILE;
        return new OfficialInputReport(
                CONTRACT_VERSION,
                inputProfile,
                hex(digest.digest()),
                featureCount,
                wireCounts,
                errors,
                warnings);
    }

    private void validateFeature(
            JsonNode feature,
            long index,
            Map<OfficialObjectType, Long> counts,
            Map<String, OfficialObjectType> identities,
            List<PendingReference> references,
            Set<String> futureOksIds,
            Set<String> connectedOksIds,
            List<OfficialInputError> errors,
            List<OfficialInputWarning> warnings) {
        if (feature == null || !feature.isObject()) {
            addError(errors, error("INVALID_FEATURE", index, null, null, "Feature must be an object"));
            return;
        }
        if (!"Feature".equals(feature.path("type").asText())) {
            addError(errors, error("INVALID_FEATURE_TYPE", index, null, "type", "Feature type must be Feature"));
        }
        JsonNode properties = feature.get("properties");
        if (properties == null || !properties.isObject()) {
            addError(errors, error("INVALID_PROPERTIES", index, null, "properties", "properties must be an object"));
            return;
        }

        String featureId = requiredIdentifier(properties, "id", index, errors, warnings);
        String objectTypeValue = requiredText(properties, "object_type", index, featureId, errors);
        Optional<OfficialObjectType> objectType = OfficialObjectType.fromWireName(objectTypeValue);
        if (objectType.isEmpty()) {
            addError(errors, error(
                    "UNSUPPORTED_OBJECT_TYPE", index, featureId, "object_type",
                    "Unsupported object_type: " + objectTypeValue));
            return;
        }
        counts.merge(objectType.get(), 1L, Long::sum);

        if (!featureId.isEmpty()) {
            OfficialObjectType previous = identities.putIfAbsent(featureId, objectType.get());
            if (previous != null) {
                addError(errors, error(
                        "DUPLICATE_FEATURE_ID", index, featureId, "id",
                        "Feature id must be unique within the input collection"));
            }
            if (objectType.get() == OfficialObjectType.OKS_FUTURE) {
                futureOksIds.add(featureId);
            }
            if (objectType.get() == OfficialObjectType.OKS_CONNECTION_POINT) {
                String oksId = properties.path("oks_id").asText();
                if (!oksId.isBlank()) {
                    connectedOksIds.add(oksId);
                    references.add(new PendingReference(
                            index, featureId, "oks_id", oksId,
                            Collections.singleton(OfficialObjectType.OKS_FUTURE)));
                } else if (isPositiveNumber(properties.get("flow_tph"))) {
                    addWarning(warnings, warning(
                            "CONNECTION_POINT_AS_DEMAND",
                            index,
                            featureId,
                            "flow_tph",
                            "No oks_future/oks_id is present; this point is used as the demand object"));
                }
            }
            if (objectType.get() == OfficialObjectType.HEAT_NETWORK
                    || objectType.get() == OfficialObjectType.HEAT_CHAMBER) {
                String upstreamId = properties.path("upstream_object_id").asText();
                if (!upstreamId.isBlank()) {
                    references.add(new PendingReference(
                            index, featureId, "upstream_object_id", upstreamId,
                            Set.of(
                                    OfficialObjectType.SOURCE,
                                    OfficialObjectType.HEAT_NETWORK,
                                    OfficialObjectType.HEAT_CHAMBER)));
                }
            }
        }

        JsonNode geometry = feature.get("geometry");
        String geometryType = geometry == null ? "" : geometry.path("type").asText();
        validateGeometry(geometry, geometryType, objectType.get(), properties, index, featureId, errors);
        validateProperties(objectType.get(), properties, index, featureId, errors, warnings);
    }

    private void validateReferences(
            Map<String, OfficialObjectType> identities,
            List<PendingReference> references,
            Set<String> futureOksIds,
            Set<String> connectedOksIds,
            List<OfficialInputError> errors) {
        for (PendingReference reference : references) {
            OfficialObjectType actualType = identities.get(reference.targetId);
            if (actualType == null) {
                addError(errors, error(
                        "UNKNOWN_REFERENCE", reference.index, reference.featureId, reference.field,
                        reference.field + " refers to an unknown feature id: " + reference.targetId));
            } else if (!reference.allowedTypes.contains(actualType)) {
                addError(errors, error(
                        "INVALID_REFERENCE_TYPE", reference.index, reference.featureId, reference.field,
                        reference.field + " points to " + actualType.getWireName()));
            }
        }
        for (String oksId : futureOksIds) {
            if (!connectedOksIds.contains(oksId)) {
                addError(errors, error(
                        "MISSING_OKS_CONNECTION_POINT", -1, oksId, "oks_id",
                        "Every oks_future must have at least one oks_connection_point"));
            }
        }
    }

    private void validateGeometry(
            JsonNode geometry,
            String geometryType,
            OfficialObjectType objectType,
            JsonNode properties,
            long index,
            String featureId,
            List<OfficialInputError> errors) {
        if (geometry == null || !geometry.isObject()) {
            addError(errors, error("MISSING_GEOMETRY", index, featureId, "geometry", "Geometry is required"));
            return;
        }
        Set<String> allowed = allowedGeometryTypes(objectType, properties);
        if (!allowed.contains(geometryType)) {
            addError(errors, error(
                    "INVALID_GEOMETRY_TYPE", index, featureId, "geometry.type",
                    "Expected " + String.join(" or ", allowed) + ", got " + geometryType));
            return;
        }
        try {
            Geometry parsed = new GeoJsonReader().read(geometry.toString());
            if (parsed == null || parsed.isEmpty()) {
                addError(errors, error("EMPTY_GEOMETRY", index, featureId, "geometry", "Geometry must not be empty"));
                return;
            }
            IsValidOp validOp = new IsValidOp(parsed);
            if (!validOp.isValid()) {
                addError(errors, error(
                        "INVALID_GEOMETRY", index, featureId, "geometry",
                        validOp.getValidationError().getMessage()));
            }
            for (Coordinate coordinate : parsed.getCoordinates()) {
                if (!Double.isFinite(coordinate.x) || !Double.isFinite(coordinate.y)
                        || coordinate.x < -180 || coordinate.x > 180
                        || coordinate.y < -90 || coordinate.y > 90) {
                    addError(errors, error(
                            "COORDINATE_OUT_OF_RANGE", index, featureId, "geometry.coordinates",
                            "Coordinates must be finite WGS 84 longitude/latitude values"));
                    break;
                }
            }
        } catch (ParseException | RuntimeException exception) {
            addError(errors, error(
                    "INVALID_GEOMETRY", index, featureId, "geometry", "Geometry cannot be parsed"));
        }
    }

    private Set<String> allowedGeometryTypes(OfficialObjectType objectType, JsonNode properties) {
        switch (objectType) {
            case SOURCE:
            case HEAT_CHAMBER:
            case OKS_CONNECTION_POINT:
                return Collections.singleton("Point");
            case HEAT_NETWORK:
                return Collections.singleton("LineString");
            case OKS_FUTURE:
            case OKS_EXISTING:
                return Set.of("Polygon", "MultiPolygon");
            case RESTRICTION:
                String restrictionType = properties.path("restriction_type").asText();
                return OfficialRestrictionType.fromWireName(restrictionType)
                        .map(type -> Arrays.stream(new String[] {
                                        "Point", "LineString", "MultiLineString", "Polygon", "MultiPolygon"})
                                .filter(type::acceptsGeometry)
                                .collect(Collectors.toSet()))
                        .orElse(Collections.emptySet());
            default:
                return Collections.emptySet();
        }
    }

    private void validateProperties(
            OfficialObjectType type,
            JsonNode properties,
            long index,
            String featureId,
            List<OfficialInputError> errors,
            List<OfficialInputWarning> warnings) {
        switch (type) {
            case HEAT_NETWORK:
                requiredDiameter(properties, index, featureId, errors);
                optionalCompatibilityNumber(properties, "flow_tph", index, featureId, warnings);
                optionalCompatibilityText(properties, "upstream_object_id", index, featureId, warnings);
                break;
            case HEAT_CHAMBER:
                optionalCompatibilityDiameter(properties, index, featureId, errors, warnings);
                optionalCompatibilityText(properties, "upstream_object_id", index, featureId, warnings);
                break;
            case OKS_FUTURE:
                requiredPositiveNumber(properties, "flow_tph", index, featureId, errors);
                requiredPositiveNumber(properties, "heat_load", index, featureId, errors);
                break;
            case OKS_CONNECTION_POINT:
                if (properties.path("oks_id").asText().isBlank()) {
                    requiredPositiveNumber(properties, "flow_tph", index, featureId, errors);
                }
                break;
            case RESTRICTION:
                String value = requiredText(properties, "restriction_type", index, featureId, errors);
                if (!value.isEmpty() && OfficialRestrictionType.fromWireName(value).isEmpty()) {
                    addError(errors, error(
                            "UNSUPPORTED_RESTRICTION_TYPE", index, featureId, "restriction_type",
                            "Unsupported restriction_type: " + value));
                }
                if ("railway".equals(value) || "oks".equals(value)) {
                    addWarning(warnings, warning(
                            "COMPATIBILITY_RESTRICTION_ALIAS",
                            index,
                            featureId,
                            "restriction_type",
                            value + " is accepted from the supplied dataset compatibility profile"));
                }
                break;
            default:
                break;
        }
    }

    private String requiredIdentifier(
            JsonNode properties,
            String field,
            long index,
            List<OfficialInputError> errors,
            List<OfficialInputWarning> warnings) {
        JsonNode value = properties.get(field);
        if (value != null && value.isTextual() && !value.textValue().trim().isEmpty()) {
            return value.textValue();
        }
        if (value != null && value.isIntegralNumber()) {
            String normalized = value.asText();
            addWarning(warnings, warning(
                    "NUMERIC_ID_NORMALIZED",
                    index,
                    normalized,
                    field,
                    "Numeric identifier is normalized to its decimal string representation"));
            return normalized;
        }
        addError(errors, error("MISSING_FIELD", index, null, field, field + " is required"));
        return "";
    }

    private void optionalCompatibilityNumber(
            JsonNode properties,
            String field,
            long index,
            String featureId,
            List<OfficialInputWarning> warnings) {
        if (!isPositiveNumber(properties.get(field))) {
            addWarning(warnings, warning(
                    "MISSING_EXISTING_NETWORK_VALUE",
                    index,
                    featureId,
                    field,
                    field + " is absent; reconstruction calculations remain unavailable"));
        }
    }

    private void optionalCompatibilityText(
            JsonNode properties,
            String field,
            long index,
            String featureId,
            List<OfficialInputWarning> warnings) {
        if (properties.path(field).asText().isBlank()) {
            addWarning(warnings, warning(
                    "MISSING_EXISTING_NETWORK_LINK",
                    index,
                    featureId,
                    field,
                    field + " is absent; direction to source must be inferred from geometry"));
        }
    }

    private void optionalCompatibilityDiameter(
            JsonNode properties,
            long index,
            String featureId,
            List<OfficialInputError> errors,
            List<OfficialInputWarning> warnings) {
        if (properties.get("diameter") == null) {
            addWarning(warnings, warning(
                    "MISSING_CHAMBER_DIAMETER",
                    index,
                    featureId,
                    "diameter",
                    "diameter is absent and must be derived from incident existing-network sections"));
            return;
        }
        requiredDiameter(properties, index, featureId, errors);
    }

    private boolean isPositiveNumber(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                && value.doubleValue() > 0;
    }

    private void requiredDiameter(
            JsonNode properties, long index, String featureId, List<OfficialInputError> errors) {
        JsonNode value = properties.get("diameter");
        if (value == null || !value.isIntegralNumber() || !OFFICIAL_DIAMETERS.contains(value.intValue())) {
            addError(errors, error(
                    "INVALID_DIAMETER", index, featureId, "diameter",
                    "diameter must be one of the 18 official nominal values"));
        }
    }

    private void requiredPositiveNumber(
            JsonNode properties,
            String field,
            long index,
            String featureId,
            List<OfficialInputError> errors) {
        JsonNode value = properties.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())
                || value.doubleValue() <= 0) {
            addError(errors, error(
                    "INVALID_NUMBER", index, featureId, field, field + " must be a positive finite number"));
        }
    }

    private String requiredText(
            JsonNode properties,
            String field,
            long index,
            String featureId,
            List<OfficialInputError> errors) {
        JsonNode value = properties.get(field);
        if (value == null || !value.isTextual() || value.textValue().trim().isEmpty()) {
            addError(errors, error("MISSING_FIELD", index, featureId, field, field + " is required"));
            return "";
        }
        return value.textValue();
    }

    private OfficialInputError error(
            String code, long index, String featureId, String field, String message) {
        return new OfficialInputError(code.toUpperCase(Locale.ROOT), index, featureId, field, message);
    }

    private void addError(List<OfficialInputError> errors, OfficialInputError error) {
        if (errors.size() < MAX_REPORTED_ERRORS) {
            errors.add(error);
        }
    }

    private OfficialInputWarning warning(
            String code, long index, String featureId, String field, String message) {
        return new OfficialInputWarning(code.toUpperCase(Locale.ROOT), index, featureId, field, message);
    }

    private void addWarning(List<OfficialInputWarning> warnings, OfficialInputWarning warning) {
        if (warnings.size() < MAX_REPORTED_ERRORS) {
            warnings.add(warning);
        }
    }

    private void requireToken(JsonToken actual, JsonToken expected, String message) {
        if (actual != expected) {
            throw new OfficialInputFormatException(message);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static final class PendingReference {
        private final long index;
        private final String featureId;
        private final String field;
        private final String targetId;
        private final Set<OfficialObjectType> allowedTypes;

        private PendingReference(
                long index,
                String featureId,
                String field,
                String targetId,
                Set<OfficialObjectType> allowedTypes) {
            this.index = index;
            this.featureId = featureId;
            this.field = field;
            this.targetId = targetId;
            this.allowedTypes = allowedTypes;
        }
    }
}
