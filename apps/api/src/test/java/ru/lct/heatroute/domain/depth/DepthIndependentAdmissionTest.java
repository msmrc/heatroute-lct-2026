package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteSection;

/** Analytic source-independent regressions supplied by independent review, not solver oracles. */
class DepthIndependentAdmissionTest {
    private final ContinuousDepthProfileValidator validator = new ContinuousDepthProfileValidator(new OfficialPipeCatalog());

    @Test
    void arbitraryPhysicalSectionCutsRetainExactRampPrice() {
        DepthProfileResult profile = profileAt("50");
        List<List<String>> cuts = List.of(
                List.of("0", "48", "52", "100"),
                List.of("0", "42.003", "48", "52", "100"),
                List.of("0", "41.751", "42.003", "47.999", "48", "52", "54.187", "58.249", "100"));
        for (List<String> boundaries : cuts) {
            assertThat(actualCost(profile, boundaries)).as("physical sections %s", boundaries)
                    .isEqualByComparingTo(analyticPrice(boundaries));
        }
    }

    @Test
    void rejectsClearanceDipInsideDeclaredConstantPlateauEvenWhenItsEndpointsMatch() {
        DepthProfileResult original = profileAt("50");
        List<DepthProfilePoint> points = new ArrayList<>(original.getPoints());
        points.add(point("50", "3.425"));
        points.sort(Comparator.comparing(DepthProfilePoint::getStationM));
        DepthProfileResult tampered = new DepthProfileResult(true, points, original.getCrossings(), List.of(), b("100"), b("100"));

        assertThat(issues(tampered, "50")).contains("CROSSING_PROFILE_MISMATCH");
    }

    @Test
    void rejectsDecisionForAnotherStationEvenWhenCrossingIdAndClearanceAgree() {
        assertThat(issues(profileAt("20"), "50")).contains("CROSSING_STATION_MISMATCH");
    }

    @Test
    void acceptsPhysicalPlateauAtItsActualCrossingAndAConservativeExtendedPlateau() {
        assertThat(issues(profileAt("50"), "50")).isEmpty();
        DepthProfileResult wider = new DepthProfileResult(true,
                List.of(point("0", "3"), point("39.75", "3"), point("46", "3.625"),
                        point("54", "3.625"), point("60.25", "3"), point("100", "3")),
                List.of(new DepthCrossingDecision("heat", "heat_network", "below", b("3.625"),
                        b("39.75"), b("46"), b("54"), b("60.25"), b(".5"), b(".5"))),
                List.of(), b("100"), b("100"));
        assertThat(issues(wider, "50")).isEmpty();
    }

    private List<String> issues(DepthProfileResult profile, String station) {
        List<String> codes = new ArrayList<>();
        validator.validate(b("100"), 50, List.of(new DepthCrossing("heat", "heat_network", b(station),
                b("3"), b(".125"), b(".5"), b("1.05"))), b("2.6"), b("5"), profile)
                .forEach(issue -> codes.add(issue.getCode()));
        return codes;
    }

    private DepthProfileResult profileAt(String station) {
        BigDecimal s = b(station), plateauStart = s.subtract(b("2")), plateauEnd = s.add(b("2"));
        BigDecimal rampStart = plateauStart.subtract(b("6.25")), rampEnd = plateauEnd.add(b("6.25"));
        return new DepthProfileResult(true,
                List.of(point("0", "3"), new DepthProfilePoint(rampStart, b("3")),
                        new DepthProfilePoint(plateauStart, b("3.625")), new DepthProfilePoint(plateauEnd, b("3.625")),
                        new DepthProfilePoint(rampEnd, b("3")), point("100", "3")),
                List.of(new DepthCrossingDecision("heat", "heat_network", "below", b("3.625"),
                        rampStart, plateauStart, plateauEnd, rampEnd, b(".5"), b(".5"))),
                List.of(), b("100"), b("100"));
    }

    private BigDecimal actualCost(DepthProfileResult profile, List<String> cuts) {
        List<RouteSection> sections = new ArrayList<>();
        for (int i = 1; i < cuts.size(); i++) {
            BigDecimal start = b(cuts.get(i - 1)), end = b(cuts.get(i));
            boolean special = start.compareTo(b("48")) >= 0 && end.compareTo(b("52")) <= 0;
            sections.add(new RouteSection(special ? "special" : "base", special ? "heat_network" : null,
                    special ? "heat" : null, List.of(new RouteCoordinate(start.doubleValue(), 0),
                            new RouteCoordinate(end.doubleValue(), 0)), end.subtract(start).doubleValue(), null));
        }
        RouteEdge edge = new RouteEdge("edge", "start", "end", 100,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(100, 0)), sections, b("1"), 50, profile);
        return new OfficialVariantEconomicsCalculator(new OfficialPipeCatalog(), new OfficialEconomics())
                .calculate(List.of(), List.of(edge), List.of(), ExistingNetworkReconstructionResult.empty())
                .getConstructionCost();
    }

    // Closed-form fixture integration; deliberately does not call any profile interpolation helper.
    private BigDecimal analyticPrice(List<String> physicalCuts) {
        TreeSet<BigDecimal> split = new TreeSet<>(List.of(b("0"), b("41.75"), b("48"), b("52"), b("58.25"), b("100")));
        physicalCuts.forEach(c -> split.add(b(c)));
        List<BigDecimal> cuts = new ArrayList<>(split);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 1; i < cuts.size(); i++) {
            BigDecimal a = cuts.get(i - 1), z = cuts.get(i);
            BigDecimal meanK = analyticCoefficient(a).add(analyticCoefficient(z)).divide(b("2"));
            BigDecimal special = a.compareTo(b("48")) >= 0 && z.compareTo(b("52")) <= 0 ? b("1.05") : BigDecimal.ONE;
            sum = sum.add(z.subtract(a).multiply(meanK).multiply(special).multiply(b("74023"))
                    .setScale(2, RoundingMode.HALF_UP));
        }
        return sum;
    }

    private BigDecimal analyticCoefficient(BigDecimal station) {
        if (station.compareTo(b("41.75")) <= 0 || station.compareTo(b("58.25")) >= 0) return BigDecimal.ONE;
        if (station.compareTo(b("48")) < 0) return BigDecimal.ONE.add(station.subtract(b("41.75")).multiply(b(".01")));
        if (station.compareTo(b("52")) <= 0) return b("1.0625");
        return BigDecimal.ONE.add(b("58.25").subtract(station).multiply(b(".01")));
    }

    private DepthProfilePoint point(String station, String depth) { return new DepthProfilePoint(b(station), b(depth)); }
    private BigDecimal b(String value) { return new BigDecimal(value); }
}
