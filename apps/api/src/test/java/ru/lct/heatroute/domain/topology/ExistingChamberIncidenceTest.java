package ru.lct.heatroute.domain.topology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.routing.OfficialRouteValidator;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteValidationIssue;

/** Существующая линия занимает по одному примыканию с каждой стороны камеры. */
class ExistingChamberIncidenceTest {
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "LINESTRING (0 0,20 0);0;0;1",
            "LINESTRING (20 0,0 0);0;0;1",
            "LINESTRING (-20 0,20 0);0;0;2",
            "LINESTRING (-20 0,0 0,20 0);0;0;2",
            "LINESTRING (-20 0,0 0,0 0,20 0);0;0;2",
            "LINESTRING (0 0,0 0,20 0);0;0;1",
            "LINESTRING (0 0,20 0,20 20,0 0);0;0;2",
            "LINESTRING (-20 -20,20 20,20 -20,-20 20);0;0;4",
            "LINESTRING (0 0,20 0,20 20,0 0,-20 0);0;0;3",
            "LINESTRING (-20 0,-0.005 0,0 0,0.005 0,20 0);0;0;2",
            "LINESTRING (0 0,0.005 0);0;0;1",
            "LINESTRING (0 0,20 0);10;0.01;2",
            "LINESTRING (0 0,20 0);10;0.011;0",
            "LINESTRING (400000 6000000,400020 6000020);400010;6000010;2",
            "LINESTRING (400020 6000020,400000 6000000);400000;6000000;1"})
    void countsPhysicalSidesWithoutCountingPolylineVertices(String wkt, double x, double y, int count)
            throws Exception {
        ExistingNetworkIncidence incidence = new ExistingNetworkIncidence(List.of(pipe("pipe", wkt)));
        assertThat(incidence.countAt(new org.locationtech.jts.geom.Coordinate(x, y))).isEqualTo(count);
    }

    @Test
    void splitTeeHasThreeExistingSectionsAndCanStillAcceptOneNewSection() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                pipe("left", "LINESTRING (-20 0,0 0)"), pipe("right", "LINESTRING (0 0,20 0)"),
                pipe("down", "LINESTRING (0 -20,0 0)"),
                feature("heat_chamber", "chamber", "POINT (0 0)"),
                feature("source", "source", "POINT (-20 0)"),
                feature("oks_connection_point", "consumer", "POINT (0 20)"));
        assertThat(new ExistingNetworkIncidence(features).countsByChamber(features)).containsEntry("chamber", 3);
        assertThat(new ExistingNetworkTopologyAnalyzer().analyze(features).getTieInCandidates())
                .anyMatch(candidate -> "chamber".equals(candidate.getTargetId()));
    }

    @Test
    void twoThroughLinesOccupyAllFourChamberConnections() throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>(List.of(
                feature("source", "source", "POINT (-20 0)"),
                feature("heat_chamber", "chamber", "POINT (0 0)"),
                pipe("horizontal", "LINESTRING (-20 0, 20 0)"),
                pipe("vertical", "LINESTRING (0 -20, 0 20)"),
                feature("oks_connection_point", "consumer", "POINT (5 5)")));
        TopologyAnalysis result = new ExistingNetworkTopologyAnalyzer().analyze(features);
        assertThat(result.getTieInCandidates()).isNotEmpty()
                .noneMatch(candidate -> "chamber".equals(candidate.getTargetId()));
    }

    @ParameterizedTest
    @CsvSource({"false,2,true", "false,3,false", "true,2,true", "true,3,false"})
    void independentValidatorRecountsSourceDespiteSavedUndercount(
            boolean splitAtChamber, int newBranches, boolean accepted) throws Exception {
        List<ImportedOfficialFeature> features = splitAtChamber
                ? List.of(pipe("left", "LINESTRING (-20 0, 0 0)"),
                        pipe("right", "LINESTRING (0 0, 20 0)"))
                : List.of(pipe("through", "LINESTRING (-20 0, 0 0, 20 0)"));
        verifyCapacity(features, newBranches, accepted);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "LINESTRING (-20 -20,20 20,20 -20,-20 20);1",
            "LINESTRING (0 0,20 0,20 20,0 0,-20 0);2"})
    void independentValidatorCountsEveryVisitOfASingleSourceLine(String line, int newBranches) throws Exception {
        verifyCapacity(List.of(pipe("a", line)), newBranches, false);
    }

    private void verifyCapacity(List<ImportedOfficialFeature> features, int newBranches, boolean accepted) {
        RouteNode root = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(0, 0),
                true, true, 1, "chamber");
        List<RouteNode> nodes = new ArrayList<>(List.of(root));
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 0; i < newBranches; i++) {
            RouteCoordinate point = new RouteCoordinate((i - 1) * 20, 20);
            RouteNode demand = new RouteNode("d" + i, "demand_connection", point, false, false, 0, null);
            nodes.add(demand);
            edges.add(new RouteEdge("e" + i, "root", demand.getId(), point.toCoordinate().distance(
                    root.getCoordinate().toCoordinate()), List.of(root.getCoordinate(), point), List.of(), null, null));
        }
        List<RouteValidationIssue> issues = new OfficialRouteValidator().validate(nodes, edges, features);
        assertThat(issues.stream().anyMatch(issue -> "CHAMBER_DEGREE_EXCEEDED".equals(issue.getCode())))
                .isEqualTo(!accepted);
        assertThat(root.getBaseIncidentSections()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "50,2", "100,1"})
    void newTieInUsesTheActualNumberOfExistingSections(double x, int expected) throws Exception {
        ExistingNetworkSupportIndex support = new ExistingNetworkSupportIndex(
                List.of(pipe("pipe", "LINESTRING (0 0, 50 0, 100 0)")));
        RouteNode root = new RouteNode("root", "new_tie_in_chamber", new RouteCoordinate(x, 0),
                true, true, 2, "pipe");
        assertThat(support.verified(root).getBaseIncidentSections()).isEqualTo(expected);
    }

    private ImportedOfficialFeature pipe(String id, String wkt) throws Exception {
        return feature("heat_network", id, wkt);
    }

    private ImportedOfficialFeature feature(String type, String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, type, new ObjectMapper().readTree("{\"diameter\":100}"),
                new WKTReader().read(wkt));
    }
}
