package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/** Ignored source94 audit gates: appendix section5, no discretization and XY cost. */
class DepthContinuousContractTest {
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final CriticalDepthSolver optimizer = new CriticalDepthSolver(pipes);
    private final ContinuousDepthProfileValidator validator = new ContinuousDepthProfileValidator(pipes);

    @Test
    void acceptsLegalThreePointEightWithoutInventedDepthGrid() {
        DepthCrossing crossing = crossing("power", "power_cable", "50", ".8", "2.5", ".5", "1.15");
        DepthProfileResult witness = constantCrossing(crossing, "below", "3.8");

        assertThat(validator.validate(b("100"), 50, List.of(crossing), b(".7"), b("3.9"), witness))
                .extracting(DepthProfileIssue::getCode).isEmpty();
    }

    @Test
    void findsOfficialPowerPassageInsideExplicitUserBoundsWithoutGrid() {
        DepthCrossing crossing = crossing("official-power", "power_cable", "50", "2.7", ".2", ".5", "1.15");
        // The official utility values give below>=3.4; above<=2.075 is excluded by user min2.6.
        DepthProfileResult result = optimizer.optimize(b("100"), 50, List.of(crossing), b("2.6"), b("3.5"));

        assertThat(result.isComplete()).as("legal below3.4 must survive explicit range[2.6,3.5]").isTrue();
        assertThat(result.getCrossings()).singleElement().satisfies(decision -> {
            assertThat(decision.getPassage()).isEqualTo("below");
            assertThat(decision.getDepthM()).isEqualByComparingTo("3.4");
        });
        assertThat(validator.validate(b("100"), 50, List.of(crossing), b("2.6"), b("3.5"), result))
                .isEmpty();
    }

    @Test
    void comparesWholeHorizontalProfileCostInsteadOfChangedWindowSize() {
        DepthCrossing gas = crossing("gas", "gas_pipeline", "50", "2.8", ".4", ".2", "1.25");
        DepthProfileResult witness = constantCrossing(gas, "above", "2.2");
        assertThat(validator.validate(b("100"), 50, List.of(gas), b(".7"), b("10"), witness)).isEmpty();
        assertThat(horizontalCost(witness, List.of(gas))).isEqualByComparingTo("101.000");

        DepthProfileResult result = optimizer.optimize(b("100"), 50, List.of(gas), b(".7"), b("10"));

        assertThat(result.isComplete()).isTrue();
        assertThat(horizontalCost(result, List.of(gas))).as("chosen cost cannot exceed an admitted witness")
                .isLessThanOrEqualTo(horizontalCost(witness, List.of(gas)));
    }

    @Test
    void reportedCostMetersUseHorizontalProjectionWhileThreeDLengthRemainsDiagnostic() {
        DepthCrossing gas = crossing("gas", "gas_pipeline", "50", "2.8", ".4", ".2", "1.25");
        DepthProfileResult result = optimizer.optimize(b("100"), 50, List.of(gas), b(".7"), b("10"));

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getProfileLength3dM()).isGreaterThan(b("100"));
        assertThat(result.getDepthAdjustedCostMeters())
                .isEqualByComparingTo(horizontalCost(result, List.of(gas)).setScale(3, RoundingMode.HALF_UP));
    }

    @Test
    void nearbyOfficialHeatCrossingsAllowDirectSlopeBetweenDifferentCriticalDepths() {
        DepthCrossing first = crossing("heat-DU50", "heat_network", "40", "3", ".125", ".5", "1.05");
        DepthCrossing second = crossing("heat-DU600", "heat_network", "50", "3", ".800", ".5", "1.05");
        List<DepthCrossing> crossings = List.of(first, second);
        // First local below bound3.625 is raised to3.7 by the second bound4.3 and the6m gap.
        DepthProfileResult witness = new DepthProfileResult(true,
                List.of(point("0", "3"), point("31", "3"), point("38", "3.7"),
                        point("42", "3.7"), point("48", "4.3"), point("52", "4.3"),
                        point("65", "3"), point("100", "3")),
                List.of(decision(first, "below", "3.7", "31", "38", "42", "48"),
                        decision(second, "below", "4.3", "42", "48", "52", "65")),
                List.of(), b("100"), b("102.93"));
        assertThat(validator.validate(b("100"), 50, crossings, b("2.6"), b("5"), witness))
                .extracting(DepthProfileIssue::getCode).filteredOn(code -> !"DEPTH_STEP_INVALID".equals(code)).isEmpty();
        assertThat(horizontalCost(witness, crossings)).isEqualByComparingTo("102.93");

        DepthProfileResult result = optimizer.optimize(b("100"), 50, crossings, b("2.6"), b("5"));

        assertThat(result.isComplete()).isTrue();
        assertThat(horizontalCost(result, crossings)).as("legal mixed-depth bridge is cheaper than one deep plateau")
                .isLessThanOrEqualTo(horizontalCost(witness, crossings));
    }

    @Test
    void explicitMaximumStillRejectsARealOutOfRangePassage() {
        DepthCrossing crossing = crossing("power", "power_cable", "50", ".8", "2.5", ".5", "1.15");
        DepthProfileResult witness = constantCrossing(crossing, "below", "3.8");

        assertThat(validator.validate(b("100"), 50, List.of(crossing), b(".7"), b("3.7"), witness))
                .extracting(DepthProfileIssue::getCode).contains("PROFILE_DEPTH_RANGE");
        assertThat(optimizer.optimize(b("100"), 50, List.of(crossing), b(".7"), b("3.7")).isComplete())
                .isFalse();
    }

    @Test
    void noUtilityRetainsOrdinaryProfileAndExactlyHorizontalCost() {
        DepthProfileResult result = optimizer.optimize(b("100"), 50, List.of(), b(".7"), b("10"));

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getCrossings()).isEmpty();
        assertThat(result.getPoints()).extracting(DepthProfilePoint::getDepthM)
                .containsExactly(b("3.000"), b("3.000"));
        assertThat(result.getDepthAdjustedCostMeters()).isEqualByComparingTo("100");
        assertThat(result.getProfileLength3dM()).isEqualByComparingTo("100");
    }

    @Test
    void independentCostOracleUsesMaximumSpecialMultiplierOnOverlap() {
        DepthCrossing power = crossing("power", "power_cable", "50", "2.7", ".2", ".5", "1.15");
        DepthCrossing gas = crossing("gas", "gas_pipeline", "50", "2.8", ".4", ".2", "1.25");
        DepthProfileResult onlyPower = constantCrossing(power, "above", "1.7");
        DepthProfileResult both = new DepthProfileResult(true, onlyPower.getPoints(),
                List.of(decision(power, "above", "1.7", "35", "48", "52", "65"),
                        decision(gas, "above", "1.7", "35", "48", "52", "65")),
                List.of(), b("100"), b("101"));

        assertThat(horizontalCost(both, List.of(power, gas))).isEqualByComparingTo("101");
        assertThat(validator.validate(b("100"), 50, List.of(power, gas), b(".7"), b("10"), both)).isEmpty();
    }

    private DepthProfileResult constantCrossing(DepthCrossing crossing, String passage, String depth) {
        BigDecimal ramp = b(depth).subtract(b("3")).abs().divide(b(".1"));
        String start = b("48").subtract(ramp).toPlainString();
        String end = b("52").add(ramp).toPlainString();
        return new DepthProfileResult(true, List.of(point("0", "3"), point(start, "3"),
                point("48", depth), point("52", depth), point(end, "3"), point("100", "3")),
                List.of(decision(crossing, passage, depth, start, "48", "52", end)),
                List.of(), b("100"), b("100"));
    }

    private DepthCrossingDecision decision(DepthCrossing c, String passage, String depth,
            String rampStart, String plateauStart, String plateauEnd, String rampEnd) {
        BigDecimal clearance = "above".equals(passage)
                ? c.getExistingTopDepthM().subtract(b(depth)).subtract(b(".125"))
                : b(depth).subtract(c.getExistingTopDepthM()).subtract(c.getExistingHeightM());
        return new DepthCrossingDecision(c.getId(), c.getType(), passage, b(depth), b(rampStart),
                b(plateauStart), b(plateauEnd), b(rampEnd), clearance, c.getMinimumVerticalClearanceM());
    }

    /** Independent integral: split at profile vertices, coefficient kink3.0, all special endpoints. */
    private BigDecimal horizontalCost(DepthProfileResult profile, List<DepthCrossing> crossings) {
        Map<String, DepthCrossing> byId = crossings.stream().collect(Collectors.toMap(DepthCrossing::getId, c -> c));
        TreeSet<BigDecimal> cuts = new TreeSet<>();
        profile.getPoints().forEach(p -> cuts.add(p.getStationM()));
        profile.getCrossings().forEach(c -> { cuts.add(c.getPlateauStartM()); cuts.add(c.getPlateauEndM()); });
        for (int i = 1; i < profile.getPoints().size(); i++) {
            DepthProfilePoint left = profile.getPoints().get(i - 1);
            DepthProfilePoint right = profile.getPoints().get(i);
            if (left.getDepthM().subtract(b("3")).signum() * right.getDepthM().subtract(b("3")).signum() < 0) {
                cuts.add(left.getStationM().add(right.getStationM().subtract(left.getStationM())
                        .multiply(b("3").subtract(left.getDepthM()))
                        .divide(right.getDepthM().subtract(left.getDepthM()), 12, RoundingMode.HALF_UP)));
            }
        }
        List<BigDecimal> ordered = new ArrayList<>(cuts);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 1; i < ordered.size(); i++) {
            BigDecimal start = ordered.get(i - 1), end = ordered.get(i);
            BigDecimal special = profile.getCrossings().stream()
                    .filter(c -> start.compareTo(c.getPlateauStartM()) >= 0 && end.compareTo(c.getPlateauEndM()) <= 0)
                    .map(c -> byId.get(c.getCrossingId()).getSpecialCostMultiplier())
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
            BigDecimal average = multiplier(depthAt(profile, start)).add(multiplier(depthAt(profile, end)))
                    .divide(b("2"));
            total = total.add(end.subtract(start).multiply(average).multiply(special));
        }
        return total;
    }

    private BigDecimal depthAt(DepthProfileResult profile, BigDecimal station) {
        for (int i = 1; i < profile.getPoints().size(); i++) {
            DepthProfilePoint left = profile.getPoints().get(i - 1), right = profile.getPoints().get(i);
            if (station.compareTo(right.getStationM()) <= 0) {
                return left.getDepthM().add(right.getDepthM().subtract(left.getDepthM())
                        .multiply(station.subtract(left.getStationM()))
                        .divide(right.getStationM().subtract(left.getStationM()), 12, RoundingMode.HALF_UP));
            }
        }
        throw new IllegalArgumentException("station outside profile");
    }

    private BigDecimal multiplier(BigDecimal depth) {
        return BigDecimal.ONE.add(depth.subtract(b("3")).max(BigDecimal.ZERO).multiply(b(".1")));
    }

    private DepthCrossing crossing(String id, String type, String station, String top,
            String height, String clearance, String multiplier) {
        return new DepthCrossing(id, type, b(station), b(top), b(height), b(clearance), b(multiplier));
    }

    private DepthProfilePoint point(String station, String depth) { return new DepthProfilePoint(b(station), b(depth)); }
    private BigDecimal b(String value) { return new BigDecimal(value); }
}
