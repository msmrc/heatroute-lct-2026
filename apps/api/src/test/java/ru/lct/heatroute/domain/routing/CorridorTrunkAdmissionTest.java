package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Допуск ствола проверяется после ориентации от корня и сборки полного пересечения дороги. */
class CorridorTrunkAdmissionTest {
    private static final int DIAMETER = 100;
    private static final BigDecimal FLOW = new BigDecimal("20");
    private static final GeometryFactory GEOMETRIES = new GeometryFactory();
    private static final Frame UTM = new Frame(500000, 6170000, 1, 0);
    private static final Frame TRANSLATED = new Frame(510321, 6180456, 1, 0);
    // Поворот 3–4–5 сохраняет миллиметровую точность целочисленных точек ствола.
    private static final Frame ROTATED = new Frame(500000, 6170000, 0.8, 0.6);
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OrthogonalCorridorNetworkBuilder builder =
            new OrthogonalCorridorNetworkBuilder(router, new OfficialPipeCatalog());

    @Test
    void retainsDirect130mCandidateWhenOnlyRootedDirectionIsLegal() throws Exception {
        assertDirectCandidate(UTM);
    }

    @Test
    void retainsDirect130mCandidateAfterUtmTranslation() throws Exception {
        assertDirectCandidate(TRANSLATED);
    }

    @Test
    void retainsDirect130mCandidateAfterUtmRotation() throws Exception {
        assertDirectCandidate(ROTATED);
    }

    @Test
    void rootsCanonicalTreeBeforeCheckingPhysicalEntrance() throws Exception {
        assertRootedCompression(UTM);
    }

    @Test
    void rootsCanonicalTreeAfterUtmTranslation() throws Exception {
        assertRootedCompression(TRANSLATED);
    }

    @Test
    void rootsCanonicalTreeAfterUtmRotation() throws Exception {
        assertRootedCompression(ROTATED);
    }

    @Test
    void admitsTechnicallySubdividedCrossingAsOne26mSpecial() throws Exception {
        assertSubdividedCrossing(UTM);
    }

    @Test
    void admitsTechnicallySubdividedCrossingAfterUtmTranslation() throws Exception {
        assertSubdividedCrossing(TRANSLATED);
    }

    @Test
    void admitsTechnicallySubdividedCrossingAfterUtmRotation() throws Exception {
        assertSubdividedCrossing(ROTATED);
    }

    @Test
    void rejectsWrongPhysicalEntranceDespiteUndirectedGridCandidate() throws Exception {
        assertWrongEntrance(UTM);
    }

    @Test
    void rejectsWrongPhysicalEntranceAfterUtmRotation() throws Exception {
        assertWrongEntrance(ROTATED);
    }

    @Test
    void rejectsTurnAfterOnlyTwoOfTheRequiredThreeProtectionMetres() throws Exception {
        Coordinate root = UTM.point(0, 0), port = UTM.point(62, 0), demand = UTM.point(62, 20);
        Fixture fixture = roadFixture(UTM, root, UTM.rectangle(40, -100, 60, 100));
        OrthogonalCorridorGrid grid = fixture.grid(List.of(port));
        assertThat(fixture.checks.edgeAllowed(root, port)).isTrue();
        assertThat(assessment(List.of(root, port), fixture.features.get(0).getMetricGeometry())
                .getFailureCode()).isEqualTo("SPECIAL_CROSSING_EXTENSION_MISSING");
        assertThat(assessment(List.of(root, port, demand), fixture.features.get(0).getMetricGeometry())
                .getFailureCode()).isEqualTo("SPECIAL_CROSSING_NOT_STRAIGHT");
        RoutePath spur = checkedSpur(fixture, port, demand);
        assertThat(compress(tree(grid, port), grid, port, spur, fixture, FLOW)).isNull();
    }

    @Test
    void rejectsBendInsideRoadEvenWhenEachTechnicalLinkIsProvisionallyLegal() throws Exception {
        List<Coordinate> points = List.of(UTM.point(0, 0), UTM.point(50, 0),
                UTM.point(50, 20), UTM.point(100, 20));
        Coordinate port = points.get(3), demand = UTM.point(110, 20);
        Fixture fixture = roadFixture(UTM, points.get(0), UTM.rectangle(40, -100, 60, 100));
        OrthogonalCorridorGrid grid = fixture.grid(points.subList(1, points.size()));
        List<int[]> selected = linksAlong(grid, points);
        for (int i = 1; i < points.size(); i++) {
            assertThat(fixture.checks.edgeAllowed(points.get(i - 1), points.get(i))).isTrue();
            assertThat(hasLink(grid, selected.get(i - 1)[0], selected.get(i - 1)[1])).isTrue();
        }
        List<Coordinate> full = new ArrayList<>(points);
        full.add(demand);
        assertThat(assessment(full, fixture.features.get(0).getMetricGeometry()).getFailureCode())
                .isEqualTo("SPECIAL_CROSSING_NOT_STRAIGHT");
        assertThat(compress(selected, grid, port, checkedSpur(fixture, port, demand), fixture, FLOW)).isNull();
    }

    @Test
    void rebuildsCompleteSpecialAcrossTrunkAndTerminalPieceJoin() throws Exception {
        Coordinate root = UTM.point(0, 0), port = UTM.point(50, 0), demand = UTM.point(110, 0);
        Fixture fixture = roadFixture(UTM, root, UTM.rectangle(40, -100, 60, 100));
        OrthogonalCorridorGrid grid = fixture.grid(List.of(port));
        assertThat(fixture.checks.path(List.of(root, port))).isNull();
        assertThat(fixture.checks.path(List.of(port, demand))).isNull();
        // Проверяем границу сборки: существующий pathAfter допускает суффикс только с реальным
        // предшественником. Это не утверждение о выборе такого порта терминальным поиском.
        RoutePath suffix = fixture.checks.pathAfter(root, List.of(port, demand));
        assertThat(suffix).isNotNull();
        List<Coordinate> before = copyCoordinates(suffix);
        OrthogonalCorridorNetworkBuilder.Network network =
                compress(tree(grid, port), grid, port, suffix, fixture, FLOW);
        RouteEdge edge = assertNetwork(network, fixture, demand, 110);
        assertSpecial(edge, 26, UTM.point(37, 0), UTM.point(63, 0));
        assertThat(suffix.coordinates()).containsExactlyElementsOf(before);
    }

    @Test
    void preservesPrevalidatedOwnOksTerminalSpurAfterSubdividedRoadTrunk() throws Exception {
        Coordinate root = UTM.point(100, 0), port = UTM.point(20, 0), demand = UTM.point(-1, 0);
        Fixture fixture = new Fixture(UTM, root, List.of(
                feature("road", "road", UTM.rectangle(40, -100, 60, 100)),
                feature("own", "oks", UTM.rectangle(-10, -10, 0, 10))));
        OrthogonalCorridorGrid grid = fixture.grid(List.of(UTM.point(70, 0), UTM.point(50, 0),
                UTM.point(30, 0), port));
        RoutePath spur = generatedSpur(fixture, port, demand);
        List<Coordinate> before = copyCoordinates(spur);
        assertThat(fixture.checks.path(List.of(port, demand))).as("own-OKS entry needs its local terminal check")
                .isNull();
        RouteEdge edge = assertNetwork(compress(tree(grid, port), grid, port, spur, fixture, FLOW),
                fixture, demand, 101);
        assertSpecial(edge, 26, UTM.point(63, 0), UTM.point(37, 0));
        assertThat(spur.coordinates()).containsExactlyElementsOf(before);
    }

    @Test
    void ownOksTerminalExemptionDoesNotPermitTrunkAlongItsFacade() throws Exception {
        Coordinate root = UTM.point(-100, -11), elbow = UTM.point(20, -11);
        Coordinate port = UTM.point(20, 0), demand = UTM.point(-1, 0);
        Fixture fixture = new Fixture(UTM, root,
                List.of(feature("own", "oks", UTM.rectangle(-10, -10, 0, 10))));
        OrthogonalCorridorGrid grid = fixture.grid(List.of(elbow, port));
        assertThat(fixture.checks.edgeAllowed(root, elbow)).isFalse();
        // Намеренно подаём запрещённое звено: компрессия обязана сохранить защиту ствола,
        // даже когда отдельный spur законно входит в тот же собственный ОКС.
        List<int[]> selected = linksAlong(grid, List.of(root, elbow, port));
        assertThat(compress(selected, grid, port, generatedSpur(fixture, port, demand), fixture, FLOW)).isNull();
    }

    @Test
    void rootInsideSetbackCannotExitOrFollowFacadeButLegalBoundaryRootCanConnect() throws Exception {
        Coordinate root = UTM.point(-2, 10), port = UTM.point(-20, 10), demand = UTM.point(-30, 10);
        Fixture fixture = new Fixture(UTM, root,
                List.of(feature("building", "oks", UTM.rectangle(0, 0, 20, 20))));
        OrthogonalCorridorGrid grid = fixture.grid(List.of(port));
        assertThat(fixture.checks.pointAllowed(root)).isFalse();
        assertThat(fixture.checks.edgeAllowed(root, port)).isFalse();
        assertThat(compress(linksAlong(grid, List.of(root, port)), grid, port,
                checkedSpur(fixture, port, demand), fixture, FLOW)).isNull();

        Coordinate unsafePort = UTM.point(-2, 30), unsafeDemand = UTM.point(-2, 40);
        OrthogonalCorridorGrid unsafeGrid = fixture.grid(List.of(unsafePort));
        assertThat(fixture.checks.edgeAllowed(root, unsafePort)).isFalse();
        assertThat(compress(linksAlong(unsafeGrid, List.of(root, unsafePort)), unsafeGrid, unsafePort,
                checkedSpur(fixture, unsafePort, unsafeDemand), fixture, FLOW)).isNull();

        // ДУ100: ровно 5 + 0,510/2 м до стены; обычный законный корень сохраняет связность.
        Coordinate legalRoot = UTM.point(-5.255, 10);
        Fixture legal = new Fixture(UTM, legalRoot, fixture.features);
        OrthogonalCorridorGrid legalGrid = legal.grid(List.of(port));
        assertThat(legal.checks.pointAllowed(legalRoot)).isTrue();
        assertThat(legal.checks.edgeAllowed(legalRoot, port)).isTrue();
        assertNetwork(compress(tree(legalGrid, port), legalGrid, port,
                checkedSpur(legal, port, demand), legal, FLOW), legal, demand, 24.745);
    }

    @Test
    void wholeRoadAdmissionUsesFlowDiameterInsteadOfProvisionalGridDiameter() throws Exception {
        Coordinate root = UTM.point(0, 0), port = UTM.point(100, 0), demand = UTM.point(110, 0);
        Geometry roads = GEOMETRIES.createMultiPolygon(new Polygon[] {
                UTM.rectangle(40, -100, 60, 100), UTM.rectangle(80, 1.8, 90, 10)});
        Fixture fixture = roadFixture(UTM, root, roads);
        OrthogonalCorridorGrid grid = fixture.grid(List.of(port));
        assertThat(fixture.checks.path(List.of(root, port, demand))).isNotNull();
        BigDecimal actualFlow = new BigDecimal("100");
        int actualDiameter = new OfficialPipeCatalog().minimumForFlow(actualFlow).orElseThrow().getDiameter();
        assertThat(actualDiameter).isEqualTo(200);
        double clearance = rules.preparationClearanceM("road", actualDiameter).doubleValue();
        assertThat(new RoadCrossingClearance().assess(rules.line(List.of(root, port, demand)), roads,
                clearance, 45, 3).getFailureCode()).isEqualTo("SPECIAL_PARALLEL_CLEARANCE_VIOLATION");
        assertThat(compress(tree(grid, port), grid, port, checkedSpur(fixture, port, demand), fixture, actualFlow))
                .isNull();
    }

    private void assertDirectCandidate(Frame frame) throws Exception {
        Coordinate root = frame.point(-10, 3), port = frame.point(120, 3), demand = frame.point(130, 3);
        Fixture fixture = roadFixture(frame, root, frame.trapezoid(false));
        assertThat(fixture.checks.edgeAllowed(root, port)).isTrue();
        assertThat(fixture.checks.edgeAllowed(port, root)).isFalse();
        assertThat(fixture.checks.path(List.of(root, port))).isNotNull();
        OrthogonalCorridorGrid grid = fixture.grid(List.of(port));
        assertThat(hasLink(grid, grid.rootIndex(), index(grid, port))).as("F1: direct 130 m link must survive")
                .isTrue();
        List<int[]> selected = tree(grid, port);
        assertThat(treeLength(selected, grid)).isCloseTo(130, offset(1e-6));
        RouteEdge edge = assertNetwork(compress(selected, grid, port, checkedSpur(fixture, port, demand),
                fixture, FLOW), fixture, demand, 140);
        assertThat(edge.getSections()).filteredOn(section -> "special".equals(section.getKind()))
                .singleElement().satisfies(section ->
                        assertThat(section.getCrossingAngleDegrees()).isEqualByComparingTo("90"));
    }

    private void assertRootedCompression(Frame frame) throws Exception {
        Coordinate root = frame.point(130, 3), port = frame.point(0, 3), demand = frame.point(-10, 3);
        Fixture fixture = roadFixture(frame, root, frame.trapezoid(true));
        OrthogonalCorridorGrid grid = fixture.grid(List.of(port));
        List<int[]> selected = tree(grid, port);
        assertThat(selected).hasSize(1);
        assertThat(treeLength(selected, grid)).isCloseTo(130, offset(1e-6));
        assertThat(fixture.checks.path(List.of(root, port))).isNotNull();
        assertThat(fixture.checks.path(List.of(port, root))).isNull();
        RouteEdge edge = assertNetwork(compress(selected, grid, port, checkedSpur(fixture, port, demand),
                fixture, FLOW), fixture, demand, 140);
        assertThat(edge.getSections()).filteredOn(section -> "special".equals(section.getKind()))
                .singleElement().satisfies(section ->
                        assertThat(section.getCrossingAngleDegrees()).isEqualByComparingTo("90"));
    }

    private void assertSubdividedCrossing(Frame frame) throws Exception {
        Coordinate root = frame.point(0, 0), port = frame.point(100, 0), demand = frame.point(110, 0);
        Fixture fixture = roadFixture(frame, root, frame.rectangle(40, -100, 60, 100));
        List<Coordinate> points = List.of(root, frame.point(30, 0), frame.point(50, 0), frame.point(70, 0), port);
        OrthogonalCorridorGrid grid = fixture.grid(points.subList(1, points.size()));
        List<int[]> selected = tree(grid, port);
        assertThat(selected).hasSize(4);
        assertThat(treeLength(selected, grid)).isCloseTo(100, offset(1e-6));
        for (int[] link : selected) {
            Coordinate from = grid.points().get(link[0]), to = grid.points().get(link[1]);
            assertThat(fixture.checks.edgeAllowed(from, to)).isTrue();
            assertThat(fixture.checks.edgeAllowed(to, from)).isTrue();
        }
        assertThat(fixture.checks.path(List.of(points.get(1), points.get(2)))).isNull();
        assertThat(fixture.checks.path(List.of(points.get(2), points.get(3)))).isNull();
        assertThat(fixture.checks.path(points)).isNotNull();
        RouteEdge edge = assertNetwork(compress(selected, grid, port, checkedSpur(fixture, port, demand),
                fixture, FLOW), fixture, demand, 110);
        assertSpecial(edge, 26, frame.point(37, 0), frame.point(63, 0));
    }

    private void assertWrongEntrance(Frame frame) throws Exception {
        Coordinate root = frame.point(0, 3), port = frame.point(130, 3), demand = frame.point(140, 3);
        Fixture fixture = roadFixture(frame, root, frame.trapezoid(true));
        OrthogonalCorridorGrid grid = fixture.grid(List.of(port));
        assertThat(fixture.checks.edgeAllowed(root, port)).isFalse();
        assertThat(fixture.checks.edgeAllowed(port, root)).isTrue();
        assertThat(hasLink(grid, grid.rootIndex(), index(grid, port))).isTrue();
        List<int[]> selected = tree(grid, port);
        assertThat(selected).hasSize(1);
        assertThat(assessment(List.of(root, port, demand), fixture.features.get(0).getMetricGeometry())
                .getFailureCode()).isEqualTo("SPECIAL_CROSSING_ANGLE_VIOLATION");
        assertThat(compress(selected, grid, port, checkedSpur(fixture, port, demand), fixture, FLOW))
                .as("candidate union cannot license the opposite physical entrance").isNull();
    }

    private Fixture roadFixture(Frame frame, Coordinate root, Geometry road) {
        return new Fixture(frame, root, List.of(feature("road", "road", road)));
    }

    private RoutePath checkedSpur(Fixture fixture, Coordinate port, Coordinate demand) {
        RoutePath path = fixture.checks.path(List.of(port, demand));
        assertThat(path).as("independently checked terminal piece").isNotNull();
        return path;
    }

    private RoutePath generatedSpur(Fixture fixture, Coordinate port, Coordinate demand) {
        CorridorTerminalRouter terminals = new CorridorTerminalRouter(router, fixture.environment,
                (id, point, diameter, avoidance) -> null, fixture.frame.angle());
        RoutePath outward = terminals.route("fixture", demand, port, DIAMETER);
        assertThat(outward).as("existing local generator must validate own-OKS normal").isNotNull();
        return outward.reversed();
    }

    private OrthogonalCorridorNetworkBuilder.Network compress(List<int[]> selected, OrthogonalCorridorGrid grid,
            Coordinate port, RoutePath spur, Fixture fixture, BigDecimal flow) throws Exception {
        Class<?> portType = Class.forName(OrthogonalCorridorNetworkBuilder.class.getName() + "$Port");
        Constructor<?> constructor = portType.getDeclaredConstructor(
                OrthogonalCorridorNetworkBuilder.Terminal.class, int.class, RoutePath.class);
        constructor.setAccessible(true);
        Coordinate demand = spur.coordinates().get(spur.coordinates().size() - 1);
        Object selectedPort = constructor.newInstance(new OrthogonalCorridorNetworkBuilder.Terminal(
                "fixture", "connection", demand, flow), index(grid, port), spur);
        Method method = OrthogonalCorridorNetworkBuilder.class.getDeclaredMethod("compress", List.class,
                OrthogonalCorridorGrid.class, List.class, RouteNode.class, PreparedCorridor.class,
                OfficialRoutingEnvironment.class);
        method.setAccessible(true);
        return (OrthogonalCorridorNetworkBuilder.Network) method.invoke(builder, selected, grid,
                List.of(selectedPort), fixture.rootNode, fixture.checks, fixture.environment);
    }

    private RouteEdge assertNetwork(OrthogonalCorridorNetworkBuilder.Network network, Fixture fixture,
            Coordinate demand, double expectedLength) {
        assertThat(network).as("root-oriented complete crossing must compress").isNotNull();
        assertThat(network.nodes()).hasSize(2);
        assertThat(network.edges()).hasSize(1);
        assertThat(network.connections()).hasSize(1);
        assertThat(new OfficialRouteValidator(rules).validate(network.nodes(), network.edges(), fixture.features))
                .extracting(RouteValidationIssue::getCode).isEmpty();
        assertThat(new EngineeringRouteEvaluator().evaluate(network.edges()).isCompliant()).isTrue();
        RouteEdge edge = network.edges().get(0);
        List<Coordinate> points = edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                .collect(Collectors.toList());
        assertThat(points.get(0)).isEqualTo(fixture.rootNode.getCoordinate().toCoordinate());
        assertThat(points.get(points.size() - 1)).isEqualTo(new RouteCoordinate(demand.x, demand.y).toCoordinate());
        assertThat(edge.getUpstreamNodeId()).isEqualTo("root");
        assertThat(edge.getDownstreamNodeId()).isEqualTo("demand:fixture");
        assertThat(edge.getFlowTph()).isEqualByComparingTo(FLOW);
        assertThat(edge.getDiameter()).isEqualTo(DIAMETER);
        assertThat(edge.getLengthM().doubleValue()).isCloseTo(expectedLength, offset(0.002));
        assertThat(rules.line(points).getLength()).isCloseTo(expectedLength, offset(0.002));
        assertThat(edge.getSections().stream().mapToDouble(section -> section.getLengthM().doubleValue()).sum())
                .isCloseTo(expectedLength, offset(0.002));
        Coordinate previous = points.get(0);
        for (RouteSection section : edge.getSections()) {
            List<RouteCoordinate> coordinates = section.getCoordinates();
            assertThat(coordinates.get(0).toCoordinate()).isEqualTo(previous);
            previous = coordinates.get(coordinates.size() - 1).toCoordinate();
        }
        assertThat(previous).isEqualTo(points.get(points.size() - 1));
        return edge;
    }

    private void assertSpecial(RouteEdge edge, double length, Coordinate start, Coordinate end) {
        assertThat(edge.getSections()).filteredOn(section -> "special".equals(section.getKind()))
                .singleElement().satisfies(section -> {
                    assertThat(section.getRestrictionId()).isEqualTo("road");
                    assertThat(section.getRestrictionType()).isEqualTo("road");
                    assertThat(section.getLengthM().doubleValue()).isCloseTo(length, offset(0.001));
                    assertThat(section.getCrossingAngleDegrees()).isEqualByComparingTo("90");
                    assertThat(section.getCoordinates().get(0).toCoordinate())
                            .isEqualTo(new RouteCoordinate(start.x, start.y).toCoordinate());
                    assertThat(section.getCoordinates().get(section.getCoordinates().size() - 1).toCoordinate())
                            .isEqualTo(new RouteCoordinate(end.x, end.y).toCoordinate());
                });
    }

    private RoadCrossingClearance.Assessment assessment(List<Coordinate> points, Geometry road) {
        return new RoadCrossingClearance().assess(rules.line(points), road, 1.755, 45, 3);
    }

    private List<int[]> tree(OrthogonalCorridorGrid grid, Coordinate port) {
        List<int[]> selected = new CorridorTreeBuilder().build(grid.points(), grid.links(), grid.rootIndex(),
                2, Map.of(index(grid, port), 1), false, 0, 0);
        assertThat(selected).as("bounded candidate tree").isNotNull();
        return selected;
    }

    private List<int[]> linksAlong(OrthogonalCorridorGrid grid, List<Coordinate> points) {
        List<int[]> selected = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            selected.add(new int[] {index(grid, points.get(i - 1)), index(grid, points.get(i))});
        }
        return selected;
    }

    private int index(OrthogonalCorridorGrid grid, Coordinate point) {
        for (int i = 0; i < grid.points().size(); i++) {
            if (grid.points().get(i).distance(point) < 1e-6) return i;
        }
        throw new AssertionError("Missing fixture grid point: " + point);
    }

    private boolean hasLink(OrthogonalCorridorGrid grid, int a, int b) {
        return grid.links().stream().anyMatch(link -> link[0] == a && link[1] == b || link[0] == b && link[1] == a);
    }

    private double treeLength(List<int[]> selected, OrthogonalCorridorGrid grid) {
        return selected.stream().mapToDouble(link -> grid.points().get(link[0]).distance(grid.points().get(link[1])))
                .sum();
    }

    private List<Coordinate> copyCoordinates(RoutePath path) {
        return path.coordinates().stream().map(Coordinate::new).collect(Collectors.toList());
    }

    private ImportedOfficialFeature feature(String id, String type, Geometry geometry) {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", type), geometry);
    }

    private final class Fixture {
        private final Frame frame;
        private final List<ImportedOfficialFeature> features;
        private final OfficialRoutingEnvironment environment;
        private final RouteNode rootNode;
        private final PreparedCorridor checks;

        private Fixture(Frame frame, Coordinate root, List<ImportedOfficialFeature> features) {
            this.frame = frame;
            this.features = features;
            environment = router.prepare(features);
            rootNode = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(root.x, root.y),
                    true, true, 2, null);
            Envelope bounds = new Envelope(root);
            features.forEach(feature -> bounds.expandToInclude(feature.getMetricGeometry().getEnvelopeInternal()));
            bounds.expandBy(150);
            checks = router.prepareCorridor(DIAMETER, environment, bounds, root, null);
        }

        private OrthogonalCorridorGrid grid(List<Coordinate> anchors) {
            return OrthogonalCorridorGrid.build(rootNode.getCoordinate().toCoordinate(), anchors, List.of(), 2,
                    checks::pointAllowed, checks::edgeAllowed, frame.angle());
        }
    }

    private static final class Frame {
        private final double x, y, cosine, sine;

        private Frame(double x, double y, double cosine, double sine) {
            this.x = x; this.y = y; this.cosine = cosine; this.sine = sine;
        }

        private double angle() { return Math.atan2(sine, cosine); }

        private Coordinate point(double localX, double localY) {
            return new Coordinate(x + cosine * localX - sine * localY, y + sine * localX + cosine * localY);
        }

        private Polygon rectangle(double x1, double y1, double x2, double y2) {
            return GEOMETRIES.createPolygon(new Coordinate[] {
                    point(x1, y1), point(x2, y1), point(x2, y2), point(x1, y2), point(x1, y1)});
        }

        private Polygon trapezoid(boolean mirror) {
            double end = 100 + 6 / Math.tan(Math.toRadians(40));
            double[][] local = {{0, 0}, {100, 0}, {end, 6}, {0, 6}, {0, 0}};
            Coordinate[] coordinates = new Coordinate[local.length];
            for (int i = 0; i < local.length; i++) {
                coordinates[i] = point(mirror ? 120 - local[i][0] : local[i][0], local[i][1]);
            }
            return GEOMETRIES.createPolygon(coordinates);
        }
    }
}
