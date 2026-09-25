package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialRouteValidatorPreparationTest {
    private static final String BUILDING = "POLYGON ((-5 -5, 5 -5, 5 5, -5 5, -5 -5))";
    // Табличные R + W/2, независимо от реализации подготовки.
    private static final Map<Integer, Double> BUILDING_CLEARANCES_M = Map.of(
            400, 5.0 + 1.370 / 2, 500, 7.0 + 1.670 / 2, 800, 7.0 + 2.250 / 2,
            900, 9.0 + 2.450 / 2, 1400, 9.0 + 3.450 / 2);
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);
    private final OfficialRouteValidator.ValidationSession session = validator.forCalculation();

    @Test
    void customGeometryUsesFullPreparationAndRechecksChangedRoutesWithIndependentIssueLists() throws Exception {
        AtomicInteger buffers = new AtomicInteger();
        List<ImportedOfficialFeature> features = List.of(countedBuilding(buffers));
        RouteEdge valid = edge(50, "LINESTRING (-30 20, 30 20)");
        RouteEdge invalid = edge(50, "LINESTRING (-30 20, -30 0, 30 0, 30 20)");
        List<RouteNode> nodes = nodes(valid, null, false);
        List<RouteEdge> edges = new ArrayList<>(List.of(valid));
        List<RouteValidationIssue> expectedValid = validator.validate(nodes, edges, features);
        List<RouteValidationIssue> expectedInvalid = validator.validate(nodes, List.of(invalid), features);
        assertThat(expectedValid).isEmpty();
        assertThat(expectedInvalid).extracting(RouteValidationIssue::getCode)
                .containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        assertThat(buffers.get()).isEqualTo(2);

        List<RouteValidationIssue> first = session.validate(nodes, edges, features);
        assertIssues(expectedValid, first);
        assertThat(buffers.get()).isEqualTo(3);
        first.add(new RouteValidationIssue("CALLER_MUTATION", "route", "not a validation result"));
        List<RouteValidationIssue> repeated = session.validate(nodes, edges, features);
        assertThat(repeated).isNotSameAs(first);
        assertIssues(expectedValid, repeated);
        // Те же ID, концы и экземпляры списков; меняется только фактическая полилиния.
        edges.set(0, invalid);
        List<RouteValidationIssue> rejected = session.validate(nodes, edges, features);
        assertIssues(expectedInvalid, rejected);
        rejected.clear();
        assertIssues(expectedInvalid, session.validate(nodes, edges, features));
        edges.set(0, valid);
        assertIssues(expectedValid, session.validate(nodes, edges, features));
        // Пользовательский Polygon не получает пространственного skip или удержания буфера.
        assertThat(buffers.get()).isEqualTo(7);

        assertIssues(expectedValid, validator.validate(nodes, edges, features));
        assertIssues(expectedValid, validator.validate(nodes, edges, features));
        assertThat(buffers.get()).isEqualTo(9);

        RouteEdge enlarged = edge(500, "LINESTRING (-30 20, -30 0, 30 0, 30 20)");
        List<RouteValidationIssue> expectedEnlarged = validator.validate(nodes, List.of(enlarged), features);
        assertThat(buffers.get()).isEqualTo(10);
        assertIssues(expectedEnlarged, session.validate(nodes, List.of(enlarged), features));
        assertThat(buffers.get()).isEqualTo(11);
        assertIssues(expectedEnlarged, session.validate(nodes, List.of(enlarged), features));
        assertIssues(expectedInvalid, session.validate(nodes, List.of(invalid), features));
        assertIssues(expectedValid, session.validate(nodes, edges, features));
        assertThat(buffers.get()).isEqualTo(14);
    }

    @Test
    void preservesFinalDiameterBoundariesAcrossRepeatedSessionValidations() throws Exception {
        List<ImportedOfficialFeature> features = List.of(feature("own", "oks", BUILDING));
        for (int diameter : List.of(400, 500, 800, 900, 1400, 400)) {
            double clearance = BUILDING_CLEARANCES_M.get(diameter);
            for (double offset : List.of(-0.001, 0.0, 0.001)) {
                double y = 5 + clearance + offset;
                RouteEdge route = edge(diameter, "LINESTRING (-30 " + y + ", 30 " + y + ")");
                List<RouteValidationIssue> issues = assertEquivalent(nodes(route, null, false), List.of(route), features);
                assertThat(issues.stream().anyMatch(issue -> "FORBIDDEN_CLEARANCE_VIOLATION".equals(issue.getCode())))
                        .as("DU %s, clearance offset %s", diameter, offset).isEqualTo(offset < 0);
            }
        }
        RouteEdge defaultDiameter = edge(null, "LINESTRING (-30 20, 30 20)");
        assertThat(assertEquivalent(nodes(defaultDiameter, null, false), List.of(defaultDiameter), features)).isEmpty();
    }

    @Test
    void reappliesRootExemptionsAndEndpointSetbackRelaxation() throws Exception {
        List<ImportedOfficialFeature> features = List.of(feature("own", "oks", BUILDING));
        RouteEdge crossing = edge(100, "LINESTRING (-30 0, 30 0)");
        assertThat(assertEquivalent(nodes(crossing, "own", false), List.of(crossing), features)).isEmpty();
        assertThat(assertEquivalent(nodes(crossing, null, false), List.of(crossing), features))
                .extracting(RouteValidationIssue::getCode).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        RouteEdge approach = edge(100, "LINESTRING (-8 0, -30 0)");
        assertThat(assertEquivalent(nodes(approach, null, false), List.of(approach), features)).isEmpty();
        RouteEdge alongside = edge(100, "LINESTRING (-8 -30, -8 30)");
        assertThat(assertEquivalent(nodes(alongside, null, false), List.of(alongside), features))
                .extracting(RouteValidationIssue::getCode).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        assertThat(assertEquivalent(nodes(crossing, "own", false), List.of(crossing), features)).isEmpty();
    }

    @Test
    void rechecksMandatoryNormalOwnPrefixAndPostSizingEgress() throws Exception {
        List<ImportedOfficialFeature> features = List.of(feature("own", "oks", BUILDING));
        RouteEdge valid = edge(50, "LINESTRING (-30 0, -10.45 0, 0 0)");
        assertThat(assertEquivalent(nodes(valid, null, true), List.of(valid), features)).isEmpty();
        RouteEdge enlarged = edge(500, "LINESTRING (-30 0, -10.45 0, 0 0)");
        assertThat(assertEquivalent(nodes(enlarged, null, true), List.of(enlarged), features))
                .extracting(RouteValidationIssue::getCode).contains("OKS_NORMAL_EGRESS_VIOLATION");
        RouteEdge diagonal = edge(50, "LINESTRING (-30 10, -12 10, 0 0)");
        assertThat(assertEquivalent(nodes(diagonal, null, true), List.of(diagonal), features))
                .extracting(RouteValidationIssue::getCode).contains("OKS_NORMAL_EGRESS_VIOLATION");
        RouteEdge prefix = edge(50, "LINESTRING (-30 2, 15 2, 15 0, 10.45 0, 0 0)");
        List<RouteValidationIssue> prefixIssues = assertEquivalent(nodes(prefix, null, true), List.of(prefix), features);
        assertThat(prefixIssues).extracting(RouteValidationIssue::getCode).doesNotContain("OKS_NORMAL_EGRESS_VIOLATION");
        assertThat(prefixIssues).extracting(RouteValidationIssue::getMessage)
                .contains("Route violates own oks clearance outside terminal approach at own");
        assertThat(assertEquivalent(nodes(valid, null, true), List.of(valid), features)).isEmpty();
    }

    @Test
    void preservesSourceConnectionOrderAndDetectsChangedDemandCoordinates() throws Exception {
        ImportedOfficialFeature building = feature("own", "oks", BUILDING);
        ImportedOfficialFeature original = connection("POINT (0 0)");
        ImportedOfficialFeature shifted = connection("POINT (1 0)");
        RouteEdge route = edge(50, "LINESTRING (-30 0, -10.45 0, 0 0)");
        List<RouteNode> nodes = nodes(route, null, true);
        assertThat(assertEquivalent(nodes, List.of(route), List.of(building, shifted, original))).isEmpty();
        assertThat(assertEquivalent(nodes, List.of(route), List.of(original, building, shifted)))
                .extracting(RouteValidationIssue::getCode).contains("DEMAND_CONNECTION_COORDINATE_MISMATCH");
        assertThat(assertEquivalent(nodes, List.of(route), List.of(building, original))).isEmpty();
    }

    @Test
    void invalidatesSameIdReplacementsAndInPlaceGeometryAndTypeMutations() throws Exception {
        ImportedOfficialFeature obstacle = feature("same", "park", BUILDING);
        RouteEdge route = edge(50, "LINESTRING (-30 20, 30 20)");
        List<RouteNode> nodes = nodes(route, null, false);
        List<ImportedOfficialFeature> features = new ArrayList<>(List.of(obstacle));
        assertThat(assertEquivalent(nodes, List.of(route), features)).isEmpty();
        shiftY(obstacle.getMetricGeometry(), 20);
        assertThat(assertEquivalent(nodes, List.of(route), features)).extracting(RouteValidationIssue::getCode)
                .containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        features.set(0, feature("same", "park", BUILDING));
        assertThat(assertEquivalent(nodes, List.of(route), features)).isEmpty();
        features.set(0, obstacle);
        assertThat(assertEquivalent(nodes, List.of(route), features)).extracting(RouteValidationIssue::getCode)
                .containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        ((ObjectNode) obstacle.getAttributes()).put("restriction_type", "unknown");
        assertThat(assertEquivalent(nodes, List.of(route), features)).isEmpty();
        ((ObjectNode) obstacle.getAttributes()).put("restriction_type", "water");
        assertThat(assertEquivalent(nodes, List.of(route), features)).extracting(RouteValidationIssue::getMessage)
                .containsExactly("Route violates water clearance at same");
    }

    @Test
    void usesOnlyCurrentFeatureWindowsAndKeepsDuplicateTypedIdsAndIssueOrder() throws Exception {
        ImportedOfficialFeature park = feature("same", "park", BUILDING);
        ImportedOfficialFeature remotePark = feature("same", "park",
                "POLYGON ((-5 1995, 5 1995, 5 2005, -5 2005, -5 1995))");
        ImportedOfficialFeature road = feature("same", "road", "POLYGON ((-1 -50,1 -50,1 50,-1 50,-1 -50))");
        ImportedOfficialFeature acuteRoad = feature("same", "road", "POLYGON ((-20 -6,20 4,20 6,-20 -4,-20 -6))");
        ImportedOfficialFeature water = feature("same", "water", BUILDING);
        List<ImportedOfficialFeature> sourceFeatures = new ArrayList<>(List.of(
                water, acuteRoad, park, remotePark, road, park));
        OfficialRoutingEnvironment environment = new OfficialRoutingEnvironment(
                List.of(), new InMemoryRoutingFeatureSource(sourceFeatures), rules);
        OfficialRouteValidator.ValidationSession windowSession = environment.validationFor(validator);
        RouteEdge near = edge(50, "LINESTRING (-30 0, 30 0)");
        List<ImportedOfficialFeature> nearWindow = window(environment, near);
        List<RouteValidationIssue> issues = assertEquivalent(windowSession, nodes(near, null, false), List.of(near), nearWindow);
        assertThat(issues).extracting(RouteValidationIssue::getMessage).containsExactly(
                "Route violates park clearance at same", "Route violates park clearance at same",
                "Route violates water clearance at same", "Route violates road crossing/clearance at same",
                "Crossing of road is not split into a special section");
        assertThat(issues).extracting(RouteValidationIssue::getCode).containsExactly(
                "FORBIDDEN_CLEARANCE_VIOLATION", "FORBIDDEN_CLEARANCE_VIOLATION", "FORBIDDEN_CLEARANCE_VIOLATION",
                "SPECIAL_CROSSING_ANGLE_VIOLATION", "SPECIAL_CROSSING_SECTION_MISSING");
        RouteEdge far = edge(50, "LINESTRING (-30 2000, 30 2000)");
        assertThat(assertEquivalent(windowSession, nodes(far, null, false), List.of(far), window(environment, far)))
                .extracting(RouteValidationIssue::getCode).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        java.util.Collections.reverse(sourceFeatures);
        assertEquivalent(windowSession, nodes(near, null, false), List.of(near), window(environment, near));
        sourceFeatures.clear();
        assertThat(assertEquivalent(windowSession, nodes(near, null, false), List.of(near), window(environment, near))).isEmpty();
        assertIssues(issues, windowSession.validate(nodes(near, null, false), List.of(near), nearWindow));
    }

    @Test
    void rerunsTopologySelfIntersectionAndFinalGeometryChecksAfterPreparationHits() throws Exception {
        List<ImportedOfficialFeature> features = List.of(feature("own", "oks", BUILDING));
        RouteEdge valid = edge(50, "LINESTRING (-30 20, 30 20)");
        List<RouteNode> nodes = nodes(valid, null, false);
        assertThat(assertEquivalent(nodes, List.of(valid), features)).isEmpty();
        RouteEdge wrongLength = new RouteEdge("route", "root", "end", 61,
                valid.getCoordinates(), List.of(), null, 50);
        assertThat(assertEquivalent(nodes, List.of(wrongLength), features)).extracting(RouteValidationIssue::getCode)
                .containsExactly("EDGE_GEOMETRY_LENGTH_MISMATCH");
        RouteEdge wrongEndpoint = edge(50, "LINESTRING (-31 20, 30 20)");
        assertThat(assertEquivalent(nodes, List.of(wrongEndpoint), features)).extracting(RouteValidationIssue::getCode)
                .containsExactly("EDGE_GEOMETRY_ENDPOINT_MISMATCH");
        RouteEdge selfCrossing = edge(50, "LINESTRING (-30 20, 30 60, -30 60, 30 20)");
        assertThat(assertEquivalent(nodes, List.of(selfCrossing), features)).extracting(RouteValidationIssue::getCode)
                .contains("SELF_INTERSECTION", "ROUTE_DEFLECTION_EXCEEDED");
        assertThat(assertEquivalent(List.of(nodes.get(0), nodes.get(1), nodes.get(1)), List.of(valid, valid), features))
                .extracting(RouteValidationIssue::getCode)
                .contains("DUPLICATE_NODE_ID", "DUPLICATE_EDGE_ID", "MULTIPLE_UPSTREAM_EDGES", "CROSSING_OUTSIDE_COMMON_NODE");
        assertThat(assertEquivalent(List.of(nodes.get(1)), List.of(valid), features))
                .extracting(RouteValidationIssue::getCode).contains("UNKNOWN_EDGE_NODE", "UPSTREAM_PATH_INCOMPLETE");
        assertThat(assertEquivalent(nodes, List.of(valid), features)).isEmpty();
    }

    @Test
    void isolatesSessionsByCalculationAndExactValidatorIdentity() throws Exception {
        AtomicInteger buffers = new AtomicInteger();
        List<ImportedOfficialFeature> features = List.of(countedBuilding(buffers));
        RouteEdge route = edge(50, "LINESTRING (-30 20, 30 20)");
        List<RouteNode> nodes = nodes(route, null, false);
        OfficialRouteValidator firstValidator = new OfficialRouteValidator(rules);
        OfficialRouteValidator secondValidator = new OfficialRouteValidator(rules);
        OfficialRouteGeometryRules environmentRules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()) {
            @Override List<Constraint> baseConstraints(List<ImportedOfficialFeature> input, int diameter) {
                throw new AssertionError("Validation must use its own validator's rules");
            }
        };
        OfficialRoutingEnvironment environment = new OfficialRoutingEnvironment(List.of(), environmentRules);
        OfficialRouteValidator.ValidationSession first = environment.validationFor(firstValidator);
        OfficialRouteValidator.ValidationSession second = environment.validationFor(secondValidator);
        assertThat(first).isSameAs(environment.validationFor(firstValidator)).isNotSameAs(second);
        assertThat(first.validate(nodes, List.of(route), features)).isEmpty();
        assertThat(first.validate(nodes, List.of(route), features)).isEmpty();
        assertThat(buffers.get()).isEqualTo(2);
        assertThat(second.validate(nodes, List.of(route), features)).isEmpty();
        assertThat(buffers.get()).isEqualTo(3);
        OfficialRouteValidator.ValidationSession next = new OfficialRoutingEnvironment(List.of(), rules)
                .validationFor(firstValidator);
        assertThat(next).isNotSameAs(first);
        assertThat(next.validate(nodes, List.of(route), features)).isEmpty();
        assertThat(buffers.get()).isEqualTo(4);
        assertThat(firstValidator.forCalculation().validate(nodes, List.of(route), features)).isEmpty();
        assertThat(buffers.get()).isEqualTo(5);
        assertThat(first.validate(nodes, List.of(route), features)).isEmpty();
        assertThat(buffers.get()).isEqualTo(6);
        // Равные equals/hashCode не делают валидаторы одним владельцем сессии.
        OfficialRouteValidator equalFirst = new EqualValidator(rules);
        OfficialRouteValidator equalSecond = new EqualValidator(rules);
        assertThat(environment.validationFor(equalFirst)).isSameAs(environment.validationFor(equalFirst))
                .isNotSameAs(environment.validationFor(equalSecond));
        OfficialRouteValidator legacy = new OfficialRouteValidator();
        RouteEdge crossing = edge(50, "LINESTRING (-30 0, 30 0)");
        assertThat(environment.validationFor(legacy).validate(nodes(crossing, null, false), List.of(crossing), features))
                .isEmpty();
        assertThat(first.validate(nodes(crossing, null, false), List.of(crossing), features))
                .extracting(RouteValidationIssue::getCode).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        assertThat(buffers.get()).isEqualTo(7);
    }

    @Test
    void preservesPublicSubclassValidationHookOnEveryCall() throws Exception {
        AtomicInteger buffers = new AtomicInteger();
        AtomicInteger validations = new AtomicInteger();
        ImportedOfficialFeature finalObstacle = countedBuilding(buffers);
        OfficialRouteValidator stricter = new OfficialRouteValidator(rules) {
            @Override
            public List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges,
                    List<ImportedOfficialFeature> features) {
                validations.incrementAndGet();
                List<ImportedOfficialFeature> complete = new ArrayList<>(features);
                complete.add(finalObstacle);
                return super.validate(nodes, edges, complete);
            }
        };
        OfficialRouteValidator.ValidationSession stricterSession = new OfficialRoutingEnvironment(List.of(), rules)
                .validationFor(stricter);
        RouteEdge invalid = edge(50, "LINESTRING (-30 0, 30 0)");
        List<RouteNode> nodes = nodes(invalid, null, false);
        assertThat(validator.validate(nodes, List.of(invalid), List.of())).isEmpty();
        List<RouteValidationIssue> expected = stricter.validate(nodes, List.of(invalid), List.of());
        assertThat(expected).extracting(RouteValidationIssue::getCode).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        assertIssues(expected, stricterSession.validate(nodes, List.of(invalid), List.of()));
        assertIssues(expected, stricterSession.validate(nodes, List.of(invalid), List.of()));
        RouteEdge valid = edge(50, "LINESTRING (-30 20, 30 20)");
        assertThat(stricterSession.validate(nodes(valid, null, false), List.of(valid), List.of())).isEmpty();
        assertIssues(expected, stricterSession.validate(nodes, List.of(invalid), List.of()));
        assertThat(validations.get()).isEqualTo(5);
        assertThat(buffers.get()).isEqualTo(5);
    }

    @Test
    void preservesExceptionsAndNullRulesLegacyPath() throws Exception {
        // park проверяет недопустимый ДУ именно при подготовке, без ранней проверки своего ОКС.
        List<ImportedOfficialFeature> features = List.of(feature("obstacle", "park", BUILDING));
        RouteEdge valid = edge(50, "LINESTRING (-30 20, 30 20)");
        List<RouteNode> nodes = nodes(valid, null, false);
        assertThat(assertEquivalent(nodes, List.of(valid), features)).isEmpty();
        RouteEdge invalidDiameter = edge(101, "LINESTRING (-30 20, 30 20)");
        Throwable expected = catchThrowable(() -> validator.validate(nodes, List.of(invalidDiameter), features));
        assertThat(expected).isInstanceOf(IllegalArgumentException.class);
        for (int repeat = 0; repeat < 2; repeat++) {
            assertThat(catchThrowable(() -> session.validate(nodes, List.of(invalidDiameter), features)))
                    .isExactlyInstanceOf(expected.getClass()).hasMessage(expected.getMessage());
        }
        List<ImportedOfficialFeature> ignored = List.of(feature("empty", "oks", "POLYGON EMPTY"),
                new ImportedOfficialFeature("null", "oks_existing", null, null),
                feature("unknown", "unknown", "POINT (0 0)"));
        assertThat(assertEquivalent(nodes, List.of(invalidDiameter), ignored)).isEmpty();
        assertThat(assertEquivalent(nodes, List.of(valid), features)).isEmpty();
        for (OfficialRouteValidator legacy : List.of(new OfficialRouteValidator(), new OfficialRouteValidator(null))) {
            OfficialRouteValidator.ValidationSession legacySession = legacy.forCalculation();
            List<RouteNode> duplicates = List.of(nodes.get(0), nodes.get(1), nodes.get(1));
            List<RouteValidationIssue> legacyIssues = legacy.validate(duplicates, List.of(invalidDiameter), null);
            assertThat(legacyIssues).extracting(RouteValidationIssue::getCode).containsExactly("DUPLICATE_NODE_ID");
            assertIssues(legacyIssues, legacySession.validate(duplicates, List.of(invalidDiameter), null));
            assertIssues(legacyIssues, legacySession.validate(duplicates, List.of(invalidDiameter), features));
        }
    }

    @Test
    void unsupportedAttributesDoNotMoveExceptionsBeforeTheOriginalValidationStage() throws Exception {
        List<ImportedOfficialFeature> malformed = List.of(new ImportedOfficialFeature(
                "malformed", "restriction", null, new WKTReader().read(BUILDING)));
        assertThat(validator.validate(List.of(), List.of(), malformed)).isEmpty();
        assertThat(session.validate(List.of(), List.of(), malformed)).isEmpty();
        RouteEdge route = edge(50, "LINESTRING (-30 20, 30 20)");
        List<RouteNode> nodes = nodes(route, null, false);
        Throwable expected = catchThrowable(() -> validator.validate(nodes, List.of(route), malformed));
        assertThat(expected).isNotNull();
        assertThat(catchThrowable(() -> session.validate(nodes, List.of(route), malformed)))
                .isExactlyInstanceOf(expected.getClass()).hasMessage(expected.getMessage());
    }

    private List<RouteValidationIssue> assertEquivalent(
            List<RouteNode> nodes, List<RouteEdge> edges, List<ImportedOfficialFeature> features) {
        return assertEquivalent(session, nodes, edges, features);
    }

    private List<RouteValidationIssue> assertEquivalent(OfficialRouteValidator.ValidationSession calculation,
            List<RouteNode> nodes, List<RouteEdge> edges, List<ImportedOfficialFeature> features) {
        List<RouteValidationIssue> expected = validator.validate(nodes, edges, features);
        List<RouteValidationIssue> actual = calculation.validate(nodes, edges, features);
        assertIssues(expected, actual);
        List<RouteValidationIssue> repeated = calculation.validate(nodes, edges, features);
        assertThat(repeated).isNotSameAs(actual);
        assertIssues(expected, repeated);
        return actual;
    }

    private void assertIssues(List<RouteValidationIssue> expected, List<RouteValidationIssue> actual) {
        assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction", mapper.createObjectNode().put("restriction_type", type),
                new WKTReader().read(wkt));
    }

    private ImportedOfficialFeature connection(String wkt) throws Exception {
        return new ImportedOfficialFeature("connection", "oks_connection_point", mapper.createObjectNode(),
                new WKTReader().read(wkt));
    }

    private ImportedOfficialFeature countedBuilding(AtomicInteger buffers) throws Exception {
        return new ImportedOfficialFeature("own", "oks_existing", mapper.createObjectNode(),
                new CountingPolygon((Polygon) new WKTReader().read(BUILDING), buffers));
    }

    private RouteEdge edge(Integer diameter, String wkt) throws Exception {
        LineString line = (LineString) new WKTReader().read(wkt);
        List<RouteCoordinate> coordinates = new ArrayList<>();
        for (Coordinate coordinate : line.getCoordinates()) {
            coordinates.add(new RouteCoordinate(coordinate.x, coordinate.y));
        }
        return new RouteEdge("route", "root", "end", line.getLength(), coordinates, List.of(), null, diameter);
    }

    private List<RouteNode> nodes(RouteEdge edge, String rootTarget, boolean demand) {
        List<RouteCoordinate> coordinates = edge.getCoordinates();
        return List.of(new RouteNode("root", "existing_chamber", coordinates.get(0), true, true, 2, rootTarget),
                new RouteNode("end", demand ? "demand_connection" : "technical_node",
                        coordinates.get(coordinates.size() - 1), false, false, 0, demand ? "connection" : null));
    }

    private List<ImportedOfficialFeature> window(OfficialRoutingEnvironment environment, RouteEdge edge) {
        List<RouteCoordinate> coordinates = edge.getCoordinates();
        return environment.featuresInWindow(coordinates.get(0).toCoordinate(),
                coordinates.get(coordinates.size() - 1).toCoordinate());
    }

    private void shiftY(Geometry geometry, double offset) {
        geometry.apply(new CoordinateSequenceFilter() {
            public void filter(CoordinateSequence sequence, int index) {
                sequence.setOrdinate(index, 1, sequence.getY(index) + offset);
            }
            public boolean isDone() { return false; }
            public boolean isGeometryChanged() { return true; }
        });
    }

    private static final class EqualValidator extends OfficialRouteValidator {
        private EqualValidator(OfficialRouteGeometryRules rules) { super(rules); }
        @Override public boolean equals(Object other) { return other instanceof EqualValidator; }
        @Override public int hashCode() { return 1; }
    }

    /** Считает настоящие JTS buffer и сохраняет счётчик при копировании в подготовку. */
    private static final class CountingPolygon extends Polygon {
        private final AtomicInteger buffers;
        private CountingPolygon(Polygon polygon, AtomicInteger buffers) {
            super(polygon.getExteriorRing(), holes(polygon), polygon.getFactory());
            this.buffers = buffers;
        }
        @Override protected Polygon copyInternal() {
            return new CountingPolygon(super.copyInternal(), buffers);
        }
        @Override public Geometry buffer(double distance, int quadrantSegments) {
            buffers.incrementAndGet();
            return super.buffer(distance, quadrantSegments);
        }
        private static LinearRing[] holes(Polygon polygon) {
            LinearRing[] holes = new LinearRing[polygon.getNumInteriorRing()];
            for (int index = 0; index < holes.length; index++) holes[index] = polygon.getInteriorRingN(index);
            return holes;
        }
    }
}
