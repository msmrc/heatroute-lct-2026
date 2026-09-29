package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.sizing.NetworkTreeEdge;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Синтетический supporting tree: проверяет отдельный этап без полного plan(). */
class OfficialCoverageRecoveryTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);
    private final RegressionRoutePlannerFixture planner = new RegressionRoutePlannerFixture(validator, router, pipes,
            new OfficialNetworkSizer(pipes), new OfficialExistingNetworkReconstructor(pipes),
            new OfficialVariantEconomicsCalculator(pipes, new OfficialEconomics()),
            new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
                    new OfficialDepthOptimizer(pipes, new OfficialEconomics()),
                    new OfficialDepthProfileValidator(pipes)));

    @Test
    void recoversNoRouteWhenSupportingTreeAlreadyExistsWithoutLosingExistingCoverage() {
        RegressionRoutePlannerFixture.VariantDraft source = source();
        List<RegressionRoutePlannerFixture.VariantDraft> recovered = planner.recoverCoverageAlternatives(
                List.of(source), List.of(missing("missing", 50, 20)), router.prepare(List.of()));

        assertThat(recovered).hasSize(1);
        assertThat(connections(recovered.get(0))).extracting(RouteConnection::getDemandId)
                .containsExactlyInAnyOrder("known", "missing");
        assertThat(connections(recovered.get(0))).allMatch(c -> "connected".equals(c.getStatus()));
        assertThat(connections(source)).extracting(RouteConnection::getStatus)
                .containsExactly("connected", "no_route");
        assertThat(edges(source)).hasSize(1);
        assertSizedValid(recovered.get(0), List.of());
    }

    @Test
    void retainsNoRouteWhenDemandIsInsideForbiddenArea() throws Exception {
        List<ImportedOfficialFeature> features = List.of(new ImportedOfficialFeature("blocked", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "park"),
                new WKTReader().read("POLYGON ((40 10,60 10,60 30,40 30,40 10))")));
        RegressionRoutePlannerFixture.VariantDraft source = source();

        assertThat(planner.recoverCoverageAlternatives(List.of(source),
                List.of(missing("missing", 50, 20)), router.prepare(features))).isEmpty();
        assertThat(connections(source)).extracting(RouteConnection::getStatus)
                .containsExactly("connected", "no_route");
        assertSizedValid(source, features);
    }

    @Test
    void promotionUsesCombinedFlowAndDoesNotLeaveDuplicateOrStaleFailureStatuses() {
        var source = source();
        connections(source).add(new RouteConnection("missing", "missing", new BigDecimal("3"),
                "no_route", "NO_NON_CROSSING_ROUTE"));
        var demand = new RegressionRoutePlannerFixture.Demand("missing", "missing", new Coordinate(50, 20),
                new BigDecimal("3"), null);
        var result = planner.recoverCoverageAlternatives(List.of(source), List.of(demand), router.prepare(List.of()));

        assertThat(result).hasSize(1);
        assertThat(connections(result.get(0))).hasSize(2).allMatch(c -> "connected".equals(c.getStatus()));
        assertThat(assertSizedValid(result.get(0), List.of())).anySatisfy(edge -> {
            assertThat(edge.getFlowTph()).isEqualByComparingTo("4");
            assertThat(edge.getDiameter()).isEqualTo(65);
        });
        assertThat(connections(source)).hasSize(3);
    }

    @Test
    void rejectsAttachmentWhosePromotedSupportingTrunkViolatesActualClearance() throws Exception {
        var obstacle = new ImportedOfficialFeature("neighbour", "oks_existing",
                new ObjectMapper().createObjectNode(),
                new WKTReader().read("POLYGON ((20 5.4,70 5.4,70 15,20 15,20 5.4))"));
        var features = List.of(obstacle);
        var source = source();
        var demand = new RegressionRoutePlannerFixture.Demand("missing", "missing", new Coordinate(90, -20),
                new BigDecimal("300"), null);
        var promoted = new ArrayList<RouteEdge>();
        var checking = new OfficialRouteValidator(rules) {
            @Override
            public List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges,
                    List<ImportedOfficialFeature> fullFeatures) {
                edges.stream().filter(e -> e.getDiameter() != null && e.getDiameter() == 300)
                        .filter(e -> "root".equals(e.getUpstreamNodeId())).forEach(promoted::add);
                return super.validate(nodes, edges, fullFeatures);
            }
        };
        assertSizedValid(source, features);
        assertThat(router.lineAllowed(List.of(new Coordinate(90, -20), new Coordinate(90, 0)),
                300, router.prepare(features), Set.of(), List.of())).isTrue();

        assertThat(planner(router, checking).recoverCoverageAlternatives(List.of(source), List.of(demand),
                router.prepare(features))).isEmpty();
        assertThat(promoted).isNotEmpty();
        assertThat(connections(source)).extracting(RouteConnection::getStatus)
                .containsExactly("connected", "no_route");
    }

    @Test
    void refusesAnInvalidSupportingNetworkWithoutLosingItsOriginalDiagnostic() {
        var source = source();
        var edge = edges(source).get(0);
        edges(source).add(new RouteEdge("duplicate-parent", edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                100, edge.getCoordinates(), List.of(), BigDecimal.ONE, 50));
        assertThat(planner.recoverCoverageAlternatives(List.of(source), List.of(missing("missing", 50, 20)),
                router.prepare(List.of()))).isEmpty();
        assertThat(edges(source)).hasSize(2);
    }

    @Test
    void repeatsDeterministicallyWhenDemandAndPortfolioOrderChange() {
        var first = sourceAt(0);
        var second = sourceAt(-100);
        for (var draft : List.of(first, second)) connections(draft).add(
                new RouteConnection("another", "another", BigDecimal.ONE, "no_route", "NO_NON_CROSSING_ROUTE"));
        var demands = List.of(missing("missing", 50, 20), missing("another", 80, 40));
        var a = planner.recoverCoverageAlternatives(List.of(first, second), demands, router.prepare(List.of()));
        var reversed = new ArrayList<>(demands);
        Collections.reverse(reversed);
        var b = planner.recoverCoverageAlternatives(List.of(second, first), reversed, router.prepare(List.of()));

        assertThat(a).hasSize(2);
        assertThat(a.stream().map(this::signature).collect(Collectors.toList()))
                .isEqualTo(b.stream().map(this::signature).collect(Collectors.toList()));
        for (var draft : a) {
            assertThat(connections(draft)).extracting(RouteConnection::getDemandId).doesNotHaveDuplicates();
            assertSizedValid(draft, List.of());
        }
    }

    @Test
    void countsFailedDemandsAgainstOneTwelveAttemptBudget() throws Exception {
        var starts = new LinkedHashSet<String>();
        var observed = observingRouter(starts, null, false);
        var source = source();
        connections(source).removeIf(c -> "no_route".equals(c.getStatus()));
        var demands = new ArrayList<RegressionRoutePlannerFixture.Demand>();
        for (int i = 15; i >= 0; i--) {
            String id = String.format(java.util.Locale.ROOT, "missing-%02d", i);
            connections(source).add(new RouteConnection(id, id, BigDecimal.ONE, "no_route", "NO_NON_CROSSING_ROUTE"));
            demands.add(missing(id, 10 + i * 4, 20));
        }
        var park = new ImportedOfficialFeature("blocked", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "park"),
                new WKTReader().read("POLYGON ((0 10,100 10,100 30,0 30,0 10))"));

        assertThat(planner(observed, validator).recoverCoverageAlternatives(List.of(source), demands,
                observed.prepare(List.of(park)))).isEmpty();
        assertThat(starts).hasSize(12).contains("10.0,20.0", "54.0,20.0").doesNotContain("58.0,20.0");
        assertThat(connections(source)).hasSize(17);
    }

    @Test
    void boundsPortfolioRecoveryToFourDistinctSupportingTrees() {
        var sources = new ArrayList<RegressionRoutePlannerFixture.VariantDraft>();
        for (int i = 0; i < 6; i++) sources.add(sourceAt(-100 * i));
        var recovered = planner.recoverCoverageAlternatives(sources, List.of(missing("missing", 50, 20)),
                router.prepare(List.of()));
        assertThat(recovered).hasSize(4);
        recovered.forEach(draft -> assertSizedValid(draft, List.of()));
        sources.forEach(draft -> assertThat(connections(draft)).hasSize(2));
    }

    @Test
    void limitsExistingChamberEnumerationWithinOneAttempt() {
        var ns = new ArrayList<RouteNode>();
        ns.add(new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(0, 0), true, true, 2, null));
        Set<String> chamberCoordinates = new LinkedHashSet<>();
        for (int i = 1; i <= 7; i++) {
            ns.add(new RouteNode("chamber-" + i, "new_branch_chamber", new RouteCoordinate(i * 20, 0), true, false, 0, null));
            chamberCoordinates.add((i * 20) + ".0,0.0");
        }
        ns.add(new RouteNode("demand:known", "demand_connection", new RouteCoordinate(200, 0), false, false, 0, "known"));
        var es = new ArrayList<RouteEdge>();
        for (int i = 1; i < ns.size(); i++) {
            var a = ns.get(i - 1); var b = ns.get(i);
            es.add(new RouteEdge("edge-" + i, a.getId(), b.getId(), b.getCoordinate().toCoordinate().distance(a.getCoordinate().toCoordinate()),
                    List.of(a.getCoordinate(), b.getCoordinate()), List.of(), BigDecimal.ONE, 50));
        }
        var source = new RegressionRoutePlannerFixture.VariantDraft(ns, es, connections(source()));
        var ends = new LinkedHashSet<String>();
        var observed = observingRouter(null, ends, false);

        var recovered = planner(observed, validator).recoverCoverageAlternatives(List.of(source),
                List.of(missing("missing", 90, 30)), observed.prepare(List.of()));
        assertThat(recovered).hasSize(1);
        ends.retainAll(chamberCoordinates);
        assertThat(ends).hasSize(4);
        assertSizedValid(recovered.get(0), List.of());
    }

    @Test
    void cancellationBeforeAnyWorkIsPropagatedEvenForEmptyPortfolio() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> planner.recoverCoverageAlternatives(List.of(), List.of(), router.prepare(List.of())))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationDuringAttachmentCannotPublishOrMutatePartialRecovery() {
        var source = source();
        var observed = observingRouter(null, null, true);
        try {
            assertThatThrownBy(() -> planner(observed, validator).recoverCoverageAlternatives(List.of(source),
                    List.of(missing("missing", 50, 20)), observed.prepare(List.of())))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(edges(source)).hasSize(1);
        assertThat(connections(source)).extracting(RouteConnection::getStatus)
                .containsExactly("connected", "no_route");
    }

    private OfficialObstacleRouter observingRouter(Set<String> starts, Set<String> ends, boolean interrupt) {
        return new OfficialObstacleRouter(rules) {
            @Override
            RoutePath find(Coordinate start, Coordinate end, int diameter, OfficialRoutingEnvironment environment,
                    Set<String> exemptions, RoutePreference preference, List<LineString> avoidance, RouteTraversal traversal) {
                if (starts != null) starts.add(start.x + "," + start.y);
                if (ends != null) ends.add(end.x + "," + end.y);
                RoutePath result = super.find(start, end, diameter, environment, exemptions, preference, avoidance, traversal);
                if (interrupt) Thread.currentThread().interrupt();
                return result;
            }
        };
    }

    private RegressionRoutePlannerFixture planner(OfficialObstacleRouter actualRouter, OfficialRouteValidator actualValidator) {
        return new RegressionRoutePlannerFixture(actualValidator, actualRouter, pipes, new OfficialNetworkSizer(pipes),
                new OfficialExistingNetworkReconstructor(pipes), new OfficialVariantEconomicsCalculator(pipes, new OfficialEconomics()),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
                        new OfficialDepthOptimizer(pipes, new OfficialEconomics()), new OfficialDepthProfileValidator(pipes)));
    }

    private String signature(RegressionRoutePlannerFixture.VariantDraft draft) {
        return edges(draft).stream().map(e -> e.getId() + ":" + e.getCoordinates().stream()
                .map(c -> c.getXM() + "," + c.getYM()).collect(Collectors.joining(";")))
                .sorted().collect(Collectors.joining("|")) + connections(draft).stream()
                .map(c -> c.getDemandId() + ":" + c.getStatus()).sorted().collect(Collectors.joining("|"));
    }

    private RegressionRoutePlannerFixture.VariantDraft source() {
        return sourceAt(0);
    }

    private RegressionRoutePlannerFixture.VariantDraft sourceAt(double y) {
        RouteNode root = new RouteNode("root", "existing_chamber_tie_in",
                new RouteCoordinate(0, y), true, true, 2, null);
        RouteNode known = new RouteNode("demand:known", "demand_connection",
                new RouteCoordinate(100, y), false, false, 0, "known");
        RouteEdge trunk = new RouteEdge("support", root.getId(), known.getId(), 100,
                List.of(root.getCoordinate(), known.getCoordinate()), List.of(), BigDecimal.ONE, 50);
        return new RegressionRoutePlannerFixture.VariantDraft(List.of(root, known), List.of(trunk),
                List.of(new RouteConnection("known", "known", BigDecimal.ONE, "connected", null),
                        new RouteConnection("missing", "missing", BigDecimal.ONE, "no_route", "NO_NON_CROSSING_ROUTE")));
    }

    private RegressionRoutePlannerFixture.Demand missing(String id, double x, double y) {
        return new RegressionRoutePlannerFixture.Demand(id, id, new Coordinate(x, y), BigDecimal.ONE, null);
    }

    @SuppressWarnings("unchecked")
    private List<RouteConnection> connections(RegressionRoutePlannerFixture.VariantDraft draft) {
        return (List<RouteConnection>) ReflectionTestUtils.getField(draft, "connections");
    }

    @SuppressWarnings("unchecked")
    private List<RouteEdge> edges(RegressionRoutePlannerFixture.VariantDraft draft) {
        return (List<RouteEdge>) ReflectionTestUtils.getField(draft, "edges");
    }

    @SuppressWarnings("unchecked")
    private List<RouteEdge> assertSizedValid(RegressionRoutePlannerFixture.VariantDraft draft, List<ImportedOfficialFeature> features) {
        List<RouteEdge> edges = edges(draft);
        Map<String, BigDecimal> flows = connections(draft).stream().filter(c -> "connected".equals(c.getStatus()))
                .collect(Collectors.toMap(c -> "demand:" + c.getDemandId(), RouteConnection::getFlowTph));
        var sizing = new OfficialNetworkSizer(pipes).size(edges.stream().map(e -> new NetworkTreeEdge(
                e.getId(), e.getUpstreamNodeId(), e.getDownstreamNodeId(), e.getLengthM()))
                .collect(Collectors.toList()), flows);
        assertThat(sizing.getIssues()).isEmpty();
        List<RouteEdge> sized = edges.stream().map(e -> {
            var s = sizing.getEdges().get(e.getId());
            return new RouteEdge(e.getId(), e.getUpstreamNodeId(), e.getDownstreamNodeId(),
                    e.getLengthM().doubleValue(), e.getCoordinates(), e.getSections(), s.getFlowTph(), s.getDiameter());
        }).collect(Collectors.toList());
        Map<String, RouteNode> nodes = (Map<String, RouteNode>) ReflectionTestUtils.getField(draft, "nodes");
        assertThat(validator.validate(new ArrayList<>(nodes.values()), sized, features)).isEmpty();
        return sized;
    }
}
