package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.io.WKTReader;
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
import ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Export повторно проверяет нормали и расстояние 2 м, включая округлённые выдаваемые секции. */
class SavedChamberGeometryTest {
    private final ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialVariantEconomicsCalculator calculator = new OfficialVariantEconomicsCalculator(pipes, economics);
    private final OfficialGeoJsonExporter exporter = new OfficialGeoJsonExporter(mapper, pipes, economics,
            new OfficialOutputContractValidator(), calculator);

    @ParameterizedTest
    @CsvSource({"1.999,false", "2,true", "2.001,true", "5,true"})
    void checksMeasuredTwoMetresBeforeEveryExportEntryPoint(double straight, boolean valid) {
        ObjectNode saved = path(straight);
        if (valid) assertThat(SavedChamberAssessment.verify(saved, support(), economics)).isEmpty();
        else rejectsAllExportEntryPoints(saved, "EXPERT_CHAMBER_BEND_TOO_CLOSE");
    }

    @Test
    void validatesEmittedCoordinatesAfterMillimetreQuantization() {
        ObjectNode saved = path(2);
        ObjectNode edge = (ObjectNode) saved.path("edges").path(0);
        edge.set("sections", mapper.valueToTree(List.of(new RouteSection("base", null, null,
                List.of(point(0, 0), point(1.999, 0), point(1.999, 8)), 10, null))));
        rejectsAllExportEntryPoints(saved, "EXPERT_CHAMBER_BEND_TOO_CLOSE");
    }

    @Test
    void originalInvalidApproachCannotBeReplacedByAToleranceEquivalentValidSection() {
        ObjectNode saved = path(1.999);
        ObjectNode edge = (ObjectNode) saved.path("edges").path(0);
        edge.set("sections", mapper.valueToTree(List.of(new RouteSection("base", null, null,
                List.of(point(0, 0), point(2, 0), point(2, 8)), 10, null))));
        rejectsAllExportEntryPoints(saved, "EXPERT_CHAMBER_BEND_TOO_CLOSE");
    }

    @ParameterizedTest
    @CsvSource({"20,0,false", "0,20,true", "0,-20,true", "20,20,false"})
    void sourceChamberUsesBothDirectionsOfExistingThroughLine(double x, double y, boolean valid) throws Exception {
        RouteNode c = root(), d = demand(x, y);
        ObjectNode saved = saved(List.of(c, d), List.of(edge(c, d, List.of(c.getCoordinate(), d.getCoordinate()))));
        ImportedOfficialFeature source = new ImportedOfficialFeature("network", "heat_network",
                mapper.readTree("{\"diameter\":100}"), new WKTReader().read("LINESTRING (399980 6000000,400020 6000000)"));
        ExistingNetworkSupportIndex support = new ExistingNetworkSupportIndex(List.of(source));
        if (valid) assertThat(SavedChamberAssessment.verify(saved, support, economics)).isEmpty();
        else assertThatThrownBy(() -> SavedChamberAssessment.verify(saved, support, economics))
                .hasMessageContaining("EXPERT_CHAMBER_OBLIQUE_ENTRY");
    }

    @Test
    void aTechnicalSplitCannotHideABendOneMetreFromTheChamber() {
        RouteNode c = root(), d = demand(1, 8);
        RouteNode t = new RouteNode("t", "technical_node", point(1, 0), false, false, 0, null);
        rejectsAllExportEntryPoints(saved(List.of(c, t, d), List.of(
                edge(c, t, List.of(c.getCoordinate(), t.getCoordinate())),
                edge(t, d, List.of(t.getCoordinate(), d.getCoordinate())))), "EXPERT_CHAMBER_BEND_TOO_CLOSE");
    }

    @Test
    void storedAcceptedObliqueJunctionCannotBeExported() {
        RouteNode c = root(), junction = new RouteNode("junction", "new_junction_chamber", point(20, 0), true, false, 0, null);
        RouteNode d = demand(30, 10);
        rejectsAllExportEntryPoints(saved(List.of(c, junction, d), List.of(
                edge(c, junction, List.of(c.getCoordinate(), junction.getCoordinate())),
                edge(junction, d, List.of(junction.getCoordinate(), d.getCoordinate())))), "EXPERT_CHAMBER_OBLIQUE_ENTRY");
    }

    @ParameterizedTest
    @CsvSource({"1.999,false", "2,true", "2.001,true"})
    void savedRunsMustKeepTwoMetresBetweenActualInternalBends(double spacing, boolean valid) {
        RouteNode c = root(), d = demand(20, spacing);
        ObjectNode saved = saved(List.of(c, d), List.of(edge(c, d,
                List.of(c.getCoordinate(), point(10, 0), point(10, spacing), d.getCoordinate()))));
        if (valid) assertThat(SavedChamberAssessment.verify(saved, support(), economics)).isEmpty();
        else rejectsAllExportEntryPoints(saved, "EXPERT_ROUTE_BENDS_TOO_CLOSE");
    }

    @ParameterizedTest
    @CsvSource({"89.999,false", "90,true", "135,true", "135.001,true", "150,true", "180,true"})
    void savedRunsCannotExportAnIllegalInternalBendAngle(double angle, boolean valid) {
        double deflection = Math.toRadians(180 - angle);
        RouteNode c = root(), d = demand(1000 + 1000 * Math.cos(deflection), 1000 * Math.sin(deflection));
        ObjectNode saved = saved(List.of(c, d), List.of(edge(c, d,
                List.of(c.getCoordinate(), point(1000, 0), d.getCoordinate()))));
        if (valid) assertThat(SavedChamberAssessment.verify(saved, support(), economics)).isEmpty();
        else rejectsAllExportEntryPoints(saved, "ROUTE_DEFLECTION_EXCEEDED");
    }

    @Test
    void roundedEmittedSectionsCannotReduceTheDistanceBetweenBendsBelowTwoMetres() {
        RouteNode c = root(), d = demand(20, 2);
        ObjectNode saved = saved(List.of(c, d), List.of(edge(c, d,
                List.of(c.getCoordinate(), point(10, 0), point(10, 2), d.getCoordinate()))));
        ((ObjectNode) saved.path("edges").path(0)).set("sections", mapper.valueToTree(List.of(
                new RouteSection("base", null, null, List.of(c.getCoordinate(), point(10, 0),
                        point(10, 1.999), point(20, 1.999)), 22, null))));
        rejectsAllExportEntryPoints(saved, "EXPERT_ROUTE_BENDS_TOO_CLOSE");
    }

    @Test
    void technicalEdgeBoundariesDoNotResetTheDistanceBetweenStoredBends() {
        RouteNode c = root(), d = demand(20, 1.999);
        RouteNode t1 = new RouteNode("t1", "technical_node", point(10, 0), false, false, 0, null);
        RouteNode t2 = new RouteNode("t2", "technical_node", point(10, 1.999), false, false, 0, null);
        ObjectNode saved = saved(List.of(c, t1, t2, d), List.of(
                edge(c, t1, List.of(c.getCoordinate(), t1.getCoordinate())),
                edge(t1, t2, List.of(t1.getCoordinate(), t2.getCoordinate())),
                edge(t2, d, List.of(t2.getCoordinate(), d.getCoordinate()))));
        rejectsAllExportEntryPoints(saved, "EXPERT_ROUTE_BENDS_TOO_CLOSE");
    }

    private void rejectsAllExportEntryPoints(ObjectNode saved, String code) {
        assertThat(saved.path("valid").asBoolean()).isTrue();
        ObjectNode calculation = mapper.createObjectNode().put("input_profile", "baseline_input");
        calculation.putArray("variants").add(saved);
        assertThatThrownBy(() -> exporter.validate(calculation, List.of())).hasMessageContaining(code);
        assertThatThrownBy(() -> exporter.validateVariant(calculation, List.of(), "route")).hasMessageContaining(code);
        assertThatThrownBy(() -> exporter.export(calculation, List.of())).hasMessageContaining(code);
        for (boolean selected : new boolean[] {false, true}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThatThrownBy(() -> {
                if (selected) exporter.writeValidatedVariant(calculation, List.of(), "route", output);
                else exporter.writeValidated(calculation, List.of(), output);
            }).hasMessageContaining(code);
            assertThat(output.size()).isZero();
        }
    }

    private ObjectNode path(double straight) {
        RouteNode c = root(), d = demand(straight, 8);
        return saved(List.of(c, d), List.of(edge(c, d, List.of(c.getCoordinate(), point(straight, 0), d.getCoordinate()))));
    }

    private ObjectNode saved(List<RouteNode> nodes, List<RouteEdge> edges) {
        List<RouteConnection> connections = List.of(new RouteConnection("cp", "demand", BigDecimal.ONE, "connected", null));
        VariantEconomics costs = calculator.calculate(nodes, edges, connections, ExistingNetworkReconstructionResult.empty());
        return mapper.valueToTree(new RouteVariant("route", "cheapest", nodes, edges, connections,
                edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add),
                List.of(), List.of(), ExistingNetworkReconstructionResult.empty(), costs, 1));
    }

    private RouteNode root() {
        return new RouteNode("root", "existing_chamber_tie_in", point(0, 0), true, true, 1, "source");
    }
    private RouteNode demand(double x, double y) {
        return new RouteNode("demand", "demand_connection", point(x, y), false, false, 0, "cp");
    }
    private RouteCoordinate point(double x, double y) { return new RouteCoordinate(400000 + x, 6000000 + y); }
    private RouteEdge edge(RouteNode a, RouteNode b, List<RouteCoordinate> path) {
        double length = 0;
        for (int i = 1; i < path.size(); i++) length += path.get(i - 1).toCoordinate().distance(path.get(i).toCoordinate());
        return new RouteEdge(a.getId() + "-" + b.getId(), a.getId(), b.getId(), length, path, List.of(), BigDecimal.ONE, 100);
    }
    private ExistingNetworkSupportIndex support() { return new ExistingNetworkSupportIndex(List.of()); }
}
