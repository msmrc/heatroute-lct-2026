package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.catalog.CatalogBuildResult;
import ru.lct.heatroute.domain.catalog.CatalogMetricPoint;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.CatalogPhysicalAsset;
import ru.lct.heatroute.domain.catalog.DirectedPathOption;
import ru.lct.heatroute.domain.catalog.PathAdmissionCertificate;
import ru.lct.heatroute.domain.catalog.PhysicalAssetCompiler;
import ru.lct.heatroute.domain.catalog.RoutingCatalogSnapshot;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.optimization.ConflictStore;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class CatalogNetworkStageCompilerTest {
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final CatalogNetworkStageCompiler compiler = new CatalogNetworkStageCompiler(
            new CatalogNetworkProblemCompiler(pipes));

    @Test
    void compilesCatalogIntoAnExecutableExactSearchStage() {
        RoutingProblemSnapshot problem = problem();
        CatalogBuildResult build = build(problem, "catalog-1");
        AdaptiveCatalogNetworkSearch.Stage stage = compiler.compile(
                problem, build, Map.of("one", "d-port"), Map.of("root", "root-port"), 3,
                "frozen-evaluator-1", "network", "nextgen",
                compilation -> realizations(compilation), List.of(), edge -> List.of());
        AcceptedSolutionArchive archive = new AcceptedSolutionArchive(3);
        AdaptiveCatalogNetworkSearch search = new AdaptiveCatalogNetworkSearch(
                new CatalogFrozenNetworkRefinement(
                        new CpSatNetworkOptimizer(new CpSatRuntime()), evaluator()));

        AdaptiveCatalogNetworkSearch.Result result = search.solve(stage, new ConflictStore(),
                archive, (current, request, remainingNanos) -> {
                    throw new AssertionError("Complete one-route catalog must not expand");
                }, settings());

        assertThat(result.getOutcome()).as(result.getReason())
                .isEqualTo(AdaptiveCatalogNetworkSearch.Outcome.ACCEPTED);
        assertThat(result.getAccepted()).isSameAs(archive.best());
        assertThat(result.getAccepted().getId()).startsWith("network-");
        assertThat(result.getAccepted().getConnections()).singleElement().satisfies(connection -> {
            assertThat(connection.getDemandId()).isEqualTo("one");
            assertThat(connection.getConnectionPointId()).isEqualTo("connection-one");
        });
        assertThat(result.getAccepted().getEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.getLengthM()).isEqualByComparingTo("20.000");
            assertThat(edge.getFlowTph()).isEqualByComparingTo("1.000");
            assertThat(edge.getDiameter()).isEqualTo(50);
        });
        assertThat(stage.getIdentity().getCatalogHash()).isEqualTo(
                build.getSnapshot().getCatalogHash());
    }

    @Test
    void executesARealBoundedRouterCatalogThroughTheExactStage() throws Exception {
        RoutingProblemSnapshot problem = problem();
        OfficialRouteGeometryRules geometryRules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        ImportedOfficialFeature road = new ImportedOfficialFeature(
                "road-1", "restriction",
                new ObjectMapper().readTree("{\"restriction_type\":\"road\"}"),
                new WKTReader().read(
                        "POLYGON ((8 -10, 12 -10, 12 10, 8 10, 8 -10))"));
        List<ImportedOfficialFeature> features = List.of(road);
        CountingObstacleRouter router = new CountingObstacleRouter(geometryRules);
        BoundedCatalogNetworkStageFactory.Preparation prepared =
                new BoundedCatalogNetworkStageFactory(
                        new RoutingFeatureWindowFactory(router),
                        new BoundedRootDemandCatalogGenerator(router, pipes),
                        new CatalogProblemNodeRealizationResolver(),
                        new CatalogEdgeSectionAssemblerFactory(router), compiler).prepare(
                        problem, features,
                        BoundedRootDemandCatalogGenerator.Options.bounded(
                                Duration.ofSeconds(10), 8, 32, 4, 4),
                        3, "frozen-evaluator-1", "generated-network", "nextgen-bounded");
        AdaptiveCatalogNetworkSearch.Stage stage = prepared.getStage().orElseThrow();
        CatalogEdgeSectionAssemblerFactory.PreparedAssembler sectionAssembler =
                prepared.getSectionAssembler();
        AdaptiveCatalogNetworkSearch search = new AdaptiveCatalogNetworkSearch(
                new CatalogFrozenNetworkRefinement(
                        new CpSatNetworkOptimizer(new CpSatRuntime()), evaluator()));

        AdaptiveCatalogNetworkSearch.Result result = search.solve(stage, new ConflictStore(),
                new AcceptedSolutionArchive(3), (current, request, remainingNanos) -> {
                    throw new AssertionError("A valid bounded seed must be admitted before expansion");
                }, settings());

        assertThat(result.getOutcome()).as(result.getReason())
                .isEqualTo(AdaptiveCatalogNetworkSearch.Outcome.ACCEPTED);
        assertThat(result.getAccepted().getStrategy()).isEqualTo("nextgen-bounded");
        assertThat(router.getPrepareCalls()).isEqualTo(1);
        assertThat(prepared.getFeatureWindow().size()).isEqualTo(1);
        assertThat(sectionAssembler.getAssemblyCalls()).isEqualTo(1);
        assertThat(sectionAssembler.getFallbackAssemblies()).isZero();
        assertThat(result.getAccepted().getEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.getLengthM()).isEqualByComparingTo("20.000");
            assertThat(edge.getDiameter()).isEqualTo(50);
            assertThat(edge.getSections()).anySatisfy(section -> {
                assertThat(section.getKind()).isEqualTo("special");
                assertThat(section.getRestrictionType()).isEqualTo("road");
                assertThat(section.getRestrictionId()).isEqualTo("road-1");
            });
        });
    }

    @Test
    void rejectsCatalogBuildFromAnotherProblemSnapshot() {
        RoutingProblemSnapshot problem = problem();
        RoutingCatalogSnapshot wrong = new RoutingCatalogSnapshot(
                "another-snapshot", problem.getRuleId(), problem.getRuleVersion(),
                "catalog-wrong", List.of(), List.of());
        CatalogBuildResult build = new CatalogBuildResult(
                wrong, Map.of(), Map.of("paths", true), List.of(), List.of());

        assertThatThrownBy(() -> compiler.compile(problem, build,
                Map.of("one", "d-port"), Map.of("root", "root-port"), 3,
                "frozen-evaluator-1", "network", "nextgen",
                compilation -> Map.of(), List.of(), edge -> List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another problem/rule scope");
    }

    @Test
    void refusesToInventMissingRootNodeSemantics() {
        RoutingProblemSnapshot unresolved = new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "source-2", "extended", "nextgen-1", "official", "rules-1",
                "cost-1", "feature-source-1", OfficialRunParameters.defaults(),
                List.of(new RoutingProblemSnapshot.Demand(
                        "one", BigDecimal.ONE, new CatalogMetricPoint(20_000, 0), "connection-one")),
                List.of(new RoutingProblemSnapshot.RootCandidate(
                        "root", new CatalogMetricPoint(0, 0), List.of())));
        OfficialRouteGeometryRules geometryRules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        BoundedRootDemandCatalogGenerator.GeneratedCatalog generated =
                new BoundedRootDemandCatalogGenerator(
                        new OfficialObstacleRouter(geometryRules), pipes).generate(
                        unresolved, List.of(),
                        BoundedRootDemandCatalogGenerator.Options.bounded(
                                Duration.ofSeconds(10), 8, 32, 4, 4));
        CatalogNetworkProblemCompiler.Compilation compilation =
                new CatalogNetworkProblemCompiler(pipes).compile(unresolved,
                        generated.getBuildResult().getSnapshot(),
                        generated.getDemandPortById(), generated.getRootPortById(), 3);

        assertThatThrownBy(() -> new CatalogProblemNodeRealizationResolver().resolve(
                unresolved, compilation, generated.getDemandPortById(),
                generated.getRootPortById()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lacks exact node realization");
    }

    @Test
    void keepsAnEmptyBoundedCatalogAsIncompletePreparationWithoutBuildingAMaster() {
        RoutingProblemSnapshot coincident = new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "source-3", "extended", "nextgen-1", "official", "rules-1",
                "cost-1", "feature-source-1", OfficialRunParameters.defaults(),
                List.of(new RoutingProblemSnapshot.Demand(
                        "one", BigDecimal.ONE, new CatalogMetricPoint(0, 0), null)),
                List.of(new RoutingProblemSnapshot.RootCandidate(
                        "root", new CatalogMetricPoint(0, 0), List.of(),
                        new RoutingProblemSnapshot.RootRealization(
                                "existing_root", true, 0, "root", null))));
        OfficialRouteGeometryRules geometryRules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(geometryRules);
        BoundedCatalogNetworkStageFactory.Preparation preparation =
                new BoundedCatalogNetworkStageFactory(
                        new RoutingFeatureWindowFactory(router),
                        new BoundedRootDemandCatalogGenerator(router, pipes),
                        new CatalogProblemNodeRealizationResolver(),
                        new CatalogEdgeSectionAssemblerFactory(router), compiler).prepare(
                        coincident, List.of(),
                        BoundedRootDemandCatalogGenerator.Options.bounded(
                                Duration.ofSeconds(10), 8, 32, 4, 4),
                        3, "frozen-evaluator-1", "network", "nextgen-bounded");

        assertThat(preparation.getStage()).isEmpty();
        assertThat(preparation.getGeneratedCatalog().getBuildResult().isComplete()).isFalse();
        assertThat(preparation.getGeneratedCatalog().getBuildResult().getRemainingWork())
                .contains("unrouted-pair:root:one");
    }

    private CatalogBuildResult build(RoutingProblemSnapshot problem, String version) {
        List<CatalogMetricPoint> points = List.of(
                new CatalogMetricPoint(0, 0), new CatalogMetricPoint(20_000, 0));
        PhysicalAssetCompiler.Result physical = new PhysicalAssetCompiler().compile(List.of(
                new PhysicalAssetCompiler.CandidatePath("path", "surface",
                        CatalogPhysicalAsset.ConstructionMode.NEW_CONSTRUCTION,
                        "chain:path", points)));
        DirectedPathOption option = new DirectedPathOption(
                "path", "root-port", "d-port", PathAdmissionCertificate.Direction.FORWARD,
                "normal", points, physical.path("path").getPhysicalAssetIds(),
                List.of(), 0L, 0L, new DirectedPathOption.Provenance(
                        "bounded-router", "router-1", problem.getSnapshotHash(), "window:one"),
                List.of());
        RoutingCatalogSnapshot catalog = new RoutingCatalogSnapshot(
                problem.getSnapshotHash(), problem.getRuleId(), problem.getRuleVersion(), version,
                physical.getPhysicalAssets(), List.of(option));
        return new CatalogBuildResult(catalog,
                Map.of("paths", 1L, "physical_assets", (long) physical.getPhysicalAssets().size()),
                Map.of("bounded_router", true), List.of(), List.of());
    }

    private Map<String, CatalogFrozenCandidateAssembler.NodeRealization> realizations(
            CatalogNetworkProblemCompiler.Compilation compilation) {
        Map<String, CatalogFrozenCandidateAssembler.NodeRealization> result = new LinkedHashMap<>();
        for (CatalogNetworkProblemCompiler.NodeBinding node : compilation.getNodeBindings()) {
            if ("root-port".equals(node.getExplicitPortId())
                    || "root-port:root".equals(node.getExplicitPortId())) {
                result.put(node.getNodeId(), new CatalogFrozenCandidateAssembler.NodeRealization(
                        "existing_root", true, 0, "root", null));
            } else if ("d-port".equals(node.getExplicitPortId())
                    || "demand-port:one".equals(node.getExplicitPortId())) {
                result.put(node.getNodeId(), new CatalogFrozenCandidateAssembler.NodeRealization(
                        "demand_connection", false, 0, "connection-one", null));
            }
        }
        return result;
    }

    private RoutingProblemSnapshot problem() {
        return new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "source-1", "extended", "nextgen-1", "official", "rules-1",
                "cost-1", "feature-source-1", OfficialRunParameters.defaults(),
                List.of(new RoutingProblemSnapshot.Demand(
                        "one", BigDecimal.ONE, new CatalogMetricPoint(20_000, 0), "connection-one")),
                List.of(new RoutingProblemSnapshot.RootCandidate(
                        "root", new CatalogMetricPoint(0, 0), List.of(),
                        new RoutingProblemSnapshot.RootRealization(
                                "existing_root", true, 0, "root", null))));
    }

    private FrozenNetworkEvaluator evaluator() {
        OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
        OfficialEconomics officialEconomics = new OfficialEconomics();
        OfficialRouteGeometryRules geometryRules =
                new OfficialRouteGeometryRules(constraints, new OfficialCrossingGeometry());
        return new FrozenNetworkEvaluator(
                new OfficialRouteValidator(geometryRules),
                new OfficialObstacleRouter(geometryRules),
                new OfficialNetworkSizer(pipes),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(constraints, pipes),
                        new OfficialDepthOptimizer(pipes, officialEconomics),
                        new OfficialDepthProfileValidator(pipes)),
                new OfficialVariantEconomicsCalculator(pipes, officialEconomics));
    }

    private AdaptiveCatalogNetworkSearch.Settings settings() {
        return AdaptiveCatalogNetworkSearch.Settings.bounded(
                30, TimeUnit.SECONDS, 1, TimeUnit.SECONDS,
                15, TimeUnit.SECONDS, 1, TimeUnit.SECONDS,
                0, 5, 2026);
    }

    private static final class CountingObstacleRouter extends OfficialObstacleRouter {
        private int prepareCalls;

        private CountingObstacleRouter(OfficialRouteGeometryRules rules) { super(rules); }

        @Override
        OfficialRoutingEnvironment prepare(List<ImportedOfficialFeature> features) {
            prepareCalls++;
            return super.prepare(features);
        }

        private int getPrepareCalls() { return prepareCalls; }
    }
}
