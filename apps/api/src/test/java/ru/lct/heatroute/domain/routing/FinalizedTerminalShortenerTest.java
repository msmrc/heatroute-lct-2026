package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.DepthProfileIssue;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.sizing.NetworkSizingIssue;

class FinalizedTerminalShortenerTest {
    private final FinalizedTerminalShortener shortener = new FinalizedTerminalShortener();
    private final EngineeringRouteEvaluator engineering = new EngineeringRouteEvaluator();
    private final OfficialRouteValidator validator = new OfficialRouteValidator(new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));
    private final OfficialVariantEconomicsCalculator economics = new OfficialVariantEconomicsCalculator(
            new OfficialPipeCatalog(), new OfficialEconomics());

    @Test
    void shortensValidatedControlFromEightyToSixtyWithoutMutatingBaseline() {
        RouteVariant baseline = control();
        assertThat(baseline.isValid()).isTrue();
        assertThat(baseline.getEconomics().isComplete()).isTrue();
        assertThat(engineering.evaluate(baseline.getEdges()).isCompliant()).isTrue();
        RoutePath direct = path(0, 0, 20, 0);
        RouteVariant result = shortener.improve(baseline, false,
                (current, edge) -> "leaf-1".equals(edge.getId()) ? List.of(direct) : List.of(),
                this::finish);

        assertThat(result).isNotSameAs(baseline);
        assertThat(result.getTotalLengthM()).isEqualByComparingTo("60");
        assertThat(result.getNodes()).containsExactlyElementsOf(baseline.getNodes());
        assertThat(result.getConnections()).containsExactlyElementsOf(baseline.getConnections());
        assertThat(result.getEdges()).extracting(RouteEdge::getId).containsExactly("trunk", "leaf-1", "leaf-2");
        assertThat(result.getValidationIssues()).isEmpty();
        assertThat(result.getSizingIssues()).isEmpty();
        assertThat(result.getEngineeringIssues()).isEmpty();
        assertThat(engineering.evaluate(result.getEdges()).isCompliant()).isTrue();
        assertThat(engineering.evaluate(result.getEdges()).bendCount()).isZero();
        assertThat(result.getEconomics().getCalculatedCost()).isLessThan(baseline.getEconomics().getCalculatedCost());
        assertThat(baseline.getTotalLengthM()).isEqualByComparingTo("80");
        assertThat(baseline.getEdges().get(1).getLengthM()).isEqualByComparingTo("40");
        assertThat(baseline.getEdges().get(1).getCoordinates()).hasSize(5);
    }

    @Test
    void submitsOnlyOneReplacementWithSameIdentityFlowDiameterAndSuppliedSections() {
        RouteVariant baseline = control();
        RoutePath direct = path(0, 0, 20, 0);
        AtomicInteger finishes = new AtomicInteger();
        RouteVariant result = shortener.improve(baseline, false,
                (current, edge) -> "leaf-1".equals(edge.getId()) ? List.of(direct) : List.of(),
                (current, edges) -> {
                    finishes.incrementAndGet();
                    assertThat(current).isSameAs(baseline);
                    assertThat(edges.get(0)).isSameAs(baseline.getEdges().get(0));
                    assertThat(edges.get(2)).isSameAs(baseline.getEdges().get(2));
                    RouteEdge replacement = edges.get(1);
                    RouteEdge original = baseline.getEdges().get(1);
                    assertThat(replacement.getId()).isEqualTo(original.getId());
                    assertThat(replacement.getUpstreamNodeId()).isEqualTo("camera");
                    assertThat(replacement.getDownstreamNodeId()).isEqualTo("demand-1");
                    assertThat(replacement.getFlowTph()).isEqualByComparingTo(original.getFlowTph());
                    assertThat(replacement.getDiameter()).isEqualTo(original.getDiameter());
                    assertThat(replacement.getSections()).containsExactlyElementsOf(direct.sections());
                    assertThat(replacement.getCoordinates()).extracting(RouteCoordinate::getXM)
                            .containsExactly(new BigDecimal("0.000"), new BigDecimal("20.000"));
                    return finish(current, edges);
                });
        assertThat(finishes).hasValue(1);
        assertThat(result.getTotalLengthM()).isEqualByComparingTo("60");
    }

    @Test
    void preservesBaselineForInvalidIncompleteOrUncostedInputsWithoutCallingCallbacks() {
        RouteVariant baseline = control();
        List<RouteVariant> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(copy(baseline, baseline.getNodes(), baseline.getEdges(), baseline.getConnections(),
                List.of(issue()), List.of(), List.of(), baseline.getEconomics(), baseline.getTotalLengthM()));
        invalid.add(baseline.withEngineeringIssues(List.of(issue())));
        invalid.add(copy(baseline, baseline.getNodes(), baseline.getEdges(), baseline.getConnections(),
                List.of(), List.of(), List.of(new NetworkSizingIssue("SIZING", "leaf-1", "invalid")),
                baseline.getEconomics(), baseline.getTotalLengthM()));
        invalid.add(withEconomics(baseline, null));
        invalid.add(withEconomics(baseline, cost(baseline, false, baseline.getEconomics().getCalculatedCost())));
        invalid.add(withEconomics(baseline, cost(baseline, true, null)));
        invalid.add(withConnections(baseline, List.of(
                baseline.getConnections().get(0), new RouteConnection("demand-2", "point-2", BigDecimal.ONE, "no_route", "none"))));
        invalid.add(withEdges(baseline, List.of()));
        List<RouteEdge> unsized = new ArrayList<>(baseline.getEdges());
        RouteEdge leaf = unsized.get(1);
        unsized.set(1, new RouteEdge(leaf.getId(), leaf.getUpstreamNodeId(), leaf.getDownstreamNodeId(),
                40, leaf.getCoordinates(), leaf.getSections(), null, null));
        invalid.add(withEdges(baseline, unsized));
        invalid.add(copy(baseline, baseline.getNodes(), baseline.getEdges(), baseline.getConnections(),
                List.of(), List.of(), List.of(), baseline.getEconomics(), new BigDecimal("79")));
        for (RouteVariant input : invalid) {
            assertThat(shortener.improve(input, false,
                    (current, edge) -> { throw new AssertionError("Unexpected alternatives"); },
                    (current, edges) -> { throw new AssertionError("Unexpected finish"); })).isSameAs(input);
        }
    }

    @Test
    void rejectsStoredIssuesMissingEconomicsLostCoverageAndNonDecreasingLengthAfterFinish() {
        RouteVariant baseline = control();
        List<UnaryOperator<RouteVariant>> corruptions = List.of(
                candidate -> null,
                candidate -> baseline,
                candidate -> withEconomics(candidate, null),
                candidate -> withEconomics(candidate, cost(candidate, false, BigDecimal.ONE)),
                candidate -> withEconomics(candidate, cost(candidate, true, null)),
                candidate -> withEconomics(candidate, cost(candidate, true,
                        baseline.getEconomics().getCalculatedCost().add(BigDecimal.ONE))),
                candidate -> candidate.withEngineeringIssues(List.of(issue())),
                candidate -> copy(candidate, candidate.getNodes(), candidate.getEdges(), candidate.getConnections(),
                        List.of(issue()), List.of(), List.of(), candidate.getEconomics(), candidate.getTotalLengthM()),
                candidate -> copy(candidate, candidate.getNodes(), candidate.getEdges(), candidate.getConnections(),
                        List.of(), List.of(), List.of(new NetworkSizingIssue("SIZING", "leaf-1", "invalid")),
                        candidate.getEconomics(), candidate.getTotalLengthM()),
                candidate -> withConnections(candidate, List.of(candidate.getConnections().get(0))),
                candidate -> withConnections(candidate, List.of(candidate.getConnections().get(0),
                        new RouteConnection("demand-2", "point-2", BigDecimal.ONE, "no_route", "none"))),
                candidate -> withConnections(candidate, List.of(candidate.getConnections().get(0),
                        new RouteConnection("other-demand", "point-2", BigDecimal.ONE, "connected", null))),
                candidate -> withConnections(candidate, List.of(candidate.getConnections().get(0),
                        new RouteConnection("demand-2", "other-point", BigDecimal.ONE, "connected", null))),
                candidate -> withConnections(candidate, List.of(candidate.getConnections().get(0),
                        new RouteConnection("demand-2", "point-2", BigDecimal.TEN, "connected", null))),
                candidate -> copy(candidate, candidate.getNodes(), candidate.getEdges(), candidate.getConnections(),
                        List.of(), List.of(), List.of(), candidate.getEconomics(), baseline.getTotalLengthM()));
        for (UnaryOperator<RouteVariant> corruption : corruptions) {
            AtomicInteger finishes = new AtomicInteger();
            RouteVariant result = shortener.improve(baseline, false, this::directAlternative, (current, edges) -> {
                finishes.incrementAndGet();
                return corruption.apply(finish(current, edges));
            });
            assertThat(finishes).hasValue(2);
            assertThat(result).isSameAs(baseline);
        }
    }

    @Test
    void preservesNodeIdentityCameraAndRootSemanticsEvenWhenFinishReportsNoIssues() {
        RouteVariant baseline = control();
        RouteNode root = baseline.getNodes().get(0);
        List<RouteNode> invalidRoots = List.of(
                new RouteNode("other-root", root.getNodeType(), root.getCoordinate(), true, true, 2, "existing", 100),
                new RouteNode(root.getId(), "new_chamber_tie_in", root.getCoordinate(), true, true, 2, "existing", 100),
                new RouteNode(root.getId(), root.getNodeType(), root.getCoordinate(), true, false, 2, "existing", 100),
                new RouteNode(root.getId(), root.getNodeType(), root.getCoordinate(), false, true, 2, "existing", 100),
                new RouteNode(root.getId(), root.getNodeType(), root.getCoordinate(), true, true, 1, "existing", 100),
                new RouteNode(root.getId(), root.getNodeType(), root.getCoordinate(), true, true, 2, "different", 100),
                new RouteNode(root.getId(), root.getNodeType(), root.getCoordinate(), true, true, 2, "existing", 150),
                new RouteNode(root.getId(), root.getNodeType(), new RouteCoordinate(-21, 0), true, true, 2, "existing", 100));
        for (RouteNode invalidRoot : invalidRoots) {
            assertRejected(candidate -> {
                List<RouteNode> nodes = new ArrayList<>(candidate.getNodes());
                nodes.set(0, invalidRoot);
                return withNodes(candidate, nodes);
            });
        }
        assertRejected(candidate -> {
            List<RouteNode> nodes = new ArrayList<>(candidate.getNodes());
            RouteNode demand = nodes.get(2);
            nodes.set(2, new RouteNode(demand.getId(), demand.getNodeType(), demand.getCoordinate(), true, false, 0, demand.getTargetId()));
            return withNodes(candidate, nodes);
        });
        assertRejected(candidate -> {
            List<RouteNode> nodes = new ArrayList<>(candidate.getNodes());
            nodes.add(new RouteNode("extra-camera", "new_chamber", new RouteCoordinate(10, 0), true, false, 0, null));
            return withNodes(candidate, nodes);
        });
        assertRejected(candidate -> withNodes(candidate, candidate.getNodes().subList(0, 3)));
    }

    @Test
    void rejectsTopologySizingAndUnrelatedGeometryChangesAfterFinish() {
        List<UnaryOperator<RouteEdge>> corruptions = List.of(
                edge -> new RouteEdge("other-edge", edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                        20, edge.getCoordinates(), edge.getSections(), edge.getFlowTph(), edge.getDiameter()),
                edge -> new RouteEdge(edge.getId(), "root", edge.getDownstreamNodeId(),
                        20, edge.getCoordinates(), edge.getSections(), edge.getFlowTph(), edge.getDiameter()),
                edge -> new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), "demand-2",
                        20, edge.getCoordinates(), edge.getSections(), edge.getFlowTph(), edge.getDiameter()),
                edge -> new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                        20, edge.getCoordinates(), edge.getSections(), BigDecimal.TEN, edge.getDiameter()),
                edge -> new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                        20, edge.getCoordinates(), edge.getSections(), edge.getFlowTph(), 65));
        for (UnaryOperator<RouteEdge> corruption : corruptions) {
            assertRejected(candidate -> {
                List<RouteEdge> edges = new ArrayList<>(candidate.getEdges());
                edges.set(1, corruption.apply(edges.get(1)));
                return withEdges(candidate, edges);
            });
        }
        assertRejected(candidate -> withEdges(candidate, candidate.getEdges().subList(0, 2)));
        assertRejected(candidate -> {
            List<RouteEdge> edges = new ArrayList<>(candidate.getEdges());
            edges.set(0, edge("trunk", "root", "camera", 2, path(-20, 0, -10, 2, 0, 0)));
            return withEdges(candidate, edges);
        });
    }

    @Test
    void rejectsCrossingAnotherEdgeThroughRealFinalValidator() {
        RouteVariant baseline = control();
        RoutePath crossed = path(0, 0, 0, -5, 10, -5, 10, 0, 20, 0);
        AtomicInteger finishes = new AtomicInteger();
        assertThat(shortener.improve(baseline, false,
                (current, edge) -> "leaf-1".equals(edge.getId()) ? List.of(crossed) : List.of(),
                (current, edges) -> {
                    finishes.incrementAndGet();
                    RouteVariant candidate = finish(current, edges);
                    assertThat(candidate.getValidationIssues()).extracting(RouteValidationIssue::getCode)
                            .contains("CROSSING_OUTSIDE_COMMON_NODE");
                    return candidate;
                })).isSameAs(baseline);
        assertThat(finishes).hasValue(2);
    }

    @Test
    void checksActualEngineeringAndBendCountEvenWithEmptyStoredEngineeringIssues() {
        RouteVariant baseline = control();
        List<RoutePath> rejected = List.of(
                path(0, 0, 10, 2, 20, 0),
                path(0, 0, 4, 0, 4, 3, 8, 3, 8, 6, 12, 6, 12, 0, 20, 0),
                path(0, 0, 4, 0, 4, 1, 8, 1, 8, 0, 20, 0));
        for (RoutePath path : rejected) {
            AtomicInteger finishes = new AtomicInteger();
            assertThat(shortener.improve(baseline, false,
                    (current, edge) -> "leaf-1".equals(edge.getId()) ? List.of(path) : List.of(),
                    (current, edges) -> {
                        finishes.incrementAndGet();
                        RouteVariant candidate = finish(current, edges);
                        EngineeringRouteEvaluator.Evaluation actual = engineering.evaluate(edges);
                        assertThat(!actual.isCompliant() || actual.bendCount() > 3).isTrue();
                        return copy(candidate, candidate.getNodes(), edges, candidate.getConnections(),
                                List.of(), List.of(), List.of(), cost(candidate, true, BigDecimal.ONE), candidate.getTotalLengthM());
                    })).isSameAs(baseline);
            assertThat(finishes).hasValue(2);
        }
    }

    @Test
    void rejectsActuallyNonCompliantBaselineBeforeLookingForPaths() {
        RouteVariant baseline = control();
        List<RouteEdge> edges = new ArrayList<>(baseline.getEdges());
        edges.set(1, edge("leaf-1", "camera", "demand-1", 1, path(0, 0, 10, 2, 20, 0)));
        RouteVariant invalid = withEdges(baseline, edges);
        assertThat(shortener.improve(invalid, false,
                (current, edge) -> { throw new AssertionError("Unexpected alternatives"); }, this::finish)).isSameAs(invalid);
    }

    @Test
    void validatesFiniteSimpleOrientedBoundedPathsBeforeFinish() {
        RouteVariant baseline = control();
        RoutePath direct = path(0, 0, 20, 0);
        List<RoutePath> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(direct.reversed());
        invalid.add(path(1, 0, 20, 0));
        invalid.add(path(0, 0, 19, 0));
        invalid.add(path(0, 0, 2, 0, 2, 2, 1, 2, 1, -1, 20, 0));
        invalid.add(path(0, 0, 10, 0, 0, 0));
        invalid.add(new RoutePath(List.of(new Coordinate(0, 0)), direct.sections(), 20));
        invalid.add(new RoutePath(List.of(), direct.sections(), 20));
        invalid.add(new RoutePath(List.of(new Coordinate(0, 0), new Coordinate(Double.NaN, 1),
                new Coordinate(20, 0)), direct.sections(), 20));
        invalid.add(new RoutePath(List.of(new Coordinate(0, 0), new Coordinate(Double.POSITIVE_INFINITY, 1),
                new Coordinate(20, 0)), direct.sections(), 20));
        invalid.add(new RoutePath(direct.coordinates(), direct.sections(), Double.NaN));
        invalid.add(new RoutePath(direct.coordinates(), direct.sections(), Double.POSITIVE_INFINITY));
        invalid.add(new RoutePath(direct.coordinates(), direct.sections(), 19));
        invalid.add(new RoutePath(direct.coordinates(), List.of(), 20));
        invalid.add(denseDirect(1001));
        for (RoutePath path : invalid) {
            assertThat(shortener.improve(baseline, false,
                    (current, edge) -> "leaf-1".equals(edge.getId()) ? Collections.singletonList(path) : List.of(),
                    (current, edges) -> { throw new AssertionError("Invalid path reached finish"); })).isSameAs(baseline);
        }
        assertThat(shortener.improve(baseline, false,
                (current, edge) -> "leaf-1".equals(edge.getId()) ? List.of(denseDirect(1000)) : List.of(),
                this::finish).getTotalLengthM()).isEqualByComparingTo("60");
    }

    @Test
    void skipsPathsThatAreNotMoreThanOneCentimetreShorter() {
        RouteVariant baseline = control();
        AtomicInteger finishes = new AtomicInteger();
        List<RoutePath> paths = List.of(
                path(0, 0, 0, 11, 8, 11, 8, 0, 20, 0),
                path(0, 0, 0, 10, 8, 10, 8, 0, 20, 0),
                path(0, 0, 0, 9.995, 8, 9.995, 8, 0, 20, 0),
                path(0, 0, 0, 9.994, 8, 9.994, 8, 0, 20, 0));
        RouteVariant result = shortener.improve(baseline, false,
                (current, edge) -> "leaf-1".equals(edge.getId()) ? paths : List.of(),
                (current, edges) -> { finishes.incrementAndGet(); return finish(current, edges); });
        assertThat(finishes).hasValue(1);
        assertThat(result.getTotalLengthM()).isEqualByComparingTo("79.988");
    }

    @Test
    void requiresRecomputedCompleteDepthOnlyWhenEnabled() {
        RouteVariant baseline = withDepth(control());
        assertThat(shortener.improve(control(), true,
                (current, edge) -> { throw new AssertionError("Incomplete baseline depth"); }, this::finish)
                .getTotalLengthM()).isEqualByComparingTo("80");
        assertThat(shortener.improve(baseline, true, this::directAlternative, this::finish)).isSameAs(baseline);
        RouteVariant result = shortener.improve(baseline, true, this::directAlternative, (current, edges) -> {
            assertThat(edges.get(1).getDepthProfile()).isNull();
            return withDepth(finish(current, edges));
        });
        assertThat(result.getTotalLengthM()).isEqualByComparingTo("60");
        assertThat(result.getEdges()).allSatisfy(edge -> assertThat(edge.getDepthProfile().isComplete()).isTrue());
        List<DepthProfileResult> invalid = List.of(
                new DepthProfileResult(false, depthPoints(20), List.of(), List.of(), BigDecimal.valueOf(20), BigDecimal.valueOf(20)),
                new DepthProfileResult(true, depthPoints(20), List.of(), List.of(new DepthProfileIssue("DEPTH", null, "invalid")),
                        BigDecimal.valueOf(20), BigDecimal.valueOf(20)),
                new DepthProfileResult(true, List.of(), List.of(), List.of(), BigDecimal.valueOf(20), BigDecimal.valueOf(20)),
                baseline.getEdges().get(1).getDepthProfile());
        for (DepthProfileResult profile : invalid) {
            assertThat(shortener.improve(baseline, true, this::directAlternative, (current, edges) -> {
                RouteVariant candidate = withDepth(finish(current, edges));
                List<RouteEdge> changed = new ArrayList<>(candidate.getEdges());
                changed.set(1, withDepth(changed.get(1), profile));
                return withEdges(candidate, changed);
            })).isSameAs(baseline);
        }
    }

    @Test
    void usesStableLeafIdsAndCurrentStateAcrossTwoPasses() {
        RouteVariant baseline = control();
        List<RouteEdge> reversed = new ArrayList<>(baseline.getEdges());
        Collections.reverse(reversed);
        RouteVariant shuffled = withEdges(baseline, reversed);
        List<String> calls = new ArrayList<>();
        RouteVariant result = shortener.improve(shuffled, false, (current, edge) -> {
            calls.add(edge.getId() + ":" + current.getTotalLengthM().toPlainString());
            if (!"leaf-1".equals(edge.getId())) return List.of();
            return List.of(current.getTotalLengthM().compareTo(new BigDecimal("80")) == 0
                    ? path(0, 0, 0, 5, 8, 5, 8, 0, 20, 0) : path(0, 0, 20, 0));
        }, this::finish);
        assertThat(calls).containsExactly("leaf-1:80.000", "leaf-2:70.000", "leaf-1:70.000", "leaf-2:60.000");
        assertThat(result.getTotalLengthM()).isEqualByComparingTo("60");
    }

    @Test
    void selectsOnlyDownstreamDemandLeaves() {
        RouteVariant baseline = control();
        List<RouteNode> nodes = new ArrayList<>(baseline.getNodes());
        RouteNode demand = nodes.get(2);
        nodes.set(2, new RouteNode(demand.getId(), "demand", demand.getCoordinate(), false, false, 0, demand.getTargetId()));
        List<String> calls = new ArrayList<>();
        shortener.improve(withNodes(baseline, nodes), false,
                (current, edge) -> { calls.add(edge.getId()); return List.of(); }, this::finish);
        assertThat(calls).containsExactly("leaf-2", "leaf-2");

        nodes = new ArrayList<>(baseline.getNodes());
        nodes.add(new RouteNode("extension", "new_chamber", new RouteCoordinate(30, 0), true, false, 0, null));
        List<RouteEdge> edges = new ArrayList<>(baseline.getEdges());
        edges.add(edge("extension", "demand-1", "extension", 1, path(20, 0, 30, 0)));
        RouteVariant nonLeaf = evaluated(nodes, edges, baseline.getConnections());
        assertThat(nonLeaf.isValid()).isTrue();
        calls.clear();
        shortener.improve(nonLeaf, false, (current, edge) -> { calls.add(edge.getId()); return List.of(); }, this::finish);
        assertThat(calls).containsExactly("leaf-2", "leaf-2");
    }

    @Test
    void boundsLeafVisitsPathsAndTotalFinishAttempts() {
        RouteVariant many = repeatedControls(25);
        List<String> visits = new ArrayList<>();
        assertThat(shortener.improve(many, false,
                (current, edge) -> { visits.add(edge.getId()); return List.of(); }, this::finish)).isSameAs(many);
        List<String> expected = many.getEdges().stream().filter(edge -> edge.getId().contains("leaf-"))
                .map(RouteEdge::getId).sorted().limit(24).collect(Collectors.toList());
        List<String> bothPasses = new ArrayList<>(expected);
        bothPasses.addAll(expected);
        assertThat(visits).containsExactlyElementsOf(bothPasses);

        AtomicInteger finishes = new AtomicInteger();
        shortener.improve(control(), false,
                (current, edge) -> "leaf-1".equals(edge.getId()) ? Collections.nCopies(9, path(0, 0, 20, 0)) : List.of(),
                (current, edges) -> { finishes.incrementAndGet(); return current; });
        assertThat(finishes).hasValue(16);
        finishes.set(0);
        assertThat(shortener.improve(many, false, (current, edge) -> {
            if (!edge.getId().endsWith("leaf-1")) return List.of();
            RouteCoordinate first = edge.getCoordinates().get(0);
            RouteCoordinate last = edge.getCoordinates().get(edge.getCoordinates().size() - 1);
            return Collections.nCopies(8, path(first.getXM().doubleValue(), first.getYM().doubleValue(),
                    last.getXM().doubleValue(), last.getYM().doubleValue()));
        }, (current, edges) -> { finishes.incrementAndGet(); return current; })).isSameAs(many);
        assertThat(finishes).hasValue(48);
    }

    @Test
    void propagatesCancellationAndRuntimeExceptionsWithoutClearingInterrupt() {
        RouteVariant baseline = control();
        RuntimeException failure = new IllegalStateException("finish failed");
        assertThatThrownBy(() -> shortener.improve(baseline, false,
                (current, edge) -> { throw failure; }, this::finish)).isSameAs(failure);
        assertThatThrownBy(() -> shortener.improve(baseline, false, this::directAlternative,
                (current, edges) -> { throw failure; })).isSameAs(failure);
        CancellationException cancelled = new CancellationException("cancelled");
        assertThatThrownBy(() -> shortener.improve(baseline, false, this::directAlternative,
                (current, edges) -> { throw cancelled; })).isSameAs(cancelled);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> shortener.improve(baseline, false, this::directAlternative, this::finish))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        try {
            assertThatThrownBy(() -> shortener.improve(baseline, false, (current, edge) -> {
                Thread.currentThread().interrupt();
                return List.of();
            }, this::finish)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        try {
            assertThatThrownBy(() -> shortener.improve(baseline, false, this::directAlternative, (current, edges) -> {
                Thread.currentThread().interrupt();
                return finish(current, edges);
            })).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private void assertRejected(UnaryOperator<RouteVariant> corruption) {
        RouteVariant baseline = control();
        assertThat(shortener.improve(baseline, false, this::directAlternative,
                (current, edges) -> corruption.apply(finish(current, edges)))).isSameAs(baseline);
    }

    private List<RoutePath> directAlternative(RouteVariant current, RouteEdge edge) {
        return "leaf-1".equals(edge.getId()) ? List.of(path(0, 0, 20, 0)) : List.of();
    }

    private RoutePath denseDirect(int count) {
        double[] xy = new double[count * 2];
        for (int index = 0; index < count; index++) xy[index * 2] = 20.0 * index / (count - 1);
        return path(xy);
    }

    private RouteValidationIssue issue() {
        return new RouteValidationIssue("INVALID", "leaf-1", "invalid");
    }

    private RouteVariant copy(RouteVariant source, List<RouteNode> nodes, List<RouteEdge> edges,
            List<RouteConnection> connections, List<RouteValidationIssue> validation,
            List<RouteValidationIssue> engineeringIssues, List<NetworkSizingIssue> sizing,
            VariantEconomics cost, BigDecimal length) {
        return new RouteVariant(source.getId(), source.getStrategy(), nodes, edges, connections, length,
                validation, engineeringIssues, sizing, source.getReconstruction(), cost, source.getRank());
    }

    private RouteVariant withNodes(RouteVariant source, List<RouteNode> nodes) {
        return copy(source, nodes, source.getEdges(), source.getConnections(), source.getValidationIssues(),
                source.getEngineeringIssues(), source.getSizingIssues(), source.getEconomics(), source.getTotalLengthM());
    }

    private RouteVariant withEdges(RouteVariant source, List<RouteEdge> edges) {
        return copy(source, source.getNodes(), edges, source.getConnections(), source.getValidationIssues(),
                source.getEngineeringIssues(), source.getSizingIssues(), source.getEconomics(),
                edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private RouteVariant withConnections(RouteVariant source, List<RouteConnection> connections) {
        return copy(source, source.getNodes(), source.getEdges(), connections, source.getValidationIssues(),
                source.getEngineeringIssues(), source.getSizingIssues(), source.getEconomics(), source.getTotalLengthM());
    }

    private RouteVariant withEconomics(RouteVariant source, VariantEconomics cost) {
        return copy(source, source.getNodes(), source.getEdges(), source.getConnections(), source.getValidationIssues(),
                source.getEngineeringIssues(), source.getSizingIssues(), cost, source.getTotalLengthM());
    }

    private VariantEconomics cost(RouteVariant source, boolean complete, BigDecimal calculated) {
        VariantEconomics original = source.getEconomics();
        return new VariantEconomics(complete, original.getConstructionCost(), original.getChamberConstructionCost(),
                original.getTieInCost(), original.getReconstructionCost(), original.getChamberReconstructionCost(),
                original.getUnconnectedPenalty(), calculated, original.getNewNetworkLength(), original.getReconstructionLength(),
                original.getLength(), original.getScore(), complete ? List.of() : List.of("Incomplete"));
    }

    private List<DepthProfilePoint> depthPoints(double length) {
        return List.of(new DepthProfilePoint(BigDecimal.ZERO, BigDecimal.ONE),
                new DepthProfilePoint(BigDecimal.valueOf(length), BigDecimal.ONE));
    }

    private RouteEdge withDepth(RouteEdge edge, DepthProfileResult depth) {
        return new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(), edge.getLengthM().doubleValue(),
                edge.getCoordinates(), edge.getSections(), edge.getFlowTph(), edge.getDiameter(), depth);
    }

    private RouteVariant withDepth(RouteVariant source) {
        List<RouteEdge> edges = source.getEdges().stream().map(edge -> withDepth(edge, new DepthProfileResult(true,
                depthPoints(edge.getLengthM().doubleValue()), List.of(), List.of(), edge.getLengthM(), edge.getLengthM())))
                .collect(Collectors.toList());
        return evaluated(source.getNodes(), edges, source.getConnections());
    }

    private RouteVariant repeatedControls(int count) {
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        List<RouteConnection> connections = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String prefix = String.format("%02d:", index);
            double offset = index * 100.0;
            RouteVariant source = control();
            for (RouteNode node : source.getNodes()) {
                nodes.add(new RouteNode(prefix + node.getId(), node.getNodeType(),
                        new RouteCoordinate(node.getCoordinate().getXM().doubleValue() + offset, node.getCoordinate().getYM().doubleValue()),
                        node.isChamber(), node.isRoot(), node.getBaseIncidentSections(), node.getTargetId(), node.getExistingIncidentDiameter()));
            }
            for (RouteEdge edge : source.getEdges()) {
                double[] xy = new double[edge.getCoordinates().size() * 2];
                for (int point = 0; point < edge.getCoordinates().size(); point++) {
                    xy[point * 2] = edge.getCoordinates().get(point).getXM().doubleValue() + offset;
                    xy[point * 2 + 1] = edge.getCoordinates().get(point).getYM().doubleValue();
                }
                edges.add(edge(prefix + edge.getId(), prefix + edge.getUpstreamNodeId(), prefix + edge.getDownstreamNodeId(),
                        edge.getFlowTph().intValueExact(), path(xy)));
            }
            for (RouteConnection connection : source.getConnections()) {
                connections.add(new RouteConnection(prefix + connection.getDemandId(), prefix + connection.getConnectionPointId(),
                        connection.getFlowTph(), connection.getStatus(), connection.getReason()));
            }
        }
        RouteVariant result = evaluated(nodes, edges, connections);
        assertThat(result.isValid()).isTrue();
        assertThat(engineering.evaluate(edges).isCompliant()).isTrue();
        return result;
    }

    private RouteVariant control() {
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(-20, 0), true, true, 2, "existing", 100),
                new RouteNode("camera", "new_chamber", new RouteCoordinate(0, 0), true, false, 0, null),
                new RouteNode("demand-1", "demand_connection", new RouteCoordinate(20, 0), false, false, 0, "point-1"),
                new RouteNode("demand-2", "demand_connection", new RouteCoordinate(0, -20), false, false, 0, "point-2"));
        List<RouteEdge> edges = List.of(
                edge("trunk", "root", "camera", 2, path(-20, 0, 0, 0)),
                edge("leaf-1", "camera", "demand-1", 1, path(0, 0, 0, 10, 8, 10, 8, 0, 20, 0)),
                edge("leaf-2", "camera", "demand-2", 1, path(0, 0, 0, -20)));
        return evaluated(nodes, edges, List.of(
                new RouteConnection("demand-1", "point-1", BigDecimal.ONE, "connected", null),
                new RouteConnection("demand-2", "point-2", BigDecimal.ONE, "connected", null)));
    }

    private RouteVariant finish(RouteVariant current, List<RouteEdge> edges) {
        return evaluated(current.getNodes(), edges, current.getConnections());
    }

    private RouteVariant evaluated(List<RouteNode> nodes, List<RouteEdge> edges, List<RouteConnection> connections) {
        BigDecimal length = edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add);
        ExistingNetworkReconstructionResult reconstruction = ExistingNetworkReconstructionResult.empty();
        return new RouteVariant("control", "engineering", nodes, edges, connections, length,
                validator.validate(nodes, edges, List.of()), List.of(), List.of(), reconstruction,
                economics.calculate(nodes, edges, connections, reconstruction, false), null);
    }

    private RouteEdge edge(String id, String upstream, String downstream, int flow, RoutePath path) {
        return new RouteEdge(id, upstream, downstream, path.lengthM(), coordinates(path), path.sections(),
                BigDecimal.valueOf(flow), 50);
    }

    private List<RouteCoordinate> coordinates(RoutePath path) {
        return path.coordinates().stream().map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList());
    }

    private RoutePath path(double... xy) {
        List<Coordinate> points = new ArrayList<>();
        for (int index = 0; index < xy.length; index += 2) points.add(new Coordinate(xy[index], xy[index + 1]));
        double length = new GeometryFactory().createLineString(points.toArray(new Coordinate[0])).getLength();
        List<RouteCoordinate> coordinates = points.stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList());
        return new RoutePath(points, List.of(new RouteSection("base", null, null, coordinates, length, null)), length);
    }
}
