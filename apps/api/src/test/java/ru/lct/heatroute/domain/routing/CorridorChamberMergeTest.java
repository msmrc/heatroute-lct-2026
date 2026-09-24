package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Проверяет реальные слияния коридорных камер через окончательный sizing, геометрию и экономику. */
class CorridorChamberMergeTest {
    private final OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void adjacentDegreeThreeCamerasBecomeOneDegreeFourCameraWithEveryConsumerAndFlow() {
        Fixture fixture = fixture(5, 0, 0, 0);
        RouteVariant source = finish(fixture.draft(), fixture);
        assertFinished(source, fixture);
        assertThat(branches(source)).hasSize(2);

        List<RouteVariant> merged = merge(fixture);

        assertThat(merged).isNotEmpty();
        for (RouteVariant variant : merged) {
            assertMerged(variant, fixture);
            assertOrthogonalJoint(variant);
            RouteNode chamber = branches(variant).get(0);
            assertThat(incidentCount(variant, chamber.getId())).isEqualTo(4);
            assertThat(variant.getEdges()).hasSize(4);
            assertThat(variant.getNodes()).extracting(RouteNode::getId).doesNotContain("a", "b");
            RouteEdge incoming = variant.getEdges().stream()
                    .filter(edge -> edge.getDownstreamNodeId().equals(chamber.getId())).findFirst().orElseThrow();
            assertThat(incoming.getFlowTph()).isEqualByComparingTo("6");
        }
    }

    @Test
    void translationAndRotationPreserveCandidateTopologyGeometryAndFlows() {
        List<RouteVariant> expected = merge(fixture(5, 0, 0, 0));
        assertThat(expected).isNotEmpty();
        for (double angle : new double[] {0, Math.PI / 2, Math.PI, 0.37}) {
            Fixture moved = fixture(5, angle, 600000, 6000000);
            List<RouteVariant> actual = merge(moved);
            assertThat(actual).hasSameSizeAs(expected);
            for (int i = 0; i < expected.size(); i++) {
                RouteVariant baseline = expected.get(i), variant = actual.get(i);
                assertMerged(variant, moved);
                assertOrthogonalJoint(variant);
                assertThat(variant.getNodes()).extracting(RouteNode::getId)
                        .containsExactlyElementsOf(baseline.getNodes().stream().map(RouteNode::getId).collect(Collectors.toList()));
                assertThat(variant.getTotalLengthM().doubleValue())
                        .isCloseTo(baseline.getTotalLengthM().doubleValue(), within(0.02));
                for (int edgeIndex = 0; edgeIndex < baseline.getEdges().size(); edgeIndex++) {
                    RouteEdge left = baseline.getEdges().get(edgeIndex), right = variant.getEdges().get(edgeIndex);
                    assertThat(right.getId()).isEqualTo(left.getId());
                    assertThat(right.getFlowTph()).isEqualByComparingTo(left.getFlowTph());
                    assertThat(right.getDiameter()).isEqualTo(left.getDiameter());
                    assertThat(right.getCoordinates()).hasSameSizeAs(left.getCoordinates());
                    for (int point = 0; point < left.getCoordinates().size(); point++) {
                        Coordinate world = right.getCoordinates().get(point).toCoordinate();
                        double dx = world.x - 600000, dy = world.y - 6000000;
                        Coordinate restored = new Coordinate(dx * Math.cos(angle) + dy * Math.sin(angle),
                                -dx * Math.sin(angle) + dy * Math.cos(angle));
                        assertThat(restored.distance(left.getCoordinates().get(point).toCoordinate())).isLessThan(0.02);
                    }
                }
            }
        }
    }

    @Test
    void rootCameraIsNeverMergedEvenWhenItAndItsNeighbourBothHaveDegreeThree() {
        Fixture fixture = fixture(5, 0, 0, 0);
        fixture.nodes.removeIf(node -> node.getId().equals("root"));
        fixture.edges.removeIf(edge -> edge.getUpstreamNodeId().equals("root"));
        fixture.replaceNode("a", new RouteNode("a", "existing_chamber_tie_in", new RouteCoordinate(0, 0),
                true, true, 1, "network"));
        fixture.addDemand("west", -100, 0, "a", 4);
        RouteVariant source = finish(fixture.draft(), fixture);
        assertFinished(source, fixture);
        assertThat(incidentCount(source, "a")).isEqualTo(3);
        assertThat(incidentCount(source, "b")).isEqualTo(3);

        assertThat(candidates(fixture.draft(), fixture)).isEmpty();
    }

    @Test
    void eitherNeighbourHavingDegreeTwoOrFourPreventsAMerge() {
        for (String chamber : List.of("a", "b")) {
            for (int degree : List.of(2, 4)) {
                Fixture fixture = fixture(5, 0, 0, 0);
                if (degree == 2) {
                    fixture.removeDemand(chamber.equals("a") ? "north" : "south");
                } else {
                    fixture.addDemand("extra", chamber.equals("a") ? -30 : 35, 60, chamber, 4);
                }
                RouteVariant source = finish(fixture.draft(), fixture);
                assertFinished(source, fixture);
                assertThat(incidentCount(source, chamber)).isEqualTo(degree);
                assertThat(candidates(fixture.draft(), fixture)).as("camera=%s degree=%s", chamber, degree).isEmpty();
            }
        }
    }

    @Test
    void corridorScopeAllowsBeyondTwentyMetresThroughEightyButNotAboveEighty() {
        for (double distance : new double[] {20.001, 79.999, 80.0}) {
            Fixture fixture = fixture(distance, 0, 0, 0);
            List<RouteVariant> merged = merge(fixture);
            assertThat(merged).as("link length %s", distance).isNotEmpty();
            merged.forEach(variant -> assertMerged(variant, fixture));
        }
        Fixture tooFar = fixture(80.001, 0, 0, 0);
        assertFinished(finish(tooFar.draft(), tooFar), tooFar);
        assertThat(candidates(tooFar.draft(), tooFar)).isEmpty();
    }

    @Test
    void obstacleOnANewDirectBranchCannotBecomeAnUnauthorizedCrossingAfterFinish() throws Exception {
        Fixture fixture = fixture(30, 0, 0, 0);
        ImportedOfficialFeature park = new ImportedOfficialFeature("synthetic-park", "restriction",
                mapper.createObjectNode().put("restriction_type", "park"),
                new WKTReader().read("POLYGON ((14 -52,16 -52,16 -48,14 -48,14 -52))"));
        fixture.features.add(park);
        assertFinished(finish(fixture.draft(), fixture), fixture);
        // Прямая от старой камеры a к south пересекает парк; исходная ветка b → south — нет.
        assertThat(line(List.of(fixture.node("a").getCoordinate(), fixture.node("demand:south").getCoordinate()))
                .intersects(park.getMetricGeometry())).isTrue();

        List<RouteVariant> merged = merge(fixture);

        assertThat(merged).isNotEmpty();
        for (RouteVariant variant : merged) {
            assertMerged(variant, fixture);
            for (RouteEdge edge : variant.getEdges()) {
                assertThat(line(edge.getCoordinates()).distance(park.getMetricGeometry())).isGreaterThanOrEqualTo(0.999);
            }
        }
    }

    @Test
    void retainedUnrelatedBranchAndItsConsumerRemainIntactAndCannotBeCrossed() {
        Fixture fixture = fixture(30, 0, 0, 0);
        fixture.nodes.add(node("other-root", -10, -50, true, true));
        fixture.addDemand("retained", 20, -50, "other-root", 4);
        RouteEdge retained = fixture.edges.stream()
                .filter(edge -> edge.getDownstreamNodeId().equals("demand:retained")).findFirst().orElseThrow();
        assertFinished(finish(fixture.draft(), fixture), fixture);
        assertThat(line(List.of(fixture.node("a").getCoordinate(), fixture.node("demand:south").getCoordinate()))
                .intersects(line(retained.getCoordinates()))).isTrue();

        List<RouteVariant> merged = merge(fixture);

        assertThat(merged).isNotEmpty();
        for (RouteVariant variant : merged) {
            assertMerged(variant, fixture);
            RouteEdge retainedResult = variant.getEdges().stream().filter(edge -> edge.getId().equals(retained.getId()))
                    .findFirst().orElseThrow();
            assertThat(retainedResult.getCoordinates()).usingRecursiveComparison().isEqualTo(retained.getCoordinates());
            for (RouteEdge edge : variant.getEdges()) {
                if (!edge.getId().equals(retained.getId())) {
                    assertThat(line(edge.getCoordinates()).intersection(line(retainedResult.getCoordinates())).isEmpty()).isTrue();
                }
            }
        }
    }

    @Test
    void generatingAndFinishingCandidatesDoesNotMutateSourceDraftOrConstructorOwnedLists() throws Exception {
        Fixture fixture = fixture(5, 0, 0, 0);
        OfficialRoutePlanner.VariantDraft source = fixture.draft();
        String inputSnapshot = mapper.writeValueAsString(List.of(fixture.nodes, fixture.edges, fixture.connections));
        String before = mapper.writeValueAsString(finish(source, fixture));
        List<OfficialRoutePlanner.VariantDraft> candidates = candidates(source, fixture);
        assertThat(candidates).isNotEmpty();
        for (OfficialRoutePlanner.VariantDraft candidate : candidates) assertMerged(finish(candidate, fixture), fixture);

        assertThat(mapper.writeValueAsString(finish(source, fixture))).isEqualTo(before);
        assertThat(mapper.writeValueAsString(List.of(fixture.nodes, fixture.edges, fixture.connections))).isEqualTo(inputSnapshot);
    }

    @Test
    void legacyPrivatePathKeepsDirectDiagonalApproachesAndTwentyMetreLimitWithoutGlobalRuleChanges() throws Exception {
        Fixture fixture = fixture(20, 0, 0, 0);
        OfficialRoutePlanner.VariantDraft source = fixture.draft();
        List<RouteVariant> before = legacyCandidates(source, fixture).stream()
                .map(draft -> finish(draft, fixture)).collect(Collectors.toList());
        assertThat(before).hasSize(3);
        assertThat(before.stream().map(variant -> branches(variant).get(0).getCoordinate().getXM()))
                .containsExactly(new BigDecimal("0.000"), new BigDecimal("20.000"), new BigDecimal("10.000"));
        for (RouteVariant variant : before) {
            assertMerged(variant, fixture);
            assertThat(variant.getEdges()).allSatisfy(edge -> assertThat(edge.getCoordinates()).hasSize(2));
        }
        // В контрольном пути сохраняется допустимая диагональ, не прошедшая бы новый фильтр ±5°.
        RouteEdge southAtLeft = before.get(0).getEdges().stream()
                .filter(edge -> edge.getDownstreamNodeId().equals("demand:south")).findFirst().orElseThrow();
        assertThat(southAtLeft.getCoordinates()).usingRecursiveComparison()
                .isEqualTo(List.of(new RouteCoordinate(0, 0), new RouteCoordinate(20, -100)));
        String snapshot = mapper.writeValueAsString(before);

        candidates(source, fixture);
        List<RouteVariant> after = legacyCandidates(source, fixture).stream()
                .map(draft -> finish(draft, fixture)).collect(Collectors.toList());

        assertThat(mapper.writeValueAsString(after)).isEqualTo(snapshot);
        Fixture beyondOldLimit = fixture(20.001, 0, 0, 0);
        assertThat(legacyCandidates(beyondOldLimit.draft(), beyondOldLimit)).isEmpty();
    }

    @Test
    void explicitOrthogonalWitnessConfirmsFailingEmptySpaceAndRetainedBranchCasesAreFeasible() {
        for (double separation : new double[] {20.001, 79.999, 80.0, 30.0}) {
            Fixture fixture = fixture(separation, 0, 0, 0);
            if (separation == 30.0) {
                fixture.nodes.add(node("other-root", -10, -50, true, true));
                fixture.addDemand("retained", 20, -50, "other-root", 4);
            }
            // Камера в b; запад/восток/юг прямые, к north ведёт свободный Г-образный ввод.
            RouteNode joint = node("witness-joint", separation, 0, true, false);
            List<RouteNode> nodes = fixture.nodes.stream().filter(node -> !Set.of("a", "b").contains(node.getId()))
                    .collect(Collectors.toCollection(ArrayList::new));
            nodes.add(joint);
            List<RouteEdge> edges = fixture.edges.stream().filter(edge -> !Set.of("a", "b").contains(edge.getUpstreamNodeId())
                    && !Set.of("a", "b").contains(edge.getDownstreamNodeId())).collect(Collectors.toCollection(ArrayList::new));
            edges.add(edge(fixture.node("root"), joint, new BigDecimal("6")));
            edges.add(edge(joint, fixture.node("demand:east"), BigDecimal.ONE));
            edges.add(edge(joint, fixture.node("demand:south"), new BigDecimal("3")));
            List<RouteCoordinate> north = List.of(joint.getCoordinate(), new RouteCoordinate(separation, 100),
                    fixture.node("demand:north").getCoordinate());
            double length = line(north).getLength();
            edges.add(new RouteEdge("witness-north", joint.getId(), "demand:north", length, north,
                    List.of(new RouteSection("base", null, null, north, length, null)), new BigDecimal("2"), 150));
            RouteVariant witness = finish(new OfficialRoutePlanner.VariantDraft(nodes, edges, fixture.connections), fixture);
            assertMerged(witness, fixture);
            assertOrthogonalJoint(witness);
            assertThat(new EngineeringRouteEvaluator().evaluate(witness.getEdges()).isCompliant()).isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    private List<OfficialRoutePlanner.VariantDraft> legacyCandidates(OfficialRoutePlanner.VariantDraft draft,
            Fixture fixture) throws Exception {
        java.lang.reflect.Method method = OfficialRoutePlanner.class.getDeclaredMethod("mergedBranchChamberCandidates",
                OfficialRoutePlanner.VariantDraft.class, List.class, OfficialRoutingEnvironment.class);
        method.setAccessible(true);
        return (List<OfficialRoutePlanner.VariantDraft>) method.invoke(planner, draft, fixture.demands(), router.prepare(fixture.features));
    }

    private List<OfficialRoutePlanner.VariantDraft> candidates(OfficialRoutePlanner.VariantDraft draft, Fixture fixture) {
        return planner.corridorChamberMergeCandidates(draft, fixture.demands(), router.prepare(fixture.features));
    }

    private List<RouteVariant> merge(Fixture fixture) {
        return candidates(fixture.draft(), fixture).stream().map(draft -> finish(draft, fixture)).collect(Collectors.toList());
    }

    private RouteVariant finish(OfficialRoutePlanner.VariantDraft draft, Fixture fixture) {
        return planner.finish("synthetic-merge", "engineering", draft, fixture.features,
                new OfficialRunParameters(null, null, false).validated(), false, router.prepare(fixture.features));
    }

    private void assertMerged(RouteVariant variant, Fixture fixture) {
        assertFinished(variant, fixture);
        assertThat(branches(variant)).hasSize(1);
        assertThat(incidentCount(variant, branches(variant).get(0).getId())).isEqualTo(4);
        List<RouteNode> expectedRoots = fixture.nodes.stream().filter(RouteNode::isRoot)
                .sorted(Comparator.comparing(RouteNode::getId)).collect(Collectors.toList());
        assertThat(variant.getNodes().stream().filter(RouteNode::isRoot).collect(Collectors.toList()))
                .usingRecursiveComparison().isEqualTo(expectedRoots);
    }

    private void assertFinished(RouteVariant variant, Fixture fixture) {
        assertThat(variant.getValidationIssues()).extracting(RouteValidationIssue::getCode).isEmpty();
        assertThat(variant.getSizingIssues()).isEmpty();
        assertThat(variant.isValid()).isTrue();
        assertThat(validator.validate(variant.getNodes(), variant.getEdges(), fixture.features)).isEmpty();
        assertThat(variant.getEconomics().isComplete()).as("%s", variant.getEconomics().getIncompleteReasons()).isTrue();
        assertThat(variant.getEconomics().getUnconnectedPenalty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(variant.getNoRouteDemandCount()).isZero();
        assertThat(variant.getConnectedDemandCount()).isEqualTo(fixture.connections.size());
        assertThat(variant.getConnections()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(fixture.connections);
        Map<String, BigDecimal> demandFlows = fixture.connections.stream().collect(Collectors.toMap(
                connection -> "demand:" + connection.getDemandId(), RouteConnection::getFlowTph));
        Set<String> actualDemandNodes = variant.getNodes().stream()
                .filter(node -> "demand_connection".equals(node.getNodeType())).map(RouteNode::getId).collect(Collectors.toSet());
        assertThat(actualDemandNodes).containsExactlyInAnyOrderElementsOf(demandFlows.keySet());
        BigDecimal rootFlow = BigDecimal.ZERO;
        for (RouteNode node : variant.getNodes()) {
            BigDecimal outgoing = variant.getEdges().stream().filter(edge -> edge.getUpstreamNodeId().equals(node.getId()))
                    .map(RouteEdge::getFlowTph).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (node.isRoot()) { rootFlow = rootFlow.add(outgoing); continue; }
            List<RouteEdge> incoming = variant.getEdges().stream()
                    .filter(edge -> edge.getDownstreamNodeId().equals(node.getId())).collect(Collectors.toList());
            assertThat(incoming).hasSize(1);
            assertThat(incoming.get(0).getFlowTph()).isEqualByComparingTo(outgoing.add(demandFlows.getOrDefault(node.getId(), BigDecimal.ZERO)));
        }
        assertThat(rootFlow).isEqualByComparingTo(demandFlows.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        for (RouteEdge edge : variant.getEdges()) {
            assertThat(line(edge.getCoordinates()).isSimple()).isTrue();
            assertThat(line(edge.getCoordinates()).getLength()).isCloseTo(edge.getLengthM().doubleValue(), within(0.01));
            assertThat(edge.getDepthProfile()).isNull();
        }
    }

    private List<RouteNode> branches(RouteVariant variant) {
        return variant.getNodes().stream().filter(node -> !node.isRoot() && "new_branch_chamber".equals(node.getNodeType()))
                .collect(Collectors.toList());
    }

    private void assertOrthogonalJoint(RouteVariant variant) {
        RouteNode joint = branches(variant).get(0);
        List<Coordinate> rays = new ArrayList<>();
        for (RouteEdge edge : variant.getEdges()) {
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            if (edge.getUpstreamNodeId().equals(joint.getId())) rays.add(coordinates.get(1).toCoordinate());
            if (edge.getDownstreamNodeId().equals(joint.getId())) rays.add(coordinates.get(coordinates.size() - 2).toCoordinate());
        }
        Coordinate center = joint.getCoordinate().toCoordinate();
        assertThat(rays).hasSize(4);
        for (int i = 0; i < rays.size(); i++) {
            for (int j = i + 1; j < rays.size(); j++) {
                Coordinate a = rays.get(i), b = rays.get(j);
                double cosine = ((a.x - center.x) * (b.x - center.x) + (a.y - center.y) * (b.y - center.y))
                        / a.distance(center) / b.distance(center);
                double degrees = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosine))));
                assertThat(Math.min(Math.abs(degrees - 90), Math.abs(degrees - 180))).isLessThanOrEqualTo(5.01);
            }
        }
    }

    private int incidentCount(RouteVariant variant, String node) {
        return (int) variant.getEdges().stream().filter(edge -> edge.getUpstreamNodeId().equals(node)
                || edge.getDownstreamNodeId().equals(node)).count();
    }

    private Fixture fixture(double separation, double angle, double tx, double ty) {
        Fixture fixture = new Fixture();
        fixture.nodes.add(node("root", -100, 0, true, true));
        fixture.nodes.add(node("a", 0, 0, true, false));
        fixture.nodes.add(node("b", separation, 0, true, false));
        fixture.addDemand("east", separation + 100, 0, "b", 1);
        fixture.addDemand("north", 0, 100, "a", 2);
        fixture.addDemand("south", separation, -100, "b", 3);
        fixture.edges.add(edge(fixture.node("root"), fixture.node("a"), new BigDecimal("6")));
        fixture.edges.add(edge(fixture.node("a"), fixture.node("b"), new BigDecimal("4")));
        if (angle != 0 || tx != 0 || ty != 0) {
            List<RouteNode> original = new ArrayList<>(fixture.nodes);
            for (RouteNode node : original) {
                Coordinate p = node.getCoordinate().toCoordinate();
                RouteCoordinate moved = new RouteCoordinate(tx + p.x * Math.cos(angle) - p.y * Math.sin(angle),
                        ty + p.x * Math.sin(angle) + p.y * Math.cos(angle));
                fixture.replaceNode(node.getId(), new RouteNode(node.getId(), node.getNodeType(), moved,
                        node.isChamber(), node.isRoot(), node.getBaseIncidentSections(), node.getTargetId()));
            }
            List<RouteEdge> edges = new ArrayList<>(fixture.edges);
            fixture.edges.clear();
            for (RouteEdge edge : edges) fixture.edges.add(edge(fixture.node(edge.getUpstreamNodeId()),
                    fixture.node(edge.getDownstreamNodeId()), edge.getFlowTph()));
        }
        return fixture;
    }

    private RouteNode node(String id, double x, double y, boolean chamber, boolean root) {
        return new RouteNode(id, root ? "existing_chamber_tie_in" : chamber ? "new_branch_chamber" : "demand_connection",
                new RouteCoordinate(x, y), chamber, root, root ? 2 : 0, root ? "network:" + id : null);
    }

    private RouteEdge edge(RouteNode from, RouteNode to, BigDecimal flow) {
        List<RouteCoordinate> coordinates = List.of(from.getCoordinate(), to.getCoordinate());
        double length = line(coordinates).getLength();
        return new RouteEdge(from.getId() + ":" + to.getId(), from.getId(), to.getId(), length, coordinates,
                List.of(new RouteSection("base", null, null, coordinates, length, null)), flow, 150);
    }

    private LineString line(List<RouteCoordinate> coordinates) {
        return geometryFactory.createLineString(coordinates.stream().map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
    }

    private final class Fixture {
        private final List<RouteNode> nodes = new ArrayList<>();
        private final List<RouteEdge> edges = new ArrayList<>();
        private final List<RouteConnection> connections = new ArrayList<>();
        private final List<ImportedOfficialFeature> features = new ArrayList<>();

        private RouteNode node(String id) {
            return nodes.stream().filter(node -> node.getId().equals(id)).findFirst().orElseThrow();
        }

        private void addDemand(String id, double x, double y, String upstream, int flow) {
            RouteNode demand = CorridorChamberMergeTest.this.node("demand:" + id, x, y, false, false);
            nodes.add(demand);
            edges.add(edge(node(upstream), demand, BigDecimal.valueOf(flow)));
            connections.add(new RouteConnection(id, "connection:" + id, BigDecimal.valueOf(flow), "connected", null));
        }

        private void removeDemand(String id) {
            nodes.removeIf(node -> node.getId().equals("demand:" + id));
            edges.removeIf(edge -> edge.getDownstreamNodeId().equals("demand:" + id));
            connections.removeIf(connection -> connection.getDemandId().equals(id));
        }

        private void replaceNode(String id, RouteNode replacement) {
            for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).getId().equals(id)) nodes.set(i, replacement);
        }

        private List<OfficialRoutePlanner.Demand> demands() {
            return connections.stream().map(connection -> new OfficialRoutePlanner.Demand(connection.getDemandId(),
                    connection.getConnectionPointId(), node("demand:" + connection.getDemandId()).getCoordinate().toCoordinate(),
                    connection.getFlowTph(), null)).collect(Collectors.toList());
        }

        private OfficialRoutePlanner.VariantDraft draft() {
            return new OfficialRoutePlanner.VariantDraft(nodes, edges, connections);
        }
    }
}
