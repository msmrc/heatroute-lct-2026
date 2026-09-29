package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

class AdjacentChamberRepairTest {
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));

    @Test
    void partialRepairPreservesOnlyTheOriginalRayAtAnInvalidNonRootNeighbour() {
        RouteNode outer = node("outer", 0, 0, true, false);
        RouteEdge source = edge("link", "outer", "junction", 1, 0, 0, 20, 0);
        List<Coordinate> occupied = List.of(new Coordinate(0, 10), new Coordinate(-10, -10));
        var environment = router.prepare(List.of());
        assertThat(CorridorLinkApproaches.build(source, outer, new Coordinate(20, 0), 0,
                router, environment, occupied)).isEmpty();
        var partial = CorridorLinkApproaches.build(source, outer, new Coordinate(20, 10), 0,
                router, environment, occupied, Set.of(), true);
        assertThat(partial).isNotEmpty();
        partial.forEach(path -> {
            var summary = ExpertChamberGeometryRules.summarize(path.coordinates().stream()
                    .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()));
            assertThat(summary.getFirstDx()).isPositive();
            assertThat(summary.getFirstDy()).isZero();
            assertThat(summary.getFirstBendDistanceM()).isGreaterThanOrEqualTo(2);
        });
        RouteNode root = node("outer", 0, 0, true, true);
        assertThat(CorridorLinkApproaches.build(source, root, new Coordinate(20, 10), 0,
                router, environment, occupied, Set.of(), true)).isEmpty();
    }

    @Test
    void preservingAShortOuterApproachCannotShortenOrMoveItsFirstBend() {
        RouteNode outer = node("outer", 0, 0, true, false);
        RouteEdge source = edge("link", "outer", "junction", 1, 0, 0, 1, 0, 1, 20);
        var partial = CorridorLinkApproaches.build(source, outer, new Coordinate(1, 25), 0,
                router, router.prepare(List.of()), List.of(new Coordinate(0, 10), new Coordinate(-10, -10)), Set.of(), true);
        assertThat(partial).isNotEmpty();
        partial.forEach(path -> {
            var summary = ExpertChamberGeometryRules.summarize(path.coordinates().stream()
                    .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()));
            assertThat(summary.getFirstDx()).isEqualTo(1);
            assertThat(summary.getFirstDy()).isZero();
            assertThat(summary.getFirstBendDistanceM()).isEqualTo(1);
        });
    }

    @Test
    void twoAdjacentInvalidChambersCanBeRepairedSequentiallyWithoutLosingAnyConsumer() {
        var planner = new OfficialDatasetRoutingTest().planner();
        var environment = router.prepare(List.of());
        var parameters = new OfficialRunParameters(null, null, false).validated();
        List<RouteNode> nodes = List.of(node("root", -100, 0, true, true), node("a", 0, 0, true, false),
                node("b", 20, 0, true, false), node("demand:one", 10, 10, false, false),
                node("demand:two", 30, 10, false, false), node("demand:three", 30, -5, false, false));
        List<RouteEdge> edges = List.of(edge("root-a", "root", "a", 3, -100, 0, 0, 0),
                edge("a-b", "a", "b", 2, 0, 0, 20, 0), edge("a-one", "a", "demand:one", 1, 0, 0, 10, 10),
                edge("b-two", "b", "demand:two", 1, 20, 0, 30, 10), edge("b-three", "b", "demand:three", 1, 20, 0, 30, -5));
        List<RouteConnection> connections = List.of(new RouteConnection("one", "one", BigDecimal.ONE, "connected", null),
                new RouteConnection("two", "two", BigDecimal.ONE, "connected", null), new RouteConnection("three", "three", BigDecimal.ONE, "connected", null));
        var seed = planner.withEngineeringAssessment(planner.finish("seed", "engineering",
                new RegressionRoutePlannerFixture.VariantDraft(nodes, edges, connections), List.of(), parameters, false, environment));
        assertThat(seed.getValidationIssues()).extracting(RouteValidationIssue::getCode).containsOnly("EXPERT_CHAMBER_OBLIQUE_ENTRY");
        assertThat(seed.getValidationIssues()).extracting(RouteValidationIssue::getSubjectId).containsExactlyInAnyOrder("a", "b");
        List<RegressionRoutePlannerFixture.Demand> demands = List.of(
                new RegressionRoutePlannerFixture.Demand("one", "one", new Coordinate(10, 10), BigDecimal.ONE, null),
                new RegressionRoutePlannerFixture.Demand("two", "two", new Coordinate(30, 10), BigDecimal.ONE, null),
                new RegressionRoutePlannerFixture.Demand("three", "three", new Coordinate(30, -5), BigDecimal.ONE, null));
        var repaired = planner.repairMandatoryChambers(List.of(seed), demands, List.of(), parameters, false, environment);
        assertThat(repaired).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.isValid()).isTrue();
            assertThat(variant.getEngineeringIssues()).isEmpty();
            assertThat(variant.getConnectedDemandCount()).isEqualTo(3);
            assertThat(variant.getConnections()).usingRecursiveFieldByFieldElementComparator()
                    .containsExactlyInAnyOrderElementsOf(connections);
            assertThat(variant.getNodes().stream().filter(RouteNode::isRoot).findFirst().orElseThrow())
                    .usingRecursiveComparison().isEqualTo(nodes.get(0));
            assertThat(new ExpertChamberRouteValidator().validate(variant.getNodes(), variant.getEdges())).isEmpty();
            assertThat(ExpertRouteBendRules.validate(variant.getNodes(), variant.getEdges())).isEmpty();
        });
    }

    private RouteNode node(String id, double x, double y, boolean chamber, boolean root) {
        return new RouteNode(id, root ? "existing_chamber_tie_in" : chamber ? "new_branch_chamber" : "demand_connection",
                new RouteCoordinate(x, y), chamber, root, root ? 1 : 0, chamber ? null : id.substring("demand:".length()));
    }
    private RouteEdge edge(String id, String from, String to, int flow, double... xy) {
        List<RouteCoordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new RouteCoordinate(xy[i], xy[i + 1]));
        double length = new GeometryFactory().createLineString(points.stream().map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new)).getLength();
        return new RouteEdge(id, from, to, length, points, List.of(new RouteSection("base", null, null, points, length, null)), BigDecimal.valueOf(flow), 50);
    }
}
