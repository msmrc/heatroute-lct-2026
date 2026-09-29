package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.RoutingProblemFactory;
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
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
import ru.lct.heatroute.domain.topology.ExistingNetworkTopologyAnalyzer;
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
        assertThat(execution.getReason()).startsWith("catalog_expansion_limit:");
        assertThat(execution.getResult()).isNull();
        assertThat(execution.getCatalogBuild().isComplete()).isFalse();
    }

    @Test
    void officialDatasetProducesAnExactlyAcceptedNextGenerationResult() throws Exception {
        List<ImportedOfficialFeature> features = new OfficialDatasetRoutingTest()
                .loadOfficialFeatures();
        TopologyAnalysis topology = new ExistingNetworkTopologyAnalyzer().analyze(features);
        RoutingExecutionContext context = new RoutingExecutionContext(
                UUID.fromString("00000000-0000-0000-0000-000000000103"),
                "cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130",
                "heatroute-input-v2", OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE);

        NextGenerationRoutePlanner.Execution execution = planner().execute(
                context, features, topology,
                new OfficialRunParameters(null, null, true),
                new InMemoryRoutingFeatureSource(features),
                NextGenerationRoutePlanner.Settings.production());

        assertThat(execution.getOutcome()).as(execution.getReason() + "\n"
                        + "elapsed_ms=" + execution.getElapsedMillis()
                        + " refinement_runs=" + execution.getRefinementRuns()
                        + " conflicts=" + execution.getConflictCount() + "\n"
                        + execution.getCatalogBuild().getCounters() + "\n"
                        + rootDemandCoverage(execution) + "\n"
                        + modelDiagnostics(context, features, topology, execution) + "\n"
                        + execution.getCatalogBuild().getRemainingWork() + "\n"
                        + execution.getCatalogBuild().getTruncationReasons())
                .isEqualTo(AdaptiveCatalogNetworkSearch.Outcome.ACCEPTED);
        assertThat(execution.getResult()).isNotNull();
        assertThat(execution.getResult().getAlgorithmVersion())
                .isEqualTo(NextGenerationRoutePlanner.VERSION);
        assertThat(execution.getResult().getDemandCount()).isEqualTo(17);
        assertThat(execution.getResult().getVariants()).isNotEmpty().allMatch(RouteVariant::isValid);
        assertThat(execution.getResult().getVariants()).allSatisfy(variant ->
                assertThat(variant.getConnectedDemandCount()).isEqualTo(17));
    }

    private static Map<String, List<String>> rootDemandCoverage(
            NextGenerationRoutePlanner.Execution execution) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        execution.getCatalogBuild().getSnapshot().getPathOptions().forEach(option ->
                result.computeIfAbsent(option.getFromPortId(), ignored -> new java.util.ArrayList<>())
                        .add(option.getToPortId()));
        return result;
    }

    private static String modelDiagnostics(RoutingExecutionContext context,
            List<ImportedOfficialFeature> features, TopologyAnalysis topology,
            NextGenerationRoutePlanner.Execution execution) {
        RoutingProblemSnapshot snapshot = new RoutingProblemFactory().create(
                context, new OfficialRunParameters(null, null, true), features, topology);
        Set<String> availablePorts = execution.getCatalogBuild().getSnapshot().getPathOptions()
                .stream().flatMap(option -> java.util.stream.Stream.of(
                        option.getFromPortId(), option.getToPortId()))
                .collect(Collectors.toSet());
        Map<String, String> demandPorts = snapshot.getDemands().stream().collect(
                Collectors.toMap(demand -> demand.getId(),
                        demand -> "demand-port:" + demand.getId()));
        Map<String, String> rootPorts = snapshot.getRoots().stream()
                .filter(root -> availablePorts.contains("root-port:" + root.getId()))
                .collect(Collectors.toMap(root -> root.getId(),
                        root -> "root-port:" + root.getId()));
        CatalogNetworkProblemCompiler.Compilation base =
                new CatalogNetworkProblemCompiler(new OfficialPipeCatalog()).compile(
                        snapshot, execution.getCatalogBuild().getSnapshot(),
                        demandPorts, rootPorts, 3);
        CpSatNetworkOptimizer.Status baseStatus = new CpSatNetworkOptimizer(new CpSatRuntime())
                .solve(base.getProblem(), 2.0, 2026).getStatus();
        NetworkConstraintProblem problem = new CatalogNodeConfigurationCompiler()
                .compile(snapshot, execution.getCatalogBuild().getSnapshot(), base).getProblem();
        Set<String> rootNodeIds = Set.copyOf(base.getRootNodeById().values());
        Set<String> demandNodeIds = base.getDemandBindings().stream()
                .map(CatalogNetworkProblemCompiler.DemandBinding::getNodeId)
                .collect(Collectors.toSet());
        CpSatNetworkOptimizer optimizer = new CpSatNetworkOptimizer(new CpSatRuntime());
        CpSatNetworkOptimizer.Status rootStatus = optimizer.solve(
                configuredSubset(base.getProblem(), problem, rootNodeIds), 2.0, 2026)
                .getStatus();
        CpSatNetworkOptimizer.Status internalStatus = optimizer.solve(
                configuredSubset(base.getProblem(), problem, problem.getNodes().stream()
                        .map(NetworkConstraintProblem.Node::getId)
                        .filter(id -> !rootNodeIds.contains(id) && !demandNodeIds.contains(id))
                        .collect(Collectors.toSet())), 2.0, 2026).getStatus();
        Set<String> internalNodeIds = problem.getNodes().stream()
                .map(NetworkConstraintProblem.Node::getId)
                .filter(id -> !rootNodeIds.contains(id) && !demandNodeIds.contains(id))
                .collect(Collectors.toSet());
        Set<String> rootAndInternal = new java.util.HashSet<>(rootNodeIds);
        rootAndInternal.addAll(internalNodeIds);
        Set<String> rootAndDemand = new java.util.HashSet<>(rootNodeIds);
        rootAndDemand.addAll(demandNodeIds);
        CpSatNetworkOptimizer.Status rootInternalStatus = optimizer.solve(
                configuredSubset(base.getProblem(), problem, rootAndInternal),
                2.0, 2026).getStatus();
        CpSatNetworkOptimizer.Status rootDemandStatus = optimizer.solve(
                configuredSubset(base.getProblem(), problem, rootAndDemand),
                2.0, 2026).getStatus();
        Map<String, Long> configurationsByNode = problem.getNodeConfigurations().stream()
                .collect(Collectors.groupingBy(
                        NetworkConstraintProblem.NodeConfiguration::getNodeId,
                        java.util.TreeMap::new, Collectors.counting()));
        List<String> emptyManagedNodes = problem.getNodes().stream()
                .filter(NetworkConstraintProblem.Node::isConfigurationRequired)
                .filter(node -> !configurationsByNode.containsKey(node.getId()))
                .map(NetworkConstraintProblem.Node::getId).collect(Collectors.toList());
        String groupTopology = groupTopologyDiagnostics(
                execution, base, problem, rootNodeIds, demandNodeIds);
        return "model={nodes=" + problem.getNodes().size()
                + ", assets=" + problem.getAssets().size()
                + ", configurations=" + problem.getNodeConfigurations().size()
                + ", roots=" + rootPorts.size()
                + ", base_status=" + baseStatus
                + ", root_config_status=" + rootStatus
                + ", internal_config_status=" + internalStatus
                + ", root_internal_status=" + rootInternalStatus
                + ", root_demand_status=" + rootDemandStatus
                + ", empty_managed_nodes=" + emptyManagedNodes + "}"
                + groupTopology;
    }

    private static String groupTopologyDiagnostics(
            NextGenerationRoutePlanner.Execution execution,
            CatalogNetworkProblemCompiler.Compilation base,
            NetworkConstraintProblem configured,
            Set<String> rootNodeIds, Set<String> demandNodeIds) {
        Map<String, String> groupByOption = new LinkedHashMap<>();
        Map<String, List<ru.lct.heatroute.domain.catalog.DirectedPathOption>> optionsByGroup =
                new LinkedHashMap<>();
        execution.getCatalogBuild().getSnapshot().getPathOptions().forEach(option -> {
            String context = option.getEndpointContext();
            int separator = context.indexOf(';');
            String group = context.startsWith("network=")
                    ? context.substring("network=".length(),
                            separator < 0 ? context.length() : separator)
                    : context;
            groupByOption.put(option.getId(), group);
            optionsByGroup.computeIfAbsent(group, ignored -> new java.util.ArrayList<>())
                    .add(option);
        });
        List<String> diagnostics = new java.util.ArrayList<>();
        for (Map.Entry<String, List<ru.lct.heatroute.domain.catalog.DirectedPathOption>> entry
                : optionsByGroup.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            String group = entry.getKey();
            Set<String> assets = base.getArcBindings().stream()
                    .filter(binding -> binding.getSourceOptionIds().stream()
                            .anyMatch(optionId -> group.equals(groupByOption.get(optionId))))
                    .map(CatalogNetworkProblemCompiler.ArcBinding::getArcId)
                    .collect(Collectors.toSet());
            Map<String, Set<String>> incidence = new LinkedHashMap<>();
            configured.getAssets().stream().filter(asset -> assets.contains(asset.getId()))
                    .forEach(asset -> {
                        incidence.computeIfAbsent(asset.getFromNodeId(), ignored ->
                                new java.util.LinkedHashSet<>()).add(asset.getId());
                        incidence.computeIfAbsent(asset.getToNodeId(), ignored ->
                                new java.util.LinkedHashSet<>()).add(asset.getId());
                    });
            List<String> missing = incidence.entrySet().stream()
                    .filter(node -> configured.getNodeConfigurations().stream()
                            .filter(configuration -> configuration.getNodeId().equals(node.getKey()))
                            .noneMatch(configuration -> Set.copyOf(configuration.getIncidentAssetIds())
                                    .equals(node.getValue())))
                    .map(node -> (rootNodeIds.contains(node.getKey()) ? "root:"
                            : demandNodeIds.contains(node.getKey()) ? "demand:" : "internal:")
                            + node.getKey() + "=" + node.getValue().size())
                    .limit(6).collect(Collectors.toList());
            diagnostics.add("{root=" + entry.getValue().get(0).getFromPortId()
                    + ", demands=" + entry.getValue().stream()
                            .map(option -> option.getToPortId().replace("demand-port:", ""))
                            .sorted().collect(Collectors.toList())
                    + ", assets=" + assets.size() + ", missing=" + missing + "}");
        }
        return " groups=" + diagnostics;
    }

    private static NetworkConstraintProblem configuredSubset(
            NetworkConstraintProblem base, NetworkConstraintProblem configured,
            Set<String> managedNodeIds) {
        List<NetworkConstraintProblem.Node> nodes = base.getNodes().stream()
                .map(node -> new NetworkConstraintProblem.Node(
                        node.getId(), node.isAllowedRoot(), node.getDemandUnits(),
                        node.isTerminal(), managedNodeIds.contains(node.getId())))
                .collect(Collectors.toList());
        List<NetworkConstraintProblem.NodeConfiguration> configurations = configured
                .getNodeConfigurations().stream()
                .filter(configuration -> managedNodeIds.contains(configuration.getNodeId()))
                .collect(Collectors.toList());
        return new NetworkConstraintProblem(
                nodes, base.getAssets(), configurations, base.getConflicts());
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
