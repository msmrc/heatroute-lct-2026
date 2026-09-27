package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance.MinimumLocation;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules;
import ru.lct.heatroute.domain.routing.OfficialRouteValidator;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialUtilityHorizontalAssessmentTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialUtilityHorizontalAssessment assessment =
            new OfficialUtilityHorizontalAssessment(pipes);

    @Test
    void rejectsOrdinaryParallelHeatNearMissAndAcceptsExactBoundary() {
        RouteEdge edge = edge("edge", 400, 0, 0, 20, 0);
        ImportedOfficialFeature unrelated =
                new ImportedOfficialFeature(
                        "demand",
                        "oks_connection_point",
                        mapper.createObjectNode(),
                        geometryFactory.createPoint(new Coordinate(20, 0)));

        var illegal =
                assessment.assess(
                        List.of(edge),
                        List.of(unrelated, heat("existing", 400, line(-10, 2, 30, 2))),
                        Map.of());
        assertThat(illegal.getOrdinaryViolations())
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.getRequiredAxisDistanceM()).hasToString("2.370");
                            assertThat(finding.getActualAxisDistanceM()).isEqualTo(2);
                            assertThat(finding.getSourceId()).isEqualTo("existing");
                        });

        var legal =
                assessment.assess(
                        List.of(edge),
                        List.of(heat("existing", 400, line(-10, 2.37, 30, 2.37))),
                        Map.of());
        assertThat(legal.getOrdinaryViolations()).isEmpty();
        assertThat(legal.getBoundaryFindings()).isEmpty();
    }

    @Test
    void keepsLiteralCrossingApproachAsBoundaryFinding() {
        RouteEdge edge = edge("edge", 50, -10, 0, 10, 0);

        var result =
                assessment.assess(
                        List.of(edge),
                        List.of(restriction("gas", "gas_pipeline", line(0, -20, 0, 20))),
                        Map.of());

        assertThat(result.getOrdinaryViolations()).isEmpty();
        assertThat(result.getBoundaryFindings())
                .hasSize(2)
                .allSatisfy(
                        finding -> {
                            assertThat(finding.getMinimumLocation())
                                    .isEqualTo(MinimumLocation.BOUNDARY_ADJACENT_MINIMUM);
                            assertThat(finding.getRequiredAxisDistanceM()).hasToString("2.400");
                            assertThat(finding.getActualAxisDistanceM()).isEqualTo(2);
                        });
    }

    @Test
    void catchesRepeatedNearPassAfterAValidCrossing() {
        RouteEdge edge =
                edge(
                        "edge",
                        50,
                        List.of(
                                point(-10, 0),
                                point(10, 0),
                                point(10, 10),
                                point(1, 10)));

        var result =
                assessment.assess(
                        List.of(edge),
                        List.of(restriction("gas", "gas_pipeline", line(0, -20, 0, 20))),
                        Map.of());

        assertThat(result.getOrdinaryViolations())
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.getActualAxisDistanceM()).isEqualTo(1);
                            assertThat(finding.getWitnessStationM()).isEqualTo(0);
                        });
        assertThat(result.getBoundaryFindings()).hasSize(1);
    }

    @Test
    void treatsOnlyTheVerifiedRootContactAsAnAllowedBoundary() {
        RouteEdge edge = edge("edge", 50, 0, 0, 10, 0);
        ImportedOfficialFeature source = heat("existing", 50, line(0, -20, 0, 20));

        var result =
                assessment.assess(
                        List.of(edge),
                        List.of(source),
                        Map.of("root", Set.of("existing")));

        assertThat(result.getOrdinaryViolations()).isEmpty();
        assertThat(result.getBoundaryFindings())
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.getSourceId()).isEqualTo("existing");
                            assertThat(
                                            Math.min(
                                                    finding.getWitnessStationM(),
                                                    10 - finding.getWitnessStationM()))
                                    .isCloseTo(.05, org.assertj.core.data.Offset.offset(1e-12));
                        });
    }

    @Test
    void technicalSplitDoesNotTurnOneTieInApproachIntoAnOrdinaryNearMiss() {
        List<RouteCoordinate> firstPoints = List.of(point(0, 0), point(5, 1.7));
        List<RouteCoordinate> secondPoints = List.of(point(5, 1.7), point(20, 10));
        RouteEdge first =
                new RouteEdge(
                        "first",
                        "root",
                        "technical",
                        line(firstPoints).getLength(),
                        firstPoints,
                        List.of(),
                        null,
                        400);
        RouteEdge second =
                new RouteEdge(
                        "second",
                        "technical",
                        "demand",
                        line(secondPoints).getLength(),
                        secondPoints,
                        List.of(),
                        null,
                        400);
        ImportedOfficialFeature source =
                heat("existing", 400, line(-10, 0, 30, 0));

        var result =
                assessment.assess(
                        List.of(first, second),
                        List.of(source),
                        Map.of("root", Set.of("existing")));

        assertThat(result.getOrdinaryViolations()).isEmpty();
        assertThat(result.getBoundaryFindings())
                .singleElement()
                .satisfies(finding -> assertThat(finding.getEdgeId()).isEqualTo("first"));
    }

    @Test
    void finalRouteValidatorReturnsTheStableUtilityIssueCode() {
        RouteEdge edge = edge("edge", 400, 0, 0, 20, 0);
        RouteNode root =
                new RouteNode("root", "existing_chamber", point(0, 0), true, true, 0, null);
        RouteNode demand =
                new RouteNode("demand", "demand_connection", point(20, 0), false, false, 0, null);
        OfficialRouteGeometryRules rules =
                new OfficialRouteGeometryRules(
                        new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

        assertThat(
                        new OfficialRouteValidator(rules)
                                .validate(
                                        List.of(root, demand),
                                        List.of(edge),
                                        List.of(heat("existing", 400, line(-10, 2, 30, 2)))))
                .extracting(issue -> issue.getCode() + ":" + issue.getSubjectId())
                .containsExactly("UTILITY_HORIZONTAL_CLEARANCE_VIOLATION:edge");
    }

    private ImportedOfficialFeature heat(
            String id, int diameter, LineString geometry) {
        return new ImportedOfficialFeature(
                id,
                "heat_network",
                mapper.createObjectNode().put("diameter", diameter),
                geometry);
    }

    private ImportedOfficialFeature restriction(
            String id, String type, LineString geometry) {
        return new ImportedOfficialFeature(
                id,
                "restriction",
                mapper.createObjectNode().put("restriction_type", type),
                geometry);
    }

    private RouteEdge edge(String id, int diameter, double... xy) {
        List<RouteCoordinate> points = new ArrayList<>();
        for (int index = 0; index < xy.length; index += 2) {
            points.add(point(xy[index], xy[index + 1]));
        }
        return edge(id, diameter, points);
    }

    private RouteEdge edge(String id, int diameter, List<RouteCoordinate> points) {
        double length = line(points).getLength();
        return new RouteEdge(
                id,
                "root",
                "demand",
                length,
                points,
                List.of(),
                null,
                diameter);
    }

    private LineString line(List<RouteCoordinate> points) {
        return geometryFactory.createLineString(
                points.stream()
                        .map(RouteCoordinate::toCoordinate)
                        .toArray(Coordinate[]::new));
    }

    private LineString line(double... xy) {
        List<RouteCoordinate> points = new ArrayList<>();
        for (int index = 0; index < xy.length; index += 2) {
            points.add(point(xy[index], xy[index + 1]));
        }
        return line(points);
    }

    private RouteCoordinate point(double x, double y) {
        return new RouteCoordinate(x, y);
    }
}
