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
        BigDecimal expected = pipeConstructionCost(calculation);
        assertThat(expected).isEqualByComparingTo("514155.40");

        exporter.validate(calculation, List.of());
        assertExportedCost(calculation, expected);
    }

    @Test
    void sectionBoundariesUseTheSameMillimetreStationsAsDepthPricing() {
        ObjectNode calculation = calculation(true, true);
        BigDecimal expected = pipeConstructionCost(calculation);
        exporter.validate(calculation, List.of());
        assertExportedCost(calculation, expected);
    }

    @Test
    void twoDimensionalPricingPreservesPerLegRoundingAtTheNewLegalBendPosition() {
        ObjectNode calculation = calculation(false, false);
        BigDecimal expected = pipeConstructionCost(calculation);
        // 3.414 м распределены пропорционально sqrt(2)*2.2 и .303; цена каждой части
        // отдельно округлена до копеек при 150022 руб/м. Округление общей цены дало бы .11.
        assertThat(expected).isEqualByComparingTo("512175.10");
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

    @Test
    void costOfMillimetreCoordinatesIsInvariantUnderLargeUtmTranslation() {
        BigDecimal control = null;
        for (double[] origin : new double[][] {{0, 0}, {400000.179, 6000000.382}}) {
            for (boolean depthEnabled : new boolean[] {false, true}) {
                var coordinates = List.of(new RouteCoordinate(origin[0], origin[1]),
                        new RouteCoordinate(origin[0] - 8.470, origin[1] + 3.399),
                        new RouteCoordinate(origin[0] - 8.312, origin[1] + 3.791),
                        new RouteCoordinate(origin[0] - 5.420, origin[1] + 10.998));
                var profile = depthEnabled ? new DepthProfileResult(true,
                        List.of(point("0", "3"), point("17.315", "3")), List.of(), List.of(),
                        new BigDecimal("17.315"), new BigDecimal("17.315")) : null;
                var edge = new RouteEdge("edge", "start", "end", 17.315, coordinates,
                        List.of(new RouteSection("base", null, null, coordinates, 17.315, null)),
                        BigDecimal.ONE, 125, profile);
                ObjectNode calculation = calculation(edge);
                BigDecimal expected = pipeConstructionCost(calculation);
                if (control == null) control = expected;
                assertThat(expected).isEqualByComparingTo(control);
                exporter.validate(calculation, List.of());
                assertExportedCost(calculation, expected);
            }
        }
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

    private BigDecimal pipeConstructionCost(ObjectNode calculation) {
        JsonNode saved = calculation.path("variants").path(0).path("economics");
        // Тест проверяет округление цены трубы. Камера ввода теперь явно присутствует
        // и оплачивается отдельно; прежние денежные границы трубы остаются неизменными.
        assertThat(saved.path("chamber_construction_cost").decimalValue()).isPositive();
        return saved.path("construction_cost").decimalValue()
                .subtract(saved.path("chamber_construction_cost").decimalValue());
    }

    private ObjectNode calculation(boolean separateSections, boolean depthEnabled) {
        // Первый отрезок образует разрешённый внутренний угол 120° и длиннее 3 м для ДУ300;
        // сохраняем сметную длину 3.414 м
        // и breakpoint 1.214 м перед поворотом, на которых воспроизводится округление цены.
        List<RouteCoordinate> coordinates = List.of(new RouteCoordinate(500000, 6100000),
                new RouteCoordinate(500001.501, 6100002.725), new RouteCoordinate(500001.804, 6100002.725));
        double length = separateSections ? 3.415 : 3.414;
        List<RouteSection> sections = separateSections
                ? List.of(new RouteSection("base", null, null, coordinates.subList(0, 2), 3.111, null),
                        new RouteSection("base", null, null, coordinates.subList(1, 3), .303, null))
                : List.of(new RouteSection("base", null, null, coordinates, length, null));
        DepthProfileResult profile = depthEnabled ? new DepthProfileResult(true,
                List.of(point("0", "3"), point("1.214", "3"), point(Double.toString(length), "3.12")),
                List.of(), List.of(), BigDecimal.valueOf(1.214 + Math.hypot(length - 1.214, .12)),
                BigDecimal.valueOf(length).add(BigDecimal.valueOf(length - 1.214).multiply(new BigDecimal(".006")))) : null;
        RouteEdge edge = new RouteEdge("edge", "start", "end", length, coordinates, sections,
                BigDecimal.ONE, 300, profile);
        return calculation(edge);
    }

    private ObjectNode calculation(RouteEdge edge) {
        List<RouteCoordinate> coordinates = edge.getCoordinates();
        List<RouteNode> nodes = List.of(
                new RouteNode("start", "new_branch_chamber", coordinates.get(0), true, true, 0, null),
                new RouteNode("end", "demand_connection", coordinates.get(coordinates.size() - 1),
                        false, false, 0, null));
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
