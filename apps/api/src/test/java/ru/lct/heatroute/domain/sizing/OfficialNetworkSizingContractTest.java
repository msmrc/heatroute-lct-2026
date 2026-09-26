package ru.lct.heatroute.domain.sizing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/** Проверяет таблицу 1 и Q1–Q2 независимо от разбиения сети техническими узлами. */
class OfficialNetworkSizingContractTest {
    // Дословные численные границы таблицы 1; ожидаемые значения не берутся из production catalog.
    private static final int[] DIAMETERS = {
        50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400
    };
    private static final String[] FLOWS = {
        "3.5", "8.3", "13.2", "22.3", "40.2", "65.1", "152.3", "274.9", "437.4",
        "943.1", "1663.4", "2627.7", "3735.1", "5296.8", "7165.0", "9391.8", "15012.8", "22501.9"
    };
    private static final int[] LENGTHS = {
        181, 245, 327, 419, 554, 696, 1042, 1379, 1718, 2477, 3245, 4037, 4775, 5644,
        6518, 7419, 9288, 11276
    };
    private final OfficialNetworkSizer sizer = new OfficialNetworkSizer(new OfficialPipeCatalog());

    @Test
    void technicalNodeCannotResetLengthByChangingDiameterAtSameFlow() {
        NetworkSizingResult result = size(
                List.of(edge("a", "root", "technical", "100"), edge("b", "technical", "demand", "100")),
                Map.of("demand", value("3.5")));
        diameters(result, Map.of("a", 65, "b", 65));
        assertThat(result.getEdges().get("b").getContinuousSameDiameterLengthM()).isEqualByComparingTo("200");
    }

    @Test
    void arbitraryTechnicalSplitsMatchUnsplitLengthAndDiameter() {
        NetworkSizingResult whole = size(List.of(edge("whole", "root", "demand", "250")),
                Map.of("demand", value("3.5")));
        List<NetworkTreeEdge> edges = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            edges.add(edge("e" + index, "n" + index, "n" + (index + 1), "12.5"));
        }
        NetworkSizingResult split = size(edges, Map.of("n20", value("3.5")));
        assertThat(whole.isValid()).isTrue();
        assertThat(split.isValid()).isTrue();
        assertThat(whole.getEdges().get("whole").getDiameter()).isEqualTo(80);
        split.getEdges().values().forEach(edge -> assertThat(edge.getDiameter()).isEqualTo(80));
        assertThat(split.getEdges().get("e19").getContinuousSameDiameterLengthM()).isEqualByComparingTo("250");
    }

    @Test
    void aLongChildRequiresItsAncestorToBeAtLeastAsWide() {
        NetworkSizingResult result = size(List.of(
                edge("trunk", "root", "branch", "10"),
                edge("long", "branch", "a", "246"),
                edge("short", "branch", "b", "1")),
                Map.of("a", value("3.5"), "b", value("0.5")));
        diameters(result, Map.of("trunk", 80, "long", 80, "short", 50));
        assertThat(result.getEdges().get("long").getContinuousSameDiameterLengthM()).isEqualByComparingTo("256");
    }

    @Test
    void combinedEqualDiameterLengthPromotesAncestorAndKeepsMinimalChild() {
        NetworkSizingResult result = size(List.of(
                edge("trunk", "root", "branch", "100"),
                edge("long", "branch", "a", "200"),
                edge("short", "branch", "b", "1")),
                Map.of("a", value("3.5"), "b", value("0.5")));
        diameters(result, Map.of("trunk", 80, "long", 65, "short", 50));
        assertThat(result.getEdges().get("long").getContinuousSameDiameterLengthM()).isEqualByComparingTo("200");
    }

    @Test
    void commonTrunkCountsInEachPathWithoutPromotingBothChildren() {
        NetworkSizingResult result = size(List.of(
                edge("trunk", "root", "branch", "10"),
                edge("left", "branch", "a", "180"),
                edge("right", "branch", "b", "180")),
                Map.of("a", value("1.75"), "b", value("1.75")));
        diameters(result, Map.of("trunk", 65, "left", 50, "right", 50));
    }

    @Test
    void parallelBranchesAreNotSummed() {
        NetworkSizingResult result = size(List.of(
                edge("trunk", "root", "branch", "50"),
                edge("left", "branch", "a", "100"),
                edge("right", "branch", "b", "100")),
                Map.of("a", value("1"), "b", value("1")));
        diameters(result, Map.of("trunk", 50, "left", 50, "right", 50));
        assertThat(result.getEdges().get("left").getContinuousSameDiameterLengthM()).isEqualByComparingTo("150");
        assertThat(result.getEdges().get("right").getContinuousSameDiameterLengthM()).isEqualByComparingTo("150");
    }

    @Test
    void actualFlowChangeAllowsDiameterDecreaseAndRestartsItsLength() {
        NetworkSizingResult result = size(List.of(
                edge("trunk", "root", "branch", "150"),
                edge("left", "branch", "a", "150"),
                edge("right", "branch", "b", "10")),
                Map.of("a", value("3.5"), "b", value("5")));
        diameters(result, Map.of("trunk", 80, "left", 50, "right", 65));
        assertThat(result.getEdges().get("left").getContinuousSameDiameterLengthM()).isEqualByComparingTo("150");
    }

    @Test
    void demandAtDegreeTwoNodeIsARealFlowChange() {
        NetworkSizingResult result = size(List.of(
                edge("trunk", "root", "node", "100"), edge("tail", "node", "demand", "180")),
                Map.of("node", value("0.5"), "demand", value("3.5")));
        diameters(result, Map.of("trunk", 65, "tail", 50));
    }

    @Test
    void equalityDoesNotMergeSeparateSiblingFlowPaths() {
        NetworkSizingResult result = size(List.of(
                edge("trunk", "root", "branch", "1"),
                edge("left", "branch", "a", "150"),
                edge("right", "branch", "b", "150")),
                Map.of("a", value("3.5"), "b", value("3.5")));
        diameters(result, Map.of("trunk", 65, "left", 50, "right", 50));
    }

    @Test
    void zeroFlowSideBranchDoesNotBreakPositiveSameFlowEquality() {
        NetworkSizingResult result = size(List.of(
                edge("trunk", "root", "branch", "100"),
                edge("left", "branch", "a", "100"),
                edge("zero", "branch", "b", "10")), Map.of("a", value("3.5")));
        diameters(result, Map.of("trunk", 65, "left", 65, "zero", 50));
    }

    @Test
    void zeroFlowEqualityComponentUsesLongestPathInsteadOfTotalLength() {
        NetworkSizingResult result = size(List.of(
                edge("trunk", "root", "branch", "100"),
                edge("left", "branch", "a", "100"),
                edge("right", "branch", "b", "100")), Map.of());
        diameters(result, Map.of("trunk", 65, "left", 65, "right", 65));
    }

    @Test
    void inputOrderDoesNotChangeTheMinimalLegalAssignment() {
        List<NetworkTreeEdge> edges = new ArrayList<>(List.of(
                edge("trunk", "root", "split", "100"),
                edge("middle", "split", "branch", "100"),
                edge("left", "branch", "a", "200"),
                edge("right", "branch", "b", "1")));
        List<List<NetworkTreeEdge>> permutations = new ArrayList<>();
        permute(edges, 0, permutations);
        for (List<NetworkTreeEdge> permutation : permutations) {
            diameters(size(permutation, Map.of("a", value("3.5"), "b", value("0.5"))),
                    Map.of("trunk", 80, "middle", 80, "left", 65, "right", 50));
        }
    }

    @TestFactory
    Stream<DynamicTest> allOfficialTableBoundariesOnWholeAndSplitPaths() {
        return IntStream.range(0, DIAMETERS.length).boxed().flatMap(index -> Stream.of(
                DynamicTest.dynamicTest("DU" + DIAMETERS[index] + " exact flow", () -> {
                    NetworkSizingResult result = size(List.of(edge("e", "r", "d", "1")), Map.of("d", value(FLOWS[index])));
                    diameters(result, Map.of("e", DIAMETERS[index]));
                }),
                DynamicTest.dynamicTest("DU" + DIAMETERS[index] + " above flow", () -> {
                    NetworkSizingResult result = size(List.of(edge("e", "r", "d", "1")),
                            Map.of("d", value(FLOWS[index]).add(value("0.001"))));
                    expectNextOrIssue(result, index, List.of("e"), "FLOW_EXCEEDS_CATALOG");
                }),
                DynamicTest.dynamicTest("DU" + DIAMETERS[index] + " exact split length", () -> {
                    BigDecimal half = BigDecimal.valueOf(LENGTHS[index]).divide(value("2"));
                    NetworkSizingResult result = size(List.of(edge("a", "r", "t", half), edge("b", "t", "d", half)),
                            Map.of("d", value(FLOWS[index])));
                    diameters(result, Map.of("a", DIAMETERS[index], "b", DIAMETERS[index]));
                    assertThat(result.getEdges().get("b").getContinuousSameDiameterLengthM())
                            .isEqualByComparingTo(BigDecimal.valueOf(LENGTHS[index]));
                }),
                DynamicTest.dynamicTest("DU" + DIAMETERS[index] + " above split length", () -> {
                    BigDecimal half = BigDecimal.valueOf(LENGTHS[index]).divide(value("2"));
                    NetworkSizingResult result = size(List.of(edge("a", "r", "t", half),
                            edge("b", "t", "d", half.add(value("0.001")))), Map.of("d", value(FLOWS[index])));
                    expectNextOrIssue(result, index, List.of("a", "b"), "MAX_CONTINUOUS_LENGTH_EXCEEDED");
                })));
    }

    private void expectNextOrIssue(NetworkSizingResult result, int index, List<String> edges, String code) {
        if (index + 1 == DIAMETERS.length) {
            assertThat(result.isValid()).isFalse();
            assertThat(result.getIssues()).extracting(NetworkSizingIssue::getCode).contains(code);
        } else {
            assertThat(result.isValid()).isTrue();
            edges.forEach(id -> assertThat(result.getEdges().get(id).getDiameter()).as(id).isEqualTo(DIAMETERS[index + 1]));
        }
    }

    private void diameters(NetworkSizingResult result, Map<String, Integer> expected) {
        assertThat(result.isValid()).as("sizing issues").isTrue();
        assertThat(result.getEdges()).hasSize(expected.size());
        expected.forEach((id, diameter) -> assertThat(result.getEdges().get(id).getDiameter()).as(id).isEqualTo(diameter));
    }

    private NetworkSizingResult size(List<NetworkTreeEdge> edges, Map<String, BigDecimal> demands) {
        return sizer.size(edges, demands);
    }

    private static void permute(List<NetworkTreeEdge> edges, int first, List<List<NetworkTreeEdge>> output) {
        if (first == edges.size()) {
            output.add(List.copyOf(edges));
            return;
        }
        for (int index = first; index < edges.size(); index++) {
            Collections.swap(edges, first, index);
            permute(edges, first + 1, output);
            Collections.swap(edges, first, index);
        }
    }

    private static BigDecimal value(String value) { return new BigDecimal(value); }
    private static NetworkTreeEdge edge(String id, String from, String to, String length) {
        return edge(id, from, to, value(length));
    }
    private static NetworkTreeEdge edge(String id, String from, String to, BigDecimal length) {
        return new NetworkTreeEdge(id, from, to, length);
    }
}
