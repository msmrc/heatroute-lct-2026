package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;

class FrozenNetworkEvaluatorTest {
    private final OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialRouteGeometryRules geometryRules =
            new OfficialRouteGeometryRules(constraints, new OfficialCrossingGeometry());
    private final FrozenNetworkEvaluator evaluator = new FrozenNetworkEvaluator(
            new OfficialRouteValidator(geometryRules),
            new OfficialObstacleRouter(geometryRules),
            new OfficialNetworkSizer(pipes),
            new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(constraints, pipes),
                    new OfficialDepthOptimizer(pipes, economics), new OfficialDepthProfileValidator(pipes)),
            new OfficialVariantEconomicsCalculator(pipes, economics));

    @Test
    void requestsCanonicalSizingBeforeAdmissionWithoutChangingGeometry() {
        FrozenNetworkCandidate candidate = candidate(straightEdge(null, null));

        FrozenNetworkEvaluator.Evaluation result = evaluator.evaluate(candidate);

        assertThat(result.getOutcome()).isEqualTo(FrozenNetworkEvaluator.Outcome.CANONICAL_SIZING_REQUIREMENT);
        assertThat(result.getRequiredSizing()).containsOnlyKeys("edge");
        assertThat(result.getRequiredSizing().get("edge").getFlowTph()).isEqualByComparingTo("1.000");
        assertThat(result.getRequiredSizing().get("edge").getDiameter()).isEqualTo(50);
        assertThat(candidate.getGeometryHash())
                .isEqualTo(FrozenNetworkCandidate.geometryHash(candidate.getNodes(), candidate.getEdges()));
    }

    @Test
    void acceptsOnlySizedFrozenGeometryAndOwnsItsCollections() {
        List<RouteNode> nodes = new ArrayList<>(nodes());
        List<RouteEdge> edges = new ArrayList<>(List.of(straightEdge(new BigDecimal("1.000"), 50)));
        FrozenNetworkCandidate candidate = candidate(nodes, edges);

        FrozenNetworkEvaluator.Evaluation result = evaluator.evaluate(candidate);
        nodes.clear();
        edges.clear();

        assertThat(result.getOutcome()).isEqualTo(FrozenNetworkEvaluator.Outcome.ACCEPTED);
        assertThat(result.getAccepted()).isNotNull();
        assertThat(result.getAccepted().getGeometryHash()).isEqualTo(candidate.getGeometryHash());
        assertThat(result.getAccepted().getNodes()).hasSize(2);
        assertThat(result.getAccepted().getEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.getFlowTph()).isEqualByComparingTo("1.000");
            assertThat(edge.getDiameter()).isEqualTo(50);
        });
        assertThat(result.getAccepted().toRouteVariant().isValid()).isTrue();
    }

    @Test
    void invalidFrozenBendIsRejectedInsteadOfBeingRepaired() {
        RouteEdge bent = new RouteEdge("edge", "root", "demand:one", 22.361,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(10, 5), new RouteCoordinate(20, 0)),
                List.of(), new BigDecimal("1.000"), 50);

        FrozenNetworkEvaluator.Evaluation result = evaluator.evaluate(candidate(bent));

        assertThat(result.getOutcome()).isEqualTo(FrozenNetworkEvaluator.Outcome.PROVEN_REJECTED);
        assertThat(result.getReason()).isEqualTo("engineering_rejected");
        assertThat(result.getValidationIssues()).extracting(RouteValidationIssue::getCode)
                .contains("EXPERT_ROUTE_BEND_ANGLE_INVALID");
    }

    private FrozenNetworkCandidate candidate(RouteEdge edge) {
        return candidate(nodes(), List.of(edge));
    }

    private FrozenNetworkCandidate candidate(List<RouteNode> nodes, List<RouteEdge> edges) {
        return new FrozenNetworkCandidate("candidate", "balanced", nodes, edges,
                List.of(new RouteConnection("one", "connection-one", new BigDecimal("1.000"),
                        "connected", null)),
                List.of(), OfficialRunParameters.defaults(), false);
    }

    private List<RouteNode> nodes() {
        return List.of(
                new RouteNode("root", "new_chamber", new RouteCoordinate(0, 0),
                        true, true, 0, null),
                new RouteNode("demand:one", "demand_connection", new RouteCoordinate(20, 0),
                        false, false, 0, "connection-one"));
    }

    private RouteEdge straightEdge(BigDecimal flow, Integer diameter) {
        return new RouteEdge("edge", "root", "demand:one", 20,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(20, 0)),
                List.of(), flow, diameter);
    }
}
