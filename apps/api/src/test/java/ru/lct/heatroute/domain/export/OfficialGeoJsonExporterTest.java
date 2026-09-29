package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.lct.heatroute.contract.ContractSchemaSupport.load;
import static ru.lct.heatroute.contract.ContractSchemaSupport.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.routing.OfficialCalculationResult;
import ru.lct.heatroute.domain.routing.OfficialObstacleRouter;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules;
import ru.lct.heatroute.domain.routing.RegressionRoutePlannerFixture;
import ru.lct.heatroute.domain.routing.OfficialRouteValidator;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

class OfficialGeoJsonExporterTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final WKTReader wktReader = new WKTReader();
    private final OfficialPipeCatalog pipeCatalog = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialOutputContractValidator validator = new OfficialOutputContractValidator();
    private final OfficialGeoJsonExporter exporter = new OfficialGeoJsonExporter(
            objectMapper, pipeCatalog, economics, validator,
            new OfficialVariantEconomicsCalculator(pipeCatalog, economics));

    @Test
    void exportsStrictFourTypeContractWithoutNullOrForeignProperties() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (500000 6170000)", "{}"),
                feature("heat_network", "network", "LINESTRING (500000 6170000, 500100 6170000)",
                        "{\"upstream_object_id\":\"source\",\"flow_tph\":2,\"diameter\":50}"),
                feature("heat_chamber", "chamber", "POINT (500100 6170000)",
                        "{\"upstream_object_id\":\"network\",\"diameter\":50}"),
                feature("oks_connection_point", "cp-network", "POINT (500050 6170050)",
                        "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-chamber", "POINT (500100 6170050)",
                        "{\"flow_tph\":5}"));
        OfficialCalculationResult result = planner().plan(
                features,
                new TopologyAnalysis(2, 1, 1, Collections.emptyList(), List.of(
                        new TieInCandidate("cp-network", "network", "heat_network", 50, true),
                        new TieInCandidate("cp-chamber", "chamber", "heat_chamber", 50, true))));

        ObjectNode output = exporter.export(objectMapper.valueToTree(result), features);

        assertThat(validator.validate(output)).isEmpty();
        assertThat(validate(load("lct-2026-output.schema.json"), output)).isEmpty();
        Set<String> types = StreamSupport.stream(output.path("features").spliterator(), false)
                .map(feature -> feature.path("properties").path("object_type").asText())
                .collect(Collectors.toSet());
        assertThat(types).containsExactlyInAnyOrder(
                "heat_network",
                "heat_chamber",
                "technical_node",
                "variant_summary");
        assertThat(output.path("features")).allSatisfy(feature -> {
            assertThat(feature.path("properties").findValuesAsText("id")).isNotEmpty();
            feature.path("properties").fields().forEachRemaining(field ->
                    assertThat(field.getValue().isNull()).as(field.getKey()).isFalse());
        });
        assertThat(StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> "heat_network".equals(
                        feature.path("properties").path("object_type").asText())))
                .allSatisfy(feature -> {
                    assertThat(feature.path("properties").has("depth_start")).isFalse();
                    assertThat(feature.path("properties").has("depth_end")).isFalse();
                    assertThat(feature.path("geometry").path("coordinates"))
                            .allSatisfy(position -> assertThat(position.size()).isEqualTo(2));
                });
        JsonNode summary = StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> "variant_summary".equals(
                        feature.path("properties").path("object_type").asText()))
                .findFirst().orElseThrow().path("properties");
        BigDecimal componentTotal = StreamSupport.stream(List.of(
                        "construction_cost", "unconnected_penalty")
                        .spliterator(), false)
                .map(field -> summary.path(field).decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summary.path("calculated_cost").decimalValue()).isEqualByComparingTo(componentTotal);
        String summaryVariantId = summary.path("variant_id").asText();
        BigDecimal exportedConstruction = StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> Set.of("heat_network", "heat_chamber", "tie_in").contains(
                        feature.path("properties").path("object_type").asText()))
                .filter(feature -> summaryVariantId.equals(
                        feature.path("properties").path("variant_id").asText()))
                .map(feature -> feature.path("properties").path("cost").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(exportedConstruction).isEqualByComparingTo(
                summary.path("construction_cost").decimalValue()
                        .subtract(summary.path("existing_chamber_tie_in_cost").decimalValue()));
        assertThat(summary.path("rank").asInt()).isEqualTo(1);
        assertThat(summary.path("existing_chamber_tie_in_count").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(summary.path("existing_chamber_tie_in_cost").decimalValue()).isNotNegative();
    }

    @Test
    void sizesAndPricesANewTieInChamberForTheExistingNetworkDiameter() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (500000 6170000)", "{}"),
                feature("heat_network", "network", "LINESTRING (500000 6170000, 500100 6170000)",
                        "{\"diameter\":1000}"),
                feature("oks_connection_point", "cp", "POINT (500050 6170050)", "{\"flow_tph\":5}"));
        OfficialCalculationResult calculation = planner().plan(features,
                new TopologyAnalysis(1, 1, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "network", "heat_network", 50, true))));
        assertThat(calculation.getVariants()).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.getConnectedDemandCount()).isEqualTo(1);
            assertThat(variant.getEconomics().getChamberConstructionCost()).isEqualByComparingTo("8000000");
            assertThat(variant.getEconomics().getTieInCost()).isEqualByComparingTo("0");
        });
        JsonNode output = exporter.export(objectMapper.valueToTree(calculation), features);
        assertThat(StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(item -> "heat_chamber".equals(item.path("properties").path("object_type").asText())))
                .isNotEmpty().allSatisfy(chamber -> {
                    assertThat(chamber.path("properties").path("diameter").asInt()).isEqualTo(1000);
                    assertThat(chamber.path("properties").path("cost").decimalValue()).isEqualByComparingTo("8000000");
                });
    }

    @Test
    void legacyCameraMetadataIsResolvedButAStaleOrContradictoryCostCannotBeExported() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (500000 6170000, 500100 6170000)", "{\"diameter\":1000}"),
                feature("oks_connection_point", "cp", "POINT (500050 6170050)", "{\"flow_tph\":5}"));
        JsonNode calculation = objectMapper.valueToTree(planner().plan(features,
                new TopologyAnalysis(1, 1, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "network", "heat_network", 50, true)))));
        JsonNode legacy = calculation.deepCopy();
        legacy.path("variants").forEach(variant -> variant.path("nodes").forEach(node ->
                ((ObjectNode) node).remove("existing_incident_diameter")));
        assertThat(exporter.export(legacy, features).path("features")).isNotEmpty();

        JsonNode contradictory = calculation.deepCopy();
        contradictory.path("variants").forEach(variant -> variant.path("nodes").forEach(node -> {
            if (node.path("root").asBoolean()) ((ObjectNode) node).put("existing_incident_diameter", 100);
        }));
        assertThatThrownBy(() -> exporter.export(contradictory, features))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE")
                .hasMessageContaining("differs from the import");

        JsonNode stale = legacy.deepCopy();
        ((ObjectNode) stale.path("variants").path(0).path("economics")).put("chamber_construction_cost", 3000000);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        assertThatThrownBy(() -> exporter.writeValidated(stale, features, stream))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("recalculate")
                .hasMessageContaining("chamber_construction_cost");
        assertThat(stream.size()).isLessThan(100); // Только оболочка FeatureCollection, ни одного объекта.
        assertThat(stream.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("\"type\":\"Feature\"");
    }

    @Test
    void cheapestSelectionAlreadyAccountsForTheExistingDiameterBeforeExport() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "a-high", "LINESTRING (500000 6170000, 500100 6170000)", "{\"diameter\":1000}"),
                feature("heat_network", "z-low", "LINESTRING (500000 6170090, 500100 6170090)", "{\"diameter\":100}"),
                feature("oks_connection_point", "cp", "POINT (500050 6170040)", "{\"flow_tph\":5}"));
        OfficialCalculationResult calculation = planner().plan(features,
                new TopologyAnalysis(1, 2, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "a-high", "heat_network", 40, true),
                        new TieInCandidate("cp", "z-low", "heat_network", 50, true))));
        assertThat(calculation.getVariants())
                .filteredOn(v -> calculation.getPreferredVariantId().equals(v.getId()))
                .singleElement().satisfies(variant -> {
                    assertThat(variant.getConnectedDemandCount()).isEqualTo(1);
                    assertThat(variant.getNodes()).filteredOn(ru.lct.heatroute.domain.routing.RouteNode::isRoot)
                            .singleElement().satisfies(root -> {
                                assertThat(root.getTargetId()).isEqualTo("z-low");
                                assertThat(root.getExistingIncidentDiameter()).isEqualTo(100);
                            });
                    assertThat(variant.getEconomics().getChamberConstructionCost()).isEqualByComparingTo("3000000");
                });
        assertThat(exporter.export(objectMapper.valueToTree(calculation), features).path("features")).isNotEmpty();
    }

    @Test
    void storedValidFlagCannotHideAnExcessiveTurnFromTheExporter() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (500000 6170000, 500100 6170000)", "{\"diameter\":50}"),
                feature("oks_connection_point", "cp", "POINT (500050 6170050)", "{\"flow_tph\":5}"));
        JsonNode calculation = objectMapper.valueToTree(planner().plan(features,
                new TopologyAnalysis(1, 1, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "network", "heat_network", 50, true)))));
        JsonNode before = calculation.deepCopy();
        ObjectNode variant = (ObjectNode) calculation.path("variants").path(0);
        assertThat(variant.path("valid").asBoolean()).isTrue();
        ArrayNode points = ((ObjectNode) variant.path("edges").path(0)).putArray("coordinates");
        points.addObject().put("xm", 500050).put("ym", 6170000);
        points.addObject().put("xm", 500050).put("ym", 6170020);
        points.addObject().put("xm", 500049).put("ym", 6170005);
        points.addObject().put("xm", 500050).put("ym", 6170050);
        assertThatThrownBy(() -> exporter.export(calculation, features))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ROUTE_DEFLECTION_EXCEEDED");
        assertThatThrownBy(() -> exporter.validateVariant(calculation, features, variant.path("id").asText()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ROUTE_DEFLECTION_EXCEEDED");
        assertThat(exporter.export(before, features).path("features")).isNotEmpty();

        JsonNode sectionOnly = before.deepCopy();
        ObjectNode changed = (ObjectNode) sectionOnly.path("variants").path(0);
        ArrayNode sectionPoints = ((ObjectNode) changed.path("edges").path(0).path("sections").path(0))
                .putArray("coordinates");
        sectionPoints.addObject().put("xm", 500050).put("ym", 6170000);
        sectionPoints.addObject().put("xm", 500050).put("ym", 6170020);
        sectionPoints.addObject().put("xm", 500050).put("ym", 6170010);
        sectionPoints.addObject().put("xm", 500050).put("ym", 6170050);
        assertThatThrownBy(() -> exporter.validateVariant(sectionOnly, features, changed.path("id").asText()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ROUTE_DEFLECTION_EXCEEDED");
        ByteArrayOutputStream rejected = new ByteArrayOutputStream();
        assertThatThrownBy(() -> exporter.writeValidatedVariant(sectionOnly, features, changed.path("id").asText(), rejected))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ROUTE_DEFLECTION_EXCEEDED");
        assertThat(rejected.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("\"type\":\"Feature\"");
    }

    @Test
    void newChamberAttachmentHasNoSeparateFeeInBothCalculationAndExport() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (500000 6170000)", "{}"),
                feature("heat_network", "network", "LINESTRING (500000 6170000, 500100 6170000)",
                        "{\"diameter\":50}"),
                feature("oks_connection_point", "cp", "POINT (500050 6170050)", "{\"flow_tph\":5}"));
        OfficialCalculationResult calculation = planner().plan(features,
                new TopologyAnalysis(1, 1, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "network", "heat_network", 50, true))));
        assertThat(calculation.getVariants()).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.getConnectedDemandCount()).isEqualTo(1);
            assertThat(variant.getNodes()).anyMatch(node -> "new_tie_in_chamber".equals(node.getNodeType()));
            assertThat(variant.getEconomics().getTieInCost()).isEqualByComparingTo("0");
        });

        ObjectNode output = exporter.export(objectMapper.valueToTree(calculation), features);
        assertThat(validator.validate(output)).isEmpty();
        assertThat(StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> "variant_summary".equals(feature.path("properties").path("object_type").asText())))
                .isNotEmpty().allSatisfy(feature -> {
                    JsonNode summary = feature.path("properties");
                    assertThat(summary.path("existing_chamber_tie_in_count").asInt()).isZero();
                    assertThat(summary.path("existing_chamber_tie_in_cost").decimalValue())
                            .isEqualByComparingTo("0");
                    BigDecimal componentCost = StreamSupport.stream(output.path("features").spliterator(), false)
                            .map(item -> item.path("properties"))
                            .filter(properties -> summary.path("variant_id").equals(properties.path("variant_id")))
                            .filter(properties -> Set.of("heat_network", "heat_chamber")
                                    .contains(properties.path("object_type").asText()))
                            .map(properties -> properties.path("cost").decimalValue())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    assertThat(summary.path("construction_cost").decimalValue()).isEqualByComparingTo(componentCost);
                });
    }

    @Test
    void rejectsResultWithoutCompleteRankedVariant() {
        ObjectNode calculation = objectMapper.createObjectNode();
        calculation.putArray("variants").addObject()
                .put("valid", true)
                .putObject("economics").put("complete", false);

        assertThatThrownBy(() -> exporter.export(calculation, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE");
    }

    @Test
    void exportsRankedBaselineResultWithoutReconstructionEntities() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (500000 6170000)", "{}"),
                feature("heat_network", "network", "LINESTRING (500000 6170000, 500100 6170000)",
                        "{\"upstream_object_id\":\"source\",\"diameter\":50}"),
                feature("oks_connection_point", "cp", "POINT (500050 6170050)", "{\"flow_tph\":5}"));
        OfficialCalculationResult result = planner().plan(
                features,
                new TopologyAnalysis(1, 1, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "network", "heat_network", 50, true))),
                ru.lct.heatroute.domain.run.OfficialRunParameters.defaults(),
                OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE);

        JsonNode calculation = objectMapper.valueToTree(result);
        assertThat(calculation.path("variants")).allSatisfy(variant -> {
            assertThat(variant.path("reconstruction").path("available").asBoolean()).isTrue();
            assertThat(variant.path("reconstruction").path("network_sections")).isEmpty();
            assertThat(variant.path("reconstruction").path("chambers")).isEmpty();
            assertThat(variant.path("economics").path("complete").asBoolean()).isTrue();
        });
        assertThat(calculation.path("variants")).anySatisfy(variant ->
                assertThat(variant.path("rank").asInt()).isEqualTo(1));

        ObjectNode output = exporter.export(calculation, features);
        assertThat(validator.validate(output, true)).isEmpty();
        assertThat(validate(load("lct-2026-output.schema.json"), output)).isEmpty();
        assertThat(StreamSupport.stream(output.path("features").spliterator(), false)
                .map(feature -> feature.path("properties").path("object_type").asText()))
                .doesNotContain("tie_in", "heat_network_reconstruction", "heat_chamber_reconstruction");

        ((ObjectNode) calculation).put("input_profile", OfficialGeoJsonInspector.EXTENDED_INPUT_PROFILE);
        assertThat(validator.validate(exporter.export(calculation, features), true)).isEmpty();
    }

    @Test
    void splitsDepthChangesIntoReferencedTechnicalNodesAndExactXyzSegments() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (500000 6170000)", "{}"),
                feature("heat_network", "network", "LINESTRING (500000 6169990, 500000 6170010)",
                        "{\"upstream_object_id\":\"source\",\"flow_tph\":2,\"diameter\":50}"),
                feature("restriction", "gas", "LINESTRING (500050 6169980, 500050 6170020)",
                        "{\"restriction_type\":\"gas_pipeline\"}"),
                feature("oks_connection_point", "cp", "POINT (500100 6170000)",
                        "{\"flow_tph\":5}"));
        OfficialCalculationResult result = planner().plan(
                features,
                new TopologyAnalysis(1, 1, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "network", "heat_network", 50, true))),
                new ru.lct.heatroute.domain.run.OfficialRunParameters(
                        new BigDecimal("0.7"), new BigDecimal("10"), true));

        ObjectNode output = exporter.export(objectMapper.valueToTree(result), features);

        assertThat(validator.validate(output)).isEmpty();
        List<JsonNode> newNetwork = StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> "heat_network".equals(
                        feature.path("properties").path("object_type").asText()))
                .collect(Collectors.toList());
        assertThat(newNetwork).hasSizeGreaterThan(3);
        assertThat(newNetwork).anySatisfy(feature -> assertThat(
                feature.path("properties").path("depth_start").decimalValue())
                .isNotEqualByComparingTo(feature.path("properties").path("depth_end").decimalValue()));
        assertThat(newNetwork).allSatisfy(feature -> assertThat(feature.path("geometry").path("coordinates"))
                .allSatisfy(position -> assertThat(position.size()).isEqualTo(3)));
        assertThat(StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> "technical_node".equals(
                        feature.path("properties").path("object_type").asText()))
                .map(feature -> feature.path("properties").path("id").asText()))
                .anyMatch(id -> id.contains(":depth:"));
    }

    @Test
    void keepsGeometryOnlyVerticesInsideLineStringWithoutExportingPointObjects() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (500000 6170000)", "{}"),
                feature("heat_network", "network", "LINESTRING (500000 6170000, 500100 6170000)",
                        "{\"upstream_object_id\":\"source\",\"flow_tph\":2,\"diameter\":50}"),
                feature("oks_connection_point", "cp", "POINT (500050 6170050)", "{\"flow_tph\":5}"));
        ObjectNode calculation = objectMapper.valueToTree(planner().plan(
                features,
                new TopologyAnalysis(1, 1, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "network", "heat_network", 50, true)))));
        ObjectNode edge = (ObjectNode) calculation.path("variants").path(0).path("edges").path(0);
        edge.remove("depth_profile");
        ArrayNode sections = edge.putArray("sections");
        JsonNode first = edge.path("coordinates").get(0);
        JsonNode last = edge.path("coordinates").get(edge.path("coordinates").size() - 1);
        ObjectNode midpoint = objectMapper.createObjectNode()
                .put("xm", first.path("xm").decimalValue().add(last.path("xm").decimalValue()).divide(BigDecimal.valueOf(2)))
                .put("ym", first.path("ym").decimalValue().add(last.path("ym").decimalValue()).divide(BigDecimal.valueOf(2)));
        BigDecimal halfLength = edge.path("length_m").decimalValue().divide(BigDecimal.valueOf(2));
        ObjectNode firstSection = sections.addObject();
        firstSection.put("kind", "base");
        firstSection.put("length_m", halfLength);
        firstSection.putArray("coordinates").add(first.deepCopy()).add(midpoint.deepCopy());
        ObjectNode secondSection = sections.addObject();
        secondSection.put("kind", "base");
        secondSection.put("length_m", edge.path("length_m").decimalValue().subtract(halfLength));
        secondSection.putArray("coordinates").add(midpoint.deepCopy()).add(last.deepCopy());

        ObjectNode output = exporter.export(calculation, features);

        assertThat(validator.validate(output)).isEmpty();
        List<String> generatedNodeIds = StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> "technical_node".equals(
                        feature.path("properties").path("object_type").asText()))
                .map(feature -> feature.path("properties").path("id").asText())
                .filter(id -> id.contains(":technical:" + edge.path("id").asText() + ":"))
                .collect(Collectors.toList());
        assertThat(generatedNodeIds).isEmpty();
        assertThat(StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> "heat_network".equals(
                        feature.path("properties").path("object_type").asText()))
                .filter(feature -> feature.path("properties").path("id").asText()
                        .contains(edge.path("id").asText())))
                .anySatisfy(feature -> {
                    assertThat(feature.path("properties").has("depth_start")).isFalse();
                    assertThat(feature.path("properties").has("depth_end")).isFalse();
                    assertThat(feature.path("geometry").path("coordinates")).hasSize(3);
                });
    }

    @Test
    void scopesTopologyAndReconstructionIdsAcrossMultipleVariants() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (500000 6170000)", "{}"),
                feature("heat_network", "network", "LINESTRING (500000 6170000, 500100 6170000)",
                        "{\"upstream_object_id\":\"source\",\"flow_tph\":2,\"diameter\":50}"),
                feature("oks_connection_point", "cp", "POINT (500050 6170050)", "{\"flow_tph\":5}"));
        JsonNode original = objectMapper.valueToTree(planner().plan(
                features,
                new TopologyAnalysis(1, 1, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "network", "heat_network", 50, true)))));
        ObjectNode first = (ObjectNode) original.path("variants").path(0).deepCopy();
        first.put("id", "variant-a");
        first.put("rank", 1);
        ObjectNode second = first.deepCopy();
        second.put("id", "variant-b");
        second.put("rank", 2);
        ObjectNode calculation = objectMapper.createObjectNode();
        ArrayNode variants = calculation.putArray("variants");
        variants.add(first);
        variants.add(second);

        ObjectNode output = exporter.export(calculation, features);

        assertThat(validator.validate(output)).isEmpty();
        List<String> ids = StreamSupport.stream(output.path("features").spliterator(), false)
                .map(feature -> feature.path("properties").path("id").asText())
                .collect(Collectors.toList());
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).anyMatch(id -> id.startsWith("variant-a:"));
        assertThat(ids).anyMatch(id -> id.startsWith("variant-b:"));

        exporter.validateVariant(calculation, features, "variant-b");
        ByteArrayOutputStream selectedOutput = new ByteArrayOutputStream();
        exporter.writeValidatedVariant(calculation, features, "variant-b", selectedOutput);
        JsonNode selected = objectMapper.readTree(selectedOutput.toByteArray());
        assertThat(validator.validate(selected)).isEmpty();
        assertThat(StreamSupport.stream(selected.path("features").spliterator(), false)
                .map(feature -> feature.path("properties").path("variant_id").asText())
                .distinct()).containsExactly("variant-b");
    }

    @Test
    void incrementallyWritesTheSameValidatedFeatureCollection() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (500000 6170000)", "{}"),
                feature("heat_network", "network", "LINESTRING (500000 6170000, 500100 6170000)",
                        "{\"upstream_object_id\":\"source\",\"flow_tph\":2,\"diameter\":50}"),
                feature("oks_connection_point", "cp", "POINT (500050 6170050)", "{\"flow_tph\":5}"));
        JsonNode calculation = objectMapper.valueToTree(planner().plan(
                features,
                new TopologyAnalysis(1, 1, 0, Collections.emptyList(), List.of(
                        new TieInCandidate("cp", "network", "heat_network", 50, true)))));
        ObjectNode materialized = exporter.export(calculation, features);
        exporter.validate(calculation, features);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        exporter.writeValidated(calculation, features, output);

        JsonNode streamed = objectMapper.readTree(output.toByteArray());
        assertThat(streamed.path("features").size()).isEqualTo(materialized.path("features").size());
        assertThat(featureIds(streamed)).containsExactlyElementsOf(featureIds(materialized));
        assertThat(validator.validate(streamed)).isEmpty();
    }

    private List<String> featureIds(JsonNode collection) {
        return StreamSupport.stream(collection.path("features").spliterator(), false)
                .map(feature -> feature.path("properties").path("id").asText())
                .collect(Collectors.toList());
    }

    private RegressionRoutePlannerFixture planner() {
        OfficialRouteGeometryRules geometryRules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        return new RegressionRoutePlannerFixture(
                new OfficialRouteValidator(geometryRules),
                new OfficialObstacleRouter(geometryRules),
                pipeCatalog,
                new ru.lct.heatroute.domain.sizing.OfficialNetworkSizer(pipeCatalog),
                new OfficialExistingNetworkReconstructor(pipeCatalog),
                new OfficialVariantEconomicsCalculator(pipeCatalog, economics),
                new OfficialDepthPlanner(
                        new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipeCatalog),
                        new OfficialDepthOptimizer(pipeCatalog, economics),
                        new OfficialDepthProfileValidator(pipeCatalog)));
    }

    private ImportedOfficialFeature feature(
            String objectType,
            String id,
            String wkt,
            String attributes) throws Exception {
        JsonNode node = objectMapper.readTree(attributes);
        return new ImportedOfficialFeature(id, objectType, node, wktReader.read(wkt));
    }
}
