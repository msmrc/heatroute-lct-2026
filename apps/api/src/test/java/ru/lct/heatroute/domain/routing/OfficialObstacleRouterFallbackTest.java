package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.ConstraintIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Проверяет исключение только точных повторов проигравшего графа, а не новых pocket-кандидатов. */
class OfficialObstacleRouterFallbackTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void skipsIdenticalPocketGraphsAfterAllOrdinaryCorridorsFail() throws Exception {
        // Недоступное звено без дополнительных узлов: все три ordinary/pocket графа одинаковы.
        // Линейная road не подходит для этой синтетики: её нет в официальном polygon-контракте.
        OfficialObstacleRouter unavailable = new OfficialObstacleRouter(new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()) {
            @Override boolean segmentAllowed(Coordinate start, Coordinate end, ConstraintIndex constraints) {
                return false;
            }
        });
        Coordinate start = new Coordinate(0, -100);
        Coordinate end = new Coordinate(100, 1000);
        for (int repeat = 0; repeat < 2; repeat++) {
            OfficialRoutingEnvironment environment = unavailable.prepare(List.of());

            assertThat(unavailable.find(start, end, 100, environment, Set.of(), RoutePreference.SHORTEST)).isNull();

            assertSearchCount(environment, 3);
            // Три ordinary-поиска сохраняют отказ одного сегмента через общую memo.
            assertThat(ReflectionTestUtils.getField(environment, "visibilityPairChecks")).isEqualTo(1L);
        }
    }

    @Test
    void orientedEnvelopeFindsAndValidatesCourtyardExitBeforePocketFallback() throws Exception {
        List<ImportedOfficialFeature> features = List.of(restriction("oks", "courtyard",
                "POLYGON ((0 0,100 0,100 100,60 100,60 20,40 20,40 100,0 100,0 0))"));
        Coordinate start = new Coordinate(50, 35);
        Coordinate end = new Coordinate(50, -40);
        OfficialRoutingEnvironment environment = router.prepare(features);
        ConstraintIndex index = rules.index(environment.constraints(100, Set.of(), start, end));
        List<Coordinate> ordinary = nodes(start, end, index, false);
        List<Coordinate> pocket = nodes(start, end, index, true);
        assertThat(pocket.size()).isGreaterThan(ordinary.size());

        RoutePath route = router.find(start, end, 100, environment, Set.of(), RoutePreference.SHORTEST);

        assertThat(route).isNotNull();
        assertThat(route.coordinates().get(0)).isEqualTo(start);
        assertThat(route.coordinates().get(route.coordinates().size() - 1)).isEqualTo(end);
        assertThat(router.lineAllowed(route.coordinates(), 100, environment, Set.of(), List.of())).isTrue();
        assertSearchCount(environment, 1);
    }

    @Test
    void stillTriesAllDifferentPocketCorridorsWhenNoExitExists() throws Exception {
        List<ImportedOfficialFeature> features = List.of(restriction("oks", "closed-courtyard",
                "POLYGON ((0 0,100 0,100 100,0 100,0 0),(30 30,30 70,70 70,70 30,30 30))"));
        OfficialRoutingEnvironment environment = router.prepare(features);

        assertThat(router.find(new Coordinate(50, 50), new Coordinate(50, -40), 100,
                environment, Set.of(), RoutePreference.SHORTEST)).isNull();

        assertSearchCount(environment, 6);
    }

    @Test
    void skipsIdenticalGraphsAlsoWhenThePreviousRouteFailedFinalValidation() throws Exception {
        RejectingFinalGeometryRules rejectingRules = new RejectingFinalGeometryRules();
        OfficialObstacleRouter rejectingRouter = new OfficialObstacleRouter(rejectingRules);
        OfficialRoutingEnvironment environment = rejectingRouter.prepare(List.of(restriction("park", "block",
                "POLYGON ((40 -10,60 -10,60 10,40 10,40 -10))")));

        assertThat(rejectingRouter.find(new Coordinate(0, 0), new Coordinate(100, 0), 100,
                environment, Set.of(), RoutePreference.SHORTEST)).isNull();

        // 14 bounded rectangular candidates are checked before each ordinary graph validates
        // its shortcut and original path; an identical pocket graph is not searched again.
        assertThat(rejectingRules.validationAttempts).isEqualTo(20);
        assertSearchCount(environment, 3);
    }

    @Test
    void orderedGraphComparisonAcceptsCopiesButRejectsReorderingAndDifferentSizes() {
        List<Coordinate> original = List.of(new Coordinate(0, 0), new Coordinate(100, 0),
                new Coordinate(40, 10), new Coordinate(60, 10));
        List<Coordinate> copy = new ArrayList<>();
        original.forEach(coordinate -> copy.add(new Coordinate(coordinate)));

        assertThat(sameNodes(original, copy)).isTrue();
        assertThat(sameNodes(original, List.of(original.get(0), original.get(1),
                original.get(3), original.get(2)))).isFalse();
        assertThat(sameNodes(original, original.subList(0, 3))).isFalse();
        assertThat(sameNodes(List.of(), List.of())).isTrue();
    }

    @Test
    void graphComparisonDoesNotRoundCoordinatesOrIgnoreTheirOtherOrdinates() {
        List<Coordinate> original = List.of(new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(40, 10));
        assertThat(sameNodes(original, List.of(new Coordinate(0, 0), new Coordinate(100, 0),
                new Coordinate(Math.nextUp(40.0), 10)))).isFalse();
        assertThat(sameNodes(original, List.of(new Coordinate(-0.0, 0), new Coordinate(100, 0),
                new Coordinate(40, 10)))).isFalse();
        assertThat(sameNodes(original, List.of(new Coordinate(0, 0), new Coordinate(100, 0),
                new Coordinate(40, 10, 1)))).isFalse();
    }

    private boolean sameNodes(List<Coordinate> first, List<Coordinate> second) {
        return ReflectionTestUtils.invokeMethod(router, "sameOrderedNavigationNodes", first, second);
    }

    private List<Coordinate> nodes(Coordinate start, Coordinate end, ConstraintIndex index, boolean pockets) {
        return ReflectionTestUtils.invokeMethod(router, "navigationNodes", start, end, index, 75.0, pockets);
    }

    private void assertSearchCount(OfficialRoutingEnvironment environment, long expected) {
        assertThat(ReflectionTestUtils.getField(environment, "visibilitySearches")).isEqualTo(expected);
    }

    private ImportedOfficialFeature restriction(String type, String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction",
                new ObjectMapper().readTree("{\"restriction_type\":\"" + type + "\"}"), new WKTReader().read(wkt));
    }

    private static final class RejectingFinalGeometryRules extends OfficialRouteGeometryRules {
        private int validationAttempts;

        private RejectingFinalGeometryRules() {
            super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        }

        @Override
        boolean lineAllowed(LineString line, ConstraintIndex constraints) {
            validationAttempts++;
            return false;
        }
    }
}
