package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

class CorridorCompressionAdmissionTest {
    @Test
    void collinearRemovalMustNotInvalidateAnAdmittedRoundedTurn() throws Exception {
        for (double offset : new double[] {0, 400000}) {
            List<Coordinate> points = new ArrayList<>();
            for (int i = 0; i <= 5; i++) points.add(new Coordinate(offset - i * 0.001, offset + i * 3));
            points.add(new Coordinate(offset + 10, offset + 15));
            assertThat(issues(points)).isEmpty();
            List<Coordinate> result = simplify(points);
            assertThat(issues(result)).as("Longer chords have a smaller rounding allowance").isEmpty();
            assertThat(result).containsExactlyElementsOf(points);
        }
    }

    @Test
    void stillRemovesTrulyStraightSamplesAndPreservesEndpoints() throws Exception {
        List<Coordinate> points = List.of(new Coordinate(0, 0), new Coordinate(3, 0),
                new Coordinate(6, 0), new Coordinate(6, 4), new Coordinate(6, 8));
        assertThat(simplify(points)).containsExactly(points.get(0), points.get(2), points.get(4));
    }

    @Test
    void doesNotInventVerticesOrMakeAnInvalidInputAnAcceptanceClaim() throws Exception {
        List<Coordinate> invalid = List.of(new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(8, 8));
        assertThat(issues(invalid)).isNotEmpty();
        assertThat(issues(simplify(invalid))).isNotEmpty();
        assertThat(simplify(invalid)).containsExactlyElementsOf(invalid);
    }

    private List<RouteValidationIssue> issues(List<Coordinate> points) {
        return OfficialRouteDeflectionRules.validatePolyline("candidate", points.stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList())).getIssues();
    }

    @SuppressWarnings("unchecked")
    private List<Coordinate> simplify(List<Coordinate> points) throws Exception {
        OfficialObstacleRouter router = new OfficialObstacleRouter(new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));
        OrthogonalCorridorNetworkBuilder builder = new OrthogonalCorridorNetworkBuilder(router, new OfficialPipeCatalog());
        Method method = OrthogonalCorridorNetworkBuilder.class.getDeclaredMethod("straightPointsRemoved", List.class);
        method.setAccessible(true);
        return (List<Coordinate>) method.invoke(builder, points);
    }
}
