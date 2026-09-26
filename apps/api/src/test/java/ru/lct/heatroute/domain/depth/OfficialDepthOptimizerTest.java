package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

class OfficialDepthOptimizerTest {
    private OfficialDepthOptimizer optimizer;
    private OfficialDepthProfileValidator validator;

    @BeforeEach
    void setUp() {
        OfficialPipeCatalog pipeCatalog = new OfficialPipeCatalog();
        optimizer = new OfficialDepthOptimizer(pipeCatalog, new OfficialEconomics());
        validator = new OfficialDepthProfileValidator(pipeCatalog);
    }

    @Test
    void choosesCheaperGasPassageAndBuildsOfficialPlateauAndSlopes() {
        DepthCrossing gas = crossing("gas-1", "gas", "50", "2.8", ".4", ".2", "1.25");

        DepthProfileResult result = optimizer.optimize(bd("100"), 50, List.of(gas), bd("10"));

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getCrossings()).singleElement().satisfies(decision -> {
            assertThat(decision.getPassage()).isEqualTo("above");
            assertThat(decision.getDepthM()).isEqualByComparingTo("2.475");
            assertThat(decision.getPlateauStartM()).isEqualByComparingTo("48");
            assertThat(decision.getPlateauEndM()).isEqualByComparingTo("52");
            assertThat(decision.getRampStartM()).isEqualByComparingTo("42.750");
            assertThat(decision.getRampEndM()).isEqualByComparingTo("57.250");
            assertThat(decision.getVerticalClearanceM()).isEqualByComparingTo("0.2");
        });
        assertThat(result.getPoints()).extracting(DepthProfilePoint::getStationM)
                .containsExactly(bd("0.000"), bd("42.750"), bd("48.000"), bd("52.000"), bd("57.250"), bd("100.000"));
        assertThat(validator.validate(bd("100"), 50, List.of(gas), bd(".7"), bd("10"), result)).isEmpty();
    }

    @Test
    void choosesPassageBelowWhenAboveIsOutsideConfiguredDepthRange() {
        DepthCrossing power = crossing("power-1", "power", "50", ".8", "2.5", ".5", "1.15");

        DepthProfileResult result = optimizer.optimize(bd("100"), 50, List.of(power), bd("8"));

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getCrossings()).singleElement().satisfies(decision -> {
            assertThat(decision.getPassage()).isEqualTo("below");
            assertThat(decision.getDepthM()).isEqualByComparingTo("3.8");
            assertThat(decision.getVerticalClearanceM()).isEqualByComparingTo("0.5");
        });
        assertThat(validator.validate(bd("100"), 50, List.of(power), bd(".7"), bd("8"), result)).isEmpty();
    }

    @Test
    void retainsOrdinaryDepthWhenItAlreadyProvidesRequiredClearance() {
        DepthCrossing heat = crossing("heat-1", "heat", "50", "2.0", ".4", ".5", "1.10");

        DepthProfileResult result = optimizer.optimize(bd("100"), 50, List.of(heat), bd("8"));

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getCrossings()).singleElement().satisfies(decision -> {
            assertThat(decision.getPassage()).isEqualTo("below");
            assertThat(decision.getDepthM()).isEqualByComparingTo("3.0");
        });
        assertThat(result.getPoints()).extracting(DepthProfilePoint::getStationM)
                .containsExactly(bd("0.000"), bd("100.000"));
        assertThat(validator.validate(bd("100"), 50, List.of(heat), bd(".7"), bd("8"), result)).isEmpty();
    }

    @Test
    void usesFeasibleEndpointDepthWhenSlopeWouldNotFitBeforeCrossing() {
        DepthCrossing power = crossing("power-edge", "power", "4", ".8", "2.5", ".5", "1.15");

        DepthProfileResult result = optimizer.optimize(bd("30"), 50, List.of(power), bd("8"));

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getPoints().get(0).getDepthM()).isEqualByComparingTo("3.6");
        assertThat(result.getCrossings()).singleElement().satisfies(decision ->
                assertThat(decision.getRampStartM()).isEqualByComparingTo("0"));
        assertThat(validator.validate(bd("30"), 50, List.of(power), bd(".7"), bd("8"), result))
                .isEmpty();
    }

    @Test
    void reportsNoPassageWhenOfficialPlateauCannotFitAtEndpoint() {
        DepthCrossing power = crossing("power-edge", "power", "1", ".8", "2.5", ".5", "1.15");

        DepthProfileResult result = optimizer.optimize(bd("30"), 50, List.of(power), bd("8"));

        assertThat(result.isComplete()).isFalse();
        assertThat(result.getIssues()).extracting(DepthProfileIssue::getCode)
                .containsExactly("NO_VERTICAL_PASSAGE");
    }

    @Test
    void connectsNearbyCrossingsWithContinuousLeastCostBridge() {
        DepthCrossing first = crossing("power-1", "power", "40", ".8", "2.5", ".5", "1.15");
        DepthCrossing second = crossing("power-2", "power", "55", ".8", "2.5", ".5", "1.15");

        DepthProfileResult result = optimizer.optimize(bd("100"), 50, List.of(first, second), bd("8"));

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getCrossings()).hasSize(2)
                .allSatisfy(decision -> {
                    assertThat(decision.getDepthM()).isEqualByComparingTo("3.8");
                });
        assertThat(result.getPoints()).extracting(DepthProfilePoint::getStationM)
                .containsExactly(bd("0.000"), bd("30.000"), bd("38.000"), bd("42.000"), bd("47.500"),
                        bd("53.000"), bd("57.000"), bd("65.000"), bd("100.000"));
        assertThat(result.depthAt(bd("47.5"))).isEqualByComparingTo("3.25");
        // Exact XY/K integral of the lawful constant3.8 bridge is103.456; a shallow valley is cheaper.
        assertThat(result.getDepthAdjustedCostMeters()).isEqualByComparingTo("103.154");
        assertThat(result.getDepthAdjustedCostMeters()).isLessThan(bd("103.456"));
        assertThat(validator.validate(bd("100"), 50, List.of(first, second), bd(".7"), bd("8"), result))
                .isEmpty();
    }

    @Test
    void validatorRejectsTamperedSlope() {
        DepthCrossing gas = crossing("gas-1", "gas", "50", "2.8", ".4", ".2", "1.25");
        DepthProfileResult valid = optimizer.optimize(bd("100"), 50, List.of(gas), bd("10"));
        DepthProfileResult tampered = new DepthProfileResult(
                true,
                List.of(
                        new DepthProfilePoint(bd("0"), bd("3")),
                        new DepthProfilePoint(bd("1"), bd("2.2")),
                        new DepthProfilePoint(bd("48"), bd("2.2")),
                        new DepthProfilePoint(bd("52"), bd("2.2")),
                        new DepthProfilePoint(bd("100"), bd("3"))),
                valid.getCrossings(),
                List.of(),
                bd("100"),
                bd("100"));

        assertThat(validator.validate(bd("100"), 50, List.of(gas), bd(".7"), bd("10"), tampered))
                .extracting(DepthProfileIssue::getCode)
                .contains("PROFILE_SLOPE_EXCEEDED");
    }

    private DepthCrossing crossing(
            String id,
            String type,
            String station,
            String topDepth,
            String height,
            String clearance,
            String multiplier) {
        return new DepthCrossing(
                id,
                type,
                bd(station),
                bd(topDepth),
                bd(height),
                bd(clearance),
                bd(multiplier));
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
