package ru.lct.heatroute.domain.economics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
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
        assertThat(result.getConstructionCost()).isEqualByComparingTo("8740230");
        assertThat(result.getChamberConstructionCost()).isEqualByComparingTo("3000000");
        assertThat(result.getTieInCost()).isEqualByComparingTo("5000000");
        assertThat(result.getReconstructionCost()).isEqualByComparingTo("0");
        assertThat(result.getChamberReconstructionCost()).isEqualByComparingTo("0");
        assertThat(result.getUnconnectedPenalty()).isEqualByComparingTo("101000000");
        assertThat(result.getCalculatedCost()).isEqualByComparingTo("109740230");
        assertThat(result.getNewNetworkLength()).isEqualByComparingTo("10");
        assertThat(result.getReconstructionLength()).isEqualByComparingTo("0");
        assertThat(result.getLength()).isEqualByComparingTo("10");
        assertThat(result.getScore()).isEqualByComparingTo(
                officialEconomics.score(new BigDecimal("109740230"), new BigDecimal("10")));
    }

    @Test
    void preservesStrictProfileWhenReconstructionInputsAreUnavailable() {
        ExistingNetworkReconstructionResult reconstruction = new ExistingNetworkReconstructionResult(
                List.of(), List.of(), List.of(new ru.lct.heatroute.domain.reconstruction.ReconstructionIssue(
                        "RECONSTRUCTION_INPUT_UNAVAILABLE", "network", "missing")));

        VariantEconomics result = calculator.calculate(
                List.of(), List.of(), List.of(), reconstruction);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getScore()).isEqualByComparingTo("0.000000000");
        assertThat(result.getIncompleteReasons()).isEmpty();
    }

    @Test
    void pricesAndRanksSuppliedProfileWhenReconstructionInputsAreUnavailable() {
        ExistingNetworkReconstructionResult reconstruction = new ExistingNetworkReconstructionResult(
                List.of(), List.of(), List.of(new ru.lct.heatroute.domain.reconstruction.ReconstructionIssue(
                        "RECONSTRUCTION_INPUT_UNAVAILABLE", "network", "missing")));

        VariantEconomics result = calculator.calculate(
                List.of(), List.of(), List.of(), reconstruction, false);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getScore()).isEqualByComparingTo("0.000000000");
        assertThat(result.getIncompleteReasons()).isEmpty();
    }

    @Test
    void chargesEveryNewRayFromTheSameTieInChamberIndependently() {
        RouteNode root = new RouteNode(
                "root", "new_tie_in_chamber", coordinate(0, 0), true, true, 2, "existing");
        RouteNode first = new RouteNode(
                "first", "demand_connection", coordinate(10, 0), false, false, 0, "oks-1");
        RouteNode second = new RouteNode(
                "second", "demand_connection", coordinate(0, 10), false, false, 0, "oks-2");
        RouteEdge firstRay = edge("first-ray", "root", "first", "base", null);
        RouteEdge secondRay = edge("second-ray", "root", "second", "base", null);

        VariantEconomics result = calculator.calculate(
                List.of(root, first, second), List.of(firstRay, secondRay), List.of(),
                ExistingNetworkReconstructionResult.empty());

        assertThat(result.getTieInCost()).isEqualByComparingTo("10000000.00");
    }

    @Test
    void appliesTheLargestSpecialCoefficientForOverlappingRestrictions() {
        RouteEdge overlapping = edge("overlap", "start", "end", "special", "road+gas_pipeline");

        VariantEconomics result = calculator.calculate(
                List.of(), List.of(overlapping), List.of(), ExistingNetworkReconstructionResult.empty());

        assertThat(result.getConstructionCost()).isEqualByComparingTo(officialEconomics.newNetworkCost(
                pipeCatalog.byDiameter(50).orElseThrow(), new BigDecimal("10.000"),
                ru.lct.heatroute.domain.engineering.SpecialCrossingType.ROAD, new BigDecimal("3.0")));
    }

    @Test
    void comparesExclusiveSpurCostAgainstTheUnconnectedPenaltyWithoutReconstruction() {
        RouteNode root = new RouteNode(
                "root", "new_tie_in_chamber", coordinate(0, 0), true, true, 2, "existing");
        RouteNode demand = new RouteNode(
                "demand", "demand_connection", coordinate(10, 0), false, false, 0, "oks-1");
        RouteConnection connection = new RouteConnection(
                "oks-1", "cp-1", new BigDecimal("1.0"), "connected", null);

        assertThat(calculator.connectionCostsMoreThanPenalty(
                connection, List.of(edge("spur", "root", "demand", "base", null)), List.of(root, demand)))
                .isFalse();
        assertThat(calculator.marginalConnectionCost(
                List.of(edge("spur", "root", "demand", "base", null)), List.of(root, demand)))
                .isEqualByComparingTo("8740230.00");
    }

    @Test
    void doesNotApplyAnInventedBendCoefficient() {
        RouteSection road = new RouteSection(
                "special", "road", "road-1",
                List.of(coordinate(0, 0), coordinate(10, 0), coordinate(13, 4)), 15, null);
        RouteEdge edge = new RouteEdge(
                "bent", "start", "end", 15,
                List.of(coordinate(0, 0), coordinate(10, 0), coordinate(13, 4)), List.of(road),
                new BigDecimal("3.5"), 50);

        VariantEconomics result = calculator.calculate(
                List.of(), List.of(edge), List.of(), ExistingNetworkReconstructionResult.empty());

        var pipe = pipeCatalog.byDiameter(50).orElseThrow();
        BigDecimal expected = officialEconomics.newNetworkCost(
                        pipe, new BigDecimal("10"),
                        ru.lct.heatroute.domain.engineering.SpecialCrossingType.ROAD, new BigDecimal("3.0"))
                .add(officialEconomics.newNetworkCost(
                        pipe, new BigDecimal("5"),
                        ru.lct.heatroute.domain.engineering.SpecialCrossingType.ROAD, new BigDecimal("3.0")));
        assertThat(result.getConstructionCost()).isEqualByComparingTo(expected);
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

        assertThat(result.getConstructionCost()).isEqualByComparingTo("35942288.00");
        assertThat(result.getChamberConstructionCost()).isEqualByComparingTo("3000000.00");
        assertThat(result.getTieInCost()).isEqualByComparingTo("5000000.00");
        assertThat(result.getReconstructionCost()).isEqualByComparingTo("0.00");
        assertThat(result.getChamberReconstructionCost()).isEqualByComparingTo("0.00");
        assertThat(result.getUnconnectedPenalty()).isEqualByComparingTo("0.00");
        assertThat(result.getCalculatedCost()).isEqualByComparingTo("35942288.00");
        assertThat(result.getNewNetworkLength()).isEqualByComparingTo("145.2");
        assertThat(result.getReconstructionLength()).isEqualByComparingTo("0.0");
        assertThat(result.getLength()).isEqualByComparingTo("145.2");
        assertThat(result.getScore()).isEqualByComparingTo(
                officialEconomics.score(new BigDecimal("35942288"), new BigDecimal("145.2")));
    }

    @Test
    void pricesEveryLinearDepthIntervalInsteadOfUsingOneAverageForTheWholeEdge() {
        RouteSection base = new RouteSection(
                "base", null, null, List.of(coordinate(0, 0), coordinate(100, 0)), 100, null);
        DepthProfileResult profile = new DepthProfileResult(
                true,
                List.of(
                        point("0", "3"),
                        point("40", "3"),
                        point("50", "4"),
                        point("60", "3"),
                        point("100", "3")),
                List.of(),
                List.of(),
                new BigDecimal("100.100"),
                new BigDecimal("104.000"));
        RouteEdge edge = new RouteEdge(
                "depth-edge", "start", "end", 100,
                List.of(coordinate(0, 0), coordinate(100, 0)), List.of(base),
                new BigDecimal("3.5"), 50, profile);

        VariantEconomics result = calculator.calculate(
                List.of(),
                List.of(edge),
                List.of(),
                new ExistingNetworkReconstructionResult(List.of(), List.of(), List.of()));

        var pipe = pipeCatalog.byDiameter(50).orElseThrow();
        BigDecimal expected = officialEconomics.newNetworkCost(
                        pipe, new BigDecimal("80"),
                        ru.lct.heatroute.domain.engineering.SpecialCrossingType.BASE,
                        new BigDecimal("3"))
                .add(officialEconomics.newNetworkCost(
                        pipe, new BigDecimal("20"),
                        ru.lct.heatroute.domain.engineering.SpecialCrossingType.BASE,
                        new BigDecimal("3.5")));

        assertThat(result.getConstructionCost()).isEqualByComparingTo(expected);
    }

    @Test
    void ignoresSubMillimetreMappedIntervalsWhenPricingDepth() {
        RouteSection base = new RouteSection(
                "base", null, null,
                List.of(coordinate(0, 0), coordinate(1, 0), coordinate(10_000, 0)), 1, null);
        DepthProfileResult profile = new DepthProfileResult(
                true,
                List.of(point("0", "3"), point("1", "3")),
                List.of(), List.of(), BigDecimal.ONE, BigDecimal.ONE);
        RouteEdge edge = new RouteEdge(
                "rounded-depth-edge", "start", "end", 1,
                base.getCoordinates(), List.of(base), new BigDecimal("1"), 50, profile);

        VariantEconomics result = calculator.calculate(
                List.of(), List.of(edge), List.of(), ExistingNetworkReconstructionResult.empty());

        assertThat(result.getConstructionCost()).isPositive();
    }

    private DepthProfilePoint point(String station, String depth) {
        return new DepthProfilePoint(new BigDecimal(station), new BigDecimal(depth));
    }

    private RouteCoordinate coordinate(double x, double y) {
        return new RouteCoordinate(x, y);
    }

    private RouteEdge edge(
            String id, String upstream, String downstream, String kind, String restrictionType) {
        return new RouteEdge(
                id, upstream, downstream, 10,
                List.of(coordinate(0, 0), coordinate(10, 0)),
                List.of(new RouteSection(kind, restrictionType, "restriction",
                        List.of(coordinate(0, 0), coordinate(10, 0)), 10, null)),
                new BigDecimal("3.5"), 50);
    }
}
