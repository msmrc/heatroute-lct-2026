package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.io.WKTReader;
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
    void disabledDepthSkipsOnlyTheUnusedInitialFeatureWindow() {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        CountingSource source = new CountingSource();

        RouteVariant result = finishWithoutDepth(rules, new OfficialRouteValidator(rules), source);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getTotalLengthM()).isEqualByComparingTo("20.000");
        assertThat(result.getEdges()).hasSize(1).allSatisfy(edge -> assertThat(edge.getDepthProfile()).isNull());
        // Two preserved-egress validations and the independent final validation remain.
        assertThat(source.windowCalls.get()).isEqualTo(3);
    }

    @Test
    void disabledDepthStillLoadsSourceOnlyRestrictionsForFinalValidation() throws Exception {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        ImportedOfficialFeature park = new ImportedOfficialFeature("window-park", "restriction",
                JsonNodeFactory.instance.objectNode().put("restriction_type", "park"),
                new WKTReader().read("POLYGON ((8 5, 12 5, 12 9, 8 9, 8 5))"));
        CountingSource source = new CountingSource(List.of(park));
        RecordingValidator validator = new RecordingValidator(rules);

        RouteVariant result = finishWithoutDepth(rules, validator, source);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getTotalLengthM()).isEqualByComparingTo("20.000");
        assertThat(validator.lastFeatureIds).contains("window-park");
        assertThat(source.windowCalls.get()).isEqualTo(3);
    }

    @Test
    void depthProfilesReuseTheAlreadyLoadedFinalGeometryWindow() {
        OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
        OfficialPipeCatalog pipes = new OfficialPipeCatalog();
        OfficialEconomics economics = new OfficialEconomics();
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                constraints, new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        RegressionRoutePlannerFixture planner = new RegressionRoutePlannerFixture(
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
        RegressionRoutePlannerFixture.VariantDraft draft = new RegressionRoutePlannerFixture.VariantDraft(nodes, List.of(edge),
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

    private RouteVariant finishWithoutDepth(OfficialRouteGeometryRules rules,
            OfficialRouteValidator validator, CountingSource source) {
        OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
        OfficialPipeCatalog pipes = new OfficialPipeCatalog();
        OfficialEconomics economics = new OfficialEconomics();
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        RegressionRoutePlannerFixture planner = new RegressionRoutePlannerFixture(
                validator, router, pipes, new OfficialNetworkSizer(pipes),
                new OfficialExistingNetworkReconstructor(pipes),
                new OfficialVariantEconomicsCalculator(pipes, economics),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(constraints, pipes),
                        new OfficialDepthOptimizer(pipes, economics), new OfficialDepthProfileValidator(pipes)));
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "new_chamber", new RouteCoordinate(0, 0), true, true, 0, null),
                new RouteNode("demand:one", "demand_connection", new RouteCoordinate(20, 0),
                        false, false, 0, "connection-one"));
        RouteEdge edge = new RouteEdge("edge", "root", "demand:one", 20,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(20, 0)), List.of(),
                new BigDecimal("1.000"), 50);
        RegressionRoutePlannerFixture.VariantDraft draft = new RegressionRoutePlannerFixture.VariantDraft(nodes, List.of(edge),
                List.of(new RouteConnection("one", "connection-one", new BigDecimal("1.000"), "connected", null)));
        return planner.finish("candidate", "balanced", draft, List.of(),
                new OfficialRunParameters(null, null, false), false, router.prepare(List.of(), source),
                TerminalApproachPolicy.PRESERVE_VALID);
    }

    private static final class RecordingValidator extends OfficialRouteValidator {
        private Set<String> lastFeatureIds = Set.of();

        private RecordingValidator(OfficialRouteGeometryRules rules) {
            super(rules);
        }

        @Override
        public List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges,
                List<ImportedOfficialFeature> features) {
            lastFeatureIds = features.stream().map(ImportedOfficialFeature::getFeatureId).collect(Collectors.toSet());
            return super.validate(nodes, edges, features);
        }
    }

    private static final class CountingSource implements RoutingFeatureSource {
        private final AtomicInteger windowCalls = new AtomicInteger();
        private final List<ImportedOfficialFeature> windowFeatures;

        private CountingSource() {
            this(List.of());
        }

        private CountingSource(List<ImportedOfficialFeature> windowFeatures) {
            this.windowFeatures = windowFeatures;
        }

        @Override
        public List<ImportedOfficialFeature> findInMetricWindow(Envelope window) {
            windowCalls.incrementAndGet();
            return windowFeatures;
        }

        @Override
        public List<ImportedOfficialFeature> findByFeatureIds(Set<String> featureIds) {
            return List.of();
        }
    }
}
