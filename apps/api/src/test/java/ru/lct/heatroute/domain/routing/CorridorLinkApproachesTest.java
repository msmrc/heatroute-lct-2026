package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class CorridorLinkApproachesTest {
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));

    @Test
    void suppliesDiverseOrthogonalApproachesWithoutChangingSource() {
        RouteEdge source = edge(0, 0, 20, 0, 20, 20);
        List<RouteCoordinate> before = List.copyOf(source.getCoordinates());
        Coordinate junction = new Coordinate(25, 20);
        List<RoutePath> paths = CorridorLinkApproaches.build(source, node(0, 0), junction, 0, router, router.prepare(List.of()));
        assertThat(paths).hasSizeBetween(2, 8);
        assertThat(paths).allSatisfy(p -> {
            assertThat(p.coordinates().get(0)).isEqualTo(new Coordinate(0, 0));
            assertThat(p.coordinates().get(p.coordinates().size() - 1)).isEqualTo(junction);
            assertThat(line(p).isSimple()).isTrue();
        });
        assertThat(paths.stream().map(this::bearing).distinct().count()).isGreaterThanOrEqualTo(2);
        assertThat(source.getCoordinates()).containsExactlyElementsOf(before);
    }

    @Test
    void everyAlternativeRespectsAnotherBuilding() throws Exception {
        ImportedOfficialFeature obstacle = new ImportedOfficialFeature("foreign", "oks_existing",
                new ObjectMapper().readTree("{}"), new WKTReader().read("POLYGON ((10 -3,14 -3,14 3,10 3,10 -3))"));
        List<RoutePath> paths = CorridorLinkApproaches.build(edge(0, 0, 0, 20, 25, 20), node(0, 0),
                new Coordinate(25, 25), 0, router, router.prepare(List.of(obstacle)));
        assertThat(paths).isNotEmpty();
        assertThat(paths).allSatisfy(p -> assertThat(line(p).intersects(obstacle.getMetricGeometry())).isFalse());
    }

    @Test
    void newChamberInsideBuildingSetbackCannotUseAnExistingRootException() throws Exception {
        ImportedOfficialFeature obstacle = new ImportedOfficialFeature("nearby", "oks_existing",
                new ObjectMapper().readTree("{}"), new WKTReader().read("POLYGON ((1 -1,3 -1,3 1,1 1,1 -1))"));
        assertThat(CorridorLinkApproaches.build(edge(0, 0, -20, 0), node(0, 0), new Coordinate(-25, 5),
                0, router, router.prepare(List.of(obstacle)))).isEmpty();
    }

    @Test
    void reversedEdgeAndRotatedFrameRemainUsable() {
        for (double angle : new double[] {0.0, 0.2, 1.5, 3.8}) {
            Coordinate origin = new Coordinate(400000, 6000000);
            Coordinate first = transformed(20, 0, origin, angle), last = transformed(20, 20, origin, angle);
            RouteEdge forward = edge(origin.x, origin.y, first.x, first.y, last.x, last.y);
            List<RouteCoordinate> reversed = new ArrayList<>(forward.getCoordinates());
            java.util.Collections.reverse(reversed);
            RouteEdge reverse = new RouteEdge("link", "joint", "outer", forward.getLengthM().doubleValue(),
                    reversed, List.of(), BigDecimal.ONE, 100);
            List<RoutePath> paths = CorridorLinkApproaches.build(reverse, node(origin.x, origin.y),
                    transformed(25, 20, origin, angle), angle, router, router.prepare(List.of()));
            assertThat(paths).isNotEmpty();
            assertThat(paths).allSatisfy(p -> assertThat(line(p).isSimple()).isTrue());
        }
    }

    @Test
    void unrelatedNodeAndInvalidOrientationAreNotSilentlyAccepted() {
        RouteEdge source = edge(0, 0, 20, 0);
        assertThatThrownBy(() -> CorridorLinkApproaches.build(source,
                new RouteNode("wrong", "new_branch_chamber", new RouteCoordinate(0, 0), true, false, 0, null),
                new Coordinate(25, 0), 0, router, router.prepare(List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorridorLinkApproaches.build(source, node(0, 0), new Coordinate(25, 0),
                Double.NaN, router, router.prepare(List.of()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void honorsCancellationBeforeGeometryWork() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> CorridorLinkApproaches.build(edge(0, 0, 20, 0), node(0, 0),
                    new Coordinate(25, 0), 0, router, router.prepare(List.of()))).isInstanceOf(CancellationException.class);
        } finally { Thread.interrupted(); }
    }

    private RouteEdge edge(double... xy) {
        List<RouteCoordinate> points = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) points.add(new RouteCoordinate(xy[i], xy[i + 1]));
        double length = 0;
        for (int i = 1; i < points.size(); i++) length += points.get(i - 1).toCoordinate().distance(points.get(i).toCoordinate());
        return new RouteEdge("link", "outer", "joint", length, points, List.of(), BigDecimal.ONE, 100);
    }

    private RouteNode node(double x, double y) {
        return new RouteNode("outer", "new_branch_chamber", new RouteCoordinate(x, y), true, false, 0, null);
    }

    private LineString line(RoutePath path) { return new GeometryFactory().createLineString(path.coordinates().toArray(new Coordinate[0])); }
    private int bearing(RoutePath path) {
        Coordinate a = path.coordinates().get(path.coordinates().size() - 2), b = path.coordinates().get(path.coordinates().size() - 1);
        return (int) Math.round(Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI);
    }
    private Coordinate transformed(double x, double y, Coordinate origin, double angle) {
        return new Coordinate(origin.x + x * Math.cos(angle) - y * Math.sin(angle), origin.y + x * Math.sin(angle) + y * Math.cos(angle));
    }
}
