package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

class CorridorCompressionGeometryTest {
    private final GeometryFactory geometries = new GeometryFactory();
    private final OrthogonalCorridorNetworkBuilder builder = new OrthogonalCorridorNetworkBuilder(
            new OfficialObstacleRouter(new OfficialRouteGeometryRules(
                    new OfficialConstraintCatalog(), new OfficialCrossingGeometry())), new OfficialPipeCatalog());

    @Test
    void doesNotMoveRoundedGeometryAcrossAnotherBranchByRemovingAShallowVertex() {
        var points = List.of(new Coordinate(0, 0), new Coordinate(50, 0.001), new Coordinate(100, 0));
        var other = geometries.createLineString(new Coordinate[] {
                new Coordinate(50, -10), new Coordinate(50, 0)});
        var original = geometries.createLineString(points.toArray(new Coordinate[0]));
        assertThat(original.intersects(other)).isFalse();
        List<Coordinate> compressed = ReflectionTestUtils.invokeMethod(builder, "straightPointsRemoved", points);
        assertThat(geometries.createLineString(compressed.toArray(new Coordinate[0])).intersects(other)).isFalse();
        assertThat(compressed).containsExactlyElementsOf(points);
    }

    @Test
    void stillRemovesExactlyCollinearForwardVerticesButNeverAReversal() {
        var points = List.of(new Coordinate(0, 0), new Coordinate(50, 20), new Coordinate(100, 40));
        List<Coordinate> compressed = ReflectionTestUtils.invokeMethod(builder, "straightPointsRemoved", points);
        assertThat(compressed).containsExactly(points.get(0), points.get(2));
        var reversal = List.of(new Coordinate(0, 0), new Coordinate(50, 20), new Coordinate(25, 10));
        List<Coordinate> retained = ReflectionTestUtils.invokeMethod(builder, "straightPointsRemoved", reversal);
        assertThat(retained).containsExactlyElementsOf(reversal);
    }
}
