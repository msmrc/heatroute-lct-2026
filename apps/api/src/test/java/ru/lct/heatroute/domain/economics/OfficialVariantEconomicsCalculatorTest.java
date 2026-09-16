package ru.lct.heatroute.domain.economics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ChamberReconstruction;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.reconstruction.NetworkReconstructionSection;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteSection;

class OfficialVariantEconomicsCalculatorTest {
    private final OfficialPipeCatalog pipeCatalog = new OfficialPipeCatalog();
    private final OfficialEconomics officialEconomics = new OfficialEconomics();
    private final OfficialVariantEconomicsCalculator calculator =
            new OfficialVariantEconomicsCalculator(pipeCatalog, officialEconomics);

    @Test
    void calculatesEveryOfficialCostComponentAndExactTotal() {
        RouteNode root = new RouteNode(
                "root", "new_tie_in_chamber", coordinate(0, 0), true, true, 2, "existing");
        RouteNode demand = new RouteNode(
                "demand", "demand_connection", coordinate(10, 0), false, false, 0, "oks-1");
        RouteSection base = new RouteSection(
                "base", null, null, List.of(coordinate(0, 0), coordinate(10, 0)), 10, null);
        RouteEdge edge = new RouteEdge(
                "edge", "root", "demand", 10,
                List.of(coordinate(0, 0), coordinate(10, 0)), List.of(base),
                new BigDecimal("3.5"), 50);
        NetworkReconstructionSection section = new NetworkReconstructionSection(
                "recon", "existing", List.of(coordinate(0, 0), coordinate(5, 0)), 5,
                new BigDecimal("2"), new BigDecimal("3.5"), new BigDecimal("5.5"),
                50, 65, true);
        ChamberReconstruction chamber = new ChamberReconstruction(
                "chamber", coordinate(0, 0), new BigDecimal("3.5"),
                new BigDecimal("5.5"), 50, 65);
        ExistingNetworkReconstructionResult reconstruction = new ExistingNetworkReconstructionResult(
                List.of(section), List.of(chamber), List.of());

        VariantEconomics result = calculator.calculate(
                List.of(root, demand),
                List.of(edge),
                List.of(
                        new RouteConnection("oks-1", "cp-1", new BigDecimal("3.5"), "connected", null),
                        new RouteConnection("oks-2", "cp-2", new BigDecimal("2"), "no_route", "NO_ROUTE")),
                reconstruction);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getConstructionCost()).isEqualByComparingTo("740230");
        assertThat(result.getChamberConstructionCost()).isEqualByComparingTo("3000000");
        assertThat(result.getTieInCost()).isEqualByComparingTo("5000000");
        assertThat(result.getReconstructionCost()).isEqualByComparingTo("549945");
        assertThat(result.getChamberReconstructionCost()).isEqualByComparingTo("3000000");
        assertThat(result.getUnconnectedPenalty()).isEqualByComparingTo("101000000");
        assertThat(result.getCalculatedCost()).isEqualByComparingTo("113290175");
        assertThat(result.getNewNetworkLength()).isEqualByComparingTo("10");
        assertThat(result.getReconstructionLength()).isEqualByComparingTo("5");
        assertThat(result.getLength()).isEqualByComparingTo("15");
        assertThat(result.getScore()).isEqualByComparingTo(
                officialEconomics.score(new BigDecimal("113290175"), new BigDecimal("15")));
    }

    @Test
    void withholdsFinalScoreWhenReconstructionInputsAreUnavailable() {
        ExistingNetworkReconstructionResult reconstruction = new ExistingNetworkReconstructionResult(
                List.of(), List.of(), List.of(new ru.lct.heatroute.domain.reconstruction.ReconstructionIssue(
                        "RECONSTRUCTION_INPUT_UNAVAILABLE", "network", "missing")));

        VariantEconomics result = calculator.calculate(
                List.of(), List.of(), List.of(), reconstruction);

        assertThat(result.isComplete()).isFalse();
        assertThat(result.getScore()).isNull();
        assertThat(result.getIncompleteReasons()).containsExactly("RECONSTRUCTION_INPUT_UNAVAILABLE");
    }

    @Test
    void reproducesAppendixExampleFromNormativeRatesAndFormulas() {
        RouteNode tieIn = new RouteNode(
                "tie", "new_tie_in_chamber", coordinate(0, 0), true, true, 2, "net-12");
        RouteNode demand = new RouteNode(
                "node", "demand_connection", coordinate(145.2, 0), false, false, 0, "oks-1");
        RouteSection road = new RouteSection(
                "special", "road", "road-1",
                List.of(coordinate(0, 0), coordinate(145.2, 0)), 145.2, 90.0);
        RouteEdge newNetwork = new RouteEdge(
                "new-1", "tie", "node", 145.2,
                List.of(coordinate(0, 0), coordinate(145.2, 0)), List.of(road),
                new BigDecimal("80.0"), 200);
        NetworkReconstructionSection reconstructionSection = new NetworkReconstructionSection(
                "recon-1", "net-12", List.of(coordinate(-75, 0), coordinate(0, 0)), 75,
                new BigDecimal("100.0"), new BigDecimal("80.0"), new BigDecimal("180.0"),
                150, 250, true);
        ExistingNetworkReconstructionResult reconstruction = new ExistingNetworkReconstructionResult(
                List.of(reconstructionSection), List.of(), List.of());

        VariantEconomics result = calculator.calculate(
                List.of(tieIn, demand),
                List.of(newNetwork),
                List.of(new RouteConnection(
                        "oks-1", "cp-1", new BigDecimal("80.0"), "connected", null)),
                reconstruction);

        assertThat(result.getConstructionCost()).isEqualByComparingTo("27942288.00");
        assertThat(result.getChamberConstructionCost()).isEqualByComparingTo("3000000.00");
        assertThat(result.getTieInCost()).isEqualByComparingTo("5000000.00");
        assertThat(result.getReconstructionCost()).isEqualByComparingTo("15152250.00");
        assertThat(result.getChamberReconstructionCost()).isEqualByComparingTo("0.00");
        assertThat(result.getUnconnectedPenalty()).isEqualByComparingTo("0.00");
        assertThat(result.getCalculatedCost()).isEqualByComparingTo("51094538.00");
        assertThat(result.getNewNetworkLength()).isEqualByComparingTo("145.2");
        assertThat(result.getReconstructionLength()).isEqualByComparingTo("75.0");
        assertThat(result.getLength()).isEqualByComparingTo("220.2");
        assertThat(result.getScore()).isEqualByComparingTo("2.091247064");
        assertThat(result.getScore().setScale(3, RoundingMode.HALF_UP)).isEqualByComparingTo("2.091");

        // Section 10.8 labels its numbers illustrative. Its stated component values differ from
        // the normative tables by 19 and 33 rubles; the engine follows sections 4, 5, 8 and 9.
        assertThat(result.getConstructionCost().subtract(new BigDecimal("27942307")))
                .isEqualByComparingTo("-19");
        assertThat(result.getReconstructionCost().subtract(new BigDecimal("15152283")))
                .isEqualByComparingTo("-33");
    }

    private RouteCoordinate coordinate(double x, double y) {
        return new RouteCoordinate(x, y);
    }
}
