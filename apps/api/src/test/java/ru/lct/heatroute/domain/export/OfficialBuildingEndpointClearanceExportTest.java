package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteSection;
import ru.lct.heatroute.domain.routing.RouteVariant;
import ru.lct.heatroute.domain.sizing.NetworkSizingResult;
import ru.lct.heatroute.domain.sizing.NetworkTreeEdge;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.sizing.SizedNetworkEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Экспорт повторно проверяет отступ ОКС у новой камеры, даже если сохранены valid, rank и полная смета. */
class OfficialBuildingEndpointClearanceExportTest {
    private static final String VARIANT_ID = "synthetic-route";
    private static final String BUILDING_ID = "synthetic-neighbour-building";
    private static final double METRIC_TOLERANCE_M = 1e-8;

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final GeometryFactory geometries = new GeometryFactory();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialAxisClearance clearances =
            new OfficialAxisClearance(pipes, new OfficialConstraintCatalog());
    private final OfficialVariantEconomicsCalculator calculator =
            new OfficialVariantEconomicsCalculator(pipes, economics);
    private final OfficialOutputContractValidator contract = new OfficialOutputContractValidator();
    private final OfficialGeoJsonExporter exporter =
            new OfficialGeoJsonExporter(mapper, pipes, economics, contract, calculator);

    @ParameterizedTest(name = "{0}, rotated={1}, final DU{3}")
    @MethodSource("largeDiameterCases")
    void rejectsNewChamberInsideBuildingSetbackDespiteSavedAcceptance(
            String profile, boolean rotated, int flowTph, int diameter, double legalClearanceM) throws Exception {
        Fixture fixture = fixture(profile, rotated, flowTph, diameter, 6.5);
        assertMetricClearance(fixture, 6.5);
        assertThat(6.5).isGreaterThan(clearances.axisClearanceM("oks", 50, null).doubleValue())
                .isLessThan(legalClearanceM);
        assertThat(clearances.axisClearanceM("oks", diameter, null))
                .isEqualByComparingTo(BigDecimal.valueOf(legalClearanceM));

        // Меняется только наличие соседнего ОКС: граф, sizing и смета остаются теми же.
        assertAccepted(fixture, withoutBuilding(fixture));
        assertRejected(fixture);
    }

    @ParameterizedTest(name = "{0}, rotated={1}, final DU{3}")
    @MethodSource("largeDiameterCases")
    void rejectsFiveMillimetresInsideActualDiameterBoundary(
            String profile, boolean rotated, int flowTph, int diameter, double legalClearanceM) throws Exception {
        Fixture fixture = fixture(profile, rotated, flowTph, diameter, legalClearanceM - 0.005);
        assertMetricClearance(fixture, legalClearanceM - 0.005);
        assertAccepted(fixture, withoutBuilding(fixture));
        assertRejected(fixture);
    }

    @ParameterizedTest(name = "{0}, rotated={1}, final DU{3}")
    @MethodSource("largeDiameterCases")
    void acceptsExactActualDiameterBuildingClearance(
            String profile, boolean rotated, int flowTph, int diameter, double legalClearanceM) throws Exception {
        Fixture fixture = fixture(profile, rotated, flowTph, diameter, legalClearanceM);
        assertMetricClearance(fixture, legalClearanceM);
        assertThat(clearances.axisClearanceM("oks", diameter, null))
                .isEqualByComparingTo(BigDecimal.valueOf(legalClearanceM));
        assertAccepted(fixture, fixture.inputs);
    }

    @ParameterizedTest(name = "{0}, rotated={1}, final DU{3}")
    @MethodSource("largeDiameterCases")
    void acceptsSameFullyCostedRouteWithoutTheNeighbourBuilding(
            String profile, boolean rotated, int flowTph, int diameter, double legalClearanceM) throws Exception {
        Fixture fixture = fixture(profile, rotated, flowTph, diameter, 6.5);
        assertMetricClearance(fixture, 6.5);
        assertThat(6.5).isLessThan(legalClearanceM);
        assertAccepted(fixture, withoutBuilding(fixture));
    }

    @ParameterizedTest(name = "{0}, rotated={1}")
    @MethodSource("placements")
    void acceptsDu50AtTheSameSixAndAHalfMetreDistance(String profile, boolean rotated) throws Exception {
        Fixture fixture = fixture(profile, rotated, 1, 50, 6.5);
        assertMetricClearance(fixture, 6.5);
        assertThat(clearances.axisClearanceM("oks", 50, null)).isEqualByComparingTo("5.200");
        assertAccepted(fixture, fixture.inputs);
    }

    @ParameterizedTest(name = "{0}, rotated={1}")
    @MethodSource("placements")
    void preservesFinalOwnBuildingInputIncludingTechnicalSectionSplits(String profile, boolean rotated) throws Exception {
        Fixture original = fixture(profile, rotated, 1000, 500, 20);
        List<ImportedOfficialFeature> inputs = new ArrayList<>(original.inputs);
        inputs.add(new ImportedOfficialFeature("own-building", "restriction",
                mapper.createObjectNode().put("restriction_type", "oks"), geometries.createPolygon(new Coordinate[] {
                    metric(39, -10, rotated), metric(51, -10, rotated), metric(51, 10, rotated),
                    metric(39, 10, rotated), metric(39, -10, rotated)})));
        Fixture own = new Fixture(original.calculation, inputs, original.building, original.nodes, original.edges);
        assertAccepted(own, inputs);
        ObjectNode edge = (ObjectNode) saved(own).path("edges").path(1);
        edge.putArray("sections")
                .add(mapper.valueToTree(new RouteSection("base", null, null,
                        List.of(point(0, 0, rotated), point(38, 0, rotated)), 38, null)))
                .add(mapper.valueToTree(new RouteSection("base", null, null,
                        List.of(point(38, 0, rotated), point(40, 0, rotated)), 2, null)));
        assertAccepted(own, inputs);
    }

    @ParameterizedTest(name = "{0}, rotated={1}")
    @MethodSource("placements")
    void rejectsActualEmittedMillimetreExcursionAcrossBuildingBoundary(String profile, boolean rotated) throws Exception {
        double clearanceM = clearances.axisClearanceM("oks", 500, null).doubleValue();
        Fixture fixture = fixture(profile, rotated, 1000, 500, clearanceM + 0.0001);
        assertAccepted(fixture, fixture.inputs);
        ObjectNode edge = (ObjectNode) saved(fixture).path("edges").path(0);
        // SavedRouteGeometry допускает миллиметровое округление секции, но не уменьшение отступа.
        edge.putArray("sections").add(mapper.valueToTree(new RouteSection("base", null, null,
                List.of(point(-40, 0, rotated), point(-2, 0.001, rotated), point(0, 0, rotated)), 40, null)));
        assertAccepted(fixture, withoutBuilding(fixture));
        assertRejected(fixture);
    }

    @ParameterizedTest(name = "{0}, rotated={1}")
    @MethodSource("placements")
    void rejectsLaterInvalidVariantBeforeWritingEarlierValidFeatures(String profile, boolean rotated) throws Exception {
        Fixture fixture = fixture(profile, rotated, 1, 50, 6.5);
        assertAccepted(fixture, fixture.inputs);
        Fixture invalid = fixture(profile, rotated, 1000, 500, 6.5);
        ObjectNode later = ((ObjectNode) saved(invalid)).deepCopy().put("id", "later-invalid").put("rank", 2);
        ((com.fasterxml.jackson.databind.node.ArrayNode) fixture.calculation.path("variants")).add(later);
        assertThat(catchThrowable(() -> exporter.validate(fixture.calculation, fixture.inputs)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FORBIDDEN_CLEARANCE_VIOLATION");
        assertStreamRejected(fixture, false);
        // Выбор отдельного корректного варианта не обязан проверять невыбранную сеть.
        exporter.validateVariant(fixture.calculation, fixture.inputs, VARIANT_ID);
    }

    @ParameterizedTest(name = "{0}, rotated={1}, type={2}")
    @MethodSource("forbiddenCases")
    void checksAllForbiddenTypesWithoutInventingSpecialPassages(String profile, boolean rotated, String type) throws Exception {
        double minimumM = clearances.axisClearanceM(type, 500, null).doubleValue();
        for (boolean legal : new boolean[] {false, true}) {
            Fixture original = fixture(profile, rotated, 1000, 500, minimumM - (legal ? 0 : 0.005));
            ImportedOfficialFeature restriction = new ImportedOfficialFeature(BUILDING_ID, "restriction",
                    mapper.createObjectNode().put("restriction_type", type), original.building.getMetricGeometry());
            List<ImportedOfficialFeature> inputs = new ArrayList<>(withoutBuilding(original));
            inputs.add(restriction);
            Fixture candidate = new Fixture(original.calculation, inputs, restriction, original.nodes, original.edges);
            assertAccepted(candidate, withoutBuilding(candidate));
            if (legal) assertAccepted(candidate, inputs);
            else assertRejected(candidate);
        }
    }

    @Test
    void cancellationBeforePreflightDoesNotEmitFeaturesOrClearTheInterrupt() throws Exception {
        Fixture fixture = fixture("baseline_input", false, 1, 50, 6.5);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread.currentThread().interrupt();
        try {
            assertThat(catchThrowable(() -> exporter.writeValidated(fixture.calculation, fixture.inputs, output)))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("\"type\":\"Feature\"");
        } finally {
            Thread.interrupted();
        }
    }

    private static Stream<Arguments> forbiddenCases() {
        return Stream.of("baseline_input", "extended_input").flatMap(profile -> Stream.of(false, true)
                .flatMap(rotated -> Stream.of("oks", "park", "social_area", "prohibited_site", "water", "railway")
                        .map(type -> Arguments.of(profile, rotated, type))));
    }

    private static Stream<Arguments> placements() {
        return Stream.of("baseline_input", "extended_input")
                .flatMap(profile -> Stream.of(false, true).map(rotated -> Arguments.of(profile, rotated)));
    }

    private static Stream<Arguments> largeDiameterCases() {
        return Stream.of("baseline_input", "extended_input").flatMap(profile -> Stream.of(false, true)
                .flatMap(rotated -> Stream.of(
                        Arguments.of(profile, rotated, 1000, 500, 7.835),
                        Arguments.of(profile, rotated, 2000, 600, 7.925))));
    }

    /** Полный синтетический импорт и сохранённый JSON: ДУ назначает sizing, стоимость — штатный калькулятор. */
    private Fixture fixture(String profile, boolean rotated, int flowTph, int expectedDiameter,
            double buildingDistanceM) throws IOException {
        RouteNode root = new RouteNode("synthetic-root", "existing_chamber_tie_in", point(-40, 0, rotated),
                true, true, 1, "synthetic-existing-chamber", expectedDiameter);
        RouteNode chamber = new RouteNode("synthetic-new-chamber", "new_junction_chamber", point(0, 0, rotated),
                true, false, 0, null);
        RouteNode demand = new RouteNode("synthetic-demand", "demand_connection", point(40, 0, rotated),
                false, false, 0, "synthetic-cp");
        List<RouteNode> nodes = List.of(root, chamber, demand);
        List<NetworkTreeEdge> tree = List.of(
                new NetworkTreeEdge("synthetic-main", root.getId(), chamber.getId(), new BigDecimal("40")),
                new NetworkTreeEdge("synthetic-input", chamber.getId(), demand.getId(), new BigDecimal("40")));
        BigDecimal flow = BigDecimal.valueOf(flowTph);
        NetworkSizingResult sized = new OfficialNetworkSizer(pipes).size(tree, Map.of(demand.getId(), flow));
        assertThat(sized.getIssues()).isEmpty();
        List<RouteEdge> edges = List.of(
                edge(root, chamber, sized.getEdges().get("synthetic-main")),
                edge(chamber, demand, sized.getEdges().get("synthetic-input")));
        assertThat(edges).extracting(RouteEdge::getDiameter).containsOnly(expectedDiameter);
        edges.forEach(edge -> assertThat(edge.getFlowTph()).isEqualByComparingTo(flow));
        List<RouteConnection> connections = List.of(
                new RouteConnection("synthetic-cp", demand.getId(), flow, "connected", null));
        VariantEconomics costs = calculator.calculate(nodes, edges, connections, ExistingNetworkReconstructionResult.empty());
        assertThat(costs.isComplete()).isTrue();
        RouteVariant variant = new RouteVariant(VARIANT_ID, "cheapest", nodes, edges, connections,
                new BigDecimal("80"), List.of(), List.of(), ExistingNetworkReconstructionResult.empty(), costs, 1);
        ObjectNode calculation = mapper.createObjectNode().put("input_profile", profile);
        calculation.putArray("variants").add(mapper.valueToTree(variant));

        ImportedOfficialFeature building = new ImportedOfficialFeature(BUILDING_ID, "oks_existing",
                mapper.createObjectNode(), geometries.createPolygon(new Coordinate[] {
                    metric(-1, buildingDistanceM, rotated), metric(1, buildingDistanceM, rotated),
                    metric(1, buildingDistanceM + 2, rotated), metric(-1, buildingDistanceM + 2, rotated),
                    metric(-1, buildingDistanceM, rotated)}));
        Coordinate source = metric(-60, 0, rotated);
        List<ImportedOfficialFeature> inputs = List.of(
                new ImportedOfficialFeature("synthetic-source", "source", mapper.createObjectNode(), geometries.createPoint(source)),
                new ImportedOfficialFeature("synthetic-existing-network", "heat_network", mapper.createObjectNode()
                        .put("upstream_object_id", "synthetic-source").put("diameter", expectedDiameter).put("flow_tph", 0),
                        geometries.createLineString(new Coordinate[] {source, root.getCoordinate().toCoordinate()})),
                new ImportedOfficialFeature("synthetic-existing-chamber", "heat_chamber", mapper.createObjectNode()
                        .put("upstream_object_id", "synthetic-existing-network").put("diameter", expectedDiameter),
                        geometries.createPoint(root.getCoordinate().toCoordinate())),
                new ImportedOfficialFeature("synthetic-cp", "oks_connection_point", mapper.createObjectNode().put("flow_tph", flow),
                        geometries.createPoint(demand.getCoordinate().toCoordinate())),
                building);
        // Проверяем именно прочитанный сохранённый результат, без доверия объекту RouteVariant в памяти.
        return new Fixture((ObjectNode) mapper.readTree(mapper.writeValueAsBytes(calculation)), inputs, building, nodes, edges);
    }

    private RouteEdge edge(RouteNode from, RouteNode to, SizedNetworkEdge sized) {
        List<RouteCoordinate> coordinates = List.of(from.getCoordinate(), to.getCoordinate());
        double lengthM = line(coordinates).getLength();
        return new RouteEdge(sized.getEdgeId(), from.getId(), to.getId(), lengthM, coordinates,
                List.of(new RouteSection("base", null, null, coordinates, lengthM, null)),
                sized.getFlowTph(), sized.getDiameter());
    }

    private void assertMetricClearance(Fixture fixture, double distanceM) {
        RouteNode chamber = fixture.nodes.get(1);
        assertThat(chamber.getNodeType()).startsWith("new_");
        assertThat(chamber.isChamber()).isTrue();
        assertThat(chamber.isRoot()).isFalse();
        assertThat(chamber.getTargetId()).isNull();
        assertThat(fixture.building.getMetricGeometry().distance(geometries.createPoint(chamber.getCoordinate().toCoordinate())))
                .isCloseTo(distanceM, offset(METRIC_TOLERANCE_M));
        for (RouteEdge edge : fixture.edges) {
            LineString route = line(edge.getCoordinates());
            assertThat(route.intersects(fixture.building.getMetricGeometry())).as("%s misses the footprint", edge.getId()).isFalse();
            assertThat(route.distance(fixture.building.getMetricGeometry())).isCloseTo(distanceM, offset(METRIC_TOLERANCE_M));
            assertThat(edge.getSections()).singleElement().satisfies(section -> {
                assertThat(section.getKind()).isEqualTo("base");
                assertThat(section.getCoordinates()).containsExactlyElementsOf(edge.getCoordinates());
            });
        }
        // Ни существующий root, ни ввод потребителя не находятся в этом буфере; ОКС не является своим.
        for (RouteNode remote : List.of(fixture.nodes.get(0), fixture.nodes.get(2))) {
            assertThat(remote.getTargetId()).isNotEqualTo(BUILDING_ID);
            assertThat(fixture.building.getMetricGeometry().distance(geometries.createPoint(remote.getCoordinate().toCoordinate())))
                    .isGreaterThan(30);
        }
    }

    private void assertRejected(Fixture fixture) {
        JsonNode before = fixture.calculation.deepCopy();
        assertSavedAcceptance(fixture);
        assertAll("Building clearance must override persisted acceptance at every public export entry point",
                () -> assertIncomplete("export", catchThrowable(() -> exporter.export(fixture.calculation, fixture.inputs))),
                () -> assertIncomplete("validate", catchThrowable(() -> exporter.validate(fixture.calculation, fixture.inputs))),
                () -> assertIncomplete("validateVariant", catchThrowable(() -> exporter.validateVariant(
                        fixture.calculation, fixture.inputs, VARIANT_ID))),
                () -> assertStreamRejected(fixture, false),
                () -> assertStreamRejected(fixture, true),
                () -> assertThat(fixture.calculation).isEqualTo(before));
    }

    private void assertStreamRejected(Fixture fixture, boolean selected) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Throwable failure = catchThrowable(() -> write(fixture, fixture.inputs, selected, output));
        String entryPoint = selected ? "writeValidatedVariant" : "writeValidated";
        assertAll("Reject before emitting the first feature",
                () -> assertIncomplete(entryPoint, failure),
                () -> assertThat(output.toString(StandardCharsets.UTF_8).contains("\"type\":\"Feature\""))
                        .as("%s emitted a feature before building-clearance rejection", entryPoint).isFalse());
    }

    private void assertIncomplete(String entryPoint, Throwable failure) {
        assertThat(failure).as(entryPoint).isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE");
    }

    private void assertAccepted(Fixture fixture, List<ImportedOfficialFeature> inputs) throws IOException {
        JsonNode before = fixture.calculation.deepCopy();
        assertSavedAcceptance(fixture);
        exporter.validate(fixture.calculation, inputs);
        exporter.validateVariant(fixture.calculation, inputs, VARIANT_ID);
        JsonNode output = exporter.export(fixture.calculation, inputs);
        assertThat(contract.validate(output)).isEmpty();
        List<JsonNode> physical = StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> List.of("heat_network", "heat_chamber")
                        .contains(feature.path("properties").path("object_type").asText()))
                .collect(Collectors.toList());
        assertThat(physical).hasSize(3);
        assertThat(physical).allSatisfy(feature -> assertThat(feature.path("properties").path("diameter").asInt())
                .isEqualTo(fixture.edges.get(0).getDiameter()));
        BigDecimal physicalCost = physical.stream().map(feature -> feature.path("properties").path("cost").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(physicalCost.add(economics.tieInCost()))
                .isEqualByComparingTo(saved(fixture).path("economics").path("construction_cost").decimalValue());
        for (boolean selected : new boolean[] {false, true}) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            write(fixture, inputs, selected, stream);
            assertThat(mapper.readTree(stream.toByteArray())).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(output)));
        }
        assertThat(fixture.calculation).isEqualTo(before);
    }

    private void write(Fixture fixture, List<ImportedOfficialFeature> inputs, boolean selected,
            ByteArrayOutputStream output) throws IOException {
        if (selected) exporter.writeValidatedVariant(fixture.calculation, inputs, VARIANT_ID, output);
        else exporter.writeValidated(fixture.calculation, inputs, output);
    }

    private void assertSavedAcceptance(Fixture fixture) {
        JsonNode variant = saved(fixture);
        assertThat(variant.path("valid").asBoolean()).isTrue();
        assertThat(variant.path("rank").asInt()).isEqualTo(1);
        assertThat(variant.path("validation_issues")).isEmpty();
        assertThat(variant.path("sizing_issues")).isEmpty();
        JsonNode costs = variant.path("economics");
        assertThat(costs.path("complete").asBoolean()).isTrue();
        assertThat(costs.path("construction_cost").decimalValue()).isPositive();
        assertThat(costs.path("new_network_length").decimalValue()).isEqualByComparingTo("80");
        assertThat(costs.path("chamber_construction_cost").decimalValue())
                .isEqualByComparingTo(economics.chamberCost(fixture.edges.get(0).getDiameter()));
        assertThat(costs.path("tie_in_cost").decimalValue()).isEqualByComparingTo(economics.tieInCost());
        assertThat(costs.path("unconnected_penalty").decimalValue()).isZero();
    }

    private JsonNode saved(Fixture fixture) { return fixture.calculation.path("variants").path(0); }

    private List<ImportedOfficialFeature> withoutBuilding(Fixture fixture) {
        return fixture.inputs.stream().filter(feature -> !BUILDING_ID.equals(feature.getFeatureId())).collect(Collectors.toList());
    }

    private RouteCoordinate point(double x, double y, boolean rotated) {
        Coordinate metric = metric(x, y, rotated);
        return new RouteCoordinate(metric.x, metric.y);
    }

    private Coordinate metric(double x, double y, boolean rotated) {
        // Поворот 3–4–5 сохраняет миллиметровые координаты маршрута в EPSG:32637.
        double cosine = rotated ? 0.6 : 1, sine = rotated ? 0.8 : 0;
        return new Coordinate(430000 + cosine * x - sine * y, 6180000 + sine * x + cosine * y);
    }

    private LineString line(List<RouteCoordinate> coordinates) {
        return geometries.createLineString(coordinates.stream().map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
    }

    private static final class Fixture {
        private final ObjectNode calculation;
        private final List<ImportedOfficialFeature> inputs;
        private final ImportedOfficialFeature building;
        private final List<RouteNode> nodes;
        private final List<RouteEdge> edges;

        private Fixture(ObjectNode calculation, List<ImportedOfficialFeature> inputs, ImportedOfficialFeature building,
                List<RouteNode> nodes, List<RouteEdge> edges) {
            this.calculation = calculation;
            this.inputs = inputs;
            this.building = building;
            this.nodes = nodes;
            this.edges = edges;
        }
    }
}
