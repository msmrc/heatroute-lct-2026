package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.util.AffineTransformation;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.SharedSpineCandidateBuilder.SpineCandidate;
import ru.lct.heatroute.domain.routing.SharedSpineNetworkBuilder.Network;
import ru.lct.heatroute.domain.sizing.NetworkSizingResult;
import ru.lct.heatroute.domain.sizing.NetworkTreeEdge;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.sizing.SizedNetworkEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class SharedSpineNetworkBuilderTest {
    private final GeometryFactory geometries = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final SharedSpineNetworkBuilder builder = new SharedSpineNetworkBuilder(router, pipes);
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);

    @Test
    void buildsRootOrientedSixTerminalTreeWithAggregatedFlowsAndRealSizing() {
        Fixture fixture = street(List.of(), 3);
        AtomicInteger rootCalls = new AtomicInteger();
        Network network = builder.build(fixture.candidate, fixture.root, fixture.terminals,
                fixture.environment, terminalRouter(fixture), (junction, flow, diameter, avoidance) -> {
                    rootCalls.incrementAndGet();
                    assertThat(flow).isEqualByComparingTo("21");
                    assertThat(diameter).isEqualTo(100);
                    return rootRouter(fixture).route(junction, flow, diameter, avoidance);
                });

        assertThat(rootCalls).hasValue(1);
        assertCompleteTree(network, fixture);
        assertThat(network.edges()).filteredOn(edge -> edge.getUpstreamNodeId().equals(fixture.root.getId()))
                .singleElement().satisfies(edge -> {
                    assertThat(edge.getFlowTph()).isEqualByComparingTo("21");
                    assertThat(edge.getDiameter()).isEqualTo(100);
                });
        // Корень расположен внутри магистрали: левое плечо несёт 1+2, правое — 3+4+5+6 т/ч.
        Map<String, RouteNode> nodes = nodesById(network);
        String rootJunction = network.edges().stream()
                .filter(edge -> edge.getUpstreamNodeId().equals(fixture.root.getId()))
                .findFirst().orElseThrow().getDownstreamNodeId();
        assertThat(network.edges()).filteredOn(edge -> edge.getUpstreamNodeId().equals(rootJunction))
                .hasSize(2).allSatisfy(edge -> {
                    double downstreamX = nodes.get(edge.getDownstreamNodeId()).getCoordinate().toCoordinate().x;
                    assertThat(edge.getFlowTph()).isEqualByComparingTo(downstreamX < -20 ? "3" : "18");
                });
        assertSizedAndValid(network, fixture);
    }

    @Test
    void routesSpineAroundForbiddenObstacleAndValidatesEveryPolyline() {
        ImportedOfficialFeature obstacle = park("between-stations", rectangle(15, -4, 35, 4));
        Fixture fixture = street(List.of(obstacle), 3);
        Network network = build(fixture);

        assertCompleteTree(network, fixture);
        Map<String, RouteNode> nodes = nodesById(network);
        List<RouteEdge> obstructedSpans = network.edges().stream()
                .filter(edge -> straight(nodes.get(edge.getUpstreamNodeId()), nodes.get(edge.getDownstreamNodeId()))
                        .intersects(obstacle.getMetricGeometry()))
                .collect(Collectors.toList());
        assertThat(obstructedSpans).isNotEmpty().allSatisfy(edge -> {
            assertThat(edge.getCoordinates().size()).isGreaterThan(2);
            LineString route = line(edge);
            assertThat(route.intersects(obstacle.getMetricGeometry())).isFalse();
            assertThat(route.distance(obstacle.getMetricGeometry())).isGreaterThanOrEqualTo(1.0 - 1e-6);
        });
        for (RouteEdge edge : network.edges()) {
            assertThat(rules.lineAllowed(line(edge), rules.baseConstraints(fixture.features, edge.getDiameter())))
                    .as("complete polyline of %s", edge.getId()).isTrue();
        }
        assertSizedAndValid(network, fixture);
    }

    @Test
    void relocatesBlockedJunctionWithinBoundedSpineAndKeepsValidatedTree() {
        Fixture original = street(List.of(), 3);
        Coordinate junction = original.candidate.junctions().get(0).coordinate();
        Fixture fixture = street(List.of(park("blocked-junction",
                rectangle(junction.x - 2, junction.y - 2, junction.x + 2, junction.y + 2))), 3);
        Network network = build(fixture);

        assertCompleteTree(network, fixture);
        Coordinate previous = null;
        for (int index = 0; index < fixture.candidate.junctions().size(); index++) {
            RouteNode station = nodesById(network).get("spine:" + fixture.root.getId() + ":" + index);
            Coordinate actual = station.getCoordinate().toCoordinate();
            Coordinate proposed = fixture.candidate.junctions().get(index).coordinate();
            assertThat(actual.y).isEqualTo(proposed.y);
            assertThat(actual.distance(proposed)).isLessThanOrEqualTo(80.0);
            if (index == 0) assertThat(actual.distance(proposed)).isGreaterThan(0.01);
            if (previous != null) assertThat(actual.x - previous.x).isGreaterThanOrEqualTo(2.0);
            previous = actual;
        }
        assertThat(fixture.candidate.junctions().get(0).coordinate().equals2D(junction)).isTrue();
        assertSizedAndValid(network, fixture);
    }

    @Test
    void rejectsJunctionWhenWholeRelocationRangeIsBlockedBeforeRequestingPaths() {
        Fixture original = street(List.of(), 3);
        Coordinate junction = original.candidate.junctions().get(0).coordinate();
        Fixture fixture = street(List.of(park("blocked-relocation-range",
                rectangle(junction.x - 81, junction.y - 2, junction.x + 81, junction.y + 2))), 3);
        AtomicInteger pathCalls = new AtomicInteger();
        Network network = builder.build(fixture.candidate, fixture.root, fixture.terminals,
                fixture.environment, (id, point, diameter, avoidance) -> {
                    pathCalls.incrementAndGet();
                    return terminalRouter(fixture).route(id, point, diameter, avoidance);
                }, (point, flow, diameter, avoidance) -> {
                    pathCalls.incrementAndGet();
                    return rootRouter(fixture).route(point, flow, diameter, avoidance);
                });

        assertThat(network).isNull();
        assertThat(pathCalls).hasValue(0);
    }

    @Test
    void returnsNoPartialNetworkWhenRealRouterCannotReachOneTerminal() {
        Fixture fixture = street(List.of(park("blocked-east-north", rectangle(58, 13, 62, 17))), 3);
        List<String> attempted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Network network = builder.build(fixture.candidate, fixture.root, fixture.terminals,
                fixture.environment, (id, point, diameter, avoidance) -> {
                    attempted.add(id);
                    RoutePath path = terminalRouter(fixture).route(id, point, diameter, avoidance);
                    if (path == null) failed.add(id);
                    return path;
                }, rootRouter(fixture));

        assertThat(attempted.size()).isGreaterThan(1);
        assertThat(failed).containsExactly("east-north");
        assertThat(network).isNull();
    }

    @Test
    void returnsNoNetworkAndDoesNotRouteTerminalsWhenRootIsUnreachable() {
        Fixture fixture = street(List.of(park("blocked-root", rectangle(-22, -47, -18, -43))), 3);
        AtomicInteger terminalCalls = new AtomicInteger();
        Network network = builder.build(fixture.candidate, fixture.root, fixture.terminals,
                fixture.environment, (id, point, diameter, avoidance) -> {
                    terminalCalls.incrementAndGet();
                    return terminalRouter(fixture).route(id, point, diameter, avoidance);
                }, rootRouter(fixture));

        assertThat(network).isNull();
        assertThat(terminalCalls).hasValue(0);
    }

    @Test
    void independentValidationIncludesExistingRootIncidentSections() {
        Fixture fixture = street(List.of(), 3);
        Network network = build(fixture);

        assertThat(network).isNotNull();
        // Допуск учитывает и существующую сеть, а не только степень нового дерева.
        List<RouteNode> saturatedRootNodes = network.nodes().stream()
                .map(node -> node.isRoot() ? new RouteNode(node.getId(), node.getNodeType(), node.getCoordinate(),
                        node.isChamber(), true, 4, node.getTargetId()) : node)
                .collect(Collectors.toList());
        assertThat(validator.validate(saturatedRootNodes, network.edges(), fixture.features))
                .anySatisfy(issue -> {
                    assertThat(issue.getCode()).isEqualTo("CHAMBER_DEGREE_EXCEEDED");
                    assertThat(issue.getSubjectId()).isEqualTo(fixture.root.getId());
                });
    }

    @Test
    void retainedRoutesReachEverySearchAndSharedRootContactRemainsAllowed() {
        Fixture fixture = street(List.of(), 2);
        RouteNode retainedLeaf = new RouteNode("retained-leaf", "demand_connection", new RouteCoordinate(-20, -90),
                false, false, 0, null);
        RouteNode otherRoot = new RouteNode("other-root", "existing_chamber", new RouteCoordinate(30, -4),
                true, true, 2, null);
        RouteNode otherLeaf = new RouteNode("other-leaf", "demand_connection", new RouteCoordinate(30, 4),
                false, false, 0, null);
        RouteEdge incident = retainedEdge("existing-ray", fixture.root, retainedLeaf);
        RouteEdge barrier = retainedEdge("crossing-ray", otherRoot, otherLeaf);
        AtomicInteger branchCalls = new AtomicInteger();
        Network network = builder.build(fixture.candidate, fixture.root, fixture.terminals, fixture.environment,
                List.of(incident, barrier), (id, point, diameter, avoidance) -> {
                    branchCalls.incrementAndGet();
                    assertThat(avoidance).anyMatch(route -> route.equalsExact(line(incident)));
                    assertThat(avoidance).anyMatch(route -> route.equalsExact(line(barrier)));
                    return terminalRouter(fixture).route(id, point, diameter, avoidance);
                }, (point, flow, diameter, avoidance) -> {
                    assertThat(avoidance).noneMatch(route -> route.equalsExact(line(incident)));
                    assertThat(avoidance).anyMatch(route -> route.equalsExact(line(barrier)));
                    return rootRouter(fixture).route(point, flow, diameter, avoidance);
                });

        assertCompleteTree(network, fixture);
        assertSizedAndValid(network, fixture);
        assertThat(branchCalls).hasValue(6);
        assertThat(network.edges()).noneMatch(edge -> edge.getId().equals(incident.getId()) || edge.getId().equals(barrier.getId()));
        Map<String, RouteNode> nodes = nodesById(network);
        assertThat(network.edges()).filteredOn(edge -> straight(nodes.get(edge.getUpstreamNodeId()), nodes.get(edge.getDownstreamNodeId()))
                .intersects(line(barrier))).isNotEmpty().allSatisfy(edge -> assertThat(line(edge).disjoint(line(barrier))).isTrue());
        List<RouteNode> combinedNodes = new ArrayList<>(network.nodes());
        combinedNodes.addAll(List.of(retainedLeaf, otherRoot, otherLeaf));
        List<RouteEdge> combinedEdges = new ArrayList<>(network.edges());
        combinedEdges.addAll(List.of(incident, barrier));
        assertThat(validator.validate(combinedNodes, combinedEdges, List.of())).isEmpty();
    }

    @Test
    void preparesBlockedStationConstraintsOnceInsteadOfOncePerOffset() {
        Fixture original = street(List.of(), 3);
        Coordinate first = original.candidate.junctions().get(0).coordinate();
        List<ImportedOfficialFeature> obstacles = List.of(park("all-offsets-blocked",
                rectangle(first.x - 81, first.y - 2, first.x + 81, first.y + 2)));
        AtomicInteger queries = new AtomicInteger();
        RoutingFeatureSource source = new RoutingFeatureSource() {
            @Override public List<ImportedOfficialFeature> findInMetricWindow(Envelope window) {
                queries.incrementAndGet();
                return new InMemoryRoutingFeatureSource(obstacles).findInMetricWindow(window);
            }
            @Override public List<ImportedOfficialFeature> findByFeatureIds(Set<String> ids) { return List.of(); }
        };
        OfficialRoutingEnvironment environment = new OfficialRoutingEnvironment(List.of(), source, rules);
        AtomicInteger routeCalls = new AtomicInteger();
        for (int operation = 1; operation <= 2; operation++) {
            Network network = builder.build(original.candidate, original.root, original.terminals, environment,
                    (id, point, diameter, avoidance) -> { routeCalls.incrementAndGet(); return null; },
                    (point, flow, diameter, avoidance) -> { routeCalls.incrementAndGet(); return null; });
            assertThat(network).isNull();
            assertThat(routeCalls).hasValue(0);
            assertThat(queries).hasValue(operation);
        }
    }

    @Test
    void keepsNominalTwoMetreStationsAfterRotationButRejectsTrulyShortSpacing() throws Exception {
        Method relocate = SharedSpineNetworkBuilder.class.getDeclaredMethod("freeStation", List.class,
                int.class, RouteNode.class, Map.class, Predicate.class);
        relocate.setAccessible(true);
        RouteNode root = new RouteNode("root", "existing_chamber", new RouteCoordinate(409900, 6179900),
                true, true, 2, null);
        for (double angle : new double[] {0.37, 0.7, 2.7}) {
            AffineTransformation transform = AffineTransformation.rotationInstance(angle).translate(410000, 6180000);
            for (double spacing : new double[] {2.0, 1.9999}) {
                List<Coordinate> stations = List.of(transform.transform(new Coordinate(-spacing, 0), new Coordinate()),
                        transform.transform(new Coordinate(0, 0), new Coordinate()),
                        transform.transform(new Coordinate(spacing, 0), new Coordinate()));
                Coordinate moved = (Coordinate) relocate.invoke(builder, stations, 1, root, Map.of(),
                        (Predicate<Coordinate>) point -> false);
                if (spacing == 2.0) assertThat(moved).isEqualTo(stations.get(1));
                else assertThat(moved).isNull();
            }
        }
    }

    private RouteEdge retainedEdge(String id, RouteNode start, RouteNode end) {
        LineString path = straight(start, end);
        return new RouteEdge(id, start.getId(), end.getId(), path.getLength(),
                List.of(start.getCoordinate(), end.getCoordinate()), List.of(), BigDecimal.ONE, 65);
    }

    @Test
    void propagatesPreexistingCancellationWithoutReturningNetwork() {
        Fixture fixture = street(List.of(), 3);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> build(fixture)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6})
    void cancellationDuringTerminalRoutingNeverReturnsPartialOrCompletedNetwork(int cancelAfterCall) {
        Fixture fixture = street(List.of(), 3);
        AtomicInteger terminalCalls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> builder.build(fixture.candidate, fixture.root, fixture.terminals,
                    fixture.environment, (id, point, diameter, avoidance) -> {
                        RoutePath path = terminalRouter(fixture).route(id, point, diameter, avoidance);
                        assertThat(path).isNotNull();
                        if (terminalCalls.incrementAndGet() == cancelAfterCall) Thread.currentThread().interrupt();
                        return path;
                    }, rootRouter(fixture))).isInstanceOf(CancellationException.class);
            assertThat(terminalCalls).hasValue(cancelAfterCall);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private Network build(Fixture fixture) {
        return builder.build(fixture.candidate, fixture.root, fixture.terminals, fixture.environment,
                terminalRouter(fixture), rootRouter(fixture));
    }

    private SharedSpineNetworkBuilder.TerminalRouter terminalRouter(Fixture fixture) {
        return (id, junction, diameter, avoidance) -> router.find(fixture.coordinates.get(id), junction,
                diameter, fixture.environment, Set.of(), RoutePreference.ENGINEERING, avoidance);
    }

    private SharedSpineNetworkBuilder.RootRouter rootRouter(Fixture fixture) {
        return (junction, flow, diameter, avoidance) -> router.find(junction, fixture.root.getCoordinate().toCoordinate(),
                diameter, fixture.environment, Set.of(), RoutePreference.ENGINEERING, avoidance);
    }

    private void assertCompleteTree(Network network, Fixture fixture) {
        assertThat(network).isNotNull();
        assertThat(network.nodes()).extracting(RouteNode::getId).doesNotHaveDuplicates();
        assertThat(network.edges()).hasSize(network.nodes().size() - 1)
                .extracting(RouteEdge::getId).doesNotHaveDuplicates();
        assertThat(network.connections()).hasSize(6).extracting(RouteConnection::getDemandId)
                .containsExactlyInAnyOrderElementsOf(fixture.coordinates.keySet());
        assertThat(network.connections()).allSatisfy(connection -> {
            assertThat(connection.getStatus()).isEqualTo("connected");
            assertThat(connection.getConnectionPointId()).isEqualTo("cp-" + connection.getDemandId());
            assertThat(connection.getFlowTph()).isEqualByComparingTo(fixture.flows.get(connection.getDemandId()));
        });
        Map<String, RouteNode> nodes = nodesById(network);
        for (RouteNode node : network.nodes()) {
            long incoming = network.edges().stream().filter(edge -> edge.getDownstreamNodeId().equals(node.getId())).count();
            long outgoing = network.edges().stream().filter(edge -> edge.getUpstreamNodeId().equals(node.getId())).count();
            assertThat(incoming).as("incoming at %s", node.getId()).isEqualTo(node.isRoot() ? 0 : 1);
            assertThat(incoming + outgoing + node.getBaseIncidentSections()).as("degree at %s", node.getId())
                    .isLessThanOrEqualTo(4);
            if ("demand_connection".equals(node.getNodeType())) assertThat(outgoing).isZero();
        }
        assertThat(network.nodes()).filteredOn(node -> "demand_connection".equals(node.getNodeType()))
                .extracting(RouteNode::getId)
                .containsExactlyInAnyOrderElementsOf(fixture.coordinates.keySet().stream()
                        .map(id -> "demand:" + id).collect(Collectors.toList()));
        for (RouteEdge edge : network.edges()) {
            LineString route = line(edge);
            assertThat(route.getCoordinateN(0).distance(nodes.get(edge.getUpstreamNodeId()).getCoordinate().toCoordinate()))
                    .isLessThanOrEqualTo(0.001);
            assertThat(route.getCoordinateN(route.getNumPoints() - 1)
                    .distance(nodes.get(edge.getDownstreamNodeId()).getCoordinate().toCoordinate()))
                    .isLessThanOrEqualTo(0.001);
        }
    }

    private void assertSizedAndValid(Network network, Fixture fixture) {
        Map<String, BigDecimal> demandFlows = new LinkedHashMap<>();
        fixture.flows.forEach((id, flow) -> demandFlows.put("demand:" + id, flow));
        NetworkSizingResult sized = new OfficialNetworkSizer(pipes).size(network.edges().stream()
                .map(edge -> new NetworkTreeEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(), edge.getLengthM()))
                .collect(Collectors.toList()), demandFlows);
        assertThat(sized.getIssues()).isEmpty();
        List<RouteEdge> sizedEdges = new ArrayList<>();
        for (RouteEdge edge : network.edges()) {
            SizedNetworkEdge actual = sized.getEdges().get(edge.getId());
            assertThat(edge.getFlowTph()).isEqualByComparingTo(actual.getFlowTph());
            assertThat(edge.getDiameter()).isEqualTo(actual.getDiameter());
            sizedEdges.add(new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                    edge.getLengthM().doubleValue(), edge.getCoordinates(), edge.getSections(), actual.getFlowTph(), actual.getDiameter()));
        }
        assertThat(validator.validate(network.nodes(), network.edges(), fixture.features)).isEmpty();
        assertThat(validator.validate(network.nodes(), sizedEdges, fixture.features)).isEmpty();
    }

    private Fixture street(List<ImportedOfficialFeature> features, int rootExistingSections) {
        Map<String, Coordinate> coordinates = new LinkedHashMap<>();
        String[] stations = {"west", "centre", "east"};
        for (int index = 0; index < stations.length; index++) {
            coordinates.put(stations[index] + "-south", new Coordinate(-60 + index * 60, -15));
            coordinates.put(stations[index] + "-north", new Coordinate(-60 + index * 60, 15));
        }
        RouteNode root = new RouteNode("existing-root", "existing_chamber", new RouteCoordinate(-20, -45),
                true, true, rootExistingSections, "existing-root-feature");
        List<SharedSpineCandidateBuilder.Terminal> candidates = coordinates.entrySet().stream()
                .map(entry -> new SharedSpineCandidateBuilder.Terminal(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        SpineCandidate candidate = new SharedSpineCandidateBuilder().build(candidates, root.getCoordinate().toCoordinate(), List.of())
                .stream().filter(item -> item.junctions().stream().allMatch(junction -> Math.abs(junction.coordinate().y) < 1e-6))
                .filter(item -> item.rootJunctionIndex() > 0 && item.rootJunctionIndex() + 1 < item.junctions().size())
                .findFirst().orElseThrow();
        Map<String, SharedSpineNetworkBuilder.Terminal> terminals = new LinkedHashMap<>();
        Map<String, BigDecimal> flows = new LinkedHashMap<>();
        for (Map.Entry<String, Coordinate> entry : coordinates.entrySet()) {
            BigDecimal flow = BigDecimal.valueOf(flows.size() + 1);
            flows.put(entry.getKey(), flow);
            terminals.put(entry.getKey(), new SharedSpineNetworkBuilder.Terminal(
                    entry.getKey(), "cp-" + entry.getKey(), entry.getValue(), flow));
        }
        return new Fixture(candidate, root, coordinates, terminals, flows, features, router.prepare(features));
    }

    private Map<String, RouteNode> nodesById(Network network) {
        return network.nodes().stream().collect(Collectors.toMap(RouteNode::getId, node -> node));
    }

    private LineString line(RouteEdge edge) {
        return geometries.createLineString(edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
    }

    private LineString straight(RouteNode from, RouteNode to) {
        return geometries.createLineString(new Coordinate[] {from.getCoordinate().toCoordinate(), to.getCoordinate().toCoordinate()});
    }

    private Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        return geometries.createPolygon(new Coordinate[] {new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY), new Coordinate(minX, minY)});
    }

    private ImportedOfficialFeature park(String id, Geometry polygon) {
        return new ImportedOfficialFeature(id, "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "park"), polygon);
    }

    private static final class Fixture {
        private final SpineCandidate candidate;
        private final RouteNode root;
        private final Map<String, Coordinate> coordinates;
        private final Map<String, SharedSpineNetworkBuilder.Terminal> terminals;
        private final Map<String, BigDecimal> flows;
        private final List<ImportedOfficialFeature> features;
        private final OfficialRoutingEnvironment environment;

        private Fixture(SpineCandidate candidate, RouteNode root, Map<String, Coordinate> coordinates,
                Map<String, SharedSpineNetworkBuilder.Terminal> terminals, Map<String, BigDecimal> flows,
                List<ImportedOfficialFeature> features, OfficialRoutingEnvironment environment) {
            this.candidate = candidate;
            this.root = root;
            this.coordinates = coordinates;
            this.terminals = terminals;
            this.flows = flows;
            this.features = features;
            this.environment = environment;
        }
    }
}
