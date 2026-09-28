package ru.lct.heatroute.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.routing.RoutingExecutionContext;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

class RoutingProblemFactoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WKTReader wkt = new WKTReader();
    private final RoutingProblemFactory factory = new RoutingProblemFactory();

    @Test
    void resolvesExtendedDemandFlowAndExactSegmentRootFromProductionFeatures() throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        features.add(feature("network", "heat_network", "LINESTRING (-20 0, 20 0)",
                attributes().put("diameter", 150)));
        features.add(feature("future-a", "oks_future", "POINT (0 20)",
                attributes().put("flow_tph", 8.3)));
        features.add(feature("cp-a", "oks_connection_point", "POINT (0 20)",
                attributes().put("oks_id", "future-a")));
        TopologyAnalysis topology = topology(List.of(
                new TieInCandidate("cp-a", "network", "heat_network",
                        20.0, true, 0.0, 0.0)));

        RoutingProblemSnapshot snapshot = factory.create(
                context(), OfficialRunParameters.defaults(), features, topology);

        assertThat(snapshot.getDemands()).singleElement().satisfies(demand -> {
            assertThat(demand.getId()).isEqualTo("cp-a");
            assertThat(demand.getFlowTph()).isEqualByComparingTo("8.3");
            assertThat(demand.getConnectionPointId()).isEqualTo("cp-a");
            assertThat(demand.getLinkedOksId()).isEqualTo("future-a");
        });
        assertThat(snapshot.getRoots()).singleElement().satisfies(root -> {
            assertThat(root.getId()).isEqualTo("root:segment:network:0:0");
            assertThat(root.getExistingDirections()).hasSize(2);
            assertThat(root.getRealization().getNodeType()).isEqualTo("new_tie_in_chamber");
            assertThat(root.getRealization().getBaseIncidentSections()).isEqualTo(2);
            assertThat(root.getRealization().getTargetId()).isEqualTo("network");
            assertThat(root.getRealization().getExistingIncidentDiameter()).isEqualTo(150);
        });
        assertThat(snapshot.getSourceHash()).isEqualTo("source-sha");
        assertThat(snapshot.getFeatureSourceVersion())
                .isEqualTo("heatroute-input-v2:source-sha");
    }

    @Test
    void keepsCoincidentConsumersDistinctAndDeduplicatesTheirSharedRoot() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("network", "heat_network", "LINESTRING (-20 0, 20 0)",
                        attributes().put("diameter", 100)),
                feature("cp-a", "oks_connection_point", "POINT (0 20)",
                        attributes().put("flow_tph", 0)),
                feature("cp-b", "oks_connection_point", "POINT (0 20)",
                        attributes().put("flow_tph", 4)));
        TopologyAnalysis topology = topology(List.of(
                new TieInCandidate("cp-a", "network", "heat_network",
                        20.0, true, 0.0, 0.0),
                new TieInCandidate("cp-b", "network", "heat_network",
                        20.0, true, 0.0, 0.0)));

        RoutingProblemSnapshot snapshot = factory.create(
                context(), OfficialRunParameters.defaults(), features, topology);

        assertThat(snapshot.getDemands()).extracting(RoutingProblemSnapshot.Demand::getId)
                .containsExactly("cp-a", "cp-b");
        assertThat(snapshot.getDemands().get(0).getFlowTph()).isZero();
        assertThat(snapshot.getRoots()).hasSize(1);
    }

    @Test
    void rejectsMissingMandatoryFlowInsteadOfInventingZero() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("network", "heat_network", "LINESTRING (-20 0, 20 0)",
                        attributes().put("diameter", 100)),
                feature("cp", "oks_connection_point", "POINT (0 20)", attributes()));

        assertThatThrownBy(() -> factory.create(
                context(), OfficialRunParameters.defaults(), features,
                topology(List.of(new TieInCandidate(
                        "cp", "network", "heat_network", 20.0, true, 0.0, 0.0)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing mandatory flow_tph")
                .hasMessageContaining("cp");
    }

    private RoutingExecutionContext context() {
        return new RoutingExecutionContext(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                "source-sha", "heatroute-input-v2", "extended");
    }

    private TopologyAnalysis topology(List<TieInCandidate> candidates) {
        return new TopologyAnalysis(1, 1, 0, List.of(), candidates);
    }

    private ImportedOfficialFeature feature(
            String id, String type, String geometry, ObjectNode attributes) throws Exception {
        return new ImportedOfficialFeature(id, type, attributes, wkt.read(geometry));
    }

    private ObjectNode attributes() {
        return mapper.createObjectNode();
    }
}
