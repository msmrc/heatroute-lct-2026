package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class TerminalAlignedChamberRelocationsTest {
    @Test
    void putsChamberAtCheckedFacadeExitBeforeGenericProjection() {
        List<Coordinate> result = build(point -> true);
        assertThat(result).isNotEmpty();
        assertThat(result.get(0)).isEqualTo(new Coordinate(12, 12));
        assertThat(result).contains(new Coordinate(12, 9.9), new Coordinate(12, 8), new Coordinate(12, 0));
    }

    @Test
    void filtersBlockedAndDuplicateRoundedCandidatesWithoutMovingInputs() {
        List<RouteEdge> edges = edges();
        List<List<RouteCoordinate>> control = new ArrayList<>();
        edges.forEach(edge -> control.add(List.copyOf(edge.getCoordinates())));
        List<Coordinate> result = TerminalAlignedChamberRelocations.build(chamber(), edges,
                Set.of("demand"), point -> point.x >= 10 && point.y >= 10);
        assertThat(result).containsExactly(new Coordinate(12, 12));
        for (int i = 0; i < edges.size(); i++) assertThat(edges.get(i).getCoordinates()).containsExactlyElementsOf(control.get(i));
    }

    @Test
    void includesSupportAxisFootWhenFacadeNormalIsSlightlyRotated() {
        RouteNode chamber = chamber();
        List<RouteEdge> edges = List.of(
                edge("left", "left", "camera", c(-20, 0), c(0, 0)),
                edge("right", "camera", "right", c(0, 0), c(20, 0)),
                edge("input", "camera", "demand", c(0, 0), c(12, 1), c(12.1, 10)));

        List<Coordinate> result = TerminalAlignedChamberRelocations.build(
                chamber, edges, Set.of("demand"), point -> true);

        assertThat(result).contains(new Coordinate(12, 0));
    }

    @Test
    void rejectsMalformedIncidenceAndPropagatesCancellation() {
        assertThatThrownBy(() -> TerminalAlignedChamberRelocations.build(chamber(), edges().subList(0, 2),
                Set.of("demand"), point -> true)).isInstanceOf(IllegalArgumentException.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> build(point -> true)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private List<Coordinate> build(java.util.function.Predicate<Coordinate> allowed) {
        return TerminalAlignedChamberRelocations.build(chamber(), edges(), Set.of("demand"), allowed);
    }

    private RouteNode chamber() {
        return new RouteNode("camera", "new_branch_chamber", p(0, 0), true, false, 0, null);
    }

    private List<RouteEdge> edges() {
        return List.of(
                edge("left", "left", "camera", c(-20, 0), c(0, 0)),
                edge("right", "camera", "right", c(0, 0), c(20, 0)),
                edge("input", "camera", "demand", c(0, 0), c(0, 12), c(12, 12), c(12, 20)));
    }

    private RouteEdge edge(String id, String from, String to, Coordinate... coordinates) {
        List<RouteCoordinate> points = new ArrayList<>();
        double length = 0;
        for (int i = 0; i < coordinates.length; i++) {
            points.add(p(coordinates[i].x, coordinates[i].y));
            if (i > 0) length += coordinates[i - 1].distance(coordinates[i]);
        }
        return new RouteEdge(id, from, to, length, points, List.of(), BigDecimal.ONE, 100);
    }

    private Coordinate c(double x, double y) { return new Coordinate(x, y); }
    private RouteCoordinate p(double x, double y) { return new RouteCoordinate(x, y); }
}
