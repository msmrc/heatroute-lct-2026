package ru.lct.heatroute.domain.economics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;

/** Приложение организатора, §3.2: новая камера уже включает присоединение к существующей сети. */
class OfficialChamberTieInCostTest {
    private final OfficialVariantEconomicsCalculator calculator = new OfficialVariantEconomicsCalculator(
            new OfficialPipeCatalog(), new OfficialEconomics());

    @Test
    void oneRayAtANewChamberHasNoSeparateTieInCharge() {
        Network network = network("new", "new_tie_in_chamber", true, 1, 0);
        assertCosts(network, "0", "3000000", "3740230");
    }

    @Test
    void twoRaysAtANewChamberStillHaveNoSeparateTieInCharge() {
        Network network = network("new", "new_tie_in_chamber", true, 2, 0);
        assertCosts(network, "0", "3000000", "4480460");
    }

    @Test
    void oneRayAtAnExistingChamberCostsFiveMillion() {
        Network network = network("existing", "existing_chamber_tie_in", true, 1, 0);
        assertCosts(network, "5000000", "0", "5740230");
    }

    @Test
    void twoRaysAtTheSameExistingChamberCostTenMillion() {
        Network network = network("existing", "existing_chamber_tie_in", true, 2, 0);
        assertCosts(network, "10000000", "0", "11480460");
    }

    @Test
    void mixedRootsChargeOnlyRaysAtExistingChambers() {
        Network network = combine(network("new", "new_tie_in_chamber", true, 2, 0),
                network("existing", "existing_chamber_tie_in", true, 2, 100));
        assertCosts(network, "10000000", "3000000", "15960920");
    }

    @Test
    void requiresBothExistingChamberTypeAndRootFlagLikeTheExporter() {
        Network network = combine(network("not-root", "existing_chamber_tie_in", false, 1, 0),
                network("not-chamber", "technical_node", true, 1, 100));
        assertCosts(network, "0", "0", "1480460");
    }

    @Test
    void marginalCostOfRemovingANewChamberIncludesItsConstructionButNoTieIns() {
        Network one = network("new", "new_tie_in_chamber", true, 1, 0);
        Network two = network("new", "new_tie_in_chamber", true, 2, 0);
        assertThat(calculator.marginalConnectionCost(one.edges, one.nodes)).isEqualByComparingTo("3740230");
        assertThat(calculator.marginalConnectionCost(two.edges, two.nodes)).isEqualByComparingTo("4480460");
    }

    @Test
    void marginalCostAtAnExistingChamberRetainsPerRayCharges() {
        Network one = network("existing", "existing_chamber_tie_in", true, 1, 0);
        Network two = network("existing", "existing_chamber_tie_in", true, 2, 0);
        assertThat(calculator.marginalConnectionCost(one.edges, one.nodes)).isEqualByComparingTo("5740230");
        assertThat(calculator.marginalConnectionCost(two.edges, two.nodes)).isEqualByComparingTo("11480460");
    }

    @Test
    void mixedMarginalCostMatchesTheSameNetworksCompleteConstructionCost() {
        Network network = combine(network("new", "new_tie_in_chamber", true, 2, 0),
                network("existing", "existing_chamber_tie_in", true, 2, 100));
        assertThat(calculator.marginalConnectionCost(network.edges, network.nodes)).isEqualByComparingTo("15960920");
    }

    @Test
    void aSpuriousNewChamberTieInMustNotMakeAConnectionMoreExpensiveThanItsPenalty() {
        Network network = network("new", "new_tie_in_chamber", true, 1, 0);
        RouteEdge longSpur = new RouteEdge("long", "new", "new-demand-0", 1300,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(1300, 0)), List.of(), BigDecimal.ONE, 50);
        RouteConnection connection = new RouteConnection("demand", "point", BigDecimal.ONE, "connected", null);
        // ДУ50: 1300 × 74023 + 3000000 = 99229900 < штраф 100500000; лишние 5 млн меняют решение.
        assertThat(calculator.marginalConnectionCost(List.of(longSpur), network.nodes))
                .isEqualByComparingTo("99229900");
        assertThat(calculator.connectionCostsMoreThanPenalty(connection, List.of(longSpur), network.nodes)).isFalse();
        // Явный legacy-флаг отдельной врезки сохраняет прежний контракт; production его не вызывает.
        assertThat(calculator.connectionCostsMoreThanPenalty(connection, List.of(longSpur), network.nodes, true)).isTrue();
        assertThat(calculator.connectionCostsMoreThanPenalty(connection, List.of(longSpur), network.nodes, false)).isFalse();
    }

    private void assertCosts(Network network, String tieInCost, String chamberCost, String constructionCost) {
        for (boolean reconstructionRequired : List.of(false, true)) {
            VariantEconomics result = calculator.calculate(network.nodes, network.edges, List.of(),
                    ExistingNetworkReconstructionResult.empty(), reconstructionRequired);
            assertThat(result.isComplete()).isTrue();
            assertThat(result.getTieInCost()).isEqualByComparingTo(tieInCost);
            assertThat(result.getChamberConstructionCost()).isEqualByComparingTo(chamberCost);
            assertThat(result.getConstructionCost()).isEqualByComparingTo(constructionCost);
            assertThat(result.getCalculatedCost()).isEqualByComparingTo(constructionCost);
            BigDecimal expectedScore = new BigDecimal(constructionCost).divide(new BigDecimal("25000000"))
                    .multiply(new BigDecimal("0.7"))
                    .add(BigDecimal.valueOf(network.edges.size() * 10L).divide(new BigDecimal("100"))
                            .multiply(new BigDecimal("0.3")));
            assertThat(result.getScore()).isEqualByComparingTo(expectedScore);
        }
    }

    private Network network(String id, String type, boolean root, int rays, double offset) {
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        RouteCoordinate origin = new RouteCoordinate(offset, 0);
        nodes.add(new RouteNode(id, type, origin, !"technical_node".equals(type), root, 2, "input-" + id,
                "new_tie_in_chamber".equals(type) ? 50 : null));
        for (int i = 0; i < rays; i++) {
            String demandId = id + "-demand-" + i;
            RouteCoordinate end = new RouteCoordinate(offset + (i == 0 ? 10 : 0), i == 0 ? 0 : 10);
            nodes.add(new RouteNode(demandId, "demand_connection", end, false, false, 0, demandId));
            edges.add(new RouteEdge(id + "-edge-" + i, id, demandId, 10,
                    List.of(origin, end), List.of(), BigDecimal.ONE, 50));
        }
        return new Network(nodes, edges);
    }

    private Network combine(Network first, Network second) {
        List<RouteNode> nodes = new ArrayList<>(first.nodes); nodes.addAll(second.nodes);
        List<RouteEdge> edges = new ArrayList<>(first.edges); edges.addAll(second.edges);
        return new Network(nodes, edges);
    }

    private static final class Network {
        private final List<RouteNode> nodes;
        private final List<RouteEdge> edges;
        private Network(List<RouteNode> nodes, List<RouteEdge> edges) { this.nodes = nodes; this.edges = edges; }
    }
}
