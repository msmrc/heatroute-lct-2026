package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;

/**
 * Проверяет бюджет между search и повторным отбором ролей на трёх независимых камерах.
 * Цена и заявленная длина синтетические: физический пересчёт проверяют интеграционные тесты.
 */
class ChamberQualityPortfolioBudgetTest {
    private final FinishedChamberQualitySelectionTest fixture = new FinishedChamberQualitySelectionTest();
    private final FinishedRouteVariantSelector selector = new FinishedRouteVariantSelector();
    private final EngineeringRouteEvaluator engineering = new EngineeringRouteEvaluator();

    @Test
    void originalPriceAnchorSurvivesExplicitAndDefaultRoleFeedback() {
        RouteVariant anchor = compound("balanced", 0, "100", "100");
        RouteVariant first = compound("repair-104", 1, "104", "99");
        RouteVariant second = compound("repair-108", 2, "108", "98");
        assertStrictRepair(anchor, first);
        assertStrictRepair(first, second);
        assertThat(second.getEconomics().getCalculatedCost())
                .isGreaterThan(anchor.getEconomics().getCalculatedCost().multiply(new BigDecimal("1.05")))
                .isLessThan(first.getEconomics().getCalculatedCost().multiply(new BigDecimal("1.05")));

        for (boolean depth : List.of(false, true)) {
            for (boolean reverse : List.of(false, true)) {
                List<RouteVariant> inputs = ordered(reverse, anchor, first, second);
                assertSource(role(selector.select(inputs, depth), "balanced"), anchor);
                List<RouteVariant> selected = selector.selectChamberQuality(inputs, depth, anchor);
                assertSource(role(selected, "balanced"), first);
                assertSource(role(selected, "shortest"), second);
                assertSource(role(selected, "cheapest"), anchor);

                // Возвращаем именно назначенные роли: вариант 108 уже присутствует как shortest.
                List<RouteVariant> explicitFeedback = new ArrayList<>(selected);
                List<RouteVariant> defaultFeedback = new ArrayList<>(selected);
                for (int repeat = 0; repeat < 2; repeat++) {
                    Collections.reverse(explicitFeedback);
                    Collections.reverse(defaultFeedback);
                    explicitFeedback = new ArrayList<>(selector.selectChamberQuality(explicitFeedback, depth, anchor));
                    defaultFeedback = new ArrayList<>(selector.select(defaultFeedback, depth));
                    assertSource(role(explicitFeedback, "balanced"), first);
                    assertSource(role(defaultFeedback, "balanced"), first);
                    assertSource(role(explicitFeedback, "cheapest"), anchor);
                    assertSource(role(defaultFeedback, "cheapest"), anchor);
                }
            }
        }
    }

    @Test
    void explicitLengthAnchorAlsoConstrainsCheaperEconomicImprovementOnFeedback() {
        RouteVariant anchor = compound("balanced", 0, "100000000", "100");
        RouteVariant first = compound("repair-104m", 1, "104000000", "104");
        RouteVariant next = compound("cheaper-108m", 2, "90000000", "108");
        assertStrictRepair(anchor, first);
        assertStrictRepair(first, next);
        assertThat(next.getTotalLengthM())
                .isGreaterThan(anchor.getTotalLengthM().multiply(new BigDecimal("1.05")))
                .isLessThan(first.getTotalLengthM().multiply(new BigDecimal("1.05")));
        assertThat(next.getEconomics().getCalculatedCost()).isLessThan(first.getEconomics().getCalculatedCost());
        assertThat(next.getEconomics().getScore()).isLessThan(anchor.getEconomics().getScore())
                .isLessThan(first.getEconomics().getScore());

        for (boolean depth : List.of(false, true)) {
            for (boolean reverse : List.of(false, true)) {
                List<RouteVariant> selected = selector.selectChamberQuality(ordered(reverse, anchor, first), depth, anchor);
                assertSource(role(selected, "balanced"), first);
                List<RouteVariant> feedback = new ArrayList<>(selected);
                feedback.add(next);
                if (reverse) Collections.reverse(feedback);

                // Общий pool сохраняет дешёвую сеть; исходный бюджет ограничивает только balanced.
                List<RouteVariant> result = selector.selectChamberQuality(feedback, depth, anchor);
                assertSource(role(result, "balanced"), first);
                assertThat(role(result, "balanced").getTotalLengthM()).isEqualByComparingTo("104");
                assertSource(role(result, "shortest"), anchor);
                assertSource(role(result, "cheapest"), next);
                assertThat(role(result, "cheapest").getEconomics().getCalculatedCost()).isEqualByComparingTo("90000000");
            }
        }
    }

    @Test
    void intermediateRepairRemainsSelectableWhenFinalWinnerExceedsBalancedLengthBudget() {
        RouteVariant anchor = compound("balanced", 0, "100", "100");
        RouteVariant shortest = compound("shortest", 0, "100", "100");
        RouteVariant seed = compound("cheapest", 0, "90", "110");
        RouteVariant intermediate = compound("repair-104m", 1, "94", "104");
        RouteVariant finalWinner = compound("repair-110m", 2, "94", "110");
        assertStrictRepair(seed, intermediate);
        assertStrictRepair(intermediate, finalWinner);
        assertThat(finalWinner.getTotalLengthM())
                .isGreaterThan(anchor.getTotalLengthM().multiply(new BigDecimal("1.05")))
                .isLessThan(seed.getTotalLengthM().multiply(new BigDecimal("1.05")));

        for (boolean depth : List.of(false, true)) {
            AtomicInteger expansions = new AtomicInteger();
            List<RouteVariant> pool = ChamberQualityRefinementSearch.alternatives(seed, depth, current -> {
                int pass = expansions.getAndIncrement();
                assertThat(pass).isLessThan(3);
                if (pass == 2) {
                    assertThat(current).isSameAs(finalWinner);
                    return List.of();
                }
                assertThat(current).isSameAs(pass == 0 ? seed : intermediate);
                return List.of(pass == 0 ? intermediate : finalWinner);
            });
            assertThat(expansions.get()).isEqualTo(3);
            assertThat(pool).containsExactlyInAnyOrder(intermediate, finalWinner);

            List<RouteVariant> originals = List.of(anchor, shortest, seed);
            List<RouteVariant> finalOnly = new ArrayList<>(originals);
            finalOnly.add(finalWinner);
            assertSource(role(selector.selectChamberQuality(finalOnly, depth, anchor), "balanced"), anchor);

            for (boolean reverse : List.of(false, true)) {
                List<RouteVariant> portfolio = new ArrayList<>(originals);
                portfolio.addAll(pool);
                if (reverse) Collections.reverse(portfolio);
                List<RouteVariant> selected = selector.selectChamberQuality(portfolio, depth, anchor);
                assertSource(role(selected, "balanced"), intermediate);
                assertSource(role(selected, "shortest"), anchor);
                assertSource(role(selected, "cheapest"), seed);
                assertThat(role(selected, "balanced").getTotalLengthM()).isEqualByComparingTo("104");
            }
        }
    }

    @Test
    void retainsAllSixAdmittedNeighboursPerSeedAndChoosesDeterministicallyAcrossTheirOrder() {
        RouteVariant anchor = compound("balanced", 0, "100", "110", 4);
        RouteVariant first = compound("a-first", 1, "101", "108", 4);
        RouteVariant firstTie = compound("b-first", 1, "101", "108", 4);
        RouteVariant second = compound("a-second", 2, "102", "107", 4);
        RouteVariant secondTie = compound("b-second", 2, "102", "107", 4);
        RouteVariant third = compound("a-third", 3, "103", "106", 4);
        RouteVariant thirdTie = compound("b-third", 3, "103", "106", 4);
        assertStrictRepair(anchor, first);
        assertStrictRepair(first, second);

        for (boolean reverseFirst : List.of(false, true)) {
            for (boolean reverseSecond : List.of(false, true)) {
                AtomicInteger expansions = new AtomicInteger();
                List<RouteVariant> pool = ChamberQualityRefinementSearch.alternatives(anchor, true, current -> {
                    int pass = expansions.getAndIncrement();
                    assertThat(pass).isLessThan(3);
                    assertThat(current).isSameAs(pass == 0 ? anchor : pass == 1 ? first : second);
                    return pass == 0 ? ordered(reverseFirst, first, firstTie)
                            : pass == 1 ? ordered(reverseSecond, second, secondTie) : ordered(reverseFirst, third, thirdTie);
                });
                assertThat(expansions.get()).isEqualTo(3);
                assertThat(pool).hasSize(6).containsExactlyInAnyOrder(first, firstTie, second, secondTie, third, thirdTie);
                // Ещё одна нерегулярная камера остаётся: остановка вызвана бюджетом трёх проходов.
                assertThat(engineering.evaluate(third.getEdges()).irregularJunctionAngleCount()).isPositive();
                List<RouteVariant> portfolio = new ArrayList<>(pool);
                portfolio.add(anchor);
                assertSource(role(selector.selectChamberQuality(portfolio, true, anchor), "balanced"), third);
                Collections.reverse(portfolio);
                assertSource(role(selector.selectChamberQuality(portfolio, true, anchor), "balanced"), third);
            }
        }
    }

    private void assertStrictRepair(RouteVariant before, RouteVariant after) {
        var original = engineering.evaluate(before.getEdges());
        var repaired = engineering.evaluate(after.getEdges());
        assertThat(repaired.isCompliant()).isTrue();
        assertThat(repaired.irregularJunctionAngleCount()).isLessThan(original.irregularJunctionAngleCount());
        assertThat(repaired.preservesJunctionQualityOf(original)).isTrue();
    }

    private RouteVariant role(List<RouteVariant> variants, String id) {
        return variants.stream().filter(variant -> id.equals(variant.getId())).findFirst().orElseThrow();
    }

    private void assertSource(RouteVariant actual, RouteVariant expected) {
        assertThat(actual).usingRecursiveComparison().ignoringFields("id", "strategy", "rank").isEqualTo(expected);
        assertThat(actual.getEdges()).containsExactlyElementsOf(expected.getEdges());
    }

    private List<RouteVariant> ordered(boolean reverse, RouteVariant... variants) {
        List<RouteVariant> result = new ArrayList<>(List.of(variants));
        if (reverse) Collections.reverse(result);
        return result;
    }

    /** Три разнесённых дерева сохраняют ID/координаты корней и потребителей при ремонте камер. */
    private RouteVariant compound(String id, int repaired, String costValue, String lengthValue) {
        return compound(id, repaired, costValue, lengthValue, 3);
    }

    private RouteVariant compound(String id, int repaired, String costValue, String lengthValue, int chamberCount) {
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        List<RouteConnection> connections = new ArrayList<>();
        for (int index = 0; index < chamberCount; index++) {
            RouteVariant piece = fixture.variant("part", 0, index < repaired, false, false);
            String suffix = ":" + index;
            double offsetY = index * 1000;
            for (RouteNode node : piece.getNodes()) nodes.add(new RouteNode(node.getId() + suffix, node.getNodeType(),
                    shift(node.getCoordinate(), offsetY), node.isChamber(), node.isRoot(), node.getBaseIncidentSections(),
                    node.getTargetId() == null ? null : node.getTargetId() + suffix, node.getExistingIncidentDiameter()));
            for (RouteEdge edge : piece.getEdges()) {
                List<RouteCoordinate> points = edge.getCoordinates().stream()
                        .map(point -> shift(point, offsetY)).collect(Collectors.toList());
                edges.add(new RouteEdge(edge.getId() + suffix, edge.getUpstreamNodeId() + suffix, edge.getDownstreamNodeId() + suffix,
                        edge.getLengthM().doubleValue(), points,
                        List.of(new RouteSection("base", null, null, points, edge.getLengthM().doubleValue(), null)),
                        edge.getFlowTph(), edge.getDiameter(), edge.getDepthProfile()));
            }
            for (RouteConnection connection : piece.getConnections()) connections.add(new RouteConnection(
                    connection.getDemandId() + suffix, connection.getConnectionPointId() + suffix,
                    connection.getFlowTph(), connection.getStatus(), connection.getReason()));
        }
        BigDecimal cost = new BigDecimal(costValue), length = new BigDecimal(lengthValue);
        VariantEconomics economics = new VariantEconomics(true, cost, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cost, length, BigDecimal.ZERO, length,
                new OfficialEconomics().score(cost, length), List.of());
        String strategy = "cheapest".equals(id) || "shortest".equals(id) ? id : "engineering";
        RouteVariant result = new RouteVariant(id, strategy, nodes, edges, connections, length,
                List.of(), List.of(), List.of(), ExistingNetworkReconstructionResult.empty(), economics, null);
        assertThat(engineering.evaluate(edges).isCompliant()).isTrue();
        assertThat(engineering.evaluate(edges).irregularJunctionAngleCount()).isEqualTo(chamberCount - repaired);
        assertThat(new ExpertChamberRouteValidator().validate(nodes, edges)).isEmpty();
        assertThat(result.getConnectedDemandCount()).isEqualTo(2 * chamberCount);
        return result;
    }

    private RouteCoordinate shift(RouteCoordinate point, double offsetY) {
        return new RouteCoordinate(point.getXM().doubleValue(), point.getYM().doubleValue() + offsetY);
    }
}
