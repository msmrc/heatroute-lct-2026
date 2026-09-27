package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialRetainedEgressTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(doubles = {0, 0.37, 1.2})
    void retainsValidTerminalDirectionInsteadOfAimingItAtRemoteCamera(double rotation) {
        List<ImportedOfficialFeature> features = features(rotation);
        List<Coordinate> points = points(rotation, -20, -10, -20, 10, -10, 10, 10, 10);
        RouteEdge original = edge(points);
        assertThat(new OfficialRouteValidator(rules).validate(nodes(points), List.of(original), features)).isEmpty();
        for (boolean depth : List.of(false, true)) {
            for (String strategy : List.of("engineering", "shortest", "cheapest")) {
                RouteVariant result = finish(original, features, strategy, depth);
                assertAccepted(result, features, depth);
                assertThat(result.getEdges().get(0).getCoordinates()).usingRecursiveComparison()
                        .isEqualTo(original.getCoordinates());
                assertThat(new EngineeringRouteEvaluator().evaluate(result.getEdges()).bendCount()).isEqualTo(1);
            }
        }
    }

    @Test
    void stillRepairsAnApproachThatTurnsInsideItsOwnBuilding() {
        List<ImportedOfficialFeature> features = features(0);
        RouteEdge original = edge(points(0, -20, -10, -20, 10, 1, 10, 10, 10));
        assertThat(new OfficialRouteValidator(rules).validate(nodes(coordinates(original)), List.of(original), features))
                .anyMatch(issue -> "OKS_NORMAL_EGRESS_VIOLATION".equals(issue.getCode()));
        for (boolean depth : List.of(false, true)) {
            RouteVariant result = finish(original, features, "shortest", depth);
            assertAccepted(result, features, depth);
            assertThat(result.getEdges().get(0).getCoordinates()).usingRecursiveComparison()
                    .isNotEqualTo(original.getCoordinates());
        }
    }

    @Test
    void validTerminalDirectionDoesNotExemptTheRemainingRouteFromObstacles() {
        List<ImportedOfficialFeature> features = new ArrayList<>(features(0));
        features.add(new ImportedOfficialFeature("park", "restriction",
                json.createObjectNode().put("restriction_type", "park"),
                new GeometryFactory().createPolygon(points(0, -23, -3, -17, -3, -17, 3, -23, 3, -23, -3)
                        .toArray(new Coordinate[0]))));
        RouteEdge original = edge(points(0, -20, -10, -20, 10, -10, 10, 10, 10));
        assertThat(new OfficialRouteValidator(rules).validate(nodes(coordinates(original)), List.of(original), features))
                .extracting(RouteValidationIssue::getCode)
                .contains("FORBIDDEN_CLEARANCE_VIOLATION").doesNotContain("OKS_NORMAL_EGRESS_VIOLATION");
        RouteVariant result = finish(original, features, "shortest", false);
        assertAccepted(result, features, false);
        assertThat(result.getEdges().get(0).getCoordinates()).usingRecursiveComparison()
                .isNotEqualTo(original.getCoordinates());
    }

    @Test
    void repairsABlockedNormalUsingAnotherNearestPermittedWall() {
        List<ImportedOfficialFeature> features = new ArrayList<>(features(0));
        features.add(new ImportedOfficialFeature("park", "restriction",
                json.createObjectNode().put("restriction_type", "park"),
                new GeometryFactory().createPolygon(points(0, 3, 9, 6, 9, 6, 11, 3, 11, 3, 9)
                        .toArray(new Coordinate[0]))));
        RouteEdge original = edge(points(0, -20, -10, -20, 10, -10, 10, 10, 10));
        assertThat(rules.validateMandatoryEgress(original, rules.line(coordinates(original)), features, 50))
                .extracting(RouteValidationIssue::getCode).containsExactly("OKS_NORMAL_EGRESS_VIOLATION");
        assertThat(new OfficialRouteValidator(rules).validate(nodes(coordinates(original)), List.of(original), features))
                .extracting(RouteValidationIssue::getCode)
                .contains("OKS_NORMAL_EGRESS_VIOLATION", "FORBIDDEN_CLEARANCE_VIOLATION");
        for (boolean depth : List.of(false, true)) {
            RouteVariant result = finish(original, features, "shortest", depth);
            assertAccepted(result, features, depth);
            assertThat(result.getEdges().get(0).getCoordinates()).usingRecursiveComparison()
                    .isNotEqualTo(original.getCoordinates());
            List<Coordinate> repaired = coordinates(result.getEdges().get(0));
            Coordinate adjacent = repaired.get(repaired.size() - 2);
            Coordinate endpoint = repaired.get(repaired.size() - 1);
            assertThat(adjacent.x).as("the obstructed west normal must not be retained")
                    .isGreaterThanOrEqualTo(endpoint.x - 0.01);
            assertThat(rules.line(repaired).distance(features.get(1).getMetricGeometry()))
                    .as("the own-building exception never exempts the park on the final leg")
                    .isGreaterThanOrEqualTo(1.0 + 0.400 / 2 - 1e-6);
        }
    }

    @Test
    void ownBuildingExemptionDoesNotHideAnotherConstraintWithTheSameId() {
        List<ImportedOfficialFeature> features = new ArrayList<>(features(0));
        features.add(new ImportedOfficialFeature("own-building", "restriction",
                json.createObjectNode().put("restriction_type", "park"),
                new GeometryFactory().createPolygon(points(0, 3, 9, 6, 9, 6, 11, 3, 11, 3, 9)
                        .toArray(new Coordinate[0]))));
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        List<Coordinate> leg = points(0, -10, 10, 10, 10);
        assertThat(router.terminalApproachAllowed(leg.get(0), leg.get(1), 50, router.prepare(features),
                java.util.Set.of(), List.of(), "own-building")).isFalse();
        assertThat(router.terminalApproachAllowed(leg.get(0), leg.get(1), 50, router.prepare(features(0)),
                java.util.Set.of(), List.of(), "own-building")).isTrue();
        assertThat(router.terminalApproachAllowed(leg.get(0), leg.get(1), 50, router.prepare(features(0)),
                java.util.Set.of(), List.of(rules.line(points(0, 3, 7, 3, 13))), "own-building")).isFalse();
    }

    @Test
    void bothFinalizationPoliciesRequireLegalNormalsInsteadOfAShorterTargetRay() {
        List<ImportedOfficialFeature> features = features(0);
        RouteEdge original = edge(points(0, -20, -10, -20, 10, -10, 10, 10, 10));
        RouteEdge targetRay = edge(points(0, -20, -10, 10, 10));
        assertThat(rules.validateMandatoryEgress(targetRay, rules.line(coordinates(targetRay)), features, 50))
                .extracting(RouteValidationIssue::getCode).containsExactly("OKS_NORMAL_EGRESS_VIOLATION");
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        OfficialRoutePlanner.VariantDraft draft = new OfficialRoutePlanner.VariantDraft(
                nodes(coordinates(original)), List.of(original),
                List.of(new RouteConnection("one", "point-one", BigDecimal.ONE, "connected", null)));
        for (boolean depth : List.of(false, true)) {
            OfficialRunParameters parameters = new OfficialRunParameters(null, null, depth).validated();
            OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(features);
            RouteVariant previous = planner.finish("previous", "shortest", draft, features, parameters, false, environment);
            RouteVariant retained = planner.finish("retained", "shortest", draft, features, parameters, false,
                    environment, TerminalApproachPolicy.PRESERVE_VALID);
            assertAccepted(previous, features, depth);
            assertAccepted(retained, features, depth);
            // Обе политики обязаны отвергнуть короткий диагональный луч на корень; равные длины допустимы.
            for (RouteVariant result : List.of(previous, retained)) {
                assertThat(result.getTotalLengthM().doubleValue())
                        .isGreaterThan(rules.line(coordinates(targetRay)).getLength());
            }
            assertThat(retained.getEdges().get(0).getCoordinates()).usingRecursiveComparison()
                    .isEqualTo(original.getCoordinates());
        }
    }

    @Test
    void finalDiameterPromotionStillRechecksClearanceAlongTheWholePrefix() {
        List<ImportedOfficialFeature> features = new ArrayList<>(features(0));
        features.add(new ImportedOfficialFeature("other-building", "oks_existing", json.createObjectNode(),
                new GeometryFactory().createPolygon(points(0, -24, -7, -14, -7, -14, 1, -24, 1, -24, -7)
                        .toArray(new Coordinate[0]))));
        List<Coordinate> points = points(0, -30, -20, -30, 10, -10, 10, 10, 10);
        RouteEdge original = edge(points);
        // 6 м достаточно для ДУ400 (R+W/2=5.685 м), но не для ДУ500 (7.835 м).
        // Нижняя грань ОКС оставляет табличные 4 м прямого выхода из корневой камеры.
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OfficialRoutingEnvironment environment = router.prepare(features);
        List<Coordinate> prefix = points.subList(0, points.size() - 1);
        assertThat(router.lineAllowed(prefix, 400, environment, java.util.Set.of(), List.of())).isTrue();
        assertThat(router.lineAllowed(prefix, 500, environment, java.util.Set.of(), List.of())).isFalse();
        for (boolean depth : List.of(false, true)) {
            RouteVariant result = finish(original, features, "shortest", depth, new BigDecimal("1000"));
            assertAccepted(result, features, depth, 500);
            assertThat(result.getEdges().get(0).getCoordinates()).usingRecursiveComparison()
                    .isNotEqualTo(original.getCoordinates());
            assertThat(result.getEdges().get(0).getFlowTph()).isEqualByComparingTo("1000");
            List<Coordinate> repaired = coordinates(result.getEdges().get(0));
            assertThat(repaired.get(repaired.size() - 2).distance(repaired.get(repaired.size() - 1)))
                    .as("the final DU500 normal includes 10 m inside and R+W/2+0.25 m outside")
                    .isGreaterThanOrEqualTo(10 + 7 + 1.670 / 2 + 0.25 - 0.01);
        }
    }

    private void assertAccepted(RouteVariant result, List<ImportedOfficialFeature> features, boolean depth) {
        assertAccepted(result, features, depth, 50);
    }

    private void assertAccepted(RouteVariant result, List<ImportedOfficialFeature> features, boolean depth, int diameter) {
        assertThat(result.getValidationIssues()).extracting(issue -> issue.getCode() + ":"
                + issue.getSubjectId() + ":" + issue.getMessage()).isEmpty();
        assertThat(result.getSizingIssues()).isEmpty();
        assertThat(result.isValid()).isTrue();
        assertThat(result.getConnectedDemandCount()).isEqualTo(1);
        assertThat(result.getEconomics().isComplete()).isTrue();
        assertThat(new OfficialRouteValidator(rules).validate(result.getNodes(), result.getEdges(), features)).isEmpty();
        BigDecimal edgeLength = result.getEdges().stream().map(RouteEdge::getLengthM)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(result.getTotalLengthM()).isEqualByComparingTo(edgeLength);
        assertThat(result.getEconomics().getNewNetworkLength()).isEqualByComparingTo(edgeLength);
        assertThat(result.getEdges()).allSatisfy(edge -> {
            assertThat(edge.getDiameter()).isEqualTo(diameter);
            assertThat(rules.line(coordinates(edge)).getLength())
                    .isCloseTo(edge.getLengthM().doubleValue(), offset(0.001));
            assertThat(edge.getSections()).isNotEmpty();
            assertThat(edge.getSections().stream().map(RouteSection::getLengthM)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(edge.getLengthM());
            if (depth) assertThat(edge.getDepthProfile().isComplete()).isTrue();
        });
    }

    private RouteVariant finish(RouteEdge original, List<ImportedOfficialFeature> features,
            String strategy, boolean depth) {
        return finish(original, features, strategy, depth, BigDecimal.ONE);
    }

    private RouteVariant finish(RouteEdge original, List<ImportedOfficialFeature> features,
            String strategy, boolean depth, BigDecimal flow) {
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        return planner.finish("test", strategy,
                new OfficialRoutePlanner.VariantDraft(nodes(coordinates(original)), List.of(original),
                        List.of(new RouteConnection("one", "point-one", flow, "connected", null))),
                features, new OfficialRunParameters(null, null, depth).validated(), false,
                new OfficialObstacleRouter(rules).prepare(features), TerminalApproachPolicy.PRESERVE_VALID);
    }

    private List<ImportedOfficialFeature> features(double rotation) {
        return List.of(new ImportedOfficialFeature("own-building", "oks_existing", json.createObjectNode(),
                new GeometryFactory().createPolygon(points(rotation, 0, 0, 20, 0, 20, 20, 0, 20, 0, 0)
                        .toArray(new Coordinate[0]))));
    }

    private RouteEdge edge(List<Coordinate> points) {
        var line = rules.line(points);
        return new RouteEdge("edge", "root", "demand:one", line.getLength(),
                points.stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()),
                rules.sections(line, List.of()), BigDecimal.ONE, 50);
    }

    private List<RouteNode> nodes(List<Coordinate> points) {
        Coordinate root = points.get(0), demand = points.get(points.size() - 1);
        return List.of(
                new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(root.x, root.y), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", new RouteCoordinate(demand.x, demand.y), false, false, 0, null));
    }

    private List<Coordinate> coordinates(RouteEdge edge) {
        return edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
    }

    private List<Coordinate> points(double rotation, double... values) {
        List<Coordinate> result = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            double x = 500000 + values[i] * Math.cos(rotation) - values[i + 1] * Math.sin(rotation);
            double y = 6100000 + values[i] * Math.sin(rotation) + values[i + 1] * Math.cos(rotation);
            result.add(new RouteCoordinate(x, y).toCoordinate());
        }
        return result;
    }
}
