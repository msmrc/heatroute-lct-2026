package ru.lct.heatroute.domain.sizing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;

class OfficialNetworkSizerContinuousLengthTest {
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialNetworkSizer sizer = new OfficialNetworkSizer(pipes);

    @Test
    void promotesPastPreviousDiameterWhenTwo182MeterEdgesWouldExceedDu65Limit() {
        NetworkSizingResult result = sizer.size(List.of(
                edge("first", "root", "chamber", new BigDecimal("182")),
                edge("second", "chamber", "demand", new BigDecimal("182"))),
                Map.of("demand", new BigDecimal("3.5")));

        assertThat(result.getIssues()).isEmpty();
        SizedNetworkEdge first = result.getEdges().get("first");
        SizedNetworkEdge second = result.getEdges().get("second");
        assertThat(first.getDiameter()).isEqualTo(65);
        assertThat(first.getContinuousSameDiameterLengthM()).isEqualByComparingTo("182");
        // Прежний код возвращал ещё один ДУ65 и терял первые 182 м: 364 > 245 м.
        assertThat(second.getDiameter()).isEqualTo(80);
        assertThat(second.getContinuousSameDiameterLengthM()).isEqualByComparingTo("182");
        assertThat(second.getFlowTph()).isEqualByComparingTo("3.5");
    }

    @Test
    void retainsAccumulatedLengthWhenPromotedDiameterMatchesPreviousAndStillFits() {
        NetworkSizingResult result = fork(new BigDecimal("40"), new BigDecimal("182"),
                new BigDecimal("3.5"), new BigDecimal("1.5"));

        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getEdges().get("trunk").getDiameter()).isEqualTo(65);
        SizedNetworkEdge branch = result.getEdges().get("branch");
        assertThat(branch.getDiameter()).isEqualTo(65);
        assertThat(branch.getContinuousSameDiameterLengthM()).isEqualByComparingTo("222");
        assertThat(result.getEdges().get("other").getDiameter()).isEqualTo(50);
        assertThat(result.getEdges().get("other").getContinuousSameDiameterLengthM()).isEqualByComparingTo("1");
    }

    @ParameterizedTest
    @CsvSource({"63, 65, 245", "63.001, 80, 182"})
    void acceptsExactAccumulatedBoundaryAndPromotesOneMillimeterAbove(
            String precedingLength, int expectedDiameter, String expectedContinuousLength) {
        NetworkSizingResult result = fork(new BigDecimal(precedingLength), new BigDecimal("182"),
                new BigDecimal("3.5"), new BigDecimal("1.5"));

        assertThat(result.getIssues()).isEmpty();
        SizedNetworkEdge branch = result.getEdges().get("branch");
        assertThat(branch.getDiameter()).isEqualTo(expectedDiameter);
        assertThat(branch.getContinuousSameDiameterLengthM()).isEqualByComparingTo(expectedContinuousLength);
    }

    @Test
    void checksAccumulatedBoundaryForEveryPossiblePromotedOfficialDiameter() {
        List<PipeCatalogEntry> entries = pipes.entries();
        BigDecimal millimeter = new BigDecimal("0.001");
        for (int index = 1; index < entries.size(); index++) {
            PipeCatalogEntry lower = entries.get(index - 1);
            PipeCatalogEntry promoted = entries.get(index);
            BigDecimal branchLength = BigDecimal.valueOf(lower.getMaxContinuousLengthM()).add(millimeter);
            BigDecimal limit = BigDecimal.valueOf(promoted.getMaxContinuousLengthM());
            BigDecimal precedingLength = limit.subtract(branchLength);
            BigDecimal branchFlow = lower.getMaxFlowTph();
            BigDecimal otherFlow = promoted.getMaxFlowTph().subtract(branchFlow);

            NetworkSizingResult exact = fork(precedingLength, branchLength, branchFlow, otherFlow);
            assertThat(exact.getIssues()).as("exact promoted DU %s boundary", promoted.getDiameter()).isEmpty();
            assertThat(exact.getEdges().get("trunk").getDiameter()).isEqualTo(promoted.getDiameter());
            assertThat(exact.getEdges().get("branch").getDiameter()).isEqualTo(promoted.getDiameter());
            assertThat(exact.getEdges().get("branch").getContinuousSameDiameterLengthM()).isEqualByComparingTo(limit);

            NetworkSizingResult above = fork(precedingLength.add(millimeter), branchLength, branchFlow, otherFlow);
            if (index + 1 < entries.size()) {
                assertThat(above.getIssues()).as("above promoted DU %s boundary", promoted.getDiameter()).isEmpty();
                assertThat(above.getEdges().get("branch").getDiameter()).isEqualTo(entries.get(index + 1).getDiameter());
                // ДУ действительно изменился относительно предыдущего участка — отсчёт начинается заново.
                assertThat(above.getEdges().get("branch").getContinuousSameDiameterLengthM()).isEqualByComparingTo(branchLength);
            } else {
                assertThat(above.isValid()).isFalse();
                assertThat(above.getIssues()).anySatisfy(issue -> {
                    assertThat(issue.getCode()).isEqualTo("MAX_CONTINUOUS_LENGTH_EXCEEDED");
                    assertThat(issue.getEdgeId()).isEqualTo("branch");
                });
            }
        }
    }

    @Test
    void rejectsLargestDiameterWhenItsAccumulatedRunCannotBePromotedFurther() {
        NetworkSizingResult result = fork(new BigDecimal("10000"), new BigDecimal("10000"),
                new BigDecimal("15000"), new BigDecimal("5000"));

        assertThat(result.getEdges().get("trunk").getDiameter()).isEqualTo(1400);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getIssues()).anySatisfy(issue -> {
            assertThat(issue.getCode()).isEqualTo("MAX_CONTINUOUS_LENGTH_EXCEEDED");
            assertThat(issue.getEdgeId()).isEqualTo("branch");
        });
    }

    private NetworkSizingResult fork(BigDecimal precedingLength, BigDecimal branchLength,
            BigDecimal branchFlow, BigDecimal otherFlow) {
        return sizer.size(List.of(
                edge("trunk", "root", "chamber", precedingLength),
                edge("branch", "chamber", "demand", branchLength),
                edge("other", "chamber", "other-demand", BigDecimal.ONE)),
                Map.of("demand", branchFlow, "other-demand", otherFlow));
    }

    private NetworkTreeEdge edge(String id, String from, String to, BigDecimal length) {
        return new NetworkTreeEdge(id, from, to, length);
    }
}
