package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.depth.DepthCrossingDecision;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class DistinctRouteAlternativesTest {
    private final DistinctRouteAlternatives selector = new DistinctRouteAlternatives();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void threeRoleCopiesBecomeOneOriginalBestScoreObject() throws Exception {
        RouteVariant balanced = single("balanced", points(0, 0, 40, 0), 3_000_000);
        RouteVariant shortest = copy("shortest", balanced, 2_000_000);
        RouteVariant cheapest = copy("cheapest", balanced, 1_000_000);

        assertThat(selector.select(List.of(balanced, shortest, cheapest), features()))
                .containsExactly(cheapest);
        assertThat(cheapest.getRank()).isNull();
        assertThat(cheapest.getEdges()).containsExactlyElementsOf(balanced.getEdges());
    }

    @Test
    void renamedReorderedAndTechnicallySplitNetworkHasTheSameFamily() throws Exception {
        RouteVariant original = single("original", points(0, 0, 40, 0), 2_000_000);
        RouteNode root = node("renamed-root", "new_tie_in_chamber", 0, 0, true, "source");
        RouteNode technical = node("technical", "technical_node", 17, 0, false, null);
        RouteNode terminal = node("renamed-terminal", "demand_connection", 40, 0, false, null);
        RouteVariant split = variant("split", List.of(terminal, technical, root),
                List.of(edge("two", technical, terminal, points(17, 0, 20, 0, 40, 0)),
                        edge("one", root, technical, points(0, 0, 5, 0, 17, 0))), connections("A"), 1_000_000);

        assertThat(selector.select(List.of(original, split), features())).containsExactly(split);
    }

    @Test
    void smallOffsetAndMovedTieInOnSameSourceDoNotCreateAnAlternative() throws Exception {
        RouteVariant original = single("original", points(0, 0, 40, 0), 2_000_000);
        RouteVariant offset = single("offset", points(0, .01, 10, .01, 30, 0, 40, 0), 1_000_000);

        assertThat(selector.select(List.of(offset, original), features())).containsExactly(offset);
    }

    @Test
    void officialScoreWinsOverRoleOrderAndLowerCostAlone() throws Exception {
        RouteVariant shortRoute = single("shortest", points(0, 0, 40, 0), 2_000_000);
        RouteVariant cheapDetour = single("cheapest", points(0, 0, 0, 20, 40, 20, 40, 0), 1_000_000);
        assertThat(shortRoute.getEconomics().getScore()).isLessThan(cheapDetour.getEconomics().getScore());
        assertThat(selector.select(List.of(cheapDetour, shortRoute), features())).containsExactly(shortRoute);
    }

    @Test
    void reversingEdgeStorageDoesNotReverseTheRootedSemanticPath() throws Exception {
        RouteVariant original = single("original", points(0, 0, 40, 0), 2_000_000);
        RouteNode root = original.getNodes().get(0), demand = original.getNodes().get(1);
        RouteVariant reversed = variant("reversed", List.of(demand, root),
                List.of(edge("reversed-edge", demand, root, points(40, 0, 20, 0, 0, 0))), connections("A"), 1_000_000);
        assertThat(selector.select(List.of(original, reversed), features())).containsExactly(reversed);
    }

    @Test
    void coincidentIndependentDemandsUseTheirSourcePointTargetsAfterRenaming() throws Exception {
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("point-B", "oks_connection_point", "POINT(40 0)"));
        RouteNode root = node("r", "new_tie_in_chamber", 0, 0, true, "source");
        RouteNode a = node("renamed-a", "demand_connection", 40, 0, false, "point-A");
        RouteNode b = node("renamed-b", "demand_connection", 40, 0, false, "point-B");
        RouteVariant original = variant("original", List.of(root, a, b),
                List.of(edge("a", root, a), edge("b", root, b)), connections("A", "B"), 1_000_000);
        RouteVariant duplicate = copy("duplicate", original, 2_000_000);
        assertThat(selector.select(List.of(duplicate, original), source)).containsExactly(original);
    }

    @Test
    void namedSourceAliasesWithTheSamePhysicalGeometryDoNotCreateAnAlternative() throws Exception {
        RouteVariant first = single("first", points(0, 0, 40, 0), 1_000_000);
        RouteVariant alias = rootTarget(single("alias", points(0, 0, 40, 0), 2_000_000), "duplicate-source");
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("duplicate-source", "heat_network", "LINESTRING(0 100,0 0,0 -100)"));

        assertThat(selector.select(List.of(alias, first), source)).containsExactly(first);
    }

    @Test
    void differentPhysicalTieInTargetRemainsAnAlternative() throws Exception {
        RouteVariant first = single("first", points(0, 0, 40, 0), 1_000_000);
        RouteVariant second = rootTarget(single("second", points(0, 10, 10, 10, 40, 0), 2_000_000), "other-source");
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("other-source", "heat_network", "LINESTRING(-10 10,10 10)"));

        assertThat(selector.select(List.of(second, first), source)).containsExactly(first, second);
    }

    @Test
    void differentDemandGroupingAtBranchingChambersRemainsDistinct() throws Exception {
        RouteVariant abThenC = grouped("ab", "A", "B", "C", 1_000_000);
        RouteVariant acThenB = grouped("ac", "A", "C", "B", 2_000_000);
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("point-B", "oks_connection_point", "POINT(30 10)"));
        source.add(feature("point-C", "oks_connection_point", "POINT(30 -10)"));

        assertThat(selector.select(List.of(acThenB, abThenC), source)).containsExactly(abThenC, acThenB);
    }

    @Test
    void technicalSplitOfASpecialPassageDoesNotDuplicateTheRealCrossing() throws Exception {
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("utility", "restriction", "LINESTRING(20 -20,20 20)"));
        RouteVariant whole = withPassage(single("whole", points(0, 0, 40, 0), 2_000_000), "utility", "above", 20);
        RouteNode root = node("r", "new_tie_in_chamber", 0, 0, true, "source");
        RouteNode technical = node("t", "technical_node", 15, 0, false, null);
        RouteNode demand = node("a", "demand_connection", 40, 0, false, null);
        RouteEdge tail = passageEdge(edge("tail", technical, demand, points(15, 0, 40, 0)), "utility", "above", 5);
        RouteEdge head = edge("head", root, technical, points(0, 0, 15, 0));
        RouteVariant split = variant("split", List.of(root, technical, demand), List.of(head, tail), connections("A"), 1_000_000);

        assertThat(selector.select(List.of(whole, split), source)).containsExactly(split);
    }

    @Test
    void aboveAndBelowTheSameUtilityAreDifferentDecisions() throws Exception {
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("utility", "restriction", "LINESTRING(20 -20,20 20)"));
        RouteVariant above = withPassage(single("above", points(0, 0, 40, 0), 1_000_000), "utility", "above", 20);
        RouteVariant below = withPassage(single("below", points(0, 0, 40, 0), 2_000_000), "utility", "below", 20);

        assertThat(selector.select(List.of(below, above), source)).containsExactly(above, below);
    }

    @Test
    void sourceCrossingIdentifiersMayContainHashAndAreNotBlindlyTrimmed() throws Exception {
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("utility#1", "restriction", "LINESTRING(20 -20,20 20)"));
        RouteVariant first = withPassage(single("first", points(0, 0, 40, 0), 1_000_000), "utility#1", "above", 20);
        RouteVariant second = withPassage(single("second", points(0, 0, 40, 0), 2_000_000), "utility#1", "below", 20);

        assertThat(selector.select(List.of(first, second), source)).containsExactly(first, second);
    }

    @Test
    void differentSpecialCrossingObjectsRemainDistinct() throws Exception {
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("lower-road", "restriction", "LINESTRING(20 -5,20 5)"));
        source.add(feature("upper-road", "restriction", "LINESTRING(20 15,20 25)"));
        RouteVariant lower = withPassage(single("lower", points(0, 0, 40, 0), 1_000_000), "lower-road", null, 20);
        RouteVariant upper = withPassage(single("upper", points(0, 0, 0, 20, 40, 20, 40, 0), 2_000_000), "upper-road", null, 40);

        assertThat(selector.select(List.of(upper, lower), source)).containsExactly(lower, upper);
    }

    @Test
    void oppositeSidesOfAnObstacleAreProvenDifferentWithoutDistanceThreshold() throws Exception {
        assertOppositeSidesDistinct("POLYGON((15 -5,25 -5,25 5,15 5,15 -5))");
    }

    @Test
    void windowedFinalSelectionLoadsTheObstacleBetweenRoutesLikeInMemorySelection() throws Exception {
        List<ImportedOfficialFeature> core = features();
        ImportedOfficialFeature obstacle = feature("between", "restriction", "POLYGON((15 -5,25 -5,25 5,15 5,15 -5))");
        List<ImportedOfficialFeature> all = new ArrayList<>(core);
        all.add(obstacle);
        RouteVariant upper = single("upper", points(0, 0, 0, 12, 40, 12, 40, 0), 1_000_000);
        RouteVariant lower = single("lower", points(0, 0, 0, -12, 40, -12, 40, 0), 2_000_000);
        List<RouteVariant> variants = List.of(lower, upper);
        OfficialObstacleRouter router = new OfficialObstacleRouter(new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));
        RegressionRoutePlannerFixture planner = new OfficialDatasetRoutingTest().planner();

        // Ни одна линия не касается полигона. Только общее окно включает препятствие между ними.
        assertThat(selector.select(variants, core)).containsExactly(upper);
        List<RouteVariant> windowed = planner.distinctFinalVariants(variants, core,
                router.prepare(core, new InMemoryRoutingFeatureSource(List.of(obstacle))));
        List<RouteVariant> inMemory = planner.distinctFinalVariants(variants, all, router.prepare(all));
        assertThat(windowed).containsExactly(upper, lower);
        assertThat(inMemory).containsExactlyElementsOf(windowed);
    }

    @Test
    void concaveObstacleUsesAnInteriorPointRatherThanItsCentroid() throws Exception {
        String wkt = "POLYGON((10 -8,30 -8,30 -4,14 -4,14 4,30 4,30 8,10 8,10 -8))";
        Geometry concave = new WKTReader().read(wkt);
        assertThat(concave.contains(concave.getCentroid())).isFalse();
        assertOppositeSidesDistinct(wkt);
    }

    @Test
    void secondMultiPolygonComponentCanCertifyTheDifferentCorridor() throws Exception {
        assertOppositeSidesDistinct("MULTIPOLYGON(((100 100,110 100,110 110,100 110,100 100)),"
                + "((15 -5,25 -5,25 5,15 5,15 -5)))");
    }

    @Test
    void freeHoleIsNotFilledInWhenComparingTwoShiftedRoutes() throws Exception {
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("courtyard", "restriction", "POLYGON((-100 -100,100 -100,100 100,-100 100,-100 -100),"
                + "(-50 -50,-50 50,50 50,50 -50,-50 -50))"));
        RouteVariant first = single("first", points(0, 0, 40, 0), 1_000_000);
        RouteVariant shifted = single("shifted", points(0, 0, 0, 10, 40, 10, 40, 0), 2_000_000);

        assertThat(selector.select(List.of(first, shifted), source)).containsExactly(first);
    }

    @Test
    void aClosingConnectorThroughTheObstacleDoesNotGiveAFalseCertificate() throws Exception {
        List<ImportedOfficialFeature> source = List.of(
                feature("source", "heat_network", "LINESTRING(0 0,0 20,40 20,40 0)"),
                feature("point-A", "oks_connection_point", "POINT(20 30)"),
                feature("obstacle", "restriction", "POLYGON((15 -5,25 -5,25 5,15 5,15 -5))"));
        RouteVariant left = single("left", points(0, 0, 0, 30, 20, 30), 1_000_000);
        RouteVariant right = single("right", points(40, 0, 40, 30, 20, 30), 2_000_000);

        // Не доказали отличие: консервативно оставляем min S, а не объявляем полную эквивалентность.
        assertThat(selector.select(List.of(left, right), source)).containsExactly(left);
    }

    @Test
    void allNoRouteResultRetainsOneBestPenaltyAndItsDiagnostics() throws Exception {
        RouteConnection unavailable = new RouteConnection("A", "point-A", BigDecimal.ONE, "no_route", "Blocked by social_area");
        RouteVariant first = variant("first", List.of(), List.of(), List.of(unavailable), 100_500_000);
        RouteVariant duplicate = copy("second", first, 100_500_000);

        List<RouteVariant> result = selector.select(List.of(duplicate, first), features());

        assertThat(result).containsExactly(first);
        assertThat(result.get(0).getConnections()).containsExactly(unavailable);
        assertThat(result.get(0).getNoRouteDemandCount()).isOne();
    }

    @Test
    void atMostThreeAreReturnedInOfficialScoreOrderRegardlessOfRoleNames() throws Exception {
        List<RouteVariant> variants = new ArrayList<>();
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        for (int i = 0; i < 4; i++) {
            String target = "source-" + i;
            source.add(feature(target, "heat_network", "LINESTRING(" + i + " -100," + i + " 100)"));
            variants.add(rootTarget(single("role-" + i, points(i, 0, 40, 0), (4 - i) * 1_000_000), target));
        }
        assertThat(selector.select(variants, source)).containsExactly(variants.get(3), variants.get(2), variants.get(1));
        Collections.reverse(variants);
        assertThat(selector.select(variants, source)).extracting(RouteVariant::getId).containsExactly("role-3", "role-2", "role-1");
    }

    @Test
    void invalidRoleCannotDisplaceTheValidBestRepresentative() throws Exception {
        RouteVariant valid = single("valid", points(0, 0, 40, 0), 1_000_000);
        RouteVariant invalid = copy("invalid", valid, 1).withEngineeringIssues(
                List.of(new RouteValidationIssue("BAD_GEOMETRY", "edge", "not admitted")));
        assertThat(selector.select(List.of(invalid, valid), features())).containsExactly(valid);
    }

    @Test
    void cancellationDuringFeatureScanIsPropagatedAndInterruptIsPreserved() throws Exception {
        ImportedOfficialFeature feature = features().get(0);
        List<ImportedOfficialFeature> interrupted = new AbstractList<ImportedOfficialFeature>() {
            @Override public ImportedOfficialFeature get(int index) {
                if (index == 20) Thread.currentThread().interrupt();
                return feature;
            }
            @Override public int size() { return 100; }
        };
        try {
            assertThatThrownBy(() -> selector.select(List.of(single("valid", points(0, 0, 40, 0), 1_000_000)), interrupted))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private void assertOppositeSidesDistinct(String wkt) throws Exception {
        List<ImportedOfficialFeature> source = new ArrayList<>(features());
        source.add(feature("obstacle", "restriction", wkt));
        RouteVariant upper = single("upper", points(0, 0, 0, 12, 40, 12, 40, 0), 1_000_000);
        RouteVariant lower = single("lower", points(0, 0, 0, -12, 40, -12, 40, 0), 2_000_000);
        assertThat(selector.select(List.of(lower, upper), source)).containsExactly(upper, lower);
    }

    private RouteVariant grouped(String id, String first, String second, String last, double cost) {
        RouteNode root = node("root", "new_tie_in_chamber", 0, 0, true, "source");
        RouteNode outer = node("outer", "new_branch_chamber", 10, 0, false, null);
        RouteNode inner = node("inner", "new_branch_chamber", 20, 0, false, null);
        RouteNode a = demand(first), b = demand(second), c = demand(last);
        List<RouteEdge> edges = List.of(edge("trunk", root, outer), edge("shared", outer, inner),
                edge("first", inner, a), edge("second", inner, b), edge("last", outer, c));
        return variant(id, List.of(root, outer, inner, a, b, c), edges, connections("A", "B", "C"), cost);
    }

    private RouteNode demand(String id) {
        return node("demand:" + id, "demand_connection", "A".equals(id) ? 40 : 30,
                "B".equals(id) ? 10 : "C".equals(id) ? -10 : 0, false, null);
    }

    private RouteVariant rootTarget(RouteVariant variant, String target) {
        List<RouteNode> nodes = new ArrayList<>();
        for (RouteNode node : variant.getNodes()) nodes.add(node.isRoot()
                ? new RouteNode(node.getId(), node.getNodeType(), node.getCoordinate(), true, true, 2, target) : node);
        return new RouteVariant(variant.getId(), variant.getStrategy(), nodes, variant.getEdges(), variant.getConnections(),
                variant.getTotalLengthM(), List.of(), List.of(), null, variant.getEconomics(), null);
    }

    private RouteVariant single(String id, List<RouteCoordinate> points, double cost) {
        RouteCoordinate first = points.get(0), last = points.get(points.size() - 1);
        RouteNode root = node("root", "new_tie_in_chamber", first.getXM().doubleValue(), first.getYM().doubleValue(), true, "source");
        RouteNode demand = node("demand:A", "demand_connection", last.getXM().doubleValue(), last.getYM().doubleValue(), false, null);
        return variant(id, List.of(root, demand), List.of(edge("edge", root, demand, points)), connections("A"), cost);
    }

    private RouteVariant withPassage(RouteVariant variant, String id, String mode, double station) {
        return variant(variant.getId(), variant.getNodes(), List.of(passageEdge(variant.getEdges().get(0), id, mode, station)),
                variant.getConnections(), variant.getEconomics().getCalculatedCost().doubleValue());
    }

    private RouteEdge passageEdge(RouteEdge edge, String id, String mode, double station) {
        double length = edge.getLengthM().doubleValue();
        double depth = "below".equals(mode) ? 4 : 1.7;
        DepthProfileResult profile = mode == null ? null : new DepthProfileResult(true,
                List.of(new DepthProfilePoint(BigDecimal.ZERO, BigDecimal.valueOf(depth)),
                        new DepthProfilePoint(BigDecimal.valueOf(length), BigDecimal.valueOf(depth))),
                List.of(new DepthCrossingDecision(id, "gas_pipeline", mode, BigDecimal.valueOf(depth), BigDecimal.ZERO,
                        BigDecimal.valueOf(station - 2), BigDecimal.valueOf(station + 2), BigDecimal.valueOf(length), BigDecimal.ONE, new BigDecimal("0.2"))),
                List.of(), BigDecimal.valueOf(length), BigDecimal.valueOf(length));
        return new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(), length, edge.getCoordinates(),
                List.of(new RouteSection("special", mode == null ? "road" : "gas_pipeline", id, edge.getCoordinates(), length, 90.0)),
                BigDecimal.ONE, 50, profile);
    }

    private RouteVariant copy(String id, RouteVariant source, double cost) {
        return variant(id, source.getNodes(), source.getEdges(), source.getConnections(), cost);
    }

    private RouteVariant variant(String id, List<RouteNode> nodes, List<RouteEdge> edges,
            List<RouteConnection> connections, double costRub) {
        BigDecimal length = edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cost = BigDecimal.valueOf(costRub), zero = BigDecimal.ZERO;
        VariantEconomics economics = new VariantEconomics(true, cost, zero, zero, zero, zero, zero, cost,
                length, zero, length, new OfficialEconomics().score(cost, length), List.of());
        return new RouteVariant(id, id, nodes, edges, connections, length, List.of(), List.of(), null, economics, null);
    }

    private RouteNode node(String id, String type, double x, double y, boolean root, String target) {
        return new RouteNode(id, type, new RouteCoordinate(x, y), type.contains("chamber"), root, root ? 2 : 0, target);
    }

    private RouteEdge edge(String id, RouteNode start, RouteNode end) {
        return edge(id, start, end, List.of(start.getCoordinate(), end.getCoordinate()));
    }

    private RouteEdge edge(String id, RouteNode start, RouteNode end, List<RouteCoordinate> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) length += points.get(i - 1).toCoordinate().distance(points.get(i).toCoordinate());
        return new RouteEdge(id, start.getId(), end.getId(), length, points,
                List.of(new RouteSection("base", null, null, points, length, null)), BigDecimal.ONE, 50);
    }

    private List<RouteConnection> connections(String... ids) {
        List<RouteConnection> connections = new ArrayList<>();
        for (String id : ids) connections.add(new RouteConnection(id, "point-" + id, BigDecimal.ONE, "connected", null));
        return connections;
    }

    private List<RouteCoordinate> points(double... xy) {
        List<RouteCoordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new RouteCoordinate(xy[i], xy[i + 1]));
        return points;
    }

    private List<ImportedOfficialFeature> features() throws Exception {
        return List.of(feature("source", "heat_network", "LINESTRING(0 -100,0 100)"),
                feature("point-A", "oks_connection_point", "POINT(40 0)"));
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, type, mapper.createObjectNode(), new WKTReader().read(wkt));
    }
}
