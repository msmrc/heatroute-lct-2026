package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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

/** Независимый export preflight экспертного уточнения камер от 25.09.2026, не нормы СП. */
class OfficialExpertChamberExportTest {
    private static final String SPACING = "EXPERT_CHAMBER_SPACING_TOO_SHORT";
    private static final String NO_CHAMBER = "EXPERT_OKS_BRANCH_WITHOUT_CHAMBER";
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialVariantEconomicsCalculator calculator = new OfficialVariantEconomicsCalculator(pipes, economics);
    private final OfficialOutputContractValidator contract = new OfficialOutputContractValidator();
    private final OfficialGeoJsonExporter exporter = new OfficialGeoJsonExporter(mapper, pipes, economics, contract, calculator);

    @ParameterizedTest
    @CsvSource({"9.999,false,false", "10,false,true", "10.001,false,true",
            "9.999,true,false", "10,true,true", "10.001,true,true"})
    void checksActualChamberPathAcrossTechnicalNodes(double length, boolean technical, boolean accepted) throws Exception {
        ObjectNode calculation = chamberPath(length, technical, length);
        if (accepted) assertAccepted(calculation, "route");
        else assertRejected(calculation, "route", SPACING);
    }

    @ParameterizedTest
    @CsvSource({"baseline_input,shortest", "baseline_input,balanced", "baseline_input,cheapest",
            "extended_input,shortest", "extended_input,balanced", "extended_input,cheapest"})
    void rejectsConnectedOksWithoutAChamberDespiteSavedAcceptance(String profile, String strategy) throws Exception {
        RouteNode root = node("root", "technical_node", 0, 0, false, true);
        RouteNode technical = node("technical", "technical_node", 5, 0, false, false);
        RouteNode demand = node("demand", "demand_connection", 20, 0, false, false);
        ObjectNode calculation = calculation(List.of(root, technical, demand),
                List.of(edge("first", root, technical, 5), edge("last", technical, demand, 15)));
        calculation.put("input_profile", profile);
        saved(calculation).put("strategy", strategy);
        assertRejected(calculation, "route", NO_CHAMBER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"global-tree-84", "global-tree-85", "unrelated-version", ""})
    void rechecksOldAndNewRunsWithoutUsingVersionAsAnAdmissionSwitch(String version) throws Exception {
        ObjectNode calculation = chamberPath(9.999, false, 9.999);
        if (!version.isEmpty()) calculation.put("algorithm_version", version);
        assertRejected(calculation, "route", SPACING);
    }

    @Test
    void declaredLengthAndConsistentEconomicsCannotHideAShortActualPath() throws Exception {
        assertRejected(chamberPath(9.999, false, 100), "route", SPACING);
    }

    @Test
    void measuresEmittedSectionsEvenWithinGeometryEquivalenceTolerance() throws Exception {
        ObjectNode calculation = chamberPath(10, false, 10);
        ObjectNode first = (ObjectNode) saved(calculation).path("edges").path(0);
        first.set("sections", mapper.valueToTree(List.of(
                new RouteSection("base", null, null, List.of(point(0, 0), point(4, 0)), 4, null),
                new RouteSection("base", null, null, List.of(point(4, 0), point(9.999, 0)), 6, null))));
        assertRejected(calculation, "route", SPACING);
    }

    @Test
    void measuresPolylineInsteadOfStraightDistanceOrDeclaredLength() throws Exception {
        RouteNode root = node("root", "existing_chamber_tie_in", 0, 0, true, true);
        RouteNode chamber = node("chamber", "new_junction_chamber", 6, 4, true, false);
        RouteNode demand = node("demand", "demand_connection", 6, 5, false, false);
        List<RouteCoordinate> path = List.of(root.getCoordinate(), point(6, 0), chamber.getCoordinate());
        RouteEdge bent = new RouteEdge("bent", root.getId(), chamber.getId(), 7,
                path, List.of(), BigDecimal.ONE, 100);
        assertAccepted(calculation(List.of(root, chamber, demand), List.of(bent, edge("input", chamber, demand, 1))), "route");
    }

    @Test
    void aNewChamberResetsDistanceForTheNextChamber() throws Exception {
        RouteNode root = node("root", "existing_chamber_tie_in", 0, 0, true, true);
        RouteNode first = node("first", "new_junction_chamber", 20, 0, true, false);
        RouteNode second = node("second", "new_junction_chamber", 29.999, 0, true, false);
        RouteNode demand = node("demand", "demand_connection", 30.999, 0, false, false);
        assertRejected(calculation(List.of(root, first, second, demand), List.of(
                edge("long", root, first, 20), edge("short", first, second, 9.999),
                edge("input", second, demand, 1))), "route", SPACING);
    }

    @Test
    void branchingAtATechnicalNodeCannotBorrowAnUpstreamChamber() throws Exception {
        assertRejected(branches(false), "route", NO_CHAMBER);
    }

    @Test
    void oneChamberMayServeSeveralShortOksInputs() throws Exception {
        assertAccepted(branches(true), "route");
    }

    @Test
    void shortChamberToOksInputMayContainTechnicalPoints() throws Exception {
        RouteNode root = node("root", "existing_chamber_tie_in", 0, 0, true, true);
        RouteNode technical = node("technical", "technical_node", 0.4, 0, false, false);
        RouteNode demand = node("demand", "demand_connection", 1, 0, false, false);
        assertAccepted(calculation(List.of(root, technical, demand), List.of(
                edge("first", root, technical, 0.4), edge("input", technical, demand, 0.6))), "route");
    }

    @Test
    void allVariantsArePreflightedBeforeOutputButSelectingAValidVariantStillWorks() throws Exception {
        ObjectNode calculation = chamberPath(10, false, 10);
        ObjectNode invalid = saved(chamberPath(9.999, false, 9.999));
        invalid.put("id", "invalid").put("rank", 2);
        calculation.withArray("variants").add(invalid);
        assertRejected(calculation, "invalid", SPACING);
        exporter.validateVariant(calculation, List.of(), "route");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.writeValidatedVariant(calculation, List.of(), "route", output);
        assertThat(contract.validate(mapper.readTree(output.toByteArray()))).isEmpty();
    }

    private ObjectNode branches(boolean chamber) {
        RouteNode root = node("root", "existing_chamber_tie_in", 0, 0, true, true);
        RouteNode branch = node("branch", chamber ? "new_junction_chamber" : "technical_node", 20, 0, chamber, false);
        RouteNode first = node("first", "demand_connection", 21, 0, false, false);
        RouteNode second = node("second", "demand_connection", 20, 1, false, false);
        return calculation(List.of(root, branch, first, second), List.of(
                edge("main", root, branch, 20), edge("input-a", branch, first, 1), edge("input-b", branch, second, 1)));
    }

    private ObjectNode chamberPath(double length, boolean technical, double declaredLength) {
        RouteNode root = node("root", "existing_chamber_tie_in", 0, 0, true, true);
        RouteNode chamber = node("chamber", "new_junction_chamber", length, 0, true, false);
        RouteNode demand = node("demand", "demand_connection", length + 1, 0, false, false);
        List<RouteNode> nodes = new ArrayList<>(List.of(root, chamber, demand));
        List<RouteEdge> edges = new ArrayList<>();
        if (technical) {
            RouteNode first = node("technical-a", "technical_node", 3, 0, false, false);
            RouteNode second = node("technical-b", "technical_node", 6, 0, false, false);
            nodes.add(first);
            nodes.add(second);
            edges.add(edge("first", root, first, 3));
            edges.add(edge("middle", first, second, 3));
            edges.add(edge("last", second, chamber, declaredLength - 6));
        } else {
            edges.add(edge("main", root, chamber, declaredLength));
        }
        edges.add(edge("input", chamber, demand, 1));
        return calculation(nodes, edges);
    }

    /** Сохранённый граф с настоящей сметой: valid/complete/rank не служат доказательством геометрии. */
    private ObjectNode calculation(List<RouteNode> nodes, List<RouteEdge> edges) {
        List<RouteConnection> connections = new ArrayList<>();
        for (RouteNode node : nodes) {
            if ("demand_connection".equals(node.getNodeType())) {
                connections.add(new RouteConnection(node.getId() + "-cp", node.getId(), BigDecimal.ONE, "connected", null));
            }
        }
        VariantEconomics costs = calculator.calculate(nodes, edges, connections, ExistingNetworkReconstructionResult.empty());
        BigDecimal length = edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add);
        RouteVariant variant = new RouteVariant("route", "cheapest", nodes, edges, connections, length,
                List.of(), List.of(), ExistingNetworkReconstructionResult.empty(), costs, 1);
        ObjectNode result = mapper.createObjectNode().put("input_profile", "baseline_input");
        result.putArray("variants").add(mapper.valueToTree(variant));
        return result;
    }

    private void assertRejected(ObjectNode calculation, String selectedId, String issue) throws Exception {
        assertSavedAcceptance(calculation);
        String before = mapper.writeValueAsString(calculation);
        assertAll("Expert chamber rules apply to every export entry point",
                () -> assertIncomplete(catchThrowable(() -> exporter.export(calculation, List.of())), issue),
                () -> assertIncomplete(catchThrowable(() -> exporter.validate(calculation, List.of())), issue),
                () -> assertIncomplete(catchThrowable(() -> exporter.validateVariant(calculation, List.of(), selectedId)), issue),
                () -> assertStreamRejected(calculation, null, issue),
                () -> assertStreamRejected(calculation, selectedId, issue));
        // Чтение старого JSON совместимо; export не меняет флаги/данные для UI.
        assertThat(mapper.writeValueAsString(calculation)).isEqualTo(before);
        assertThat(mapper.readTree(before)).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(calculation)));
    }

    private void assertStreamRejected(ObjectNode calculation, String id, String issue) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Throwable failure = catchThrowable(() -> {
            if (id == null) exporter.writeValidated(calculation, List.of(), output);
            else exporter.writeValidatedVariant(calculation, List.of(), id, output);
        });
        assertAll(
                () -> assertIncomplete(failure, issue),
                () -> assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("\"type\":\"Feature\""));
    }

    private void assertIncomplete(Throwable failure, String issue) {
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE: recalculate variant")
                .hasMessageContaining(issue);
    }

    private void assertAccepted(ObjectNode calculation, String id) throws Exception {
        assertSavedAcceptance(calculation);
        exporter.validate(calculation, List.of());
        exporter.validateVariant(calculation, List.of(), id);
        JsonNode expected = exporter.export(calculation, List.of());
        assertThat(contract.validate(expected)).isEmpty();
        assertThat(expected.path("features")).isNotEmpty();
        for (boolean selected : new boolean[] {false, true}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (selected) exporter.writeValidatedVariant(calculation, List.of(), id, output);
            else exporter.writeValidated(calculation, List.of(), output);
            assertThat(mapper.readTree(output.toByteArray())).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(expected)));
        }
    }

    private void assertSavedAcceptance(ObjectNode calculation) {
        for (JsonNode variant : calculation.path("variants")) {
            assertThat(variant.path("valid").asBoolean()).isTrue();
            assertThat(variant.path("economics").path("complete").asBoolean()).isTrue();
            assertThat(variant.path("rank").asInt()).isPositive();
        }
    }

    private ObjectNode saved(ObjectNode calculation) { return (ObjectNode) calculation.path("variants").path(0); }

    private RouteNode node(String id, String type, double x, double y, boolean chamber, boolean root) {
        return new RouteNode(id, type, point(x, y), chamber, root, root && chamber ? 1 : 0, null);
    }

    private RouteEdge edge(String id, RouteNode from, RouteNode to, double length) {
        return new RouteEdge(id, from.getId(), to.getId(), length,
                List.of(from.getCoordinate(), to.getCoordinate()), List.of(), BigDecimal.ONE, 100);
    }

    private RouteCoordinate point(double x, double y) { return new RouteCoordinate(500000 + x, 6170000 + y); }
}
