package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.DepthProfileIssue;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Проверяет отказы доводки камер через настоящий finish, sizing, глубину, смету и selector. */
@Timeout(30)
class OfficialChamberQualitySafetyTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final EngineeringRouteEvaluator engineering = new EngineeringRouteEvaluator();
    private final RegressionRoutePlannerFixture planner = new OfficialDatasetRoutingTest().planner();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialAxisClearance clearances = new OfficialAxisClearance(pipes, new OfficialConstraintCatalog());
    private final OfficialDepthPlanner depths = new OfficialDepthPlanner(
            new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
            new OfficialDepthOptimizer(pipes, new OfficialEconomics()), new OfficialDepthProfileValidator(pipes));
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsEverySelectedRoleClearOfAnObstacleAtTheAttractiveRelocation(boolean depth) {
        var parameters = new OfficialRunParameters(null, null, depth).validated();
        RouteVariant attractive = attractiveRelocation(1, parameters);
        Coordinate attractiveChamber = chamber(attractive);
        ImportedOfficialFeature park = polygon("restriction", "synthetic-park", "park",
                attractiveChamber.x - 414000 - .5, attractiveChamber.y - 6173000 - .5,
                attractiveChamber.x - 414000 + .5, attractiveChamber.y - 6173000 + .5);
        List<ImportedOfficialFeature> features = List.of(park);
        assertThat(park.getMetricGeometry().covers(new GeometryFactory().createPoint(chamber(attractive)))).isTrue();
        assertThat(validator.validate(attractive.getNodes(), attractive.getEdges(), features))
                .as("The unconstrained repair must really violate this obstacle").isNotEmpty();

        var environment = new OfficialObstacleRouter(rules).prepare(features);
        RouteVariant original = finish(seed(1), features, parameters, environment);
        assertRepairableSeed(original, features, parameters);
        assertThat(engineering.evaluate(original.getEdges()).irregularJunctionAngleCount()).isPositive();
        List<RouteVariant> roles = improve(original, 1, features, parameters, environment);

        assertPortfolio(roles, original, features, parameters);
        roles.forEach(role -> assertPolygonClearance(role, park, "park"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesTheSizedDiameterClearanceInsteadOfInitialDu50WhenMovingTheChamber(boolean depth) {
        var parameters = new OfficialRunParameters(null, null, depth).validated();
        RouteVariant attractive = attractiveRelocation(1000, parameters);
        // 6,5 м достаточно для ДУ50 (5,2 м), но недостаточно для ДУ500/600 (7,835/7,925 м).
        RouteEdge attractiveInput = attractive.getEdges().stream().filter(edge -> "a".equals(edge.getId()))
                .findFirst().orElseThrow();
        Coordinate attractiveJunction = chamber(attractive);
        Coordinate clearancePoint = new Coordinate(attractiveJunction.x, attractiveJunction.y - 6.5);
        ImportedOfficialFeature building = new ImportedOfficialFeature("synthetic-building", "oks_existing",
                new ObjectMapper().createObjectNode(), new GeometryFactory().createPoint(clearancePoint));
        List<ImportedOfficialFeature> features = List.of(building);
        double distanceM = building.getMetricGeometry().distance(line(attractiveInput));
        assertThat(distanceM).isBetween(6.49, 6.51);
        assertThat(distanceM).isGreaterThan(clearances.axisClearanceM("oks", 50, null).doubleValue());
        assertThat(distanceM).isLessThan(clearances.axisClearanceM("oks", 600, null).doubleValue());
        assertThat(validator.validate(attractive.getNodes(), attractive.getEdges(), features)).isNotEmpty();

        var smallEnvironment = new OfficialObstacleRouter(rules).prepare(features);
        RouteVariant smallSeed = finish(seed(1), features, parameters, smallEnvironment);
        List<RouteVariant> smallRoles = improve(smallSeed, 1, features, parameters, smallEnvironment);
        assertPortfolio(smallRoles, smallSeed, features, parameters);
        assertThat(smallRoles.get(0).getEdges()).extracting(RouteEdge::getDiameter).containsOnly(50);
        assertThat(chamber(smallRoles.get(0)).equals2D(chamber(attractive))).isTrue();
        assertThat(engineering.evaluate(smallRoles.get(0).getEdges()).irregularJunctionAngleCount()).isZero();

        var environment = new OfficialObstacleRouter(rules).prepare(features);
        RegressionRoutePlannerFixture.VariantDraft initial = seed(1000);
        RouteVariant original = finish(initial, features, parameters, environment);
        assertRepairableSeed(original, features, parameters);
        assertThat(original.getEdges()).extracting(RouteEdge::getDiameter).containsExactly(500, 500, 600);
        assertThat(engineering.evaluate(original.getEdges()).irregularJunctionAngleCount()).isPositive();
        List<RouteVariant> roles = improve(original, 1000, features, parameters, environment);

        assertPortfolio(roles, original, features, parameters);
        roles.forEach(role -> {
            assertThat(role.getEdges()).extracting(RouteEdge::getDiameter).containsExactly(500, 500, 600);
            assertPolygonClearance(role, building, "oks");
        });
    }

    @Test
    void doesNotSelectAPlanarRepairWhoseEnabledDepthHasNoVerticalPassage() {
        var planarParameters = new OfficialRunParameters(new BigDecimal("3.0"), new BigDecimal("3.0"), false).validated();
        var depthParameters = new OfficialRunParameters(new BigDecimal("3.0"), new BigDecimal("3.0"), true).validated();
        var attributes = new ObjectMapper().createObjectNode().put("restriction_type", "gas_pipeline");
        var gas = new ImportedOfficialFeature("synthetic-gas", "restriction", attributes,
                rules.line(List.of(point(-100, 5).toCoordinate(), point(0, 5).toCoordinate())));
        var otherGas = new ImportedOfficialFeature("synthetic-other-gas", "restriction", attributes,
                rules.line(List.of(point(5, -100).toCoordinate(), point(5, 0).toCoordinate())));
        List<ImportedOfficialFeature> features = List.of(gas, otherGas);
        var planarEnvironment = new OfficialObstacleRouter(rules).prepare(features);
        RouteVariant planarSeed = finish(seed(1), features, planarParameters, planarEnvironment);
        assertRepairableSeed(planarSeed, features, planarParameters);
        List<RouteVariant> planarRoles = improve(planarSeed, 1, features, planarParameters, planarEnvironment);
        assertPortfolio(planarRoles, planarSeed, features, planarParameters);
        RouteVariant planarRepair = planarRoles.get(0);
        assertThat(engineering.evaluate(planarRepair.getEdges()).irregularJunctionAngleCount()).isZero();
        List<RouteEdge> crossings = planarRepair.getEdges().stream()
                .filter(edge -> features.stream().anyMatch(feature -> line(edge).intersects(feature.getMetricGeometry())))
                .collect(Collectors.toList());
        assertThat(crossings).as("The real 2D pipeline must accept a repair crossing this gas line").isNotEmpty();
        crossings.forEach(edge -> {
            var profile = depths.plan(edge, features, depthParameters.getMinimumDepthM(), depthParameters.getMaximumDepthM());
            assertThat(profile.isComplete()).isFalse();
            assertThat(profile.getIssues()).extracting(DepthProfileIssue::getCode).contains("NO_VERTICAL_PASSAGE");
        });

        var environment = new OfficialObstacleRouter(rules).prepare(features);
        RouteVariant original = finish(seed(1), features, depthParameters, environment);
        assertRepairableSeed(original, features, depthParameters);
        // Независимый положительный свидетель: конечные газопроводы можно обойти снаружи,
        // сохранив обе нагрузки, нормали камер и табличный минимум до первых поворотов.
        RouteVariant witness = finish(new RegressionRoutePlannerFixture.VariantDraft(
                original.getNodes(), List.of(
                    edge("backbone", "root", "j", 2, -100, 0, 0, 0),
                    edge("a", "j", "demand:a", 1, 0, 0, 0, 2.1, -104, 2.1, -104, 25, 30, 25, 30, 20),
                    edge("b", "j", "demand:b", 1, 0, 0, 2.1, 0, 2.1, -104, 20, -104, 20, 19.999)),
                original.getConnections()), features, depthParameters, environment);
        assertSafe(witness, original, features, depthParameters);
        assertThat(engineering.evaluate(original.getEdges()).irregularJunctionAngleCount()).isPositive();
        List<RouteVariant> roles = improve(original, 1, features, depthParameters, environment);

        assertPortfolio(roles, original, features, depthParameters);
        roles.forEach(role -> role.getEdges().forEach(edge -> {
            features.forEach(feature -> assertThat(line(edge).intersects(feature.getMetricGeometry())).isFalse());
            var independentlyPlanned = depths.plan(edge, features,
                    depthParameters.getMinimumDepthM(), depthParameters.getMaximumDepthM());
            assertThat(independentlyPlanned.isComplete()).isTrue();
            assertThat(independentlyPlanned.getIssues()).isEmpty();
        }));
    }

    private RouteVariant attractiveRelocation(int flow, OfficialRunParameters parameters) {
        var environment = new OfficialObstacleRouter(rules).prepare(List.of());
        RouteVariant original = finish(seed(flow), List.of(), parameters, environment);
        assertRepairableSeed(original, List.of(), parameters);
        assertThat(engineering.evaluate(original.getEdges()).irregularJunctionAngleCount()).isPositive();
        List<RouteVariant> roles = improve(original, flow, List.of(), parameters, environment);
        assertPortfolio(roles, original, List.of(), parameters);
        RouteVariant attractive = roles.get(0);
        assertThat(engineering.evaluate(attractive.getEdges()).irregularJunctionAngleCount()).isZero();
        assertThat(chamber(attractive).equals2D(point(0, 0).toCoordinate())).isFalse();
        return attractive;
    }

    private List<RouteVariant> improve(RouteVariant original, int flow, List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters, OfficialRoutingEnvironment environment) {
        List<RouteVariant> repaired = planner.repairMandatoryChambers(List.of(original), demands(flow), features,
                parameters, false, environment);
        assertThat(repaired).isNotEmpty();
        List<RouteVariant> selected = new FinishedRouteVariantSelector().select(repaired, parameters.isDepthEnabled());
        assertThat(selected).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
        return planner.improveSelectedChamberQuality(selected, demands(flow), features, parameters, false, environment);
    }

    private void assertPortfolio(List<RouteVariant> roles, RouteVariant original,
            List<ImportedOfficialFeature> features, OfficialRunParameters parameters) {
        assertThat(roles).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
        roles.forEach(role -> assertSafe(role, original, features, parameters));
    }

    private void assertSafe(RouteVariant result, RouteVariant original,
            List<ImportedOfficialFeature> features, OfficialRunParameters parameters) {
        assertThat(result.getValidationIssues()).isEmpty();
        assertThat(result.getSizingIssues()).isEmpty();
        assertThat(result.isValid()).isTrue();
        assertThat(result.getEngineeringIssues()).isEmpty();
        assertThat(engineering.evaluate(result.getEdges()).isCompliant()).isTrue();
        assertThat(validator.validate(result.getNodes(), result.getEdges(), features)).isEmpty();
        assertThat(new ExpertChamberRouteValidator().validate(result.getNodes(), result.getEdges())).isEmpty();
        assertThat(result.getConnectedDemandCount()).isEqualTo(2);
        assertThat(result.getConnections()).usingRecursiveComparison().isEqualTo(original.getConnections());
        assertThat(result.getNodes().stream().filter(node -> node.isRoot() || "demand_connection".equals(node.getNodeType()))
                .collect(Collectors.toList())).usingRecursiveComparison().isEqualTo(original.getNodes().stream()
                        .filter(node -> node.isRoot() || "demand_connection".equals(node.getNodeType())).collect(Collectors.toList()));
        assertThat(result.getEconomics().isComplete()).isTrue();
        assertThat(result.getEconomics().getCalculatedCost()).isPositive();
        var recalculated = new OfficialVariantEconomicsCalculator(pipes, new OfficialEconomics()).calculate(
                result.getNodes(), result.getEdges(), result.getConnections(), result.getReconstruction(), false);
        assertThat(result.getEconomics()).usingRecursiveComparison().isEqualTo(recalculated);
        result.getEdges().forEach(edge -> {
            assertThat(edge.getFlowTph()).isEqualByComparingTo(original.getConnections().get(0).getFlowTph()
                    .multiply(BigDecimal.valueOf("backbone".equals(edge.getId()) ? 2 : 1)));
            if (parameters.isDepthEnabled()) {
                assertThat(edge.getDepthProfile()).isNotNull();
                assertThat(edge.getDepthProfile().isComplete()).isTrue();
                assertThat(edge.getDepthProfile().getIssues()).isEmpty();
            }
        });
    }

    private void assertRepairableSeed(RouteVariant original, List<ImportedOfficialFeature> features,
            OfficialRunParameters parameters) {
        assertThat(original.isValid()).isFalse();
        assertThat(original.getValidationIssues()).extracting(RouteValidationIssue::getCode)
                .containsOnly("EXPERT_CHAMBER_OBLIQUE_ENTRY");
        assertThat(validator.validate(original.getNodes(), original.getEdges(), features)).isEmpty();
        assertThat(new ExpertChamberRouteValidator().validate(original.getNodes(), original.getEdges()))
                .extracting(RouteValidationIssue::getCode).containsOnly("EXPERT_CHAMBER_OBLIQUE_ENTRY");
        assertThat(ChamberQualityRefinementSearch.repairableSeed(original, parameters.isDepthEnabled())).isTrue();
        assertThat(original.getConnectedDemandCount()).isEqualTo(2);
        assertThat(original.getEconomics().isComplete()).isTrue();
    }

    private void assertPolygonClearance(RouteVariant role, ImportedOfficialFeature feature, String type) {
        role.getEdges().forEach(edge -> assertThat(line(edge).distance(feature.getMetricGeometry()))
                .as("%s/%s must clear %s at its final DU%d", role.getId(), edge.getId(), feature.getFeatureId(), edge.getDiameter())
                .isGreaterThanOrEqualTo(clearances.axisClearanceM(type, edge.getDiameter(), null).doubleValue() - 1e-6));
    }

    private Coordinate chamber(RouteVariant variant) {
        return variant.getNodes().stream().filter(node -> "j".equals(node.getId())).findFirst().orElseThrow()
                .getCoordinate().toCoordinate();
    }

    private LineString line(RouteEdge edge) {
        return rules.line(edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList()));
    }

    private ImportedOfficialFeature polygon(String objectType, String id, String restrictionType,
            double minX, double minY, double maxX, double maxY) {
        var attributes = new ObjectMapper().createObjectNode();
        if (restrictionType != null) attributes.put("restriction_type", restrictionType);
        Geometry geometry = new GeometryFactory().createPolygon(new Coordinate[] {
                point(minX, minY).toCoordinate(), point(maxX, minY).toCoordinate(),
                point(maxX, maxY).toCoordinate(), point(minX, maxY).toCoordinate(), point(minX, minY).toCoordinate()});
        return new ImportedOfficialFeature(id, objectType, attributes, geometry);
    }

    private RouteVariant finish(RegressionRoutePlannerFixture.VariantDraft draft,
            List<ImportedOfficialFeature> features, OfficialRunParameters parameters,
            OfficialRoutingEnvironment environment) {
        return planner.withEngineeringAssessment(planner.finish("cheapest", "cheapest", draft,
                features, parameters, false, environment, TerminalApproachPolicy.PRESERVE_VALID));
    }

    private RegressionRoutePlannerFixture.VariantDraft seed(int flow) {
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", point(-100, 0), true, true, 1, null, 1400),
                new RouteNode("j", "new_branch_chamber", point(0, 0), true, false, 0, null),
                new RouteNode("demand:a", "demand_connection", point(30, 20), false, false, 0, "a"),
                new RouteNode("demand:b", "demand_connection", point(20, 19.999), false, false, 0, "b"));
        List<RouteEdge> edges = List.of(
                edge("backbone", "root", "j", 2 * flow, -100, 0, 0, 0),
                edge("a", "j", "demand:a", flow, 0, 0, 30, 20),
                edge("b", "j", "demand:b", flow, 0, 0, 20, 19.999));
        assertThat(edges).extracting(RouteEdge::getDiameter).containsOnly(50);
        return new RegressionRoutePlannerFixture.VariantDraft(nodes, edges, List.of(
                new RouteConnection("a", "a", BigDecimal.valueOf(flow), "connected", null),
                new RouteConnection("b", "b", BigDecimal.valueOf(flow), "connected", null)));
    }

    private List<RegressionRoutePlannerFixture.Demand> demands(int flow) {
        return List.of(
                new RegressionRoutePlannerFixture.Demand("a", "a", point(30, 20).toCoordinate(), BigDecimal.valueOf(flow), null),
                new RegressionRoutePlannerFixture.Demand("b", "b", point(20, 19.999).toCoordinate(), BigDecimal.valueOf(flow), null));
    }

    private RouteEdge edge(String id, String from, String to, int flow, double... xy) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) coordinates.add(point(xy[i], xy[i + 1]).toCoordinate());
        return new RouteEdge(id, from, to, rules.line(coordinates).getLength(),
                coordinates.stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()),
                rules.sections(rules.line(coordinates), List.of()), BigDecimal.valueOf(flow), 50);
    }

    private RouteCoordinate point(double x, double y) {
        return new RouteCoordinate(414000 + x, 6173000 + y);
    }
}
