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
    private final OfficialRoutePlanner planner = planner(RoutePlannerTuning.stable());

    private OfficialRoutePlanner planner(RoutePlannerTuning tuning) {
        return new OfficialRoutePlanner(
                new OfficialRouteValidator(geometryRules),
                new OfficialObstacleRouter(geometryRules),
                pipeCatalog,
                new OfficialNetworkSizer(pipeCatalog),
                new OfficialExistingNetworkReconstructor(pipeCatalog),
                new OfficialVariantEconomicsCalculator(pipeCatalog, new OfficialEconomics()),
                depthPlanner(),
                tuning);
    }

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
        assertThat(result.getAlgorithmVersion()).isEqualTo(RoutePlannerTuning.STABLE_ALGORITHM_VERSION);
        assertThat(shared.getEngineeringIssues()).isEmpty();
        assertThat(independent.getEngineeringIssues()).isEmpty();
    }

    @Test
    void legacyExperimentalTuningUsesThePrimaryAlgorithm() throws Exception {
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

        OfficialCalculationResult result = planner(RoutePlannerTuning.expertExperimental())
                .plan(features, topology);

        assertThat(result.getAlgorithmVersion()).isEqualTo(RoutePlannerTuning.STABLE_ALGORITHM_VERSION);
        JsonNode legacyResult = objectMapper.valueToTree(result);
        JsonNode primaryResult = objectMapper.valueToTree(planner.plan(features, topology));
        assertThat(legacyResult).isEqualTo(primaryResult);
    }

    @Test
    void oppositeDemandsCanUseTwoRaysOfOneExistingChamber() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "north", "LINESTRING (0 0, 0 100)", "{}"),
                feature("heat_network", "south", "LINESTRING (0 -100, 0 0)", "{}"),
                feature("heat_chamber", "chamber", "POINT (0 0)", "{}"),
                feature("oks_connection_point", "left", "POINT (-100 0)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "right", "POINT (100 0)", "{\"flow_tph\":5}"));
        OfficialCalculationResult result = planner.plan(features, topology(List.of(
                new TieInCandidate("left", "chamber", "heat_chamber", 100, false),
                new TieInCandidate("right", "chamber", "heat_chamber", 100, false))));

        assertThat(result.getVariants()).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.getConnectedDemandCount()).isEqualTo(2);
            assertThat(variant.getNodes()).filteredOn(RouteNode::isRoot).hasSize(1);
            assertThat(variant.getNodes()).filteredOn(node -> "new_branch_chamber".equals(node.getNodeType())).isEmpty();
            assertThat(variant.getEdges()).hasSize(2);
        });
    }

    @Test
    void groupBackboneConnectsSixOppositeConsumersWithThreeBranchChambers() throws Exception {
        List<ImportedOfficialFeature> features = new java.util.ArrayList<>(List.of(
                feature("heat_network", "north", "LINESTRING (0 0,0 100)", "{}"),
                feature("heat_network", "south", "LINESTRING (0 -100,0 0)", "{}"),
                feature("heat_chamber", "chamber", "POINT (0 0)", "{}")));
        List<TieInCandidate> candidates = new java.util.ArrayList<>();
        for (int x : List.of(100, 200, 300)) {
            for (int y : List.of(-30, 30)) {
                String id = "consumer-" + x + "-" + y;
                features.add(feature("oks_connection_point", id, "POINT (" + x + " " + y + ")",
                        "{\"flow_tph\":3}"));
                candidates.add(new TieInCandidate(id, "chamber", "heat_chamber", Math.hypot(x, y), false));
            }
        }
        OfficialCalculationResult result = planner.plan(features, topology(candidates));

        assertThat(result.getVariants()).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.isValid()).isTrue();
            assertThat(variant.getConnectedDemandCount()).isEqualTo(6);
            assertThat(variant.getSizingIssues()).isEmpty();
        });
        assertThat(result.getVariants()).anySatisfy(variant -> {
            assertThat(variant.getNodes()).filteredOn(RouteNode::isRoot).hasSize(1);
            assertThat(variant.getNodes()).filteredOn(node -> "new_branch_chamber".equals(node.getNodeType())).hasSize(3);
            assertThat(variant.getEngineeringIssues()).isEmpty();
            assertThat(variant.getTotalLengthM()).isLessThanOrEqualTo(new BigDecimal("485"));
        });
    }

    @Test
    void coincidentSegmentTieInsShareOnePhysicalChamber() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -100,0 100)", "{}"),
                feature("oks_connection_point", "left", "POINT (-100 0)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "right", "POINT (100 0)", "{\"flow_tph\":5}"));
        OfficialCalculationResult result = planner.plan(features, topology(List.of(
                new TieInCandidate("left", "network", "heat_network", 100, true, 0.0, 0.0),
                new TieInCandidate("right", "network", "heat_network", 100, true, 0.0, 0.0))));
        assertThat(result.getVariants()).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.getConnectedDemandCount()).isEqualTo(2);
            assertThat(variant.getNodes()).filteredOn(RouteNode::isChamber).hasSize(1);
            assertThat(variant.getEdges()).hasSize(2);
        });
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

        OfficialCalculationResult result = planner.plan(features, topology);
        result.getVariants().forEach(variant -> {
            var evaluation = new EngineeringRouteEvaluator().evaluate(variant.getEdges());
            assertThat(variant.getEngineeringIssues().stream()
                    .anyMatch(issue -> "EXPERT_BEND_ANGLE_OUT_OF_RANGE".equals(issue.getCode())))
                    .as("actual angle warnings for %s", variant.getId()).isEqualTo(evaluation.invalidAngleCount() > 0);
            assertThat(variant.getEngineeringIssues().stream()
                    .anyMatch(issue -> "EXPERT_BEND_SPACING_TOO_SHORT".equals(issue.getCode())))
                    .as("actual spacing warnings for %s", variant.getId()).isEqualTo(evaluation.insufficientSpacingCount() > 0);
        });
        RouteVariant shared = result.getVariants().stream()
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
    void finalConstraintWindowCoversTheWholeDetourNotOnlyEndpoints() throws Exception {
        ImportedOfficialFeature obstacle = feature("oks_existing", "remote",
                "POLYGON ((990 990,1010 990,1010 1010,990 1010,990 990))", "{}");
        OfficialRoutingEnvironment environment = new OfficialObstacleRouter(geometryRules)
                .prepare(List.of(), new InMemoryRoutingFeatureSource(List.of(obstacle)));
        RouteEdge detour = new RouteEdge("detour", "root", "demand", 2835,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(1000, 1000), new RouteCoordinate(10, 0)),
                List.of(), BigDecimal.ONE, 50);
        assertThat(planner.featuresForEdges(List.of(), List.of(detour), environment))
                .extracting(ImportedOfficialFeature::getFeatureId).contains("remote");
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
            assertThat(OfficialRouteDeflectionRules.validate(variant.getNodes(), variant.getEdges())).isEmpty();
            assertThat(variant.isValid()).isTrue();
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
                feature("heat_network", "network", "LINESTRING (10000 -10, 10000 10)", "{}"),
                feature("oks_connection_point", "cp", "POINT (0 0)", "{\"flow_tph\":5}"));

        RouteVariant variant = planner.plan(
                features,
                topology(List.of(candidate("cp", "network", 10000))))
                .getVariants().get(0);

        assertThat(variant.getConnectedDemandCount()).isEqualTo(1);
        assertThat(variant.getNoRouteDemandCount()).isZero();
        assertThat(variant.getSizingIssues()).isEmpty();
    }

    @Test
    void routeBeyondEveryCatalogLengthIsNotPublishedAsValid() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (50000 -10, 50000 10)", "{}"),
                feature("oks_connection_point", "cp", "POINT (0 0)", "{\"flow_tph\":5}"));
        OfficialCalculationResult result = planner.plan(features,
                topology(List.of(candidate("cp", "network", 50000))));
        assertThat(result.getVariants()).isEmpty();
        assertThat(result.getPreferredVariantId()).isNull();
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
        // У синтетических существующих труб этих сценариев ДУ50, если явно не задан другой.
        // Новое приложение требует ДУ; отсутствие расхода/реконструкции по-прежнему допустимо.
        if ("heat_network".equals(objectType) && !node.has("diameter")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("diameter", 50);
        }
        return new ImportedOfficialFeature(id, objectType, node, wktReader.read(wkt));
    }
}
