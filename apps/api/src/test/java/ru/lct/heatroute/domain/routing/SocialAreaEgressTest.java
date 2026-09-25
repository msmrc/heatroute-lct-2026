package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** ТЗ §2.2 освобождает только собственный ОКС; запрет social_area из таблицы 2 сохраняется. */
class SocialAreaEgressTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeometryFactory geometry = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @ParameterizedTest(name = "rotated={0}, DU{1}, shared ID={2}")
    @MethodSource("placements")
    void containingSocialAreaBlocksEveryOwnBuildingNormal(boolean rotated, int diameter, boolean sharedId) {
        List<ImportedOfficialFeature> features = List.of(
                rectangle("own", "oks", 40, 40, 60, 60, rotated),
                rectangle(sharedId ? "own" : "site", "social_area", 20, 20, 80, 80, rotated));
        Coordinate demand = point(50, 50, rotated), source = point(0, 50, rotated);
        assertThat(rules.normalEgress(features, diameter, demand)).isEmpty();
        for (RouteTraversal direction : RouteTraversal.values()) {
            assertThat(rules.normalEgressCandidates(features, diameter, demand, source, 60, direction)).isEmpty();
        }
    }

    @ParameterizedTest(name = "rotated={0}, DU{1}, shared ID={2}")
    @MethodSource("placements")
    void finalValidatorRejectsNormalThroughContainingSocialArea(boolean rotated, int diameter, boolean sharedId) {
        List<ImportedOfficialFeature> ownOnly = List.of(rectangle("own", "oks", 40, 40, 60, 60, rotated));
        assertThat(issues(ownOnly, diameter, rotated)).isEmpty();
        List<ImportedOfficialFeature> forbidden = List.of(ownOnly.get(0),
                rectangle(sharedId ? "own" : "site", "social_area", 20, 20, 80, 80, rotated));
        assertThat(issues(forbidden, diameter, rotated)).extracting(RouteValidationIssue::getCode)
                .contains("FORBIDDEN_CLEARANCE_VIOLATION");
    }

    @ParameterizedTest(name = "rotated={0}, DU{1}, shared ID={2}")
    @MethodSource("placements")
    void legalOwnBuildingNormalKeepsOnlyOksExemption(boolean rotated, int diameter, boolean sharedId) {
        List<ImportedOfficialFeature> features = List.of(
                rectangle("own", "oks", 40, 40, 60, 60, rotated),
                rectangle(sharedId ? "own" : "site", "social_area", 20, 70, 80, 90, rotated));
        var egress = rules.normalEgressTowards(features, diameter, point(50, 50, rotated), point(0, 50, rotated))
                .orElseThrow();
        assertThat(egress.terminalExemptionIds()).containsExactly("own");
        assertThat(issues(features, diameter, rotated)).isEmpty();
    }

    private List<RouteValidationIssue> issues(List<ImportedOfficialFeature> features, int diameter, boolean rotated) {
        Coordinate source = point(0, 50, rotated), demand = point(50, 50, rotated);
        List<RouteCoordinate> points = List.of(new RouteCoordinate(source.x, source.y), new RouteCoordinate(demand.x, demand.y));
        RouteEdge edge = new RouteEdge("edge", "root", "demand", 50, points, List.of(), null, diameter);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", points.get(0), true, true, 1, "root"),
                new RouteNode("demand", "demand_connection", points.get(1), false, true, 0, "demand"));
        return new OfficialRouteValidator(rules).validate(nodes, List.of(edge), features);
    }

    private ImportedOfficialFeature rectangle(String id, String type, double x0, double y0,
            double x1, double y1, boolean rotated) {
        return new ImportedOfficialFeature(id, "restriction", mapper.createObjectNode().put("restriction_type", type),
                geometry.createPolygon(new Coordinate[] {point(x0, y0, rotated), point(x1, y0, rotated),
                    point(x1, y1, rotated), point(x0, y1, rotated), point(x0, y0, rotated)}));
    }

    private Coordinate point(double x, double y, boolean rotated) {
        double cosine = rotated ? 0.6 : 1, sine = rotated ? 0.8 : 0;
        return new Coordinate(430000 + cosine * x - sine * y, 6180000 + sine * x + cosine * y);
    }

    private static Stream<Arguments> placements() {
        return Stream.of(false, true).flatMap(rotated -> Stream.of(50, 500)
                .flatMap(diameter -> Stream.of(false, true).map(sharedId -> Arguments.of(rotated, diameter, sharedId))));
    }
}
