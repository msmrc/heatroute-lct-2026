package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

class CorridorPortCompatibilityTest {
    private final GeometryFactory factory = new GeometryFactory();

    @Test
    void sharedPortAcceptsAValidTAndCollinearIntermediatePoints() {
        Map<Integer, RoutePath> paths = Map.of(10, path(0, 0, 0, 4, 0, 10), 20, path(0, 0, 4, 0, 10, 0));
        assertThat(check(paths, Map.of(10, 7, 20, 7), List.of(line(-10, 0, 0, 0)))).isNull();
        assertThat(check(Map.of(10, path(0, 0, 0, 0, 0, 5, 0, 5, 0, 10)), Map.of(10, 7),
                List.of(line(-10, 0, 0, 0), line(0, 0, 10, 0)))).isNull();
    }

    @Test
    void sameStartIsNotASharedPortWhenAttachmentIndexesDiffer() {
        assertThat(check(Map.of(10, path(0, 0, 0, 10), 20, path(0, 0, 10, 0)),
                Map.of(10, 7, 20, 8), List.of())).containsExactly(10, 20);
    }

    @Test
    void sharedPortNeverPermitsPositiveLengthOverlapEvenBelowRoundingTolerance() {
        for (double overlap : new double[] {5, 0.001, 0.0005}) {
            assertThat(check(Map.of(10, path(0, 0, 0, 10), 20, path(0, 0, 0, overlap, 10, overlap)),
                    Map.of(10, 7, 20, 7), List.of())).containsExactly(10, 20);
        }
    }

    @Test
    void detectsCurvedStubCrossingsButLeavesDistantAndDisjointCurvesAlone() {
        RoutePath first = path(0, 0, 10, 0, 10, 10);
        RoutePath crossing = path(5, 5, 15, 5);
        assertThat(check(Map.of(7, first, 3, crossing), Map.of(7, 1, 3, 2), List.of())).containsExactly(3, 7);
        assertThat(check(Map.of(7, first, 3, path(20, 5, 30, 5)), Map.of(7, 1, 3, 2), List.of())).isNull();
        // Конверты пересекаются, сами линии — нет.
        assertThat(check(Map.of(7, first, 3, path(2, 2, 2, 8, 8, 8)), Map.of(7, 1, 3, 2), List.of())).isNull();
    }

    @Test
    void sharedPortDoesNotHideAnotherIntersectionFartherAlongTheTwoStubs() {
        assertThat(check(Map.of(10, path(0, 0, 10, 0, 10, 10), 20, path(0, 0, 0, 5, 15, 5)),
                Map.of(10, 7, 20, 7), List.of())).containsExactly(10, 20);
    }

    @Test
    void selfCrossingRetracingAndClosedStubsReturnTheSingleLeafConflict() {
        for (RoutePath invalid : List.of(path(0, 0, 10, 0, 10, 10, 0, 10, 0, 5, 15, 5),
                path(0, 0, 10, 0, 5, 0, 5, 10), path(0, 0, 10, 0, 10, 10, 0, 10, 0, 0))) {
            assertThat(check(Map.of(5, invalid), Map.of(5, 7), List.of())).containsExactly(5, -1);
        }
    }

    @Test
    void gridAllowsOnlyThePortAndRejectsCrossingReentryDemandTouchAndAnyOverlap() {
        Map<Integer, RoutePath> paths = Map.of(5, path(0, 0, 10, 0, 10, 10));
        Map<Integer, Integer> ports = Map.of(5, 7);
        assertThat(check(paths, ports, List.of(line(0, 0, 0, 10)))).isNull();
        assertThat(check(paths, ports, List.of(line(0, 0, 0, 10), line(5, -5, 5, 5)))).containsExactly(5, -1);
        assertThat(check(paths, ports, List.of(line(10, 10, 20, 10)))).containsExactly(5, -1);
        assertThat(check(paths, ports, List.of(line(0, 0, 0.0005, 0)))).containsExactly(5, -1);
        assertThat(check(Map.of(5, path(0, 0, 10, 0, 10, 5, 0, 5)), ports,
                List.of(line(0, -5, 0, 10)))).containsExactly(5, -1);
        // Полилиния направлена порт → потребитель: касание её последней точки не является врезкой.
        assertThat(check(Map.of(5, path(10, 0, 0, 0)), ports, List.of(line(0, 0, 0, 10))))
                .containsExactly(5, -1);
    }

    @Test
    void roundedGridPortAndSharedStubStartsWithinOneMillimetreAreAllowed() {
        Map<Integer, RoutePath> paths = Map.of(5, path(0, 0, 0, 10));
        assertThat(check(paths, Map.of(5, 7), List.of(line(0, 0.001, -10, 0.001)))).isNull();
        assertThat(check(Map.of(5, path(0, 0, 0, 10), 6, path(0, 0.001, 10, 0.001)),
                Map.of(5, 7, 6, 7), List.of())).isNull();
        assertThat(check(paths, Map.of(5, 7), List.of(line(0, 0.01, -10, 0.01)))).isNull();
        assertThat(check(paths, Map.of(5, 7), List.of(line(0, 0.011, -10, 0.011)))).containsExactly(5, -1);
        assertThat(check(Map.of(5, path(0, 0, 0, 10), 6, path(0, 0.011, 10, 0.011)),
                Map.of(5, 7, 6, 7), List.of())).containsExactly(5, 6);
    }

    @Test
    void firstConflictIsDeterministicForShuffledMapsAndGridSectionsWithUnaryFirstForEachLeaf() {
        Map<Integer, RoutePath> paths = Map.of(7, path(0, 0, 0, 10), 3, path(-5, 5, 5, 5),
                11, path(100, 100, 110, 100, 105, 100));
        Random random = new Random(51003);
        for (int trial = 0; trial < 30; trial++) {
            List<Integer> order = new ArrayList<>(paths.keySet());
            Collections.shuffle(order, random);
            Map<Integer, RoutePath> shuffledPaths = new LinkedHashMap<>();
            Map<Integer, Integer> shuffledPorts = new LinkedHashMap<>();
            for (int leaf : order) shuffledPaths.put(leaf, paths.get(leaf));
            Collections.reverse(order);
            for (int leaf : order) shuffledPorts.put(leaf, leaf + 100);
            List<LineString> grid = new ArrayList<>(List.of(line(200, 200, 210, 210), line(300, 300, 310, 310)));
            Collections.shuffle(grid, random);
            assertThat(check(shuffledPaths, shuffledPorts, grid)).containsExactly(3, 7);
            grid.add(line(0, 4, 0, 6));
            Collections.shuffle(grid, random);
            assertThat(check(shuffledPaths, shuffledPorts, grid)).containsExactly(3, -1);
        }
    }

    @Test
    void rotationAndMetricTranslationPreserveValidAndConflictingResults() {
        for (double angle : new double[] {0, 0.4, 1.7, 3.9}) {
            Map<Integer, RoutePath> valid = Map.of(5, transformed(path(0, 0, 0, 10), angle),
                    6, transformed(path(0, 0, 10, 0), angle));
            List<LineString> grid = List.of(transformed(line(-10, 0, 0, 0), angle));
            assertThat(check(valid, Map.of(5, 7, 6, 7), grid)).isNull();
            Map<Integer, RoutePath> crossing = Map.of(5, transformed(path(0, 0, 10, 0, 10, 10), angle),
                    6, transformed(path(0, 0, 0, 5, 15, 5), angle));
            assertThat(check(crossing, Map.of(5, 7, 6, 7), List.of())).containsExactly(5, 6);
            assertThat(check(Map.of(5, valid.get(5)), Map.of(5, 7),
                    List.of(transformed(line(-5, 5, 5, 5), angle)))).containsExactly(5, -1);
        }
    }

    @Test
    void doesNotMutateBorrowedGeometryOrRetainResultsAcrossCalls() {
        RoutePath path = path(0, 0, 0, 10);
        List<Coordinate> original = new ArrayList<>();
        path.coordinates().forEach(c -> original.add(new Coordinate(c)));
        LineString grid = line(-5, 5, 5, 5);
        LineString snapshot = (LineString) grid.copy();
        Map<Integer, RoutePath> paths = new HashMap<>(Map.of(5, path));
        Map<Integer, Integer> ports = new HashMap<>(Map.of(5, 7));
        int[] conflict = check(paths, ports, List.of(grid));
        assertThat(conflict).containsExactly(5, -1);
        conflict[0] = 999;
        assertThat(check(paths, ports, List.of(grid))).containsExactly(5, -1);
        assertThat(path.coordinates()).containsExactlyElementsOf(original);
        assertThat(grid.equalsExact(snapshot)).isTrue();
        assertThat(paths).containsExactlyEntriesOf(Map.of(5, path));
        assertThat(ports).containsExactlyEntriesOf(Map.of(5, 7));
        paths.put(5, path(20, 0, 20, 10));
        assertThat(check(paths, ports, List.of(grid))).isNull();
    }

    @Test
    void enforcesStubAndGridBudgetsWithoutCuttingValidBoundaryInputs() {
        Map<Integer, RoutePath> paths = new HashMap<>();
        Map<Integer, Integer> ports = new HashMap<>();
        for (int leaf = 0; leaf < 64; leaf++) {
            paths.put(leaf, path(leaf * 20, 0, leaf * 20, 10));
            ports.put(leaf, leaf);
        }
        LineString remote = line(-100, -100, -100, -90);
        assertThat(check(paths, ports, Collections.nCopies(20_000, remote))).isNull();
        assertInvalid(() -> check(paths, ports, Collections.nCopies(20_001, remote)));
        paths.put(64, path(1280, 0, 1280, 10));
        ports.put(64, 64);
        assertInvalid(() -> check(paths, ports, List.of()));
        assertThat(check(Map.of(), Map.of(), List.of())).isNull();
    }

    @Test
    void rejectsMalformedMapsAndNonFiniteOrDegenerateGeometry() {
        RoutePath valid = path(0, 0, 0, 10);
        Map<Integer, RoutePath> paths = Map.of(5, valid);
        Map<Integer, Integer> ports = Map.of(5, 7);
        assertInvalid(() -> check(null, ports, List.of()));
        assertInvalid(() -> check(paths, null, List.of()));
        assertInvalid(() -> check(paths, ports, null));
        assertInvalid(() -> check(paths, Map.of(), List.of()));
        assertInvalid(() -> check(paths, Map.of(5, 7, 6, 8), List.of()));
        assertInvalid(() -> check(Map.of(-1, valid), Map.of(-1, 7), List.of()));
        assertInvalid(() -> check(paths, Map.of(5, -1), List.of()));
        assertInvalid(() -> check(Collections.singletonMap(null, valid), Collections.singletonMap(null, 7), List.of()));
        assertInvalid(() -> check(Collections.singletonMap(5, null), ports, List.of()));
        assertInvalid(() -> check(paths, Collections.singletonMap(5, null), List.of()));
        for (List<Coordinate> coordinates : List.of(List.<Coordinate>of(), List.of(new Coordinate(0, 0)),
                List.of(new Coordinate(0, 0), new Coordinate(0, 0)),
                List.of(new Coordinate(0, 0), new Coordinate(Double.NaN, 1)),
                List.of(new Coordinate(0, 0), new Coordinate(1, Double.POSITIVE_INFINITY)))) {
            RoutePath malformed = new RoutePath(coordinates, List.of(), 1);
            assertInvalid(() -> check(Map.of(5, malformed), ports, List.of()));
        }
        assertInvalid(() -> check(paths, ports, Collections.singletonList(null)));
        for (LineString malformed : List.of(line(), line(0, 0, 0, 0), line(0, 0, Double.NaN, 1),
                line(0, 0, 1, Double.POSITIVE_INFINITY))) {
            assertInvalid(() -> check(paths, ports, List.of(malformed)));
        }
    }

    @Test
    void cancellationBeforeAndDuringPreparationPreservesTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> check(Map.of(), Map.of(), List.of())).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        Map<Integer, RoutePath> interrupted = new HashMap<Integer, RoutePath>() {
            @Override public RoutePath get(Object key) {
                Thread.currentThread().interrupt();
                return super.get(key);
            }
        };
        interrupted.put(5, path(0, 0, 0, 10));
        try {
            assertThatThrownBy(() -> check(interrupted, Map.of(5, 7), List.of())).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private int[] check(Map<Integer, RoutePath> paths, Map<Integer, Integer> ports, List<LineString> grid) {
        return CorridorPortCompatibility.firstConflict(paths, ports, grid);
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class);
    }

    private RoutePath path(double... xy) {
        LineString line = line(xy);
        return new RoutePath(List.of(line.getCoordinates()), List.of(), line.getLength());
    }

    private LineString line(double... xy) {
        Coordinate[] coordinates = new Coordinate[xy.length / 2];
        for (int i = 0; i < coordinates.length; i++) coordinates[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
        return factory.createLineString(coordinates);
    }

    private RoutePath transformed(RoutePath path, double angle) {
        LineString line = factory.createLineString(path.coordinates().toArray(new Coordinate[0]));
        LineString moved = transformed(line, angle);
        return new RoutePath(List.of(moved.getCoordinates()), List.of(), moved.getLength());
    }

    private LineString transformed(LineString line, double angle) {
        Coordinate[] coordinates = line.getCoordinates();
        for (int i = 0; i < coordinates.length; i++) {
            Coordinate p = coordinates[i];
            coordinates[i] = new Coordinate(600000 + p.x * Math.cos(angle) - p.y * Math.sin(angle),
                    6000000 + p.x * Math.sin(angle) + p.y * Math.cos(angle));
        }
        return factory.createLineString(coordinates);
    }
}
