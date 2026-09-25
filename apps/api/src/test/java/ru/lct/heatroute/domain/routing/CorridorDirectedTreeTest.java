package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;

/** Направление дуги проверяется при поиске, не только после выбора неориентированных пар. */
class CorridorDirectedTreeTest {
    private final CorridorTreeBuilder builder = new CorridorTreeBuilder();
    private static final BiPredicate<Integer, Integer> ALL = (from, to) -> true;

    @ParameterizedTest
    @EnumSource(Mode.class)
    void findsLegalDetourInsteadOfForbiddenRootShortcut(Mode mode) {
        List<int[]> tree = build(mode, points(), links(), Map.of(2, 1), (from, to) -> from != 0 || to != 1);
        assertThat(keys(tree)).containsExactlyInAnyOrder("0:3", "3:4", "2:4");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void alsoFiltersLaterArcInTheSameSearch(Mode mode) {
        List<int[]> tree = build(mode, points(), links(), Map.of(2, 1), (from, to) -> from != 1 || to != 2);
        assertThat(keys(tree)).containsExactlyInAnyOrder("0:3", "3:4", "2:4");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void reverseOnlyPathDoesNotConnectDemand(Mode mode) {
        assertThat(build(mode, points(), List.of(new int[] {0, 1}, new int[] {1, 2}),
                Map.of(2, 1), (from, to) -> from > to)).isNull();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void laterAttachmentUsesDirectionOutOfExistingNonrootTreeNode(Mode mode) {
        List<Coordinate> points = List.of(new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(20, 0), new Coordinate(10, 10), new Coordinate(20, 10));
        List<int[]> links = List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {1, 3},
                new int[] {2, 4}, new int[] {4, 3});
        List<int[]> tree = build(mode, points, links, Map.of(2, 1, 3, 1), (from, to) -> from != 1 || to != 3);
        assertThat(keys(tree)).containsExactlyInAnyOrder("0:1", "1:2", "2:4", "3:4");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void inputPairOrderAndUtmRotationDoNotChangePhysicalAdmission(Mode mode) {
        List<Coordinate> transformed = points().stream().map(p ->
                new Coordinate(500000 - p.y, 6170000 + p.x)).collect(Collectors.toList());
        List<int[]> reversed = links().stream().map(link -> new int[] {link[1], link[0]})
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(reversed);
        List<String> before = reversed.stream().map(Arrays::toString).collect(Collectors.toList());
        List<int[]> tree = build(mode, transformed, reversed, Map.of(2, 1), (from, to) -> from != 0 || to != 1);
        assertThat(keys(tree)).containsExactlyInAnyOrder("0:3", "3:4", "2:4");
        assertThat(reversed.stream().map(Arrays::toString).collect(Collectors.toList())).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void unrestrictedAdmissionPreservesExistingSelection(Mode mode) {
        List<Coordinate> points = points();
        List<int[]> links = links();
        List<Double> weights = lengths(points, links);
        List<int[]> previous;
        if (mode == Mode.UNWEIGHTED) previous = builder.build(points, links, 0, 2, Map.of(2, 1), true, 0, 0);
        else if (mode == Mode.WEIGHTED) previous = builder.buildWeighted(points, links, weights,
                0, 2, Map.of(2, 1), true, 0, 0);
        else previous = builder.buildWeightedFrom(points, links, weights, 0, 2, Map.of(2, 1), 2, 0, 0);
        assertThat(keys(build(mode, points, links, Map.of(2, 1), ALL))).isEqualTo(keys(previous));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void preparesEachUniqueArcOnceEvenWithDuplicateLinks(Mode mode) {
        List<int[]> links = new ArrayList<>(links());
        links.add(new int[] {1, 0});
        links.add(new int[] {0, 1});
        AtomicInteger calls = new AtomicInteger();
        assertThat(build(mode, points(), links, Map.of(2, 1, 4, 1), (from, to) -> {
            calls.incrementAndGet();
            return true;
        })).isNotNull();
        assertThat(calls.get()).isEqualTo(10);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void missingDirectedAdmissionIsNotAnImplicitBypass(Mode mode) {
        assertThatThrownBy(() -> build(mode, points(), links(), Map.of(2, 1), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("admission");
    }

    private List<int[]> build(Mode mode, List<Coordinate> points, List<int[]> links,
            Map<Integer, Integer> stubs, BiPredicate<Integer, Integer> admission) {
        if (mode == Mode.UNWEIGHTED) return builder.build(points, links, 0, 2, stubs, true, 0, 0, admission);
        if (mode == Mode.WEIGHTED) return builder.buildWeighted(points, links, lengths(points, links),
                0, 2, stubs, true, 0, 0, admission);
        return builder.buildWeightedFrom(points, links, lengths(points, links), 0, 2, stubs, 2, 0, 0, admission);
    }

    private List<Double> lengths(List<Coordinate> points, List<int[]> links) {
        return links.stream().map(link -> points.get(link[0]).distance(points.get(link[1]))).collect(Collectors.toList());
    }

    private Set<String> keys(List<int[]> tree) {
        assertThat(tree).isNotNull();
        return tree.stream().map(link -> Math.min(link[0], link[1]) + ":" + Math.max(link[0], link[1]))
                .collect(Collectors.toSet());
    }

    private List<Coordinate> points() {
        return List.of(new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(20, 0),
                new Coordinate(0, 10), new Coordinate(20, 10));
    }

    private List<int[]> links() {
        return List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {0, 3},
                new int[] {3, 4}, new int[] {4, 2});
    }

    enum Mode { UNWEIGHTED, WEIGHTED, SEEDED }
}
