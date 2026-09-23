package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

class OfficialRoutePlannerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WKTReader wktReader = new WKTReader();
    private final OfficialRouteGeometryRules geometryRules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialPipeCatalog pipeCatalog = new OfficialPipeCatalog();
    private final OfficialRoutePlanner planner = new OfficialRoutePlanner(
            new OfficialRouteValidator(geometryRules),
            new OfficialObstacleRouter(geometryRules),
            pipeCatalog,
            new OfficialNetworkSizer(pipeCatalog),
            new OfficialExistingNetworkReconstructor(pipeCatalog),
            new OfficialVariantEconomicsCalculator(pipeCatalog, new OfficialEconomics()),
            depthPlanner());

    private OfficialDepthPlanner depthPlanner() {
        return new OfficialDepthPlanner(
                new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipeCatalog),
                new OfficialDepthOptimizer(pipeCatalog, new OfficialEconomics()),
                new OfficialDepthProfileValidator(pipeCatalog));
    }

    @Test
    void nearbyDemandsPublishAllObjectiveVariantsWithAValidSharedTrunk() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -100, 0 100)", "{}"),
                feature("restriction", "shared-oks",
                        "POLYGON ((95 -20, 110 -20, 110 30, 95 30, 95 -20))",
                        "{\"restriction_type\":\"oks\"}"),
                feature("oks_connection_point", "cp-a", "POINT (100 0)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (100 10)", "{\"flow_tph\":7}"));
        TopologyAnalysis topology = topology(List.of(
                candidate("cp-a", "network", 100),
                candidate("cp-b", "network", 100)));

        OfficialCalculationResult result = planner.plan(features, topology);

        assertThat(result.getVariants()).extracting(RouteVariant::getId)
                .contains("shortest", "balanced");
        RouteVariant independent = result.getVariants().stream()
                .filter(variant -> "shortest".equals(variant.getStrategy()))
                .findFirst()
                .orElseThrow();
        RouteVariant shared = result.getVariants().stream()
                .filter(variant -> "engineering".equals(variant.getStrategy()))
                .findFirst()
                .orElseThrow();
        assertThat(shared.getTotalLengthM()).isLessThanOrEqualTo(independent.getTotalLengthM());
        assertThat(shared.getEconomics().getCalculatedCost())
                .isLessThanOrEqualTo(independent.getEconomics().getCalculatedCost());
        assertThat(shared.getEdges()).hasSize(3);
        assertThat(shared.getNodes()).filteredOn(RouteNode::isChamber).hasSize(2);
        assertThat(shared.isValid()).isTrue();
        assertThat(result.getPreferredVariantId()).isEqualTo("balanced");
        assertThat(result.getAlgorithmVersion()).isEqualTo("global-tree-46");
        assertThat(shared.getEngineeringIssues()).isEmpty();
        assertThat(independent.getEngineeringIssues()).isEmpty();
    }

    @Test
    void thirdDemandGraftsOntoTheAlreadyBuiltTree() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -100, 0 100)", "{}"),
                feature("restriction", "shared-oks",
                        "POLYGON ((95 -20, 110 -20, 110 40, 95 40, 95 -20))",
                        "{\"restriction_type\":\"oks\"}"),
                feature("oks_connection_point", "cp-a", "POINT (100 0)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (100 10)", "{\"flow_tph\":7}"),
                feature("oks_connection_point", "cp-c", "POINT (100 20)", "{\"flow_tph\":6}"));
        TopologyAnalysis topology = topology(List.of(
                candidate("cp-a", "network", 100),
                candidate("cp-b", "network", 100),
                candidate("cp-c", "network", 100)));

        RouteVariant shared = planner.plan(features, topology).getVariants().stream()
                .filter(variant -> "balanced".equals(variant.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(shared.isValid()).isTrue();
        assertThat(shared.getConnectedDemandCount()).isEqualTo(3);
        assertThat(shared.getNodes()).filteredOn(RouteNode::isRoot).hasSize(1);
        assertThat(shared.getEdges())
                .filteredOn(edge -> shared.getNodes().stream()
                        .filter(RouteNode::isRoot)
                        .map(RouteNode::getId)
                        .anyMatch(edge.getUpstreamNodeId()::equals))
                .hasSize(1);
        assertThat(shared.getNodes())
                .filteredOn(node -> "new_branch_chamber".equals(node.getNodeType()))
                .isNotEmpty()
                .allSatisfy(node -> assertThat(shared.getEdges().stream()
                        .filter(edge -> edge.getUpstreamNodeId().equals(node.getId())
                                || edge.getDownstreamNodeId().equals(node.getId()))
                        .count()).isBetween(3L, 4L));
        assertThat(shared.getEdges())
                .anySatisfy(edge -> assertThat(edge.getFlowTph())
                        .isEqualByComparingTo(new BigDecimal("18")));
    }

    @Test
    void windowedConstraintSourceMatchesMaterializedPlannerResult() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -100, 0 100)", "{}"),
                feature("restriction", "shared-oks",
                        "POLYGON ((95 -20, 110 -20, 110 30, 95 30, 95 -20))",
                        "{\"restriction_type\":\"oks\"}"),
                feature("restriction", "road", "LINESTRING (45 -40, 45 40)",
                        "{\"restriction_type\":\"road\"}"),
                feature("oks_connection_point", "cp-a", "POINT (100 0)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (100 10)", "{\"flow_tph\":7}"));
        TopologyAnalysis topology = topology(List.of(
                candidate("cp-a", "network", 100),
                candidate("cp-b", "network", 100)));
        OfficialRunParameters parameters = OfficialRunParameters.defaults();

        OfficialCalculationResult materialized = planner.plan(
                features, topology, parameters, "baseline_input");
        List<ImportedOfficialFeature> core = features.stream()
                .filter(feature -> !"restriction".equals(feature.getObjectType()))
                .collect(java.util.stream.Collectors.toList());
        List<ImportedOfficialFeature> restrictions = features.stream()
                .filter(feature -> "restriction".equals(feature.getObjectType()))
                .collect(java.util.stream.Collectors.toList());
        OfficialCalculationResult windowed = planner.plan(
                core,
                topology,
                parameters,
                "baseline_input",
                new InMemoryRoutingFeatureSource(restrictions));

        JsonNode windowedJson = objectMapper.valueToTree(windowed);
        JsonNode materializedJson = objectMapper.valueToTree(materialized);
        assertThat(windowedJson).isEqualTo(materializedJson);
    }

    @Test
    void distantDemandsWithDifferentTieInsRemainSeparate() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "left-network", "LINESTRING (0 0, 0 100)", "{}"),
                feature("heat_network", "right-network", "LINESTRING (1000 0, 1000 100)", "{}"),
                feature("oks_connection_point", "cp-a", "POINT (100 50)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (900 50)", "{\"flow_tph\":7}"));
        TopologyAnalysis topology = topology(List.of(
                candidate("cp-a", "left-network", 100),
                candidate("cp-b", "right-network", 100)));

        OfficialCalculationResult result = planner.plan(features, topology);

        assertThat(result.getVariants()).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.getConnectedDemandCount()).isEqualTo(2);
            assertThat(variant.getEdges()).hasSize(2);
        });
        assertThat(result.getPreferredVariantId()).isNotNull();
    }

    @Test
    void demandWithoutOwnTieInAttachesToTheAcceptedTree() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 0, 0 100)", "{}"),
                feature("oks_connection_point", "cp-a", "POINT (100 50)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (900 50)", "{\"flow_tph\":7}"));

        OfficialCalculationResult result = planner.plan(
                features,
                topology(List.of(candidate("cp-a", "network", 100))));

        assertThat(result.getVariants()).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.getConnectedDemandCount()).isEqualTo(2);
            assertThat(variant.getNoRouteDemandCount()).isZero();
        });
    }

    @Test
    void impossibleVerticalCrossingTriggersSeparatePlanDetour() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -10, 0 10)", "{}"),
                feature("restriction", "cable", "LINESTRING (95 -20, 95 20)",
                        "{\"restriction_type\":\"power_cable\"}"),
                feature("oks_connection_point", "cp", "POINT (100 0)", "{\"flow_tph\":5}"));

        List<RouteVariant> variants = planner.plan(
                features,
                topology(List.of(candidate("cp", "network", 100))),
                new OfficialRunParameters(new BigDecimal("3.0"), new BigDecimal("3.0"), true))
                .getVariants();

        assertThat(variants).anySatisfy(variant -> {
            assertThat(variant.getEdges()).singleElement().satisfies(edge -> {
                assertThat(edge.getDepthProfile().getIssues())
                        .as("coordinates %s", edge.getCoordinates())
                        .extracting(
                                ru.lct.heatroute.domain.depth.DepthProfileIssue::getCode,
                                ru.lct.heatroute.domain.depth.DepthProfileIssue::getCrossingId)
                        .isEmpty();
                assertThat(edge.getLengthM()).isGreaterThan(new java.math.BigDecimal("100"));
                assertThat(edge.getCoordinates()).hasSizeGreaterThan(2);
                assertThat(edge.getDepthProfile().isComplete()).isTrue();
                assertThat(edge.getDepthProfile().getCrossings()).isEmpty();
            });
        });
    }

    @Test
    void mandatoryTwoDimensionalRouteDoesNotRequireOptionalDepthProfile() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -10, 0 10)", "{}"),
                feature("restriction", "cable", "LINESTRING (95 -20, 95 20)",
                        "{\"restriction_type\":\"power_cable\"}"),
                feature("oks_connection_point", "cp", "POINT (100 0)", "{\"flow_tph\":5}"));

        RouteVariant variant = planner.plan(
                features,
                topology(List.of(candidate("cp", "network", 100))))
                .getVariants().get(0);

        assertThat(variant.isValid()).isTrue();
        assertThat(variant.getEdges()).singleElement()
                .extracting(RouteEdge::getDepthProfile)
                .isNull();
    }

    @Test
    void depthDetourPreservesMandatoryOksEgress() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -10, 0 10)", "{}"),
                feature("restriction", "cable", "LINESTRING (50 -20, 50 20)",
                        "{\"restriction_type\":\"power_cable\"}"),
                feature("restriction", "own-oks", "POLYGON ((90 -10, 110 -10, 110 10, 90 10, 90 -10))",
                        "{\"restriction_type\":\"oks\"}"),
                feature("oks_connection_point", "cp", "POINT (100 0)", "{\"flow_tph\":5}"));

        RouteVariant variant = planner.plan(
                features,
                topology(List.of(candidate("cp", "network", 100))),
                new OfficialRunParameters(new BigDecimal("3.0"), new BigDecimal("3.0"), true))
                .getVariants().get(0);

        assertThat(variant.isValid()).isTrue();
        assertThat(variant.getEdges()).singleElement().satisfies(edge -> {
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            RouteCoordinate egress = coordinates.get(coordinates.size() - 2);
            RouteCoordinate demand = coordinates.get(coordinates.size() - 1);
            assertThat(distance(egress, demand)).isGreaterThanOrEqualTo(10.24);
            assertThat(edge.getDepthProfile().isComplete()).isTrue();
        });
    }

    @Test
    void demandInsideOksLeavesThroughOneNearbyBoundary() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -100, 0 100)", "{}"),
                feature("restriction", "own-oks", "POLYGON ((90 -10, 110 -10, 110 10, 90 10, 90 -10))",
                        "{\"restriction_type\":\"oks\"}"),
                feature("oks_connection_point", "cp", "POINT (100 0)", "{\"flow_tph\":5}"));

        RouteVariant variant = planner.plan(
                features,
                topology(List.of(candidate("cp", "network", 100))))
                .getVariants().get(0);

        assertThat(variant.isValid()).isTrue();
        assertThat(variant.getEdges()).singleElement().satisfies(edge -> {
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            RouteCoordinate egress = coordinates.get(coordinates.size() - 2);
            RouteCoordinate demand = coordinates.get(coordinates.size() - 1);
            assertThat(demand.getXM()).isEqualByComparingTo(new BigDecimal("100.0"));
            assertThat(distance(egress, demand)).isGreaterThanOrEqualTo(10.24);
        });
    }

    private double distance(RouteCoordinate left, RouteCoordinate right) {
        return Math.hypot(
                left.getXM().subtract(right.getXM()).doubleValue(),
                left.getYM().subtract(right.getYM()).doubleValue());
    }

    @Test
    void normalizedResultIsByteStableForSameInput() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -100, 0 100)", "{}"),
                feature("oks_connection_point", "cp-b", "POINT (100 10)", "{\"flow_tph\":7}"),
                feature("oks_connection_point", "cp-a", "POINT (100 0)", "{\"flow_tph\":5}"));
        TopologyAnalysis topology = topology(List.of(
                candidate("cp-b", "network", 100),
                candidate("cp-a", "network", 100)));

        byte[] first = objectMapper.writeValueAsBytes(planner.plan(features, topology));
        byte[] second = objectMapper.writeValueAsBytes(planner.plan(features, topology));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void avoidsNearestTieInWhenItWouldCrossAnAcceptedRoute() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network-a", "LINESTRING (10 10, 10 20)", "{}"),
                feature("heat_network", "network-bad", "LINESTRING (0 10, 0 20)", "{}"),
                feature("heat_network", "network-good", "LINESTRING (20 0, 20 5)", "{}"),
                feature("oks_connection_point", "cp-a", "POINT (0 0)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (10 0)", "{\"flow_tph\":7}"));
        TopologyAnalysis topology = topology(List.of(
                candidate("cp-a", "network-a", 1),
                candidate("cp-b", "network-bad", 1),
                candidate("cp-b", "network-good", 2)));

        RouteVariant independent = planner.plan(features, topology).getVariants().stream()
                .filter(variant -> "shortest".equals(variant.getStrategy()))
                .findFirst()
                .orElseThrow();

        assertThat(independent.isValid()).isTrue();
        assertThat(independent.getConnectedDemandCount()).isEqualTo(2);
        assertThat(independent.getNodes())
                .filteredOn(node -> "network-bad".equals(node.getTargetId()))
                .isEmpty();
    }

    @Test
    void searchesBeyondFourNearestTieInsWhenEarlierTargetsAreUnavailable() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network-a", "LINESTRING (10 10, 10 20)", "{}"),
                feature("heat_network", "network-good", "LINESTRING (20 0, 20 5)", "{}"),
                feature("oks_connection_point", "cp-a", "POINT (0 0)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (10 0)", "{\"flow_tph\":7}"));
        TopologyAnalysis topology = topology(List.of(
                candidate("cp-a", "network-a", 1),
                candidate("cp-b", "network-bad-1", 1),
                candidate("cp-b", "network-bad-2", 2),
                candidate("cp-b", "network-bad-3", 3),
                candidate("cp-b", "network-bad-4", 4),
                candidate("cp-b", "network-bad-5", 5),
                candidate("cp-b", "network-good", 6)));

        RouteVariant independent = planner.plan(features, topology).getVariants().stream()
                .filter(variant -> "shortest".equals(variant.getStrategy()))
                .findFirst()
                .orElseThrow();

        assertThat(independent.getConnectedDemandCount()).isEqualTo(2);
        assertThat(independent.getNodes())
                .filteredOn(node -> node.getTargetId() != null
                        && node.getTargetId().startsWith("network-bad"))
                .isEmpty();
    }

    @Test
    void preservesExpensiveButFeasibleConnectionForCoverageFirstGeneration() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (50000 -10, 50000 10)", "{}"),
                feature("oks_connection_point", "cp", "POINT (0 0)", "{\"flow_tph\":5}"));

        RouteVariant variant = planner.plan(
                features,
                topology(List.of(candidate("cp", "network", 50000))))
                .getVariants().get(0);

        assertThat(variant.getConnectedDemandCount()).isEqualTo(1);
        assertThat(variant.getNoRouteDemandCount()).isZero();
    }

    @Test
    void explainsNoRouteWhenNoTieInCandidatesExist() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("oks_connection_point", "cp", "POINT (0 0)", "{\"flow_tph\":5}"));

        RouteConnection connection = planner.plan(features, topology(List.of()))
                .getVariants().get(0).getConnections().get(0);

        assertThat(connection.getReason()).isEqualTo("NO_TIE_IN_CANDIDATE");
        assertThat(connection.getDiagnostics()).isNotNull();
        assertThat(connection.getDiagnostics().getCandidateCount()).isZero();
        assertThat(connection.getDiagnostics().getAttemptedCandidateCount()).isZero();
    }

    @Test
    void publishesThreeObjectiveVariantsWhenAlternativeTieInsExist() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network-a", "LINESTRING (0 -100, 0 100)", "{}"),
                feature("heat_network", "network-b", "LINESTRING (200 -100, 200 100)", "{}"),
                feature("oks_connection_point", "cp-a", "POINT (100 0)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (100 10)", "{\"flow_tph\":7}"));
        TopologyAnalysis topology = topology(List.of(
                candidate("cp-a", "network-a", 100),
                candidate("cp-a", "network-b", 100),
                candidate("cp-b", "network-a", 100),
                candidate("cp-b", "network-b", 100)));

        OfficialCalculationResult result = planner.plan(features, topology);

        assertThat(result.getVariants()).extracting(RouteVariant::getId)
                .containsExactly("balanced", "shortest", "cheapest");
        assertThat(result.getVariants()).allMatch(RouteVariant::isValid);
        assertThat(result.getVariants().get(0).getNodes())
                .filteredOn(node -> "network-a".equals(node.getTargetId()))
                .isNotEmpty();
    }

    @Test
    void baselineVariantsAreRankedWithoutExistingNetworkReconstruction() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (0 0)", "{}"),
                feature("heat_network", "network", "LINESTRING (0 0, 100 0)",
                        "{\"upstream_object_id\":\"source\",\"flow_tph\":2,\"diameter\":50}"),
                feature("oks_connection_point", "cp", "POINT (50 50)", "{\"flow_tph\":5}"));

        OfficialCalculationResult result = planner.plan(
                features,
                topology(List.of(candidate("cp", "network", 50))));

        assertThat(result.getVariants()).allSatisfy(variant -> {
            assertThat(variant.getReconstruction().isAvailable()).isTrue();
            assertThat(variant.getReconstruction().getNetworkSections()).isEmpty();
            assertThat(variant.getReconstruction().getChambers()).isEmpty();
            assertThat(variant.getEconomics().isComplete()).isTrue();
            assertThat(variant.getEconomics().getScore()).isNotNull();
        });
        assertThat(result.getVariants()).anySatisfy(variant -> assertThat(variant.getRank()).isEqualTo(1));
    }

    private TopologyAnalysis topology(List<TieInCandidate> candidates) {
        return new TopologyAnalysis(1, 1, 0, Collections.emptyList(), candidates);
    }

    private TieInCandidate candidate(String connectionPointId, String targetId, double distanceM) {
        return new TieInCandidate(connectionPointId, targetId, "heat_network", distanceM, true);
    }

    private ImportedOfficialFeature feature(
            String objectType,
            String id,
            String wkt,
            String attributes) throws Exception {
        JsonNode node = objectMapper.readTree(attributes);
        return new ImportedOfficialFeature(id, objectType, node, wktReader.read(wkt));
    }
}
