package ru.lct.heatroute.domain.sizing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;

class OfficialNetworkSizerTest {
    private final OfficialPipeCatalog pipeCatalog = new OfficialPipeCatalog();
    private final OfficialNetworkSizer sizer = new OfficialNetworkSizer(pipeCatalog);

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
    void promotesWholeSameFlowSectionInsteadOfResettingLengthAtChamber() {
        NetworkSizingResult result = sizer.size(
                List.of(
                        edge("a", "source", "chamber", "100"),
                        edge("b", "chamber", "oks", "100")),
                Map.of("oks", new BigDecimal("3.5")));

        assertThat(result.getEdges().get("a").getDiameter()).isEqualTo(65);
        assertThat(result.getEdges().get("b").getDiameter()).isEqualTo(65);
        assertThat(result.getEdges().get("b").getContinuousSameDiameterLengthM())
                .isEqualByComparingTo("200");
        assertThat(result.getIssues()).extracting(NetworkSizingIssue::getCode)
                .doesNotContain("MAX_CONTINUOUS_LENGTH_EXCEEDED");
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

    @Test
    void keepsDiameterAtExactLengthBoundaryAndPromotesAboveIt() {
        NetworkSizingResult exact = sizer.size(
                List.of(edge("exact", "source", "oks", "181")),
                Map.of("oks", new BigDecimal("3.5")));
        NetworkSizingResult above = sizer.size(
                List.of(edge("above", "source", "oks", "182")),
                Map.of("oks", new BigDecimal("3.5")));

        assertThat(exact.getEdges().get("exact").getDiameter()).isEqualTo(50);
        assertThat(above.getEdges().get("above").getDiameter()).isEqualTo(65);
        assertThat(exact.isValid()).isTrue();
        assertThat(above.isValid()).isTrue();
    }

    @Test
    void reportsLengthWhenNoOfficialDiameterCanCarryOneSection() {
        NetworkSizingResult result = sizer.size(
                List.of(edge("too-long", "source", "oks", "12000")),
                Map.of("oks", new BigDecimal("3.5")));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getIssues()).extracting(NetworkSizingIssue::getCode)
                .contains("MAX_CONTINUOUS_LENGTH_EXCEEDED");
    }

    @Test
    void coversFlowAndContinuousLengthBoundariesForEveryOfficialDiameter() {
        List<PipeCatalogEntry> entries = pipeCatalog.entries();
        for (int index = 0; index < entries.size(); index++) {
            PipeCatalogEntry entry = entries.get(index);
            String suffix = Integer.toString(entry.getDiameter());
            NetworkSizingResult exactFlow = sizer.size(
                    List.of(edge("flow-" + suffix, "source", "oks", "1")),
                    Map.of("oks", entry.getMaxFlowTph()));
            assertThat(exactFlow.getEdges().get("flow-" + suffix).getDiameter())
                    .as("exact flow boundary for DU %s", entry.getDiameter())
                    .isEqualTo(entry.getDiameter());

            NetworkSizingResult exactLength = sizer.size(
                    List.of(edge("length-" + suffix, "source", "oks",
                            Integer.toString(entry.getMaxContinuousLengthM()))),
                    Map.of("oks", entry.getMaxFlowTph()));
            assertThat(exactLength.getEdges().get("length-" + suffix).getDiameter())
                    .as("exact length boundary for DU %s", entry.getDiameter())
                    .isEqualTo(entry.getDiameter());
            assertThat(exactLength.isValid()).isTrue();

            NetworkSizingResult aboveLength = sizer.size(
                    List.of(edge("above-length-" + suffix, "source", "oks",
                            Integer.toString(entry.getMaxContinuousLengthM() + 1))),
                    Map.of("oks", entry.getMaxFlowTph()));
            if (index + 1 < entries.size()) {
                assertThat(aboveLength.getEdges().get("above-length-" + suffix).getDiameter())
                        .as("length promotion above DU %s", entry.getDiameter())
                        .isEqualTo(entries.get(index + 1).getDiameter());
                assertThat(aboveLength.isValid()).isTrue();
            } else {
                assertThat(aboveLength.getIssues()).extracting(NetworkSizingIssue::getCode)
                        .contains("MAX_CONTINUOUS_LENGTH_EXCEEDED");
            }
        }
    }

    private NetworkTreeEdge edge(String id, String upstream, String downstream, String length) {
        return new NetworkTreeEdge(id, upstream, downstream, new BigDecimal(length));
    }
}
