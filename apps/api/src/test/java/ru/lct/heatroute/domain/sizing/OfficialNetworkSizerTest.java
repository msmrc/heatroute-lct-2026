package ru.lct.heatroute.domain.sizing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

class OfficialNetworkSizerTest {
    private final OfficialNetworkSizer sizer = new OfficialNetworkSizer(new OfficialPipeCatalog());

    @Test
    void aggregatesBranchFlowsAndSelectsMinimumOfficialDiameter() {
        NetworkSizingResult result = sizer.size(
                List.of(
                        edge("trunk", "root", "branch", "100"),
                        edge("left", "branch", "oks-a", "50"),
                        edge("right", "branch", "oks-b", "50")),
                Map.of(
                        "oks-a", new BigDecimal("8.3"),
                        "oks-b", new BigDecimal("13.2")));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getEdges().get("trunk").getFlowTph()).isEqualByComparingTo("21.5");
        assertThat(result.getEdges().get("trunk").getDiameter()).isEqualTo(100);
        assertThat(result.getEdges().get("left").getDiameter()).isEqualTo(65);
        assertThat(result.getEdges().get("right").getDiameter()).isEqualTo(80);
    }

    @Test
    void doesNotResetContinuousLengthAtIntermediateChamber() {
        NetworkSizingResult result = sizer.size(
                List.of(
                        edge("a", "source", "chamber", "100"),
                        edge("b", "chamber", "oks", "100")),
                Map.of("oks", new BigDecimal("3.5")));

        assertThat(result.getEdges().get("a").getDiameter()).isEqualTo(50);
        assertThat(result.getEdges().get("b").getContinuousSameDiameterLengthM())
                .isEqualByComparingTo("200");
        assertThat(result.getIssues()).extracting(NetworkSizingIssue::getCode)
                .contains("MAX_CONTINUOUS_LENGTH_EXCEEDED");
    }

    @Test
    void diameterChangeStartsANewContinuousLengthRun() {
        NetworkSizingResult result = sizer.size(
                List.of(
                        edge("trunk", "source", "branch", "150"),
                        edge("small", "branch", "oks-small", "150"),
                        edge("other", "branch", "oks-other", "10")),
                Map.of(
                        "oks-small", new BigDecimal("3.5"),
                        "oks-other", new BigDecimal("5.0")));

        assertThat(result.getEdges().get("trunk").getDiameter()).isEqualTo(80);
        assertThat(result.getEdges().get("small").getDiameter()).isEqualTo(50);
        assertThat(result.getEdges().get("small").getContinuousSameDiameterLengthM())
                .isEqualByComparingTo("150");
        assertThat(result.getIssues()).extracting(NetworkSizingIssue::getCode)
                .doesNotContain("MAX_CONTINUOUS_LENGTH_EXCEEDED");
    }

    @Test
    void rejectsCycleAndMultipleUpstreamEdges() {
        NetworkSizingResult result = sizer.size(
                List.of(
                        edge("a", "one", "two", "10"),
                        edge("b", "two", "one", "10"),
                        edge("c", "three", "two", "10")),
                Map.of("two", BigDecimal.ONE));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getIssues()).extracting(NetworkSizingIssue::getCode)
                .contains("NETWORK_CYCLE", "MULTIPLE_UPSTREAM_EDGES");
    }

    private NetworkTreeEdge edge(String id, String upstream, String downstream, String length) {
        return new NetworkTreeEdge(id, upstream, downstream, new BigDecimal(length));
    }
}
