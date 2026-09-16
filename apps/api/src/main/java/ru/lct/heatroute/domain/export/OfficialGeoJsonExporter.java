package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.engineering.SpecialCrossingType;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialGeoJsonExporter {
    private static final BigDecimal TWO_DIMENSIONAL_DEPTH_M = new BigDecimal("3.0");

    private final ObjectMapper objectMapper;
    private final OfficialPipeCatalog pipeCatalog;
    private final OfficialEconomics economics;
    private final OfficialOutputContractValidator validator;
    private final OfficialGeoJsonStreamWriter streamWriter;
    private final CoordinateTransform toWgs84;

    public OfficialGeoJsonExporter(
            ObjectMapper objectMapper,
            OfficialPipeCatalog pipeCatalog,
            OfficialEconomics economics,
            OfficialOutputContractValidator validator) {
        this.objectMapper = objectMapper;
        this.pipeCatalog = pipeCatalog;
        this.economics = economics;
        this.validator = validator;
        this.streamWriter = new OfficialGeoJsonStreamWriter(objectMapper);
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem metric = factory.createFromParameters(
                "UTM37N", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs");
        CoordinateReferenceSystem wgs84 = factory.createFromParameters(
                "WGS84", "+proj=longlat +datum=WGS84 +no_defs");
        this.toWgs84 = new CoordinateTransformFactory().createTransform(metric, wgs84);
    }

    public ObjectNode export(JsonNode calculation, List<ImportedOfficialFeature> inputFeatures) {
        ObjectNode collection = objectMapper.createObjectNode();
        collection.put("type", "FeatureCollection");
        ArrayNode output = collection.putArray("features");
        forEachFeature(calculation, inputFeatures, null, output::add);
        assertValid(validator.validate(collection));
        return collection;
    }

    public void validate(JsonNode calculation, List<ImportedOfficialFeature> inputFeatures) {
        OfficialOutputContractValidator.ValidationSession session = validator.begin();
        forEachFeature(calculation, inputFeatures, null, session::accept);
        assertValid(session.finish());
    }

    public void validateVariant(
            JsonNode calculation,
            List<ImportedOfficialFeature> inputFeatures,
            String variantId) {
        OfficialOutputContractValidator.ValidationSession session = validator.begin();
        forEachFeature(calculation, inputFeatures, variantId, session::accept);
        assertValid(session.finish());
    }

    public void writeValidated(
            JsonNode calculation,
            List<ImportedOfficialFeature> inputFeatures,
            OutputStream outputStream) throws IOException {
        streamWriter.writeFeatureCollection(
                outputStream,
                output -> forEachFeature(calculation, inputFeatures, null, output));
    }

    public void writeValidatedVariant(
            JsonNode calculation,
            List<ImportedOfficialFeature> inputFeatures,
            String variantId,
            OutputStream outputStream) throws IOException {
        streamWriter.writeFeatureCollection(
                outputStream,
                output -> forEachFeature(calculation, inputFeatures, variantId, output));
    }

    private void forEachFeature(
            JsonNode calculation,
            List<ImportedOfficialFeature> inputFeatures,
            String selectedVariantId,
            Consumer<ObjectNode> output) {
        Map<String, ImportedOfficialFeature> inputById = inputFeatures.stream().collect(Collectors.toMap(
                ImportedOfficialFeature::getFeatureId,
                feature -> feature,
                (left, right) -> left,
                LinkedHashMap::new));
        List<JsonNode> variants = new ArrayList<>();
        calculation.path("variants").forEach(variant -> {
            if (variant.path("valid").asBoolean()
                    && variant.path("economics").path("complete").asBoolean()
                    && variant.path("rank").isInt()
                    && (selectedVariantId == null
                            || selectedVariantId.equals(variant.path("id").asText()))) {
                variants.add(variant);
            }
        });
        variants.sort(Comparator.comparingInt(variant -> variant.path("rank").asInt()));
        if (variants.isEmpty()) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: no fully costed ranked variant");
        }
        for (JsonNode variant : variants) {
            appendVariant(output, variant, inputById);
        }
    }

    private void assertValid(List<String> issues) {
        if (!issues.isEmpty()) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INVALID: " + String.join("; ", issues));
        }
    }

    private void appendVariant(
            Consumer<ObjectNode> output,
            JsonNode variant,
            Map<String, ImportedOfficialFeature> inputById) {
        String variantId = variant.path("id").asText();
        Map<String, JsonNode> nodes = new HashMap<>();
        variant.path("nodes").forEach(node -> nodes.put(node.path("id").asText(), node));
        Map<String, Integer> maxDiameterByNode = maximumDiameterByNode(variant.path("edges"));
        Map<String, double[]> generatedTechnicalNodes = new LinkedHashMap<>();

        for (JsonNode edge : variant.path("edges")) {
            appendEdgeSections(output, variantId, edge, nodes, generatedTechnicalNodes);
        }
        for (JsonNode node : variant.path("nodes")) {
            String nodeType = node.path("node_type").asText();
            if (node.path("root").asBoolean()) {
                appendTieIn(output, variantId, node, maxDiameterByNode, inputById);
            }
            if (node.path("chamber").asBoolean() && nodeType.startsWith("new_")) {
                appendNewChamber(output, variantId, node, maxDiameterByNode);
            }
            if (!node.path("chamber").asBoolean()) {
                appendTechnicalNode(output, variantId, node.path("id").asText(), coordinate(node.path("coordinate")));
            }
        }
        generatedTechnicalNodes.forEach((id, coordinate) ->
                appendTechnicalNode(output, variantId, id, coordinate));
        appendReconstruction(output, variantId, variant.path("reconstruction"));
        appendSummary(output, variantId, variant);
    }

    private void appendEdgeSections(
            Consumer<ObjectNode> output,
            String variantId,
            JsonNode edge,
            Map<String, JsonNode> nodes,
            Map<String, double[]> technicalNodes) {
        List<JsonNode> sections = new ArrayList<>();
        edge.path("sections").forEach(sections::add);
        if (sections.isEmpty()) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("kind", "base");
            fallback.set("coordinates", edge.path("coordinates"));
            fallback.set("length_m", edge.path("length_m"));
            sections.add(fallback);
        }
        int diameter = edge.path("diameter").asInt();
        PipeCatalogEntry pipe = pipeCatalog.byDiameter(diameter).orElseThrow();
        JsonNode depthProfile = edge.path("depth_profile");
        BigDecimal edgeLength = edge.path("length_m").decimalValue();
        BigDecimal sectionTotal = sections.stream()
                .map(section -> section.path("length_m").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int index = 0; index < sections.size(); index++) {
            JsonNode section = sections.get(index);
            BigDecimal sectionStart = edgeLength.multiply(cumulative)
                    .divide(sectionTotal, 12, RoundingMode.HALF_UP);
            cumulative = cumulative.add(section.path("length_m").decimalValue());
            BigDecimal sectionEnd = index == sections.size() - 1
                    ? edgeLength
                    : edgeLength.multiply(cumulative).divide(sectionTotal, 12, RoundingMode.HALF_UP);
            BigDecimal depthStart = depthAt(depthProfile, sectionStart);
            BigDecimal depthEnd = depthAt(depthProfile, sectionEnd);
            BigDecimal averageDepth = averageDepth(depthProfile, sectionStart, sectionEnd);
            String previousBoundary = outputId(variantId,
                    "technical:" + edge.path("id").asText() + ":" + index);
            String nextBoundary = outputId(variantId,
                    "technical:" + edge.path("id").asText() + ":" + (index + 1));
            String startNode = index == 0
                    ? outputId(variantId, edge.path("upstream_node_id").asText())
                    : previousBoundary;
            String endNode = index == sections.size() - 1
                    ? outputId(variantId, edge.path("downstream_node_id").asText())
                    : nextBoundary;
            if (index > 0) {
                technicalNodes.putIfAbsent(previousBoundary, firstCoordinate(section.path("coordinates")));
            }
            if (index < sections.size() - 1) {
                technicalNodes.putIfAbsent(nextBoundary, lastCoordinate(section.path("coordinates")));
            }
            String kind = section.path("kind").asText("base");
            SpecialCrossingType crossing = crossingType(kind, section.path("restriction_type").asText(null));
            BigDecimal length = section.path("length_m").decimalValue();
            BigDecimal cost = economics.newNetworkCost(pipe, length, crossing, averageDepth);
            ObjectNode properties = properties(
                    "network:" + variantId + ":" + edge.path("id").asText() + ":" + index,
                    "heat_network",
                    variantId);
            properties.put("start_node_id", startNode);
            properties.put("end_node_id", endNode);
            properties.set("flow_tph", edge.path("flow_tph"));
            properties.put("diameter", diameter);
            properties.set("length", section.path("length_m"));
            properties.put("laying_method", kind);
            properties.set("cost", objectMapper.valueToTree(cost));
            properties.set("depth_start", objectMapper.valueToTree(depthStart));
            properties.set("depth_end", objectMapper.valueToTree(depthEnd));
            output.accept(feature(lineGeometry(
                    section.path("coordinates"),
                    depthProfile,
                    sectionStart,
                    sectionEnd,
                    pipe.getEnvelopeHeightM()), properties));
        }
    }

    private void appendTieIn(
            Consumer<ObjectNode> output,
            String variantId,
            JsonNode node,
            Map<String, Integer> maxDiameterByNode,
            Map<String, ImportedOfficialFeature> inputById) {
        String targetId = node.path("target_id").asText();
        ImportedOfficialFeature target = inputById.get(targetId);
        if (target == null || !target.getAttributes().path("diameter").isNumber()) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: tie-in baseline is missing for " + targetId);
        }
        ObjectNode properties = properties(outputId(variantId, node.path("id").asText()), "tie_in", variantId);
        properties.put("existing_object_id", targetId);
        properties.put("existing_object_type", target.getObjectType());
        properties.put("existing_diameter", target.getAttributes().path("diameter").asInt());
        properties.put("required_diameter", maxDiameterByNode.get(node.path("id").asText()));
        properties.set("cost", objectMapper.valueToTree(economics.tieInCost()));
        output.accept(feature(pointGeometry(coordinate(node.path("coordinate"))), properties));
    }

    private void appendNewChamber(
            Consumer<ObjectNode> output,
            String variantId,
            JsonNode node,
            Map<String, Integer> maxDiameterByNode) {
        String sourceId = node.path("id").asText();
        int diameter = maxDiameterByNode.get(sourceId);
        String id = node.path("root").asBoolean()
                ? outputId(variantId, "chamber:" + sourceId)
                : outputId(variantId, sourceId);
        ObjectNode properties = properties(id, "heat_chamber", variantId);
        properties.put("diameter", diameter);
        properties.set("cost", objectMapper.valueToTree(economics.chamberCost(diameter)));
        output.accept(feature(pointGeometry(coordinate(node.path("coordinate"))), properties));
    }

    private void appendTechnicalNode(Consumer<ObjectNode> output, String variantId, String id, double[] metric) {
        String scopedId = id.startsWith(variantId + ":") ? id : outputId(variantId, id);
        output.accept(feature(pointGeometry(metric), properties(scopedId, "technical_node", variantId)));
    }

    private void appendReconstruction(Consumer<ObjectNode> output, String variantId, JsonNode reconstruction) {
        for (JsonNode section : reconstruction.path("network_sections")) {
            int requiredDiameter = section.path("required_diameter").asInt();
            PipeCatalogEntry pipe = pipeCatalog.byDiameter(requiredDiameter).orElseThrow();
            ObjectNode properties = properties(outputId(variantId,
                    "reconstruction:" + section.path("id").asText()),
                    "heat_network_reconstruction", variantId);
            properties.set("existing_object_id", section.path("existing_feature_id"));
            properties.set("existing_flow_tph", section.path("existing_flow_tph"));
            properties.set("added_flow_tph", section.path("added_flow_tph"));
            properties.set("calculated_flow_tph", section.path("resulting_flow_tph"));
            properties.set("existing_diameter", section.path("existing_diameter"));
            properties.put("required_diameter", requiredDiameter);
            properties.set("length", section.path("length_m"));
            properties.set("cost", objectMapper.valueToTree(economics.reconstructionCost(
                    pipe, section.path("length_m").decimalValue())));
            output.accept(feature(lineGeometry(section.path("coordinates")), properties));
        }
        for (JsonNode chamber : reconstruction.path("chambers")) {
            int requiredDiameter = chamber.path("required_diameter").asInt();
            ObjectNode properties = properties(
                    outputId(variantId,
                            "chamber-reconstruction:" + chamber.path("existing_feature_id").asText()),
                    "heat_chamber_reconstruction",
                    variantId);
            properties.set("existing_object_id", chamber.path("existing_feature_id"));
            properties.set("existing_diameter", chamber.path("existing_diameter"));
            properties.put("required_diameter", requiredDiameter);
            properties.set("cost", objectMapper.valueToTree(economics.chamberCost(requiredDiameter)));
            output.accept(feature(pointGeometry(coordinate(chamber.path("coordinate"))), properties));
        }
    }

    private void appendSummary(Consumer<ObjectNode> output, String variantId, JsonNode variant) {
        JsonNode economicsNode = variant.path("economics");
        ObjectNode properties = properties("summary:" + variantId, "variant_summary", variantId);
        properties.set("rank", variant.path("rank"));
        for (String field : List.of(
                "construction_cost", "chamber_construction_cost", "tie_in_cost",
                "reconstruction_cost", "chamber_reconstruction_cost", "unconnected_penalty",
                "calculated_cost", "new_network_length", "reconstruction_length", "length", "score")) {
            properties.set(field, economicsNode.path(field));
        }
        ArrayNode unconnected = properties.putArray("unconnected_oks_ids");
        variant.path("connections").forEach(connection -> {
            if ("no_route".equals(connection.path("status").asText())) {
                unconnected.add(connection.path("demand_id").asText());
            }
        });
        output.accept(feature(null, properties));
    }

    private Map<String, Integer> maximumDiameterByNode(JsonNode edges) {
        Map<String, Integer> result = new HashMap<>();
        edges.forEach(edge -> {
            int diameter = edge.path("diameter").asInt();
            result.merge(edge.path("upstream_node_id").asText(), diameter, Math::max);
            result.merge(edge.path("downstream_node_id").asText(), diameter, Math::max);
        });
        return result;
    }

    private ObjectNode properties(String id, String objectType, String variantId) {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("id", id);
        properties.put("object_type", objectType);
        properties.put("variant_id", variantId);
        return properties;
    }

    private String outputId(String variantId, String sourceId) {
        return variantId + ":" + sourceId;
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

    private ObjectNode pointGeometry(double[] metric) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "Point");
        ArrayNode coordinates = geometry.putArray("coordinates");
        double[] wgs = transform(metric[0], metric[1]);
        coordinates.add(wgs[0]);
        coordinates.add(wgs[1]);
        return geometry;
    }

    private ObjectNode lineGeometry(JsonNode metricCoordinates) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "LineString");
        ArrayNode coordinates = geometry.putArray("coordinates");
        metricCoordinates.forEach(coordinate -> {
            double[] wgs = transform(coordinate.path("xm").asDouble(), coordinate.path("ym").asDouble());
            ArrayNode pair = coordinates.addArray();
            pair.add(wgs[0]);
            pair.add(wgs[1]);
        });
        return geometry;
    }

    private ObjectNode lineGeometry(
            JsonNode metricCoordinates,
            JsonNode depthProfile,
            BigDecimal sectionStart,
            BigDecimal sectionEnd,
            BigDecimal envelopeHeightM) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "LineString");
        ArrayNode coordinates = geometry.putArray("coordinates");
        double total = coordinateLength(metricCoordinates);
        double cumulative = 0.0;
        for (int index = 0; index < metricCoordinates.size(); index++) {
            JsonNode coordinate = metricCoordinates.path(index);
            if (index > 0) cumulative += coordinateDistance(metricCoordinates.path(index - 1), coordinate);
            BigDecimal fraction = total <= 1e-9
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(cumulative / total);
            BigDecimal station = sectionStart.add(sectionEnd.subtract(sectionStart).multiply(fraction));
            BigDecimal depth = depthAt(depthProfile, station);
            BigDecimal axisZ = depth.add(envelopeHeightM.divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP))
                    .negate().setScale(3, RoundingMode.HALF_UP);
            double[] wgs = transform(coordinate.path("xm").asDouble(), coordinate.path("ym").asDouble());
            ArrayNode position = coordinates.addArray();
            position.add(wgs[0]);
            position.add(wgs[1]);
            position.add(axisZ);
        }
        return geometry;
    }

    private BigDecimal depthAt(JsonNode profile, BigDecimal station) {
        JsonNode points = profile.path("points");
        if (!points.isArray() || points.size() < 2) return TWO_DIMENSIONAL_DEPTH_M;
        for (int index = 1; index < points.size(); index++) {
            JsonNode left = points.path(index - 1);
            JsonNode right = points.path(index);
            BigDecimal leftStation = left.path("station_m").decimalValue();
            BigDecimal rightStation = right.path("station_m").decimalValue();
            if (station.compareTo(rightStation) > 0) continue;
            BigDecimal run = rightStation.subtract(leftStation);
            if (run.signum() == 0) return left.path("depth_m").decimalValue();
            BigDecimal fraction = station.subtract(leftStation).divide(run, 12, RoundingMode.HALF_UP);
            return left.path("depth_m").decimalValue().add(
                    right.path("depth_m").decimalValue()
                            .subtract(left.path("depth_m").decimalValue())
                            .multiply(fraction))
                    .setScale(3, RoundingMode.HALF_UP);
        }
        return points.path(points.size() - 1).path("depth_m").decimalValue();
    }

    private BigDecimal averageDepth(JsonNode profile, BigDecimal start, BigDecimal end) {
        if (!profile.path("points").isArray()) return TWO_DIMENSIONAL_DEPTH_M;
        List<BigDecimal> stations = new ArrayList<>();
        stations.add(start);
        profile.path("points").forEach(point -> {
            BigDecimal station = point.path("station_m").decimalValue();
            if (station.compareTo(start) > 0 && station.compareTo(end) < 0) stations.add(station);
        });
        stations.add(end);
        stations.sort(BigDecimal::compareTo);
        BigDecimal integral = BigDecimal.ZERO;
        for (int index = 1; index < stations.size(); index++) {
            BigDecimal left = stations.get(index - 1);
            BigDecimal right = stations.get(index);
            BigDecimal mean = depthAt(profile, left).add(depthAt(profile, right))
                    .divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
            integral = integral.add(mean.multiply(right.subtract(left)));
        }
        return integral.divide(end.subtract(start), 12, RoundingMode.HALF_UP)
                .setScale(3, RoundingMode.HALF_UP);
    }

    private double coordinateLength(JsonNode coordinates) {
        double result = 0.0;
        for (int index = 1; index < coordinates.size(); index++) {
            result += coordinateDistance(coordinates.path(index - 1), coordinates.path(index));
        }
        return result;
    }

    private double coordinateDistance(JsonNode left, JsonNode right) {
        return Math.hypot(
                right.path("xm").asDouble() - left.path("xm").asDouble(),
                right.path("ym").asDouble() - left.path("ym").asDouble());
    }

    private double[] coordinate(JsonNode coordinate) {
        return new double[]{coordinate.path("xm").asDouble(), coordinate.path("ym").asDouble()};
    }

    private double[] firstCoordinate(JsonNode coordinates) {
        return coordinate(coordinates.path(0));
    }

    private double[] lastCoordinate(JsonNode coordinates) {
        return coordinate(coordinates.path(coordinates.size() - 1));
    }

    private double[] transform(double x, double y) {
        ProjCoordinate result = new ProjCoordinate();
        toWgs84.transform(new ProjCoordinate(x, y), result);
        return new double[]{result.x, result.y};
    }

    private SpecialCrossingType crossingType(String kind, String restrictionType) {
        if (!"special".equals(kind) || restrictionType == null) return SpecialCrossingType.BASE;
        switch (restrictionType) {
            case "road": return SpecialCrossingType.ROAD;
            case "tram_tracks": return SpecialCrossingType.TRAM_TRACKS;
            case "gas_pipeline": return SpecialCrossingType.GAS_PIPELINE;
            case "power_cable": return SpecialCrossingType.POWER_CABLE;
            case "heat_network": return SpecialCrossingType.HEAT_NETWORK;
            default: throw new IllegalStateException("Unsupported special crossing type " + restrictionType);
        }
    }
}
