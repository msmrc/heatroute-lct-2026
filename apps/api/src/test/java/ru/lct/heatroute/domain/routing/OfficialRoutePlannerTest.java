package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void nearbyDemandsPreferShorterSharedTrunk() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 -100, 0 100)", "{}"),
                feature("oks_connection_point", "cp-a", "POINT (100 0)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (100 10)", "{\"flow_tph\":7}"));
        TopologyAnalysis topology = topology(List.of(
                candidate("cp-a", "network", 100),
                candidate("cp-b", "network", 100)));

        OfficialCalculationResult result = planner.plan(features, topology);

        assertThat(result.getVariants()).extracting(RouteVariant::getId)
                .containsExactly("independent", "shared");
        RouteVariant independent = result.getVariants().get(0);
        RouteVariant shared = result.getVariants().get(1);
        assertThat(shared.getTotalLengthM()).isLessThan(independent.getTotalLengthM());
        assertThat(shared.getEdges()).hasSize(3);
        assertThat(shared.getNodes()).filteredOn(RouteNode::isChamber).hasSize(2);
        assertThat(shared.isValid()).isTrue();
        assertThat(result.getPreferredVariantId()).isEqualTo("shared");
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

        assertThat(result.getVariants()).singleElement().satisfies(variant -> {
            assertThat(variant.getId()).isEqualTo("independent");
            assertThat(variant.getConnectedDemandCount()).isEqualTo(2);
            assertThat(variant.getEdges()).hasSize(2);
        });
        assertThat(result.getPreferredVariantId()).isEqualTo("independent");
    }

    @Test
    void impossibleDemandIsReportedWithoutDiscardingConnectedDemand() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("heat_network", "network", "LINESTRING (0 0, 0 100)", "{}"),
                feature("oks_connection_point", "cp-a", "POINT (100 50)", "{\"flow_tph\":5}"),
                feature("oks_connection_point", "cp-b", "POINT (900 50)", "{\"flow_tph\":7}"));

        OfficialCalculationResult result = planner.plan(
                features,
                topology(List.of(candidate("cp-a", "network", 100))));

        RouteVariant variant = result.getVariants().get(0);
        assertThat(variant.getConnectedDemandCount()).isEqualTo(1);
        assertThat(variant.getNoRouteDemandCount()).isEqualTo(1);
        assertThat(variant.getConnections())
                .filteredOn(connection -> "no_route".equals(connection.getStatus()))
                .singleElement()
                .extracting(RouteConnection::getDemandId, RouteConnection::getReason)
                .containsExactly("cp-b", "NO_TIE_IN_CANDIDATE");
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
    void selectsFartherTieInWhenNearestCandidateWouldCrossAnAcceptedRoute() throws Exception {
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

        RouteVariant independent = planner.plan(features, topology).getVariants().get(0);

        assertThat(independent.isValid()).isTrue();
        assertThat(independent.getConnectedDemandCount()).isEqualTo(2);
        assertThat(independent.getNodes())
                .filteredOn(node -> "network-good".equals(node.getTargetId()))
                .hasSize(1);
        assertThat(independent.getNodes())
                .filteredOn(node -> "network-bad".equals(node.getTargetId()))
                .isEmpty();
    }

    @Test
    void producesThreeMateriallyDifferentVariantsWhenAlternativeTieInsExist() throws Exception {
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
                .containsExactly("independent", "shared", "diverse");
        assertThat(result.getVariants()).allMatch(RouteVariant::isValid);
        assertThat(result.getVariants().get(0).getNodes())
                .filteredOn(node -> "network-a".equals(node.getTargetId()))
                .isNotEmpty();
        assertThat(result.getVariants().get(2).getNodes())
                .filteredOn(node -> "network-b".equals(node.getTargetId()))
                .isNotEmpty();
    }

    @Test
    void includesPartialExistingNetworkReconstructionInPlannedVariant() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", "POINT (0 0)", "{}"),
                feature("heat_network", "network", "LINESTRING (0 0, 100 0)",
                        "{\"upstream_object_id\":\"source\",\"flow_tph\":2,\"diameter\":50}"),
                feature("oks_connection_point", "cp", "POINT (50 50)", "{\"flow_tph\":5}"));

        RouteVariant variant = planner.plan(
                features,
                topology(List.of(candidate("cp", "network", 50))))
                .getVariants().get(0);

        assertThat(variant.getReconstruction().isAvailable()).isTrue();
        assertThat(variant.getEconomics().isComplete()).isTrue();
        assertThat(variant.getEconomics().getScore()).isNotNull();
        assertThat(variant.getRank()).isEqualTo(1);
        assertThat(variant.getReconstruction().getNetworkSections()).singleElement().satisfies(section -> {
            assertThat(section.getExistingFeatureId()).isEqualTo("network");
            assertThat(section.getLengthM()).isEqualByComparingTo("50");
            assertThat(section.getExistingDiameter()).isEqualTo(50);
            assertThat(section.getRequiredDiameter()).isEqualTo(65);
            assertThat(section.isPartial()).isTrue();
        });
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
