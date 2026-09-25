package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;

class ExpertChamberSelectionTest {
    private final FinishedRouteVariantSelector selector = new FinishedRouteVariantSelector();

    @Test
    void shortChamberSectionCannotWinAnyRoleEvenWithLowerCostAndHigherCoverage() {
        RouteVariant invalid = variant("balanced", 4.587, true, 2, 1);
        RouteVariant valid = variant("valid", 10, true, 1, 100);

        List<RouteVariant> selected = selector.select(List.of(invalid, valid));

        assertThat(selected).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
        assertThat(selected).allSatisfy(role -> {
            assertThat(role.getEdges()).containsExactlyElementsOf(valid.getEdges());
            assertThat(role.getConnectedDemandCount()).isEqualTo(1);
        });
    }

    @Test
    void returnsNoRolesWhenAllCandidatesViolateChamberSpacing() {
        assertThat(selector.select(List.of(variant("balanced", 9.999, true, 1, 1)))).isEmpty();
    }

    @Test
    void connectedBuildingCannotStartAtAnUnmarkedTechnicalJunction() {
        assertThat(selector.select(List.of(variant("balanced", 10, false, 2, 1)))).isEmpty();
    }

    @Test
    void exactTenMetresBetweenChambersAndShortBuildingSpurRemainEligible() {
        RouteVariant valid = variant("balanced", 10, true, 1, 1);
        assertThat(selector.select(List.of(valid))).hasSize(3)
                .allSatisfy(role -> assertThat(role.getEdges()).containsExactlyElementsOf(valid.getEdges()));
    }

    private RouteVariant variant(String id, double chamberDistanceM, boolean chamber, int coverage, int cost) {
        RouteNode root = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(0, 0),
                true, true, 2, "existing");
        RouteNode junction = new RouteNode("junction", chamber ? "new_chamber" : "technical",
                new RouteCoordinate(chamberDistanceM, 0), chamber, false, 0, null);
        List<RouteNode> nodes = new ArrayList<>(List.of(root, junction));
        List<RouteEdge> edges = new ArrayList<>(List.of(edge("trunk", root, junction)));
        List<RouteConnection> connections = new ArrayList<>();
        for (int i = 0; i < coverage; i++) {
            RouteNode demand = new RouteNode("demand:" + i, "demand_connection",
                    new RouteCoordinate(chamberDistanceM, i == 0 ? 2 : -2), false, false, 0, "oks:" + i);
            nodes.add(demand);
            edges.add(edge("spur:" + i, junction, demand));
            connections.add(new RouteConnection("oks:" + i, demand.getId(), BigDecimal.ONE, "connected", null));
        }
        BigDecimal length = edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal price = BigDecimal.valueOf(cost);
        VariantEconomics economics = new VariantEconomics(true, price, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, price, length, BigDecimal.ZERO,
                length, price, List.of());
        return new RouteVariant(id, "engineering", nodes, edges, connections, length, List.of(), List.of(),
                ExistingNetworkReconstructionResult.empty(), economics, null);
    }

    private RouteEdge edge(String id, RouteNode from, RouteNode to) {
        return new RouteEdge(id, from.getId(), to.getId(),
                from.getCoordinate().toCoordinate().distance(to.getCoordinate().toCoordinate()),
                List.of(from.getCoordinate(), to.getCoordinate()), List.of(), BigDecimal.ONE, 50);
    }
}
