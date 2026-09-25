package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Перенос камеры проверяет дорогу по потоку, а не по порядку внешнего узла в поиске. */
class CorridorLinkDirectionTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @ParameterizedTest
    @CsvSource({
            "true,true,false", "true,false,false", "false,true,false", "false,false,false",
            "true,true,true", "true,false,true", "false,true,true", "false,false,true"
    })
    void crossingAndItsSectionsUsePhysicalDirection(boolean outerUpstream, boolean goodDirection, boolean rotated) {
        ImportedOfficialFeature road = new ImportedOfficialFeature("asymmetric-road", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "road"),
                new GeometryFactory().createPolygon(new Coordinate[] {
                        point(0, 0, rotated), point(100, 0, rotated), point(107.150521556, 6, rotated),
                        point(0, 6, rotated), point(0, 0, rotated)}));
        List<ImportedOfficialFeature> features = List.of(road);
        Coordinate left = point(-10, 3, rotated), right = point(120, 3, rotated);
        Coordinate upstream = goodDirection ? left : right, downstream = goodDirection ? right : left;
        Coordinate outside = outerUpstream ? upstream : downstream;
        Coordinate junction = outerUpstream ? downstream : upstream;
        RouteNode outer = new RouteNode("outer", "new_branch_chamber", rc(outside), true, outerUpstream, 0, null);
        RouteNode joint = new RouteNode("joint", "new_branch_chamber", rc(junction), true, !outerUpstream, 0, null);
        String from = outerUpstream ? outer.getId() : joint.getId();
        String to = outerUpstream ? joint.getId() : outer.getId();
        RouteEdge source = new RouteEdge("source", from, to, 130,
                List.of(rc(upstream), rc(downstream)), List.of(), BigDecimal.ONE, 50);

        // Настоящий асимметричный контроль: прямой проход слева направо входит под90°, обратно под40°.
        PreparedCorridor physical = router.prepareCorridor(50, router.prepare(features),
                new org.locationtech.jts.geom.Envelope(upstream, downstream), upstream, null);
        assertThat(physical.path(List.of(upstream, downstream)) != null).isEqualTo(goodDirection);

        List<RoutePath> paths = CorridorLinkApproaches.build(source, outer, junction,
                rotated ? Math.PI / 2 : 0, router, router.prepare(features));
        assertThat(paths.stream().anyMatch(path -> Math.abs(path.lengthM() - 130) < 0.001))
                .as("the direct path must follow physical direction independently of outer endpoint")
                .isEqualTo(goodDirection);
        for (RoutePath stored : paths) {
            assertThat(stored.coordinates().get(0)).isEqualTo(outside);
            RoutePath oriented = outerUpstream ? stored : stored.reversed();
            RouteEdge actual = new RouteEdge("candidate", from, to, oriented.lengthM(),
                    oriented.coordinates().stream().map(CorridorLinkDirectionTest::rc).collect(Collectors.toList()),
                    oriented.sections(), BigDecimal.ONE, 50);
            assertThat(new OfficialRouteValidator(rules).validate(List.of(outer, joint), List.of(actual), features))
                    .as("reversal must preserve sections checked at the actual entrance")
                    .extracting(RouteValidationIssue::getCode)
                    .isEmpty();
            assertThat(new EngineeringRouteEvaluator().evaluate(List.of(actual)).isCompliant()).isTrue();
        }
    }

    private static Coordinate point(double x, double y, boolean rotated) {
        return new Coordinate(500000 + (rotated ? -y : x), 6170000 + (rotated ? x : y));
    }

    private static RouteCoordinate rc(Coordinate point) { return new RouteCoordinate(point.x, point.y); }
}
