package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteVariant;
import ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class SavedChamberIncidenceTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final OfficialEconomics economics = new OfficialEconomics();

    @ParameterizedTest
    @CsvSource({"false,2,true", "false,3,false", "true,2,true", "true,3,false"})
    void exportRecountsExistingRaysAndDoesNotDoubleCountSeparateEndpointFeatures(
            boolean split, int newBranches, boolean accepted) throws Exception {
        List<ImportedOfficialFeature> features = split
                ? List.of(pipe("a", "LINESTRING (-20 0, 0 0)"), pipe("b", "LINESTRING (0 0, 20 0)"))
                : List.of(pipe("a", "LINESTRING (-20 0, 0 0, 20 0)"));
        verifyCapacity(features, newBranches, accepted);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "LINESTRING (-20 -20,20 20,20 -20,-20 20);1",
            "LINESTRING (0 0,20 0,20 20,0 0,-20 0);2"})
    void exportCountsEveryVisitOfASingleSourceLine(String line, int newBranches) throws Exception {
        verifyCapacity(List.of(pipe("a", line)), newBranches, false);
    }

    private void verifyCapacity(List<ImportedOfficialFeature> features, int newBranches, boolean accepted) throws Exception {
        ExistingNetworkSupportIndex support = new ExistingNetworkSupportIndex(features);
        RouteNode root = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(0, 0),
                true, true, 1, "chamber");
        List<RouteNode> nodes = new ArrayList<>(List.of(root));
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 0; i < newBranches; i++) {
            RouteNode demand = new RouteNode("d" + i, "demand_connection", new RouteCoordinate((i - 1) * 20, 20),
                    false, false, 0, null);
            nodes.add(demand);
            edges.add(new RouteEdge("e" + i, "root", demand.getId(),
                    root.getCoordinate().toCoordinate().distance(demand.getCoordinate().toCoordinate()),
                    List.of(root.getCoordinate(), demand.getCoordinate()), List.of(), BigDecimal.ONE, 100));
        }
        VariantEconomics cost = new OfficialVariantEconomicsCalculator(new OfficialPipeCatalog(), economics)
                .calculate(nodes, edges, List.of(), ExistingNetworkReconstructionResult.empty());
        RouteVariant route = new RouteVariant("route", "cheapest", nodes, edges, List.of(),
                edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add),
                List.of(), List.of(), ExistingNetworkReconstructionResult.empty(), cost, 1);
        ObjectNode saved = mapper.valueToTree(route);
        String before = mapper.writeValueAsString(saved);
        if (accepted) assertThat(SavedChamberAssessment.verify(saved, support, economics)).isEmpty();
        else assertThatThrownBy(() -> SavedChamberAssessment.verify(saved, support, economics))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CHAMBER_DEGREE_EXCEEDED");
        assertThat(mapper.writeValueAsString(saved)).isEqualTo(before);
    }

    private ImportedOfficialFeature pipe(String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "heat_network", mapper.readTree("{\"diameter\":100}"),
                new WKTReader().read(wkt));
    }
}
