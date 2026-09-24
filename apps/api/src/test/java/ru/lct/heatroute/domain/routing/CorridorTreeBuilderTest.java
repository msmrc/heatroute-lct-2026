package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class CorridorTreeBuilderTest {
    private final CorridorTreeBuilder builder = new CorridorTreeBuilder();

    @Test
    void sharesCommonCorridorAndNeverExceedsFourTotalConnections() {
        List<Coordinate> points = points(0, 0, 5, 0, 10, 0, 10, 5, 10, -5, 15, 0);
        List<int[]> links = links(0, 1, 1, 2, 2, 3, 2, 4, 2, 5);
        Map<Integer, Integer> stubs = Map.of(3, 1, 4, 1, 5, 1);

        List<int[]> tree = build(points, links, 1, stubs, true, 8, 3);

        assertThat(edgeKeys(tree)).isEqualTo(edgeKeys(links));
        assertTree(tree, points.size(), 0, 1, stubs);
        assertThat(degrees(tree, points.size())[2]).isEqualTo(4);
    }

    @Test
    void rootCapacityOneRejectsTwoIndependentRaysButTwoAcceptsThem() {
        List<Coordinate> points = points(0, 0, -10, 0, 10, 0);
        List<int[]> links = links(0, 1, 0, 2);
        Map<Integer, Integer> stubs = Map.of(1, 1, 2, 1);

        assertThat(build(points, links, 1, stubs, true, 0, 0)).isNull();
        List<int[]> tree = build(points, links, 2, stubs, true, 100, 0);
        assertThat(tree).hasSize(2);
        assertTree(tree, points.size(), 0, 2, stubs);
    }

    @Test
    void reservesMultipleStubsBeforeSearchingAndBypassesASaturatedUnconnectedPort() {
        List<Coordinate> points = points(0, 0, 5, 0, 10, 0, 0, 5, 10, 5);
        List<int[]> links = links(0, 1, 1, 2, 0, 3, 3, 4, 4, 2);
        Map<Integer, Integer> stubs = Map.of(1, 3, 2, 1);

        List<int[]> tree = build(points, links, 1, stubs, true, 0, 0);

        assertThat(edgeKeys(tree)).containsExactlyInAnyOrder("0:3", "3:4", "2:4", "1:2");
        assertTree(tree, points.size(), 0, 1, stubs);
        assertThat(degrees(tree, points.size())[1]).isEqualTo(1);
        assertThat(build(points, links(0, 1, 1, 2), 1, stubs, true, 0, 0)).isNull();
        assertThat(build(points, links, 1, Map.of(1, 4), true, 0, 0)).isNull();
    }

    @Test
    void terminalsCrossedOnFirstPathAreAlreadyConnectedAndTheirStubsStillCount() {
        List<Coordinate> points = points(0, 0, 5, 0, 10, 0);
        Map<Integer, Integer> stubs = Map.of(1, 2, 2, 1);
        List<int[]> tree = build(points, links(0, 1, 1, 2), 1, stubs, true, 0, 0);

        assertThat(tree).hasSize(2);
        assertTree(tree, points.size(), 0, 1, stubs);
        assertThat(degrees(tree, points.size())[1] + stubs.get(1)).isEqualTo(4);
    }

    @Test
    void farthestFirstChangesOnlyTheInitialSeed() {
        List<Coordinate> points = points(0, 0, 4, 0, 0, 10, 4, 10);
        List<int[]> links = links(0, 1, 1, 3, 3, 2, 2, 0);
        Map<Integer, Integer> stubs = Map.of(1, 1, 2, 1);

        List<int[]> farthest = build(points, links, 1, stubs, true, 0, 0);
        List<int[]> nearest = build(points, links, 1, stubs, false, 0, 0);

        assertThat(edgeKeys(farthest)).containsExactlyInAnyOrder("0:2", "2:3", "1:3");
        assertThat(edgeKeys(nearest)).containsExactlyInAnyOrder("0:1", "1:3", "2:3");
        assertTree(farthest, points.size(), 0, 1, stubs);
        assertTree(nearest, points.size(), 0, 1, stubs);
    }

    @Test
    void bendPenaltyPrefersOneTurnOverAnEqualLengthZigzag() {
        List<Coordinate> points = points(0, 0, 0, 2, 4, 2, 4, 4, 8, 4, 8, 0);
        List<int[]> links = links(0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 4);

        assertThat(edgeKeys(build(points, links, 1, Map.of(4, 1), true, 0, 0)))
                .containsExactlyInAnyOrder("0:1", "1:2", "2:3", "3:4");
        assertThat(edgeKeys(build(points, links, 1, Map.of(4, 1), true, 0, 3)))
                .containsExactlyInAnyOrder("0:5", "4:5");
    }

    @Test
    void keepsDifferentArrivalDirectionsUntilTheirFutureTurnCostIsKnown() {
        List<Coordinate> points = points(8, 0, 4, 0, 8, 2, 4, 2, 0, 2);
        List<int[]> links = links(0, 1, 1, 3, 0, 2, 2, 3, 3, 4);

        List<int[]> tree = build(points, links, 1, Map.of(4, 1), true, 0, 10);

        assertThat(edgeKeys(tree)).containsExactlyInAnyOrder("0:2", "2:3", "3:4");
        assertTree(tree, points.size(), 0, 1, Map.of(4, 1));
    }

    @Test
    void junctionPenaltyReusesAnExistingChamberWithASpareSlot() {
        List<Coordinate> points = points(0, 0, 5, 0, 10, 0, 5, 4, 10, 4);
        List<int[]> links = links(0, 1, 1, 2, 1, 3, 2, 4, 4, 3);
        Map<Integer, Integer> stubs = Map.of(2, 2, 3, 1);

        List<int[]> freeJunction = build(points, links, 1, stubs, true, 0, 0);
        List<int[]> costlyJunction = build(points, links, 1, stubs, true, 6, 0);

        assertThat(edgeKeys(freeJunction)).containsExactlyInAnyOrder("0:1", "1:2", "1:3");
        assertThat(edgeKeys(costlyJunction)).containsExactlyInAnyOrder("0:1", "1:2", "2:4", "3:4");
        assertTree(costlyJunction, points.size(), 0, 1, stubs);
        assertThat(degrees(costlyJunction, points.size())[2] + stubs.get(2)).isEqualTo(4);
    }

    @Test
    void disconnectedGraphReturnsNoPartialTree() {
        List<Coordinate> points = points(0, 0, 1, 0, 10, 0, 11, 0);
        assertThat(build(points, links(0, 1, 2, 3), 2, Map.of(1, 1, 3, 1), false, 0, 0)).isNull();
        assertThat(build(points, List.of(), 2, Map.of(1, 1), true, 0, 0)).isNull();
    }

    @Test
    void rotationAndMetricTranslationPreserveUniqueBendOptimalTree() {
        List<Coordinate> original = points(0, 0, 0, 2, 4, 2, 4, 4, 8, 4, 8, 0);
        List<int[]> links = links(0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 4);
        Set<String> expected = edgeKeys(build(original, links, 1, Map.of(4, 1), true, 0, 10));
        for (double angle : new double[] {0.17, 0.71, 1.8, 3.9}) {
            List<Coordinate> rotated = original.stream().map(point -> new Coordinate(
                    600000 + point.x * Math.cos(angle) - point.y * Math.sin(angle),
                    6000000 + point.x * Math.sin(angle) + point.y * Math.cos(angle)))
                    .collect(Collectors.toList());
            assertThat(edgeKeys(build(rotated, links, 1, Map.of(4, 1), true, 0, 10))).isEqualTo(expected);
        }
    }

    @Test
    void shuffledReversedAndDuplicateLinksDoNotChangeTieBreakingOrReturnedOrder() {
        List<Coordinate> points = gridPoints(4);
        List<int[]> links = gridLinks(4);
        Map<Integer, Integer> stubs = Map.of(3, 1, 12, 1, 15, 1, 6, 1);
        List<String> expected = orderedEdges(build(points, links, 2, stubs, true, 7, 2));
        Random random = new Random(5061);
        for (int trial = 0; trial < 30; trial++) {
            List<int[]> shuffled = new ArrayList<>();
            for (int[] link : links) {
                shuffled.add(random.nextBoolean() ? new int[] {link[0], link[1]} : new int[] {link[1], link[0]});
            }
            shuffled.add(new int[] {links.get(0)[0], links.get(0)[1]});
            Collections.shuffle(shuffled, random);
            List<int[]> tree = build(points, shuffled, 2, stubs, true, 7, 2);
            assertThat(orderedEdges(tree)).containsExactlyElementsOf(expected);
            assertTree(tree, points.size(), 0, 2, stubs);
        }
    }

    @Test
    void singleTerminalCostMatchesExhaustiveSimplePathEnumerationOnSmallGrid() {
        List<Coordinate> points = gridPoints(3);
        List<int[]> links = gridLinks(3);
        for (int root = 0; root < points.size(); root++) {
            for (int terminal = 0; terminal < points.size(); terminal++) {
                if (root == terminal) continue;
                for (double penalty : new double[] {0, 1, 17}) {
                    List<int[]> tree = builder.build(points, links, root, 1, Map.of(terminal, 1), true, 0, penalty);
                    assertTree(tree, points.size(), root, 1, Map.of(terminal, 1));
                    double expected = enumerate(points, links, -1, root, terminal, new boolean[points.size()], penalty);
                    double actual = enumerate(points, tree, -1, root, terminal, new boolean[points.size()], penalty);
                    assertThat(actual).as("root=%s terminal=%s bend=%s", root, terminal, penalty).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void noTerminalsOrOnlyRootStubsNeedNoEdgesAndStillRespectRootCapacity() {
        List<Coordinate> points = points(0, 0, 1, 0);
        List<int[]> links = links(0, 1);
        assertThat(build(points, links, 0, Map.of(), true, 0, 0)).isEmpty();
        assertThat(build(points, links, 2, Map.of(0, 2), true, 0, 0)).isEmpty();
        assertThat(build(points, links, 1, Map.of(0, 2), true, 0, 0)).isNull();
        assertThat(build(points, links, 1, Map.of(0, 1, 1, 1), true, 0, 0)).isNull();
        assertThat(build(points, links, 0, Map.of(1, 0), true, 0, 0)).isEmpty();
    }

    @Test
    void doesNotMutateOrReturnBorrowedInputsAndDoesNotKeepStateBetweenCalls() {
        List<Coordinate> points = points(0, 0, 5, 0, 10, 0);
        List<int[]> links = links(2, 1, 1, 0);
        List<String> beforeLinks = orderedEdges(links);
        List<Coordinate> beforePoints = points.stream().map(Coordinate::copy).collect(Collectors.toList());
        Map<Integer, Integer> stubs = new HashMap<>(Map.of(2, 1));

        List<int[]> result = build(points, links, 1, stubs, true, 0, 0);
        result.get(0)[0] = 99;
        result.clear();

        assertThat(orderedEdges(links)).containsExactlyElementsOf(beforeLinks);
        assertThat(points).containsExactlyElementsOf(beforePoints);
        assertThat(stubs).containsExactlyEntriesOf(Map.of(2, 1));
        assertThat(edgeKeys(build(points, links, 1, stubs, true, 0, 0)))
                .containsExactlyInAnyOrder("0:1", "1:2");
    }

    @Test
    void propagatesCancellationWithoutClearingInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> build(points(0, 0), List.of(), 0, Map.of(), true, 0, 0))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void observesCancellationDuringInputPreparation() {
        List<Coordinate> points = new ArrayList<>(gridPoints(3)) {
            @Override public Coordinate get(int index) {
                if (index == 3) Thread.currentThread().interrupt();
                return super.get(index);
            }
        };
        try {
            assertThatThrownBy(() -> build(points, gridLinks(3), 1, Map.of(8, 1), true, 0, 0))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void validatesGraphBoundsLengthsIndicesAndNonnegativeFiniteCosts() {
        List<Coordinate> points = points(0, 0, 1, 0);
        List<int[]> links = links(0, 1);
        assertInvalid(() -> build(List.of(), links, 1, Map.of(), true, 0, 0));
        assertInvalid(() -> build(Collections.nCopies(100001, new Coordinate(0, 0)), List.of(), 1, Map.of(), true, 0, 0));
        assertInvalid(() -> build(points, Collections.nCopies(400001, new int[] {0, 1}), 1, Map.of(), true, 0, 0));
        assertInvalid(() -> build(points, links(0, 2), 1, Map.of(), true, 0, 0));
        assertInvalid(() -> build(points, links(0, 0), 1, Map.of(), true, 0, 0));
        assertInvalid(() -> build(points(0, 0, 0, 0), links, 1, Map.of(), true, 0, 0));
        assertInvalid(() -> build(points(Double.NaN, 0, 1, 0), links, 1, Map.of(), true, 0, 0));
        assertInvalid(() -> build(points, links, -1, Map.of(), true, 0, 0));
        assertInvalid(() -> build(points, links, 1, Map.of(2, 1), true, 0, 0));
        assertInvalid(() -> build(points, links, 1, Map.of(1, -1), true, 0, 0));
        assertInvalid(() -> build(points, links, 1, Map.of(), true, -1, 0));
        assertInvalid(() -> build(points, links, 1, Map.of(), true, 0, Double.POSITIVE_INFINITY));
    }

    private List<int[]> build(List<Coordinate> points, List<int[]> links, int rootCapacity,
            Map<Integer, Integer> stubs, boolean farthest, double junction, double bend) {
        return builder.build(points, links, 0, rootCapacity, stubs, farthest, junction, bend);
    }

    @Test
    void metricClosureBeatsFarthestGreedyOnUnequalAlternativeCorridors() {
        List<Coordinate> points = points(0, 0, 4, 6, 6, 5, 0, 6, 6, 0, 6, 6);
        List<int[]> links = links(0, 3, 3, 1, 1, 5, 5, 2, 0, 4, 4, 2);
        Map<Integer, Integer> stubs = Map.of(1, 1, 2, 1);
        List<int[]> greedy = build(points, links, 1, stubs, true, 0, 0);

        List<int[]> metric = builder.buildMetricClosure(points, links, 0, 1, stubs, 0);

        assertTree(metric, points.size(), 0, 1, stubs);
        assertThat(length(points, greedy)).isEqualTo(14);
        assertThat(length(points, metric)).isEqualTo(13);
        assertThat(edgeKeys(metric)).containsExactlyInAnyOrder("0:3", "1:3", "1:5", "2:5");
    }

    @Test
    void metricClosureRemovesUnionCyclesAndPrunesNonterminalLeavesBeforeCheckingCapacity() {
        List<Coordinate> points = points(0, 0, 10, 0, 0, 10, 10, 10, 10, 40, 40, 10);
        List<int[]> links = links(0, 1, 1, 3, 0, 2, 2, 3, 3, 4, 3, 5);
        Map<Integer, Integer> stubs = Map.of(4, 1, 5, 1);

        List<int[]> tree = builder.buildMetricClosure(points, links, 0, 1, stubs, 3);

        // Кратчайшие root→4 и root→5 образуют квадрат. Второй MST оставляет 0→1 листом,
        // его удаление освобождает второе ребро корня; проверять rootCapacity до обрезки нельзя.
        assertThat(edgeKeys(tree)).containsExactlyInAnyOrder("0:2", "2:3", "3:4", "3:5");
        assertTree(tree, points.size(), 0, 1, stubs);
        assertThat(degrees(tree, points.size())[1]).isZero();
        assertThat(length(points, tree)).isEqualTo(80);
    }

    @Test
    void metricClosureRejectsDisconnectedRequiredNodesWithoutReturningAPartialTree() {
        List<Coordinate> points = points(0, 0, 1, 0, 10, 0, 11, 0);
        List<int[]> links = links(0, 1, 2, 3);

        assertThat(builder.buildMetricClosure(points, links, 0, 2, Map.of(1, 1, 3, 1), 3)).isNull();
        assertThat(builder.buildMetricClosure(points, List.of(), 0, 2, Map.of(1, 1), 0)).isNull();
    }

    @Test
    void metricClosureValidatesFinalRootCapacityAndEveryReservedStub() {
        List<Coordinate> rays = points(0, 0, -10, 0, 10, 0);
        List<int[]> rayLinks = links(0, 1, 0, 2);
        assertThat(builder.buildMetricClosure(rays, rayLinks, 0, 1, Map.of(1, 1, 2, 1), 0)).isNull();
        List<int[]> twoRays = builder.buildMetricClosure(rays, rayLinks, 0, 2, Map.of(1, 1, 2, 1), 0);
        assertTree(twoRays, rays.size(), 0, 2, Map.of(1, 1, 2, 1));

        List<Coordinate> corridor = points(0, 0, 5, 0, 10, 0);
        List<int[]> corridorLinks = links(0, 1, 1, 2);
        assertThat(builder.buildMetricClosure(corridor, corridorLinks, 0, 1, Map.of(1, 3, 2, 1), 0)).isNull();
        List<int[]> allowed = builder.buildMetricClosure(corridor, corridorLinks, 0, 1, Map.of(1, 2, 2, 1), 0);
        assertTree(allowed, corridor.size(), 0, 1, Map.of(1, 2, 2, 1));
        assertThat(builder.buildMetricClosure(corridor, corridorLinks, 0, 1, Map.of(2, 3), 0)).hasSize(2);
        assertThat(builder.buildMetricClosure(corridor, corridorLinks, 0, 1, Map.of(2, 4), 0)).isNull();
        assertThat(builder.buildMetricClosure(corridor, corridorLinks, 0, 1, Map.of(0, 1, 2, 1), 0)).isNull();
    }

    @Test
    void metricClosurePreservesGeometryUnderNodeRelabelingLinkShuffleAndReversedPairs() {
        List<Coordinate> points = gridPoints(4);
        List<int[]> links = gridLinks(4);
        Map<Integer, Integer> stubs = Map.of(3, 1, 12, 1, 15, 1, 6, 1);
        Set<String> expected = geometryEdges(points, builder.buildMetricClosure(points, links, 0, 4, stubs, 3));
        Random random = new Random(5062);
        for (int repeat = 0; repeat < 20; repeat++) {
            List<Integer> permutation = new ArrayList<>();
            for (int node = 0; node < points.size(); node++) permutation.add(node);
            Collections.shuffle(permutation, random);
            int[] translatedIndex = new int[points.size()];
            List<Coordinate> reorderedPoints = new ArrayList<>();
            for (int index = 0; index < permutation.size(); index++) {
                translatedIndex[permutation.get(index)] = index;
                reorderedPoints.add(points.get(permutation.get(index)));
            }
            List<int[]> reorderedLinks = new ArrayList<>();
            for (int[] link : links) {
                int a = translatedIndex[link[0]];
                int b = translatedIndex[link[1]];
                reorderedLinks.add(random.nextBoolean() ? new int[] {a, b} : new int[] {b, a});
            }
            reorderedLinks.add(reorderedLinks.get(0).clone());
            Collections.shuffle(reorderedLinks, random);
            Map<Integer, Integer> reorderedStubs = new HashMap<>();
            stubs.forEach((node, count) -> reorderedStubs.put(translatedIndex[node], count));

            List<int[]> actual = builder.buildMetricClosure(reorderedPoints, reorderedLinks,
                    translatedIndex[0], 4, reorderedStubs, 3);

            assertTree(actual, points.size(), translatedIndex[0], 4, reorderedStubs);
            assertThat(geometryEdges(reorderedPoints, actual)).isEqualTo(expected);
        }
    }

    @Test
    void metricClosureIsRotationEquivalentWhenPathAndMstChoicesAreNondegenerate() {
        List<Coordinate> points = points(0, 0, 4, 6, 6, 5, 0, 6, 6, 0, 6, 6);
        List<int[]> links = links(0, 3, 3, 1, 1, 5, 5, 2, 0, 4, 4, 2);
        Map<Integer, Integer> stubs = Map.of(1, 1, 2, 1);
        Set<String> expected = edgeKeys(builder.buildMetricClosure(points, links, 0, 1, stubs, 3));
        for (double angle : new double[] {0.3, 0.9, 1.9, 4.2}) {
            List<Coordinate> transformed = points.stream().map(point -> new Coordinate(
                    610000 + point.x * Math.cos(angle) - point.y * Math.sin(angle),
                    6100000 + point.x * Math.sin(angle) + point.y * Math.cos(angle)))
                    .collect(Collectors.toList());
            assertThat(edgeKeys(builder.buildMetricClosure(transformed, links, 0, 1, stubs, 3))).isEqualTo(expected);
        }
    }

    @Test
    void metricClosureSinglePairMatchesExhaustiveBendAwarePaths() {
        List<Coordinate> points = gridPoints(3);
        List<int[]> links = gridLinks(3);
        for (int root = 0; root < points.size(); root++) {
            for (int terminal = 0; terminal < points.size(); terminal++) {
                if (root == terminal) continue;
                for (double penalty : new double[] {0, 1, 17}) {
                    List<int[]> tree = builder.buildMetricClosure(points, links, root, 1, Map.of(terminal, 1), penalty);
                    assertTree(tree, points.size(), root, 1, Map.of(terminal, 1));
                    double expected = enumerate(points, links, -1, root, terminal, new boolean[points.size()], penalty);
                    assertThat(enumerate(points, tree, -1, root, terminal, new boolean[points.size()], penalty))
                            .as("root=%s terminal=%s bend=%s", root, terminal, penalty).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void metricClosureBoundsRequiredNodesAt64WithoutReducingTheGreedyCandidate() {
        List<Coordinate> points = new ArrayList<>();
        List<int[]> links = new ArrayList<>();
        Map<Integer, Integer> stubs = new HashMap<>();
        for (int node = 0; node < 65; node++) {
            points.add(new Coordinate(node, 0));
            if (node > 0) {
                links.add(new int[] {node - 1, node});
                stubs.put(node, 1);
            }
        }
        assertThat(builder.buildMetricClosure(points, links, 0, 1, stubs, 3)).isNull();
        assertThat(build(points, links, 1, stubs, true, 0, 3)).hasSize(64);
        stubs.remove(64);
        List<int[]> metric = builder.buildMetricClosure(points, links, 0, 1, stubs, 3);
        assertThat(metric).hasSize(63);
        assertTree(metric, points.size(), 0, 1, stubs);
    }

    @Test
    void metricClosureKeepsInputsUntouchedAndHandlesOnlyRootOrEmptyTerminalSets() {
        List<Coordinate> points = points(0, 0, 1, 0);
        List<int[]> links = links(1, 0);
        List<String> originalLinks = orderedEdges(links);
        assertThat(builder.buildMetricClosure(points, links, 0, 0, Map.of(), 3)).isEmpty();
        assertThat(builder.buildMetricClosure(points, links, 0, 2, Map.of(0, 2), 3)).isEmpty();
        assertThat(builder.buildMetricClosure(points, links, 0, 1, Map.of(0, 2), 3)).isNull();
        assertThat(builder.buildMetricClosure(points, links, 0, 0, Map.of(1, 0), 3)).isEmpty();
        Map<Integer, Integer> stubs = new HashMap<>(Map.of(1, 2));
        List<int[]> result = builder.buildMetricClosure(points, links, 0, 1, stubs, 3);
        result.get(0)[0] = 99;
        result.clear();
        assertThat(orderedEdges(links)).containsExactlyElementsOf(originalLinks);
        assertThat(points).containsExactly(new Coordinate(0, 0), new Coordinate(1, 0));
        assertThat(stubs).containsExactlyEntriesOf(Map.of(1, 2));
        assertThat(builder.buildMetricClosure(points, links, 0, 1, stubs, 3)).hasSize(1);
    }

    @Test
    void metricClosurePropagatesCancellationAndRejectsInvalidPenalty() {
        List<Coordinate> points = points(0, 0, 1, 0);
        List<int[]> links = links(0, 1);
        assertInvalid(() -> builder.buildMetricClosure(points, links, 0, 1, Map.of(1, 1), -1));
        assertInvalid(() -> builder.buildMetricClosure(points, links, 0, 1, Map.of(1, 1), Double.NaN));
        assertInvalid(() -> builder.buildMetricClosure(points, links, 0, -1, Map.of(1, 1), 0));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> builder.buildMetricClosure(points, links, 0, 1, Map.of(1, 1), 3))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private double length(List<Coordinate> points, List<int[]> edges) {
        return edges.stream().mapToDouble(edge -> points.get(edge[0]).distance(points.get(edge[1]))).sum();
    }

    @Test
    void weightedBuildersChooseTheActuallyShorterCurvedPortNotTheClosestChord() {
        List<Coordinate> points = points(0, 0, 1, 0, 0, 4, 2, 0);
        List<int[]> links = links(0, 1, 1, 3, 0, 2, 2, 3);
        List<Double> weights = List.of(1.0, 100.0, 4.0, 5.0);
        Map<Integer, Integer> reservations = Map.of(3, 3);

        assertThat(edgeKeys(build(points, links, 1, reservations, true, 0, 0)))
                .containsExactlyInAnyOrder("0:1", "1:3");
        List<int[]> greedy = builder.buildWeighted(points, links, weights, 0, 1, reservations, true, 0, 0);
        List<int[]> metric = builder.buildMetricClosureWeighted(points, links, weights, 0, 1, reservations, 0);

        for (List<int[]> result : List.of(greedy, metric)) {
            assertThat(edgeKeys(result)).containsExactlyInAnyOrder("0:2", "2:3");
            assertTree(result, points.size(), 0, 1, reservations);
            assertThat(degrees(result, points.size())[3]).isEqualTo(1);
        }
        assertThat(weights).containsExactly(1.0, 100.0, 4.0, 5.0);
    }

    @Test
    void weightedDuplicatesUseMinimumLengthIndependentlyOfOrderAndPairDirection() {
        List<Coordinate> points = points(0, 0, 1, 0, 0, 4, 2, 0);
        List<int[]> links = links(0, 1, 1, 3, 0, 2, 2, 3, 3, 1);
        List<Double> weights = List.of(1.0, 100.0, 4.0, 5.0, 5.0);
        Random random = new Random(54001);
        List<String> expected = orderedEdges(builder.buildWeighted(points, links, weights, 0, 1, Map.of(3, 3), true, 0, 0));
        assertThat(edgeKeys(builder.buildWeighted(points, links, weights, 0, 1, Map.of(3, 3), true, 0, 0)))
                .containsExactlyInAnyOrder("0:1", "1:3");
        for (int repeat = 0; repeat < 25; repeat++) {
            List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3, 4));
            Collections.shuffle(order, random);
            List<int[]> shuffled = new ArrayList<>();
            List<Double> shuffledWeights = new ArrayList<>();
            for (int index : order) {
                int[] pair = links.get(index);
                shuffled.add(random.nextBoolean() ? pair.clone() : new int[] {pair[1], pair[0]});
                shuffledWeights.add(weights.get(index));
            }
            assertThat(orderedEdges(builder.buildWeighted(points, shuffled, shuffledWeights,
                    0, 1, Map.of(3, 3), true, 0, 0))).containsExactlyElementsOf(expected);
            assertThat(orderedEdges(builder.buildMetricClosureWeighted(points, shuffled, shuffledWeights,
                    0, 1, Map.of(3, 3), 0))).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void weightedEuclideanInputsPreserveBothExistingCandidatesWhenThereAreNoTransitLimits() {
        List<Coordinate> points = gridPoints(4);
        List<int[]> links = gridLinks(4);
        List<Double> weights = links.stream().map(edge -> points.get(edge[0]).distance(points.get(edge[1])))
                .collect(Collectors.toList());
        Map<Integer, Integer> stubs = Map.of(3, 1, 12, 1, 15, 1, 6, 1);
        for (boolean farthest : new boolean[] {true, false}) {
            assertThat(orderedEdges(builder.buildWeighted(points, links, weights, 0, 4, stubs, farthest, 25, 3)))
                    .containsExactlyElementsOf(orderedEdges(build(points, links, 4, stubs, farthest, 25, 3)));
        }
        assertThat(orderedEdges(builder.buildMetricClosureWeighted(points, links, weights, 0, 4, stubs, 3)))
                .containsExactlyElementsOf(orderedEdges(builder.buildMetricClosure(points, links, 0, 4, stubs, 3)));
    }

    @Test
    void weightedMetricDistancesCannotTransitThroughVirtualLeavesEvenAfterRecordingTheirArrival() throws Exception {
        List<Coordinate> points = points(0, 0, 1, 0, 2, 0, 0, 3, 2, 3);
        List<int[]> links = links(0, 1, 1, 2, 0, 3, 3, 4, 4, 2);
        List<Double> weights = List.of(1.0, 1.0, 3.0, 2.0, 3.0);
        Class<?> graphType = Class.forName(CorridorTreeBuilder.class.getName() + "$Graph");
        Class<?> treeType = Class.forName(CorridorTreeBuilder.class.getName() + "$Tree");
        Class<?> metricType = Class.forName(CorridorTreeBuilder.class.getName() + "$MetricClosure");
        Object graph = construct(graphType, new Class<?>[] {List.class, List.class, List.class, int.class},
                points, links, weights, 0);
        Object reservations = construct(treeType, new Class<?>[] {graphType, int.class, int.class, Map.class},
                graph, 0, 1, Map.of(1, 3, 2, 3));
        Object metric = construct(metricType, new Class<?>[] {graphType, treeType, double.class, boolean.class},
                graph, reservations, 0.0, true);
        java.lang.reflect.Method searchMethod = metricType.getDeclaredMethod("search", int.class, boolean[].class, int[].class);
        searchMethod.setAccessible(true);
        int[] arrivals = new int[3];
        Object search = searchMethod.invoke(metric, 0, new boolean[] {false, true, true}, arrivals);
        java.lang.reflect.Field cost = search.getClass().getDeclaredField("cost");
        cost.setAccessible(true);
        double[] distances = (double[]) cost.get(search);
        assertThat(distances[arrivals[1]]).isEqualTo(1);
        assertThat(distances[arrivals[2]]).isEqualTo(8);

        // Этот же virtual leaf разрешён в качестве источника: его единственное ребро не транзит.
        Object fromLeaf = searchMethod.invoke(metric, 1, new boolean[] {false, false, true}, arrivals);
        assertThat(((double[]) cost.get(fromLeaf))[arrivals[2]]).isEqualTo(1);
        // Проверка финальной степени остаётся обязательной: MST может выбрать разные порты одного leaf.
        assertThat(builder.buildMetricClosureWeighted(points, links, weights, 0, 1, Map.of(1, 3, 2, 3), 0)).isNull();
    }

    @Test
    void weightedBuildersAllowCoincidentDistinctVirtualPointsButNoSelfLoops() {
        List<Coordinate> points = points(0, 0, 0, 0);
        List<int[]> links = links(0, 1);
        assertThat(builder.buildWeighted(points, links, List.of(2.0), 0, 1, Map.of(1, 3), true, 0, 3)).hasSize(1);
        assertThat(builder.buildMetricClosureWeighted(points, links, List.of(2.0), 0, 1, Map.of(1, 3), 3)).hasSize(1);
        assertInvalid(() -> builder.buildWeighted(points, links(0, 0), List.of(2.0), 0, 1, Map.of(), true, 0, 0));
        assertInvalid(() -> build(points, links, 1, Map.of(1, 1), true, 0, 0));
    }

    @Test
    void weightedInputsRequireFinitePositiveLengthsAtLeastTheirChordWithinNumericalEpsilon() {
        List<Coordinate> points = points(0, 0, 1, 0);
        List<int[]> links = links(0, 1);
        for (List<Double> weights : Arrays.asList(null, List.<Double>of(), List.of(1.0, 2.0),
                Arrays.asList((Double) null), List.of(Double.NaN), List.of(Double.POSITIVE_INFINITY),
                List.of(0.0), List.of(-1.0), List.of(0.9), List.of(1.0 - 2e-6))) {
            assertInvalid(() -> builder.buildWeighted(points, links, weights, 0, 1, Map.of(1, 3), true, 0, 0));
            assertInvalid(() -> builder.buildMetricClosureWeighted(points, links, weights, 0, 1, Map.of(1, 3), 0));
        }
        assertThat(builder.buildWeighted(points, links, List.of(1.0 - 1e-6),
                0, 1, Map.of(1, 3), true, 0, 0)).hasSize(1);
        assertThat(builder.buildMetricClosureWeighted(points, links, List.of(1.0 - 1e-6),
                0, 1, Map.of(1, 3), 0)).hasSize(1);
    }

    @Test
    void weightedBuildersKeepCancellationAndDisconnectedFailureBehavior() {
        List<Coordinate> points = points(0, 0, 1, 0);
        assertThat(builder.buildWeighted(points, List.of(), List.of(), 0, 1, Map.of(1, 3), true, 0, 0)).isNull();
        assertThat(builder.buildMetricClosureWeighted(points, List.of(), List.of(), 0, 1, Map.of(1, 3), 0)).isNull();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> builder.buildWeighted(points, links(0, 1), List.of(1.0),
                    0, 1, Map.of(1, 3), true, 0, 0)).isInstanceOf(CancellationException.class);
            assertThatThrownBy(() -> builder.buildMetricClosureWeighted(points, links(0, 1), List.of(1.0),
                    0, 1, Map.of(1, 3), 0)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void explicitWeightedSeedMustBeAnActiveNonRootTerminalEvenWhenCapacityCannotFit() {
        List<Coordinate> points = points(0, 0, 1, 0, 2, 0, 3, 0);
        List<int[]> links = links(0, 1, 1, 2, 2, 3);
        List<Double> weights = List.of(1.0, 1.0, 1.0);
        Map<Integer, Integer> stubs = Map.of(0, 1, 1, 0, 3, 1);
        for (int invalid : new int[] {-1, 0, 1, 2, 4, Integer.MAX_VALUE}) {
            assertInvalid(() -> builder.buildWeightedFrom(points, links, weights, 0, 2, stubs, invalid, 0, 0));
            assertInvalid(() -> builder.buildWeightedFrom(points, links, weights, 0, 0, stubs, invalid, 0, 0));
        }
        assertInvalid(() -> builder.buildWeightedFrom(points, links, weights, 0, 1, Map.of(), 3, 0, 0));
        assertThat(builder.buildWeightedFrom(points, links, weights, 0, 2, stubs, 3, 0, 0)).hasSize(3);
        assertInvalid(() -> builder.buildWeightedFrom(points, links, null, 0, 2, stubs, 3, 0, 0));
        assertInvalid(() -> builder.buildWeightedFrom(points, links, List.of(0.5, 1.0, 1.0), 0, 2, stubs, 3, 0, 0));
        assertInvalid(() -> builder.buildWeightedFrom(points, links, weights, 0, -1, stubs, 3, 0, 0));
        assertInvalid(() -> builder.buildWeightedFrom(points, links, weights, 0, 2, stubs, 3, -1, 0));
        assertInvalid(() -> builder.buildWeightedFrom(points, links, weights, 0, 2, stubs, 3, 0, Double.NaN));
    }

    @Test
    void explicitMiddleSeedProducesADifferentTreeFromBothNearestAndFarthestWithoutChangingInputs() {
        List<Coordinate> points = points(0, 0, 4, 0, 0, 10, -6, 0);
        List<int[]> links = links(0, 1, 0, 2, 0, 3, 1, 2, 2, 3, 1, 3);
        List<Double> weights = List.of(4.0, 10.0, 6.0, 14.0, 16.0, 10.0);
        Map<Integer, Integer> stubs = Map.of(1, 1, 2, 1, 3, 1);
        List<String> originalLinks = orderedEdges(links);
        List<int[]> seeded = builder.buildWeightedFrom(points, links, weights, 0, 1, stubs, 3, 0, 0);

        assertThat(edgeKeys(seeded)).containsExactlyInAnyOrder("0:3", "1:3", "1:2");
        for (boolean farthest : new boolean[] {true, false}) {
            List<int[]> existing = builder.buildWeighted(points, links, weights, 0, 1, stubs, farthest, 0, 0);
            assertThat(edgeKeys(seeded)).isNotEqualTo(edgeKeys(existing));
        }
        assertTree(seeded, points.size(), 0, 1, stubs);
        List<int[]> reversed = links.stream().map(edge -> new int[] {edge[1], edge[0]}).collect(Collectors.toList());
        Collections.reverse(reversed);
        List<Double> reversedWeights = new ArrayList<>(weights);
        Collections.reverse(reversedWeights);
        assertThat(orderedEdges(builder.buildWeightedFrom(points, reversed, reversedWeights, 0, 1, stubs, 3, 0, 0)))
                .containsExactlyElementsOf(orderedEdges(seeded));
        assertThat(orderedEdges(links)).containsExactlyElementsOf(originalLinks);
        assertThat(weights).containsExactly(4.0, 10.0, 6.0, 14.0, 16.0, 10.0);
        assertThat(points).containsExactlyElementsOf(points(0, 0, 4, 0, 0, 10, -6, 0));
        assertThat(stubs).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1, 2, 1, 3, 1));
    }

    @Test
    void explicitAutomaticSeedsExactlyMatchExistingWeightedWorkflowIncludingPenalties() {
        List<Coordinate> points = gridPoints(4);
        List<int[]> links = gridLinks(4);
        List<Double> weights = links.stream().map(edge -> points.get(edge[0]).distance(points.get(edge[1])))
                .collect(Collectors.toList());
        Map<Integer, Integer> stubs = Map.of(3, 1, 12, 1, 15, 1, 6, 1);
        for (boolean farthest : new boolean[] {true, false}) {
            int seed = farthest ? 15 : 6;
            for (double penalty : new double[] {0, 3, 25}) {
                assertThat(orderedEdges(builder.buildWeightedFrom(points, links, weights, 0, 2, stubs, seed, penalty, penalty)))
                        .containsExactlyElementsOf(orderedEdges(builder.buildWeighted(points, links, weights,
                                0, 2, stubs, farthest, penalty, penalty)));
            }
        }
    }

    @Test
    void explicitWeightedSeedKeepsLeafReservationsRootCapacityFailureAndCancellation() {
        List<Coordinate> points = points(0, 0, 5, 0, 10, 0, 0, 5, 10, 5);
        List<int[]> links = links(0, 1, 1, 2, 0, 3, 3, 4, 4, 2);
        List<Double> weights = List.of(5.0, 5.0, 5.0, 10.0, 5.0);
        Map<Integer, Integer> stubs = Map.of(1, 3, 2, 1);
        List<int[]> tree = builder.buildWeightedFrom(points, links, weights, 0, 1, stubs, 2, 0, 0);
        assertThat(edgeKeys(tree)).containsExactlyInAnyOrder("0:3", "3:4", "2:4", "1:2");
        assertTree(tree, points.size(), 0, 1, stubs);
        assertThat(degrees(tree, points.size())[1]).isEqualTo(1);
        assertThat(builder.buildWeightedFrom(points, links, weights, 0, 1, stubs, 1, 0, 0)).isNull();
        assertThat(builder.buildWeightedFrom(points, links, weights, 0, 2, stubs, 1, 0, 0)).isNotNull();
        assertThat(builder.buildWeightedFrom(points, List.of(), List.of(), 0, 2, stubs, 2, 0, 0)).isNull();
        assertThat(builder.buildWeightedFrom(points, links, weights, 0, 1, Map.of(1, 4), 1, 0, 0)).isNull();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> builder.buildWeightedFrom(points, links, weights, 0, 1, stubs, 2, 0, 0))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private Object construct(Class<?> type, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private Set<String> geometryEdges(List<Coordinate> points, List<int[]> edges) {
        assertThat(edges).isNotNull();
        return edges.stream().map(edge -> {
            Coordinate a = points.get(edge[0]);
            Coordinate b = points.get(edge[1]);
            return a.x + ":" + a.y + "->" + b.x + ":" + b.y;
        }).collect(Collectors.toSet());
    }

    private void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertTree(List<int[]> edges, int nodeCount, int root, int rootCapacity, Map<Integer, Integer> stubs) {
        assertThat(edges).isNotNull();
        int[] parents = new int[nodeCount];
        for (int index = 0; index < nodeCount; index++) parents[index] = index;
        Set<Integer> used = new HashSet<>(Set.of(root));
        for (int[] edge : edges) {
            int a = representative(parents, edge[0]);
            int b = representative(parents, edge[1]);
            assertThat(a).as("cycle at %s", Arrays.toString(edge)).isNotEqualTo(b);
            parents[a] = b;
            used.add(edge[0]);
            used.add(edge[1]);
        }
        int[] degree = degrees(edges, nodeCount);
        for (int node = 0; node < nodeCount; node++) {
            assertThat(degree[node] + stubs.getOrDefault(node, 0)).isLessThanOrEqualTo(node == root ? rootCapacity : 4);
            if (stubs.getOrDefault(node, 0) > 0 || used.contains(node)) {
                assertThat(representative(parents, node)).isEqualTo(representative(parents, root));
            }
        }
        assertThat(edges).hasSize(used.size() - 1);
    }

    private int representative(int[] parents, int node) {
        while (parents[node] != node) node = parents[node];
        return node;
    }

    private int[] degrees(List<int[]> edges, int count) {
        int[] degrees = new int[count];
        for (int[] edge : edges) { degrees[edge[0]]++; degrees[edge[1]]++; }
        return degrees;
    }

    private Set<String> edgeKeys(List<int[]> edges) {
        assertThat(edges).isNotNull();
        return edges.stream().map(edge -> Math.min(edge[0], edge[1]) + ":" + Math.max(edge[0], edge[1]))
                .collect(Collectors.toSet());
    }

    private List<String> orderedEdges(List<int[]> edges) {
        assertThat(edges).isNotNull();
        return edges.stream().map(Arrays::toString).collect(Collectors.toList());
    }

    private List<Coordinate> points(double... values) {
        List<Coordinate> result = new ArrayList<>();
        for (int index = 0; index < values.length; index += 2) result.add(new Coordinate(values[index], values[index + 1]));
        return result;
    }

    private List<int[]> links(int... values) {
        List<int[]> result = new ArrayList<>();
        for (int index = 0; index < values.length; index += 2) result.add(new int[] {values[index], values[index + 1]});
        return result;
    }

    private List<Coordinate> gridPoints(int side) {
        List<Coordinate> result = new ArrayList<>();
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) result.add(new Coordinate(x, y));
        return result;
    }

    private List<int[]> gridLinks(int side) {
        List<int[]> result = new ArrayList<>();
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
            if (x + 1 < side) result.add(new int[] {x * side + y, (x + 1) * side + y});
            if (y + 1 < side) result.add(new int[] {x * side + y, x * side + y + 1});
        }
        return result;
    }

    private double enumerate(List<Coordinate> points, List<int[]> links, int previous, int node,
            int target, boolean[] visited, double penalty) {
        if (node == target) return 0;
        visited[node] = true;
        double best = Double.POSITIVE_INFINITY;
        for (int[] edge : links) {
            int next = edge[0] == node ? edge[1] : edge[1] == node ? edge[0] : -1;
            if (next < 0 || visited[next]) continue;
            Coordinate a = previous < 0 ? null : points.get(previous);
            Coordinate b = points.get(node);
            Coordinate c = points.get(next);
            boolean bend = a != null && ((b.x - a.x) * (c.y - b.y) != (b.y - a.y) * (c.x - b.x));
            double cost = b.distance(c) + (bend ? penalty : 0)
                    + enumerate(points, links, node, next, target, visited, penalty);
            best = Math.min(best, cost);
        }
        visited[node] = false;
        return best;
    }
}
