package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpertChamberRouteValidatorTest {
    private final ExpertChamberRouteValidator validator = new ExpertChamberRouteValidator();

    @ParameterizedTest
    @CsvSource({"0,false", "4.587,false", "9.999,false", "10,true", "10.001,true", "20,true"})
    void enforcesTenMetreBoundaryOnActualGeometryNotDeclaredLength(double distanceM, boolean accepted) {
        RouteNode a = chamber("a", 400_000, 6_000_000);
        RouteNode b = chamber("b", 400_000 + distanceM, 6_000_000);
        RouteEdge edge = new RouteEdge("edge", "a", "b", 999,
                List.of(a.getCoordinate(), b.getCoordinate()), List.of(), null, null);
        List<RouteValidationIssue> issues = validator.validate(List.of(a, b), List.of(edge));
        if (accepted) assertThat(issues).isEmpty();
        else if (distanceM == 0) assertThat(issues).extracting(RouteValidationIssue::getCode)
                .containsExactly("EXPERT_CHAMBER_SPACING_TOO_SHORT", "EXPERT_CHAMBER_GEOMETRY_UNCHECKABLE",
                        "EXPERT_CHAMBER_GEOMETRY_UNCHECKABLE");
        else assertThat(issues).extracting(RouteValidationIssue::getCode).containsExactly("EXPERT_CHAMBER_SPACING_TOO_SHORT");
    }

    @Test
    void measuresAlongBentSectionNotEuclideanDistanceBetweenChambers() {
        RouteNode a = chamber("a", 0, 0);
        RouteNode b = chamber("b", 6, 0);
        RouteEdge bent = new RouteEdge("bent", "a", "b", 1,
                List.of(a.getCoordinate(), new RouteCoordinate(0, 3), new RouteCoordinate(6, 3), b.getCoordinate()),
                List.of(), null, null);
        assertThat(validator.validate(List.of(a, b), List.of(bent))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"9.999,false", "10,true", "10.001,true"})
    void technicalVerticesNeitherResetNorIndependentlyConstrainTheSection(double distanceM, boolean accepted) {
        RouteNode a = chamber("a", 0, 0);
        RouteNode t = node("t", "technical", 3, 0, false);
        RouteNode b = chamber("b", distanceM, 0);
        List<RouteValidationIssue> issues = validator.validate(List.of(b, t, a), List.of(edge(t, b), edge(a, t)));
        assertThat(issues.isEmpty()).isEqualTo(accepted);
    }

    @Test
    void measuresEachConsecutiveChamberPairInsteadOfTotalDistanceFromRoot() {
        RouteNode a = chamber("a", 0, 0);
        RouteNode b = chamber("b", 100, 0);
        RouteNode c = chamber("c", 105, 0);
        assertThat(validator.validate(List.of(a, b, c), List.of(edge(a, b), edge(b, c))))
                .singleElement().satisfies(issue -> {
                    assertThat(issue.getCode()).isEqualTo("EXPERT_CHAMBER_SPACING_TOO_SHORT");
                    assertThat(issue.getSubjectId()).isEqualTo("c");
                });
    }

    @Test
    void oneChamberMayServeSeveralShortBuildingSpursIncludingTechnicalVertices() {
        RouteNode a = chamber("a", 0, 0);
        RouteNode t = node("t", "technical", 1, 0, false);
        RouteNode d1 = node("d1", "demand_connection", 2, 0, false);
        RouteNode d2 = node("d2", "demand_connection", 0, 1, false);
        assertThat(validator.validate(List.of(a, t, d1, d2), List.of(edge(a, t), edge(t, d1), edge(a, d2)))).isEmpty();
    }

    @Test
    void rejectsBuildingInputWithoutAnyUpstreamChamber() {
        RouteNode a = node("a", "technical", 0, 0, false);
        RouteNode d = node("d", "demand_connection", 1, 0, false);
        assertThat(validator.validate(List.of(a, d), List.of(edge(a, d))))
                .extracting(RouteValidationIssue::getCode).containsExactly("EXPERT_OKS_BRANCH_WITHOUT_CHAMBER");
    }

    @Test
    void isolatedUnconnectedDemandDoesNotRequireAChamber() {
        assertThat(validator.validate(List.of(node("d", "demand_connection", 0, 0, false)), List.of())).isEmpty();
    }

    @Test
    void missingGeometryCannotBeReplacedByDeclaredLength() {
        RouteNode a = chamber("a", 0, 0);
        RouteNode b = chamber("b", 100, 0);
        assertThat(validator.validate(List.of(a, b), List.of(new RouteEdge("e", "a", "b", 100))))
                .extracting(RouteValidationIssue::getCode).containsExactly("EXPERT_CHAMBER_LENGTH_UNCHECKABLE",
                        "EXPERT_CHAMBER_GEOMETRY_UNCHECKABLE", "EXPERT_CHAMBER_GEOMETRY_UNCHECKABLE");
    }

    @Test
    void duplicateNodesEdgesUnknownEndpointsMultipleParentsAndCyclesFailClosed() {
        RouteNode a = chamber("a", 0, 0);
        RouteNode b = chamber("b", 10, 0);
        RouteNode c = chamber("c", 20, 0);
        assertTopologyFailure(List.of(a, a), List.of());
        assertTopologyFailure(List.of(a, b), List.of(edge(a, b), edge(a, b)));
        assertTopologyFailure(List.of(a), List.of(edge(a, b)));
        assertTopologyFailure(List.of(a, b, c), List.of(edge(a, b), edge(c, b)));
        assertTopologyFailure(List.of(a, b), List.of(edge(a, b), edge(b, a)));
    }

    @Test
    void longTechnicalChainDoesNotUseRecursion() {
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        RouteNode previous = chamber("a", 0, 0);
        nodes.add(previous);
        for (int i = 1; i <= 20_000; i++) {
            RouteNode next = node("n" + i, i == 20_000 ? "new_chamber" : "technical", i, 0, i == 20_000);
            nodes.add(next);
            edges.add(edge(previous, next));
            previous = next;
        }
        assertThat(validator.validate(nodes, edges)).isEmpty();
    }

    private void assertTopologyFailure(List<RouteNode> nodes, List<RouteEdge> edges) {
        assertThat(validator.validate(nodes, edges)).extracting(RouteValidationIssue::getCode)
                .contains("EXPERT_CHAMBER_TOPOLOGY_UNCHECKABLE");
    }

    private RouteNode chamber(String id, double x, double y) {
        return node(id, "new_chamber", x, y, true);
    }

    private RouteNode node(String id, String type, double x, double y, boolean chamber) {
        return new RouteNode(id, type, new RouteCoordinate(x, y), chamber, false, 0, null);
    }

    private RouteEdge edge(RouteNode from, RouteNode to) {
        return new RouteEdge(from.getId() + "-" + to.getId(), from.getId(), to.getId(), 999,
                List.of(from.getCoordinate(), to.getCoordinate()), List.of(), null, null);
    }
}
