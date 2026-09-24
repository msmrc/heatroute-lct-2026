package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Проверяет, что shortcut и изменение одной вершины не портят соседние обязательные углы. */
class OfficialObstacleRouterPostprocessingTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @Test
    void normalizationDoesNotCreateAnObtuseTurnAfterAVisibleShortcut() {
        List<Coordinate> source = List.of(point(0, 0), point(10, 0), point(10, 10), point(0, 10), point(-1, 0));
        OfficialRouteGeometryRules graph = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()) {
            @Override boolean segmentAllowed(Coordinate a, Coordinate b, ConstraintIndex index) {
                int i = source.indexOf(a), j = source.indexOf(b);
                return i >= 0 && j >= 0 && (Math.abs(i - j) == 1 || Math.min(i, j) == 0 && Math.max(i, j) == 2);
            }
        };
        assertLegal(source);
        List<Coordinate> actual = ReflectionTestUtils.invokeMethod(new OfficialObstacleRouter(graph),
                "normalize", source, rules.index(List.of()));
        assertLegal(actual);
        assertThat(actual).containsExactlyElementsOf(source);
    }

    @Test
    void normalizationChecksTheIncomingSideOfAShortcutToo() {
        List<Coordinate> source = List.of(point(-10, 0), point(0, 0), point(10, 0),
                point(10, 10), point(0, 10), point(-1, 0));
        OfficialRouteGeometryRules graph = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()) {
            @Override boolean segmentAllowed(Coordinate a, Coordinate b, ConstraintIndex index) {
                int i = source.indexOf(a), j = source.indexOf(b);
                return i >= 0 && j >= 0 && (Math.abs(i - j) == 1 || Math.min(i, j) == 1 && Math.max(i, j) == 5);
            }
        };
        assertLegal(source);
        List<Coordinate> actual = ReflectionTestUtils.invokeMethod(new OfficialObstacleRouter(graph),
                "normalize", source, rules.index(List.of()));
        assertThat(actual).containsExactlyElementsOf(source);
        assertLegal(actual);
    }

    @Test
    void realForbiddenPolygonsReproduceTheShortcutAndRegularizationKeepsAValidPath() throws Exception {
        List<Coordinate> source = List.of(point(0, 0), point(100, 0), point(100, 100), point(0, 100), point(-10, 0));
        List<ImportedOfficialFeature> obstacles = List.of(
                park("west", "POLYGON ((-6 -1,-4 -1,-4 1,-6 1,-6 -1))"),
                park("north", "POLYGON ((-1 40,1 40,1 50,-1 50,-1 40))"),
                park("diagonal", "POLYGON ((22 29,24 29,24 31,22 31,22 29))"),
                park("other-diagonal", "POLYGON ((44 54,46 54,46 56,44 56,44 54))"));
        OfficialRoutingEnvironment environment = router.prepare(obstacles);
        var constraints = rules.index(environment.constraints(50, Set.of(), source.get(0), source.get(4)));
        assertThat(rules.lineAllowed(rules.line(source), constraints)).isTrue();
        assertThat(rules.segmentAllowed(source.get(0), source.get(2), constraints)).isTrue();
        List<Coordinate> normalized = ReflectionTestUtils.invokeMethod(router, "normalize", source, constraints);
        assertLegal(normalized);
        assertThat(rules.lineAllowed(rules.line(normalized), constraints)).isTrue();
        RoutePath result = router.regularize(source, 50, environment, Set.of(), List.of());
        assertThat(result).isNotNull();
        assertLegal(result.coordinates());
        assertThat(rules.lineAllowed(rules.line(result.coordinates()), constraints)).isTrue();
        assertThat(source).containsExactly(point(0, 0), point(100, 0), point(100, 100), point(0, 100), point(-10, 0));
    }

    @Test
    void legalSimplificationStillRemovesVerticesAndConsecutiveMillimetreDuplicates() {
        List<Coordinate> source = List.of(point(0, 0), point(0.0001, -0.0001), point(10, 0), point(10, 10));
        List<Coordinate> actual = ReflectionTestUtils.invokeMethod(router, "normalize", source, rules.index(List.of()));
        assertThat(actual).containsExactly(point(0, 0), point(10, 10));
        assertLegal(actual);
    }

    @Test
    void postprocessingObservesCancellationAndPreservesTheInterruptFlag() {
        List<Coordinate> source = List.of(point(0, 0), point(10, 0), point(10, 10));
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(router, "normalize", source, rules.index(List.of())))
                    .isInstanceOf(CancellationException.class);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(router, "snapConstructibleCorners",
                    source, rules.index(List.of()), RoutePreference.ENGINEERING)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void snappingPreservesLegalNeighbouringTurnsOnSeededPolylines() {
        Random random = new Random(530923L);
        for (int sample = 0; sample < 120; sample++) {
            List<Coordinate> source = new ArrayList<>(List.of(point(0, 0)));
            double heading = random.nextDouble() * Math.PI * 2;
            for (int index = 0; index < 5; index++) {
                Coordinate previous = source.get(source.size() - 1);
                double length = 3 + random.nextDouble() * 20;
                heading += (random.nextDouble() - 0.5) * Math.toRadians(170);
                source.add(point(previous.x + Math.cos(heading) * length, previous.y + Math.sin(heading) * length));
            }
            assertLegal(source);
            for (double rotation : new double[] {0, 0.713}) {
                List<Coordinate> transformed = source.stream().map(p -> point(
                        414000.123 + p.x * Math.cos(rotation) - p.y * Math.sin(rotation),
                        6173000.456 + p.x * Math.sin(rotation) + p.y * Math.cos(rotation))).collect(Collectors.toList());
                List<Coordinate> before = transformed.stream().map(Coordinate::new).collect(Collectors.toList());
                for (RoutePreference preference : RoutePreference.values()) {
                    List<Coordinate> actual = ReflectionTestUtils.invokeMethod(router, "snapConstructibleCorners",
                            transformed, rules.index(List.of()), preference);
                    assertThat(issues(actual)).as("sample=%s preference=%s source=%s actual=%s", sample, preference, transformed, actual)
                            .isEmpty();
                    assertThat(actual.get(0)).isEqualTo(before.get(0));
                    assertThat(actual.get(actual.size() - 1)).isEqualTo(before.get(before.size() - 1));
                    assertThat(transformed).containsExactlyElementsOf(before);
                }
            }
        }
    }

    private static Coordinate point(double x, double y) { return new Coordinate(x, y); }
    private void assertLegal(List<Coordinate> points) { assertThat(issues(points)).as("%s", points).isEmpty(); }
    private List<RouteValidationIssue> issues(List<Coordinate> points) {
        return OfficialRouteDeflectionRules.validatePolyline("postprocessed", points.stream()
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList())).getIssues();
    }

    private ImportedOfficialFeature park(String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", "park"), new WKTReader().read(wkt));
    }
}
