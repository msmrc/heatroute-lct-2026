package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.connections;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.edge;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.edges;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.necessaryObstacle;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.node;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.nodes;
import static ru.lct.heatroute.domain.routing.AxisShiftTestNetwork.variant;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class RouteAxisShiftControlTest {
    private final RouteAxisShiftControl control = new RouteAxisShiftControl();
    private final AxisShiftAlternativeEvaluator evaluator = AxisShiftAlternativeEvaluator.standard(
            new OfficialPipeCatalog(), new OfficialEconomics());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void alignsWholeRunAndRebuildsAllDerivedValuesEvenIfTotalLengthIncreases(boolean depth) {
        OfficialRunParameters parameters = new OfficialRunParameters(null, null, depth);
        RouteVariant aligned = align(nodes(), edges(), List.of(), parameters);

        assertThat(aligned).isNotNull();
        assertThat(aligned.getTotalLengthM()).isEqualByComparingTo("90.000");
        assertThat(aligned.getConnectedDemandCount()).isEqualTo(2);
        assertThat(aligned.getValidationIssues()).isEmpty();
        assertThat(aligned.getSizingIssues()).isEmpty();
        assertThat(aligned.getEconomics().isComplete()).isTrue();
        assertThat(new EngineeringRouteEvaluator().evaluate(aligned.getEdges()).bendCount()).isEqualTo(1);
        assertThat(new ExpertChamberRouteValidator().validate(aligned.getNodes(), aligned.getEdges())).isEmpty();
        assertThat(aligned.getNodes().stream().filter(RouteNode::isChamber).count()).isEqualTo(3);
        assertThat(aligned.getNodes()).filteredOn(at -> at.getId().equals("j") || at.getId().equals("k"))
                .allSatisfy(at -> assertThat(at.getCoordinate().getYM()).isEqualByComparingTo("0"));
        assertThat(aligned.getNodes()).filteredOn(at -> at.isRoot() || at.getTargetId() != null)
                .allSatisfy(at -> assertThat(at.getCoordinate().toCoordinate()).isEqualTo(nodes().stream()
                        .filter(original -> original.getId().equals(at.getId())).findFirst().orElseThrow().getCoordinate().toCoordinate()));
        assertThat(aligned.getEdges()).allSatisfy(at -> {
            assertThat(at.getDiameter()).isEqualTo(50);
            assertThat(at.getSections()).isNotEmpty();
            if (depth) {
                assertThat(at.getDepthProfile()).isNotNull();
                assertThat(at.getDepthProfile().isComplete()).isTrue();
            } else assertThat(at.getDepthProfile()).isNull();
        });
        assertThat(edges().get(0).getCoordinates()).hasSize(3);
        assertThat(nodes().get(1).getCoordinate().getYM()).isEqualByComparingTo("5");
    }

    @Test
    void retainsShiftWhenStraightAlternativeCrossesForbiddenSourceGeometry() {
        assertThat(align(nodes(), edges(), List.of(necessaryObstacle()), OfficialRunParameters.defaults())).isNull();
    }

    @Test
    void neverMovesAnAnchoredConnectionOrRoot() {
        List<RouteNode> anchored = new ArrayList<>(nodes());
        anchored.set(2, node("k", 40, 5, true, false, "existing-connection"));
        AtomicBoolean assessed = new AtomicBoolean();

        assertThat(control.firstImprovement(anchored, edges(), replacement -> {
            assessed.set(true);
            return variant(replacement.getNodes(), replacement.getEdges());
        })).isNull();
        assertThat(assessed).isFalse();
    }

    @Test
    void collinearTechnicalSplitsDoNotHideTheStepOrMergeRealChambers() {
        List<RouteNode> splitNodes = new ArrayList<>(nodes());
        splitNodes.add(node("t", 20, 0, false, false, null));
        splitNodes.add(node("u", 20, 2, false, false, null));
        List<RouteEdge> splitEdges = new ArrayList<>(edges());
        splitEdges.remove(0);
        splitEdges.add(edge("a", "root", "t", "2.000", 0, 0, 20, 0));
        splitEdges.add(edge("b", "t", "u", "2.000", 20, 0, 20, 2));
        splitEdges.add(edge("c", "u", "j", "2.000", 20, 2, 20, 5));

        RouteVariant aligned = align(splitNodes, splitEdges, List.of(), OfficialRunParameters.defaults());

        assertThat(aligned).isNotNull();
        assertThat(aligned.getNodes()).extracting(RouteNode::getId)
                .containsExactlyInAnyOrder("root", "j", "k", "demand:one", "demand:two");
        assertThat(aligned.getEdges()).hasSize(4).allSatisfy(at -> assertThat(at.getLengthM()).isPositive());
        assertThat(aligned.getTotalLengthM()).isEqualByComparingTo("90.000");
    }

    @Test
    void detectionIsIndependentOfMapOrientationAndMetricOrigin() {
        List<RouteNode> rotatedNodes = nodes().stream().map(at -> new RouteNode(at.getId(), at.getNodeType(),
                rotate(at.getCoordinate()), at.isChamber(), at.isRoot(), at.getBaseIncidentSections(), at.getTargetId()))
                .collect(Collectors.toList());
        List<RouteEdge> rotatedEdges = edges().stream().map(at -> new RouteEdge(at.getId(), at.getUpstreamNodeId(),
                at.getDownstreamNodeId(), at.getLengthM().doubleValue(), at.getCoordinates().stream()
                        .map(RouteAxisShiftControlTest::rotate).collect(Collectors.toList()),
                List.of(), at.getFlowTph(), at.getDiameter())).collect(Collectors.toList());

        RouteVariant aligned = align(rotatedNodes, rotatedEdges, List.of(), OfficialRunParameters.defaults());

        assertThat(aligned).isNotNull();
        assertThat(new EngineeringRouteEvaluator().evaluate(aligned.getEdges()).bendCount()).isEqualTo(1);
    }

    @Test
    void finalStableStageAppliesTheSameControlBeforeReassigningRoles() {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        List<RouteVariant> refined = new OfficialDatasetRoutingTest().planner().refineSelectedAxisShifts(
                List.of(variant(nodes(), edges())), List.of(), OfficialRunParameters.defaults(),
                new OfficialObstacleRouter(rules).prepare(List.of()));

        assertThat(refined).isNotEmpty().allSatisfy(at -> {
            assertThat(at.getTotalLengthM()).isEqualByComparingTo("90.000");
            assertThat(at.getConnectedDemandCount()).isEqualTo(2);
            assertThat(at.getEngineeringIssues()).isEmpty();
        });
    }

    @Test
    void stableStageRetainsRequiredBypassAndLeavesUnchangedRolesAlone() {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        List<ImportedOfficialFeature> features = List.of(necessaryObstacle());
        List<RouteVariant> originals = List.of(variant(nodes(), edges()));

        List<RouteVariant> refined = new OfficialDatasetRoutingTest().planner().refineSelectedAxisShifts(
                originals, features, OfficialRunParameters.defaults(), new OfficialObstacleRouter(rules).prepare(features));

        assertThat(refined).isSameAs(originals);
    }

    private RouteVariant align(List<RouteNode> nodes, List<RouteEdge> edges,
            List<ImportedOfficialFeature> features, OfficialRunParameters parameters) {
        return control.firstImprovement(nodes, edges, replacement -> evaluator.assess(
                replacement, "aligned", "shortest", connections(), features, parameters));
    }

    private static RouteCoordinate rotate(RouteCoordinate point) {
        double angle = Math.toRadians(27), x = point.getXM().doubleValue(), y = point.getYM().doubleValue();
        return new RouteCoordinate(414000 + x * Math.cos(angle) - y * Math.sin(angle),
                6173000 + x * Math.sin(angle) + y * Math.cos(angle));
    }
}
