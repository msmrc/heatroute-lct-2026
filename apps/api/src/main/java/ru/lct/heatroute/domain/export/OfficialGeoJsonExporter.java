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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialGeoJsonExporter {
    private static final BigDecimal TWO_DIMENSIONAL_DEPTH_M = new BigDecimal("3.0");

    private final ObjectMapper objectMapper;
    private final OfficialPipeCatalog pipeCatalog;
    private final OfficialEconomics economics;
    private final OfficialOutputContractValidator validator;
    private final OfficialVariantEconomicsCalculator economicsCalculator;
    private final OfficialGeoJsonStreamWriter streamWriter;
    private final CoordinateTransform toWgs84;

    public OfficialGeoJsonExporter(
            ObjectMapper objectMapper,
            OfficialPipeCatalog pipeCatalog,
            OfficialEconomics economics,
            OfficialOutputContractValidator validator,
            OfficialVariantEconomicsCalculator economicsCalculator) {
        this.objectMapper = objectMapper;
        this.pipeCatalog = pipeCatalog;
        this.economics = economics;
        this.validator = validator;
        this.economicsCalculator = economicsCalculator;
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
        assertValid(validator.validate(collection, allowsMissingTieInDiameter(calculation)));
        return collection;
    }

    public void validate(JsonNode calculation, List<ImportedOfficialFeature> inputFeatures) {
        OfficialOutputContractValidator.ValidationSession session = validator.begin(
                allowsMissingTieInDiameter(calculation));
        forEachFeature(calculation, inputFeatures, null, session::accept);
        assertValid(session.finish());
    }

    public void validateVariant(
            JsonNode calculation,
            List<ImportedOfficialFeature> inputFeatures,
            String variantId) {
        OfficialOutputContractValidator.ValidationSession session = validator.begin(
                allowsMissingTieInDiameter(calculation));
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
        boolean allowMissingTieInDiameter = allowsMissingTieInDiameter(calculation);
        ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex support =
                new ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex(inputFeatures);
        Map<JsonNode, Map<String, Integer>> chamberDiameters = new java.util.IdentityHashMap<>();
        // Проверяем все выбранные варианты до передачи первой feature потребителю потока.
        for (JsonNode variant : variants) {
            chamberDiameters.put(variant, SavedChamberAssessment.verify(variant, support, economics));
        }
        for (JsonNode variant : variants) {
            appendVariant(output, variant, inputById, allowMissingTieInDiameter, chamberDiameters.get(variant));
        }
    }

    private boolean allowsMissingTieInDiameter(JsonNode calculation) {
        return OfficialGeoJsonInspector.isBaselineInputProfile(calculation.path("input_profile").asText());
    }

    private void assertValid(List<String> issues) {
        if (!issues.isEmpty()) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INVALID: " + String.join("; ", issues));
        }
    }

    private void appendVariant(
            Consumer<ObjectNode> output,
            JsonNode variant,
            Map<String, ImportedOfficialFeature> inputById,
            boolean allowMissingTieInDiameter,
            Map<String, Integer> maxDiameterByNode) {
        String variantId = variant.path("id").asText();
        Map<String, JsonNode> nodes = new HashMap<>();
        variant.path("nodes").forEach(node -> nodes.put(node.path("id").asText(), node));
        Map<String, double[]> generatedTechnicalNodes = new LinkedHashMap<>();
        BigDecimal[] physicalCost = {BigDecimal.ZERO};
        Consumer<ObjectNode> checkedOutput = feature -> {
            JsonNode properties = feature.path("properties");
            if (Set.of("heat_network", "heat_chamber").contains(properties.path("object_type").asText())) {
                physicalCost[0] = physicalCost[0].add(properties.path("cost").decimalValue());
            }
            output.accept(feature);
        };

        for (JsonNode edge : variant.path("edges")) {
            appendEdgeSections(checkedOutput, variantId, edge, nodes, generatedTechnicalNodes);
        }
        for (JsonNode node : variant.path("nodes")) {
            String nodeType = node.path("node_type").asText();
            if (node.path("chamber").asBoolean() && nodeType.startsWith("new_")) {
                appendNewChamber(checkedOutput, variantId, node, maxDiameterByNode);
            }
            if (!node.path("chamber").asBoolean()
                    || node.path("root").asBoolean() && !nodeType.startsWith("new_")) {
                appendTechnicalNode(output, variantId, node.path("id").asText(), coordinate(node.path("coordinate")));
            }
        }
        generatedTechnicalNodes.forEach((id, coordinate) ->
                appendTechnicalNode(output, variantId, id, coordinate));
        BigDecimal expected = physicalCost[0].add(variant.path("economics").path("tie_in_cost").decimalValue());
        if (expected.compareTo(variant.path("economics").path("construction_cost").decimalValue()) != 0) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: recalculate variant " + variantId
                    + "; exported construction components disagree with saved economics");
        }
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
        JsonNode depthProfile = edge.path("depth_profile");
        if (!hasDepthProfile(depthProfile)) {
            sections = mergeEquivalentSections(sections);
        }
        int diameter = edge.path("diameter").asInt();
        PipeCatalogEntry pipe = pipeCatalog.byDiameter(diameter).orElseThrow();
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
            String kind = section.path("kind").asText("base");
            SpecialCrossingType crossing = crossingType(kind, section.path("restriction_type").asText(null));
            double geometryLength = coordinateLength(section.path("coordinates"));
            if (geometryLength <= 1e-9) continue;
            BigDecimal sectionSpan = sectionEnd.subtract(sectionStart);
            if (sectionSpan.signum() == 0) continue;
            List<BigDecimal> cuts = profileCuts(depthProfile, sectionStart, sectionEnd);
            for (int piece = 1; piece < cuts.size(); piece++) {
                BigDecimal pieceStart = cuts.get(piece - 1);
                BigDecimal pieceEnd = cuts.get(piece);
                String startNode = pieceStart.signum() == 0
                        ? endpointId(variantId, edge, nodes, true)
                        : technicalNodeId(
                                variantId,
                                edge.path("id").asText(),
                                pieceStart,
                                isDepthProfileBreakpoint(depthProfile, pieceStart));
                String endNode = pieceEnd.compareTo(edgeLength) == 0
                        ? endpointId(variantId, edge, nodes, false)
                        : technicalNodeId(
                                variantId,
                                edge.path("id").asText(),
                                pieceEnd,
                                isDepthProfileBreakpoint(depthProfile, pieceEnd));
                if (pieceStart.signum() > 0) {
                    technicalNodes.putIfAbsent(startNode, metricAtStation(
                            section.path("coordinates"), sectionStart, sectionEnd, pieceStart));
                }
                if (pieceEnd.compareTo(edgeLength) < 0) {
                    technicalNodes.putIfAbsent(endNode, metricAtStation(
                            section.path("coordinates"), sectionStart, sectionEnd, pieceEnd));
                }
                ExportPieceMeasure measure = measureSectionPiece(
                        section, section.path("length_m").decimalValue(),
                        sectionStart, sectionEnd, pieceStart, pieceEnd,
                        pipe, crossing, depthProfile);
                ObjectNode properties = properties(
                        "network:" + variantId + ":" + edge.path("id").asText()
                                + ":" + index + ":" + (piece - 1),
                        "heat_network",
                        variantId);
                properties.put("start_node_id", startNode);
                properties.put("end_node_id", endNode);
                properties.set("flow_tph", edge.path("flow_tph"));
                properties.put("diameter", diameter);
                properties.set("length", objectMapper.valueToTree(measure.length));
                properties.put("laying_method", kind);
                properties.set("cost", objectMapper.valueToTree(measure.cost));
                if (hasDepthProfile(depthProfile)) {
                    properties.set("depth_start", objectMapper.valueToTree(depthAt(depthProfile, pieceStart)));
                    properties.set("depth_end", objectMapper.valueToTree(depthAt(depthProfile, pieceEnd)));
                    output.accept(feature(lineGeometry(
                            section.path("coordinates"), depthProfile, sectionStart, sectionEnd,
                            pieceStart, pieceEnd, pipe.getEnvelopeHeightM()), properties));
                } else {
                    output.accept(feature(lineGeometry2d(
                            section.path("coordinates"), sectionStart, sectionEnd, pieceStart, pieceEnd),
                            properties));
                }
            }
        }
    }

    /**
     * Consecutive 2D sections with identical export properties are one physical pipe section.
     * Their intermediate routing vertices stay inside the LineString and do not become facilities.
     */
    private List<JsonNode> mergeEquivalentSections(List<JsonNode> sections) {
        List<JsonNode> merged = new ArrayList<>();
        ObjectNode current = null;
        for (JsonNode section : sections) {
            String kind = section.path("kind").asText("base");
            String restrictionType = section.path("restriction_type").asText("");
            if (current == null
                    || !kind.equals(current.path("kind").asText("base"))
                    || !restrictionType.equals(current.path("restriction_type").asText(""))) {
                current = objectMapper.createObjectNode();
                current.put("kind", kind);
                if (!restrictionType.isEmpty()) current.put("restriction_type", restrictionType);
                current.set("length_m", section.path("length_m").deepCopy());
                current.set("coordinates", section.path("coordinates").deepCopy());
                current.putArray("_source_sections").add(section.deepCopy());
                merged.add(current);
                continue;
            }
            current.put("length_m", current.path("length_m").decimalValue()
                    .add(section.path("length_m").decimalValue()));
            appendCoordinates((ArrayNode) current.path("coordinates"), section.path("coordinates"));
            ((ArrayNode) current.path("_source_sections")).add(section.deepCopy());
        }
        return merged;
    }

    private void appendCoordinates(ArrayNode target, JsonNode source) {
        for (int index = 0; index < source.size(); index++) {
            JsonNode coordinate = source.path(index);
            if (index == 0 && !target.isEmpty() && target.path(target.size() - 1).equals(coordinate)) continue;
            target.add(coordinate.deepCopy());
        }
    }

    private String endpointId(
            String variantId, JsonNode edge, Map<String, JsonNode> nodes, boolean upstream) {
        String nodeId = edge.path(upstream ? "upstream_node_id" : "downstream_node_id").asText();
        JsonNode node = nodes.get(nodeId);
        if (upstream && node != null && node.path("root").asBoolean()
                && node.path("node_type").asText().startsWith("new_")) {
            return outputId(variantId, "chamber:" + nodeId);
        }
        return outputId(variantId, nodeId);
    }

    private void appendTieIns(
            Consumer<ObjectNode> output,
            String variantId,
            JsonNode nodes,
            JsonNode edges,
            Map<String, ImportedOfficialFeature> inputById,
            boolean allowMissingTieInDiameter) {
        Map<String, JsonNode> roots = new HashMap<>();
        nodes.forEach(node -> {
            if (node.path("root").asBoolean()) roots.put(node.path("id").asText(), node);
        });
        edges.forEach(edge -> {
            JsonNode root = roots.get(edge.path("upstream_node_id").asText());
            if (root != null) {
                appendTieIn(output, variantId, root, edge, inputById, allowMissingTieInDiameter);
            }
        });
    }

    private void appendTieIn(
            Consumer<ObjectNode> output,
            String variantId,
            JsonNode node,
            JsonNode edge,
            Map<String, ImportedOfficialFeature> inputById,
            boolean allowMissingTieInDiameter) {
        String targetId = node.path("target_id").asText();
        ImportedOfficialFeature target = inputById.get(targetId);
        if (target == null) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: tie-in baseline is missing for " + targetId);
        }
        boolean hasExistingDiameter = target.getAttributes().path("diameter").isNumber();
        if (!hasExistingDiameter && !allowMissingTieInDiameter) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: tie-in baseline is missing for " + targetId);
        }
        ObjectNode properties = properties(tieInId(
                variantId, node.path("id").asText(), edge.path("id").asText()), "tie_in", variantId);
        properties.put("existing_object_id", targetId);
        properties.put("existing_object_type", target.getObjectType());
        if (hasExistingDiameter) {
            properties.put("existing_diameter", target.getAttributes().path("diameter").asInt());
        }
        properties.put("required_diameter", edge.path("diameter").asInt());
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
                "construction_cost", "chamber_construction_cost", "unconnected_penalty",
                "calculated_cost", "new_network_length", "score")) {
            properties.set(field, economicsNode.path(field));
        }
        properties.put("existing_chamber_tie_in_count", existingChamberTieInCount(variant));
        properties.set("existing_chamber_tie_in_cost", economicsNode.path("tie_in_cost"));
        ArrayNode unconnected = properties.putArray("unconnected_oks_ids");
        variant.path("connections").forEach(connection -> {
            if ("no_route".equals(connection.path("status").asText())) {
                unconnected.add(connection.path("demand_id").asText());
            }
        });
        output.accept(feature(null, properties));
    }

    private long existingChamberTieInCount(JsonNode variant) {
        Set<String> existingRoots = new HashSet<>();
        variant.path("nodes").forEach(node -> {
            if (node.path("root").asBoolean()
                    && "existing_chamber_tie_in".equals(node.path("node_type").asText())) {
                existingRoots.add(node.path("id").asText());
            }
        });
        long count = 0;
        for (JsonNode edge : variant.path("edges")) {
            if (existingRoots.contains(edge.path("upstream_node_id").asText())) count++;
        }
        return count;
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

    private String tieInId(String variantId, String rootNodeId, String edgeId) {
        return outputId(variantId, "tie-in:" + rootNodeId + ":" + edgeId);
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
            BigDecimal pieceStart,
            BigDecimal pieceEnd,
            BigDecimal envelopeHeightM) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "LineString");
        ArrayNode coordinates = geometry.putArray("coordinates");
        double total = coordinateLength(metricCoordinates);
        BigDecimal sectionSpan = sectionEnd.subtract(sectionStart);
        java.util.SortedSet<BigDecimal> fractions = new java.util.TreeSet<>();
        BigDecimal pieceStartFraction = pieceStart.subtract(sectionStart)
                .divide(sectionSpan, 12, RoundingMode.HALF_UP);
        BigDecimal pieceEndFraction = pieceEnd.subtract(sectionStart)
                .divide(sectionSpan, 12, RoundingMode.HALF_UP);
        fractions.add(pieceStartFraction);
        fractions.add(pieceEndFraction);
        double cumulative = 0.0;
        for (int index = 0; index < metricCoordinates.size(); index++) {
            if (index > 0) cumulative += coordinateDistance(
                    metricCoordinates.path(index - 1), metricCoordinates.path(index));
            BigDecimal fraction = total <= 1e-9
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(cumulative / total);
            if (fraction.compareTo(pieceStartFraction) > 0 && fraction.compareTo(pieceEndFraction) < 0) {
                fractions.add(fraction);
            }
        }
        depthProfile.path("points").forEach(point -> {
            BigDecimal station = point.path("station_m").decimalValue();
            if (station.compareTo(pieceStart) > 0 && station.compareTo(pieceEnd) < 0) {
                fractions.add(station.subtract(sectionStart)
                        .divide(sectionSpan, 12, RoundingMode.HALF_UP));
            }
        });
        for (BigDecimal fraction : fractions) {
            BigDecimal station = sectionStart.add(sectionEnd.subtract(sectionStart).multiply(fraction));
            BigDecimal depth = depthAt(depthProfile, station);
            BigDecimal axisZ = depth.add(envelopeHeightM.divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP))
                    .negate().setScale(3, RoundingMode.HALF_UP);
            double[] metric = metricAtFraction(metricCoordinates, fraction.doubleValue(), total);
            double[] wgs = transform(metric[0], metric[1]);
            ArrayNode position = coordinates.addArray();
            position.add(wgs[0]);
            position.add(wgs[1]);
            position.add(axisZ);
        }
        return geometry;
    }

    private ObjectNode lineGeometry2d(
            JsonNode metricCoordinates,
            BigDecimal sectionStart,
            BigDecimal sectionEnd,
            BigDecimal pieceStart,
            BigDecimal pieceEnd) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "LineString");
        ArrayNode coordinates = geometry.putArray("coordinates");
        double total = coordinateLength(metricCoordinates);
        BigDecimal span = sectionEnd.subtract(sectionStart);
        BigDecimal startFraction = pieceStart.subtract(sectionStart).divide(span, 12, RoundingMode.HALF_UP);
        BigDecimal endFraction = pieceEnd.subtract(sectionStart).divide(span, 12, RoundingMode.HALF_UP);
        java.util.SortedSet<BigDecimal> fractions = new java.util.TreeSet<>();
        fractions.add(startFraction);
        fractions.add(endFraction);
        double cumulative = 0.0;
        for (int index = 1; index < metricCoordinates.size(); index++) {
            cumulative += coordinateDistance(metricCoordinates.path(index - 1), metricCoordinates.path(index));
            BigDecimal fraction = total <= 1e-9
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(cumulative / total);
            if (fraction.compareTo(startFraction) > 0 && fraction.compareTo(endFraction) < 0) {
                fractions.add(fraction);
            }
        }
        for (BigDecimal fraction : fractions) {
            double[] metric = metricAtFraction(metricCoordinates, fraction.doubleValue(), total);
            double[] wgs = transform(metric[0], metric[1]);
            ArrayNode position = coordinates.addArray();
            position.add(wgs[0]);
            position.add(wgs[1]);
        }
        return geometry;
    }

    private ExportPieceMeasure measureSectionPiece(
            JsonNode section,
            BigDecimal sectionLength,
            BigDecimal sectionStart,
            BigDecimal sectionEnd,
            BigDecimal pieceStart,
            BigDecimal pieceEnd,
            PipeCatalogEntry pipe,
            SpecialCrossingType crossing,
            JsonNode depthProfile) {
        if (!hasDepthProfile(depthProfile) && section.path("_source_sections").isArray()) {
            return measureOriginalTwoDimensionalSections(section.path("_source_sections"), pipe, crossing);
        }
        JsonNode coordinates = section.path("coordinates");
        double geometryLength = coordinateLength(coordinates);
        double consumed = 0.0;
        BigDecimal length = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (int index = 1; index < coordinates.size(); index++) {
            double segmentGeometryLength = coordinateDistance(coordinates.path(index - 1), coordinates.path(index));
            if (segmentGeometryLength <= 1e-9) continue;
            BigDecimal segmentStart = sectionStart.add(sectionEnd.subtract(sectionStart)
                    .multiply(BigDecimal.valueOf(consumed / geometryLength)));
            consumed += segmentGeometryLength;
            BigDecimal segmentEnd = index == coordinates.size() - 1
                    ? sectionEnd
                    : sectionStart.add(sectionEnd.subtract(sectionStart)
                            .multiply(BigDecimal.valueOf(consumed / geometryLength)));
            BigDecimal overlapStart = segmentStart.compareTo(pieceStart) < 0 ? pieceStart : segmentStart;
            BigDecimal overlapEnd = segmentEnd.compareTo(pieceEnd) > 0 ? pieceEnd : segmentEnd;
            if (overlapEnd.compareTo(overlapStart) <= 0) continue;
            BigDecimal segmentLength = sectionLength
                    .multiply(BigDecimal.valueOf(segmentGeometryLength / geometryLength));
            BigDecimal overlapLength = segmentLength.multiply(overlapEnd.subtract(overlapStart))
                    .divide(segmentEnd.subtract(segmentStart), 12, RoundingMode.HALF_UP);
            length = length.add(overlapLength);
            if (hasDepthProfile(depthProfile)) {
                // Смета делит цену сегмента по миллиметровым станциям профиля глубины.
                // Геометрическая длина выше остаётся независимой от округления ценового интервала.
                BigDecimal pricedStart = segmentStart.setScale(3, RoundingMode.HALF_UP);
                BigDecimal pricedEnd = segmentEnd.setScale(3, RoundingMode.HALF_UP);
                overlapStart = pricedStart.max(pieceStart.setScale(3, RoundingMode.HALF_UP));
                overlapEnd = pricedEnd.min(pieceEnd.setScale(3, RoundingMode.HALF_UP));
                if (overlapEnd.compareTo(overlapStart) <= 0) continue;
                overlapLength = segmentLength.multiply(overlapEnd.subtract(overlapStart))
                        .divide(pricedEnd.subtract(pricedStart), 12, RoundingMode.HALF_UP);
            }
            cost = cost.add(economicsCalculator.constructionSegmentCost(
                    pipe, overlapLength, crossing,
                    averageDepth(depthProfile, overlapStart, overlapEnd), BigDecimal.ONE));
        }
        return new ExportPieceMeasure(length, cost);
    }

    private ExportPieceMeasure measureOriginalTwoDimensionalSections(
            JsonNode sections,
            PipeCatalogEntry pipe,
            SpecialCrossingType crossing) {
        BigDecimal length = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (JsonNode section : sections) {
            JsonNode coordinates = section.path("coordinates");
            double geometryLength = coordinateLength(coordinates);
            if (geometryLength <= 1e-9) continue;
            for (int index = 1; index < coordinates.size(); index++) {
                double segmentGeometryLength = coordinateDistance(
                        coordinates.path(index - 1), coordinates.path(index));
                if (segmentGeometryLength <= 1e-9) continue;
                BigDecimal segmentLength = section.path("length_m").decimalValue()
                        .multiply(BigDecimal.valueOf(segmentGeometryLength / geometryLength));
                length = length.add(segmentLength);
                cost = cost.add(economicsCalculator.constructionSegmentCost(
                        pipe, segmentLength, crossing, TWO_DIMENSIONAL_DEPTH_M, BigDecimal.ONE));
            }
        }
        return new ExportPieceMeasure(length, cost);
    }

    private List<BigDecimal> profileCuts(JsonNode profile, BigDecimal start, BigDecimal end) {
        java.util.SortedSet<BigDecimal> cuts = new java.util.TreeSet<>();
        cuts.add(start);
        profile.path("points").forEach(point -> {
            BigDecimal station = point.path("station_m").decimalValue();
            if (station.compareTo(start) > 0 && station.compareTo(end) < 0) cuts.add(station);
        });
        cuts.add(end);
        return new ArrayList<>(cuts);
    }

    private boolean hasDepthProfile(JsonNode profile) {
        return profile.path("points").isArray() && profile.path("points").size() >= 2;
    }


    private boolean isDepthProfileBreakpoint(JsonNode profile, BigDecimal station) {
        for (JsonNode point : profile.path("points")) {
            if (station.compareTo(point.path("station_m").decimalValue()) == 0) return true;
        }
        return false;
    }

    private String technicalNodeId(String variantId, String edgeId, BigDecimal station, boolean depthBreakpoint) {
        return outputId(variantId, "technical:" + edgeId + ":"
                + (depthBreakpoint ? "depth:" : "section:") + station
                .setScale(3, RoundingMode.HALF_UP).toPlainString());
    }

    private double[] metricAtStation(
            JsonNode coordinates,
            BigDecimal sectionStart,
            BigDecimal sectionEnd,
            BigDecimal station) {
        BigDecimal fraction = station.subtract(sectionStart)
                .divide(sectionEnd.subtract(sectionStart), 12, RoundingMode.HALF_UP);
        return metricAtFraction(coordinates, fraction.doubleValue(), coordinateLength(coordinates));
    }

    private double[] metricAtFraction(JsonNode coordinates, double fraction, double totalLength) {
        if (coordinates.isEmpty()) return new double[]{0.0, 0.0};
        if (coordinates.size() == 1 || totalLength <= 1e-9 || fraction <= 0.0) {
            return coordinate(coordinates.path(0));
        }
        if (fraction >= 1.0) return coordinate(coordinates.path(coordinates.size() - 1));
        double target = totalLength * fraction;
        double cumulative = 0.0;
        for (int index = 1; index < coordinates.size(); index++) {
            JsonNode left = coordinates.path(index - 1);
            JsonNode right = coordinates.path(index);
            double length = coordinateDistance(left, right);
            if (cumulative + length + 1e-9 >= target) {
                double local = length <= 1e-9 ? 0.0 : (target - cumulative) / length;
                return new double[]{
                        left.path("xm").asDouble()
                                + (right.path("xm").asDouble() - left.path("xm").asDouble()) * local,
                        left.path("ym").asDouble()
                                + (right.path("ym").asDouble() - left.path("ym").asDouble()) * local};
            }
            cumulative += length;
        }
        return coordinate(coordinates.path(coordinates.size() - 1));
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
        // Вычитаем метрические координаты до перехода к double, как в калькуляторе сметы.
        // Иначе перенос в UTM меняет доли длины и округление цены на границе полкопейки.
        return Math.hypot(
                right.path("xm").decimalValue().subtract(left.path("xm").decimalValue()).doubleValue(),
                right.path("ym").decimalValue().subtract(left.path("ym").decimalValue()).doubleValue());
    }

    private static final class ExportPieceMeasure {
        private final BigDecimal length;
        private final BigDecimal cost;

        private ExportPieceMeasure(BigDecimal length, BigDecimal cost) {
            this.length = length;
            this.cost = cost;
        }
    }

    private double[] coordinate(JsonNode coordinate) {
        return new double[]{coordinate.path("xm").asDouble(), coordinate.path("ym").asDouble()};
    }

    private double[] transform(double x, double y) {
        ProjCoordinate result = new ProjCoordinate();
        toWgs84.transform(new ProjCoordinate(x, y), result);
        return new double[]{result.x, result.y};
    }

    private SpecialCrossingType crossingType(String kind, String restrictionType) {
        if (!"special".equals(kind) || restrictionType == null) return SpecialCrossingType.BASE;
        return java.util.Arrays.stream(restrictionType.split("\\+"))
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .map(this::singleCrossingType)
                .max(java.util.Comparator.comparing(SpecialCrossingType::getCostMultiplier))
                .orElse(SpecialCrossingType.BASE);
    }

    private SpecialCrossingType singleCrossingType(String restrictionType) {
        switch (restrictionType) {
            case "road": return SpecialCrossingType.ROAD;
            case "tram_tracks": return SpecialCrossingType.TRAM_TRACKS;
            case "railway": throw new IllegalStateException("Railway is a forbidden restriction");
            case "gas_pipeline": return SpecialCrossingType.GAS_PIPELINE;
            case "power_cable": return SpecialCrossingType.POWER_CABLE;
            case "heat_network": return SpecialCrossingType.HEAT_NETWORK;
            default: throw new IllegalStateException("Unsupported special crossing type " + restrictionType);
        }
    }
}
