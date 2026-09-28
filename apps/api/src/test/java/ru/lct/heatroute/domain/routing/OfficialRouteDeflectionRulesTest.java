package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Потоковая публичная проверка для независимого preflight сохранённого GeoJSON. */
class OfficialRouteDeflectionRulesTest {
    private static final String CODE = "ROUTE_DEFLECTION_EXCEEDED";

    @Test
    void primitiveSearchPredicateMatchesIndependentAngleFormulaAtAllLengthScales() {
        for (double length : new double[] {0.01, 0.1, 1, 10, 1000}) {
            for (double angle : new double[] {0, 0.49, 0.5, 0.51, 30, 45, 89.9, 90, 90.01, 90.1, 90.11, 91, 135, 180}) {
                for (double rotation : new double[] {0, 0.4, 1.7, 2.8}) {
                    List<RouteCoordinate> p = transform(turn(angle, length), rotation);
                    double ax = p.get(1).getXM().subtract(p.get(0).getXM()).doubleValue();
                    double ay = p.get(1).getYM().subtract(p.get(0).getYM()).doubleValue();
                    double bx = p.get(2).getXM().subtract(p.get(1).getXM()).doubleValue();
                    double by = p.get(2).getYM().subtract(p.get(1).getYM()).doubleValue();
                    double change = Math.abs(Math.IEEEremainder(Math.atan2(by, bx) - Math.atan2(ay, ax), 2 * Math.PI));
                    double rounding = Math.min(Math.toRadians(0.1),
                            Math.asin(Math.min(1, Math.sqrt(2) * 0.001 / Math.hypot(ax, ay)))
                                    + Math.asin(Math.min(1, Math.sqrt(2) * 0.001 / Math.hypot(bx, by))));
                    boolean expected = change <= Math.toRadians(0.5) + 1e-12
                            || change + rounding + 1e-12 >= Math.PI / 3
                                    && change <= Math.PI / 2 + rounding + 1e-12;
                    assertThat(OfficialRouteDeflectionRules.allowsTurn(ax, ay, bx, by))
                            .as("length=%s angle=%s rotation=%s", length, angle, rotation).isEqualTo(expected);
                }
            }
        }
        assertThat(OfficialRouteDeflectionRules.allowsTurn(0, 0, 1, 0)).isFalse();
        assertThat(OfficialRouteDeflectionRules.allowsTurn(1, 0, 0, 0)).isFalse();
        assertThat(OfficialRouteDeflectionRules.allowsTurn(Double.NaN, 0, 1, 0)).isFalse();
        assertThat(OfficialRouteDeflectionRules.allowsTurn(1, 0, Double.POSITIVE_INFINITY, 0)).isFalse();
        assertThat(OfficialRouteDeflectionRules.allowsTurn(1e308, 1e308, -1e308, -1e308)).isFalse();
    }

    @Test
    void consumesALargeLazyPolylineOnlyOnceAndFindsItsLastTurn() {
        AtomicInteger traversals = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        Iterable<RouteCoordinate> coordinates = () -> {
            assertThat(traversals.incrementAndGet()).isEqualTo(1);
            return new Iterator<RouteCoordinate>() {
                @Override public boolean hasNext() { return delivered.get() < 100_000; }
                @Override public RouteCoordinate next() {
                    int index = delivered.getAndIncrement();
                    return point(index == 99_999 ? 99_997 : index, 0);
                }
            };
        };
        var check = OfficialRouteDeflectionRules.validatePolyline("long", coordinates);
        assertThat(check.getIssues()).extracting(RouteValidationIssue::getCode).containsExactly(CODE);
        assertThat(delivered).hasValue(100_000);
        assertThat(traversals).hasValue(1);
        // Создание/использование сводки концов не читает iterable снова.
        OfficialRouteDeflectionRules.validateDegreeTwoNodes(List.of(node("a", point(0, 0)), node("b", point(99_997, 0))),
                List.of(check.endpoints("a", "b")));
        assertThat(traversals).hasValue(1);
    }

    @Test
    void boundsPolylineDiagnosticsEvenForManyBadTurns() {
        List<RouteCoordinate> coordinates = new ArrayList<>();
        for (int i = 0; i < 100; i++) coordinates.add(point(i % 2, 0));
        assertThat(OfficialRouteDeflectionRules.validatePolyline("many", coordinates).getIssues())
                .extracting(RouteValidationIssue::getCode).containsExactly(CODE);
    }

    @Test
    void isInvariantUnderReversalRotationAndMetricTranslationAtBothUpdatedBoundaries() {
        for (double deflection : new double[] {0, 0.49, 0.51, 30, 60, 90, 91, 135, 180}) {
            for (double rotation : new double[] {0, 0.4, 1.7, 2.8}) {
                List<RouteCoordinate> transformed = transform(turn(deflection, 10), rotation);
                List<RouteCoordinate> reversed = new ArrayList<>(transformed); Collections.reverse(reversed);
                for (List<RouteCoordinate> coordinates : List.of(transformed, reversed)) {
                    assertThat(OfficialRouteDeflectionRules.validatePolyline("edge", coordinates).getIssues().isEmpty())
                            .as("deflection=%s rotation=%s", deflection, rotation)
                            .isEqualTo(deflection <= 0.5 || deflection >= 60 && deflection <= 90);
                }
            }
        }
    }

    @Test
    void junctionRaysAreIndependentOfCoordinateAndDeclaredEdgeDirections() {
        for (double deflection : new double[] {0, 0.49, 0.51, 30, 60, 90, 91, 135}) {
            for (double rotation : new double[] {0, 0.4, 1.7}) {
                List<RouteCoordinate> p = transform(turn(deflection, 10), rotation);
                List<RouteNode> nodes = List.of(node("a", p.get(0)), node("joint", p.get(1)), node("b", p.get(2)));
                for (boolean reverseFirst : List.of(false, true)) {
                    for (boolean reverseSecond : List.of(false, true)) {
                        var first = OfficialRouteDeflectionRules.validatePolyline("first",
                                reverseFirst ? List.of(p.get(1), p.get(0)) : List.of(p.get(0), p.get(1)));
                        var second = OfficialRouteDeflectionRules.validatePolyline("second",
                                reverseSecond ? List.of(p.get(2), p.get(1)) : List.of(p.get(1), p.get(2)));
                        var ends = List.of(first.endpoints("joint", "a"), second.endpoints("b", "joint"));
                        List<RouteValidationIssue> issues = OfficialRouteDeflectionRules.validateDegreeTwoNodes(nodes, ends);
                        assertThat(issues.isEmpty()).as("deflection=%s rotation=%s", deflection, rotation)
                                .isEqualTo(deflection <= 0.5 || deflection >= 60 && deflection <= 90);
                        if (deflection > 0.5 && (deflection < 60 || deflection > 90)) {
                            assertThat(issues).extracting(RouteValidationIssue::getSubjectId).containsExactly("joint");
                        }
                    }
                }
            }
        }
    }

    @Test
    void junctionOnlyPassDoesNotRepeatInternalPolylineErrors() {
        var first = OfficialRouteDeflectionRules.validatePolyline("first", List.of(point(0, 0), point(10, 0), point(9, 10)));
        var second = OfficialRouteDeflectionRules.validatePolyline("second", List.of(point(9, 10), point(8, 20)));
        assertThat(first.getIssues()).extracting(RouteValidationIssue::getCode).containsExactly(CODE);
        assertThat(OfficialRouteDeflectionRules.validateDegreeTwoNodes(
                List.of(node("a", point(0, 0)), node("joint", point(9, 10)), node("b", point(8, 20))),
                List.of(first.endpoints("a", "joint"), second.endpoints("joint", "b")))).isEmpty();
    }

    @Test
    void nonRootIncidentMetadataCannotHideATechnicalOrChamberDeflection() {
        List<RouteCoordinate> p = turn(135, 10);
        var ends = List.of(OfficialRouteDeflectionRules.validatePolyline("first", p.subList(0, 2)).endpoints("a", "joint"),
                OfficialRouteDeflectionRules.validatePolyline("second", p.subList(1, 3)).endpoints("joint", "b"));
        for (String type : List.of("technical_node", "new_branch_chamber")) {
            for (int claimedExisting : new int[] {1, 2, 4, -1}) {
                RouteNode joint = new RouteNode("joint", type, p.get(1), "new_branch_chamber".equals(type),
                        false, claimedExisting, null);
                assertThat(OfficialRouteDeflectionRules.validateDegreeTwoNodes(
                        List.of(node("a", p.get(0)), joint, node("b", p.get(2))), ends))
                        .as("type=%s claimedExisting=%s", type, claimedExisting)
                        .extracting(RouteValidationIssue::getCode).containsExactly(CODE);
            }
        }
    }

    @Test
    void rootExemptionRequiresPositiveExistingIncidence() {
        List<RouteCoordinate> p = turn(135, 10);
        var ends = List.of(OfficialRouteDeflectionRules.validatePolyline("first", p.subList(0, 2)).endpoints("a", "joint"),
                OfficialRouteDeflectionRules.validatePolyline("second", p.subList(1, 3)).endpoints("joint", "b"));
        for (int existing : new int[] {-1, 0, 1, 2}) {
            RouteNode root = new RouteNode("joint", "existing_chamber_tie_in", p.get(1), true, true, existing, "input");
            assertThat(OfficialRouteDeflectionRules.validateDegreeTwoNodes(
                    List.of(node("a", p.get(0)), root, node("b", p.get(2))), ends).isEmpty())
                    .as("existing=%s", existing).isEqualTo(existing > 0);
        }
        RouteNode technicalRoot = new RouteNode("joint", "technical_node", p.get(1), false, true, 2, "input");
        assertThat(OfficialRouteDeflectionRules.validateDegreeTwoNodes(
                List.of(node("a", p.get(0)), technicalRoot, node("b", p.get(2))), ends))
                .extracting(RouteValidationIssue::getCode).containsExactly(CODE);
    }

    @Test
    void roundingToleranceShrinksWithLegLengthAndDoesNotAllowShallowOrExcessTurns() {
        assertThat(OfficialRouteDeflectionRules.validatePolyline("exact", turn(90, 100)).getIssues()).isEmpty();
        assertThat(OfficialRouteDeflectionRules.validatePolyline("long-small-excess", turn(90.01, 100)).getIssues())
                .extracting(RouteValidationIssue::getCode).containsExactly(CODE);
        assertThat(OfficialRouteDeflectionRules.validatePolyline("long-shallow", turn(59.99, 100)).getIssues())
                .extracting(RouteValidationIssue::getCode).containsExactly(CODE);
        for (double length : new double[] {0.1, 1, 2, 100}) {
            for (double angle : new double[] {90.5, 91, 91.2055, 91.6649, 135, 180}) {
                assertThat(OfficialRouteDeflectionRules.validatePolyline("excess", turn(angle, length)).getIssues())
                        .as("length=%s angle=%s", length, angle)
                        .extracting(RouteValidationIssue::getCode).containsExactly(CODE);
            }
        }
        for (List<RouteCoordinate> tiny : List.of(
                List.of(point(0, 0), point(0.001, 0), point(0, 0.001)),
                List.of(point(0, 0), point(0.001, 0), point(0, 0)))) {
            assertThat(OfficialRouteDeflectionRules.validatePolyline("tiny", tiny).getIssues())
                    .extracting(RouteValidationIssue::getCode).containsExactly(CODE);
        }
    }

    @Test
    void repeatedPointsDoNotRemoveTheNearestPositiveLengthRay() {
        var left = OfficialRouteDeflectionRules.validatePolyline("left", List.of(point(-1, 0), point(0, 0), point(0, 0)));
        var right = OfficialRouteDeflectionRules.validatePolyline("right",
                List.of(point(0, 0), point(0, 0), point(-0.001, 0.001), point(-1, 1)));
        assertThat(right.getIssues()).isEmpty();
        assertThat(OfficialRouteDeflectionRules.validateDegreeTwoNodes(
                List.of(node("a", point(-1, 0)), node("joint", point(0, 0)), node("b", point(-1, 1))),
                List.of(left.endpoints("a", "joint"), right.endpoints("joint", "b"))))
                .extracting(RouteValidationIssue::getCode).containsExactly(CODE);
    }

    @Test
    void rejectsUndeterminedDegenerateGeometryRatherThanTreatingItAsAStraightRoute() {
        for (List<RouteCoordinate> points : List.of(List.<RouteCoordinate>of(), List.of(point(0, 0)),
                List.of(point(0, 0), point(0, 0), point(0, 0)))) {
            assertThat(OfficialRouteDeflectionRules.validatePolyline("degenerate", points).getIssues())
                    .extracting(RouteValidationIssue::getCode).containsExactly("ROUTE_DEFLECTION_UNDEFINED");
        }
    }

    @Test
    void wholeNetworkFacadeMatchesStreamingChecksIncludingImplicitStraightEdges() {
        List<RouteCoordinate> p = turn(135, 10);
        List<RouteNode> nodes = List.of(node("a", p.get(0)), node("joint", p.get(1)), node("b", p.get(2)));
        List<RouteEdge> edges = List.of(new RouteEdge("first", "a", "joint", 10), new RouteEdge("second", "joint", "b", 10));
        List<OfficialRouteDeflectionRules.EdgeEndpoints> ends = List.of(
                OfficialRouteDeflectionRules.validatePolyline("first", p.subList(0, 2)).endpoints("a", "joint"),
                OfficialRouteDeflectionRules.validatePolyline("second", p.subList(1, 3)).endpoints("joint", "b"));
        assertThat(OfficialRouteDeflectionRules.validate(nodes, edges)).extracting(RouteValidationIssue::getCode)
                .containsExactlyElementsOf(OfficialRouteDeflectionRules.validateDegreeTwoNodes(nodes, ends).stream()
                        .map(RouteValidationIssue::getCode).collect(Collectors.toList()));
    }

    @Test
    void cancellationIsObservedBeforeAndDuringLazyTraversalWithoutClearingInterrupt() {
        assertThatThrownBy(() -> {
            try {
                Thread.currentThread().interrupt();
                OfficialRouteDeflectionRules.validatePolyline("edge", List.of(point(0, 0), point(1, 0)));
            } finally { Thread.interrupted(); }
        }).isInstanceOf(CancellationException.class);
        AtomicInteger delivered = new AtomicInteger();
        Iterable<RouteCoordinate> coordinates = () -> new Iterator<RouteCoordinate>() {
            @Override public boolean hasNext() { return delivered.get() < 10; }
            @Override public RouteCoordinate next() {
                int i = delivered.getAndIncrement();
                if (i == 4) Thread.currentThread().interrupt();
                return point(i, 0);
            }
        };
        assertThatThrownBy(() -> {
            try { OfficialRouteDeflectionRules.validatePolyline("edge", coordinates); }
            finally {
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                Thread.interrupted();
            }
        }).isInstanceOf(CancellationException.class);
        assertThat(delivered).hasValue(5);
    }

    private List<RouteCoordinate> turn(double angle, double length) {
        return List.of(point(0, 0), point(length, 0),
                point(length + length * Math.cos(Math.toRadians(angle)), length * Math.sin(Math.toRadians(angle))));
    }

    private List<RouteCoordinate> transform(List<RouteCoordinate> coordinates, double angle) {
        return coordinates.stream().map(p -> point(600_000 + p.getXM().doubleValue() * Math.cos(angle) - p.getYM().doubleValue() * Math.sin(angle),
                6_000_000 + p.getXM().doubleValue() * Math.sin(angle) + p.getYM().doubleValue() * Math.cos(angle))).collect(Collectors.toList());
    }

    private RouteNode node(String id, RouteCoordinate coordinate) {
        return new RouteNode(id, "technical_node", coordinate, false, false, 0, null);
    }

    private RouteCoordinate point(double x, double y) { return new RouteCoordinate(x, y); }
}
