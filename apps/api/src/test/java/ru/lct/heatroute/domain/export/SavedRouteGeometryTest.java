package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.routing.OfficialRouteDeflectionRules;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteNode;

/** Проверяет фактически экспортируемый путь, включая стыки секций и совместимость с ребром. */
class SavedRouteGeometryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sectionOnlyUTurnCannotHideBehindStraightEdgeCoordinates() {
        ObjectNode edge = edge(points(0, 0, 20, 0), points(0, 0, 20, 0, 10, 0, 20, 0));
        rejected(edge, coordinate(0, 0), coordinate(20, 0));
    }

    @Test
    void twoIndividuallyStraightSectionsCannotHideAJoinUTurn() {
        ObjectNode edge = edge(points(0, 0, 10, 0), points(0, 0, 20, 0), points(20, 0, 10, 0));
        rejected(edge, coordinate(0, 0), coordinate(10, 0));
    }

    @Test
    void duplicateJoinCoordinatesDoNotHideATurn() {
        ObjectNode edge = edge(points(0, 0, 10, 0),
                points(0, 0, 20, 0, 20, 0), points(20, 0, 20, 0, 10, 0));
        rejected(edge, coordinate(0, 0), coordinate(10, 0));
    }

    @Test
    void evenAMillimetreGapBetweenSectionsIsRejected() {
        for (double gap : new double[]{0.001, 2.0}) {
            ObjectNode edge = edge(points(0, 0, 20, 0),
                    points(0, 0, 10, 0), points(10 + gap, 0, 20, 0));
            rejected(edge, coordinate(0, 0), coordinate(20, 0));
        }
    }

    @Test
    void everyPresentSectionMustHaveTwoDistinctPoints() {
        for (ArrayNode invalid : List.of(points(), points(5, 0), points(5, 0, 5, 0, 5, 0))) {
            ObjectNode edge = edge(points(0, 0, 10, 0),
                    points(0, 0, 5, 0), invalid, points(5, 0, 10, 0));
            rejected(edge, coordinate(0, 0), coordinate(10, 0));
        }
    }

    @Test
    void diagonalSectionsMayAddRoundedInterpolatedPoints() {
        ObjectNode edge = edge(points(0, 0, 10, 3),
                points(0, 0, 3.333, 1), points(3.333, 1, 6.667, 2, 10, 3));
        accepted(edge, coordinate(0, 0), coordinate(10, 3));
    }

    @Test
    void roundedDiagonalEquivalenceIsTranslationIndependentAtUtmCoordinates() {
        ObjectNode edge = edge(points(413100.127, 6170123.333, 413110.127, 6170126.333),
                points(413100.127, 6170123.333, 413103.460, 6170124.333),
                points(413103.460, 6170124.333, 413106.794, 6170125.333, 413110.127, 6170126.333));
        accepted(edge, coordinate(413100.127, 6170123.333), coordinate(413110.127, 6170126.333));
    }

    @Test
    void roundedDiagonalProjectionWorksAcrossOctantsAndInBothDirections() {
        for (double[] end : new double[][]{
                {10, 3}, {3, 10}, {-3, 10}, {-10, 3}, {-10, -3}, {-3, -10}, {3, -10}, {10, -3}}) {
            double x = end[0], y = end[1];
            double ax = rounded(x / 3), ay = rounded(y / 3);
            double bx = rounded(2 * x / 3), by = rounded(2 * y / 3);
            accepted(edge(points(0, 0, x, y), points(0, 0, ax, ay), points(ax, ay, bx, by, x, y)),
                    coordinate(0, 0), coordinate(x, y));
            accepted(edge(points(x, y, 0, 0), points(x, y, bx, by, ax, ay), points(ax, ay, 0, 0)),
                    coordinate(x, y), coordinate(0, 0));
        }
    }

    @Test
    void equivalentPathsCanPartitionDifferentCollinearVertices() {
        ObjectNode edge = edge(points(0, 0, 2, 0, 8, 0, 10, 0, 10, 10),
                points(0, 0, 5, 0), points(5, 0, 10, 0, 10, 3, 10, 10));
        accepted(edge, coordinate(0, 0), coordinate(10, 10));
    }

    @Test
    void aDifferentLegalPathWithTheSameEndpointsAndLengthIsRejected() {
        ObjectNode edge = edge(points(0, 0, 10, 0, 10, 10), points(0, 0, 0, 10, 10, 10));
        rejected(edge, coordinate(0, 0), coordinate(10, 10));
    }

    @Test
    void sectionsCannotShortcutAnEdgeCorner() {
        ObjectNode edge = edge(points(0, 0, 10, 0, 10, 10), points(0, 0, 10, 10));
        rejected(edge, coordinate(0, 0), coordinate(10, 10));
    }

    @Test
    void equivalenceToleranceAllowsTwoMillimetresButNotThree() {
        ObjectNode within = edge(points(0, 0, 10, 0), points(0, 0.002, 10, 0.002));
        accepted(within, coordinate(0, 0), coordinate(10, 0));
        ObjectNode outside = edge(points(0, 0, 10, 0), points(0, 0.003, 10, 0.003));
        rejected(outside, coordinate(0, 0), coordinate(10, 0));
    }

    @Test
    void equivalenceToleranceNeverPermitsMillimetreBacktracking() {
        ObjectNode edge = edge(points(0, 0, 10, 0), points(0, 0, 5, 0, 4.999, 0, 10, 0));
        rejected(edge, coordinate(0, 0), coordinate(10, 0));
    }

    @Test
    void endpointToleranceMustNotSwallowAShortLoopAfterOnePathHasEnded() {
        // Каждый поворот равен 90°, а петля целиком лежит в радиусе 2 мм от конца.
        // Проверка углов её не отсекает; монотонное сопоставление не должно поглощать обратный ход.
        ArrayNode loop = points(0, 0, 10, 0, 10, 0.001, 9.999, 0.001, 9.999, 0, 10, 0);
        rejected(edge(points(0, 0, 10, 0), loop), coordinate(0, 0), coordinate(10, 0));
        rejected(edge(loop, points(0, 0, 10, 0)), coordinate(0, 0), coordinate(10, 0));
    }

    @Test
    void missingSectionsUseTheEdgePathWithoutChangingInput() {
        ObjectNode edge = edge(points(0, 0, 10, 0, 10, 10));
        edge.remove("sections");
        JsonNode before = edge.deepCopy();
        accepted(edge, coordinate(0, 0), coordinate(10, 10));
        assertThat(edge).isEqualTo(before);
    }

    @Test
    void emptySectionsUseTheEdgePath() {
        accepted(edge(points(0, 0, 10, 0, 10, 10)), coordinate(0, 0), coordinate(10, 10));
    }

    @Test
    void bothFallbacksStillRejectExcessiveTurns() {
        ObjectNode edge = edge(points(0, 0, 10, 0, 5, 5));
        rejected(edge, coordinate(0, 0), coordinate(5, 5));
        edge.remove("sections");
        rejected(edge, coordinate(0, 0), coordinate(5, 5));
    }

    @Test
    void degenerateFallbackDoesNotPassAsAValidPolyline() {
        for (ArrayNode invalid : List.of(points(), points(0, 0), points(0, 0, 0, 0))) {
            rejected(edge(invalid), coordinate(0, 0), coordinate(0, 0));
        }
    }

    @Test
    void declaredEndpointsAllowOneCentimetreButRejectElevenMillimetres() {
        ObjectNode edge = edge(points(0, 0, 10, 0), points(0, 0, 10, 0));
        accepted(edge, coordinate(-0.010, 0), coordinate(10.010, 0));
        rejected(edge, coordinate(-0.011, 0), coordinate(10, 0));
        rejected(edge, coordinate(0, 0), coordinate(10.011, 0));
    }

    @Test
    void reversingCoordinatesWithoutSwappingDeclaredEndpointsIsRejected() {
        ObjectNode edge = edge(points(10, 0, 0, 0), points(10, 0, 5, 0, 0, 0));
        rejected(edge, coordinate(0, 0), coordinate(10, 0));
        edge.remove("sections");
        rejected(edge, coordinate(0, 0), coordinate(10, 0));
    }

    @Test
    void reversingBothPathsAndDeclaredEndpointsRemainsValid() {
        ObjectNode edge = edge(points(10, 3, 0, 0),
                points(10, 3, 6.667, 2, 3.333, 1), points(3.333, 1, 0, 0));
        accepted(edge, coordinate(10, 3), coordinate(0, 0));
    }

    @Test
    void reversingOnlyTheSectionsIsRejected() {
        ObjectNode edge = edge(points(0, 0, 10, 0), points(10, 0, 0, 0));
        rejected(edge, coordinate(0, 0), coordinate(10, 0));
    }

    @Test
    void submillimetreCoordinatesAreRejectedInBothRepresentations() {
        for (boolean changeSection : new boolean[]{false, true}) {
            for (String axis : List.of("xm", "ym")) {
                ObjectNode edge = edge(points(0, 0, 5, 0, 10, 0), points(0, 0, 5, 0, 10, 0));
                JsonNode coordinates = changeSection
                        ? edge.path("sections").path(0).path("coordinates") : edge.path("coordinates");
                ((ObjectNode) coordinates.path(1)).put(axis,
                        new BigDecimal("xm".equals(axis) ? "5.0001" : "0.0001"));
                rejected(edge, coordinate(0, 0), coordinate(10, 0));
            }
        }
    }

    @Test
    void coincidentVerticesAndDuplicatedSectionBoundariesRemainValid() {
        ObjectNode edge = edge(points(0, 0, 10, 0, 10, 10),
                points(0, 0, 0, 0, 10, 0, 10, 0), points(10, 0, 10, 0, 10, 10, 10, 10));
        accepted(edge, coordinate(0, 0), coordinate(10, 10));
    }

    @Test
    void returnedEndpointDirectionsDescribeTheEmittedPath() {
        // Допустимое расхождение 1 мм меняет последний луч, но не эквивалентность пути.
        ObjectNode edge = edge(points(0, 0, 10, 0), points(0, 0, 10, -0.001, 10, 0));
        OfficialRouteDeflectionRules.PolylineCheck before = accepted(edge, coordinate(0, 0), coordinate(10, 0));
        OfficialRouteDeflectionRules.PolylineCheck after = OfficialRouteDeflectionRules.validatePolyline(
                "after", List.of(coordinate(10, 0), coordinate(10, -10)));
        RouteNode junction = new RouteNode("joint", "technical_node", coordinate(10, 0), false, false, 0, null);

        assertThat(OfficialRouteDeflectionRules.validateDegreeTwoNodes(List.of(junction), List.of(
                before.endpoints("root", "joint"), after.endpoints("joint", "demand"))))
                .anySatisfy(issue -> {
                    assertThat(issue.getCode()).isEqualTo("ROUTE_DEFLECTION_EXCEEDED");
                    assertThat(issue.getSubjectId()).isEqualTo("joint");
                });
    }

    private OfficialRouteDeflectionRules.PolylineCheck accepted(
            ObjectNode edge, RouteCoordinate upstream, RouteCoordinate downstream) {
        OfficialRouteDeflectionRules.PolylineCheck result = SavedRouteGeometry.verify(edge, upstream, downstream);
        assertThat(result).isNotNull();
        assertThat(result.getIssues()).isEmpty();
        return result;
    }

    private void rejected(ObjectNode edge, RouteCoordinate upstream, RouteCoordinate downstream) {
        assertThatThrownBy(() -> SavedRouteGeometry.verify(edge, upstream, downstream))
                .as("edge %s; upstream=(%s,%s), downstream=(%s,%s)", edge,
                        upstream.getXM(), upstream.getYM(), downstream.getXM(), downstream.getYM())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode edge(ArrayNode coordinates, ArrayNode... sectionCoordinates) {
        ObjectNode result = mapper.createObjectNode();
        result.put("id", "route-under-test");
        result.set("coordinates", coordinates);
        ArrayNode sections = result.putArray("sections");
        for (ArrayNode section : sectionCoordinates) sections.addObject().set("coordinates", section);
        return result;
    }

    private ArrayNode points(double... ordinates) {
        if (ordinates.length % 2 != 0) throw new IllegalArgumentException("Coordinate pairs are required");
        ArrayNode result = mapper.createArrayNode();
        for (int index = 0; index < ordinates.length; index += 2) {
            result.addObject().put("xm", BigDecimal.valueOf(ordinates[index]))
                    .put("ym", BigDecimal.valueOf(ordinates[index + 1]));
        }
        return result;
    }

    private RouteCoordinate coordinate(double x, double y) {
        return new RouteCoordinate(x, y);
    }

    private double rounded(double value) {
        return BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
