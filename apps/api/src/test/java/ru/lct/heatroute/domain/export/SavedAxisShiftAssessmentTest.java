package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.connections;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.edges;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.necessaryObstacle;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.nodes;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.variant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.AxisShiftAlternativeEvaluator;
import ru.lct.heatroute.domain.routing.OfficialCalculationResult;
import ru.lct.heatroute.domain.routing.RouteAxisShiftControl;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteVariant;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

class SavedAxisShiftAssessmentTest {
    private final ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics costs = new OfficialEconomics();
    private final AxisShiftAlternativeEvaluator evaluator = AxisShiftAlternativeEvaluator.standard(pipes, costs);

    @Test
    void savedValidFlagCannotAuthorizeARemovableStep() {
        ObjectNode saved = saved();

        assertThat(saved.path("valid").asBoolean()).isTrue();
        assertThatThrownBy(() -> SavedAxisShiftAssessment.verify(saved, List.of(), null, evaluator))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EXPERT_UNNECESSARY_AXIS_SHIFT");
    }

    @Test
    void retainsNecessaryBypassUsingTheOriginalConstraintGeometry() {
        assertThatCode(() -> SavedAxisShiftAssessment.verify(saved(), List.of(necessaryObstacle()), null, evaluator))
                .doesNotThrowAnyException();
    }

    @Test
    void alreadyAlignedResultIsExportableWithoutChangingIt() {
        RouteVariant aligned = new RouteAxisShiftControl().firstImprovement(nodes(), edges(), replacement -> evaluator.assess(
                replacement, "aligned", "shortest", connections(), List.of(), OfficialRunParameters.defaults()));
        assertThat(aligned).isNotNull();
        ObjectNode saved = mapper.valueToTree(aligned);
        ObjectNode before = saved.deepCopy();

        assertThatCode(() -> SavedAxisShiftAssessment.verify(saved, List.of(), OfficialRunParameters.defaults(), evaluator))
                .doesNotThrowAnyException();
        assertThat(saved).isEqualTo(before);
    }

    @Test
    void exactInputSignatureDeduplicatesRolesButNotDifferentFlows() {
        ObjectNode engineering = saved();
        ObjectNode cheapest = engineering.deepCopy()
                .put("id", "cheapest")
                .put("strategy", "cheapest")
                .put("rank", 2);

        assertThat(SavedAxisShiftAssessment.inputSignature(cheapest))
                .isEqualTo(SavedAxisShiftAssessment.inputSignature(engineering));

        ((ObjectNode) cheapest.path("edges").path(0)).put("flow_tph", new java.math.BigDecimal("2.0001"));
        assertThat(SavedAxisShiftAssessment.inputSignature(cheapest))
                .isNotEqualTo(SavedAxisShiftAssessment.inputSignature(engineering));
    }

    @Test
    void denseCollinearDigitizationDoesNotHideTheShift() {
        ObjectNode saved = saved();
        ArrayNode points = ((ObjectNode) saved.path("edges").path(0)).putArray("coordinates");
        for (int i = 0; i <= 2000; i++) points.add(mapper.valueToTree(new RouteCoordinate(i / 100.0, 0)));
        for (int i = 1; i <= 500; i++) points.add(mapper.valueToTree(new RouteCoordinate(20, i / 100.0)));

        assertThatThrownBy(() -> SavedAxisShiftAssessment.verify(saved, List.of(), null, evaluator))
                .hasMessageContaining("EXPERT_UNNECESSARY_AXIS_SHIFT");
    }

    @Test
    void doesNotInventMissingConnectedDemandFlowForAlternativeSizing() {
        ObjectNode saved = saved();
        ((ObjectNode) saved.path("connections").path(0)).remove("flow_tph");

        assertThatThrownBy(() -> SavedAxisShiftAssessment.verify(saved, List.of(), null, evaluator))
                .hasMessageContaining("AXIS_SHIFT_FLOW_UNCHECKABLE");
    }

    @Test
    void doesNotTreatOmittedDemandConnectionsAsZeroFlow() {
        ObjectNode saved = saved();
        saved.putArray("connections");

        assertThatThrownBy(() -> SavedAxisShiftAssessment.verify(saved, List.of(), null, evaluator))
                .hasMessageContaining("AXIS_SHIFT_FLOW_UNCHECKABLE");
    }

    @Test
    void rejectsTheOldResultBeforeWritingTheFirstByte() {
        OfficialGeoJsonExporter exporter = new OfficialGeoJsonExporter(mapper, pipes, costs,
                new OfficialOutputContractValidator(), new OfficialVariantEconomicsCalculator(pipes, costs));
        ObjectNode calculation = mapper.valueToTree(new OfficialCalculationResult("global-tree-102", 2,
                List.of(variant(nodes(), edges()).withRank(1)), "shortest"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> exporter.writeValidated(calculation, List.of(), output))
                .hasMessageContaining("EXPERT_UNNECESSARY_AXIS_SHIFT");
        assertThat(output.size()).isZero();
    }

    private ObjectNode saved() {
        return mapper.valueToTree(variant(nodes(), edges()));
    }
}
