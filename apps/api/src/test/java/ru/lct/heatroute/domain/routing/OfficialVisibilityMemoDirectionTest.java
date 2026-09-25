package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Общая memo не переносит допуск road/tram на обратное направление или другой контекст. */
class OfficialVisibilityMemoDirectionTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final Coordinate left = p(-1, 0);
    private final Coordinate right = p(150, 0);

    @Test
    void oppositeEndpointOrdersStayIndependentAcrossSearchesForRoadAndTram() {
        for (String type : List.of("road", "tram_tracks")) {
            List<Constraint> constraints = asymmetricCrossing(type);
            ConstraintIndex index = rules.index(constraints);
            assertThat(rules.segmentAllowed(left, right, index)).isFalse();
            assertThat(rules.segmentAllowed(right, left, index)).isTrue();
            for (boolean allowedFirst : new boolean[] {false, true}) {
                var memo = environment().visibilityMemo(constraints);
                if (allowedFirst) assertThat(search(List.of(right, left), index, memo)).containsExactly(right, left);
                assertThat(search(List.of(left, right), index, memo)).isEmpty();
                assertThat(search(List.of(right, left), index, memo)).containsExactly(right, left);
                assertThat(search(List.of(left, right), index, memo)).isEmpty();
            }
        }
    }

    @Test
    void traversalContextsCannotShareAnOppositeAdmissionForTheSameSegment() {
        for (String type : List.of("road", "tram_tracks")) {
            List<Constraint> constraints = asymmetricCrossing(type);
            for (boolean reversedFirst : new boolean[] {false, true}) {
                var environment = environment();
                var given = environment.visibilityMemo(constraints, RouteTraversal.AS_GIVEN);
                var reversed = environment.visibilityMemo(constraints, RouteTraversal.REVERSED);
                assertThat(given).isNotSameAs(reversed);
                if (reversedFirst) {
                    assertThat(search(List.of(left, right), rules.index(constraints, RouteTraversal.REVERSED), reversed))
                            .containsExactly(left, right);
                }
                assertThat(search(List.of(left, right), rules.index(constraints), given)).isEmpty();
                assertThat(search(List.of(left, right), rules.index(constraints, RouteTraversal.REVERSED), reversed))
                        .containsExactly(left, right);
                assertThat(search(List.of(left, right), rules.index(constraints), given)).isEmpty();
            }
        }
    }

    @Test
    void exactPreparedConstraintsReuseTheMemoButNewConstraintObjectsDoNot() {
        var environment = environment();
        List<Constraint> constraints = asymmetricCrossing("road");
        var memo = environment.visibilityMemo(constraints);
        assertThat(environment.visibilityMemo(new ArrayList<>(constraints))).isSameAs(memo);
        assertThat(environment.visibilityMemo(asymmetricCrossing("road"))).isNotSameAs(memo);

        List<Constraint> forbidden = rules.baseConstraints(List.of(rectangle("park", "park", 40, -10, 50, 10)), 100);
        assertThat(environment.visibilityMemo(forbidden, RouteTraversal.REVERSED))
                .isSameAs(environment.visibilityMemo(forbidden, RouteTraversal.AS_GIVEN));
    }

    @Test
    void dynamicObstaclesCannotPoisonReusableBaseAndCrossingChecks() {
        List<Constraint> crossing = asymmetricCrossing("road");
        List<Constraint> dynamic = rules.baseConstraints(List.of(rectangle("park", "park", 95, -2, 105, 2)), 100);
        var environment = environment();
        var baseMemo = environment.visibilityMemo(List.of());
        var crossingMemo = environment.visibilityMemo(crossing);
        for (boolean blockedFirst : new boolean[] {false, true}) {
            if (blockedFirst) assertThat(layeredSearch(crossing, dynamic, baseMemo, crossingMemo)).isEmpty();
            assertThat(layeredSearch(crossing, List.of(), baseMemo, crossingMemo)).containsExactly(right, left);
            assertThat(layeredSearch(crossing, dynamic, baseMemo, crossingMemo)).isEmpty();
            assertThat(layeredSearch(crossing, List.of(), baseMemo, crossingMemo)).containsExactly(right, left);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Coordinate> layeredSearch(List<Constraint> crossing, List<Constraint> dynamic,
            OfficialObstacleRouter.SegmentVisibilityMemo baseMemo,
            OfficialObstacleRouter.SegmentVisibilityMemo crossingMemo) {
        List<Constraint> all = new ArrayList<>(crossing);
        all.addAll(dynamic);
        Object result = ReflectionTestUtils.invokeMethod(router, "shortestPath", List.of(right, left),
                rules.index(all), RoutePreference.SHORTEST, right, left, null,
                new OfficialObstacleRouter.SegmentVisibilityMemo(), rules.index(List.of()), rules.index(crossing),
                dynamic.isEmpty() ? null : rules.index(dynamic), baseMemo, crossingMemo);
        return (List<Coordinate>) ReflectionTestUtils.getField(result, "coordinates");
    }

    @SuppressWarnings("unchecked")
    private List<Coordinate> search(List<Coordinate> nodes, ConstraintIndex index,
            OfficialObstacleRouter.SegmentVisibilityMemo memo) {
        Object result = ReflectionTestUtils.invokeMethod(router, "shortestPathWithMemo", nodes, index,
                RoutePreference.SHORTEST, nodes.get(0), nodes.get(1), memo);
        return (List<Coordinate>) ReflectionTestUtils.getField(result, "coordinates");
    }

    private OfficialRoutingEnvironment environment() { return router.prepare(List.of()); }

    private List<Constraint> asymmetricCrossing(String type) {
        ImportedOfficialFeature crossing = polygon(type, type, p(30, -3), p(70, -3), p(70, 3),
                p(30 + 6 / Math.tan(Math.toRadians(40)), 3), p(30, -3));
        return rules.baseConstraints(List.of(crossing), 100);
    }

    private ImportedOfficialFeature rectangle(String id, String type, double minX, double minY,
            double maxX, double maxY) {
        return polygon(id, type, p(minX, minY), p(maxX, minY), p(maxX, maxY), p(minX, maxY), p(minX, minY));
    }

    private ImportedOfficialFeature polygon(String id, String type, Coordinate... points) {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", type), new GeometryFactory().createPolygon(points));
    }

    private Coordinate p(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
}
