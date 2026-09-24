package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialObstacleRouterConcavityTest {
    private static final String COURTYARD = "POLYGON ((0 0,100 0,100 100,60 100,60 20,40 20,40 100,0 100,0 0))";
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void escapesOpenCourtyardWhoseHullVerticesAreNotVisible() throws Exception {
        ImportedOfficialFeature building = building(new WKTReader().read(COURTYARD));
        Coordinate start = new Coordinate(50, 35), end = new Coordinate(50, -40);
        assertValidRoute(building, start, end, RoutePreference.SHORTEST);
        assertValidRoute(building, start, end, RoutePreference.ENGINEERING);
    }

    @Test
    void entersAndLeavesRotatedCourtyardInMetricCoordinates() throws Exception {
        for (double angle : new double[] {0.37, 1.1, 2.7}) {
            AffineTransformation transform = AffineTransformation.rotationInstance(angle).translate(410000, 6180000);
            ImportedOfficialFeature building = building(transform.transform(new WKTReader().read(COURTYARD)));
            Coordinate inside = transform.transform(new Coordinate(50, 35), new Coordinate());
            Coordinate outside = transform.transform(new Coordinate(50, -40), new Coordinate());
            assertValidRoute(building, inside, outside, RoutePreference.SHORTEST);
            assertValidRoute(building, outside, inside, RoutePreference.SHORTEST);
        }
    }

    @Test
    void doesNotInventExitFromFullyEnclosedCourtyard() throws Exception {
        ImportedOfficialFeature building = building(new WKTReader().read(
                "POLYGON ((0 0,100 0,100 100,0 100,0 0),(30 30,30 70,70 70,70 30,30 30))"));
        assertThat(router.find(new Coordinate(50, 50), new Coordinate(50, -40), 100,
                List.of(building), Set.of(), RoutePreference.SHORTEST)).isNull();
    }

    @Test
    void preservesOldSupportVerticesAndBoundsOnlyAdditionalPocketCandidates() throws Exception {
        ImportedOfficialFeature building = building(new WKTReader().read(COURTYARD));
        Coordinate start = new Coordinate(50, 35), end = new Coordinate(50, -40);
        List<OfficialRouteGeometryRules.Constraint> constraints = rules.baseConstraints(List.of(building), 100);
        Geometry hull = constraints.get(0).blocked().buffer(0.25, 2).convexHull();
        Method supportMethod = OfficialObstacleRouter.class.getDeclaredMethod("navigationCoordinates", Geometry.class);
        supportMethod.setAccessible(true);
        Coordinate[] oldSupport = (Coordinate[]) supportMethod.invoke(router, hull);
        List<Coordinate> nodes = navigationNodes(start, end, constraints);
        for (Coordinate old : oldSupport) {
            assertThat(nodes).anySatisfy(point -> assertThat(point.distance(old)).isLessThan(1e-8));
        }
        assertThat(nodes.size()).isGreaterThan(oldSupport.length + 1);
        // 2 терминала + 12 старых опор + 64 точки контура + до 24 проекций двух терминалов.
        assertThat(nodes).hasSizeLessThanOrEqualTo(102);
    }

    @Test
    void doesNotExpandNavigationForOrdinaryConvexObstacle() throws Exception {
        ImportedOfficialFeature building = building(new WKTReader().read("POLYGON ((0 0,100 0,100 100,0 100,0 0))"));
        for (double startX : new double[] {-30, -5.1}) {
            List<Coordinate> nodes = navigationNodes(new Coordinate(startX, 50), new Coordinate(130, 50),
                    rules.baseConstraints(List.of(building), 100));
            assertThat(nodes).hasSizeLessThanOrEqualTo(14);
        }
    }

    private void assertValidRoute(ImportedOfficialFeature building, Coordinate start, Coordinate end,
            RoutePreference preference) {
        RoutePath route = router.find(start, end, 100, List.of(building), Set.of(), preference);
        assertThat(route).as("open courtyard, preference %s", preference).isNotNull();
        assertThat(route.coordinates().get(0).distance(start)).isLessThan(0.002);
        assertThat(route.coordinates().get(route.coordinates().size() - 1).distance(end)).isLessThan(0.002);
        LineString line = rules.line(route.coordinates());
        assertThat(line.isSimple()).isTrue();
        assertThat(line.distance(building.getMetricGeometry())).isGreaterThanOrEqualTo(4.99);
        assertThat(route.lengthM()).isLessThan(500);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber", new RouteCoordinate(start.x, start.y), true, true, 2, null),
                new RouteNode("demand", "demand_connection", new RouteCoordinate(end.x, end.y), false, false, 0, null));
        RouteEdge edge = new RouteEdge("route", "root", "demand", route.lengthM(), route.coordinates().stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList()),
                route.sections(), java.math.BigDecimal.ONE, 100);
        assertThat(new OfficialRouteValidator(rules).validate(nodes, List.of(edge), List.of(building))).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Coordinate> navigationNodes(Coordinate start, Coordinate end,
            List<OfficialRouteGeometryRules.Constraint> constraints) throws Exception {
        Method method = OfficialObstacleRouter.class.getDeclaredMethod("navigationNodes", Coordinate.class,
                Coordinate.class, OfficialRouteGeometryRules.ConstraintIndex.class, double.class);
        method.setAccessible(true);
        return (List<Coordinate>) method.invoke(router, start, end, rules.index(constraints), 75.0);
    }

    private ImportedOfficialFeature building(Geometry geometry) throws Exception {
        return new ImportedOfficialFeature("courtyard", "restriction",
                new ObjectMapper().readTree("{\"restriction_type\":\"oks\"}"), geometry);
    }
}
