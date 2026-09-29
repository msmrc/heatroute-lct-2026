package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.util.AffineTransformation;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Техническая граница ввода не меняет допуск полного перехода после окончательного выбора ДУ. */
class OfficialFinalDiameterRoadRetentionTest {
    private final OfficialConstraintCatalog catalog = new OfficialConstraintCatalog();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            catalog, new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);
    private final GeometryFactory factory = new GeometryFactory();
    private final Coordinate connection = c(-1, 0);
    private final Coordinate root = c(-20, 20);
    // Обе вертикальные стены находятся ровно в метре от подключения.
    private final ImportedOfficialFeature own = rectangle("own", "oks", -2, -10, 0, 10);

    @Test
    void roadCrossingAcrossTechnicalApproachMustRetainValidIncumbent() throws Exception {
        assertRetainedAcrossSplit("road");
    }

    @Test
    void tramCrossingAcrossTechnicalApproachMustRetainValidIncumbent() throws Exception {
        assertRetainedAcrossSplit("tram_tracks");
    }

    @Test
    void sameSplitWithoutCrossingIsRetained() throws Exception {
        List<ImportedOfficialFeature> features = List.of(own);
        OfficialRoutingEnvironment environment = router.prepare(features);
        Coordinate approach = environment.normalEgressTowards(100, connection, c(20, 0))
                .orElseThrow().exit();
        RouteEdge before = edge(List.of(root, c(20, 20), c(20, 0), approach, connection), 100, features);
        assertValid(before, features);
        assertThat(ensure(before, features, environment))
                .as("without the crossing, the same technical split keeps the exact edge").isSameAs(before);
    }

    @Test
    void sameCompleteRoadAndTramGeometryWithoutTechnicalSplitIsRetained() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            List<ImportedOfficialFeature> features = features(type);
            RouteEdge before = edge(List.of(root, c(20, 20), c(20, 0), connection), 100, features);
            assertValid(before, features);
            assertThat(ensure(before, features, router.prepare(features)))
                    .as(type + ": removing only the collinear technical vertex retains the edge").isSameAs(before);
        }
    }

    @Test
    void realTurnInsideProtectionMustBeRejectedAndRepaired() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            List<ImportedOfficialFeature> features = features(type);
            OfficialRoutingEnvironment environment = router.prepare(features);
            Coordinate approach = environment.normalEgressTowards(100, connection, c(20, 0))
                    .orElseThrow().exit();
            // Защита перехода x=4..6 заканчивается в x=9, но здесь поворот уже в x=8.
            RouteEdge before = edge(List.of(root, c(8, 20), c(8, 0), approach, connection), 100, features);
            assertThat(assessment(points(before), features.get(1), 100).getFailureCode())
                    .isEqualTo("SPECIAL_CROSSING_NOT_STRAIGHT");
            assertThat(codes(before, features)).contains("SPECIAL_CROSSING_NOT_STRAIGHT");
            assertThat(terminalAllowed(before, environment)).isFalse();
            RouteEdge after = ensure(before, features, environment);
            assertThat(after.getCoordinates()).usingRecursiveComparison().isNotEqualTo(before.getCoordinates());
            assertValid(after, features);
        }
    }

    @Test
    void realFinalDiameterClearanceViolationMustNotBeRetained() throws Exception {
        List<ImportedOfficialFeature> features = List.of(own,
                rectangle("foreign", "oks", -8, 26, 8, 30));
        List<Coordinate> coordinates = List.of(root, c(20, 20), c(20, 0), c(10, 0), connection);
        RouteEdge at400 = edge(coordinates, 400, features);
        RouteEdge at500 = edge(coordinates, 500, features);
        assertThat(clearance("oks", 400)).isEqualByComparingTo("5.685");
        assertThat(clearance("oks", 500)).isEqualByComparingTo("7.835");
        assertValid(at400, features);
        assertThat(codes(at500, features)).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        OfficialRoutingEnvironment environment = router.prepare(features);
        assertThat(terminalAllowed(at400, environment)).isTrue();
        assertThat(terminalAllowed(at500, environment)).isFalse();
        RouteEdge after = ensure(at500, features, environment);
        assertThat(after.getCoordinates()).usingRecursiveComparison().isNotEqualTo(at500.getCoordinates());
        assertThat(after.getDiameter()).isEqualTo(500);
        assertValid(after, features);
    }

    @Test
    void storedDirectionAcceptsLegalEntranceDespiteShallowExit() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            ImportedOfficialFeature crossing = asymmetricCrossing(type, false);
            List<ImportedOfficialFeature> features = List.of(own, crossing);
            OfficialRoutingEnvironment environment = router.prepare(features);
            // Допуск готового ребра проверяем независимо от поиска нормали в обратном направлении.
            var egress = router.prepare(List.of(own)).normalEgressTowards(100, connection, c(150, 0))
                    .orElseThrow();
            RouteEdge before = edge(List.of(c(150, 0), egress.exit(), connection), 100, features);
            assertThat(assessment(points(before), crossing, 100).isAllowed()).isTrue();
            assertThat(assessment(reversed(points(before)), crossing, 100).getFailureCode())
                    .isEqualTo("SPECIAL_CROSSING_ANGLE_VIOLATION");
            assertValid(before, features);
            assertThat(router.terminalRouteAllowed(points(before), 100, environment,
                    Set.of(), List.of(), egress))
                    .as(type + ": stored root-to-demand entrance is 90 degrees, reversed entrance is 40").isTrue();
            assertThat(normal(before, environment).exit()).isEqualTo(egress.exit());
            assertThat(ensure(before, features, environment)).isSameAs(before);
        }
    }

    @Test
    void storedDirectionRejectsShallowEntranceEvenWhenReverseIsLegal() {
        for (String type : List.of("road", "tram_tracks")) {
            ImportedOfficialFeature crossing = asymmetricCrossing(type, true);
            List<ImportedOfficialFeature> features = List.of(own, crossing);
            OfficialRoutingEnvironment environment = router.prepare(features);
            var egress = router.prepare(List.of(own)).normalEgressTowards(100, connection, c(150, 0))
                    .orElseThrow();
            RouteEdge edge = edge(List.of(c(150, 0), egress.exit(), connection), 100, features);
            assertThat(assessment(points(edge), crossing, 100).getFailureCode())
                    .isEqualTo("SPECIAL_CROSSING_ANGLE_VIOLATION");
            assertThat(assessment(reversed(points(edge)), crossing, 100).isAllowed()).isTrue();
            assertThat(codes(edge, features)).containsExactly("SPECIAL_CROSSING_ANGLE_VIOLATION");
            assertThat(router.terminalRouteAllowed(points(edge), 100, environment,
                    Set.of(), List.of(), egress))
                    .as(type + ": reversing the route must not legalize its actual 40-degree entrance").isFalse();
        }
    }

    @Test
    void foreignRestrictionsRemainForbiddenOnBodyAndTerminalEvenWithOwnId() {
        List<ImportedOfficialFeature> baseline = features("road");
        OfficialRoutingEnvironment originalEnvironment = router.prepare(baseline);
        var egress = originalEnvironment.normalEgressTowards(100, connection, c(20, 0)).orElseThrow();
        RouteEdge edge = edge(List.of(root, c(20, 20), c(20, 0), egress.exit(), connection), 100, baseline);
        assertValid(edge, baseline);
        assertThat(terminalAllowed(edge, originalEnvironment)).isTrue();
        for (ImportedOfficialFeature foreign : List.of(
                rectangle("own", "park", 0.5, -0.5, 1, 0.5),
                rectangle("foreign", "park", 19, 8, 21, 10))) {
            List<ImportedOfficialFeature> features = new ArrayList<>(baseline);
            features.add(foreign);
            assertThat(rules.line(points(edge)).intersects(foreign.getMetricGeometry())).isTrue();
            assertThat(codes(edge, features)).contains("FORBIDDEN_CLEARANCE_VIOLATION");
            assertThat(router.terminalRouteAllowed(points(edge), 100, router.prepare(features),
                    Set.of(), List.of(), egress)).as(foreign.getFeatureId()).isFalse();
        }
    }

    @Test
    void acceptedRouteCollisionIsRejectedOnBodyTerminalAndRealEndpoint() {
        List<ImportedOfficialFeature> features = features("road");
        OfficialRoutingEnvironment environment = router.prepare(features);
        var egress = environment.normalEgressTowards(100, connection, c(20, 0)).orElseThrow();
        RouteEdge edge = edge(List.of(root, c(20, 20), c(20, 0), egress.exit(), connection), 100, features);
        assertValid(edge, features);
        assertThat(terminalAllowed(edge, environment)).isTrue();
        for (LineString accepted : List.of(
                rules.line(List.of(c(0, 19), c(0, 21))),
                rules.line(List.of(c(2, -2), c(2, 2))),
                rules.line(List.of(c(-20, 19), c(-20, 21))))) {
            assertThat(rules.line(points(edge)).intersects(accepted)).isTrue();
            assertThat(router.terminalRouteAllowed(points(edge), 100, environment,
                    Set.of(), List.of(accepted), egress)).as(accepted.toText()).isFalse();
        }
    }

    @Test
    void ownExemptionDoesNotAuthorizeOutsideBodyInsideOwnSetback() {
        List<ImportedOfficialFeature> features = List.of(own);
        OfficialRoutingEnvironment environment = router.prepare(features);
        var egress = environment.normalEgressTowards(100, connection, c(20, 0)).orElseThrow();
        Coordinate approach = egress.exit();
        RouteEdge edge = edge(List.of(root, c(3, 20), c(3, 12), c(5.505, 12), approach, connection),
                100, features);
        List<Coordinate> body = points(edge).subList(0, edge.getCoordinates().size() - 1);
        assertThat(rules.line(body).intersects(own.getMetricGeometry())).isFalse();
        assertThat(rules.line(body).distance(own.getMetricGeometry()))
                .isLessThan(clearance("oks", 100).doubleValue());
        assertThat(router.terminalApproachAllowed(approach, connection, 100, environment,
                Set.of(), List.of(), egress)).isTrue();
        assertThat(codes(edge, features)).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        assertThat(terminalAllowed(edge, environment)).isFalse();
    }

    @Test
    void technicalSplitDoesNotBecomeAnExemptForeignBuildingEndpoint() {
        OfficialRoutingEnvironment originalEnvironment = router.prepare(List.of(own));
        var egress = originalEnvironment.normalEgressTowards(100, connection, c(20, 0)).orElseThrow();
        List<ImportedOfficialFeature> features = List.of(own, rectangle("foreign", "oks", 9, -1, 11, 1));
        OfficialRoutingEnvironment environment = router.prepare(features);
        RouteEdge edge = edge(List.of(root, c(20, 20), c(20, 8), c(5.505, 8), egress.exit(), connection),
                100, features);
        List<Coordinate> body = points(edge).subList(0, edge.getCoordinates().size() - 1);
        // Техническое разбиение не разрешает отступ чужого ОКС ни одной из половин маршрута.
        assertThat(router.lineAllowed(body, 100, environment, Set.of(), List.of())).isFalse();
        assertThat(router.terminalApproachAllowed(egress.exit(), connection, 100, environment,
                Set.of(), List.of(), egress)).isFalse();
        assertThat(codes(edge, features)).contains("FORBIDDEN_CLEARANCE_VIOLATION");
        assertThat(router.terminalRouteAllowed(points(edge), 100, environment,
                Set.of(), List.of(), egress)).isFalse();

        assertThat(router.lineAllowed(body, 100, originalEnvironment, Set.of(), List.of())).isTrue();
        assertThat(router.terminalApproachAllowed(egress.exit(), connection, 100, originalEnvironment,
                Set.of(), List.of(), egress)).isTrue();
        assertValid(edge, List.of(own));
        assertThat(router.terminalRouteAllowed(points(edge), 100, originalEnvironment,
                Set.of(), List.of(), egress)).isTrue();
    }

    @Test
    void fullPolylineWindowFindsForeignRestrictionFarFromBothEndpoints() {
        List<ImportedOfficialFeature> baseline = features("road");
        OfficialRoutingEnvironment originalEnvironment = router.prepare(baseline);
        var egress = originalEnvironment.normalEgressTowards(100, connection, c(20, 0)).orElseThrow();
        RouteEdge edge = edge(List.of(root, c(1000, 20), c(1000, 0), egress.exit(), connection), 100, baseline);
        assertValid(edge, baseline);
        assertThat(terminalAllowed(edge, originalEnvironment)).isTrue();
        ImportedOfficialFeature distant = rectangle("distant", "park", 998, 8, 1002, 12);
        OfficialRoutingEnvironment environment = router.prepare(baseline,
                new InMemoryRoutingFeatureSource(List.of(distant)));
        assertThat(environment.featuresInWindow(root, connection)).doesNotContain(distant);
        List<ImportedOfficialFeature> allFeatures = new ArrayList<>(baseline);
        allFeatures.add(distant);
        assertThat(codes(edge, allFeatures)).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        assertThat(router.terminalRouteAllowed(points(edge), 100, environment,
                Set.of(), List.of(), egress)).isFalse();
    }

    @Test
    void rotatedLongerActualTerminalLegIsPreservedWithoutResplitting() throws Exception {
        // Поворот 3-4-5 сохраняет миллиметровую коллинеарность без ослабления геометрических допусков.
        for (double rotation : List.of(0.0, Math.atan2(4, 3))) {
            for (String type : List.of("road", "tram_tracks")) {
                AffineTransformation transform = AffineTransformation.rotationInstance(rotation, 500000, 6170000);
                List<ImportedOfficialFeature> features = features(type).stream()
                        .map(feature -> new ImportedOfficialFeature(feature.getFeatureId(), feature.getObjectType(),
                                feature.getAttributes(), transform.transform(feature.getMetricGeometry())))
                        .collect(Collectors.toList());
                List<Coordinate> coordinates = List.of(root, c(20, 20), c(20, 0), c(8, 0), connection).stream()
                        .map(point -> transform.transform(point, new Coordinate())).collect(Collectors.toList());
                RouteEdge before = edge(coordinates, 100, features);
                OfficialRoutingEnvironment environment = router.prepare(features);
                var egress = normal(before, environment);
                List<Coordinate> stored = points(before);
                assertThat(stored.get(3).distance(stored.get(4)))
                        .isGreaterThan(egress.start().distance(egress.exit()) + 2);
                assertThat(assessment(stored, features.get(1), 100).isAllowed()).isTrue();
                assertValid(before, features);
                assertThat(terminalAllowed(before, environment)).isTrue();
                assertThat(ensure(before, features, environment))
                        .as(type + "/rotation=" + rotation + ": keep the longer actual terminal leg").isSameAs(before);
            }
        }
    }

    @Test
    void genuineTwoPointRouteAllowsSinglePointBodyButStillRequiresFullProtection() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            List<ImportedOfficialFeature> features = features(type);
            OfficialRoutingEnvironment environment = router.prepare(features);
            RouteEdge complete = edge(List.of(c(20, 0), connection), 100, features);
            assertValid(complete, features);
            assertThat(terminalAllowed(complete, environment)).isTrue();
            assertThat(ensure(complete, features, environment)).isSameAs(complete);
            RouteEdge shortProtection = edge(List.of(c(8, 0), connection), 100, features);
            assertThat(assessment(points(shortProtection), features.get(1), 100).getFailureCode())
                    .isEqualTo("SPECIAL_CROSSING_EXTENSION_MISSING");
            assertThat(codes(shortProtection, features)).contains("SPECIAL_CROSSING_EXTENSION_MISSING");
            assertThat(terminalAllowed(shortProtection, environment)).isFalse();
        }
    }

    private void assertRetainedAcrossSplit(String type) throws Exception {
        List<ImportedOfficialFeature> features = features(type);
        OfficialRoutingEnvironment environment = router.prepare(features);
        var retainedNormal = environment.normalEgressTowards(100, connection, c(20, 0)).orElseThrow();
        var recoveryNormal = environment.normalEgressTowards(100, connection, root).orElseThrow();
        Coordinate approach = retainedNormal.exit();
        assertThat(approach.x).isGreaterThan(c(4, 0).x).isLessThan(c(6, 0).x);
        assertThat(recoveryNormal.exit().x).isLessThan(connection.x);
        RouteEdge before = edge(List.of(root, c(20, 20), c(20, 0), approach, connection), 100, features);
        List<Coordinate> coordinates = points(before);
        assertValid(before, features);
        assertThat(assessment(coordinates, features.get(1), 100).isAllowed()).isTrue();
        assertThat(terminalAllowed(before, environment)).isTrue();
        assertThat(before.getSections()).filteredOn(section -> "special".equals(section.getKind()))
                .singleElement().satisfies(section -> {
                    assertThat(section.getRestrictionId()).isEqualTo("crossing");
                    assertThat(section.getLengthM()).isEqualByComparingTo("8");
                });
        List<Coordinate> prefix = coordinates.subList(0, coordinates.size() - 1);
        List<Coordinate> terminal = coordinates.subList(coordinates.size() - 2, coordinates.size());
        assertThat(assessment(prefix, features.get(1), 100).getFailureCode())
                .isEqualTo("SPECIAL_CROSSING_EXTENSION_MISSING");
        assertThat(assessment(terminal, features.get(1), 100).getFailureCode())
                .isEqualTo("SPECIAL_CROSSING_EXTENSION_MISSING");
        assertThat(router.lineAllowed(prefix, 100, environment, Set.of(), List.of())).isFalse();
        assertThat(router.terminalApproachAllowed(approach, connection, 100,
                environment, Set.of(), List.of(), retainedNormal)).isFalse();
        RouteEdge after = ensure(before, features, environment);
        assertValid(after, features);
        assertThat(after.getCoordinates())
                .as(type + ": PRESERVE_VALID must retain a fully valid incumbent across a technical split")
                .usingRecursiveComparison().isEqualTo(before.getCoordinates());
        assertThat(after).isSameAs(before);
    }

    private boolean terminalAllowed(RouteEdge edge, OfficialRoutingEnvironment environment) {
        return router.terminalRouteAllowed(points(edge), edge.getDiameter(), environment,
                Set.of(), List.of(), normal(edge, environment));
    }

    private OfficialRouteGeometryRules.NormalEgress normal(RouteEdge edge, OfficialRoutingEnvironment environment) {
        List<Coordinate> points = points(edge);
        return environment.normalEgressTowards(edge.getDiameter(), points.get(points.size() - 1),
                points.get(points.size() - 2)).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private RouteEdge ensure(RouteEdge edge, List<ImportedOfficialFeature> features,
            OfficialRoutingEnvironment environment) throws Exception {
        RegressionRoutePlannerFixture planner = new OfficialDatasetRoutingTest().planner();
        Method method = RegressionRoutePlannerFixture.class.getDeclaredMethod("ensureMandatoryEgress",
                List.class, List.class, List.class, OfficialRoutingEnvironment.class, TerminalApproachPolicy.class);
        method.setAccessible(true);
        try {
            List<RouteEdge> result = (List<RouteEdge>) method.invoke(planner, nodes(edge), List.of(edge),
                    features, environment, TerminalApproachPolicy.PRESERVE_VALID);
            assertThat(result).hasSize(1);
            return result.get(0);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("ensureMandatoryEgress threw", exception.getCause());
        }
    }

    private void assertValid(RouteEdge edge, List<ImportedOfficialFeature> features) {
        assertThat(codes(edge, features)).as("independent full network and geometry validator").isEmpty();
        assertThat(new EngineeringRouteEvaluator().evaluate(List.of(edge)).isCompliant()).isTrue();
        assertThat(rules.line(points(edge)).getLength()).isCloseTo(edge.getLengthM().doubleValue(), offset(0.001));
        assertThat(edge.getSections().stream().map(RouteSection::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(edge.getLengthM());
    }

    private List<String> codes(RouteEdge edge, List<ImportedOfficialFeature> features) {
        return validator.validate(nodes(edge), List.of(edge), features).stream()
                .map(RouteValidationIssue::getCode).collect(Collectors.toList());
    }

    private RoadCrossingClearance.Assessment assessment(List<Coordinate> points,
            ImportedOfficialFeature crossing, int diameter) {
        String type = crossing.getAttributes().path("restriction_type").asText();
        var rule = catalog.find(type).orElseThrow();
        return new RoadCrossingClearance().assess(rules.line(points), crossing.getMetricGeometry(),
                clearance(type, diameter).doubleValue(), rule.getMinimumCrossingAngleDegrees().doubleValue(),
                rule.getSpecialExtensionM().doubleValue());
    }

    private BigDecimal clearance(String type, int diameter) {
        return new OfficialAxisClearance(new OfficialPipeCatalog(), catalog).axisClearanceM(type, diameter, null);
    }

    private RouteEdge edge(List<Coordinate> points, int diameter, List<ImportedOfficialFeature> features) {
        List<RouteCoordinate> coordinates = points.stream().map(point -> new RouteCoordinate(point.x, point.y))
                .collect(Collectors.toList());
        LineString line = rules.line(coordinates.stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList()));
        return new RouteEdge("incumbent", "root", "demand", line.getLength(), coordinates,
                rules.sections(line, rules.baseConstraints(features, diameter)), BigDecimal.ONE, diameter);
    }

    private List<RouteNode> nodes(RouteEdge edge) {
        List<RouteCoordinate> points = edge.getCoordinates();
        return List.of(new RouteNode("root", "existing_chamber_tie_in", points.get(0), true, true, 2, null),
                new RouteNode("demand", "demand_connection", points.get(points.size() - 1), false, false, 0, null));
    }

    private List<ImportedOfficialFeature> features(String type) {
        return List.of(own, rectangle("crossing", type, 4, -10, 6, 10));
    }

    private ImportedOfficialFeature rectangle(String id, String type, double x1, double y1, double x2, double y2) {
        return polygon(id, type, List.of(c(x1, y1), c(x2, y1), c(x2, y2), c(x1, y2), c(x1, y1)));
    }

    private ImportedOfficialFeature asymmetricCrossing(String type, boolean shallowRight) {
        double shift = 6 / Math.tan(Math.toRadians(40));
        return polygon("crossing", type, List.of(c(30, -3), c(70, -3),
                c(shallowRight ? 70 + shift : 70, 3), c(shallowRight ? 30 : 30 + shift, 3), c(30, -3)));
    }

    private ImportedOfficialFeature polygon(String id, String type, List<Coordinate> coordinates) {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", type), factory.createPolygon(coordinates.toArray(new Coordinate[0])));
    }

    private List<Coordinate> points(RouteEdge edge) {
        return edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
    }

    private List<Coordinate> reversed(List<Coordinate> points) {
        List<Coordinate> reversed = new ArrayList<>(points);
        Collections.reverse(reversed);
        return reversed;
    }

    private Coordinate c(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }
}
