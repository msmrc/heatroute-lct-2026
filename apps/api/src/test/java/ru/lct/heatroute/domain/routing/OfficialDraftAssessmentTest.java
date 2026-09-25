package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.NetworkSizingResult;
import ru.lct.heatroute.domain.sizing.NetworkTreeEdge;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;

/** Реальные sizing/finish и счётчик повторной работы, без подмены предметных результатов. */
class OfficialDraftAssessmentTest {
    @Test
    void corridorRefinementDoesNotRunSizingInsideComparatorsInTwoDimensionalMode() {
        verifyRefinement(false);
    }

    @Test
    void corridorRefinementDoesNotRunSizingInsideComparatorsWithCompleteDepth() {
        verifyRefinement(true);
    }

    private void verifyRefinement(boolean depth) {
        OfficialPipeCatalog catalog = new OfficialPipeCatalog();
        CountingSizer sizer = new CountingSizer(catalog);
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OfficialRoutePlanner planner = new OfficialRoutePlanner(new OfficialRouteValidator(rules), router, catalog, sizer,
                new OfficialExistingNetworkReconstructor(catalog), new OfficialVariantEconomicsCalculator(catalog, new OfficialEconomics()),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), catalog),
                        new OfficialDepthOptimizer(catalog, new OfficialEconomics()), new OfficialDepthProfileValidator(catalog)));
        List<RouteNode> nodes = List.of(node("root", -100, 0, true, true), node("a", 0, 0, true, false),
                node("b", 5, 0, true, false), node("demand:east", 105, 0, false, false),
                node("demand:north", 0, 100, false, false), node("demand:south", 5, -100, false, false));
        List<RouteEdge> edges = List.of(edge(nodes.get(2), nodes.get(3), 1), edge(nodes.get(1), nodes.get(4), 2),
                edge(nodes.get(2), nodes.get(5), 3), edge(nodes.get(0), nodes.get(1), 6), edge(nodes.get(1), nodes.get(2), 4));
        List<RouteConnection> connections = List.of(connection("east", 1), connection("north", 2), connection("south", 3));
        Map<String, RouteNode> byId = nodes.stream().collect(Collectors.toMap(RouteNode::getId, n -> n));
        List<OfficialRoutePlanner.Demand> demands = connections.stream().map(c -> new OfficialRoutePlanner.Demand(
                c.getDemandId(), c.getConnectionPointId(), byId.get("demand:" + c.getDemandId()).getCoordinate().toCoordinate(),
                c.getFlowTph(), null)).collect(Collectors.toList());
        OfficialRunParameters parameters = new OfficialRunParameters(null, null, depth).validated();
        OfficialRoutingEnvironment environment = router.prepare(List.of());
        RouteVariant source = planner.withEngineeringAssessment(planner.finish("seed", "engineering",
                new OfficialRoutePlanner.VariantDraft(nodes, edges, connections), List.of(), parameters, false, environment));
        assertThat(source.isValid()).isTrue();
        assertThat(source.getEngineeringIssues()).extracting(RouteValidationIssue::getCode)
                .containsExactly("EXPERT_CHAMBER_SPACING_TOO_SHORT");
        sizer.reset();
        List<RouteVariant> result = planner.refineCorridorVariants(List.of(source), demands, List.of(), parameters, false, environment);
        assertThat(result).isNotEmpty();
        assertThat(sizer.draftCalls).isGreaterThan(1);
        assertThat(sizer.comparatorCalls).as("Sizing must not be repeated by the draft sorter").isZero();
        assertThat(result).allSatisfy(v -> {
            assertThat(v.isValid()).isTrue();
            assertThat(v.getConnectedDemandCount()).isEqualTo(3);
            assertThat(v.getNodes().stream().filter(n -> "new_branch_chamber".equals(n.getNodeType())).count()).isEqualTo(1);
            assertThat(v.getEconomics().isComplete()).isTrue();
            assertThat(v.getSizingIssues()).isEmpty();
            assertThat(v.getEngineeringIssues()).isEmpty();
            assertThat(new ExpertChamberRouteValidator().validate(v.getNodes(), v.getEdges())).isEmpty();
            assertThat(new OfficialRouteValidator(rules).validate(v.getNodes(), v.getEdges(), List.of())).isEmpty();
            if (depth) assertThat(v.getEdges()).allSatisfy(e -> {
                assertThat(e.getDepthProfile()).isNotNull();
                assertThat(e.getDepthProfile().isComplete()).isTrue();
                assertThat(e.getDepthProfile().getIssues()).isEmpty();
            });
        });
        System.out.println("Draft assessment depth=" + depth + " calls=" + sizer.draftCalls
                + " comparator_calls=" + sizer.comparatorCalls + " completed=" + result.size());
    }

    private RouteNode node(String id, double x, double y, boolean chamber, boolean root) {
        return new RouteNode(id, root ? "existing_chamber_tie_in" : chamber ? "new_branch_chamber" : "demand_connection",
                new RouteCoordinate(500000 + x, 6100000 + y), chamber, root, root ? 2 : 0, root ? "support" : null);
    }

    private RouteEdge edge(RouteNode from, RouteNode to, int flow) {
        List<RouteCoordinate> geometry = List.of(from.getCoordinate(), to.getCoordinate());
        double length = from.getCoordinate().toCoordinate().distance(to.getCoordinate().toCoordinate());
        return new RouteEdge(from.getId() + ":" + to.getId(), from.getId(), to.getId(), length, geometry,
                List.of(new RouteSection("base", null, null, geometry, length, null)), BigDecimal.valueOf(flow), 150);
    }

    private RouteConnection connection(String id, int flow) {
        return new RouteConnection(id, id, BigDecimal.valueOf(flow), "connected", null);
    }

    private static final class CountingSizer extends OfficialNetworkSizer {
        private int draftCalls;
        private int comparatorCalls;
        private CountingSizer(OfficialPipeCatalog catalog) { super(catalog); }
        private void reset() { draftCalls = 0; comparatorCalls = 0; }
        @Override
        public NetworkSizingResult size(List<NetworkTreeEdge> edges, Map<String, BigDecimal> flow) {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            if (Arrays.stream(stack).anyMatch(f -> f.getClassName().equals(OfficialRoutePlanner.class.getName())
                    && f.getMethodName().equals("draftScore"))) {
                draftCalls++;
                if (Arrays.stream(stack).anyMatch(f -> f.getClassName().contains("TimSort")
                        || f.getClassName().contains("SortedOps"))) comparatorCalls++;
            }
            return super.size(edges, flow);
        }
    }
}
