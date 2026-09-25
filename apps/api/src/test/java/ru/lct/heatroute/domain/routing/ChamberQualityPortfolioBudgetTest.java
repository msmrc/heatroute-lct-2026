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
 * Частичный обязательный ремонт не становится выбранной ролью до исправления всех камер.
 * Цена и заявленная длина синтетические: физический пересчёт проверяют интеграционные тесты.
 */
class ChamberQualityPortfolioBudgetTest {
    private final FinishedChamberQualitySelectionTest fixture = new FinishedChamberQualitySelectionTest();
    private final FinishedRouteVariantSelector selector = new FinishedRouteVariantSelector();
    private final EngineeringRouteEvaluator engineering = new EngineeringRouteEvaluator();

    @Test
    void anInvalidCheapPriceAnchorCannotDisplaceTheCompleteMandatoryRepair() {
        RouteVariant anchor = compound("invalid-anchor", 0, "100", "100");
        RouteVariant partial = compound("partial", 1, "104", "99");
        RouteVariant complete = compound("complete", 3, "130", "110");
        assertStrictRepair(anchor, partial);
        for (boolean depth : List.of(false, true)) {
            for (boolean reverse : List.of(false, true)) {
                List<RouteVariant> candidates = ordered(reverse, anchor, partial, complete);
                assertThat(selector.select(candidates, depth)).hasSize(3)
                        .allSatisfy(role -> assertSource(role, complete));
                assertThat(selector.selectChamberQuality(candidates, depth, anchor)).hasSize(3)
                        .allSatisfy(role -> assertSource(role, complete));
            }
        }
    }

    @Test
    void roleFeedbackCannotBringBackAShorterPartialRepair() {
        RouteVariant anchor = compound("invalid-anchor", 0, "100", "100");
        RouteVariant partial = compound("short-partial", 2, "90", "98");
        RouteVariant complete = compound("complete", 3, "120", "110");
        for (boolean depth : List.of(false, true)) {
            List<RouteVariant> roles = selector.select(List.of(anchor, partial, complete), depth);
            for (int repeat = 0; repeat < 2; repeat++) {
                List<RouteVariant> feedback = new ArrayList<>(roles);
                feedback.add(partial); feedback.add(anchor); Collections.reverse(feedback);
                roles = selector.selectChamberQuality(feedback, depth, anchor);
                assertThat(roles).hasSize(3).allSatisfy(role -> assertSource(role, complete));
            }
        }
    }

    @Test
    void intermediateRepairsStayInternalUntilAllChambersPass() {
        RouteVariant seed = compound("seed", 0, "100", "100");
        RouteVariant first = compound("first", 1, "110", "110");
        RouteVariant second = compound("second", 2, "120", "120");
        RouteVariant last = compound("last", 3, "130", "130");
        for (boolean depth : List.of(false, true)) {
            AtomicInteger expansions = new AtomicInteger();
            List<RouteVariant> pool = ChamberQualityRefinementSearch.alternatives(seed, depth,
                    current -> List.of(List.of(first, second, last).get(expansions.getAndIncrement())));
            assertThat(expansions.get()).isEqualTo(3);
            assertThat(pool).containsExactly(last);
            assertThat(selector.select(pool, depth)).hasSize(3).allSatisfy(role -> assertSource(role, last));
            assertThat(last.getConnectedDemandCount()).isEqualTo(seed.getConnectedDemandCount());
        }
    }

    @Test
    void boundedPartialProgressCanContinueButCannotEnterTheRolePortfolio() {
        RouteVariant seed = compound("seed", 0, "100", "100", 4);
        RouteVariant first = compound("first", 1, "110", "110", 4);
        RouteVariant second = compound("second", 2, "120", "120", 4);
        RouteVariant third = compound("third", 3, "130", "130", 4);
        AtomicInteger expansions = new AtomicInteger();
        RouteVariant progress = ChamberQualityRefinementSearch.advanceRepair(seed, true,
                current -> List.of(List.of(first, second, third).get(expansions.getAndIncrement())));
        assertThat(progress).isSameAs(third);
        assertThat(expansions.get()).isEqualTo(3);
        assertThat(selector.select(List.of(seed, progress), true)).isEmpty();
        RouteVariant complete = compound("a-complete", 4, "140", "140", 4);
        RouteVariant tie = compound("b-complete", 4, "140", "140", 4);
        for (boolean reverse : List.of(false, true)) {
            RouteVariant result = ChamberQualityRefinementSearch.improve(progress, true,
                    current -> ordered(reverse, complete, tie));
            assertThat(result).isSameAs(complete);
            assertThat(result.getConnectedDemandCount()).isEqualTo(8);
            assertThat(new ExpertChamberRouteValidator().validate(result.getNodes(), result.getEdges())).isEmpty();
        }
    }

    @Test
    void aStrictValidAnchorStillEnforcesBothFivePercentBoundaries() {
        RouteVariant anchor = compound("valid-anchor", 3, "100", "100");
        for (boolean depth : List.of(false, true)) {
            RouteVariant boundary = compound("boundary", 3, "105", "105");
            assertThat(selector.selectChamberQuality(List.of(boundary), depth, anchor)).hasSize(3)
                    .allSatisfy(role -> assertSource(role, boundary));
            for (RouteVariant outside : List.of(compound("price-over", 3, "105.001", "105"),
                    compound("length-over", 3, "105", "105.001"))) {
                assertThat(selector.selectChamberQuality(List.of(outside), depth, anchor))
                        .extracting(RouteVariant::getId).containsExactly("shortest", "cheapest");
            }
        }
    }

    @Test
    void validRoleFeedbackCannotCompoundTheOriginalPriceOrLengthBudget() {
        RouteVariant anchor = compound("valid-anchor", 3, "100", "100");
        RouteVariant first = compound("first", 3, "104", "104");
        RouteVariant compoundIncrease = compound("compound", 3, "108", "108");
        for (boolean depth : List.of(false, true)) {
            List<RouteVariant> roles = selector.selectChamberQuality(List.of(first), depth, anchor);
            for (int repeat = 0; repeat < 2; repeat++) {
                List<RouteVariant> feedback = new ArrayList<>(roles);
                feedback.add(compoundIncrease);
                roles = selector.selectChamberQuality(feedback, depth, anchor);
                assertThat(roles).hasSize(3).allSatisfy(role -> assertSource(role, first));
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
        if (repaired == chamberCount) assertThat(new ExpertChamberRouteValidator().validate(nodes, edges)).isEmpty();
        else assertThat(new ExpertChamberRouteValidator().validate(nodes, edges))
                .extracting(RouteValidationIssue::getCode).containsOnly("EXPERT_CHAMBER_OBLIQUE_ENTRY");
        assertThat(result.getConnectedDemandCount()).isEqualTo(2 * chamberCount);
        return result;
    }

    private RouteCoordinate shift(RouteCoordinate point, double offsetY) {
        return new RouteCoordinate(point.getXM().doubleValue(), point.getYM().doubleValue() + offsetY);
    }
}
