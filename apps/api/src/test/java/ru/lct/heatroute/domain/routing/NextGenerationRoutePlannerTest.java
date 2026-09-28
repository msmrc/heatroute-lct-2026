package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.RoutingProblemFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

class NextGenerationRoutePlannerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WKTReader wkt = new WKTReader();

    @Test
    void runsProductionInputThroughBoundedCatalogAndExactEvaluator() throws Exception {
        List<ImportedOfficialFeature> core = List.of(
                feature("network", "heat_network", "LINESTRING (-20 0, 20 0)",
                        attributes().put("diameter", 150)),
                feature("connection", "oks_connection_point", "POINT (0 20)",
                        attributes().put("flow_tph", 1)));
        TopologyAnalysis topology = new TopologyAnalysis(1, 1, 0, List.of(), List.of(
                new TieInCandidate("connection", "network", "heat_network",
                        20.0, true, 0.0, 0.0)));
        AtomicReference<Envelope> queried = new AtomicReference<>();
        RoutingFeatureSource source = new RoutingFeatureSource() {
            @Override public List<ImportedOfficialFeature> findInMetricWindow(Envelope window) {
                queried.set(new Envelope(window));
                return List.of();
            }

            @Override public List<ImportedOfficialFeature> findByFeatureIds(Set<String> ids) {
                return List.of();
            }
        };

        NextGenerationRoutePlanner.Execution execution = planner().execute(
                context(), core, topology, OfficialRunParameters.defaults(), source, settings());

        assertThat(execution.getOutcome()).as(execution.getReason())
                .isEqualTo(AdaptiveCatalogNetworkSearch.Outcome.ACCEPTED);
        assertThat(execution.getFeatureCount()).isEqualTo(2);
        assertThat(execution.getCatalogBuild()).isNotNull();
        assertThat(execution.getCatalogBuild().isComplete()).isFalse();
        assertThat(execution.getRefinementRuns()).isEqualTo(1);
        assertThat(execution.getArchiveSize()).isEqualTo(1);
        assertThat(execution.getResult()).isNotNull();
        assertThat(execution.getResult().getAlgorithmVersion())
                .isEqualTo(NextGenerationRoutePlanner.VERSION);
        assertThat(execution.getResult().getDemandCount()).isEqualTo(1);
        assertThat(execution.getResult().getPreferredVariantId()).isEqualTo("balanced");
        assertThat(execution.getResult().getVariants()).singleElement().satisfies(variant -> {
            assertThat(variant.getId()).isEqualTo("balanced");
            assertThat(variant.getRank()).isEqualTo(1);
            assertThat(variant.getConnectedDemandCount()).isEqualTo(1);
            assertThat(variant.isValid()).isTrue();
            assertThat(variant.getEdges()).singleElement().satisfies(edge -> {
                assertThat(edge.getFlowTph()).isEqualByComparingTo("1.000");
                assertThat(edge.getDiameter()).isEqualTo(50);
            });
        });
        assertThat(queried.get().getMinX()).isEqualTo(-610.0);
        assertThat(queried.get().getMaxX()).isEqualTo(610.0);
        assertThat(queried.get().getMinY()).isEqualTo(-610.0);
        assertThat(queried.get().getMaxY()).isEqualTo(630.0);
    }

    @Test
    void reportsUnsatisfiedSeedCatalogAsIncompleteWithoutInventingNoRoute() throws Exception {
        List<ImportedOfficialFeature> core = List.of(
                feature("network", "heat_network", "LINESTRING (-20 0, 20 0)",
                        attributes().put("diameter", 150)),
                feature("connection", "oks_connection_point", "POINT (0 0)",
                        attributes().put("flow_tph", 1)));
        TopologyAnalysis topology = new TopologyAnalysis(1, 1, 0, List.of(), List.of(
                new TieInCandidate("connection", "network", "heat_network",
                        0.0, true, 0.0, 0.0)));
        RoutingFeatureSource source = new RoutingFeatureSource() {
            @Override public List<ImportedOfficialFeature> findInMetricWindow(Envelope window) {
                return List.of();
            }

            @Override public List<ImportedOfficialFeature> findByFeatureIds(Set<String> ids) {
                return List.of();
            }
        };

        NextGenerationRoutePlanner.Execution execution = planner().execute(
                context(), core, topology, OfficialRunParameters.defaults(), source, settings());

        assertThat(execution.getOutcome())
                .isEqualTo(AdaptiveCatalogNetworkSearch.Outcome.CATALOG_INCOMPLETE);
        assertThat(execution.getReason()).isEqualTo("catalog_expansion_limit");
        assertThat(execution.getResult()).isNull();
        assertThat(execution.getCatalogBuild().isComplete()).isFalse();
    }

    private NextGenerationRoutePlanner planner() {
        OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
        OfficialPipeCatalog pipes = new OfficialPipeCatalog();
        OfficialEconomics economics = new OfficialEconomics();
        OfficialRouteGeometryRules geometryRules = new OfficialRouteGeometryRules(
                constraints, new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(geometryRules);
        FrozenNetworkEvaluator evaluator = new FrozenNetworkEvaluator(
                new OfficialRouteValidator(geometryRules), router,
                new ru.lct.heatroute.domain.sizing.OfficialNetworkSizer(pipes),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(constraints, pipes),
                        new OfficialDepthOptimizer(pipes, economics),
                        new OfficialDepthProfileValidator(pipes)),
                new OfficialVariantEconomicsCalculator(pipes, economics));
        CatalogNetworkStageCompiler stageCompiler = new CatalogNetworkStageCompiler(
                new CatalogNetworkProblemCompiler(pipes));
        BoundedCatalogNetworkStageFactory stageFactory =
                new BoundedCatalogNetworkStageFactory(
                        new RoutingFeatureWindowFactory(router),
                        new BoundedRootDemandCatalogGenerator(router, pipes),
                        new CatalogProblemNodeRealizationResolver(),
                        new CatalogEdgeSectionAssemblerFactory(router), stageCompiler);
        return new NextGenerationRoutePlanner(
                new RoutingProblemFactory(), new RoutingFeatureWindowLoader(), stageFactory,
                new CpSatRuntime(), evaluator);
    }

    private NextGenerationRoutePlanner.Settings settings() {
        return NextGenerationRoutePlanner.Settings.bounded(
                Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(15), Duration.ofSeconds(1),
                8, 32, 4, 4, 5, 2026, 100, 3);
    }

    private RoutingExecutionContext context() {
        return new RoutingExecutionContext(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                "source-sha", "heatroute-input-v2", "extended");
    }

    private ImportedOfficialFeature feature(
            String id, String type, String geometry, ObjectNode attributes) throws Exception {
        return new ImportedOfficialFeature(id, type, attributes, wkt.read(geometry));
    }

    private ObjectNode attributes() {
        return mapper.createObjectNode();
    }
}
