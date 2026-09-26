package ru.lct.heatroute.domain.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;

/** Проверяет ремонт расстояния между поворотами без исходного нарушения углов. */
class EngineeringSpacingRepairTest {
    @Test
    void acceptsSpacingOnlyRepairAfterRotationAndTranslation() throws Exception {
        for (double rotation : new double[] {0.0, 0.37, 1.1}) {
            for (double offset : new double[] {0.0, 410000.0}) {
                var points = List.of(point(0, 0, rotation, offset), point(10, 0, rotation, offset),
                        point(10, 1, rotation, offset), point(20, 1, rotation, offset));
                var nodes = List.of(
                        new RouteNode("root", "chamber", points.get(0), true, true, 0, null),
                        new RouteNode("demand:consumer", "demand", points.get(3), false, false, 0, "consumer"));
                double length = 0.0;
                for (int i = 1; i < points.size(); i++) {
                    length += points.get(i - 1).toCoordinate().distance(points.get(i).toCoordinate());
                }
                var edge = new RouteEdge("branch", "root", "demand:consumer", length,
                        points, List.of(), BigDecimal.ONE, 50);
                var connections = List.of(new RouteConnection("consumer", "consumer", BigDecimal.ONE,
                        "connected", null));
                var draft = new OfficialRoutePlanner.VariantDraft(nodes, List.of(edge), connections);
                var evaluator = new EngineeringRouteEvaluator();
                assertEquals(0, evaluator.evaluate(List.of(edge)).invalidAngleCount());
                assertEquals(1, evaluator.evaluate(List.of(edge)).insufficientSpacingCount());
                var rules = new OfficialRouteGeometryRules(new OfficialConstraintCatalog(),
                        new OfficialCrossingGeometry());
                var router = new OfficialObstacleRouter(rules);
                var environment = router.prepare(List.of());
                var planner = new OfficialDatasetRoutingTest().planner();
                var local = (OfficialRoutePlanner.VariantDraft) invoke(planner, "regularizeEngineeringEdgeLocally",
                        new Class<?>[] {OfficialRoutePlanner.VariantDraft.class, RouteEdge.class,
                                OfficialRoutingEnvironment.class}, draft, edge, environment);
                assertNotNull(local, "A legal local repair is available");
                assertTrue(evaluator.evaluate(edges(local)).isCompliant());
                var repaired = (OfficialRoutePlanner.VariantDraft) invoke(planner, "regularizeEngineeringDraft",
                        new Class<?>[] {OfficialRoutePlanner.VariantDraft.class, List.class,
                                OfficialRoutingEnvironment.class, boolean.class},
                        draft, List.of(), environment, false);
                assertTrue(evaluator.evaluate(edges(repaired)).isCompliant(),
                        "A spacing-only improvement must not require reducing an already zero angle count");
                assertTrue(new OfficialRouteValidator(rules).validate(nodes, edges(repaired), List.of()).isEmpty());
                assertTrue(ExpertRouteBendRules.validate(nodes, edges(repaired)).isEmpty());
                assertEquals(connections, field(repaired, "connections"));
            }
        }
    }

    @Test
    void rejectsUnchangedHardIssuesAndNewAngleViolations() throws Exception {
        var planner = new OfficialDatasetRoutingTest().planner();
        var spacing = evaluation(List.of(new RouteCoordinate(0, 0), new RouteCoordinate(10, 0),
                new RouteCoordinate(10, 1), new RouteCoordinate(20, 1)));
        var straight = evaluation(List.of(new RouteCoordinate(0, 0), new RouteCoordinate(20, 1)));
        var sharp = evaluation(List.of(new RouteCoordinate(0, 0), new RouteCoordinate(10, 0),
                new RouteCoordinate(9, 1), new RouteCoordinate(20, 1)));
        assertEquals(0, spacing.invalidAngleCount());
        assertEquals(1, spacing.insufficientSpacingCount());
        assertTrue(sharp.invalidAngleCount() > 0);
        assertTrue(progress(planner, straight, spacing));
        assertFalse(progress(planner, spacing, straight));
        assertFalse(progress(planner, spacing, spacing));
        assertFalse(progress(planner, sharp, spacing));
    }

    private static EngineeringRouteEvaluator.Evaluation evaluation(List<RouteCoordinate> points) {
        var edge = new RouteEdge("candidate", "a", "b", 30, points, List.of(), BigDecimal.ONE, 50);
        return new EngineeringRouteEvaluator().evaluate(List.of(edge));
    }

    private static boolean progress(OfficialRoutePlanner planner,
            EngineeringRouteEvaluator.Evaluation candidate,
            EngineeringRouteEvaluator.Evaluation control) throws Exception {
        return (boolean) invoke(planner, "reducesHardEngineeringViolations",
                new Class<?>[] {EngineeringRouteEvaluator.Evaluation.class,
                        EngineeringRouteEvaluator.Evaluation.class}, candidate, control);
    }

    private static Object invoke(OfficialRoutePlanner planner, String name, Class<?>[] types,
            Object... values) throws Exception {
        Method method = OfficialRoutePlanner.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(planner, values);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @SuppressWarnings("unchecked")
    private static List<RouteEdge> edges(OfficialRoutePlanner.VariantDraft draft) throws Exception {
        return (List<RouteEdge>) field(draft, "edges");
    }

    private static RouteCoordinate point(double x, double y, double rotation, double offset) {
        return new RouteCoordinate(offset + x * Math.cos(rotation) - y * Math.sin(rotation),
                offset + x * Math.sin(rotation) + y * Math.cos(rotation));
    }
}
