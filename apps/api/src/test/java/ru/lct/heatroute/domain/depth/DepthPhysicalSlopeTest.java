package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;

class DepthPhysicalSlopeTest {
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialDepthPlanner planner =
            new OfficialDepthPlanner(
                    new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
                    new OfficialDepthOptimizer(pipes, new OfficialEconomics()),
                    new OfficialDepthProfileValidator(pipes));

    @Test
    void physicallyFeasiblePinnedRiseSurvivesTwoHundredRoundedTechnicalEdges() {
        for (double[] direction : new double[][] {{.039, .011}, {.040, .010}}) {
            List<RouteEdge> input = chain(direction[0], direction[1]);
            List<RouteEdge> result =
                    planner.planNetwork(
                            input,
                            List.of(),
                            b(".7"),
                            b("5"),
                            Map.of(),
                            Map.of("n0", b("3"), "n200", b("3.8")));
            assertThat(result)
                    .allSatisfy(
                            edge -> {
                                assertThat(edge.getDepthProfile().isComplete()).isTrue();
                                var a = edge.getCoordinates().get(0);
                                var z = edge.getCoordinates().get(1);
                                BigDecimal physical =
                                        BigDecimal.valueOf(
                                                Math.hypot(
                                                        z.getXM().subtract(a.getXM()).doubleValue(),
                                                        z.getYM()
                                                                .subtract(a.getYM())
                                                                .doubleValue()));
                                var points = edge.getDepthProfile().getPoints();
                                for (int i = 1; i < points.size(); i++) {
                                    BigDecimal rise =
                                            points.get(i)
                                                    .getDepthM()
                                                    .subtract(points.get(i - 1).getDepthM())
                                                    .abs();
                                    BigDecimal run =
                                            points.get(i)
                                                    .getStationM()
                                                    .subtract(points.get(i - 1).getStationM());
                                    assertThat(rise.multiply(edge.getLengthM()))
                                            .isLessThanOrEqualTo(
                                                    physical.multiply(run).multiply(b(".1")));
                                }
                            });
            assertThat(result.get(0).getDepthProfile().depthAt(b("0"))).isEqualByComparingTo("3");
            assertThat(result.get(199).getDepthProfile().depthAt(b(".041")))
                    .isEqualByComparingTo("3.8");
        }
    }

    @Test
    void independentNetworkAdmissionRejectsTheFormerStoredStationOnlyRamp() {
        RouteEdge edge = chain(.039, .011).get(0);
        DepthProfileResult forged =
                new DepthProfileResult(
                        true,
                        List.of(
                                new DepthProfilePoint(b("0"), b("3")),
                                new DepthProfilePoint(b(".001"), b("3")),
                                new DepthProfilePoint(b(".041"), b("3.004"))),
                        List.of(),
                        List.of(),
                        b(".041"),
                        b(".041"));
        RouteEdge saved =
                new RouteEdge(
                        edge.getId(),
                        edge.getUpstreamNodeId(),
                        edge.getDownstreamNodeId(),
                        edge.getLengthM().doubleValue(),
                        edge.getCoordinates(),
                        edge.getSections(),
                        edge.getFlowTph(),
                        edge.getDiameter(),
                        forged);
        assertThat(
                        new DepthNetworkAssessment(pipes)
                                .assess(
                                        List.of(saved),
                                        List.of(),
                                        b(".7"),
                                        b("5"),
                                        Map.of(),
                                        Map.of())
                                .getIssues())
                .extracting(DepthProfileIssue::getCode)
                .contains("PROFILE_PHYSICAL_SLOPE_EXCEEDED");
    }

    @Test
    void exactMillimetreSlopeBoundaryDoesNotDependOnLargeMetricOrigin() {
        for (double origin : new double[]{0, 400000, 6000000}) {
            RouteEdge edge = new RouteEdge("tiny", "a", "b", .01,
                    List.of(new RouteCoordinate(origin + .01, 0), new RouteCoordinate(origin + .02, 0)),
                    List.of(), BigDecimal.ONE, 50);
            var result = planner.planNetwork(List.of(edge), List.of(), b(".7"), b("5"), Map.of(),
                    Map.of("a", b("3"), "b", b("3.001")));
            assertThat(result.get(0).getDepthProfile().isComplete()).as("origin=%s", origin).isTrue();
        }
    }

    private List<RouteEdge> chain(double dx, double dy) {
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            edges.add(
                    new RouteEdge(
                            "e" + i,
                            "n" + i,
                            "n" + (i + 1),
                            Math.hypot(dx, dy),
                            List.of(
                                    new RouteCoordinate(i * dx, i * dy),
                                    new RouteCoordinate((i + 1) * dx, (i + 1) * dy)),
                            List.of(),
                            BigDecimal.ONE,
                            50));
        }
        return edges;
    }

    private BigDecimal b(String value) {
        return new BigDecimal(value);
    }
}
