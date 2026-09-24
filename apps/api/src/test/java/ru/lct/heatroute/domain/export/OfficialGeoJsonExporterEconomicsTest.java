package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.SpecialCrossingType;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteSection;
import ru.lct.heatroute.domain.routing.RouteVariant;

/** Экспорт независимо воспроизводит округление стоимости частей профиля глубины. */
class OfficialGeoJsonExporterEconomicsTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialVariantEconomicsCalculator calculator = new OfficialVariantEconomicsCalculator(pipes, economics);
    private final OfficialGeoJsonExporter exporter = new OfficialGeoJsonExporter(
            mapper, pipes, economics, new OfficialOutputContractValidator(), calculator);

    @Test
    void depthBreakpointBeforeADiagonalBendPreservesSavedConstructionCost() {
        ObjectNode calculation = calculation(false, true);
        BigDecimal expected = calculation.path("variants").path(0).path("economics")
                .path("construction_cost").decimalValue();
        assertThat(expected).isEqualByComparingTo("531991.79");

        exporter.validate(calculation, List.of());
        assertExportedCost(calculation, expected);
    }

    @Test
    void sectionBoundariesUseTheSameMillimetreStationsAsDepthPricing() {
        ObjectNode calculation = calculation(true, true);
        BigDecimal expected = calculation.path("variants").path(0).path("economics")
                .path("construction_cost").decimalValue();
        exporter.validate(calculation, List.of());
        assertExportedCost(calculation, expected);
    }

    @Test
    void twoDimensionalPricingStillUsesTheOriginalSectionLengths() {
        ObjectNode calculation = calculation(false, false);
        BigDecimal expected = calculation.path("variants").path(0).path("economics")
                .path("construction_cost").decimalValue();
        assertThat(expected).isEqualByComparingTo(economics.newNetworkCost(pipes.byDiameter(300).orElseThrow(),
                new BigDecimal("3.414"), SpecialCrossingType.BASE, new BigDecimal("3")));
        assertExportedCost(calculation, expected);
    }

    @Test
    void oneKopeckMismatchStillBlocksExportEvenWithConsistentSavedTotals() {
        ObjectNode calculation = calculation(false, true);
        ObjectNode saved = (ObjectNode) calculation.path("variants").path(0).path("economics");
        BigDecimal changed = saved.path("construction_cost").decimalValue().add(new BigDecimal("0.01"));
        saved.put("construction_cost", changed);
        saved.put("calculated_cost", changed);
        saved.put("score", economics.score(changed, saved.path("new_network_length").decimalValue()));

        assertThatThrownBy(() -> exporter.validate(calculation, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exported construction components disagree with saved economics");
    }

    private void assertExportedCost(ObjectNode calculation, BigDecimal expected) {
        JsonNode output = exporter.export(calculation, List.of());
        BigDecimal physical = StreamSupport.stream(output.path("features").spliterator(), false)
                .map(feature -> feature.path("properties"))
                .filter(properties -> "heat_network".equals(properties.path("object_type").asText()))
                .map(properties -> properties.path("cost").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(physical).isEqualByComparingTo(expected);
    }

    private ObjectNode calculation(boolean separateSections, boolean depthEnabled) {
        List<RouteCoordinate> coordinates = List.of(new RouteCoordinate(500000, 6100000),
                new RouteCoordinate(500001, 6100001), new RouteCoordinate(500003, 6100001));
        double length = separateSections ? 3.415 : 3.414;
        List<RouteSection> sections = separateSections
                ? List.of(new RouteSection("base", null, null, coordinates.subList(0, 2), 1.414, null),
                        new RouteSection("base", null, null, coordinates.subList(1, 3), 2, null))
                : List.of(new RouteSection("base", null, null, coordinates, length, null));
        DepthProfileResult profile = depthEnabled ? new DepthProfileResult(true,
                List.of(point("0", "3"), point("1.214", "3"), point(Double.toString(length), "4.2")),
                List.of(), List.of(), BigDecimal.valueOf(length), BigDecimal.valueOf(length)) : null;
        RouteEdge edge = new RouteEdge("edge", "start", "end", length, coordinates, sections,
                BigDecimal.ONE, 300, profile);
        List<RouteNode> nodes = List.of(
                new RouteNode("start", "technical_node", coordinates.get(0), false, true, 0, null),
                new RouteNode("end", "demand_connection", coordinates.get(2), false, false, 0, null));
        List<RouteConnection> connections = List.of(new RouteConnection("demand", "end", BigDecimal.ONE, "connected", null));
        VariantEconomics costs = calculator.calculate(nodes, List.of(edge), connections, ExistingNetworkReconstructionResult.empty());
        RouteVariant variant = new RouteVariant("cheapest", "cheapest", nodes, List.of(edge), connections,
                edge.getLengthM(), List.of(), List.of(), ExistingNetworkReconstructionResult.empty(), costs, 1);
        ObjectNode result = mapper.createObjectNode();
        result.put("input_profile", "baseline_input");
        result.putArray("variants").add(mapper.valueToTree(variant));
        return result;
    }

    private DepthProfilePoint point(String station, String depth) {
        return new DepthProfilePoint(new BigDecimal(station), new BigDecimal(depth));
    }
}
