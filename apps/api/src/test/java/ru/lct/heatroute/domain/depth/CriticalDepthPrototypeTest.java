package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

class CriticalDepthPrototypeTest {
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final CriticalDepthSolver solver = new CriticalDepthSolver(pipes);
    private final ContinuousDepthProfileValidator validator = new ContinuousDepthProfileValidator(pipes);

    @Test
    void roundsAboveDownAndBelowUpWithoutClearanceOrSlopeToleranceExpansion() {
        DepthCrossing crossing = crossing("gas", "gas_pipeline", "50", "2.8", ".4", ".2006", "1.25");
        DepthProfileResult above = run(List.of(crossing), ".7", "10");
        DepthProfileResult below = run(List.of(crossing), "2.6", "10");
        assertThat(above.getCrossings().get(0).getDepthM()).isEqualByComparingTo("2.474");
        assertThat(below.getCrossings().get(0).getDepthM()).isEqualByComparingTo("3.401");
        assertThat(above.getCrossings().get(0).getVerticalClearanceM()).isEqualByComparingTo(".201");
        assertThat(below.getCrossings().get(0).getVerticalClearanceM()).isEqualByComparingTo(".201");
    }

    @Test
    void propagatesWithActualStoredGapInsteadOfRoundingSlopeBudgetUp() {
        List<DepthCrossing> crossings = List.of(
                crossing("first", "heat_network", "40", "3", ".125", ".5", "1.05"),
                crossing("second", "heat_network", "50.003", "3", ".8", ".5", "1.05"));
        DepthProfileResult result = run(crossings, "2.6", "5");
        assertThat(result.getCrossings()).extracting(DepthCrossingDecision::getDepthM)
                .containsExactly(b("3.700"), b("4.300"));
        assertThat(result.depthAt(b("42"))).isEqualByComparingTo("3.7");
        assertThat(result.depthAt(b("48.003"))).isEqualByComparingTo("4.3");
    }

    @Test
    void overlappingPlateausShareDepthAndUseMaximumSpecialCoefficient() {
        List<DepthCrossing> crossings = List.of(
                crossing("heat", "heat_network", "50", "3", ".125", ".5", "1.05"),
                crossing("gas", "gas_pipeline", "52", "2.8", ".4", ".2", "1.25"));
        DepthProfileResult result = run(crossings, "2.6", "5");
        assertThat(result.getCrossings()).extracting(DepthCrossingDecision::getDepthM)
                .containsExactly(b("3.625"), b("3.625"));
        assertThat(result.depthAt(b("48"))).isEqualByComparingTo("3.625");
        assertThat(result.depthAt(b("54"))).isEqualByComparingTo("3.625");
        // Ramp surcharge .390625; six-metre plateau depth surcharge .375;
        // special surcharge at 1.0625 is 2*.05 +4*.25, never a product of overlaps.
        assertThat(CriticalDepthSolver.horizontalWeightedMeters(result, crossings))
                .isEqualByComparingTo("101.934375");
    }

    @Test
    void crossingOrderAndIdentifiersDoNotChangeProfileOrCost() {
        List<DepthCrossing> forward = List.of(
                crossing("a", "heat_network", "40", "3", ".125", ".5", "1.05"),
                crossing("z", "heat_network", "50", "3", ".8", ".5", "1.05"));
        List<DepthCrossing> reverse = List.of(
                crossing("a", "heat_network", "50", "3", ".8", ".5", "1.05"),
                crossing("z", "heat_network", "40", "3", ".125", ".5", "1.05"));
        DepthProfileResult a = run(forward, "2.6", "5"), b = run(reverse, "2.6", "5");
        assertThat(b.getPoints()).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(a.getPoints());
        assertThat(b.getDepthAdjustedCostMeters()).isEqualByComparingTo(a.getDepthAdjustedCostMeters());
    }

    @Test
    void freeShortEndpointUsesOnlyActuallyAvailableRampWithoutInventingDepthJump() {
        DepthCrossing crossing = crossing("power", "power_cable", "4", "2.7", ".2", ".5", "1.15");
        DepthProfileResult result = run(List.of(crossing), "2.6", "5");
        assertThat(result.getPoints().get(0).getDepthM()).isEqualByComparingTo("3.2");
        assertThat(result.depthAt(b("2"))).isEqualByComparingTo("3.4");
        assertThat(result.getPoints()).anySatisfy(p -> {
            assertThat(p.getStationM()).isEqualByComparingTo("10");
            assertThat(p.getDepthM()).isEqualByComparingTo("3");
        });
    }

    @Test
    void millimetreResidualGapUsesSafeFlatValleyInsteadOfAnInvalidRoundedV() {
        List<DepthCrossing> crossings = List.of(
                crossing("first", "heat_network", "40", "3", ".125", ".5", "1.05"),
                crossing("second", "heat_network", "44.019", "3", ".125", ".5", "1.05"));
        DepthProfileResult result = run(crossings, "2.6", "5");
        assertThat(result.depthAt(b("42"))).isEqualByComparingTo("3.625");
        assertThat(result.depthAt(b("42.019"))).isEqualByComparingTo("3.625");
        assertThat(result.getPoints().stream().filter(p -> p.getStationM().compareTo(b("42")) > 0
                && p.getStationM().compareTo(b("42.019")) < 0))
                .allSatisfy(p -> assertThat(p.getDepthM()).isGreaterThanOrEqualTo(b("3.625")));
    }

    @Test
    void currentOfficialPipeCatalogueMaintainsClearanceAndCostIntegral() {
        for (var pipe : pipes.entries()) {
            List<DepthCrossing> crossings = List.of(
                    crossing("power", "power_cable", "40", "2.7", ".2", ".5", "1.15"),
                    crossing("gas", "gas_pipeline", "50", "2.8", ".4", ".2", "1.25"));
            DepthProfileResult result = solver.optimize(b("100"), pipe.getDiameter(), crossings, b(".7"), b("10"));
            assertThat(result.isComplete()).as("DU %s", pipe.getDiameter()).isTrue();
            assertThat(validator.validate(b("100"), pipe.getDiameter(), crossings, b(".7"), b("10"), result))
                    .as("DU %s", pipe.getDiameter()).isEmpty();
            strictStoredSlopes(result);
            assertThat(result.getDepthAdjustedCostMeters()).isEqualByComparingTo(
                    CriticalDepthSolver.horizontalWeightedMeters(result, crossings).setScale(3, RoundingMode.HALF_UP));
        }
    }

    private DepthProfileResult run(List<DepthCrossing> crossings, String min, String max) {
        DepthProfileResult result = solver.optimize(b("100"), 50, crossings, b(min), b(max));
        assertThat(result.isComplete()).isTrue();
        assertThat(validator.validate(b("100"), 50, crossings, b(min), b(max), result)).isEmpty();
        strictStoredSlopes(result);
        return result;
    }

    private void strictStoredSlopes(DepthProfileResult result) {
        for (int i = 1; i < result.getPoints().size(); i++) {
            DepthProfilePoint left = result.getPoints().get(i - 1), right = result.getPoints().get(i);
            assertThat(right.getStationM()).isGreaterThan(left.getStationM());
            assertThat(right.getDepthM().subtract(left.getDepthM()).abs())
                    .isLessThanOrEqualTo(right.getStationM().subtract(left.getStationM()).multiply(b(".1")));
            assertThat(left.getDepthM().subtract(b("3")).signum() * right.getDepthM().subtract(b("3")).signum())
                    .as("every crossing of3.0 must be a stored breakpoint").isGreaterThanOrEqualTo(0);
        }
    }

    private DepthCrossing crossing(String id, String type, String station, String top, String height,
            String clearance, String coefficient) {
        return new DepthCrossing(id, type, b(station), b(top), b(height), b(clearance), b(coefficient));
    }

    private BigDecimal b(String value) { return new BigDecimal(value); }
}
