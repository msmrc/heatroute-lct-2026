package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

class ExistingChamberIncidencePlanningTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void plannerRecordsTwoExistingConnectionsForBothEquivalentLineRepresentations(boolean split) throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        if (split) {
            features.add(feature("heat_network", "north", "LINESTRING (0 0,0 100)", "{\"diameter\":50}"));
            features.add(feature("heat_network", "south", "LINESTRING (0 -100,0 0)", "{\"diameter\":50}"));
        } else {
            features.add(feature("heat_network", "through", "LINESTRING (0 -100,0 0,0 100)", "{\"diameter\":50}"));
        }
        features.add(feature("heat_chamber", "chamber", "POINT (0 0)", "{}"));
        features.add(feature("oks_connection_point", "left", "POINT (-100 0)", "{\"flow_tph\":5}"));
        features.add(feature("oks_connection_point", "right", "POINT (100 0)", "{\"flow_tph\":5}"));
        TopologyAnalysis topology = new TopologyAnalysis(1, split ? 2 : 1, 1, List.of(), List.of(
                new TieInCandidate("left", "chamber", "heat_chamber", 100, false),
                new TieInCandidate("right", "chamber", "heat_chamber", 100, false)));
        OfficialCalculationResult result = planner().plan(features, topology);
        assertThat(result.getVariants()).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.isValid()).isTrue();
            assertThat(variant.getConnectedDemandCount()).isEqualTo(2);
            assertThat(variant.getNodes()).filteredOn(RouteNode::isRoot).singleElement()
                    .satisfies(root -> assertThat(root.getBaseIncidentSections()).isEqualTo(2));
            assertThat(variant.getEdges()).hasSize(2);
        });
    }

    private RegressionRoutePlannerFixture planner() {
        OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
        OfficialRouteGeometryRules geometry = new OfficialRouteGeometryRules(constraints, new OfficialCrossingGeometry());
        OfficialPipeCatalog pipes = new OfficialPipeCatalog();
        OfficialEconomics economics = new OfficialEconomics();
        return new RegressionRoutePlannerFixture(new OfficialRouteValidator(geometry), new OfficialObstacleRouter(geometry), pipes,
                new OfficialNetworkSizer(pipes), new OfficialExistingNetworkReconstructor(pipes),
                new OfficialVariantEconomicsCalculator(pipes, economics),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(constraints, pipes),
                        new OfficialDepthOptimizer(pipes, economics), new OfficialDepthProfileValidator(pipes)));
    }

    private ImportedOfficialFeature feature(String type, String id, String wkt, String attributes) throws Exception {
        return new ImportedOfficialFeature(id, type, new ObjectMapper().readTree(attributes), new WKTReader().read(wkt));
    }
}
