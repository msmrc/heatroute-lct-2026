package ru.lct.heatroute.domain.engineering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OfficialEngineeringRulesTest {
    private final OfficialPipeCatalog catalog = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();

    @Test
    void containsAllEighteenOfficialDiameterRows() {
        assertThat(catalog.entries()).hasSize(18);
        assertThat(catalog.entries()).extracting(PipeCatalogEntry::getDiameter)
                .containsExactly(50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400);
        PipeCatalogEntry largest = catalog.byDiameter(1400).orElseThrow();
        assertThat(largest.getMaxFlowTph()).isEqualByComparingTo("22501.9");
        assertThat(largest.getMaxContinuousLengthM()).isEqualTo(11276);
        assertThat(largest.getReconstructionRubPerM()).isEqualByComparingTo("978584");
        assertThat(largest.getPairWidthM()).isEqualByComparingTo("3.450");
    }

    @Test
    void selectsSmallestDiameterAtAndImmediatelyAboveBoundary() {
        assertThat(catalog.minimumForFlow(new BigDecimal("3.5")).orElseThrow().getDiameter()).isEqualTo(50);
        assertThat(catalog.minimumForFlow(new BigDecimal("3.5001")).orElseThrow().getDiameter()).isEqualTo(65);
        assertThat(catalog.minimumForFlow(new BigDecimal("22501.9")).orElseThrow().getDiameter()).isEqualTo(1400);
        assertThat(catalog.minimumForFlow(new BigDecimal("22502.0"))).isEmpty();
        assertThatThrownBy(() -> catalog.minimumForFlow(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesOfficialSpecialAndDepthMultipliers() {
        PipeCatalogEntry pipe = catalog.byDiameter(50).orElseThrow();
        assertThat(economics.newNetworkCost(
                        pipe, new BigDecimal("10"), SpecialCrossingType.ROAD, new BigDecimal("4.0")))
                .isEqualByComparingTo("1302804.80");
        assertThat(economics.reconstructionCost(pipe, new BigDecimal("10")))
                .isEqualByComparingTo("961800.00");
        assertThat(economics.depthMultiplier(new BigDecimal("3.0"))).isEqualByComparingTo("1");
        assertThat(economics.depthMultiplier(new BigDecimal("4.0"))).isEqualByComparingTo("1.10");
    }

    @Test
    void calculatesOfficialPenaltyAndScoreExactly() {
        assertThat(economics.unconnectedPenalty(new BigDecimal("8.3")))
                .isEqualByComparingTo("104150000.00");
        assertThat(economics.score(new BigDecimal("25000000"), new BigDecimal("100")))
                .isEqualByComparingTo("1.000000000");
    }
}
