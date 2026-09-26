package ru.lct.heatroute.domain.sizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

class OfficialNetworkSizerSafetyTest {
    private final OfficialNetworkSizer sizer = new OfficialNetworkSizer(new OfficialPipeCatalog());

    @Test
    void longTechnicalChainUsesBoundedStackAndPreservesTheTotalLength() {
        List<NetworkTreeEdge> edges = new ArrayList<>();
        for (int index = 0; index < 20000; index++) {
            edges.add(edge("edge-" + index, "n" + index, "n" + (index + 1), "0.01"));
        }
        NetworkSizingResult result = sizer.size(edges, Map.of("n20000", new BigDecimal("3.5")));
        assertThat(result.isValid()).isTrue();
        assertThat(result.getEdges()).hasSize(20000);
        assertThat(result.getEdges().values()).allSatisfy(edge -> assertThat(edge.getDiameter()).isEqualTo(65));
        assertThat(result.getEdges().get("edge-19999").getContinuousSameDiameterLengthM())
                .isEqualByComparingTo("200");
    }

    @Test
    void interruptionIsPreservedAndStopsSizing() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> sizer.size(List.of(edge("e", "r", "d", "1")),
                    Map.of("d", BigDecimal.ONE))).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidGraphStillReportsCycleAndMultipleParents() {
        NetworkSizingResult result = sizer.size(List.of(
                edge("a", "one", "two", "1"), edge("b", "two", "one", "1"),
                edge("c", "other", "two", "1")), Map.of("two", BigDecimal.ONE));
        assertThat(result.isValid()).isFalse();
        assertThat(result.getIssues()).extracting(NetworkSizingIssue::getCode)
                .contains("NETWORK_CYCLE", "MULTIPLE_UPSTREAM_EDGES");
        assertThat(result.getEdges().values()).allSatisfy(edge -> assertThat(edge.getDiameter()).isNull());
    }

    @Test
    void invalidDemandsAndDuplicateIdsRemainExplicitErrors() {
        Map<String, BigDecimal> demands = new HashMap<>();
        demands.put("a", null);
        demands.put("b", BigDecimal.ONE.negate());
        NetworkSizingResult result = sizer.size(List.of(
                edge("duplicate", "r", "a", "1"), edge("duplicate", "r", "b", "1")), demands);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getIssues()).extracting(NetworkSizingIssue::getCode)
                .contains("DUPLICATE_EDGE_ID", "INVALID_DEMAND_FLOW");
    }

    @Test
    void disconnectedTreesRemainIndependentAndIdsAreOpaque() {
        NetworkSizingResult result = sizer.size(List.of(
                edge("a+b", "root1", "d1", "200"), edge("a", "root2", "d2", "150"),
                edge("b", "root3", "d3", "250")),
                Map.of("d1", new BigDecimal("3.5"), "d2", new BigDecimal("3.5"), "d3", new BigDecimal("3.5")));
        assertThat(result.isValid()).isTrue();
        assertThat(result.getEdges().get("a+b").getDiameter()).isEqualTo(65);
        assertThat(result.getEdges().get("a").getDiameter()).isEqualTo(50);
        assertThat(result.getEdges().get("b").getDiameter()).isEqualTo(80);
    }

    @Test
    void flowExceedingTheLargestDiameterDoesNotReceiveAUsableDiameter() {
        NetworkSizingResult result = sizer.size(List.of(
                edge("a", "root", "technical", "1"), edge("b", "technical", "d", "1")),
                Map.of("d", new BigDecimal("22501.901")));
        assertThat(result.isValid()).isFalse();
        assertThat(result.getIssues()).extracting(NetworkSizingIssue::getCode).contains("FLOW_EXCEEDS_CATALOG");
        assertThat(result.getEdges().values()).allSatisfy(edge -> assertThat(edge.getDiameter()).isNull());
    }

    private NetworkTreeEdge edge(String id, String from, String to, String length) {
        return new NetworkTreeEdge(id, from, to, new BigDecimal(length));
    }
}
