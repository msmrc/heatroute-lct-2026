package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Уточнение пользователя от 25.09.2026: нормаль в камере и минимум 2 м до поворота. */
class ExpertChamberGeometryRulesTest {
    private final ExpertChamberRouteValidator validator = new ExpertChamberRouteValidator();

    @ParameterizedTest
    @CsvSource({"1.999,false", "2,true", "2.001,true", "8,true"})
    void nearestBendUsesActualPolylineRatherThanDeclaredLength(double distance, boolean valid) {
        RouteNode chamber = node("c", 0, 0, true);
        RouteNode demand = node("d", distance, 4, false);
        List<RouteValidationIssue> issues = validator.validate(List.of(chamber, demand),
                List.of(edge(chamber, demand, point(0, 0), point(distance, 0), point(distance, 4))));
        assertThat(issues.isEmpty()).isEqualTo(valid);
        if (!valid) assertThat(issues).extracting(RouteValidationIssue::getCode)
                .contains("EXPERT_CHAMBER_BEND_TOO_CLOSE");
    }

    @ParameterizedTest
    @CsvSource({"10,0,true", "0,10,true", "0,-10,true", "10,10,false", "-10,10,false", "-10,0,false"})
    void onlyDistinctOrthogonalOrOppositeRaysAreAllowed(double x, double y, boolean valid) {
        RouteNode chamber = node("c", 0, 0, true);
        RouteNode incoming = node("a", -20, 0, true);
        RouteNode demand = node("d", x, y, false);
        assertThat(validator.validate(List.of(incoming, chamber, demand),
                List.of(edge(incoming, chamber), edge(chamber, demand))).isEmpty()).isEqualTo(valid);
    }

    @ParameterizedTest
    @CsvSource({"1.999,false", "2,true", "2.001,true"})
    void bendAtATechnicalNodeCannotHideBehindAnEdgeBoundary(double distance, boolean valid) {
        RouteNode chamber = node("c", 0, 0, true);
        RouteNode technical = new RouteNode("t", "technical", point(distance, 0), false, false, 0, null);
        RouteNode demand = node("d", distance, 4, false);
        assertThat(validator.validate(List.of(chamber, technical, demand),
                List.of(edge(chamber, technical), edge(technical, demand))).isEmpty()).isEqualTo(valid);
    }

    @Test
    void collinearTechnicalEdgesAndDuplicateVerticesDoNotResetStraightLength() {
        RouteNode chamber = node("c", 0, 0, true);
        RouteNode a = new RouteNode("a", "technical", point(.3, 0), false, false, 0, null);
        RouteNode b = new RouteNode("b", "technical", point(1.6, 0), false, false, 0, null);
        RouteNode demand = node("d", 2, 4, false);
        assertThat(validator.validate(List.of(chamber, a, b, demand), List.of(edge(chamber, a), edge(a, b),
                edge(b, demand, b.getCoordinate(), b.getCoordinate(), point(1.7, 0), point(2, 0),
                        point(2, 0), demand.getCoordinate())))).isEmpty();
    }

    @Test
    void checksBendsBeforeDownstreamChamberAndAcceptsReversedCoordinateStorage() {
        RouteNode a = node("a", 0, 0, true);
        RouteNode b = node("b", 20, 1.999, true);
        List<RouteCoordinate> path = new ArrayList<>(List.of(a.getCoordinate(), point(20, 0), b.getCoordinate()));
        Collections.reverse(path);
        RouteEdge edge = edge(a, b, path.toArray(new RouteCoordinate[0]));
        assertThat(validator.validate(List.of(a, b), List.of(edge))).extracting(RouteValidationIssue::getCode)
                .contains("EXPERT_CHAMBER_BEND_TOO_CLOSE");
    }

    @Test
    void shortDirectChamberToDemandNeedsNoArtificialTwoMetreExtension() {
        RouteNode c = node("c", 0, 0, true), d = node("d", .5, 0, false);
        assertThat(validator.validate(List.of(c, d), List.of(edge(c, d)))).isEmpty();
    }

    @Test
    void obliqueEntryIsRejectedAtDegreeThreeAndFourChambers() {
        RouteNode a = node("a", -20, 0, true), c = node("c", 0, 0, true);
        RouteNode east = node("east", 20, 0, false), diagonal = node("diag", 10, 10, false);
        RouteNode south = node("south", 0, -20, false);
        for (boolean fourth : new boolean[] {false, true}) {
            List<RouteEdge> edges = new ArrayList<>(List.of(edge(a, c), edge(c, east), edge(c, diagonal)));
            List<RouteNode> nodes = new ArrayList<>(List.of(a, c, east, diagonal));
            if (fourth) { edges.add(edge(c, south)); nodes.add(south); }
            assertThat(validator.validate(nodes, edges)).extracting(RouteValidationIssue::getCode)
                    .contains("EXPERT_CHAMBER_OBLIQUE_ENTRY");
        }
    }

    private RouteNode node(String id, double x, double y, boolean chamber) {
        return new RouteNode(id, chamber ? "new_chamber" : "demand_connection", point(x, y),
                chamber, false, 0, null);
    }

    private RouteEdge edge(RouteNode a, RouteNode b, RouteCoordinate... path) {
        return new RouteEdge(a.getId() + "-" + b.getId(), a.getId(), b.getId(), 999,
                path.length == 0 ? List.of(a.getCoordinate(), b.getCoordinate()) : List.of(path),
                List.of(), null, 100);
    }

    private RouteCoordinate point(double x, double y) { return new RouteCoordinate(400000 + x, 6000000 + y); }
}
