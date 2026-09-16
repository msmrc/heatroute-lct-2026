package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OfficialRouteValidatorTest {
    private final OfficialRouteValidator validator = new OfficialRouteValidator();

    @Test
    void rejectsCycleAndMultipleUpstreamEdges() {
        List<RouteNode> nodes = List.of(
                node("a", 0, 0, false, false, 0),
                node("b", 10, 0, false, false, 0),
                node("root", 5, 10, true, true, 2));
        List<RouteEdge> edges = List.of(
                edge("a-b", "a", "b", 10),
                edge("b-a", "b", "a", 10),
                edge("root-a", "root", "a", 11.18));

        assertThat(validator.validate(nodes, edges)).extracting(RouteValidationIssue::getCode)
                .contains("ROUTE_CYCLE", "MULTIPLE_UPSTREAM_EDGES");
    }

    @Test
    void rejectsBranchWithoutChamberAndDegreeAboveFour() {
        List<RouteNode> nodes = List.of(
                node("root", 0, 0, true, true, 3),
                node("branch", 10, 0, false, false, 0),
                node("one", 20, -10, false, false, 0),
                node("two", 20, 10, false, false, 0),
                node("three", -10, 0, false, false, 0));
        List<RouteEdge> edges = List.of(
                edge("branch-root", "root", "branch", 10),
                edge("one", "branch", "one", 14),
                edge("two", "branch", "two", 14),
                edge("three", "root", "three", 10));

        assertThat(validator.validate(nodes, edges)).extracting(RouteValidationIssue::getCode)
                .contains("BRANCH_WITHOUT_CHAMBER", "CHAMBER_DEGREE_EXCEEDED");
    }

    @Test
    void rejectsCrossingOutsideACommonNode() {
        List<RouteNode> nodes = List.of(
                node("root-a", 10, 10, true, true, 2),
                node("demand-a", 0, 0, false, false, 0),
                node("root-b", 0, 10, true, true, 2),
                node("demand-b", 10, 0, false, false, 0));
        List<RouteEdge> edges = List.of(
                edge("diagonal-a", "root-a", "demand-a", 14),
                edge("diagonal-b", "root-b", "demand-b", 14));

        assertThat(validator.validate(nodes, edges)).extracting(RouteValidationIssue::getCode)
                .contains("CROSSING_OUTSIDE_COMMON_NODE");
    }

    private RouteNode node(
            String id,
            double x,
            double y,
            boolean chamber,
            boolean root,
            int baseIncidentSections) {
        return new RouteNode(
                id,
                chamber ? "chamber" : "node",
                new RouteCoordinate(x, y),
                chamber,
                root,
                baseIncidentSections,
                null);
    }

    private RouteEdge edge(String id, String upstream, String downstream, double length) {
        return new RouteEdge(id, upstream, downstream, length);
    }
}
