package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Таблица Google от 27.09.2026: проверка фактического ДУ каждого примыкающего участка. */
class ChamberDiameterBendDistanceTest {
    @ParameterizedTest
    @CsvSource({"50,2", "65,2", "80,2", "100,2", "125,2", "150,2", "200,3", "250,3",
            "300,3", "400,4", "500,4", "600,4", "700,5", "800,5", "900,5",
            "1000,6", "1200,6", "1400,6"})
    void allDiametersKeepTheirBoundaryAcrossStorageDirectionAndTechnicalSplits(int diameter, double minimum) {
        for (double delta : new double[] {-0.001, 0, 0.001}) {
            double distance = minimum + delta;
            for (boolean reverse : new boolean[] {false, true}) {
                for (boolean split : new boolean[] {false, true}) {
                    RouteNode chamber = node("c", 0, 0, true);
                    RouteNode demand = node("d", distance, 10, false);
                    List<RouteNode> nodes = new ArrayList<>(List.of(chamber, demand));
                    List<RouteEdge> edges = new ArrayList<>();
                    if (split) {
                        RouteNode technical = node("t", distance / 2, 0, false);
                        nodes.add(technical);
                        edges.add(edge(chamber, technical, diameter, reverse,
                                chamber.getCoordinate(), technical.getCoordinate()));
                        edges.add(edge(technical, demand, diameter, reverse, technical.getCoordinate(),
                                point(distance, 0), demand.getCoordinate()));
                    } else {
                        edges.add(edge(chamber, demand, diameter, reverse,
                                chamber.getCoordinate(), point(distance, 0), demand.getCoordinate()));
                    }
                    assertThat(validate(nodes, edges)).as("DU%s delta%s reverse%s split%s", diameter, delta, reverse, split)
                            .extracting(RouteValidationIssue::getCode)
                            .containsExactlyElementsOf(delta < 0 ? List.of("EXPERT_CHAMBER_BEND_TOO_CLOSE") : List.of());
                }
            }
        }
    }

    @Test
    void eachArmUsesItsOwnDiameterAndBothEndsAreChecked() {
        RouteNode large = node("large", -20, 0, false), chamber = node("c", 0, 0, true);
        RouteNode small = node("small", 2, 10, false);
        List<RouteEdge> edges = List.of(edge(large, chamber, 1400, false,
                large.getCoordinate(), chamber.getCoordinate()), edge(chamber, small, 50, false,
                chamber.getCoordinate(), point(2, 0), small.getCoordinate()));
        assertThat(validate(List.of(large, chamber, small), edges)).isEmpty();
        RouteNode downstream = node("downstream", 20, 5.999, true);
        assertThat(validate(List.of(chamber, downstream), List.of(edge(chamber, downstream, 1400, true,
                chamber.getCoordinate(), point(20, 0), downstream.getCoordinate()))))
                .extracting(RouteValidationIssue::getSubjectId).containsExactly("downstream");
    }

    @Test
    void shortStraightArmDoesNotNeedAnArtificialBendOrExtension() {
        RouteNode chamber = node("c", 0, 0, true), demand = node("d", 0.5, 0, false);
        assertThat(validate(List.of(chamber, demand), List.of(edge(chamber, demand, 1400, false,
                chamber.getCoordinate(), demand.getCoordinate())))).isEmpty();
    }

    @Test
    void missingOrUnsupportedDiameterCannotSilentlySelectTheTwoMetreRule() {
        RouteNode chamber = node("c", 0, 0, true), demand = node("d", 20, 0, false);
        for (Integer diameter : new Integer[] {null, 0, 151, 350, 1500}) {
            assertThat(validate(List.of(chamber, demand), List.of(edge(chamber, demand, diameter, false,
                    chamber.getCoordinate(), demand.getCoordinate()))))
                    .extracting(RouteValidationIssue::getCode).containsExactly("EXPERT_CHAMBER_GEOMETRY_UNCHECKABLE");
        }
        assertThatThrownBy(() -> ExpertChamberGeometryRules.minimumBendDistanceM(350))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges) {
        return ExpertChamberGeometryRules.validate(nodes, edges, ignored -> List.of());
    }

    private RouteNode node(String id, double x, double y, boolean chamber) {
        return new RouteNode(id, chamber ? "new_chamber" : "technical_node", point(x, y), chamber, false, 0, null);
    }

    private RouteEdge edge(RouteNode a, RouteNode b, Integer diameter, boolean reverse, RouteCoordinate... coordinates) {
        List<RouteCoordinate> path = new ArrayList<>(List.of(coordinates));
        if (reverse) Collections.reverse(path);
        return new RouteEdge(a.getId() + "-" + b.getId(), a.getId(), b.getId(), 999, path, List.of(), null, diameter);
    }

    private RouteCoordinate point(double x, double y) {
        return new RouteCoordinate(400000 + x, 6000000 + y);
    }
}
