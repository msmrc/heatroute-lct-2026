package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import ru.lct.heatroute.domain.routing.SharedSpineCandidateBuilder.Junction;
import ru.lct.heatroute.domain.routing.SharedSpineCandidateBuilder.SpineCandidate;
import ru.lct.heatroute.domain.routing.SharedSpineCandidateBuilder.Terminal;

class SharedSpineCandidateBuilderTest {
    private final SharedSpineCandidateBuilder builder = new SharedSpineCandidateBuilder();
    private final Coordinate root = new Coordinate(-46, -53);

    @Test
    void assignsEveryTerminalExactlyOnceAndBuildsBoundedRankedTrees() {
        List<Terminal> terminals = neighbourhood(17);
        List<SpineCandidate> candidates = builder.build(terminals, root, buildings());

        assertThat(candidates).hasSize(6);
        assertThat(candidates).extracting(SpineCandidate::getEstimatedLengthM).isSorted();
        for (SpineCandidate candidate : candidates) assertTree(candidate, terminals, root);
    }

    @Test
    void insertsSeparateRootChamberInsideSaturatedSpine() {
        List<Terminal> terminals = new ArrayList<>();
        for (int x : new int[] {-90, -30, 30, 90}) {
            terminals.add(new Terminal("south" + x, new Coordinate(x, -12)));
            terminals.add(new Terminal("north" + x, new Coordinate(x, 12)));
        }
        Coordinate middleRoot = new Coordinate(0, -30);
        List<SpineCandidate> candidates = builder.build(terminals, middleRoot, List.of());

        assertThat(candidates).anySatisfy(candidate -> {
            int index = candidate.getRootJunctionIndex();
            assertThat(index).isBetween(1, candidate.getJunctions().size() - 2);
            assertThat(candidate.getJunctions().get(index).getTerminalIds()).isEmpty();
        });
        for (SpineCandidate candidate : candidates) assertTree(candidate, terminals, middleRoot);
    }

    @Test
    void reusesEligibleEndChamberInsteadOfAlwaysAddingRootChamber() {
        List<Terminal> terminals = List.of(terminal("a", 10, -4), terminal("b", 12, 5),
                terminal("c", 40, -3), terminal("d", 43, 5));
        Coordinate outsideRoot = new Coordinate(-20, 1);

        assertThat(builder.build(terminals, outsideRoot, List.of())).anySatisfy(candidate -> {
            assertThat(candidate.getJunctions()).hasSize(2);
            assertThat(candidate.getJunctions().get(candidate.getRootJunctionIndex()).getTerminalIds()).hasSize(2);
        });
    }

    @Test
    void preservesDirectionAndOffsetDiversity() {
        List<SpineCandidate> candidates = builder.build(neighbourhood(17), root, buildings());
        Map<String, Set<Long>> offsetsByDirection = new HashMap<>();
        for (SpineCandidate candidate : candidates) {
            Coordinate first = candidate.getJunctions().get(0).getCoordinate();
            Coordinate last = candidate.getJunctions().get(candidate.getJunctions().size() - 1).getCoordinate();
            boolean horizontal = Math.abs(first.y - last.y) < 1e-6;
            assertThat(horizontal || Math.abs(first.x - last.x) < 1e-6).isTrue();
            offsetsByDirection.computeIfAbsent(horizontal ? "horizontal" : "vertical", key -> new HashSet<>())
                    .add(Math.round((horizontal ? first.y : first.x) * 1e6));
        }
        assertThat(offsetsByDirection).hasSize(2);
        assertThat(offsetsByDirection.values()).allSatisfy(offsets -> assertThat(offsets).hasSize(3));
    }

    @Test
    void prefersOppositeSidesAtNearbyStationsWithoutPairingDistantConsumers() {
        List<Terminal> terminals = List.of(terminal("a", 0, -10), terminal("b", 0.5, -8),
                terminal("c", 0.8, 10), terminal("d", 1.1, 8), terminal("e", 50, -10), terminal("f", 51, -8),
                terminal("g", 100, 10), terminal("h", 101, 8));
        List<SpineCandidate> candidates = builder.build(terminals, root, buildings());
        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(candidate.getJunctions()).anySatisfy(j -> assertThat(j.getTerminalIds()).containsExactlyInAnyOrder("a", "c"));
            assertThat(candidate.getJunctions()).anySatisfy(j -> assertThat(j.getTerminalIds()).containsExactlyInAnyOrder("b", "d"));
            assertThat(candidate.getJunctions()).anySatisfy(j -> assertThat(j.getTerminalIds()).containsExactlyInAnyOrder("e", "f"));
            assertThat(candidate.getJunctions()).anySatisfy(j -> assertThat(j.getTerminalIds()).containsExactlyInAnyOrder("g", "h"));
        });
        for (SpineCandidate candidate : candidates) assertTree(candidate, terminals, root);
    }

    @Test
    void inputShuffleAndNonGeometricIdChangesDoNotChangeTopology() {
        List<Terminal> terminals = neighbourhood(17);
        List<SpineCandidate> original = builder.build(terminals, root, buildings());
        List<Terminal> shuffled = new ArrayList<>(terminals);
        Collections.shuffle(shuffled, new Random(123));
        List<Geometry> shuffledBuildings = new ArrayList<>(buildings());
        Collections.reverse(shuffledBuildings);
        assertEquivalent(original, builder.build(shuffled, root, shuffledBuildings), null, Map.of());

        Map<String, String> renamedIds = new HashMap<>();
        List<Terminal> renamed = new ArrayList<>();
        for (int i = 0; i < terminals.size(); i++) {
            String id = "renamed-" + (terminals.size() - i);
            renamedIds.put(terminals.get(i).getId(), id);
            renamed.add(new Terminal(id, terminals.get(i).getCoordinate()));
        }
        assertEquivalent(original, builder.build(renamed, root, buildings()), null, renamedIds);
    }

    @Test
    void topologyFollowsRotationAndTranslationWithOrWithoutFacades() {
        List<Terminal> terminals = neighbourhood(17);
        for (List<Geometry> footprints : List.of(buildings(), List.<Geometry>of())) {
            List<SpineCandidate> original = builder.build(terminals, root, footprints);
            for (double angle : new double[] {0, 0.37, 1.1, 2.7, -1.8}) {
                AffineTransformation transform = AffineTransformation.rotationInstance(angle).translate(410000, 6180000);
                List<Terminal> moved = terminals.stream().map(t -> new Terminal(t.getId(),
                        transform.transform(t.getCoordinate(), new Coordinate()))).collect(Collectors.toList());
                Coordinate movedRoot = transform.transform(root, new Coordinate());
                List<Geometry> movedBuildings = footprints.stream().map(transform::transform).collect(Collectors.toList());
                List<SpineCandidate> candidates = builder.build(moved, movedRoot, movedBuildings);
                assertEquivalent(original, candidates, transform, Map.of());
                for (SpineCandidate candidate : candidates) assertTree(candidate, moved, movedRoot);
            }
        }
    }

    @Test
    void returnsEmptyForNoTerminalsAndHandlesSingleTerminalAtRoot() {
        assertThat(builder.build(List.of(), root, List.of())).isEmpty();
        List<Terminal> terminals = List.of(new Terminal("only", root));
        List<SpineCandidate> candidates = builder.build(terminals, root, List.of());
        assertThat(candidates).isNotEmpty().hasSizeLessThanOrEqualTo(6);
        for (SpineCandidate candidate : candidates) assertTree(candidate, terminals, root);
    }

    @Test
    void handlesCoincidentCollinearIsotropicAndDistantTerminals() {
        List<Terminal> coincident = new ArrayList<>();
        List<Terminal> collinear = new ArrayList<>();
        List<Terminal> distant = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            coincident.add(terminal("same" + i, 0, 0));
            collinear.add(terminal("line" + i, i, 0));
            distant.add(terminal("far" + i, 410000 + i * 23000, 6200000 + (i % 3) * 42000));
        }
        List<Terminal> isotropic = List.of(terminal("east", 20, 0), terminal("west", -20, 0),
                terminal("north", 0, 20), terminal("south", 0, -20));
        for (List<Terminal> terminals : List.of(coincident, collinear, distant, isotropic)) {
            List<SpineCandidate> candidates = builder.build(terminals, new Coordinate(0, 0), List.of());
            assertThat(candidates).isNotEmpty().hasSizeLessThanOrEqualTo(6);
            for (SpineCandidate candidate : candidates) assertTree(candidate, terminals, new Coordinate(0, 0));
        }
    }

    @Test
    void footprintsGuideAxesButDoNotClaimObstacleClearance() {
        Geometry obstacle = rectangle(-1000, -1000, 1000, 1000);
        List<Terminal> terminals = neighbourhood(8);
        List<SpineCandidate> candidates = builder.build(terminals, root, List.of(obstacle));
        assertThat(candidates).isNotEmpty();
        for (SpineCandidate candidate : candidates) {
            assertTree(candidate, terminals, root);
            assertThat(candidate.getJunctions()).anySatisfy(junction ->
                    assertThat(obstacle.contains(obstacle.getFactory().createPoint(junction.getCoordinate()))).isTrue());
        }
    }

    @Test
    void ownsCoordinatesAndExposesUnmodifiableLists() {
        Coordinate input = new Coordinate(12, 8);
        Terminal terminal = new Terminal("one", input);
        input.x = 999;
        terminal.getCoordinate().y = 999;
        assertThat(terminal.getCoordinate()).isEqualTo(new Coordinate(12, 8));
        Geometry footprint = buildings().get(0);
        Geometry before = footprint.copy();
        Coordinate rootBefore = new Coordinate(root);
        List<SpineCandidate> candidates = builder.build(List.of(terminal), root, List.of(footprint));
        Junction junction = candidates.get(0).getJunctions().get(0);
        Coordinate saved = junction.getCoordinate();
        junction.getCoordinate().x = 999;
        assertThat(junction.getCoordinate()).isEqualTo(saved);
        assertThat(root).isEqualTo(rootBefore);
        assertThat(footprint.equalsExact(before)).isTrue();
        assertThatThrownBy(() -> candidates.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> candidates.get(0).getJunctions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> junction.getTerminalIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidCoordinatesIdsAndDuplicateAssignments() {
        for (Coordinate coordinate : new Coordinate[] {null, new Coordinate(Double.NaN, 0), new Coordinate(0, Double.POSITIVE_INFINITY)}) {
            assertThatThrownBy(() -> new Terminal("bad", coordinate)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.build(List.of(), coordinate, List.of())).isInstanceOf(IllegalArgumentException.class);
        }
        for (String id : new String[] {null, "", " "}) {
            assertThatThrownBy(() -> new Terminal(id, root)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> builder.build(List.of(terminal("same", 1, 2), terminal("same", 3, 4)), root, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate terminal id");
    }

    private void assertTree(SpineCandidate candidate, List<Terminal> terminals, Coordinate source) {
        List<Junction> junctions = candidate.getJunctions();
        assertThat(candidate.getRootJunctionIndex()).isBetween(0, junctions.size() - 1);
        Map<String, Terminal> byId = terminals.stream().collect(Collectors.toMap(Terminal::getId, t -> t));
        List<String> assigned = new ArrayList<>();
        int vertexCount = junctions.size() + terminals.size() + 1;
        List<List<Integer>> graph = new ArrayList<>();
        for (int i = 0; i < vertexCount; i++) graph.add(new ArrayList<>());
        int leaf = junctions.size(), edgeCount = 0;
        double length = 0;
        for (int i = 0; i < junctions.size(); i++) {
            Junction junction = junctions.get(i);
            assertThat(junction.getTerminalIds()).hasSizeLessThanOrEqualTo(2);
            if (i > 0) {
                double distance = junction.getCoordinate().distance(junctions.get(i - 1).getCoordinate());
                assertThat(distance).isGreaterThanOrEqualTo(2 - 1e-7);
                length += distance; connect(graph, i, i - 1); edgeCount++;
            }
            for (Terminal terminal : terminals) {
                assertThat(junction.getCoordinate().distance(terminal.getCoordinate())).isGreaterThan(1e-7);
            }
            for (String id : junction.getTerminalIds()) {
                assertThat(byId).containsKey(id);
                assigned.add(id);
                length += junction.getCoordinate().distance(byId.get(id).getCoordinate());
                connect(graph, i, leaf++); edgeCount++;
            }
        }
        double rootLength = source.distance(junctions.get(candidate.getRootJunctionIndex()).getCoordinate());
        assertThat(rootLength).isGreaterThan(1e-7);
        length += rootLength;
        connect(graph, candidate.getRootJunctionIndex(), vertexCount - 1); edgeCount++;
        assertThat(assigned).containsExactlyInAnyOrderElementsOf(byId.keySet());
        assertThat(edgeCount).isEqualTo(vertexCount - 1);
        assertThat(graph).allSatisfy(neighbours -> assertThat(neighbours).hasSizeLessThanOrEqualTo(4));
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(vertexCount - 1);
        while (!queue.isEmpty()) {
            int vertex = queue.remove();
            if (visited.add(vertex)) queue.addAll(graph.get(vertex));
        }
        assertThat(visited).hasSize(vertexCount);
        assertThat(candidate.getEstimatedLengthM()).isFinite().isCloseTo(length, offset(1e-6));
    }

    private void connect(List<List<Integer>> graph, int a, int b) {
        graph.get(a).add(b); graph.get(b).add(a);
    }

    private void assertEquivalent(List<SpineCandidate> expected, List<SpineCandidate> actual,
            AffineTransformation transform, Map<String, String> renamed) {
        assertThat(actual).hasSize(expected.size());
        for (SpineCandidate candidate : expected) {
            assertThat(actual).anySatisfy(other -> {
                assertThat(other.getRootJunctionIndex()).isEqualTo(candidate.getRootJunctionIndex());
                assertThat(other.getEstimatedLengthM()).isCloseTo(candidate.getEstimatedLengthM(), offset(1e-5));
                assertThat(other.getJunctions()).hasSize(candidate.getJunctions().size());
                for (int j = 0; j < candidate.getJunctions().size(); j++) {
                    Junction junction = candidate.getJunctions().get(j), moved = other.getJunctions().get(j);
                    Coordinate point = junction.getCoordinate();
                    if (transform != null) point = transform.transform(point, new Coordinate());
                    assertThat(moved.getCoordinate().distance(point)).isLessThan(1e-6);
                    assertThat(moved.getTerminalIds()).containsExactlyElementsOf(junction.getTerminalIds().stream()
                            .map(id -> renamed.getOrDefault(id, id)).collect(Collectors.toList()));
                }
            });
        }
    }

    private List<Terminal> neighbourhood(int size) {
        List<Terminal> terminals = new ArrayList<>();
        for (int i = 0; i < size; i++) terminals.add(terminal("t" + i, 15 + (i / 2) * 26 + (i % 3),
                (i % 2 == 0 ? -18 : 27) + (i % 4) * 2));
        return terminals;
    }

    private Terminal terminal(String id, double x, double y) { return new Terminal(id, new Coordinate(x, y)); }
    private List<Geometry> buildings() { return List.of(rectangle(0, -40, 180, -25), rectangle(5, 45, 95, 70)); }
    private Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        return new GeometryFactory().createPolygon(new Coordinate[] {new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY), new Coordinate(minX, minY)});
    }
}
