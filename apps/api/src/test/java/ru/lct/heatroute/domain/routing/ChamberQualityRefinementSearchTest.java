package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;

/** Границы локального поиска: callback предлагает сети, допуск и выбор выполняет настоящий компонент. */
class ChamberQualityRefinementSearchTest {
    private final FinishedChamberQualitySelectionTest fixture = new FinishedChamberQualitySelectionTest();
    private final EngineeringRouteEvaluator engineering = new EngineeringRouteEvaluator();

    @Test
    void acceptsARealCheaperShorterRepairWithoutChangingTheSeed() {
        RouteVariant seed = fixture.variant("seed", 31, false, false, false);
        RouteVariant improved = fixture.variant("improved", 31, true, false, false);
        AtomicInteger expansions = new AtomicInteger();
        RouteVariant result = ChamberQualityRefinementSearch.improve(seed, true, current -> {
            expansions.incrementAndGet();
            assertThat(current).isSameAs(seed);
            return List.of(improved);
        });
        assertThat(result).isSameAs(improved);
        assertThat(expansions.get()).isEqualTo(1);
        assertThat(engineering.evaluate(seed.getEdges()).irregularJunctionAngleCount()).isPositive();
    }

    @Test
    void stopsAfterThreeStrictImprovementsEvenWhenAnotherRepairExists() {
        RouteVariant seed = compound(0, 4);
        AtomicInteger expansions = new AtomicInteger();
        RouteVariant result = ChamberQualityRefinementSearch.improve(seed, true,
                current -> List.of(compound(expansions.incrementAndGet(), 4)));
        assertThat(expansions.get()).isEqualTo(3);
        assertThat(engineering.evaluate(result.getEdges()).irregularJunctionAngleCount()).isEqualTo(1);
        assertThat(result.getConnectedDemandCount()).isEqualTo(8);
    }

    @Test
    void thirdStrictRepairCanFinishThreeDistinctChambersWithinTheOriginalBudget() {
        RouteVariant seed = compound(0);
        AtomicInteger expansions = new AtomicInteger();
        RouteVariant result = ChamberQualityRefinementSearch.improve(seed, true,
                current -> List.of(compound(expansions.incrementAndGet())));
        assertThat(expansions.get()).isEqualTo(3);
        assertThat(engineering.evaluate(result.getEdges()).irregularJunctionAngleCount()).isZero();
        assertThat(result.getConnectedDemandCount()).isEqualTo(6);
    }

    @Test
    void fivePercentPriceBudgetNeverAccumulatesAcrossPasses() {
        RouteVariant seed = priced(compound(0), "1000");
        RouteVariant first = priced(compound(1), "1040");
        RouteVariant second = priced(compound(2), "1080");
        AtomicInteger expansions = new AtomicInteger();
        RouteVariant result = ChamberQualityRefinementSearch.improve(seed, true,
                current -> List.of(expansions.getAndIncrement() == 0 ? first : second));
        assertThat(result).isSameAs(first);
        assertThat(expansions.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1050", "1050.01"})
    void fivePercentPriceIncludesTheBoundaryButNotAnotherKopeck(String cost) {
        RouteVariant seed = priced(fixture.variant("seed", 0, false, false, false), "1000");
        RouteVariant repair = priced(fixture.variant("repair", 0, true, false, false), cost);
        assertThat(ChamberQualityRefinementSearch.improve(seed, true, current -> List.of(repair)))
                .isSameAs("1050".equals(cost) ? repair : seed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"105", "105.001"})
    void fivePercentLengthIncludesTheBoundaryButNotAnotherMillimetre(String length) {
        RouteVariant seed = withLength(fixture.variant("seed", 0, false, false, false), "100");
        RouteVariant repair = withLength(fixture.variant("repair", 0, true, false, false), length);
        assertThat(ChamberQualityRefinementSearch.improve(seed, true, current -> List.of(repair)))
                .isSameAs("105".equals(length) ? repair : seed);
    }

    @Test
    void lengthBudgetDoesNotAccumulateAndCandidateOrderingIsDeterministic() {
        RouteVariant seed = withLength(compound(0), "100");
        RouteVariant first = withLength(compound(1), "104");
        RouteVariant second = withLength(compound(2), "108");
        AtomicInteger expansions = new AtomicInteger();
        assertThat(ChamberQualityRefinementSearch.improve(seed, true,
                current -> List.of(expansions.getAndIncrement() == 0 ? first : second))).isSameAs(first);
        RouteVariant expensive = priced(first, "999999999");
        for (List<RouteVariant> order : List.of(List.of(first, expensive), List.of(expensive, first))) {
            assertThat(ChamberQualityRefinementSearch.improve(seed, true, current -> order)).isSameAs(first);
        }
    }

    @Test
    void equalCountDoesNotAllowReplacingOrDuplicatingConsumers() {
        RouteVariant seed = fixture.variant("seed", 0, false, false, false);
        RouteVariant repair = fixture.variant("repair", 0, true, false, false);
        RouteVariant duplicate = copy(repair, repair.getNodes(), repair.getEdges(),
                List.of(repair.getConnections().get(0), repair.getConnections().get(0)));
        RouteConnection a = repair.getConnections().get(0);
        RouteVariant flowChanged = copy(repair, repair.getNodes(), repair.getEdges(), List.of(
                new RouteConnection(a.getDemandId(), a.getConnectionPointId(), BigDecimal.TEN, a.getStatus(), a.getReason()),
                repair.getConnections().get(1)));
        assertThat(ChamberQualityRefinementSearch.improve(seed, true, current -> List.of(duplicate, flowChanged))).isSameAs(seed);
    }

    @Test
    void failedOrUnchangedNeighboursDoNotConsumeAnotherPass() {
        RouteVariant seed = fixture.variant("seed", 0, false, false, false);
        RouteVariant bad = fixture.variant("bad-spacing", 0, true, false, true);
        AtomicInteger expansions = new AtomicInteger();
        assertThat(ChamberQualityRefinementSearch.improve(seed, true, current -> {
            expansions.incrementAndGet();
            return List.of(seed, bad);
        })).isSameAs(seed);
        assertThat(expansions.get()).isEqualTo(1);
    }

    @Test
    void lostConnectionOrMovedRootCannotBeAnImprovement() {
        RouteVariant seed = fixture.variant("seed", 0, false, false, false);
        RouteVariant repair = fixture.variant("repair", 0, true, false, false);
        RouteVariant missing = copy(repair, repair.getNodes(), repair.getEdges(), repair.getConnections().subList(0, 1));
        List<RouteNode> nodes = new ArrayList<>(repair.getNodes());
        RouteNode root = nodes.get(0);
        nodes.set(0, new RouteNode(root.getId(), root.getNodeType(), new RouteCoordinate(1, 1),
                true, true, root.getBaseIncidentSections(), root.getTargetId(), root.getExistingIncidentDiameter()));
        RouteVariant moved = copy(repair, nodes, repair.getEdges(), repair.getConnections());
        assertThat(ChamberQualityRefinementSearch.improve(seed, true, current -> List.of(missing, moved))).isSameAs(seed);
    }

    @Test
    void enabledDepthDoesNotAcceptAMissingProfileEvenWhenAllRaysImprove() {
        RouteVariant seed = fixture.variant("seed", 0, false, false, false);
        RouteVariant repair = fixture.variant("repair", 0, true, false, false);
        List<RouteEdge> edges = new ArrayList<>(repair.getEdges());
        RouteEdge edge = edges.get(0);
        edges.set(0, new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                edge.getLengthM().doubleValue(), edge.getCoordinates(), edge.getSections(), edge.getFlowTph(), edge.getDiameter()));
        RouteVariant missing = copy(repair, repair.getNodes(), edges, repair.getConnections());
        assertThat(ChamberQualityRefinementSearch.improve(seed, true, current -> List.of(missing))).isSameAs(seed);
        assertThat(ChamberQualityRefinementSearch.improve(seed, false, current -> List.of(missing))).isSameAs(missing);
    }

    @Test
    void rejectsMoreThanTwoFinishedNeighbours() {
        RouteVariant seed = fixture.variant("seed", 0, false, false, false);
        assertThatThrownBy(() -> ChamberQualityRefinementSearch.improve(seed, true,
                current -> List.of(seed, seed, seed))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationAfterExpansionDoesNotPublishTheRepair() {
        RouteVariant seed = fixture.variant("seed", 0, false, false, false);
        RouteVariant repair = fixture.variant("repair", 0, true, false, false);
        try {
            assertThatThrownBy(() -> ChamberQualityRefinementSearch.improve(seed, true, current -> {
                Thread.currentThread().interrupt();
                return List.of(repair);
            })).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private RouteVariant compound(int repaired) {
        return compound(repaired, 3);
    }

    private RouteVariant compound(int repaired, int chamberCount) {
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        List<RouteConnection> connections = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        for (int i = 0; i < chamberCount; i++) {
            RouteVariant piece = fixture.variant("part", 0, i < repaired, false, false);
            String suffix = ":" + i;
            double y = i * 1000;
            for (RouteNode node : piece.getNodes()) nodes.add(new RouteNode(node.getId() + suffix, node.getNodeType(),
                    shift(node.getCoordinate(), y), node.isChamber(), node.isRoot(), node.getBaseIncidentSections(),
                    node.getTargetId(), node.getExistingIncidentDiameter()));
            for (RouteEdge edge : piece.getEdges()) {
                List<RouteCoordinate> points = edge.getCoordinates().stream().map(p -> shift(p, y)).collect(Collectors.toList());
                edges.add(new RouteEdge(edge.getId() + suffix, edge.getUpstreamNodeId() + suffix, edge.getDownstreamNodeId() + suffix,
                        edge.getLengthM().doubleValue(), points,
                        List.of(new RouteSection("base", null, null, points, edge.getLengthM().doubleValue(), null)),
                        edge.getFlowTph(), edge.getDiameter(), edge.getDepthProfile()));
            }
            for (RouteConnection c : piece.getConnections()) connections.add(new RouteConnection(c.getDemandId() + suffix,
                    c.getConnectionPointId() + suffix, c.getFlowTph(), c.getStatus(), c.getReason()));
            cost = cost.add(piece.getEconomics().getCalculatedCost());
        }
        RouteVariant reference = fixture.variant("compound-" + repaired, 0, false, false, false);
        return priced(copy(reference, nodes, edges, connections), cost.toPlainString());
    }

    private RouteCoordinate shift(RouteCoordinate point, double y) {
        return new RouteCoordinate(point.getXM().doubleValue(), point.getYM().doubleValue() + y);
    }

    private RouteVariant copy(RouteVariant source, List<RouteNode> nodes, List<RouteEdge> edges, List<RouteConnection> connections) {
        return new RouteVariant(source.getId(), source.getStrategy(), nodes, edges, connections,
                edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add), List.of(), List.of(), List.of(),
                ExistingNetworkReconstructionResult.empty(), source.getEconomics(), null);
    }

    /** Проверка объявленного бюджета отдельно от инварианта полилиний в pipeline/export. */
    private RouteVariant withLength(RouteVariant source, String length) {
        return new RouteVariant(source.getId(), source.getStrategy(), source.getNodes(), source.getEdges(), source.getConnections(),
                new BigDecimal(length), source.getValidationIssues(), source.getEngineeringIssues(), source.getSizingIssues(),
                source.getReconstruction(), source.getEconomics(), null);
    }

    /** Заданные суммы проверяют только границу бюджета; это не тест тарификации или export. */
    private RouteVariant priced(RouteVariant source, String value) {
        BigDecimal cost = new BigDecimal(value), length = source.getTotalLengthM();
        VariantEconomics economics = new VariantEconomics(true, cost, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cost, length, BigDecimal.ZERO, length,
                new OfficialEconomics().score(cost, length), List.of());
        return new RouteVariant(source.getId(), source.getStrategy(), source.getNodes(), source.getEdges(), source.getConnections(),
                length, source.getValidationIssues(), source.getEngineeringIssues(), source.getSizingIssues(),
                source.getReconstruction(), economics, null);
    }
}
