package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Льгота врезки не должна удалять секцию дальнего пересечения той же выбранной теплосети. */
class CorridorRepeatedTargetSectionTest {
    private final GeometryFactory geometries = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void mergedRootToBranchEdgeRetainsTheDistantSpecialOfItsActualSelectedTarget() throws Exception {
        Fixture fixture = fixture(true);
        OrthogonalCorridorNetworkBuilder.Network network = fixture.network;
        assertThat(network.connections()).extracting(RouteConnection::getDemandId)
                .containsExactlyInAnyOrder("east", "north");
        assertThat(new OfficialRouteValidator(rules).validate(network.nodes(), network.edges(), fixture.features)).isEmpty();
        RouteEdge trunk = fixture.trunk();
        assertThat(network.nodes()).filteredOn(node -> node.getId().equals(trunk.getDownstreamNodeId()))
                .singleElement().satisfies(node -> assertThat(node.getNodeType()).isEqualTo("new_branch_chamber"));
        assertThat(trunk.getLengthM()).isEqualByComparingTo("100.000");
        assertThat(trunk.getDiameter()).isEqualTo(50);
        assertThat(trunk.getFlowTph()).isEqualByComparingTo("2.000");
        assertThat(network.edges().stream().mapToDouble(edge -> edge.getLengthM().doubleValue()).sum())
                .isCloseTo(120, offset(0.001));
        List<RouteSection> specials = trunk.getSections().stream()
                .filter(section -> "special".equals(section.getKind())).collect(Collectors.toList());
        assertThat(specials).as("the crossing at x=60 is distinct from the tie-in at x=0")
                .singleElement().satisfies(section -> {
                    assertThat(section.getRestrictionId()).isEqualTo(fixture.root.getTargetId());
                    assertThat(section.getRestrictionType()).isEqualTo("heat_network");
                    assertThat(section.getLengthM()).isEqualByComparingTo("4.000");
                    assertThat(section.getCrossingAngleDegrees()).isEqualByComparingTo("90");
                    assertThat(section.getCoordinates()).extracting(RouteCoordinate::toCoordinate)
                            .containsExactly(point(58, 0), point(62, 0));
                });
        assertSectionCoverage(trunk);
        // Дополнительно проверяем реальные ограничения независимо от политики льгот финального валидатора.
        LineString physicalLine = rules.line(trunk.getCoordinates().stream()
                .map(RouteCoordinate::toCoordinate).collect(Collectors.toList()));
        assertThat(rules.validate(trunk, physicalLine, rules.baseConstraints(fixture.features, trunk.getDiameter()))).isEmpty();
    }

    @Test
    void finalValidatorRejectsMissingRepeatedSpecialWithTheOriginalTargetIdStillPresent() throws Exception {
        Fixture fixture = fixture(true);
        RouteEdge trunk = fixture.trunk();
        assertThat(fixture.root.getTargetId()).isEqualTo(fixture.features.get(0).getFeatureId());
        RouteEdge withoutSpecial = new RouteEdge(trunk.getId(), trunk.getUpstreamNodeId(), trunk.getDownstreamNodeId(),
                trunk.getLengthM().doubleValue(), trunk.getCoordinates(), List.of(new RouteSection(
                        "base", null, null, trunk.getCoordinates(), trunk.getLengthM().doubleValue(), null)),
                trunk.getFlowTph(), trunk.getDiameter());
        List<RouteEdge> corrupted = fixture.network.edges().stream()
                .map(edge -> edge == trunk ? withoutSpecial : edge).collect(Collectors.toList());
        assertThat(new OfficialRouteValidator(rules).validate(fixture.network.nodes(), corrupted, fixture.features))
                .anySatisfy(issue -> {
                    assertThat(issue.getCode()).isEqualTo("SPECIAL_CROSSING_SECTION_MISSING");
                    assertThat(issue.getSubjectId()).isEqualTo(trunk.getId());
                });
    }

    @Test
    void localTieInWithoutASecondCrossingNeedsNoSpecialSection() throws Exception {
        Fixture fixture = fixture(false);
        assertThat(fixture.trunk().getLengthM()).isEqualByComparingTo("100.000");
        assertThat(fixture.trunk().getSections()).allSatisfy(section -> {
            assertThat(section.getKind()).isEqualTo("base");
            assertThat(section.getRestrictionId()).isNull();
        });
        assertSectionCoverage(fixture.trunk());
        assertThat(new OfficialRouteValidator(rules).validate(
                fixture.network.nodes(), fixture.network.edges(), fixture.features)).isEmpty();
    }

    @Test
    void aForbiddenFeatureSharingTheSelectedNetworkIdIsNotExempt() throws Exception {
        Fixture fixture = fixture(false);
        ImportedOfficialFeature water = new ImportedOfficialFeature(fixture.root.getTargetId(), "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "water"),
                geometries.createPolygon(new Coordinate[] {
                        point(59, -5), point(61, -5), point(61, 5), point(59, 5), point(59, -5)}));
        List<ImportedOfficialFeature> features = List.of(fixture.features.get(0), water);
        PreparedCorridor checks = router.prepareCorridor(50, router.prepare(features),
                new Envelope(point(-10, -60), point(120, 60)), point(0, 0), fixture.root.getTargetId());
        assertThat(checks.edgeAllowed(point(0, 0), point(100, 0))).isFalse();
        assertThat(new OfficialRouteValidator(rules).validate(
                fixture.network.nodes(), fixture.network.edges(), features))
                .anySatisfy(issue -> {
                    assertThat(issue.getCode()).isEqualTo("FORBIDDEN_CLEARANCE_VIOLATION");
                    assertThat(issue.getSubjectId()).isEqualTo(fixture.trunk().getId());
                });
    }

    private Fixture fixture(boolean repeatedCrossing) throws Exception {
        Coordinate[] existingPoints = repeatedCrossing
                ? new Coordinate[] {point(0, 0), point(0, 50), point(60, 50), point(60, -50)}
                : new Coordinate[] {point(0, 0), point(0, 50)};
        LineString existing = geometries.createLineString(existingPoints);
        ImportedOfficialFeature selected = new ImportedOfficialFeature("selected", "heat_network",
                new ObjectMapper().createObjectNode(), existing);
        List<ImportedOfficialFeature> features = List.of(selected);
        OfficialRoutingEnvironment environment = router.prepare(features);
        List<Coordinate> points = List.of(point(0, 0), point(30, 0), point(70, 0), point(100, 0));
        List<int[]> tree = List.of(new int[] {0, 1}, new int[] {1, 2}, new int[] {2, 3});
        RouteNode root = new RouteNode("root", "new_tie_in_chamber", routeCoordinate(points.get(0)),
                true, true, 2, selected.getFeatureId());
        assertThat(root.getTargetId()).isEqualTo(selected.getFeatureId());
        assertThat(existing.getCoordinateN(0).equals2D(root.getCoordinate().toCoordinate())).isTrue();
        PreparedCorridor checks = router.prepareCorridor(50, environment,
                new Envelope(point(-10, -60), point(120, 60)), points.get(0), root.getTargetId());
        for (int[] link : tree) {
            assertThat(checks.edgeAllowed(points.get(link[0]), points.get(link[1])))
                    .as("each piece must be legal in the rooted direction").isTrue();
        }
        Constructor<OrthogonalCorridorGrid> gridConstructor = OrthogonalCorridorGrid.class
                .getDeclaredConstructor(List.class, List.class, int.class);
        gridConstructor.setAccessible(true);
        OrthogonalCorridorGrid grid = gridConstructor.newInstance(points, tree, 0);
        List<Object> ports = List.of(
                port("east", point(110, 0), points.get(3), environment),
                port("north", point(100, 10), points.get(3), environment));
        OrthogonalCorridorNetworkBuilder builder = new OrthogonalCorridorNetworkBuilder(router, new OfficialPipeCatalog());
        Method compress = OrthogonalCorridorNetworkBuilder.class.getDeclaredMethod("compress",
                List.class, OrthogonalCorridorGrid.class, List.class, RouteNode.class,
                PreparedCorridor.class, OfficialRoutingEnvironment.class);
        compress.setAccessible(true);
        OrthogonalCorridorNetworkBuilder.Network network = (OrthogonalCorridorNetworkBuilder.Network)
                compress.invoke(builder, tree, grid, ports, root, checks, environment);
        assertThat(network).isNotNull();
        return new Fixture(network, root, features);
    }

    private Object port(String id, Coordinate demand, Coordinate junction,
            OfficialRoutingEnvironment environment) throws Exception {
        PreparedCorridor terminalChecks = router.prepareCorridor(50, environment,
                new Envelope(demand, junction), demand, null, RouteTraversal.REVERSED);
        RoutePath checked = terminalChecks.path(List.of(demand, junction));
        assertThat(checked).as("terminal spur must be checked in its physical direction").isNotNull();
        Constructor<?> constructor = Class.forName(OrthogonalCorridorNetworkBuilder.class.getName() + "$Port")
                .getDeclaredConstructor(OrthogonalCorridorNetworkBuilder.Terminal.class, int.class, RoutePath.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new OrthogonalCorridorNetworkBuilder.Terminal(
                id, "cp:" + id, demand, BigDecimal.ONE), 3, checked.reversed());
    }

    private void assertSectionCoverage(RouteEdge edge) {
        Coordinate previous = edge.getCoordinates().get(0).toCoordinate();
        List<Double> lengths = new ArrayList<>();
        for (RouteSection section : edge.getSections()) {
            List<Coordinate> points = section.getCoordinates().stream()
                    .map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
            assertThat(points.get(0).distance(previous)).isLessThanOrEqualTo(0.001);
            double length = rules.line(points).getLength();
            assertThat(section.getLengthM().doubleValue()).isCloseTo(length, offset(0.001));
            lengths.add(length);
            previous = points.get(points.size() - 1);
        }
        assertThat(previous).isEqualTo(edge.getCoordinates().get(edge.getCoordinates().size() - 1).toCoordinate());
        assertThat(lengths.stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(edge.getLengthM().doubleValue(), offset(0.001));
    }

    private Coordinate point(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
    private RouteCoordinate routeCoordinate(Coordinate point) { return new RouteCoordinate(point.x, point.y); }

    private static final class Fixture {
        private final OrthogonalCorridorNetworkBuilder.Network network;
        private final RouteNode root;
        private final List<ImportedOfficialFeature> features;

        private Fixture(OrthogonalCorridorNetworkBuilder.Network network, RouteNode root,
                List<ImportedOfficialFeature> features) {
            this.network = network;
            this.root = root;
            this.features = features;
        }

        private RouteEdge trunk() {
            return network.edges().stream().filter(edge -> root.getId().equals(edge.getUpstreamNodeId()))
                    .findFirst().orElseThrow();
        }
    }
}
