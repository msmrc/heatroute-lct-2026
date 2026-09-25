package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Проверяет сохранение допустимого обхода при наличии более короткого ребра против потока. */
class CorridorDirectionalAdmissionTest {
    private final GeometryFactory geometries = new GeometryFactory();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OrthogonalCorridorNetworkBuilder builder = new OrthogonalCorridorNetworkBuilder(
            router, new OfficialPipeCatalog());

    @Test
    void oneConsumerKeepsLegalDetourInsteadOfLosingAllTreesToReverseOnlyShortcut() {
        assertDetourReturned(1, false, false, 200.01);
    }

    @Test
    void fourConsumersKeepACompleteValidDetourWithinTheKnownFeasibleLength() {
        assertDetourReturned(4, false, false, 308.0);
    }

    @Test
    void ownOksTerminalKeepsTheShorterValidDetourAndItsNormalEntry() {
        assertDetourReturned(1, true, false, 159.0);
    }

    @Test
    void rotatedRoadRetainsTheDetourInThePhysicalRootToDemandDirection() {
        assertDetourReturned(1, false, true, 200.01);
    }

    private void assertDetourReturned(int demandCount, boolean ownOks, boolean rotated, double upperLengthM) {
        Geometry road = polygon(rotated, 0, 0, 100, 0,
                100 + 6 / Math.tan(Math.toRadians(40)), 6, 0, 6, 0, 0);
        List<ImportedOfficialFeature> features = new ArrayList<>(List.of(new ImportedOfficialFeature(
                "directional-road", "restriction", mapper.createObjectNode().put("restriction_type", "road"), road)));
        List<Geometry> footprints = new ArrayList<>();
        Map<String, Coordinate> demands = new LinkedHashMap<>();
        List<OrthogonalCorridorNetworkBuilder.Terminal> terminals = new ArrayList<>();
        for (int i = 0; i < demandCount; i++) {
            String id = "consumer-" + i;
            Coordinate point = point(-20 - 10 * i, 3 + 10 * i, rotated);
            demands.put(id, point);
            terminals.add(new OrthogonalCorridorNetworkBuilder.Terminal(id, "cp:" + id, point, BigDecimal.ONE));
        }
        if (ownOks) {
            Geometry house = polygon(rotated, -24, -1, -16, -1, -16, 7, -24, 7, -24, -1);
            assertThat(house.covers(geometries.createPoint(demands.values().iterator().next()))).isTrue();
            features.add(new ImportedOfficialFeature("own-building", "oks_existing", mapper.createObjectNode(), house));
            footprints.add(house);
        }
        Coordinate origin = point(120, 3, rotated), shortcutEnd = point(-10, 3, rotated);
        RouteNode root = new RouteNode("root", "existing_chamber_tie_in", routeCoordinate(origin),
                true, true, 2, null);
        // Проверяем асимметрию настоящими правилами: вход 40° по потоку и 90° в обратном направлении.
        RoadCrossingClearance crossings = new RoadCrossingClearance();
        double clearance = rules.preparationClearanceM("road", 50).doubleValue();
        assertThat(crossings.assess(rules.line(List.of(origin, shortcutEnd)), road, clearance, 45, 3).isAllowed())
                .as("the shorter root-to-demand shortcut must be illegal").isFalse();
        assertThat(crossings.assess(rules.line(List.of(shortcutEnd, origin)), road, clearance, 45, 3).isAllowed())
                .as("the same shortcut in the opposite direction must remain legal").isTrue();

        OfficialRoutingEnvironment environment = router.prepare(features);
        List<OrthogonalCorridorNetworkBuilder.Network> candidates = builder.build(terminals, root, 2,
                footprints, environment, (id, port, diameter, avoidance) -> terminalRoute(
                        demands.get(id), port, diameter, environment, avoidance));
        OfficialRouteValidator validator = new OfficialRouteValidator(rules);
        List<OrthogonalCorridorNetworkBuilder.Network> valid = candidates.stream()
                .filter(network -> validator.validate(network.nodes(), network.edges(), features).isEmpty())
                .collect(Collectors.toList());
        assertThat(valid).as("public build must retain a physically valid detour for every consumer").isNotEmpty();
        OrthogonalCorridorNetworkBuilder.Network best = valid.stream()
                .min(Comparator.comparingDouble(this::lengthM)).orElseThrow();
        // Верхняя граница — длина реально допустимого обхода, а не требование конкретной топологии.
        assertThat(lengthM(best)).as("the known feasible detour must not be displaced by an unusable shortcut")
                .isLessThanOrEqualTo(upperLengthM);
        assertThat(validator.validate(best.nodes(), best.edges(), features)).isEmpty();
        assertCoverageAndArithmetic(best, demands);
    }

    /** Реальный поиск demand→port оценивается в обратном физическом направлении, включая свой ОКС. */
    private RoutePath terminalRoute(Coordinate start, Coordinate port, int diameter,
            OfficialRoutingEnvironment environment, List<LineString> avoidance) {
        if (start.distance(port) <= 0.01) return null;
        OfficialRouteGeometryRules.NormalEgress egress = environment.normalEgressTowards(
                diameter, start, port, RoutePlannerTuning.stable().getEngineeringEgressExtraM(), RouteTraversal.REVERSED)
                .orElse(null);
        if (egress == null) return router.find(start, port, diameter, environment, Set.of(),
                RoutePreference.ENGINEERING, avoidance, RouteTraversal.REVERSED);
        if (egress.exit().distance(port) <= 0.01) return null;
        RoutePath outside = router.findAfter(egress.start(), egress.exit(), port, diameter, environment,
                Set.of(), RoutePreference.ENGINEERING, avoidance, RouteTraversal.REVERSED);
        return outside == null ? null : router.withCheckedTerminalPrefix(egress, outside, diameter,
                environment, Set.of(), avoidance, RouteTraversal.REVERSED);
    }

    private void assertCoverageAndArithmetic(OrthogonalCorridorNetworkBuilder.Network network,
            Map<String, Coordinate> demands) {
        assertThat(network.connections()).extracting(RouteConnection::getDemandId)
                .containsExactlyInAnyOrderElementsOf(demands.keySet());
        for (RouteConnection connection : network.connections()) {
            assertThat(connection.getStatus()).isEqualTo("connected");
            assertThat(connection.getConnectionPointId()).isEqualTo("cp:" + connection.getDemandId());
            assertThat(connection.getFlowTph()).isEqualByComparingTo(BigDecimal.ONE);
        }
        Map<String, RouteNode> nodes = network.nodes().stream()
                .collect(Collectors.toMap(RouteNode::getId, node -> node));
        for (Map.Entry<String, Coordinate> demand : demands.entrySet()) {
            RouteNode node = nodes.get("demand:" + demand.getKey());
            assertThat(node).isNotNull();
            assertThat(node.getCoordinate().toCoordinate().distance(demand.getValue())).isLessThanOrEqualTo(0.001);
            assertThat(network.edges()).filteredOn(edge -> edge.getDownstreamNodeId().equals(node.getId())).hasSize(1);
        }
        BigDecimal rootFlow = network.edges().stream().filter(edge -> "root".equals(edge.getUpstreamNodeId()))
                .map(RouteEdge::getFlowTph).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(rootFlow).isEqualByComparingTo(BigDecimal.valueOf(demands.size()));
        for (RouteEdge edge : network.edges()) {
            LineString line = geometries.createLineString(edge.getCoordinates().stream()
                    .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
            assertThat(edge.getLengthM().doubleValue()).isCloseTo(line.getLength(), offset(0.002));
            assertThat(edge.getSections().stream().mapToDouble(section -> section.getLengthM().doubleValue()).sum())
                    .isCloseTo(line.getLength(), offset(0.01));
            assertThat(edge.getDiameter()).isNotNull();
            assertThat(edge.getFlowTph()).isPositive();
        }
    }

    private double lengthM(OrthogonalCorridorNetworkBuilder.Network network) {
        return network.edges().stream().mapToDouble(edge -> edge.getLengthM().doubleValue()).sum();
    }

    private Geometry polygon(boolean rotated, double... xy) {
        Coordinate[] points = new Coordinate[xy.length / 2];
        for (int i = 0; i < points.length; i++) points[i] = point(xy[i * 2], xy[i * 2 + 1], rotated);
        return geometries.createPolygon(points);
    }

    private Coordinate point(double x, double y, boolean rotated) {
        return new Coordinate(500000 + (rotated ? -y : x), 6170000 + (rotated ? x : y));
    }

    private RouteCoordinate routeCoordinate(Coordinate point) { return new RouteCoordinate(point.x, point.y); }
}
