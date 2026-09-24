package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;

/** Обязательный угол поворота по §2.1, а не экспертное предпочтение углов 90/135°. */
class OfficialRouteDeflectionTest {
    private static final String CODE = "ROUTE_DEFLECTION_EXCEEDED";
    private final OfficialRouteValidator structural = new OfficialRouteValidator();
    private final OfficialRouteValidator full = new OfficialRouteValidator(new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));

    @Test
    void rejectsInternalDeflectionsAboveNinetyForEveryVariantRole() {
        for (double angle : new double[] {91, 135, 180}) {
            List<RouteCoordinate> coordinates = turn(angle, 10);
            List<RouteNode> nodes = endpoints(coordinates);
            List<RouteEdge> edges = List.of(edge("route", "root", "end", coordinates));
            for (List<RouteValidationIssue> issues : List.of(structural.validate(nodes, edges),
                    full.validate(nodes, edges, List.of()))) {
                assertThat(issues).extracting(RouteValidationIssue::getCode).contains(CODE);
                for (String role : List.of("shortest", "cheapest", "balanced", "engineering")) {
                    assertThat(new RouteVariant(role, role, nodes, edges, List.of(), BigDecimal.valueOf(20),
                            issues, List.of(), null, null, null).isValid()).isFalse();
                }
            }
        }
    }

    @Test
    void permitsStraightAndArbitraryThirtySixtyAndNinetyDegreeTurns() {
        for (double angle : new double[] {0, 17, 30, 60, 89, 90}) {
            List<RouteCoordinate> coordinates = turn(angle, 10);
            List<RouteNode> nodes = endpoints(coordinates);
            List<RouteEdge> edges = List.of(edge("route", "root", "end", coordinates));
            assertThat(structural.validate(nodes, edges)).isEmpty();
            assertThat(full.validate(nodes, edges, List.of())).isEmpty();
        }
    }

    @Test
    void detectsTheSameTurnWhenSplitAtATechnicalNodeOrDegreeTwoChamber() {
        for (String nodeType : List.of("technical_node", "new_branch_chamber")) {
            for (double angle : new double[] {91, 135, 180}) {
                Network network = split(turn(angle, 10), nodeType);
                for (List<RouteValidationIssue> issues : List.of(structural.validate(network.nodes, network.edges),
                        full.validate(network.nodes, network.edges, List.of()))) {
                    assertThat(issues).filteredOn(i -> CODE.equals(i.getCode()))
                            .extracting(RouteValidationIssue::getSubjectId).containsExactly("joint");
                }
            }
        }
    }

    @Test
    void consecutiveDuplicateVerticesAndSubCentimetreLegsCannotHideDeflection() {
        List<RouteCoordinate> turn = turn(135, 10);
        List<RouteCoordinate> duplicates = List.of(turn.get(0), turn.get(0), turn.get(1), turn.get(1),
                turn.get(1), turn.get(2), turn.get(2));
        assertThat(structural.validate(endpoints(duplicates), List.of(edge("route", "root", "end", duplicates))))
                .extracting(RouteValidationIssue::getCode).contains(CODE);
        List<RouteCoordinate> tiny = List.of(point(0, 0), point(0.001, 0), point(0, 0), point(0, 1));
        assertThat(structural.validate(endpoints(tiny), List.of(edge("route", "root", "end", tiny))))
                .extracting(RouteValidationIssue::getCode).contains(CODE);
        Network split = split(turn(135, 10), "technical_node");
        List<RouteEdge> edges = split.edges.stream().map(e -> edge(e.getId(), e.getUpstreamNodeId(), e.getDownstreamNodeId(),
                List.of(e.getCoordinates().get(0), e.getCoordinates().get(0),
                        e.getCoordinates().get(1), e.getCoordinates().get(1)))).collect(Collectors.toList());
        assertThat(structural.validate(split.nodes, edges)).filteredOn(i -> CODE.equals(i.getCode()))
                .extracting(RouteValidationIssue::getSubjectId).containsExactly("joint");
    }

    @Test
    void doesNotInventAnObliqueJunctionRuleAtThreeOrFourRayChambers() {
        for (int rays : new int[] {3, 4}) {
            List<RouteNode> nodes = new ArrayList<>(List.of(node("root", point(-10, 0), true, true, 0),
                    node("joint", point(0, 0), false, true, 0)));
            List<RouteEdge> edges = new ArrayList<>(List.of(edge("trunk", "root", "joint", List.of(point(-10, 0), point(0, 0)))));
            for (int i = 0; i < rays - 1; i++) {
                RouteCoordinate end = point(10 * Math.cos(Math.toRadians(i * 30)), 10 * Math.sin(Math.toRadians(i * 30)));
                nodes.add(node("end" + i, end, false, false, 0));
                edges.add(edge("branch" + i, "joint", "end" + i, List.of(point(0, 0), end)));
            }
            assertThat(structural.validate(nodes, edges)).isEmpty();
        }
        // Два новых луча + две части существующей магистрали — степень четыре, не сквозной поворот.
        List<RouteNode> nodes = List.of(node("root", point(0, 0), true, true, 2),
                node("a", point(10, 0), false, false, 0), node("b", point(10, 5), false, false, 0));
        List<RouteEdge> edges = List.of(edge("a", "root", "a", List.of(point(0, 0), point(10, 0))),
                edge("b", "root", "b", List.of(point(0, 0), point(10, 5))));
        assertThat(structural.validate(nodes, edges)).isEmpty();
    }

    private Network split(List<RouteCoordinate> coordinates, String nodeType) {
        List<RouteNode> nodes = List.of(node("root", coordinates.get(0), true, true, 0),
                new RouteNode("joint", nodeType, coordinates.get(1), "new_branch_chamber".equals(nodeType), false, 0, null),
                node("end", coordinates.get(2), false, false, 0));
        List<RouteEdge> edges = List.of(edge("before", "root", "joint", coordinates.subList(0, 2)),
                edge("after", "joint", "end", coordinates.subList(1, 3)));
        return new Network(nodes, edges);
    }

    private List<RouteCoordinate> turn(double angle, double length) {
        return List.of(point(0, 0), point(length, 0),
                point(length + length * Math.cos(Math.toRadians(angle)), length * Math.sin(Math.toRadians(angle))));
    }

    private List<RouteNode> endpoints(List<RouteCoordinate> coordinates) {
        return List.of(node("root", coordinates.get(0), true, true, 0),
                node("end", coordinates.get(coordinates.size() - 1), false, false, 0));
    }

    private RouteNode node(String id, RouteCoordinate coordinate, boolean root, boolean chamber, int existing) {
        return new RouteNode(id, root ? "existing_chamber_tie_in" : chamber ? "new_branch_chamber" : "demand_connection",
                coordinate, chamber, root, existing, root ? "existing" : null);
    }

    private RouteEdge edge(String id, String from, String to, List<RouteCoordinate> coordinates) {
        double length = 0;
        for (int i = 1; i < coordinates.size(); i++) length += coordinates.get(i - 1).toCoordinate().distance(coordinates.get(i).toCoordinate());
        return new RouteEdge(id, from, to, length, coordinates, List.of(), BigDecimal.ONE, 50);
    }

    private RouteCoordinate point(double x, double y) { return new RouteCoordinate(x, y); }

    private static final class Network {
        private final List<RouteNode> nodes;
        private final List<RouteEdge> edges;
        private Network(List<RouteNode> nodes, List<RouteEdge> edges) { this.nodes = nodes; this.edges = edges; }
    }
}
