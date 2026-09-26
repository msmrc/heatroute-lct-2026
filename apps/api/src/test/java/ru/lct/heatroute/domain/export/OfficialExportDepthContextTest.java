package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteSection;
import ru.lct.heatroute.domain.routing.RouteVariant;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.run.OfficialRunService;
import ru.lct.heatroute.domain.run.OfficialRunView;
import ru.lct.heatroute.domain.topology.OfficialFeatureRepository;

/** Проверяет экспорт через параметры сохранённого запуска, с настоящей геометрией и сметой. */
class OfficialExportDepthContextTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialVariantEconomicsCalculator calculator =
            new OfficialVariantEconomicsCalculator(pipes, economics);
    private final OfficialOutputContractValidator contract = new OfficialOutputContractValidator();
    private final OfficialGeoJsonExporter exporter =
            new OfficialGeoJsonExporter(mapper, pipes, economics, contract, calculator);
    private final UUID runId = UUID.fromString("b1bb892f-41da-450e-9884-4fc0cb12a224");

    @Test
    void rejectsEveryProfileRemovedFromDepthEnabledRun() {
        for (String selected : new String[] {null, "cheapest"}) {
            ObjectNode result = calculation(null);
            // В самом result глубина не помечена: её включение доказано сохранёнными параметрами.
            assertThatThrownBy(() -> service(result, parameters(true, "10")).prepare(runId, selected))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE");
        }
    }

    @Test
    void acceptsGenuineTwoDimensionalAndCompleteDepthRuns() throws Exception {
        for (boolean enabled : new boolean[] {false, true}) {
            for (String selected : new String[] {null, "cheapest"}) {
                ObjectNode result = calculation(enabled ? "3" : null);
                OfficialExportPayload payload = service(result, parameters(enabled, "10"))
                        .prepare(runId, selected);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                payload.writeTo(bytes);
                JsonNode saved = mapper.readTree(bytes.toByteArray());
                assertThat(contract.validate(saved, true)).isEmpty();
                assertThat(saved.path("features")).isNotEmpty();
            }
        }
    }

    @Test
    void usesStoredMaximumInsteadOfApplicationDefault() {
        for (String selected : new String[] {null, "cheapest"}) {
            assertThatThrownBy(() -> service(calculation("3.2"), parameters(true, "3.1"))
                    .prepare(runId, selected))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE");
        }
    }

    @Test
    void rechecksTrustedContextBeforeFirstOutputByte() throws Exception {
        for (String selected : new String[] {null, "cheapest"}) {
            ObjectNode result = calculation("3");
            OfficialExportPayload payload = service(result, parameters(true, "10")).prepare(runId, selected);
            result.path("variants").forEach(variant -> variant.path("edges").forEach(edge ->
                    ((ObjectNode) edge).remove("depth_profile")));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertThatThrownBy(() -> payload.writeTo(bytes))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE");
            assertThat(bytes.size()).isZero();
        }
    }

    @Test
    void rejectsMissingTrustedRunParameters() {
        assertThatThrownBy(() -> service(calculation(null), null).prepare(runId, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE");
    }

    @Test
    void doesNotAcceptProfilesForARecordedTwoDimensionalRun() {
        assertThatThrownBy(() -> service(calculation("3"), parameters(false, "10")).prepare(runId, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DEPTH_MODE_MISMATCH");
    }

    @Test
    void usesStoredMinimumAndRejectsInvalidStoredBounds() {
        OfficialRunParameters minimum = new OfficialRunParameters(new BigDecimal("2.5"),
                new BigDecimal("10"), true).validated();
        assertThatThrownBy(() -> service(calculation("2.4"), minimum).prepare(runId, "cheapest"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE");
        OfficialRunParameters invalid = new OfficialRunParameters(new BigDecimal("0.1"),
                new BigDecimal("10"), true);
        assertThatThrownBy(() -> service(calculation("3"), invalid).prepare(runId, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("saved run parameters are invalid");
    }

    private OfficialExportService service(ObjectNode result, OfficialRunParameters parameters) {
        OfficialRunService runs = mock(OfficialRunService.class);
        OfficialFeatureRepository features = mock(OfficialFeatureRepository.class);
        UUID importId = UUID.fromString("b0b95db4-df87-47b0-8277-7bfa49e3df70");
        OffsetDateTime time = OffsetDateTime.parse("2026-09-26T00:00:00Z");
        OfficialRunView run = new OfficialRunView(runId, importId, null, "completed", "test", "test-sha",
                parameters, result, null, null, time, time);
        when(runs.find(runId)).thenReturn(run);
        when(features.findByImport(importId)).thenReturn(List.of());
        return new OfficialExportService(runs, features, exporter);
    }

    private ObjectNode calculation(String depth) {
        List<RouteCoordinate> points = List.of(new RouteCoordinate(500000, 6100000),
                new RouteCoordinate(500100, 6100000));
        DepthProfileResult profile = depth == null ? null : new DepthProfileResult(true,
                List.of(new DepthProfilePoint(BigDecimal.ZERO, new BigDecimal(depth)),
                        new DepthProfilePoint(new BigDecimal("100"), new BigDecimal(depth))),
                List.of(), List.of(), new BigDecimal("100"),
                new BigDecimal("100").multiply(economics.depthMultiplier(new BigDecimal(depth))));
        RouteEdge edge = new RouteEdge("edge", "root", "end", 100, points,
                List.of(new RouteSection("base", null, null, points, 100, null)), BigDecimal.ONE, 50, profile);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "new_branch_chamber", points.get(0), true, true, 0, null),
                new RouteNode("end", "demand_connection", points.get(1), false, false, 0, null));
        List<RouteConnection> connections = List.of(
                new RouteConnection("demand", "end", BigDecimal.ONE, "connected", null));
        ExistingNetworkReconstructionResult reconstruction = ExistingNetworkReconstructionResult.empty();
        RouteVariant variant = new RouteVariant("cheapest", "cheapest", nodes, List.of(edge), connections,
                edge.getLengthM(), List.of(), List.of(), reconstruction,
                calculator.calculate(nodes, List.of(edge), connections, reconstruction), 1);
        ObjectNode result = mapper.createObjectNode().put("input_profile", "baseline_input");
        result.putArray("variants").add(mapper.valueToTree(variant));
        return result;
    }

    private OfficialRunParameters parameters(boolean enabled, String maximum) {
        return new OfficialRunParameters(new BigDecimal("0.7"), new BigDecimal(maximum), enabled).validated();
    }
}
