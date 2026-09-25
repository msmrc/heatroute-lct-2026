package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Нормаль реальной стены не обязана совпадать с общей осью квартала. */
class StrictNormalCorridorTest {
    @Test
    void usesVerifiedLocalTransitionBeforeGenericFallbackForTiltedWalls() throws Exception {
        assertTransitions(new Coordinate(-40, -35));
    }

    @Test
    void reachesOppositeSideOfTiltedNormalWithoutAGlobalSearchOrThreeBendLoop() throws Exception {
        assertTransitions(new Coordinate(-40, 35));
    }

    private void assertTransitions(Coordinate port) throws Exception {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        for (double degrees : new double[] {5, 13, 27, 41}) {
            var transform = AffineTransformation.rotationInstance(Math.toRadians(degrees));
            var building = transform.transform(new WKTReader().read(
                    "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
            var start = transform.transform(new Coordinate(2, 8), new Coordinate());
            var features = List.of(new ImportedOfficialFeature("own", "oks_existing",
                    new ObjectMapper().createObjectNode(), building));
            var environment = router.prepare(features);
            AtomicInteger fallback = new AtomicInteger();
            var spurs = new CorridorTerminalRouter(router, environment,
                    (id, end, diameter, avoidance) -> { fallback.incrementAndGet(); return null; }, 0);
            assertThat(spurs.localAlternatives("d", start, port, 50))
                    .as("independently available transition at %s degrees", degrees).isNotEmpty();
            RoutePath path = spurs.route("d", start, port, 50);
            assertThat(path).as("chosen transition at %s degrees", degrees).isNotNull();
            assertThat(fallback).hasValue(0);
            var edge = new RouteEdge("e", "r", "d", path.lengthM(), path.reversed().coordinates().stream()
                    .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()), List.of(), null, 50);
            var evaluation = new EngineeringRouteEvaluator().evaluate(List.of(edge));
            assertThat(evaluation.isCompliant()).isTrue();
            assertThat(evaluation.bendCount()).isLessThanOrEqualTo(2);
            assertThat(rules.validateMandatoryEgress(edge, rules.line(path.reversed().coordinates()),
                    features, 50)).isEmpty();
        }
    }
}
