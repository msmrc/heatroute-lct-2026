package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
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
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialFinalFeatureWindowReuseTest {
    @Test
    void depthProfilesReuseTheAlreadyLoadedFinalGeometryWindow() {
        OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
        OfficialPipeCatalog pipes = new OfficialPipeCatalog();
        OfficialEconomics economics = new OfficialEconomics();
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                constraints, new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OfficialRoutePlanner planner = new OfficialRoutePlanner(
                new OfficialRouteValidator(rules), router, pipes, new OfficialNetworkSizer(pipes),
                new OfficialExistingNetworkReconstructor(pipes),
                new OfficialVariantEconomicsCalculator(pipes, economics),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(constraints, pipes),
                        new OfficialDepthOptimizer(pipes, economics), new OfficialDepthProfileValidator(pipes)));
        CountingSource source = new CountingSource();
        OfficialRoutingEnvironment environment = router.prepare(List.of(), source);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "new_chamber", new RouteCoordinate(0, 0), true, true, 0, null),
                new RouteNode("demand:one", "demand_connection", new RouteCoordinate(20, 0),
                        false, false, 0, "connection-one"));
        RouteEdge edge = new RouteEdge("edge", "root", "demand:one", 20,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(20, 0)), List.of(),
                new BigDecimal("1.000"), 50);
        OfficialRoutePlanner.VariantDraft draft = new OfficialRoutePlanner.VariantDraft(nodes, List.of(edge),
                List.of(new RouteConnection("one", "connection-one", new BigDecimal("1.000"),
                        "connected", null)));

        RouteVariant result = planner.finish("candidate", "balanced", draft, List.of(),
                new OfficialRunParameters(null, null, true), false, environment,
                TerminalApproachPolicy.PRESERVE_VALID);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getEdges()).allSatisfy(finalEdge -> {
            assertThat(finalEdge.getDepthProfile()).isNotNull();
            assertThat(finalEdge.getDepthProfile().isComplete()).isTrue();
        });
        // Two sizing/egress checks and two distinct final-geometry stages. A redundant fifth
        // query after profile attachment would indicate loss of the proven XY-invariance reuse.
        assertThat(source.windowCalls.get()).isEqualTo(4);
    }

    private static final class CountingSource implements RoutingFeatureSource {
        private final AtomicInteger windowCalls = new AtomicInteger();

        @Override
        public List<ImportedOfficialFeature> findInMetricWindow(Envelope window) {
            windowCalls.incrementAndGet();
            return List.of();
        }

        @Override
        public List<ImportedOfficialFeature> findByFeatureIds(Set<String> featureIds) {
            return List.of();
        }
    }
}
