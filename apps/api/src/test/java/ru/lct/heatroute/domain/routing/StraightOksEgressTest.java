package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Письменное уточнение 29.09: прямой ввод не обязан быть нормалью к стене. */
class StraightOksEgressTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsNonNormalStraightFinalLegInIndependentValidator() throws Exception {
        assertThat(validate(List.of(building()), new Coordinate(-10, 7), new Coordinate(2, 8))).isEmpty();
    }

    @Test
    void locallyLegalNearestExitDoesNotExcludeOtherWallsOrObliqueCandidates() throws Exception {
        var features = List.of(building(), restriction("west-pocket", "park",
                "POLYGON ((-12 -5, -1 -5, -1 -3, -10 -3, -10 23, -1 23, -1 25, -12 25, -12 -5))"));
        var start = new Coordinate(2, 8);
        var target = new Coordinate(80, 12);
        // Западный ввод попадает в карман между ОКС и П-образным запретом; он локально допустим,
        // но отступы верхней/нижней перемычек замыкают карман. Восточный ввод оставляем поиску.
        var candidates = rules.normalEgressCandidates(features, 50, start, target, 60, RouteTraversal.REVERSED);
        assertThat(candidates.get(0).exit().x).isLessThan(0);
        assertThat(candidates.get(0).exit().y).isEqualTo(8);
        assertThat(candidates).hasSizeBetween(2, 17)
                .anySatisfy(candidate -> assertThat(candidate.exit().x).isGreaterThan(20));
        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(Math.abs(candidate.exit().x - start.x)).isGreaterThan(0.01);
            assertThat(Math.abs(candidate.exit().y - start.y)).isGreaterThan(0.01);
        });
        candidates.forEach(candidate -> assertThat(validate(features, candidate.exit(), start)).isEmpty());
    }

    @Test
    void findsDeterministicObliqueExitWhenAllWallNormalsAreBlocked() throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>(List.of(building(),
                restriction("west", "park", "POLYGON ((-4 9, -2 9, -2 11, -4 11, -4 9))"),
                restriction("east", "park", "POLYGON ((22 9, 24 9, 24 11, 22 11, 22 9))"),
                restriction("south", "park", "POLYGON ((9 -4, 11 -4, 11 -2, 9 -2, 9 -4))"),
                restriction("north", "park", "POLYGON ((9 22, 11 22, 11 24, 9 24, 9 22))")));
        var start = new Coordinate(10, 10);
        var target = new Coordinate(-30, -30);
        var first = rules.normalEgressCandidates(features, 50, start, target, 60, RouteTraversal.REVERSED);
        assertThat(first).isNotEmpty();
        for (var egress : first) {
            assertThat(Math.abs(egress.exit().x - start.x)).isGreaterThan(0.01);
            assertThat(Math.abs(egress.exit().y - start.y)).isGreaterThan(0.01);
            assertThat(validate(features, egress.exit(), start)).isEmpty();
        }
        Collections.reverse(features);
        assertThat(rules.normalEgressCandidates(features, 50, start, target, 60, RouteTraversal.REVERSED))
                .extracting(egress -> egress.exit().toString())
                .containsExactlyElementsOf(first.stream().map(egress -> egress.exit().toString())
                        .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void nonNormalFinalLegDoesNotExemptForeignRailway() throws Exception {
        var features = List.of(building(), restriction("rail", "railway",
                "POLYGON ((-7 0, -6 0, -6 20, -7 20, -7 0))"));
        assertThat(validate(features, new Coordinate(-10, 7), new Coordinate(2, 8))).isNotEmpty();
    }

    @Test
    void straightFinalLegMustReachFullExteriorClearanceBeforeTurning() throws Exception {
        assertThat(validate(List.of(building()), new Coordinate(-1, 7), new Coordinate(2, 8))).isNotEmpty();
    }

    private List<RouteValidationIssue> validate(List<ImportedOfficialFeature> features,
            Coordinate adjacent, Coordinate endpoint) {
        var start = new RouteCoordinate(adjacent.x, adjacent.y);
        var end = new RouteCoordinate(endpoint.x, endpoint.y);
        var edge = new RouteEdge("edge", "root", "demand", adjacent.distance(endpoint),
                List.of(start, end), List.of(), null, 50);
        var nodes = List.of(new RouteNode("root", "existing_chamber_tie_in", start, true, true, 1, "root"),
                new RouteNode("demand", "demand_connection", end, false, true, 0, "demand"));
        return new OfficialRouteValidator(rules).validate(nodes, List.of(edge), features);
    }

    private ImportedOfficialFeature building() throws Exception {
        return restriction("own", "oks", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))");
    }

    private ImportedOfficialFeature restriction(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction", mapper.createObjectNode().put("restriction_type", type),
                new WKTReader().read(wkt));
    }
}
