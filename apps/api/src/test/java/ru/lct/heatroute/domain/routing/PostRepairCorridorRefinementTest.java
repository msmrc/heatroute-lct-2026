package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

/** Реальное обязательное исправление, затем объединение, sizing/depth и окончательный допуск. */
class PostRepairCorridorRefinementTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mergesLegalNetworkCreatedByMandatoryRepairWithoutLosingConsumers(boolean depth) {
        var planner = new OfficialDatasetRoutingTest().planner();
        var rules = new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        var environment = new OfficialObstacleRouter(rules).prepare(List.of());
        var parameters = new OfficialRunParameters(null, null, depth).validated();
        List<RouteNode> nodes = List.of(node("root", -100, 0, true, true), node("a", 0, 0, true, false),
                node("b", 30, 0, true, false), node("demand:east", 130, 0, false, false),
                node("demand:north", -20, 100, false, false), node("demand:south", 30, -100, false, false));
        List<RouteEdge> edges = List.of(edge(nodes.get(0), nodes.get(1), 6), edge(nodes.get(1), nodes.get(2), 4),
                edge(nodes.get(2), nodes.get(3), 1), edge(nodes.get(1), nodes.get(4), 2), edge(nodes.get(2), nodes.get(5), 3));
        List<RouteConnection> connections = List.of(connection("east", 1), connection("north", 2), connection("south", 3));
        var byId = nodes.stream().collect(Collectors.toMap(RouteNode::getId, n -> n));
        var demands = connections.stream().map(c -> new OfficialRoutePlanner.Demand(c.getDemandId(),
                c.getConnectionPointId(), byId.get("demand:" + c.getDemandId()).getCoordinate().toCoordinate(),
                c.getFlowTph(), null)).collect(Collectors.toList());
        var seed = planner.withEngineeringAssessment(planner.finish("seed", "engineering",
                new OfficialRoutePlanner.VariantDraft(nodes, edges, connections), List.of(), parameters, false,
                environment, TerminalApproachPolicy.PRESERVE_VALID));
        assertThat(seed.getValidationIssues()).extracting(RouteValidationIssue::getCode)
                .containsOnly("EXPERT_CHAMBER_OBLIQUE_ENTRY");
        assertThat(planner.refineCorridorVariants(List.of(seed), demands, List.of(), parameters, false, environment)).isEmpty();
        var repaired = planner.repairMandatoryChambers(List.of(seed), demands, List.of(), parameters, false, environment);
        assertThat(repaired).isNotEmpty();
        assertThat(repaired).allSatisfy(v -> assertThat(branches(v)).isEqualTo(2));
        var improved = planner.refineRepairedCorridorVariants(repaired, demands, List.of(), parameters, false, environment);
        assertThat(improved).isNotEmpty();
        assertThat(improved).allSatisfy(v -> {
            assertThat(v.getId()).startsWith("post-repair-refined-");
            assertThat(v.getConnectedDemandCount()).isEqualTo(3);
            assertThat(branches(v)).isEqualTo(1);
            assertThat(v.isValid()).isTrue();
            assertThat(v.getEngineeringIssues()).isEmpty();
            assertThat(v.getEconomics().isComplete()).isTrue();
            assertThat(new OfficialRouteValidator(rules).validate(v.getNodes(), v.getEdges(), List.of())).isEmpty();
            assertThat(new ExpertChamberRouteValidator().validate(v.getNodes(), v.getEdges(), environment::existingDirections)).isEmpty();
            assertThat(ExpertRouteBendRules.validate(v.getNodes(), v.getEdges())).isEmpty();
            if (depth) assertThat(v.getEdges()).allSatisfy(e -> {
                assertThat(e.getDepthProfile().isComplete()).isTrue();
                assertThat(e.getDepthProfile().getIssues()).isEmpty();
            });
        });
        List<RouteVariant> portfolio = new ArrayList<>(repaired);
        portfolio.addAll(improved);
        var selected = new FinishedRouteVariantSelector().select(portfolio, depth);
        assertThat(selected).allSatisfy(v -> assertThat(v.getConnectedDemandCount()).isEqualTo(3));
        // Локальное объединение здесь удлиняет сеть: исходная дешёвая сеть остаётся доступной.
        var cheapest = selected.stream().filter(v -> "cheapest".equals(v.getId())).findFirst().orElseThrow();
        assertThat(cheapest.getEconomics().getCalculatedCost()).isEqualByComparingTo(portfolio.stream()
                .map(v -> v.getEconomics().getCalculatedCost()).min(BigDecimal::compareTo).orElseThrow());
        assertThat(branches(cheapest)).isEqualTo(2);
        assertThat(seed.isValid()).isFalse();
    }

    private long branches(RouteVariant v) {
        return v.getNodes().stream().filter(n -> "new_branch_chamber".equals(n.getNodeType())).count();
    }

    private RouteNode node(String id, double x, double y, boolean chamber, boolean root) {
        return new RouteNode(id, root ? "existing_chamber_tie_in" : chamber ? "new_branch_chamber" : "demand_connection",
                new RouteCoordinate(500000 + x, 6100000 + y), chamber, root, root ? 2 : 0, root ? "support" : null);
    }

    private RouteEdge edge(RouteNode from, RouteNode to, int flow) {
        var points = List.of(from.getCoordinate(), to.getCoordinate());
        double length = from.getCoordinate().toCoordinate().distance(to.getCoordinate().toCoordinate());
        return new RouteEdge(from.getId() + ":" + to.getId(), from.getId(), to.getId(), length, points,
                List.of(new RouteSection("base", null, null, points, length, null)), BigDecimal.valueOf(flow), 150);
    }

    private RouteConnection connection(String id, int flow) {
        return new RouteConnection(id, id, BigDecimal.valueOf(flow), "connected", null);
    }
}
