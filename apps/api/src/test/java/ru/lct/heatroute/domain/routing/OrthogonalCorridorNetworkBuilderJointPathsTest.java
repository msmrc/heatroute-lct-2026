package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/** Проверяет реальную локальную генерацию и компрессию выбранных вводов фиксированного дерева. */
class OrthogonalCorridorNetworkBuilderJointPathsTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialRoutingEnvironment environment = router.prepare(List.of());
    private final OrthogonalCorridorNetworkBuilder builder = new OrthogonalCorridorNetworkBuilder(router, new OfficialPipeCatalog());
    private final RouteNode root = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(-10, 0),
            true, true, 2, "root");
    private final List<int[]> tree = List.of(new int[] {0, 1});


    @Test
    void fixedPortAssemblyRecoversBothConsumersWithoutOverlappingTheNorthApproach() throws Exception {
        RoutePath north = path(0, 0, 0, 10), overlap = path(0, 0, 0, 10, 10, 10);
        List<Coordinate> before = List.copyOf(overlap.coordinates());
        List<Object> ports = List.of(port("a", north), port("b", overlap));
        OrthogonalCorridorGrid grid = grid();
        List<OrthogonalCorridorNetworkBuilder.Network> repaired = repair(grid, ports);
        assertThat(repaired).hasSize(1);
        OrthogonalCorridorNetworkBuilder.Network network = repaired.get(0);
        assertValidArithmetic(network, 40);
        assertThat(network.connections()).hasSize(2);
        assertThat(network.edges()).filteredOn(edge -> edge.getDownstreamNodeId().equals("demand:b"))
                .singleElement().satisfies(edge -> assertThat(edge.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate).collect(Collectors.toList())).contains(new Coordinate(10, 0)));
        assertThat(overlap.coordinates()).containsExactlyElementsOf(before);
    }

    @Test
    void compressionUsesChosenLongerStubAndItsSectionsAfterAnIllegalDegreeTwoJoin() throws Exception {
        RoutePath shorter = path(0, 0, -0.035, 10, -10, 10);
        List<Object> ports = List.of(port("a", shorter));
        OrthogonalCorridorGrid grid = grid();
        Method jointPaths = OrthogonalCorridorNetworkBuilder.class.getDeclaredMethod("jointPaths",
                ports.get(0).getClass(), OrthogonalCorridorGrid.class, CorridorTerminalRouter.class);
        jointPaths.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<RoutePath> bounded = (List<RoutePath>) jointPaths.invoke(builder, ports.get(0), grid, spurs());
        assertThat(bounded).hasSizeLessThanOrEqualTo(8);
        assertThat(bounded).anySatisfy(candidate -> assertThat(candidate.coordinates())
                .containsExactly(new Coordinate(), new Coordinate(0, 10), new Coordinate(-10, 10)));
        Method compress = OrthogonalCorridorNetworkBuilder.class.getDeclaredMethod("compress",
                List.class, OrthogonalCorridorGrid.class, List.class, RouteNode.class, PreparedCorridor.class,
                OfficialRoutingEnvironment.class);
        compress.setAccessible(true);
        assertThat(compress.invoke(builder, tree, grid, ports, root, checks(), environment)).isNull();
        List<OrthogonalCorridorNetworkBuilder.Network> repaired = repair(grid, ports);
        assertThat(repaired).hasSize(1);
        assertValidArithmetic(repaired.get(0), 30);
        assertThat(new EngineeringRouteEvaluator().evaluate(repaired.get(0).edges()).bendCount()).isEqualTo(2);
        assertThat(repaired.get(0).edges()).singleElement().satisfies(edge -> {
            assertThat(edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList()))
                    .contains(new Coordinate(0, 10)).doesNotContain(new Coordinate(-0.035, 10));
            assertThat(edge.getLengthM().doubleValue()).isGreaterThan(10 + shorter.lengthM());
        });
    }

    private void assertValidArithmetic(OrthogonalCorridorNetworkBuilder.Network network, double expectedLength) {
        assertThat(new OfficialRouteValidator(rules).validate(network.nodes(), network.edges(), List.of())).isEmpty();
        assertThat(new EngineeringRouteEvaluator().evaluate(network.edges()).isCompliant()).isTrue();
        assertThat(network.edges().stream().mapToDouble(edge -> edge.getLengthM().doubleValue()).sum())
                .isCloseTo(expectedLength, offset(0.002));
        for (RouteEdge edge : network.edges()) {
            double geometryLength = new GeometryFactory().createLineString(edge.getCoordinates().stream()
                    .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new)).getLength();
            assertThat(edge.getLengthM().doubleValue()).isCloseTo(geometryLength, offset(0.002));
            assertThat(edge.getSections().stream().mapToDouble(section -> section.getLengthM().doubleValue()).sum())
                    .isCloseTo(geometryLength, offset(0.002));
        }
    }

    private List<OrthogonalCorridorNetworkBuilder.Network> repair(OrthogonalCorridorGrid grid, List<Object> ports)
            throws Exception {
        List<OrthogonalCorridorNetworkBuilder.Network> results = new ArrayList<>();
        Method method = OrthogonalCorridorNetworkBuilder.class.getDeclaredMethod("addJointCandidate", List.class,
                List.class, OrthogonalCorridorGrid.class, List.class, RouteNode.class, PreparedCorridor.class,
                CorridorTerminalRouter.class, OfficialRoutingEnvironment.class);
        method.setAccessible(true);
        method.invoke(builder, results, tree, grid, ports, root, checks(), spurs(), environment);
        return results;
    }

    private CorridorTerminalRouter spurs() {
        return new CorridorTerminalRouter(router, environment,
                (id, point, diameter, avoidance) -> { throw new AssertionError("Joint repair must use local generation"); }, 0);
    }

    private PreparedCorridor checks() {
        return router.prepareCorridor(50, environment, new Envelope(-20, 20, -20, 20),
                root.getCoordinate().toCoordinate(), root.getTargetId());
    }

    private OrthogonalCorridorGrid grid() throws Exception {
        Constructor<OrthogonalCorridorGrid> constructor = OrthogonalCorridorGrid.class
                .getDeclaredConstructor(List.class, List.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(List.of(new Coordinate(-10, 0), new Coordinate()), tree, 0);
    }

    private Object port(String id, RoutePath path) throws Exception {
        OrthogonalCorridorNetworkBuilder.Terminal terminal = new OrthogonalCorridorNetworkBuilder.Terminal(
                id, id, path.coordinates().get(path.coordinates().size() - 1), BigDecimal.ONE);
        Constructor<?> constructor = Class.forName(OrthogonalCorridorNetworkBuilder.class.getName() + "$Port")
                .getDeclaredConstructor(OrthogonalCorridorNetworkBuilder.Terminal.class, int.class, RoutePath.class);
        constructor.setAccessible(true);
        return constructor.newInstance(terminal, 1, path);
    }

    private RoutePath path(double... xy) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new Coordinate(xy[i], xy[i + 1]));
        double length = new GeometryFactory().createLineString(points.toArray(new Coordinate[0])).getLength();
        List<RouteCoordinate> rounded = points.stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList());
        return new RoutePath(points, List.of(new RouteSection("base", null, null, rounded, length, null)), length);
    }
}
