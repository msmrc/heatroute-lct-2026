package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.economics.VariantEconomics;

class AcceptedSolutionArchiveTest {
    @Test
    void keepsOnlyBestDistinctExactSolutionsWithinCapacity() {
        AcceptedSolutionArchive archive = new AcceptedSolutionArchive(2);
        AcceptedNetworkSolution expensive = solution("expensive", 0, "3.0", "300");
        AcceptedNetworkSolution best = solution("best", 10_000, "1.0", "120");
        AcceptedNetworkSolution middle = solution("middle", 20_000, "2.0", "180");

        assertThat(archive.add(expensive)).isTrue();
        assertThat(archive.add(best)).isTrue();
        assertThat(archive.add(middle)).isTrue();

        assertThat(archive.snapshot()).extracting(AcceptedNetworkSolution::getId)
                .containsExactly("best", "middle");
        assertThat(archive.best()).isSameAs(best);
        assertThat(archive.size()).isEqualTo(2);
    }

    @Test
    void duplicateGeometryCannotDisplaceItsBetterExactEvaluation() {
        AcceptedSolutionArchive archive = new AcceptedSolutionArchive(2);
        AcceptedNetworkSolution better = solution("better", 0, "1.0", "100");
        AcceptedNetworkSolution worse = solution("worse", 0, "2.0", "200");

        assertThat(archive.add(better)).isTrue();
        assertThat(archive.add(worse)).isFalse();

        assertThat(archive.snapshot()).containsExactly(better);
    }

    @Test
    void rejectsAnAcceptedObjectWithoutRankableExactEconomics() {
        AcceptedNetworkSolution incomplete = new AcceptedNetworkSolution(
                "incomplete", "heatroute", nodes(0), edges(0), connections(),
                BigDecimal.TEN, economics(false, null, null), geometryHash(0));

        assertThatThrownBy(() -> new AcceptedSolutionArchive(1).add(incomplete))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact economics");
    }

    @Test
    void hashMatchDoesNotReplaceFullGeometryEquality() {
        AcceptedSolutionArchive archive = new AcceptedSolutionArchive(2);
        AcceptedNetworkSolution original = solution("original", 0, "1.0", "100");
        AcceptedNetworkSolution forcedCollision = new AcceptedNetworkSolution(
                "collision", "heatroute", nodes(10_000), edges(10_000), connections(),
                BigDecimal.TEN, economics(true, "0.5", "90"), original.getGeometryHash());
        archive.add(original);

        assertThatThrownBy(() -> archive.add(forcedCollision))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hash collision");
    }

    private AcceptedNetworkSolution solution(String id, long offsetMm, String score, String cost) {
        return new AcceptedNetworkSolution(id, "heatroute", nodes(offsetMm), edges(offsetMm), connections(),
                BigDecimal.TEN, economics(true, score, cost), geometryHash(offsetMm));
    }

    private List<RouteNode> nodes(long offsetMm) {
        return List.of(
                new RouteNode("root", "root", new RouteCoordinate(offsetMm / 1000.0, 0),
                        true, true, 0, "source"),
                new RouteNode("demand", "demand", new RouteCoordinate(offsetMm / 1000.0 + 10, 0),
                        false, false, 0, "demand"));
    }

    private List<RouteEdge> edges(long offsetMm) {
        return List.of(new RouteEdge("edge", "root", "demand", 10.0,
                List.of(new RouteCoordinate(offsetMm / 1000.0, 0),
                        new RouteCoordinate(offsetMm / 1000.0 + 10, 0)),
                List.of(), BigDecimal.ONE, 50));
    }

    private List<RouteConnection> connections() {
        return List.of(new RouteConnection("demand", "demand", BigDecimal.ONE,
                "connected", null));
    }

    private String geometryHash(long offsetMm) {
        return FrozenNetworkCandidate.geometryHash(nodes(offsetMm), edges(offsetMm));
    }

    private VariantEconomics economics(boolean complete, String score, String cost) {
        BigDecimal calculatedCost = cost == null ? null : new BigDecimal(cost);
        return new VariantEconomics(complete, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, calculatedCost,
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN,
                score == null ? null : new BigDecimal(score), complete ? List.of() : List.of("incomplete"));
    }
}
