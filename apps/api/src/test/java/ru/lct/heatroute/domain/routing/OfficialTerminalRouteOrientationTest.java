package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Вход road/tram определяется сохранённым root→demand, а не направлением построения ввода. */
class OfficialTerminalRouteOrientationTest {
    private static final int DIAMETER = 100;
    private final OfficialConstraintCatalog catalog = new OfficialConstraintCatalog();
    private final OfficialAxisClearance axis = new OfficialAxisClearance(new OfficialPipeCatalog(), catalog);
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            catalog, new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);
    private final RoadCrossingClearance roadGuard = new RoadCrossingClearance();
    private final GeometryFactory factory = new GeometryFactory();

    @Test
    void shallowStoredEntryMustNotEscapeTerminalBuilder() throws Exception {
        assertSafeAlternative(new Fixture(false, 40, 90));
    }

    @Test
    void shallowStoredExitDoesNotForceADetour() throws Exception {
        Fixture fixture = new Fixture(false, 90, 40);
        assertStoredSections(fixture, storedEdge(assertShortestDirect(fixture)));
    }

    @Test
    void roadInsideTerminalMustKeepTheLegalIncomingNormal() throws Exception {
        Fixture fixture = new Fixture(true, 90, 40);
        OfficialRouteGeometryRules.NormalEgress expected = fixture.ownOnlyNormal();
        OfficialRouteGeometryRules.NormalEgress selected = fixture.selectedNormal();
        assertThat(roadCode(fixture, fixture.direct())).isEqualTo("ALLOWED");

        RoutePath outward = planned(fixture, selected);
        assertValid(fixture, storedEdge(outward));
        assertThat(selected.exit().distance(expected.exit()))
                .as("Incoming road entry is legal; it must not discard the target-facing nearest wall")
                .isCloseTo(0, within(1e-6));
        assertThat(outward.lengthM()).isCloseTo(fixture.directLength(), within(1e-6));
        assertStoredSections(fixture, storedEdge(outward));
    }

    @Test
    void finalValidatorAcceptsLegalIncomingNormalAcrossTerminalRoad() {
        Fixture fixture = new Fixture(true, 90, 40);
        OfficialRouteGeometryRules.NormalEgress normal = fixture.ownOnlyNormal();
        List<Coordinate> incoming = List.of(fixture.root(), normal.exit(), fixture.demand());
        assertThat(roadCode(fixture, incoming)).isEqualTo("ALLOWED");
        assertThat(roadCode(fixture, rules.line(incoming).reverse()))
                .isEqualTo("SPECIAL_CROSSING_ANGLE_VIOLATION");

        assertValid(fixture, fixture.edge(incoming));
    }

    @Test
    void terminalBuilderAcceptsARealNormalWhoseIncomingCrossingIsLegal() throws Exception {
        Fixture fixture = new Fixture(true, 90, 40);
        // This is the actual own-OKS normal, not a mocked or manually enlarged exemption.
        OfficialRouteGeometryRules.NormalEgress normal = fixture.ownOnlyNormal();
        assertThat(roadCode(fixture, fixture.direct())).isEqualTo("ALLOWED");

        RoutePath outward = planned(fixture, normal);

        assertValid(fixture, storedEdge(outward));
        assertThat(outward.lengthM()).isCloseTo(fixture.directLength(), within(1e-6));
        assertStoredSections(fixture, storedEdge(outward));
    }

    @Test
    void terminalRoadCannotAdmitANormalWithShallowIncomingEntry() {
        Fixture fixture = new Fixture(true, 40, 90);
        OfficialRouteGeometryRules.NormalEgress outwardOnly = fixture.ownOnlyNormal();
        assertThat(roadCode(fixture, fixture.direct()))
                .isEqualTo("SPECIAL_CROSSING_ANGLE_VIOLATION");
        assertThat(roadCode(fixture, rules.line(fixture.direct()).reverse())).isEqualTo("ALLOWED");
        assertThat(codes(fixture, fixture.edge(fixture.direct())))
                .contains("SPECIAL_CROSSING_ANGLE_VIOLATION");

        assertThat(fixture.selectedNormal().exit().distance(outwardOnly.exit()))
                .as("A legal outward entry cannot authorize the shallow stored entry")
                .isGreaterThan(1);
    }

    @Test
    void quarterTurnAtUtmCoordinatesPreservesTheStoredEntryRequirement() throws Exception {
        assertSafeAlternative(new Fixture(false, 40, 90, 1, false, "road"));
    }

    @Test
    void reflectedLayoutStillAllowsAShallowStoredExit() throws Exception {
        Fixture fixture = new Fixture(false, 90, 40, 0, true, "road");
        assertStoredSections(fixture, storedEdge(assertShortestDirect(fixture)));
    }

    @Test
    void catalogMinimumStoredEntryAngleAllowsTheDirectRoute() throws Exception {
        double minimum = catalog.find("road").orElseThrow().getMinimumCrossingAngleDegrees().doubleValue();
        assertShortestDirect(new Fixture(false, minimum, 90));
    }

    @Test
    void justBelowCatalogMinimumStoredEntryRequiresALegalAlternative() throws Exception {
        double minimum = catalog.find("road").orElseThrow().getMinimumCrossingAngleDegrees().doubleValue();
        assertSafeAlternative(new Fixture(false, minimum - 0.1, 90));
    }

    @Test
    void minimumStoredEntryDoesNotAddAnExitAngleRequirement() throws Exception {
        double minimum = catalog.find("road").orElseThrow().getMinimumCrossingAngleDegrees().doubleValue();
        assertShortestDirect(new Fixture(false, minimum, 40));
    }

    @Test
    void finalValidatorRejectsShallowStoredEntryDespiteLegalOutwardEntry() {
        Fixture fixture = new Fixture(false, 40, 90);
        assertThat(roadCode(fixture, rules.line(fixture.direct()).reverse())).isEqualTo("ALLOWED");

        assertThat(codes(fixture, fixture.edge(fixture.direct())))
                .containsExactly("SPECIAL_CROSSING_ANGLE_VIOLATION");
    }

    @Test
    void reversingTerminalDirectionDoesNotInventProtectionBeyondTheDemand() {
        Fixture fixture = new Fixture(true, 90, 90);
        // Move the near road towards the demand: only 2 m remain after its stored exit.
        ImportedOfficialFeature road = fixture.polygon("road", "road",
                new double[][] {{1, -1}, {10, -1}, {10, 1}, {1, 1}, {1, -1}});
        List<ImportedOfficialFeature> features = List.of(fixture.own, road);
        RouteEdge edge = fixture.edge(fixture.direct());

        assertThat(assess(rules.line(fixture.direct()), road).getFailureCode())
                .isEqualTo("SPECIAL_CROSSING_EXTENSION_MISSING");
        assertThat(validator.validate(fixture.nodes(), List.of(edge), features))
                .extracting(RouteValidationIssue::getCode).contains("SPECIAL_CROSSING_EXTENSION_MISSING");
    }

    @Test
    void noRoadControlRetainsOutwardNormalAndDemandToRootBuilderContract() throws Exception {
        Fixture fixture = new Fixture(false, 90, 90);
        RoutePath outward = planned(fixture, fixture.ownOnlyNormal(), router.prepare(List.of(fixture.own)));

        assertThat(outward.lengthM()).isCloseTo(fixture.directLength(), within(1e-6));
        // The constructed normal remains outward; the caller stores the reversed edge.
        assertThat(fixture.ownOnlyNormal().start()).isEqualTo(fixture.demand());
        assertThat(validator.validate(fixture.nodes(), List.of(storedEdge(outward)), List.of(fixture.own)))
                .isEmpty();
    }

    @Test
    void tramStoredEntryUsesTheSameDirectionalContract() throws Exception {
        assertSafeAlternative(new Fixture(false, 40, 90, 0, false, "tram_tracks"));
    }

    @Test
    void storedSectionsUseIncomingAngleForALegalAsymmetricCrossing() throws Exception {
        assertStoredSections(new Fixture(false, 60, 90));
    }

    @Test
    void mirroredStoredSectionsUseIncomingAngleForALegalAsymmetricCrossing() throws Exception {
        assertStoredSections(new Fixture(false, 60, 90, 0, true, "road"));
    }

    @Test
    void terminalSplitSectionsMatchTheWholeIncomingCrossing() throws Exception {
        assertStoredSections(new Fixture(true, 90, 60));
    }

    private void assertStoredSections(Fixture fixture) throws Exception {
        assertThat(roadCode(fixture, fixture.direct())).isEqualTo("ALLOWED");
        assertValid(fixture, fixture.edge(fixture.direct()));
        RoutePath outward = planned(fixture, fixture.selectedNormal());
        RouteEdge stored = storedEdge(outward);
        assertValid(fixture, stored);
        assertThat(outward.lengthM()).isCloseTo(fixture.directLength(), within(1e-6));
        assertStoredSections(fixture, stored);
    }

    private void assertStoredSections(Fixture fixture, RouteEdge stored) {
        LineString actualStoredLine = rules.line(stored.getCoordinates().stream()
                .map(RouteCoordinate::toCoordinate).collect(Collectors.toList()));
        List<RouteSection> expected = rules.sections(actualStoredLine,
                rules.baseConstraints(fixture.features, DIAMETER));
        assertThat(expected).filteredOn(section -> "special".equals(section.getKind())).hasSize(1);

        // Compare all metadata and geometry: reversal must not preserve the outward entry angle.
        assertThat(stored.getSections()).as("Sections recomputed for the actual stored root-to-demand line")
                .usingRecursiveComparison().isEqualTo(expected);
    }

    private void assertSafeAlternative(Fixture fixture) throws Exception {
        assertThat(roadCode(fixture, fixture.direct())).isEqualTo("SPECIAL_CROSSING_ANGLE_VIOLATION");
        assertThat(roadCode(fixture, rules.line(fixture.direct()).reverse())).isEqualTo("ALLOWED");
        // An independently validated 45-degree detour proves that null is not a sufficient repair.
        assertValid(fixture, fixture.edge(fixture.detour()));

        RoutePath outward = planned(fixture, fixture.selectedNormal());

        assertValid(fixture, storedEdge(outward));
    }

    private RoutePath assertShortestDirect(Fixture fixture) throws Exception {
        // The Euclidean lower bound is itself legal; a both-sides angle workaround must fail here.
        assertThat(roadCode(fixture, fixture.direct())).isEqualTo("ALLOWED");
        assertValid(fixture, fixture.edge(fixture.direct()));

        RoutePath outward = planned(fixture, fixture.selectedNormal());

        assertValid(fixture, storedEdge(outward));
        assertThat(outward.lengthM()).as("A legal root-to-demand straight route is the shortest route")
                .isCloseTo(fixture.directLength(), within(1e-6));
        return outward;
    }

    private RoutePath planned(Fixture fixture, OfficialRouteGeometryRules.NormalEgress normal) throws Exception {
        return planned(fixture, normal, router.prepare(fixture.features));
    }

    private RoutePath planned(Fixture fixture, OfficialRouteGeometryRules.NormalEgress normal,
            OfficialRoutingEnvironment environment) throws Exception {
        Method method = OfficialRoutePlanner.class.getDeclaredMethod("routeDemandWithEgress",
                OfficialRoutePlanner.Demand.class, OfficialRouteGeometryRules.NormalEgress.class,
                Coordinate.class, int.class, OfficialRoutingEnvironment.class, Set.class,
                RoutePreference.class, List.class);
        method.setAccessible(true);
        OfficialRoutePlanner.Demand demand = new OfficialRoutePlanner.Demand(
                "demand", "connection", fixture.demand(), BigDecimal.ONE, normal);
        RoutePath outward;
        try {
            outward = (RoutePath) method.invoke(new OfficialDatasetRoutingTest().planner(), demand, normal,
                    fixture.root(), DIAMETER, environment, Set.of(), RoutePreference.SHORTEST, List.of());
        } catch (InvocationTargetException exception) {
            throw new AssertionError("Actual terminal planner threw", exception.getCause());
        }
        assertThat(outward).as("A valid stored-direction route exists for this normal").isNotNull();
        assertThat(outward.coordinates().get(0)).isEqualTo(fixture.demand());
        assertThat(outward.coordinates().get(outward.coordinates().size() - 1)).isEqualTo(fixture.root());
        return outward;
    }

    private RouteEdge storedEdge(RoutePath outward) {
        RoutePath stored = outward.reversed();
        return new RouteEdge("edge", "root", "demand", stored.lengthM(),
                stored.coordinates().stream().map(point -> new RouteCoordinate(point.x, point.y))
                        .collect(Collectors.toList()),
                stored.sections(), BigDecimal.ONE, DIAMETER);
    }

    private void assertValid(Fixture fixture, RouteEdge edge) {
        assertThat(codes(fixture, edge)).as("Stored root-to-demand edge, length=%s, coordinates=%s",
                edge.getLengthM(), edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                        .collect(Collectors.toList())).isEmpty();
    }

    private List<String> codes(Fixture fixture, RouteEdge edge) {
        return validator.validate(fixture.nodes(), List.of(edge), fixture.features).stream()
                .map(RouteValidationIssue::getCode).collect(Collectors.toList());
    }

    private String roadCode(Fixture fixture, List<Coordinate> coordinates) {
        return roadCode(fixture, rules.line(coordinates));
    }

    private String roadCode(Fixture fixture, LineString line) {
        RoadCrossingClearance.Assessment result = assess(line, fixture.road);
        return result.isAllowed() ? "ALLOWED" : result.getFailureCode();
    }

    private RoadCrossingClearance.Assessment assess(LineString line, ImportedOfficialFeature road) {
        String type = road.getAttributes().path("restriction_type").asText();
        SpatialConstraintRule rule = catalog.find(type).orElseThrow();
        return roadGuard.assess(line, road.getMetricGeometry(), axis.axisClearanceM(type, DIAMETER, null)
                .doubleValue(), rule.getMinimumCrossingAngleDegrees().doubleValue(),
                rule.getSpecialExtensionM().doubleValue());
    }

    private final class Fixture {
        private final int quarterTurns;
        private final boolean mirrored;
        private final ImportedOfficialFeature own;
        private final ImportedOfficialFeature road;
        private final List<ImportedOfficialFeature> features;

        private Fixture(boolean terminal, double entryAngle, double exitAngle) {
            this(terminal, entryAngle, exitAngle, 0, false, "road");
        }

        private Fixture(boolean terminal, double entryAngle, double exitAngle, int quarterTurns,
                boolean mirrored, String type) {
            this.quarterTurns = quarterTurns;
            this.mirrored = mirrored;
            own = polygon("own", "oks", new double[][] {
                    {-2, -10}, {0, -10}, {0, 10}, {-2, 10}, {-2, -10}});
            // Root is on the right: its entry is the right wall, and its exit is the left wall.
            double left = terminal ? 4 : 30, right = terminal ? 10 : 70, halfWidth = terminal ? 1 : 3;
            double entryShift = 2 * halfWidth / Math.tan(Math.toRadians(entryAngle));
            double exitShift = 2 * halfWidth / Math.tan(Math.toRadians(exitAngle));
            road = polygon("road", type, new double[][] {{left, -halfWidth}, {right, -halfWidth},
                    {right + entryShift, halfWidth}, {left + exitShift, halfWidth}, {left, -halfWidth}});
            features = List.of(own, road);
        }

        private Coordinate point(double x, double y) {
            if (mirrored) x = -x;
            for (int turn = 0; turn < quarterTurns; turn++) {
                double previousX = x;
                x = -y;
                y = previousX;
            }
            return new Coordinate(500000 + x, 6170000 + y);
        }

        private Coordinate root() { return point(150, 0); }
        private Coordinate demand() { return point(-1, 0); }
        private List<Coordinate> direct() { return List.of(root(), demand()); }
        private double directLength() { return root().distance(demand()); }

        private OfficialRouteGeometryRules.NormalEgress ownOnlyNormal() {
            return router.prepare(List.of(own)).normalEgressTowards(DIAMETER, demand(), root()).orElseThrow();
        }

        private OfficialRouteGeometryRules.NormalEgress selectedNormal() {
            return router.prepare(features).normalEgressTowards(
                    DIAMETER, demand(), root(), RouteTraversal.REVERSED).orElseThrow();
        }

        private List<Coordinate> detour() {
            String type = road.getAttributes().path("restriction_type").asText();
            double height = 3 + axis.axisClearanceM(type, DIAMETER, null).doubleValue() + 0.25;
            OfficialRouteGeometryRules.NormalEgress normal = ownOnlyNormal();
            double exitX = -1 + normal.start().distance(normal.exit());
            return List.of(root(), point(150 - height, height), point(exitX + height, height),
                    normal.exit(), demand());
        }

        private RouteEdge edge(List<Coordinate> coordinates) {
            LineString line = rules.line(coordinates);
            return new RouteEdge("edge", "root", "demand", line.getLength(), coordinates.stream()
                    .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()),
                    rules.sections(line, rules.baseConstraints(features, DIAMETER)), BigDecimal.ONE, DIAMETER);
        }

        private List<RouteNode> nodes() {
            return List.of(new RouteNode("root", "existing_chamber_tie_in",
                            new RouteCoordinate(root().x, root().y), true, true, 2, null),
                    new RouteNode("demand", "demand_connection", new RouteCoordinate(demand().x, demand().y),
                            false, false, 0, null));
        }

        private ImportedOfficialFeature polygon(String id, String type, double[][] coordinates) {
            return new ImportedOfficialFeature(id, "restriction",
                    new ObjectMapper().createObjectNode().put("restriction_type", type),
                    factory.createPolygon(Arrays.stream(coordinates).map(point -> point(point[0], point[1]))
                            .toArray(Coordinate[]::new)));
        }
    }
}
