package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class DepthPlannerNetworkWiringTest {
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialDepthProfileValidator validator =
            new OfficialDepthProfileValidator(pipes);
    private final OfficialDepthPlanner planner =
            new OfficialDepthPlanner(
                    new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
                    new OfficialDepthOptimizer(pipes, new OfficialEconomics()),
                    validator);

    @Test
    void officialPlannerUsesCommonNodeDepthAndDoesNotPinExternalSourceToThree() throws Exception {
        var features = List.of(utility("power", "power_cable", 48));
        var input =
                List.of(
                        edge("incoming", "source", "joint", 0, 50, 1400),
                        edge("outgoing", "joint", "demand", 50, 100, 50));
        var result = plan(input, features, Map.of());
        assertComplete(result);
        assertThat(result.get(0).getDepthProfile().depthAt(b("50"))).isEqualByComparingTo("3.4");
        assertThat(result.get(1).getDepthProfile().depthAt(b("0"))).isEqualByComparingTo("3.4");
        assertThat(validator.validateContinuity(result, Map.of())).isEmpty();

        var shortSource = List.of(edge("incoming", "source", "demand", 46, 100, 1400));
        var freeSource = plan(shortSource, features, Map.of());
        assertComplete(freeSource);
        assertThat(freeSource.get(0).getDepthProfile().depthAt(b("0"))).isEqualByComparingTo("3.4");
        assertThat(plan(shortSource, features, Map.of("source", b("3"))))
                .allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isFalse());
    }

    @Test
    void actualTramPolygonChangesTheUtilityPassageAndRoadBoundaryRemainsLegal() throws Exception {
        var input = List.of(edge("route", "source", "demand", 0, 100, 1400));
        var gas = utility("gas", "gas_pipeline", 50);
        var road = plan(input, List.of(gas, area("road")), Map.of());
        var tram = plan(input, List.of(gas, area("tram_tracks")), Map.of());
        assertComplete(road);
        assertComplete(tram);
        assertThat(road.get(0).getDepthProfile().getCrossings().get(0).getPassage())
                .isEqualTo("above");
        assertThat(tram.get(0).getDepthProfile().getCrossings().get(0).getPassage())
                .isEqualTo("below");
    }

    @Test
    void missingDiameterCannotLeavePartialProfilesAdmissible() throws Exception {
        var sized = edge("sized", "source", "joint", 0, 50, 50);
        var unsized =
                new RouteEdge(
                        "unsized",
                        "joint",
                        "demand",
                        50,
                        List.of(new RouteCoordinate(50, 0), new RouteCoordinate(100, 0)),
                        List.of(),
                        b("2"),
                        null);
        assertThat(plan(List.of(sized, unsized), List.of(), Map.of()))
                .allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isFalse());
    }

    private List<RouteEdge> plan(
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            Map<String, BigDecimal> pins) {
        return planner.planNetwork(edges, features, b(".7"), b("10"), Map.of(), pins);
    }

    private void assertComplete(List<RouteEdge> edges) {
        assertThat(edges)
                .allSatisfy(
                        e -> {
                            assertThat(e.getDepthProfile().isComplete()).isTrue();
                            assertThat(e.getDepthProfile().getIssues()).isEmpty();
                        });
    }

    static RouteEdge edge(String id, String from, String to, double x0, double x1, int diameter) {
        return new RouteEdge(
                id,
                from,
                to,
                x1 - x0,
                List.of(new RouteCoordinate(x0, 0), new RouteCoordinate(x1, 0)),
                List.of(),
                b("2"),
                diameter);
    }

    static ImportedOfficialFeature utility(String id, String type, double x) throws Exception {
        return new ImportedOfficialFeature(
                id,
                "restriction",
                new ObjectMapper().readTree("{\"restriction_type\":\"" + type + "\"}"),
                new WKTReader().read("LINESTRING (" + x + " -10, " + x + " 10)"));
    }

    private ImportedOfficialFeature area(String type) throws Exception {
        return new ImportedOfficialFeature(
                type,
                "restriction",
                new ObjectMapper().readTree("{\"restriction_type\":\"" + type + "\"}"),
                new WKTReader().read("POLYGON ((48 -2,52 -2,52 2,48 2,48 -2))"));
    }

    static BigDecimal b(String value) {
        return new BigDecimal(value);
    }
}
