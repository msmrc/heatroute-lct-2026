package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

class StrictChamberPlannerTest {
    @ParameterizedTest
    @CsvSource({"0,false", "27,false", "90,false", "0,true", "27,true"})
    void diagonalConsumersReceiveNormalCameraApproachesInEveryRole(double rotation, boolean depth) throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        add(features, rotation, "heat_network", "network", "LINESTRING (0 -100,0 100)", "{\"diameter\":50}");
        add(features, rotation, "heat_chamber", "chamber", "POINT (0 0)", "{}");
        add(features, rotation, "oks_connection_point", "left", "POINT (-100 -40)", "{\"flow_tph\":5}");
        add(features, rotation, "oks_connection_point", "right", "POINT (100 40)", "{\"flow_tph\":5}");
        TopologyAnalysis topology = new TopologyAnalysis(1, 1, 1, List.of(), List.of(
                new TieInCandidate("left", "chamber", "heat_chamber", 108, false),
                new TieInCandidate("right", "chamber", "heat_chamber", 108, false)));
        OfficialCalculationResult result = new OfficialDatasetRoutingTest().planner().plan(features, topology,
                new OfficialRunParameters(null, null, depth));
        ExistingNetworkSupportIndex support = new ExistingNetworkSupportIndex(features);
        assertThat(result.getVariants()).hasSize(3).allSatisfy(variant -> {
            assertThat(variant.getConnectedDemandCount()).isEqualTo(2);
            assertThat(variant.isValid()).isTrue();
            assertThat(new ExpertChamberRouteValidator().validate(variant.getNodes(), variant.getEdges(),
                    support::existingDirections)).isEmpty();
            assertThat(new EngineeringRouteEvaluator().evaluate(variant.getEdges()).isCompliant()).isTrue();
        });
    }

    private void add(List<ImportedOfficialFeature> features, double rotation, String type, String id,
            String wkt, String attributes) throws Exception {
        Geometry geometry = new WKTReader().read(wkt);
        geometry = AffineTransformation.rotationInstance(Math.toRadians(rotation)).transform(geometry);
        geometry = AffineTransformation.translationInstance(400000, 6000000).transform(geometry);
        features.add(new ImportedOfficialFeature(id, type, new ObjectMapper().readTree(attributes), geometry));
    }
}
