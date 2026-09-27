package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class SavedUtilityHorizontalAssessmentTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();

    @Test
    void rejectsAnOrdinaryViolationInTheSavedAxis() {
        ObjectNode variant = variant(0, 0, 20, 0, 400);
        ImportedOfficialFeature source = heat("existing", 400, -10, 2, 30, 2);

        assertThatThrownBy(
                        () ->
                                SavedUtilityHorizontalAssessment.verify(
                                        variant, List.of(source), pipes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UTILITY_HORIZONTAL_CLEARANCE_SOURCE")
                .hasMessageContaining("edge=edge")
                .hasMessageContaining("source=existing")
                .hasMessageContaining("actual=2.000000")
                .hasMessageContaining("required=2.370");
    }

    @Test
    void acceptsExactEqualityInBothSavedAndEmittedAxes() {
        ObjectNode variant = variant(0, 0, 20, 0, 400);
        ImportedOfficialFeature source = heat("existing", 400, -10, 2.37, 30, 2.37);

        var result =
                SavedUtilityHorizontalAssessment.verify(variant, List.of(source), pipes);

        assertThat(result.getBoundaryFindings()).isEmpty();
    }

    @Test
    void rejectsMillimetreRoundingThatMakesOnlyTheEmittedAxisIllegal() {
        ObjectNode variant = variant(0, 2.4, 20, 2.4, 50);
        ObjectNode edge = (ObjectNode) variant.path("edges").get(0);
        ObjectNode section = edge.putArray("sections").addObject();
        section.put("kind", "base");
        coordinates(section.putArray("coordinates"), 0, 2.399, 20, 2.399);
        ImportedOfficialFeature gas = restriction("gas", "gas_pipeline", -10, 0, 30, 0);

        assertThatThrownBy(
                        () ->
                                SavedUtilityHorizontalAssessment.verify(
                                        variant, List.of(gas), pipes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UTILITY_HORIZONTAL_CLEARANCE_EMITTED")
                .hasMessageContaining("actual=2.399000")
                .hasMessageContaining("required=2.400");
    }

    @Test
    void rejectsAnObliqueUtilityCrossingBeforeWritingOutput() {
        ObjectNode variant = variant(-10, -5, 10, 5, 100);
        ImportedOfficialFeature gas = restriction("gas", "gas_pipeline", -20, 0, 20, 0);

        assertThatThrownBy(
                        () -> SavedUtilityHorizontalAssessment.verify(
                                variant, List.of(gas), pipes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPECIAL_SECTION_CROSSING_ANGLE: gas");
    }

    private ObjectNode variant(
            double startX, double startY, double endX, double endY, int diameter) {
        ObjectNode variant = mapper.createObjectNode();
        variant.put("id", "variant");
        ArrayNode nodes = variant.putArray("nodes");
        nodes.addObject().put("id", "root").put("root", true);
        nodes.addObject().put("id", "demand").put("root", false);
        ObjectNode edge = variant.putArray("edges").addObject();
        edge.put("id", "edge");
        edge.put("upstream_node_id", "root");
        edge.put("downstream_node_id", "demand");
        edge.put("length_m", Math.hypot(endX - startX, endY - startY));
        edge.put("diameter", diameter);
        coordinates(edge.putArray("coordinates"), startX, startY, endX, endY);
        return variant;
    }

    private void coordinates(ArrayNode output, double... xy) {
        for (int index = 0; index < xy.length; index += 2) {
            output.addObject().put("xm", xy[index]).put("ym", xy[index + 1]);
        }
    }

    private ImportedOfficialFeature heat(
            String id, int diameter, double... xy) {
        return new ImportedOfficialFeature(
                id,
                "heat_network",
                mapper.createObjectNode().put("diameter", diameter),
                line(xy));
    }

    private ImportedOfficialFeature restriction(
            String id, String type, double... xy) {
        return new ImportedOfficialFeature(
                id,
                "restriction",
                mapper.createObjectNode().put("restriction_type", type),
                line(xy));
    }

    private org.locationtech.jts.geom.LineString line(double... xy) {
        Coordinate[] coordinates = new Coordinate[xy.length / 2];
        for (int index = 0; index < coordinates.length; index++) {
            coordinates[index] = new Coordinate(xy[index * 2], xy[index * 2 + 1]);
        }
        return geometryFactory.createLineString(coordinates);
    }
}
