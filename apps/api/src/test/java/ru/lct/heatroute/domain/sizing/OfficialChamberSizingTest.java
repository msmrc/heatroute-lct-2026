package ru.lct.heatroute.domain.sizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;

class OfficialChamberSizingTest {
    @Test
    void existingAndNewDiametersParticipateSymmetricallyInAllChamberPriceBoundaries() {
        int[] diameters = {200, 250, 500, 600, 1000, 1200};
        int[] prices = {3, 5, 5, 8, 8, 12};
        OfficialEconomics economics = new OfficialEconomics();
        for (int i = 0; i < diameters.length; i++) {
            for (int[] pair : List.of(new int[] {diameters[i], 50}, new int[] {50, diameters[i]},
                    new int[] {diameters[i], diameters[i]})) {
                int maximum = OfficialChamberSizing.diameters(List.of(root(pair[0])), List.of(edge(pair[1]))).get("root");
                assertThat(maximum).isEqualTo(diameters[i]);
                assertThat(economics.chamberCost(maximum)).isEqualByComparingTo(BigDecimal.valueOf(prices[i] * 1_000_000L));
            }
        }
    }

    @Test
    void bothEndsAndMultipleNewRaysUseTheMaximumWithoutDuplicatingTheChamber() {
        RouteEdge reverse = new RouteEdge("other", "consumer2", "root", 10,
                List.of(), List.of(), BigDecimal.ONE, 1200);
        assertThat(OfficialChamberSizing.diameters(List.of(root(600)), List.of(edge(100), reverse)))
                .containsOnlyKeys("root").containsEntry("root", 1200);
    }

    @Test
    void aBranchUsesNewDiametersAndAnExistingChamberIsNotReconstructed() {
        RouteNode branch = new RouteNode("root", "new_branch_chamber", new RouteCoordinate(0, 0), true, false, 0, null);
        RouteNode existing = new RouteNode("consumer", "existing_chamber_tie_in", new RouteCoordinate(10, 0), true, true, 2, "old");
        assertThat(OfficialChamberSizing.diameters(List.of(branch, existing), List.of(edge(600))))
                .containsOnlyKeys("root").containsEntry("root", 600);
    }

    @Test
    void unresolvedSupportAndUnsizedEdgesCannotProduceAnApparentlyCompleteCost() {
        assertThatThrownBy(() -> OfficialChamberSizing.diameters(List.of(root(null)), List.of(edge(100))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("support diameter");
        assertThatThrownBy(() -> OfficialChamberSizing.diameters(List.of(root(1000)), List.of(edge(null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("edge diameter");
        assertThatThrownBy(() -> OfficialChamberSizing.diameters(List.of(root(1000)), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no incident");
    }

    private RouteNode root(Integer support) {
        return new RouteNode("root", "new_tie_in_chamber", new RouteCoordinate(0, 0), true, true, 2, "network", support);
    }
    private RouteEdge edge(Integer diameter) {
        return new RouteEdge("edge", "root", "consumer", 10, List.of(), List.of(), BigDecimal.ONE, diameter);
    }
}
