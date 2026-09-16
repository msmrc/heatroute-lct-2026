package ru.lct.heatroute.domain.reconstruction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialExistingNetworkReconstructorTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OfficialExistingNetworkReconstructor reconstructor =
            new OfficialExistingNetworkReconstructor(new OfficialPipeCatalog());

    @Test
    void splitsTargetSegmentAndSumsOverlappingTieInsUpstream() {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", point(0, 0), attributes()),
                feature("n1", "heat_network", line(0, 0, 100, 0),
                        attributes("upstream_object_id", "source", "flow_tph", 2.0, "diameter", 50)),
                feature("n2", "heat_network", line(100, 0, 200, 0),
                        attributes("upstream_object_id", "n1", "flow_tph", 1.0, "diameter", 50)));

        ExistingNetworkReconstructionResult result = reconstructor.reconstruct(features, List.of(
                new TieInLoad("n2", new RouteCoordinate(150, 0), new BigDecimal("3.5")),
                new TieInLoad("n2", new RouteCoordinate(180, 0), new BigDecimal("3.5"))));

        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getNetworkSections())
                .extracting(NetworkReconstructionSection::getExistingFeatureId)
                .containsExactly("n1", "n2", "n2");
        BigDecimal reconstructedTargetLength = result.getNetworkSections().stream()
                .filter(section -> "n2".equals(section.getExistingFeatureId()))
                .map(NetworkReconstructionSection::getLengthM)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(reconstructedTargetLength).isEqualByComparingTo("80");
        NetworkReconstructionSection sharedPart = result.getNetworkSections().stream()
                .filter(section -> "n2".equals(section.getExistingFeatureId()))
                .filter(section -> section.getAddedFlowTph().compareTo(new BigDecimal("7")) == 0)
                .findFirst().orElseThrow();
        assertThat(sharedPart.getLengthM()).isEqualByComparingTo("50");
        assertThat(sharedPart.isPartial()).isTrue();
        assertThat(sharedPart.getRequiredDiameter()).isEqualTo(65);
    }

    @Test
    void reconstructsUsedChamberFromUpstreamSegmentFlow() {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", point(0, 0), attributes()),
                feature("n1", "heat_network", line(0, 0, 100, 0),
                        attributes("upstream_object_id", "source", "flow_tph", 2.0, "diameter", 50)),
                feature("ch", "heat_chamber", point(100, 0),
                        attributes("upstream_object_id", "n1", "diameter", 50)));

        ExistingNetworkReconstructionResult result = reconstructor.reconstruct(features, List.of(
                new TieInLoad("ch", new RouteCoordinate(100, 0), new BigDecimal("3.5"))));

        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getNetworkSections()).hasSize(1);
        assertThat(result.getChambers()).singleElement().satisfies(chamber -> {
            assertThat(chamber.getExistingFeatureId()).isEqualTo("ch");
            assertThat(chamber.getExistingDiameter()).isEqualTo(50);
            assertThat(chamber.getRequiredDiameter()).isEqualTo(65);
            assertThat(chamber.getResultingFlowTph()).isEqualByComparingTo("5.5");
        });
    }

    @Test
    void reportsUnavailableInsteadOfInventingMissingOfficialInputs() {
        List<ImportedOfficialFeature> features = List.of(
                feature("source", "source", point(0, 0), attributes()),
                feature("n1", "heat_network", line(0, 0, 100, 0), attributes("diameter", 50)));

        ExistingNetworkReconstructionResult result = reconstructor.reconstruct(features, List.of(
                new TieInLoad("n1", new RouteCoordinate(50, 0), new BigDecimal("3.5"))));

        assertThat(result.isAvailable()).isFalse();
        assertThat(result.getNetworkSections()).isEmpty();
        assertThat(result.getIssues()).extracting(ReconstructionIssue::getCode)
                .containsOnly("RECONSTRUCTION_INPUT_UNAVAILABLE");
    }

    private ImportedOfficialFeature feature(
            String id, String type, Geometry geometry, ObjectNode attributes) {
        attributes.put("id", id);
        attributes.put("object_type", type);
        return new ImportedOfficialFeature(id, type, attributes, geometry);
    }

    private ObjectNode attributes(Object... values) {
        ObjectNode node = objectMapper.createObjectNode();
        for (int index = 0; index < values.length; index += 2) {
            String key = (String) values[index];
            Object value = values[index + 1];
            if (value instanceof String) {
                node.put(key, (String) value);
            } else if (value instanceof Integer) {
                node.put(key, (Integer) value);
            } else if (value instanceof Double) {
                node.put(key, (Double) value);
            }
        }
        return node;
    }

    private Geometry point(double x, double y) {
        return geometryFactory.createPoint(new Coordinate(x, y));
    }

    private Geometry line(double x1, double y1, double x2, double y2) {
        return geometryFactory.createLineString(new Coordinate[]{
                new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }
}
