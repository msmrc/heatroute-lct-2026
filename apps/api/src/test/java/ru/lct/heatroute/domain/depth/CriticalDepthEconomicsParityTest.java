package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteSection;

class CriticalDepthEconomicsParityTest {
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final CriticalDepthSolver solver = new CriticalDepthSolver(pipes);
    private final OfficialVariantEconomicsCalculator economics =
            new OfficialVariantEconomicsCalculator(pipes, new OfficialEconomics());

    @Test
    void legalGasAboveMatchesProductionEconomicsAndExactWholeEdgePrice() {
        List<DepthCrossing> crossings = List.of(crossing("gas", "gas_pipeline", "50", "2.8", ".4", ".2", "1.25"));
        DepthProfileResult result = solver.optimize(b("100"), 50, crossings, b(".7"), b("10"));
        assertParity(result, crossings);
        assertThat(actualCost(result, crossings)).isEqualByComparingTo("7476323.00");
    }

    @Test
    void mixedDepthTransitionMatchesProductionEconomics() {
        List<DepthCrossing> crossings = List.of(
                crossing("first", "heat_network", "40", "3", ".125", ".5", "1.05"),
                crossing("second", "heat_network", "50", "3", ".8", ".5", "1.05"));
        DepthProfileResult result = solver.optimize(b("100"), 50, crossings, b("2.6"), b("5"));
        assertParity(result, crossings);
    }

    @Test
    void oddMillimetreDepthRetainsExactMeanCoefficientInProductionEconomics() {
        List<DepthCrossing> crossings = List.of(crossing("heat", "heat_network", "50", "3", ".125", ".5", "1.05"));
        DepthProfileResult result = solver.optimize(b("100"), 50, crossings, b("2.6"), b("5"));
        assertThat(result.getCrossings().get(0).getDepthM()).isEqualByComparingTo("3.625");
        assertParity(result, crossings);
    }

    @Test
    void lawfulLinearProfileCrossingThreeMetresIntegratesTheCoefficientKinkExactly() {
        DepthProfileResult result = new DepthProfileResult(true,
                List.of(new DepthProfilePoint(b("0"), b("2")), new DepthProfilePoint(b("20"), b("4")),
                        new DepthProfilePoint(b("100"), b("4"))), List.of(), List.of(), b("100"), b("108.5"));
        // First10m cost10; next10m meanK1.05; remaining80m K1.10. Exact total108.5*74023.
        assertThat(actualCost(result, List.of())).isEqualByComparingTo("8031495.50");
    }

    private void assertParity(DepthProfileResult result, List<DepthCrossing> crossings) {
        assertThat(result.isComplete()).isTrue();
        List<RouteSection> pieces = sections(result, crossings, true);
        BigDecimal expected = BigDecimal.ZERO;
        for (RouteSection piece : pieces) {
            BigDecimal start = piece.getCoordinates().get(0).getXM();
            BigDecimal end = piece.getCoordinates().get(1).getXM();
            BigDecimal kStart = multiplier(result.depthAt(start)), kEnd = multiplier(result.depthAt(end));
            BigDecimal special = crossings.stream()
                    .filter(c -> start.compareTo(c.getStationM().subtract(b("2"))) >= 0
                            && end.compareTo(c.getStationM().add(b("2"))) <= 0)
                    .map(DepthCrossing::getSpecialCostMultiplier).max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
            expected = expected.add(end.subtract(start).multiply(kStart.add(kEnd).divide(b("2")))
                    .multiply(special).multiply(b("74023")).setScale(2, RoundingMode.HALF_UP));
        }
        assertThat(actualCost(result, crossings)).as("actual production economics versus exact endpoint-coefficient mean")
                .isEqualByComparingTo(expected);
    }

    private BigDecimal actualCost(DepthProfileResult result, List<DepthCrossing> crossings) {
        RouteEdge edge = new RouteEdge("edge", "start", "end", 100,
                List.of(coordinate(b("0")), coordinate(b("100"))), sections(result, crossings, false),
                b("1"), 50, result);
        return economics.calculate(List.of(), List.of(edge), List.of(), ExistingNetworkReconstructionResult.empty())
                .getConstructionCost();
    }

    private List<RouteSection> sections(DepthProfileResult profile, List<DepthCrossing> crossings, boolean splitProfile) {
        TreeSet<BigDecimal> cuts = new TreeSet<>(List.of(b("0"), b("100")));
        for (DepthCrossing crossing : crossings) { cuts.add(crossing.getStationM().subtract(b("2"))); cuts.add(crossing.getStationM().add(b("2"))); }
        if (splitProfile) profile.getPoints().forEach(p -> cuts.add(p.getStationM()));
        List<BigDecimal> ordered = new ArrayList<>(cuts);
        List<RouteSection> result = new ArrayList<>();
        for (int i = 1; i < ordered.size(); i++) {
            BigDecimal start = ordered.get(i - 1), end = ordered.get(i);
            String types = crossings.stream().filter(c -> start.compareTo(c.getStationM().subtract(b("2"))) >= 0
                    && end.compareTo(c.getStationM().add(b("2"))) <= 0).map(DepthCrossing::getType)
                    .sorted().collect(Collectors.joining("+"));
            result.add(new RouteSection(types.isEmpty() ? "base" : "special", types.isEmpty() ? null : types,
                    types.isEmpty() ? null : "restrictions", List.of(coordinate(start), coordinate(end)),
                    end.subtract(start).doubleValue(), null));
        }
        return result;
    }

    private BigDecimal multiplier(BigDecimal depth) { return BigDecimal.ONE.add(depth.subtract(b("3")).max(BigDecimal.ZERO).multiply(b(".1"))); }
    private RouteCoordinate coordinate(BigDecimal x) { return new RouteCoordinate(x.doubleValue(), 0); }
    private DepthCrossing crossing(String id, String type, String station, String top, String height, String clearance, String coefficient) {
        return new DepthCrossing(id, type, b(station), b(top), b(height), b(clearance), b(coefficient));
    }
    private BigDecimal b(String value) { return new BigDecimal(value); }
}
