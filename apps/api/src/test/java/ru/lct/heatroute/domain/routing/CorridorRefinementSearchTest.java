package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.depth.DepthProfileIssue;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;

/** Проверяет бюджет и отбор фронта; реальную geometry/sizing/depth проверяет OfficialCorridorDatasetTest. */
class CorridorRefinementSearchTest {
    @Test
    void shortChamberSectionsCanBeRepairedAcrossLevelsButAreNeverReturnedAsFinished() {
        RouteVariant seed = closeChambers(variant("seed", 4, 10, 10, 2));
        RouteVariant intermediate = closeChambers(variant("intermediate", 3, 11, 11, 2));
        RouteVariant repaired = variant("repaired", 2, 12, 12, 2);
        List<String> expanded = new ArrayList<>();
        List<RouteVariant> result = CorridorRefinementSearch.improve(List.of(seed), false, source -> {
            expanded.add(source.getId());
            if (source == seed) return List.of(intermediate);
            if (source == intermediate) return List.of(repaired);
            return List.of();
        });
        assertThat(result).containsExactly(repaired);
        assertThat(expanded).containsExactly("seed", "intermediate", "repaired");
    }

    @Test
    void unrepairedChamberSectionsCannotLeakFromTheBoundedSearch() {
        RouteVariant seed = closeChambers(variant("seed", 5, 10, 10, 2));
        AtomicInteger calls = new AtomicInteger();
        assertThat(CorridorRefinementSearch.improve(List.of(seed), false, source -> {
            int call = calls.incrementAndGet();
            return List.of(closeChambers(variant("next-" + call, chambers(source) - 1, 11, 11, 2)));
        })).isEmpty();
        assertThat(calls).hasValue(3);
    }

    private RouteVariant closeChambers(RouteVariant variant) {
        return variant.withEngineeringIssues(List.of(new RouteValidationIssue(
                "EXPERT_CHAMBER_SPACING_TOO_SHORT", "chamber", "less than 10 m")));
    }

    @Test
    void keepsScoreAndCostFrontsWithinThreeLevelsAndTwoStates() {
        RouteVariant score = variant("score", 10, 10, 10, 2);
        RouteVariant cheap = variant("cheap", 10, 1, 11, 2);
        AtomicInteger calls = new AtomicInteger();
        List<String> expanded = new ArrayList<>();
        List<RouteVariant> result = CorridorRefinementSearch.improve(List.of(score, cheap), false, source -> {
            int call = calls.incrementAndGet(); expanded.add(source.getId());
            return List.of(variant("next-" + call, chambers(source) - 1, 20 + call, 20 + call, 2));
        });
        assertThat(expanded.subList(0, 2)).containsExactly("score", "cheap");
        assertThat(calls).hasValue(6);
        assertThat(result).hasSize(6);
        assertThat(result.get(4).getNodes().stream().filter(n -> "new_branch_chamber".equals(n.getNodeType())).count()).isEqualTo(7);
    }

    @Test
    void neverTradesAwayAConsumerOrSkipsEnabledDepth() {
        AtomicInteger calls = new AtomicInteger();
        RouteVariant source = variant("source", 4, 10, 10, 2);
        assertThat(CorridorRefinementSearch.improve(List.of(source), true, v -> {
            calls.incrementAndGet(); return List.of();
        })).isEmpty();
        assertThat(calls).hasValue(0);
        assertThat(CorridorRefinementSearch.improve(List.of(source), false, v -> List.of(
                variant("missing-consumer", 3, 1, 1, 1)))).isEmpty();
    }

    @Test
    void rejectsEngineeringWarningsAndNonReducingBranches() {
        RouteVariant source = variant("source", 4, 10, 10, 2);
        RouteVariant warning = variant("warning", 3, 1, 1, 2).withEngineeringIssues(
                List.of(new RouteValidationIssue("EXPERT_BEND_ANGLE_OUT_OF_RANGE", "edge", "bad bend")));
        assertThat(CorridorRefinementSearch.improve(List.of(source), false, v -> List.of(warning,
                variant("unchanged", 4, 1, 1, 2)))).isEmpty();
    }

    @Test
    void enforcesExpansionBoundAndCancellation() {
        RouteVariant source = variant("source", 4, 10, 10, 2);
        assertThatThrownBy(() -> CorridorRefinementSearch.improve(List.of(source), false,
                v -> java.util.Collections.nCopies(7, variant("expanded", 3, 1, 1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> {
            try {
                CorridorRefinementSearch.improve(List.of(source), false, v -> {
                    Thread.currentThread().interrupt(); return List.of();
                });
            } finally { Thread.interrupted(); }
        }).isInstanceOf(CancellationException.class);
    }

    @Test
    void duplicateSeedsDoNotOccupyBothBeamSlots() {
        RouteVariant first = network("first", "left-", 4, 0, 1);
        RouteVariant renamed = network("renamed", "right-", 4, 0, 1);
        RouteVariant alternative = network("alternative", "other-", 4, 1, 2);
        List<String> expanded = new ArrayList<>();
        assertThat(CorridorRefinementSearch.improve(List.of(first, renamed, alternative), false, v -> {
            expanded.add(v.getId()); return List.of();
        })).isEmpty();
        assertThat(expanded).containsExactly("first", "alternative");
    }

    @Test
    void duplicateChildrenLeaveRoomForADistinctNetworkAndAreReturnedOnce() {
        RouteVariant source = network("source", "s-", 4, 0, 3);
        RouteVariant child = network("child", "a-", 3, 0, 1);
        RouteVariant duplicate = network("duplicate", "b-", 3, 0, 1);
        RouteVariant other = network("other", "c-", 3, 1, 2);
        List<String> expanded = new ArrayList<>();
        List<RouteVariant> result = CorridorRefinementSearch.improve(List.of(source), false, v -> {
            expanded.add(v.getId());
            return v == source ? List.of(child, duplicate, other) : List.of();
        });
        assertThat(result).containsExactly(child, other);
        assertThat(expanded).containsExactly("source", "child", "other");
    }

    @Test
    void convergingParentsAndLaterLevelsNeverReexpandAnAlreadySeenNetwork() {
        RouteVariant tall = network("tall", "t-", 5, 0, 1);
        RouteVariant shortSeed = network("short", "s-", 3, 0, 2);
        RouteVariant middle = network("middle", "m-", 4, 0, 3);
        RouteVariant leaf = network("leaf", "l-", 2, 0, 4);
        RouteVariant repeatedSeed = network("renamed-seed", "r-", 3, 0, 2);
        RouteVariant repeatedLeaf = network("renamed-leaf", "q-", 2, 0, 4);
        List<String> expanded = new ArrayList<>();
        List<RouteVariant> result = CorridorRefinementSearch.improve(List.of(tall, shortSeed), false, v -> {
            expanded.add(v.getId());
            if (v == tall) return List.of(middle);
            if (v == shortSeed) return List.of(leaf);
            if (v == middle) return List.of(repeatedSeed, repeatedLeaf);
            return List.of();
        });
        assertThat(result).containsExactly(middle, leaf);
        assertThat(expanded).containsExactly("tall", "short", "middle", "leaf");
    }

    @Test
    void visitedStatesAreLocalToEachInvocationAndInvalidCandidatesCannotPoisonThem() {
        RouteVariant seed = network("seed", "s-", 4, 0, 3);
        RouteVariant child = network("child", "c-", 3, 0, 1);
        RouteVariant invalid = child.withEngineeringIssues(List.of(
                new RouteValidationIssue("bad", "edge", "bad geometry")));
        for (int invocation = 0; invocation < 2; invocation++) {
            assertThat(CorridorRefinementSearch.improve(List.of(seed), false,
                    v -> v == seed ? List.of(invalid, child) : List.of())).containsExactly(child);
        }
    }

    @Test
    void unexpandedSeedCanReenterTheFrontierAfterAChamberReduction() {
        RouteVariant bestScore = network("best-score", "s-", 5, 0, 1);
        RouteVariant second = network("second", "t-", 4, 1, 2);
        RouteVariant initiallyPruned = network("pruned-seed", "p-", 3, 2, 3);
        RouteVariant rediscovered = network("rediscovered", "r-", 3, 2, 3);
        RouteVariant improvement = network("improvement", "i-", 2, 3, 1);
        List<String> expanded = new ArrayList<>();

        List<RouteVariant> result = CorridorRefinementSearch.improve(
                List.of(bestScore, second, initiallyPruned), false, source -> {
                    expanded.add(source.getId());
                    if (source == bestScore) return List.of(rediscovered);
                    if (source == rediscovered) return List.of(improvement);
                    return List.of();
                });

        assertThat(result).containsExactly(rediscovered, improvement);
        assertThat(expanded).containsExactly("best-score", "second", "rediscovered", "improvement");
    }

    @Test
    void aReturnedButUnexpandedChildMayBeExpandedOnALaterPass() {
        RouteVariant seed = network("seed", "s-", 5, 0, 1);
        RouteVariant first = network("first", "f-", 4, 1, 1);
        RouteVariant second = network("second", "t-", 4, 2, 2);
        RouteVariant pruned = network("pruned", "p-", 3, 3, 3);
        RouteVariant revisited = network("revisited", "r-", 3, 3, 3);
        RouteVariant improvement = network("improvement", "i-", 2, 4, 1);
        List<String> expanded = new ArrayList<>();

        List<RouteVariant> result = CorridorRefinementSearch.improve(List.of(seed), false, source -> {
            expanded.add(source.getId());
            if (source == seed) return List.of(first, second, pruned);
            if (source == first) return List.of(revisited);
            if (source == revisited) return List.of(improvement);
            return List.of();
        });

        assertThat(result).containsExactly(first, second, pruned, improvement);
        assertThat(expanded).containsExactly("seed", "first", "second", "revisited");
    }

    @Test
    void duplicateChildrenStillCountTowardTheSixCandidateBudget() {
        RouteVariant seed = network("seed", "s-", 4, 0, 3);
        RouteVariant child = network("child", "c-", 3, 0, 1);
        assertThatThrownBy(() -> CorridorRefinementSearch.improve(List.of(seed), false,
                v -> java.util.Collections.nCopies(7, child))).isInstanceOf(IllegalArgumentException.class);
        assertThat(CorridorRefinementSearch.improve(List.of(seed), false,
                v -> v == seed ? java.util.Collections.nCopies(6, child) : List.of())).containsExactly(child);
    }

    @Test
    void repairedPassExpandsOnlyTwoDistinctLegalSeedsAndNeverItsOwnChildren() {
        RouteVariant first = network("first", "a-", 5, 0, 1);
        RouteVariant renamed = network("renamed", "b-", 5, 0, 1);
        RouteVariant role = new RouteVariant("role", "cheapest", first.getNodes(), first.getEdges(),
                first.getConnections(), first.getTotalLengthM(), List.of(), List.of(), null, first.getEconomics(), null);
        RouteVariant second = network("second", "c-", 5, 1, 2);
        RouteVariant pruned = network("pruned", "d-", 5, 2, 3);
        RouteVariant child = network("child", "e-", 4, 0, 1);
        List<String> expanded = new ArrayList<>();
        assertThat(CorridorRefinementSearch.improveRepaired(List.of(first, renamed, role, second, pruned), false, v -> {
            expanded.add(v.getId());
            return v == child ? List.of(network("grandchild", "g-", 3, 0, 1)) : List.of(child);
        })).containsExactly(child);
        assertThat(expanded).containsExactly("first", "second");
    }

    @Test
    void repairedPassRejectsIncompleteSeedsIntermediateWarningsAndLostConsumers() {
        RouteVariant source = network("source", "s-", 4, 0, 1);
        List<RouteVariant> touched = new ArrayList<>();
        assertThat(CorridorRefinementSearch.improveRepaired(List.of(closeChambers(source)), false, v -> {
            touched.add(v); return List.of();
        })).isEmpty();
        assertThat(touched).isEmpty();
        assertThat(CorridorRefinementSearch.improveRepaired(List.of(source), true, v -> {
            touched.add(v); return List.of();
        })).isEmpty();
        assertThat(touched).isEmpty();
        assertThat(CorridorRefinementSearch.improveRepaired(List.of(source), false, v -> List.of(
                closeChambers(network("close", "c-", 3, 0, 1)),
                variant("lost", 3, 1, 1, 0), network("unchanged", "u-", 4, 0, 1)))).isEmpty();
    }

    @Test
    void repairedPassKeepsMaximumCoverageAndSixCompletionsPerSeedBound() {
        RouteVariant source = variant("source", 4, 10, 10, 2);
        RouteVariant partial = variant("partial", 4, 1, 1, 1);
        List<String> expanded = new ArrayList<>();
        RouteVariant child = variant("child", 3, 10, 10, 2);
        assertThat(CorridorRefinementSearch.improveRepaired(List.of(partial, source), false, v -> {
            expanded.add(v.getId()); return List.of(child);
        })).containsExactly(child);
        assertThat(expanded).containsExactly("source");
        assertThatThrownBy(() -> CorridorRefinementSearch.improveRepaired(List.of(source), false,
                v -> java.util.Collections.nCopies(7, child))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repairedPassNeverReturnsCheaperDepthInvalidCandidate() {
        RouteVariant source = withDepth(variant("source", 4, 10, 10, 2), true, false);
        RouteVariant good = withDepth(variant("good", 3, 9, 9, 2), true, false);
        RouteVariant missing = variant("missing", 3, 1, 1, 2);
        RouteVariant incomplete = withDepth(variant("incomplete", 3, 1, 1, 2), false, true);
        RouteVariant issue = withDepth(variant("issue", 3, 1, 1, 2), true, true);
        assertThat(incomplete.isValid()).isTrue();
        assertThat(CorridorRefinementSearch.improveRepaired(List.of(source), true,
                v -> List.of(missing, incomplete, issue, good))).containsExactly(good);
    }

    private RouteVariant withDepth(RouteVariant source, boolean complete, boolean issue) {
        var depth = new DepthProfileResult(complete, List.of(
                new DepthProfilePoint(BigDecimal.ZERO, new BigDecimal("3")),
                new DepthProfilePoint(new BigDecimal("100"), new BigDecimal("3"))), List.of(),
                issue ? List.of(new DepthProfileIssue("NO_VERTICAL_PASSAGE", "crossing", "no passage")) : List.of(),
                new BigDecimal("100"), new BigDecimal("100"));
        List<RouteEdge> edges = source.getEdges().stream().map(e -> new RouteEdge(e.getId(), e.getUpstreamNodeId(),
                e.getDownstreamNodeId(), e.getLengthM().doubleValue(), e.getCoordinates(), e.getSections(),
                e.getFlowTph(), e.getDiameter(), depth)).collect(java.util.stream.Collectors.toList());
        return new RouteVariant(source.getId(), source.getStrategy(), source.getNodes(), edges, source.getConnections(),
                source.getTotalLengthM(), List.of(), List.of(), null, source.getEconomics(), null);
    }

    /** Валидная инцидентность для проверки идентичности; инженерный finish здесь не подменяется. */
    private RouteVariant network(String id, String prefix, int chambers, double offset, int cost) {
        List<RouteNode> nodes = new ArrayList<>();
        nodes.add(new RouteNode("root", "existing_chamber", new RouteCoordinate(0, -10), true, true, 1, "existing"));
        for (int i = 0; i < chambers; i++) nodes.add(new RouteNode(prefix + i, "new_branch_chamber",
                new RouteCoordinate(i * 10, offset), true, false, 0, null));
        nodes.add(new RouteNode("demand:d", "demand", new RouteCoordinate(100, 0), false, false, 0, "d"));
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) {
            RouteNode from = nodes.get(i - 1), to = nodes.get(i);
            edges.add(new RouteEdge(prefix + "edge-" + i, from.getId(), to.getId(), 10,
                    List.of(from.getCoordinate(), to.getCoordinate()), List.of(), BigDecimal.ONE, 100));
        }
        RouteVariant assessment = variant(id, chambers, cost, cost, 1);
        return new RouteVariant(id, "engineering", nodes, edges,
                List.of(new RouteConnection("d", "d", BigDecimal.ONE, "connected", null)),
                assessment.getTotalLengthM(), List.of(), List.of(), null, assessment.getEconomics(), null);
    }

    private int chambers(RouteVariant variant) {
        return (int) variant.getNodes().stream().filter(n -> "new_branch_chamber".equals(n.getNodeType())).count();
    }

    private RouteVariant variant(String id, int chambers, int cost, int score, int coverage) {
        List<RouteNode> nodes = new ArrayList<>();
        for (int i = 0; i < chambers; i++) nodes.add(new RouteNode("c" + i, "new_branch_chamber",
                new RouteCoordinate(i, 0), true, false, 0, null));
        List<RouteConnection> connections = new ArrayList<>();
        for (int i = 0; i < coverage; i++) connections.add(new RouteConnection("d" + i, "d" + i, BigDecimal.ONE, "connected", null));
        BigDecimal length = BigDecimal.valueOf(100);
        VariantEconomics economics = new VariantEconomics(true, BigDecimal.valueOf(cost), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(cost),
                length, BigDecimal.ZERO, length, BigDecimal.valueOf(score), List.of());
        return new RouteVariant(id, "engineering", nodes, List.of(new RouteEdge("e", "a", "b", 100)),
                connections, length, List.of(), List.of(), null, economics, null);
    }
}
