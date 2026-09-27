package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpertRouteBendRulesTest {
    @ParameterizedTest
    @CsvSource({"89.899,false", "89.9,true", "90,true", "119.999,true", "120,true", "120.101,false", "150,false", "180,true"})
    void updatedInternalAngleIsNinetyToOneHundredTwentyDegrees(double internalAngle, boolean valid) {
        double turn = Math.toRadians(180 - internalAngle);
        List<RouteCoordinate> path = List.of(p(0, 0), p(1000, 0), p(1000 + 1000 * Math.cos(turn), 1000 * Math.sin(turn)));
        RouteNode a = node("a", path.get(0), true), b = node("b", path.get(2), false);
        assertThat(ExpertRouteBendRules.validate(List.of(a, b), List.of(edge(a, b, path))).isEmpty()).isEqualTo(valid);
    }

    @ParameterizedTest
    @CsvSource({"1.999,false,false", "2,false,true", "2.001,false,true", "1.999,true,false", "2,true,true", "2.001,true,true"})
    void bendSpacingSurvivesTechnicalSplitsAndDirection(double spacing, boolean reversed, boolean valid) {
        RouteNode a = node("a", p(0, 0), true), b = node("b", p(10, 0), false);
        RouteNode c = node("c", p(10, spacing), false), d = node("d", p(20, spacing), false);
        List<RouteEdge> edges = List.of(edge(a, b, points(reversed, a.getCoordinate(), b.getCoordinate())),
                edge(b, c, points(reversed, b.getCoordinate(), c.getCoordinate())),
                edge(c, d, points(reversed, c.getCoordinate(), d.getCoordinate())));
        List<RouteValidationIssue> issues = ExpertRouteBendRules.validate(List.of(d, c, b, a), edges);
        assertThat(issues.isEmpty()).isEqualTo(valid);

    }

    @ParameterizedTest
    @CsvSource({"1.999,false", "2,true", "2.001,true"})
    void internalBendsOnSeparateEdgesUseTheSumAcrossSeveralCollinearTechnicalEdges(double spacing, boolean valid) {
        RouteNode a = node("a", p(0, 0), true), b = node("b", p(10, .3), false);
        RouteNode c = node("c", p(10, 1.2), false), d = node("d", p(20, spacing), false);
        List<RouteEdge> edges = List.of(edge(a, b, List.of(a.getCoordinate(), p(10, 0), b.getCoordinate())),
                edge(b, c, List.of(b.getCoordinate(), c.getCoordinate())),
                edge(c, d, List.of(c.getCoordinate(), p(10, spacing), d.getCoordinate())));
        assertThat(ExpertRouteBendRules.validate(List.of(a, b, c, d), edges).isEmpty()).isEqualTo(valid);
    }

    @Test
    void duplicatesAndCollinearVerticesDoNotInventAnAngleOrResetSpacing() {
        RouteNode a = node("a", p(0, 0), true), b = node("b", p(20, 2), false);
        List<RouteCoordinate> path = List.of(p(0, 0), p(0, 0), p(5, 0), p(10, 0), p(10, 0),
                p(10, .2), p(10, 1.7), p(10, 2), p(15, 2), p(20, 2));
        assertThat(ExpertRouteBendRules.validate(List.of(a, b), List.of(edge(a, b, path)))).isEmpty();
    }

    @Test
    void aShortStraightTerminalIsValidButMissingGeometryIsNot() {
        RouteNode a = node("a", p(0, 0), true), b = node("b", p(.2, 0), false);
        assertThat(ExpertRouteBendRules.validate(List.of(a, b), List.of(edge(a, b, List.of(a.getCoordinate(), b.getCoordinate()))))).isEmpty();
        assertThat(ExpertRouteBendRules.validate(List.of(a, b), List.of(new RouteEdge("missing", "a", "b", 100))))
                .extracting(RouteValidationIssue::getCode).contains("EXPERT_ROUTE_BEND_GEOMETRY_UNCHECKABLE");
    }

    @Test
    void implicitAngleAtTechnicalNodeHasTheSameRuleAsAnInteriorPolylineVertex() {
        RouteNode a = node("a", p(0, 0), true), b = node("b", p(10, 0), false), c = node("c", p(8, 2), false);
        assertThat(ExpertRouteBendRules.validate(List.of(a, b, c), List.of(
                edge(a, b, List.of(a.getCoordinate(), b.getCoordinate())), edge(b, c, List.of(b.getCoordinate(), c.getCoordinate())))))
                .extracting(RouteValidationIssue::getCode).contains("EXPERT_ROUTE_BEND_ANGLE_INVALID");
    }

    @Test
    void shortSpacingIsReportedIndependentlyOfBendAngles() {
        List<RouteNode> nodes = gradualDrift();
        List<RouteCoordinate> points = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            points.add(nodes.get(i).getCoordinate());
            if (i > 0) edges.add(edge(nodes.get(i - 1), nodes.get(i), points.subList(i - 1, i + 1)));
        }
        assertThat(ExpertChamberGeometryRules.summarize(points).hasShortBendSpacing()).isTrue();
        assertThat(ExpertRouteBendRules.validate(nodes, edges))
                .extracting(RouteValidationIssue::getCode)
                .contains("EXPERT_ROUTE_BEND_TOO_CLOSE");
    }

    @Test
    void technicalSubdivisionsCannotHideCumulativeBendWithinTwoMetresOfChamber() {
        List<RouteNode> nodes = gradualDrift();
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) {
            edges.add(edge(nodes.get(i - 1), nodes.get(i), List.of(nodes.get(i - 1).getCoordinate(), nodes.get(i).getCoordinate())));
        }
        assertThat(new ExpertChamberRouteValidator().validate(nodes, edges)).extracting(RouteValidationIssue::getCode)
                .contains("EXPERT_CHAMBER_BEND_TOO_CLOSE");
    }

    @Test
    void partitioningCannotHideTheUpdatedBendMinimum() {
        List<RouteNode> pathNodes = gradualDrift();
        List<RouteCoordinate> points = new ArrayList<>();
        pathNodes.forEach(node -> points.add(node.getCoordinate()));
        for (int first = 1; first < points.size() - 2; first++) {
            for (int second = first + 1; second < points.size() - 1; second++) {
                RouteNode a = pathNodes.get(0), b = pathNodes.get(first), c = pathNodes.get(second);
                RouteNode d = pathNodes.get(points.size() - 1);
                List<RouteEdge> edges = List.of(edge(a, b, points.subList(0, first + 1)),
                        edge(b, c, points.subList(first, second + 1)),
                        edge(c, d, points.subList(second, points.size())));
                assertThat(ExpertRouteBendRules.validate(List.of(a, b, c, d), edges))
                        .as("technical cuts %s / %s", first, second)
                        .extracting(RouteValidationIssue::getCode)
                        .contains("EXPERT_ROUTE_BEND_TOO_CLOSE");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "50,1.999,false", "50,2,true",
            "200,2.999,false", "200,3,true",
            "400,3.999,false", "400,4,true",
            "700,4.999,false", "700,5,true",
            "1000,5.999,false", "1000,6,true"
    })
    void spacingBoundaryUsesTheActualDiameter(int diameter, double spacing, boolean valid) {
        RouteNode a = node("a", p(0, 0), true), b = node("b", p(20, spacing), false);
        RouteEdge edge = new RouteEdge(
                "route",
                a.getId(),
                b.getId(),
                999,
                List.of(a.getCoordinate(), p(10, 0), p(10, spacing), b.getCoordinate()),
                List.of(),
                null,
                diameter);

        assertThat(ExpertRouteBendRules.validate(List.of(a, b), List.of(edge)).isEmpty())
                .isEqualTo(valid);
    }

    private List<RouteNode> gradualDrift() {
        List<RouteNode> nodes = new ArrayList<>();
        double x = 0, y = 0;
        nodes.add(node("n0", p(x, y), true));
        for (int i = 0; i < 20; i++) {
            x += .6;
            y += i * .001;
            nodes.add(node("n" + (i + 1), p(x, y), false));
        }
        return nodes;
    }

    private RouteNode node(String id, RouteCoordinate coordinate, boolean chamber) {
        return new RouteNode(id, chamber ? "new_chamber" : "technical", coordinate, chamber, chamber, 0, null);
    }
    private RouteEdge edge(RouteNode a, RouteNode b, List<RouteCoordinate> path) {
        return new RouteEdge(a.getId() + "-" + b.getId(), a.getId(), b.getId(), 999, path, List.of(), null, 100);
    }
    private List<RouteCoordinate> points(boolean reversed, RouteCoordinate... coordinates) {
        List<RouteCoordinate> result = new ArrayList<>(List.of(coordinates));
        if (reversed) Collections.reverse(result);
        return result;
    }
    private RouteCoordinate p(double x, double y) { return new RouteCoordinate(400000 + x, 6000000 + y); }
}
