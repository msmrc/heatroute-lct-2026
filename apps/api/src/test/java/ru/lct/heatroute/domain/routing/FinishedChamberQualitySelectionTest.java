package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;

/** Предпочтение геометрии balanced, не новая обязательная норма углов камеры. */
class FinishedChamberQualitySelectionTest {
    private final EngineeringRouteEvaluator engineering = new EngineeringRouteEvaluator();
    private final FinishedRouteVariantSelector selector = new FinishedRouteVariantSelector();

    @ParameterizedTest
    @ValueSource(doubles = {0, 31, 90})
    void cheaperShorterNetworkMayAddLegalBendsToSeparateCameraRays(double rotation) {
        RouteVariant before = variant("balanced", rotation, false, false, false);
        RouteVariant after = variant("camera-quality", rotation, true, false, false);
        assertWholeGeometry(before);
        assertWholeGeometry(after);
        assertThat(after.getTotalLengthM()).isLessThan(before.getTotalLengthM());
        assertThat(after.getEconomics().getCalculatedCost()).isLessThan(before.getEconomics().getCalculatedCost());
        assertThat(after.getEconomics().getScore()).isLessThan(before.getEconomics().getScore());
        var oldGeometry = engineering.evaluate(before.getEdges());
        var newGeometry = engineering.evaluate(after.getEdges());
        assertThat(newGeometry.isCompliant()).isTrue();
        assertThat(newGeometry.bendCount()).isGreaterThan(oldGeometry.bendCount());
        assertThat(newGeometry.irregularJunctionAngleCount()).isLessThan(oldGeometry.irregularJunctionAngleCount());
        assertThat(newGeometry.preservesJunctionQualityOf(oldGeometry)).isTrue();

        List<RouteVariant> candidates = new ArrayList<>(List.of(before, after));
        for (int repeat = 0; repeat < 2; repeat++) {
            assertThat(balanced(selector.select(candidates, true)).getEdges()).containsExactlyElementsOf(after.getEdges());
            Collections.reverse(candidates);
        }
    }

    @Test
    void extraBendsWithoutBetterCameraRaysStillCannotReplaceBalanced() {
        RouteVariant before = variant("balanced", 0, false, false, false);
        RouteVariant after = variant("camera-quality", 0, true, true, false);
        assertWholeGeometry(after);
        assertThat(after.getEconomics().getScore()).isLessThan(before.getEconomics().getScore());
        assertThat(engineering.evaluate(after.getEdges()).bendCount())
                .isGreaterThan(engineering.evaluate(before.getEdges()).bendCount());
        assertThat(engineering.evaluate(after.getEdges()).irregularJunctionAngleCount())
                .isEqualTo(engineering.evaluate(before.getEdges()).irregularJunctionAngleCount());
        assertThat(balanced(selector.select(List.of(before, after), true)).getEdges())
                .containsExactlyElementsOf(before.getEdges());
    }

    @Test
    void betterCameraCannotExcuseInsufficientSpacingBetweenNewBends() {
        RouteVariant before = variant("balanced", 0, false, false, false);
        RouteVariant after = variant("camera-quality", 0, true, false, true);
        assertWholeGeometry(after);
        assertThat(engineering.evaluate(after.getEdges()).irregularJunctionAngleCount()).isZero();
        assertThat(engineering.evaluate(after.getEdges()).insufficientSpacingCount()).isPositive();
        assertThat(balanced(selector.select(List.of(before, after), true)).getEdges())
                .containsExactlyElementsOf(before.getEdges());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1040", "1050", "1050.01"})
    void balancedMayPayAtMostFivePercentForStrictlyBetterRaysButCheapestKeepsItsPrice(String cost) {
        RouteVariant before = priced(variant("balanced", 0, false, false, false), "1000");
        RouteVariant after = priced(variant("camera-quality", 0, true, false, false), cost);
        List<RouteVariant> selected = selector.selectChamberQuality(List.of(before, after), true, before);
        assertThat(balanced(selected).getEdges()).containsExactlyElementsOf(
                new BigDecimal(cost).compareTo(new BigDecimal("1050")) <= 0 ? after.getEdges() : before.getEdges());
        assertThat(selected.stream().filter(v -> "cheapest".equals(v.getId())).findFirst().orElseThrow()
                .getEconomics().getCalculatedCost()).isEqualByComparingTo("1000");
    }

    @Test
    void payingForAnUnchangedCameraOrIllegalNewBendsIsNeverAQualityTradeoff() {
        RouteVariant before = priced(variant("balanced", 0, false, false, false), "1000");
        for (RouteVariant candidate : List.of(variant("same-rays", 0, true, true, false),
                variant("illegal-bends", 0, true, false, true))) {
            assertThat(balanced(selector.selectChamberQuality(List.of(before, priced(candidate, "1001")), true, before)).getEdges())
                    .containsExactlyElementsOf(before.getEdges());
        }
    }

    /** Синтетические суммы изолируют границу политики; реальные тарифы проверяет первый тест. */
    private RouteVariant priced(RouteVariant source, String value) {
        BigDecimal cost = new BigDecimal(value), length = source.getTotalLengthM();
        VariantEconomics economics = new VariantEconomics(true, cost, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cost, length, BigDecimal.ZERO, length,
                new OfficialEconomics().score(cost, length), List.of());
        return new RouteVariant(source.getId(), source.getStrategy(), source.getNodes(), source.getEdges(), source.getConnections(),
                length, source.getValidationIssues(), source.getEngineeringIssues(), source.getSizingIssues(),
                source.getReconstruction(), economics, null);
    }

    private void assertWholeGeometry(RouteVariant variant) {
        var validator = new OfficialRouteValidator(new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));
        assertThat(validator.validate(variant.getNodes(), variant.getEdges(), List.of())).isEmpty();
        assertThat(new ExpertChamberRouteValidator().validate(variant.getNodes(), variant.getEdges())).isEmpty();
        assertThat(variant.getConnectedDemandCount()).isEqualTo(2);
        assertThat(variant.getEconomics().isComplete()).isTrue();
    }

    private RouteVariant balanced(List<RouteVariant> selected) {
        return selected.stream().filter(v -> "balanced".equals(v.getId())).findFirst().orElseThrow();
    }

    /** Общая синтетическая сеть для тестов отбора и ограниченного refinement, без запуска планировщика. */
    RouteVariant variant(String id, double rotation, boolean improved, boolean unchangedRays, boolean closeBends) {
        List<RouteCoordinate> root = improved ? path(rotation, -100, 0, 0, 0)
                : path(rotation, -100, 0, -100, 30, 0, 30, 0, 0);
        List<RouteCoordinate> first;
        if (!improved) first = path(rotation, 0, 0, 20, 0);
        else if (unchangedRays) first = path(rotation, 0, 0, 5, 0, 5, 5, 15, 5, 15, 0, 20, 0);
        else if (closeBends) first = path(rotation, 0, 0, 0, 1, 1, 1, 1, 0, 20, 0);
        else first = path(rotation, 0, 0, 0, 10, 10, 10, 10, 0, 20, 0);
        List<RouteCoordinate> second = path(rotation, 0, 0, 20, -0.1);
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", root.get(0), true, true, 1, null, 100),
                new RouteNode("camera", "new_branch_chamber", first.get(0), true, false, 0, null),
                new RouteNode("demand:a", "demand_connection", first.get(first.size() - 1), false, false, 0, "a"),
                new RouteNode("demand:b", "demand_connection", second.get(1), false, false, 0, "b"));
        List<RouteEdge> edges = List.of(edge("root-edge", "root", "camera", root, 100, 2),
                edge("input-a", "camera", "demand:a", first, 50, 1),
                edge("input-b", "camera", "demand:b", second, 50, 1));
        List<RouteConnection> connections = List.of(new RouteConnection("a", "a", BigDecimal.ONE, "connected", null),
                new RouteConnection("b", "b", BigDecimal.ONE, "connected", null));
        var reconstruction = ExistingNetworkReconstructionResult.empty();
        var costs = new OfficialVariantEconomicsCalculator(new OfficialPipeCatalog(), new OfficialEconomics())
                .calculate(nodes, edges, connections, reconstruction, false);
        return new RouteVariant(id, "engineering", nodes, edges, connections,
                edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add),
                List.of(), List.of(), List.of(), reconstruction, costs, null);
    }

    private RouteEdge edge(String id, String from, String to, List<RouteCoordinate> points, int diameter, int flow) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) length += points.get(i - 1).toCoordinate().distance(points.get(i).toCoordinate());
        BigDecimal measured = BigDecimal.valueOf(length);
        var depth = new DepthProfileResult(true, List.of(new DepthProfilePoint(BigDecimal.ZERO, BigDecimal.ONE),
                new DepthProfilePoint(measured, BigDecimal.ONE)), List.of(), List.of(), measured, measured);
        return new RouteEdge(id, from, to, length, points,
                List.of(new RouteSection("base", null, null, points, length, null)), BigDecimal.valueOf(flow), diameter, depth);
    }

    private List<RouteCoordinate> path(double rotation, double... xy) {
        double angle = Math.toRadians(rotation), cos = Math.cos(angle), sin = Math.sin(angle);
        List<RouteCoordinate> result = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) result.add(new RouteCoordinate(
                414000 + xy[i] * cos - xy[i + 1] * sin, 6173000 + xy[i] * sin + xy[i + 1] * cos));
        return result;
    }
}
