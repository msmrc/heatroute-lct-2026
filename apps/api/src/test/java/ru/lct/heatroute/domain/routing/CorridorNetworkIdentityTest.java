package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.depth.DepthCrossingDecision;
import ru.lct.heatroute.domain.depth.DepthProfileIssue;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.reconstruction.ChamberReconstruction;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.reconstruction.NetworkReconstructionSection;

/** Точная идентичность не заменяет геометрический валидатор; неизвестная эквивалентность не сливается. */
class CorridorNetworkIdentityTest {
    @Test
    void ignoresGeneratedNamesAndCollectionOrderButKeepsGraphIncidence() {
        RouteVariant first = network("first", "merge:a:b:");
        RouteVariant renamed = network("other", "merge:b:a:");
        List<RouteNode> nodes = reversed(renamed.getNodes());
        List<RouteEdge> edges = reversed(renamed.getEdges());
        List<RouteConnection> connections = reversed(renamed.getConnections());
        RouteVariant reordered = copy(renamed, nodes, edges, connections).withRank(9);
        assertThat(CorridorNetworkIdentity.of(first)).isPresent().isEqualTo(CorridorNetworkIdentity.of(reordered));
    }

    @Test
    void retainsEveryPolylineVertexExactlyAtModelPrecision() {
        RouteVariant source = network("source", "a:");
        RouteEdge edge = source.getEdges().get(0);
        for (List<RouteCoordinate> coordinates : List.of(
                List.of(point(0, 0), point(10, 0.001)),
                List.of(point(0, 0), point(5, 0), point(10, 0)),
                reversed(edge.getCoordinates()))) {
            different(source, replaceEdge(source, 0, edge(edge, edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                    coordinates, edge.getFlowTph(), edge.getDiameter(), edge.getSections(), edge.getDepthProfile())));
        }
    }

    @Test
    void reversedDirectionAndRewiredTopologyAreNotEquivalent() {
        RouteVariant source = network("source", "a:");
        RouteEdge edge = source.getEdges().get(0);
        different(source, replaceEdge(source, 0, edge(edge, edge.getDownstreamNodeId(), edge.getUpstreamNodeId(),
                reversed(edge.getCoordinates()), edge.getFlowTph(), edge.getDiameter(), List.of(), null)));
        different(source, replaceEdge(source, 0, edge(edge, edge.getUpstreamNodeId(), "a:b",
                edge.getCoordinates(), edge.getFlowTph(), edge.getDiameter(), List.of(), null)));
    }

    @Test
    void preservesNodeCoordinatesTypesFlagsRootsAndExistingFeatureTargets() {
        RouteVariant source = network("source", "a:");
        RouteNode branch = source.getNodes().get(1);
        for (RouteNode changed : List.of(
                new RouteNode(branch.getId(), branch.getNodeType(), point(10, 0.001), true, false, 0, null),
                new RouteNode(branch.getId(), "new_tie_chamber", branch.getCoordinate(), true, false, 0, null),
                new RouteNode(branch.getId(), branch.getNodeType(), branch.getCoordinate(), false, false, 0, null),
                new RouteNode(branch.getId(), branch.getNodeType(), branch.getCoordinate(), true, true, 0, null),
                new RouteNode(branch.getId(), branch.getNodeType(), branch.getCoordinate(), true, false, 1, null),
                new RouteNode(branch.getId(), branch.getNodeType(), branch.getCoordinate(), true, false, 0, "target"))) {
            different(source, replaceNode(source, 1, changed));
        }
        RouteNode root = source.getNodes().get(0);
        different(source, replaceNode(source, 0, new RouteNode(root.getId(), root.getNodeType(), root.getCoordinate(),
                true, true, 2, "different-existing-feature")));
        RouteVariant changedRoot = replaceNode(source, 0, new RouteNode("other-root", root.getNodeType(),
                root.getCoordinate(), true, true, 2, root.getTargetId()));
        RouteEdge edge = source.getEdges().get(0);
        different(source, replaceEdge(changedRoot, 0, edge(edge, "other-root", edge.getDownstreamNodeId(),
                edge.getCoordinates(), edge.getFlowTph(), edge.getDiameter(), List.of(), null)));
    }

    @Test
    void distinguishesExistingSupportDiametersEvenWithinTheSamePriceBand() {
        RouteVariant source = network("source", "a:");
        RouteNode root = source.getNodes().get(0);
        RouteVariant first = replaceNode(source, 0, root.withExistingIncidentDiameter(100));
        RouteVariant second = replaceNode(source, 0, root.withExistingIncidentDiameter(150));
        different(source, first);
        different(first, second);
    }

    @Test
    void preservesConsumersConnectionPointsFlowsStatusesAndDiagnostics() {
        RouteVariant source = network("source", "a:");
        for (RouteConnection connection : List.of(
                new RouteConnection("different", "p1", BigDecimal.ONE, "connected", null),
                new RouteConnection("d1", "different", BigDecimal.ONE, "connected", null),
                new RouteConnection("d1", "p1", BigDecimal.TEN, "connected", null),
                new RouteConnection("d1", "p1", BigDecimal.ONE, "no_route", null),
                new RouteConnection("d1", "p1", BigDecimal.ONE, "connected", "reason"),
                new RouteConnection("d1", "p1", BigDecimal.ONE, "connected", null,
                        new RouteFailureDiagnostics(1, 1, List.of("target"), List.of("blocker"), 20)))) {
            different(source, copy(source, source.getNodes(), source.getEdges(),
                    List.of(connection, source.getConnections().get(1))));
        }
    }

    @Test
    void preservesPipeFlowsDiametersAndSectionAttributes() {
        RouteVariant source = network("source", "a:");
        RouteEdge edge = source.getEdges().get(0);
        different(source, replaceEdge(source, 0, edge(edge, "root", "a:a", edge.getCoordinates(),
                new BigDecimal("2.001"), 100, List.of(), null)));
        different(source, replaceEdge(source, 0, edge(edge, "root", "a:a", edge.getCoordinates(),
                edge.getFlowTph(), 125, List.of(), null)));
        different(source, replaceEdge(source, 0, edge(edge, "root", "a:a", edge.getCoordinates(),
                null, null, List.of(), null)));
        RouteSection base = new RouteSection("special", "road", "r1", edge.getCoordinates(), 10, 90.0);
        RouteVariant sectioned = replaceEdge(source, 0, withSections(edge, List.of(base)));
        different(source, sectioned);
        for (RouteSection section : List.of(
                new RouteSection("base", "road", "r1", edge.getCoordinates(), 10, 90.0),
                new RouteSection("special", "water", "r1", edge.getCoordinates(), 10, 90.0),
                new RouteSection("special", "road", "r2", edge.getCoordinates(), 10, 90.0),
                new RouteSection("special", "road", "r1", reversed(edge.getCoordinates()), 10, 90.0),
                new RouteSection("special", "road", "r1", edge.getCoordinates(), 11, 90.0),
                new RouteSection("special", "road", "r1", edge.getCoordinates(), 10, 89.0))) {
            different(sectioned, replaceEdge(source, 0, withSections(edge, List.of(section))));
        }
    }

    @Test
    void preservesDepthGeometryClearancesAndIssues() {
        RouteVariant source = network("source", "a:");
        DepthCrossingDecision crossing = crossing("above", "0.4");
        DepthProfileResult base = profile("1.0", List.of(crossing), List.of());
        RouteVariant profiled = withDepth(source, base);
        different(source, profiled);
        assertThat(CorridorNetworkIdentity.of(profiled)).isEqualTo(CorridorNetworkIdentity.of(
                withDepth(network("copy", "b:"), profile("1.0", List.of(crossing("above", "0.4")), List.of()))));
        different(profiled, withDepth(source, profile("1.1", List.of(crossing), List.of())));
        different(profiled, withDepth(source, profile("1.0", List.of(crossing("below", "0.4")), List.of())));
        different(profiled, withDepth(source, profile("1.0", List.of(crossing("above", "0.5")), List.of())));
        different(profiled, withDepth(source, profile("1.0", List.of(crossing),
                List.of(new DepthProfileIssue("issue", "crossing", "message")))));
    }

    @Test
    void preservesReconstructionAndEconomicAssessment() {
        RouteVariant source = network("source", "a:");
        ExistingNetworkReconstructionResult reconstruction = new ExistingNetworkReconstructionResult(
                List.of(new NetworkReconstructionSection("rs", "feature", List.of(point(0, 0), point(10, 0)),
                        10, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("2"), 65, 100, false)),
                List.of(new ChamberReconstruction("chamber", point(0, 0), BigDecimal.ONE, new BigDecimal("2"), 65, 100)),
                List.of());
        different(source, assessment(source, source.getEconomics(), reconstruction));
        different(source, assessment(source, economics(101), null));
        assertThat(CorridorNetworkIdentity.of(assessment(source, economics(100), null)))
                .isEqualTo(CorridorNetworkIdentity.of(source));
    }

    @Test
    void retainsEdgeMultiplicityAndIsolatedNodes() {
        RouteVariant source = network("source", "a:");
        List<RouteEdge> parallel = new ArrayList<>(source.getEdges());
        RouteEdge edge = parallel.get(0);
        parallel.add(new RouteEdge("parallel", edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                edge.getLengthM().doubleValue(), edge.getCoordinates(), edge.getSections(), edge.getFlowTph(), edge.getDiameter()));
        different(source, copy(source, source.getNodes(), parallel, source.getConnections()));
        List<RouteNode> nodes = new ArrayList<>(source.getNodes());
        nodes.add(new RouteNode("extra", "new_branch_chamber", point(50, 50), true, false, 0, null));
        different(source, copy(source, nodes, source.getEdges(), source.getConnections()));
    }

    @Test
    void ambiguousCoincidentNodesAndBrokenReferencesDeclineDeduplication() {
        RouteVariant source = network("source", "a:");
        RouteNode branch = source.getNodes().get(1);
        assertThat(CorridorNetworkIdentity.of(replaceNode(source, 2, new RouteNode("a:b", branch.getNodeType(),
                branch.getCoordinate(), true, false, 0, null)))).isEmpty();
        assertThat(CorridorNetworkIdentity.of(replaceNode(source, 2, branch))).isEmpty();
        RouteEdge edge = source.getEdges().get(0);
        assertThat(CorridorNetworkIdentity.of(replaceEdge(source, 0, edge(edge, "missing", "a:a", edge.getCoordinates(),
                edge.getFlowTph(), edge.getDiameter(), List.of(), null)))).isEmpty();
        assertThat(CorridorNetworkIdentity.of(replaceEdge(source, 1, edge))).isEmpty();
    }

    @Test
    void lengthFramingCannotConfuseIdentifierDelimitersOrNullWithText() {
        RouteVariant source = network("source", "a:");
        RouteVariant first = copy(source, source.getNodes(), source.getEdges(),
                List.of(new RouteConnection("a|b", "c", BigDecimal.ONE, "connected", null)));
        RouteVariant second = copy(source, source.getNodes(), source.getEdges(),
                List.of(new RouteConnection("a", "b|c", BigDecimal.ONE, "connected", null)));
        different(first, second);
        different(first, copy(first, first.getNodes(), first.getEdges(),
                List.of(new RouteConnection("a|b", "c", BigDecimal.ONE, "connected", "-;"))));
    }

    @Test
    void oversizedIdentityFallsBackWithoutRejectingTheNetwork() {
        RouteVariant source = network("source", "a:");
        RouteVariant large = copy(source, source.getNodes(), source.getEdges(),
                List.of(new RouteConnection("x".repeat(1_000_001), "point", BigDecimal.ONE, "connected", null)));
        assertThat(CorridorNetworkIdentity.of(large)).isEmpty();
        List<String> expanded = new ArrayList<>();
        CorridorRefinementSearch.improve(List.of(large, large), false, v -> { expanded.add(v.getId()); return List.of(); });
        assertThat(expanded).hasSize(2);
    }

    @Test
    void cancellationIsObservedAtEntryAndDuringCoordinateTraversalWithoutClearingTheFlag() {
        RouteVariant source = network("source", "a:");
        assertThatThrownBy(() -> {
            try {
                Thread.currentThread().interrupt();
                CorridorNetworkIdentity.of(source);
            } finally {
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                Thread.interrupted();
            }
        }).isInstanceOf(CancellationException.class);
        RouteNode branch = source.getNodes().get(1);
        RouteCoordinate interrupting = new RouteCoordinate(10, 0) {
            @Override public BigDecimal getXM() {
                Thread.currentThread().interrupt(); return super.getXM();
            }
        };
        RouteVariant interrupted = replaceNode(source, 1, new RouteNode(branch.getId(), branch.getNodeType(),
                interrupting, true, false, 0, null));
        assertThatThrownBy(() -> {
            try { CorridorNetworkIdentity.of(interrupted); }
            finally {
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                Thread.interrupted();
            }
        }).isInstanceOf(CancellationException.class);
    }

    private void different(RouteVariant first, RouteVariant second) {
        assertThat(CorridorNetworkIdentity.of(first)).isPresent();
        assertThat(CorridorNetworkIdentity.of(second)).isPresent().isNotEqualTo(CorridorNetworkIdentity.of(first));
    }

    private RouteVariant network(String id, String prefix) {
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber", point(0, 0), true, true, 2, "existing-root"),
                new RouteNode(prefix + "a", "new_branch_chamber", point(10, 0), true, false, 0, null),
                new RouteNode(prefix + "b", "new_branch_chamber", point(20, 0), true, false, 0, null),
                new RouteNode("demand:d1", "demand", point(30, 0), false, false, 0, "p1"),
                new RouteNode("demand:d2", "demand", point(20, 10), false, false, 0, "p2"));
        List<RouteEdge> edges = new ArrayList<>();
        int[][] ends = {{0, 1}, {1, 2}, {2, 3}, {2, 4}};
        for (int i = 0; i < ends.length; i++) {
            RouteNode from = nodes.get(ends[i][0]), to = nodes.get(ends[i][1]);
            edges.add(new RouteEdge(prefix + "edge" + i, from.getId(), to.getId(), 10,
                    List.of(from.getCoordinate(), to.getCoordinate()), List.of(), BigDecimal.valueOf(i < 2 ? 2 : 1), 100));
        }
        return new RouteVariant(id, "engineering", nodes, edges, List.of(
                new RouteConnection("d1", "p1", BigDecimal.ONE, "connected", null),
                new RouteConnection("d2", "p2", BigDecimal.ONE, "connected", null)),
                BigDecimal.valueOf(40), List.of(), List.of(), null, economics(100), null);
    }

    private RouteVariant copy(RouteVariant source, List<RouteNode> nodes, List<RouteEdge> edges, List<RouteConnection> connections) {
        return new RouteVariant(source.getId(), source.getStrategy(), nodes, edges, connections, source.getTotalLengthM(),
                source.getValidationIssues(), source.getEngineeringIssues(), source.getSizingIssues(),
                source.getReconstruction(), source.getEconomics(), source.getRank());
    }

    private RouteVariant assessment(RouteVariant source, VariantEconomics economics, ExistingNetworkReconstructionResult reconstruction) {
        return new RouteVariant(source.getId(), source.getStrategy(), source.getNodes(), source.getEdges(), source.getConnections(),
                source.getTotalLengthM(), List.of(), List.of(), reconstruction, economics, null);
    }

    private RouteVariant replaceNode(RouteVariant source, int index, RouteNode node) {
        List<RouteNode> nodes = new ArrayList<>(source.getNodes()); nodes.set(index, node);
        return copy(source, nodes, source.getEdges(), source.getConnections());
    }

    private RouteVariant replaceEdge(RouteVariant source, int index, RouteEdge edge) {
        List<RouteEdge> edges = new ArrayList<>(source.getEdges()); edges.set(index, edge);
        return copy(source, source.getNodes(), edges, source.getConnections());
    }

    private RouteEdge edge(RouteEdge source, String from, String to, List<RouteCoordinate> coordinates,
            BigDecimal flow, Integer diameter, List<RouteSection> sections, DepthProfileResult depth) {
        return new RouteEdge(source.getId(), from, to, source.getLengthM().doubleValue(), coordinates, sections, flow, diameter, depth);
    }

    private RouteEdge withSections(RouteEdge edge, List<RouteSection> sections) {
        return edge(edge, edge.getUpstreamNodeId(), edge.getDownstreamNodeId(), edge.getCoordinates(),
                edge.getFlowTph(), edge.getDiameter(), sections, edge.getDepthProfile());
    }

    private RouteVariant withDepth(RouteVariant source, DepthProfileResult depth) {
        RouteEdge edge = source.getEdges().get(0);
        return replaceEdge(source, 0, edge(edge, edge.getUpstreamNodeId(), edge.getDownstreamNodeId(), edge.getCoordinates(),
                edge.getFlowTph(), edge.getDiameter(), edge.getSections(), depth));
    }

    private DepthProfileResult profile(String depth, List<DepthCrossingDecision> crossings, List<DepthProfileIssue> issues) {
        return new DepthProfileResult(true, List.of(new DepthProfilePoint(BigDecimal.ZERO, new BigDecimal(depth)),
                new DepthProfilePoint(BigDecimal.TEN, new BigDecimal(depth))), crossings, issues, BigDecimal.TEN, BigDecimal.TEN);
    }

    private DepthCrossingDecision crossing(String passage, String clearance) {
        return new DepthCrossingDecision("crossing", "water", passage, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, new BigDecimal("9"), BigDecimal.TEN, new BigDecimal(clearance), new BigDecimal("0.3"));
    }

    private VariantEconomics economics(int cost) {
        return new VariantEconomics(true, BigDecimal.valueOf(cost), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(cost), BigDecimal.valueOf(40),
                BigDecimal.ZERO, BigDecimal.valueOf(40), BigDecimal.ONE, List.of());
    }

    private RouteCoordinate point(double x, double y) { return new RouteCoordinate(x, y); }

    private <T> List<T> reversed(List<T> source) {
        List<T> copy = new ArrayList<>(source); Collections.reverse(copy); return copy;
    }
}
