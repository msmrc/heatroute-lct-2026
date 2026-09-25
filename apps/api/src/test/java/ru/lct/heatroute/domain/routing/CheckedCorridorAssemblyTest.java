package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Эквивалентность быстрой сборки секций; проверка запретных отступов остаётся отдельным этапом. */
class CheckedCorridorAssemblyTest {
    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FORBIDDEN = List.of("park", "social_area", "prohibited_site", "water", "railway", "oks");
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @ParameterizedTest
    @ValueSource(ints = {50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000})
    void preservesAcceptedCrossingsThroughDiameter1000InMixedCoreAndWindowedBackground(int diameter) {
        List<ImportedOfficialFeature> features = new ArrayList<>(background());
        features.add(feature("road", "road", rectangle(20, -50, 26, 50)));
        features.add(feature("tram", "tram_tracks", rectangle(50, -50, 56, 50)));
        features.add(feature("gas", "gas_pipeline", line(c(80, -50), c(80, 50))));
        features.add(feature("power", "power_cable", line(c(100, -50), c(100, 50))));
        features.add(network("heat", line(c(120, -50), c(120, 50))));
        RoutePath path = equivalent(rules, diameter, features, c(0, 0), null, points(0, 0, 150, 0));
        assertThat(path).isNotNull();
        assertThat(path.lengthM()).isEqualTo(150);
        assertThat(path.sections()).filteredOn(s -> "special".equals(s.getKind()))
                .extracting(RouteSection::getRestrictionType)
                .containsExactly("road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network");
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void assemblyUsesTheActualDiameterAfterANarrowerCorridorWasPrepared(String type) {
        List<ImportedOfficialFeature> features = List.of(feature("parallel", type, rectangle(10, 0, 90, 6)));
        List<Coordinate> coordinates = points(0, -2, 100, -2);
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OfficialRoutingEnvironment environment = router.prepare(features);
        Envelope bounds = new Envelope(c(0, -2), c(100, -2));
        assertThat(router.prepareCorridor(50, environment, bounds, c(0, -2), null)
                .completeCheckedAssembly(coordinates)).isNotNull();

        // Ось в 2 м от дороги: ДУ50 требует 1,7 м, ДУ1400 — 3,225 м.
        assertThat(router.completeCheckedCorridorAssembly(50, environment, bounds, c(0, -2), null, coordinates))
                .isNotNull();
        assertThat(router.completeCheckedCorridorAssembly(1400, environment, bounds, c(0, -2), null, coordinates))
                .isNull();
        assertThat(equivalent(rules, 50, features, c(0, -2), null, coordinates)).isNotNull();
        assertThat(equivalent(rules, 1400, features, c(0, -2), null, coordinates)).isNull();
    }

    @ParameterizedTest(name = "{0}, DU{1}")
    @MethodSource("wideCrossings")
    void preservesReferenceRejectionWhenAxisClearanceExceedsTheProtectiveExtension(String type, int diameter) {
        // Это открытая граница прежнего алгоритма, а не утверждение о нормативном запрете:
        // фиксированная защита 3 м короче осевого отступа ДУ1200/1400 (3,05/3,225 м).
        assertThat(equivalent(rules, diameter, List.of(feature("crossing", type, rectangle(40, -30, 46, 30))),
                c(0, 0), null, points(0, 0, 100, 0))).isNull();
    }

    static Stream<Arguments> wideCrossings() {
        return Stream.of("road", "tram_tracks").flatMap(type -> Stream.of(
                Arguments.of(type, 1200), Arguments.of(type, 1400)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network"})
    void eachNonForbiddenTypeRetainsItsActualSpecialInterval(String type) {
        Geometry source = type.equals("road") || type.equals("tram_tracks")
                ? rectangle(40, -30, 46, 30) : line(c(43, -30), c(43, 30));
        RoutePath path = equivalent(rules, 100, List.of(feature("special", type, source)),
                c(0, 0), null, points(0, 0, 100, 0));
        assertThat(path).isNotNull();
        assertThat(path.sections()).filteredOn(s -> "special".equals(s.getKind())).singleElement()
                .satisfies(section -> {
                    assertThat(section.getRestrictionType()).isEqualTo(type);
                    assertThat(section.getRestrictionId()).isEqualTo("special");
                    assertThat(section.getLengthM()).isEqualByComparingTo(
                            type.equals("road") || type.equals("tram_tracks") ? "12" : "4");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"park", "social_area", "prohibited_site", "water", "railway", "oks"})
    void assemblyDoesNotPretendToReplaceIndependentForbiddenClearanceValidation(String type) {
        List<ImportedOfficialFeature> features = List.of(feature("forbidden", type, rectangle(40, -5, 60, 5)));
        RoutePath path = equivalent(rules, 100, features, c(0, 0), null, points(0, 0, 100, 0));
        // Обе сборки доверяют уже проверенным звеньям; намеренно неверный вход обнаруживается ниже.
        assertThat(path).isNotNull();
        assertThat(path.sections()).extracting(RouteSection::getKind).containsExactly("base");
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OfficialRoutingEnvironment environment = router.prepare(features);
        Envelope bounds = new Envelope(c(0, 0), c(100, 0));
        assertThat(router.completeCheckedCorridorAssembly(100, environment, bounds,
                c(0, 0), null, points(0, 0, 100, 0))).isNotNull();
        // Частичная подготовка сборки не должна загрязнять полный набор проверок того же окружения.
        assertThat(router.prepareCorridor(100, environment, bounds, c(0, 0), null)
                .edgeAllowed(c(0, 0), c(100, 0))).isFalse();
        RouteNode root = new RouteNode("root", "existing_chamber_tie_in", rc(c(0, 0)), true, true, 2, null, 100);
        RouteNode end = new RouteNode("end", "new_branch_chamber", rc(c(100, 0)), true, false, 0, null);
        RouteEdge edge = new RouteEdge("edge", "root", "end", path.lengthM(),
                path.coordinates().stream().map(CheckedCorridorAssemblyTest::rc).collect(Collectors.toList()),
                path.sections(), BigDecimal.ONE, 100);
        assertThat(new OfficialRouteValidator(rules).validate(List.of(root, end), List.of(edge), features))
                .extracting(RouteValidationIssue::getCode).contains("FORBIDDEN_CLEARANCE_VIOLATION");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("roadCases")
    void preservesWholeRoadAndTramAcceptanceInPhysicalCoordinateOrder(
            String type, String label, List<Coordinate> coordinates, boolean accepted) {
        List<ImportedOfficialFeature> features = new ArrayList<>(background());
        features.add(feature("crossing", type, rectangle(0, 0, 100, 6)));
        RoutePath path = equivalent(rules, 100, features, coordinates.get(0), null, coordinates);
        assertThat(path != null).as(label).isEqualTo(accepted);
    }

    static Stream<Arguments> roadCases() {
        return Stream.of("road", "tram_tracks").flatMap(type -> Stream.of(
                Arguments.of(type, "complete", points(50, -10, 50, 16), true),
                Arguments.of(type, "partial entry", points(50, -2, 50, 16), false),
                Arguments.of(type, "partial exit", points(50, -10, 50, 8), false),
                Arguments.of(type, "interior endpoint", points(50, 2, 50, 16), false),
                Arguments.of(type, "turn inside protection", points(50, -10, 50, 6.25, 80, 6.25), false),
                Arguments.of(type, "turn at exact protection boundary", points(50, -10, 50, 9, 80, 9), true),
                Arguments.of(type, "collinear subdivisions", points(50, -10, 50, -2, 50, 0, 50, 3, 50, 6, 50, 8, 50, 16), true),
                Arguments.of(type, "parallel inside clearance", points(10, -1.7, 90, -1.7), false)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void reversingAsymmetricCrossingChangesThePhysicalEntryNotTheComparison(String type) {
        Geometry trapezoid = FACTORY.createPolygon(new Coordinate[] {c(0, 0), c(100, 0),
                c(100 + 6 / Math.tan(Math.toRadians(40)), 6), c(0, 6), c(0, 0)});
        List<ImportedOfficialFeature> features = List.of(feature("crossing", type, trapezoid));
        assertThat(equivalent(rules, 100, features, c(-10, 3), null, points(-10, 3, 120, 3))).isNotNull();
        assertThat(equivalent(rules, 100, features, c(120, 3), null, points(120, 3, -10, 3))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void multipartCrossingsKeepSeparateProtectiveIntervals(String type) {
        Geometry source = FACTORY.createMultiPolygon(new Polygon[] {
                rectangle(0, 0, 100, 6), rectangle(0, 20, 100, 26)});
        RoutePath path = equivalent(rules, 100, List.of(feature("multipart", type, source)),
                c(50, -10), null, points(50, -10, 50, 36));
        assertThat(path).isNotNull();
        assertThat(path.sections()).extracting(RouteSection::getKind)
                .containsExactly("base", "special", "base", "special", "base");
        assertThat(path.sections().get(2).getLengthM()).isEqualByComparingTo("8");
    }

    @Test
    void overlappingSpecialsAreSplitAtEveryBoundary() {
        List<ImportedOfficialFeature> features = List.of(
                feature("road", "road", rectangle(40, -30, 60, 30)),
                feature("cable", "power_cable", line(c(59, -30), c(59, 30))),
                feature("gas", "gas_pipeline", line(c(59, -30), c(59, 30))));
        RoutePath path = equivalent(rules, 100, features, c(0, 0), null, points(0, 0, 100, 0));
        assertThat(path).isNotNull();
        assertThat(path.sections()).extracting(RouteSection::getRestrictionType)
                .containsExactly(null, "road", "road+gas_pipeline+power_cable", "road", null);
        assertThat(path.sections().get(2).getLengthM()).isEqualByComparingTo("4");
    }

    @Test
    void selectedHeatNetworkOnlyExemptsLocalRootAndNotRemoteRecrossingOrDuplicateRoadId() {
        Geometry heat = FACTORY.createMultiLineString(new LineString[] {
                line(c(0, -20), c(0, 20)), line(c(40, -20), c(40, 20))});
        List<ImportedOfficialFeature> features = List.of(network("target", heat),
                feature("target", "road", rectangle(60, -20, 66, 20)));
        RoutePath path = equivalent(rules, 100, features, c(0, 0), "target", points(0, 0, 100, 0));
        assertThat(path).isNotNull();
        assertThat(path.sections()).filteredOn(s -> "heat_network".equals(s.getRestrictionType()))
                .singleElement().satisfies(s -> {
                    assertThat(s.getLengthM()).isEqualByComparingTo("4");
                    assertThat(s.getCoordinates().get(0).toCoordinate()).isEqualTo(c(38, 0));
                });
        assertThat(path.sections()).anyMatch(s -> "road".equals(s.getRestrictionType()));
        List<Coordinate> reverse = new ArrayList<>(points(0, 0, 100, 0));
        Collections.reverse(reverse);
        assertThat(equivalent(rules, 100, features, c(0, 0), "target", reverse)).isNotNull();
    }

    @Test
    void roundedRootStillReceivesOnlyItsLocalHeatNetworkExemption() {
        List<ImportedOfficialFeature> features = List.of(network("target", line(c(0, -20), c(0, 20))));
        RoutePath path = equivalent(rules, 100, features, c(0.00049, 0.00049), "target",
                points(0.00049, 0.00049, 100.00049, 0.00049));
        assertThat(path).isNotNull();
        assertThat(path.coordinates()).containsExactly(c(0, 0), c(100, 0));
        assertThat(path.sections()).extracting(RouteSection::getKind).containsExactly("base");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAssemblies")
    void rejectsInvalidAssemblyShapesLikeTheReference(String label, Coordinate root, List<Coordinate> coordinates) {
        assertThat(equivalent(rules, 100, List.of(), root, null, coordinates)).as(label).isNull();
    }

    static Stream<Arguments> invalidAssemblies() {
        return Stream.of(
                Arguments.of("empty", c(0, 0), List.<Coordinate>of()),
                Arguments.of("single point", c(0, 0), points(0, 0)),
                Arguments.of("intermediate root", c(10, 0), points(0, 0, 10, 0, 20, 0)),
                Arguments.of("rounded intermediate root", c(10.00049, 0.00049), points(0, 0, 10, 0, 20, 0)),
                Arguments.of("simple closed ring", c(-1, -1), points(0, 0, 10, 0, 10, 10, 0, 10, 0, 0)),
                Arguments.of("self intersection", c(-1, -1), points(0, 0, 10, 10, 0, 10, 10, 0)));
    }

    @Test
    void nonFiniteCoordinateFailsWithTheSameContract() {
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        List<Coordinate> coordinates = List.of(c(0, 0), c(Double.NaN, 1));
        OfficialRoutingEnvironment environment = router.prepare(List.of());
        Envelope bounds = new Envelope(0, 100, 0, 100);
        assertThatThrownBy(() -> router.prepareCorridor(100, environment, bounds, c(0, 0), null)
                .completeCheckedAssembly(coordinates)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Corridor XY coordinates must be finite");
        assertThatThrownBy(() -> router.completeCheckedCorridorAssembly(100, environment, bounds, c(0, 0), null, coordinates))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Corridor XY coordinates must be finite");
    }

    @Test
    void fullPolylineBoundsIncludeDistantCrossingAndBothCoreAndWindowedFeatures() {
        List<Coordinate> coordinates = points(0, 0, 0, 1500, 100, 1500, 100, 0);
        ImportedOfficialFeature road = feature("far-road", "road", rectangle(40, 1470, 46, 1530));
        ImportedOfficialFeature heat = network("far-heat", line(c(70, 1470), c(70, 1530)));
        Envelope endpoints = new Envelope(coordinates.get(0), coordinates.get(3));
        endpoints.expandBy(610);
        assertThat(endpoints.intersects(road.getMetricGeometry().getEnvelopeInternal())).isFalse();
        RoutePath path = equivalent(rules, 100, List.of(road, heat), c(0, 0), null, coordinates);
        assertThat(path).isNotNull();
        assertThat(path.sections()).filteredOn(s -> "special".equals(s.getKind()))
                .extracting(RouteSection::getRestrictionId).containsExactly("far-road", "far-heat");
    }

    @Test
    void fullPolylineWindowAlsoFindsRemoteInvalidRoadTurn() {
        List<Coordinate> coordinates = points(0, 0, 0, 1500, 42, 1500, 42, 1550, 100, 1550, 100, 0);
        assertThat(equivalent(rules, 100, List.of(feature("far-road", "road", rectangle(40, 1470, 46, 1530))),
                c(0, 0), null, coordinates)).isNull();
    }

    @Test
    void ignoresUnknownNonSpatialAndEmptyGeometryFeaturesLikeTheReference() {
        List<ImportedOfficialFeature> features = List.of(
                feature("unknown", "unrecognized", rectangle(40, -10, 60, 10)),
                feature("empty", "road", FACTORY.createPolygon()),
                new ImportedOfficialFeature("demand", "oks_connection_point", JSON.createObjectNode(), FACTORY.createPoint(c(100, 0))));
        RoutePath path = equivalent(rules, 100, features, c(0, 0), null, points(0, 0, 100, 0));
        assertThat(path).isNotNull();
        assertThat(path.sections()).extracting(RouteSection::getKind).containsExactly("base");
    }

    @Test
    void customRulesKeepTheirFullConstraintDependentSectionSemantics() {
        OfficialRouteGeometryRules custom = new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry()) {
            @Override List<RouteSection> sections(LineString route, List<Constraint> constraints) {
                if (constraints.stream().anyMatch(c -> c.rule().isForbidden())) {
                    return List.of(new RouteSection("special", "custom-survey", "survey",
                            Stream.of(route.getCoordinates()).map(CheckedCorridorAssemblyTest::rc).collect(Collectors.toList()),
                            route.getLength(), 90.0));
                }
                return super.sections(route, constraints);
            }
        };
        assertThat(custom.hasStandardPreparationRules()).isFalse();
        RoutePath path = equivalent(custom, 100, background(), c(0, 0), null, points(0, 0, 100, 0));
        assertThat(path.sections()).extracting(RouteSection::getRestrictionType).containsExactly("custom-survey");
    }

    @Test
    void customCatalogKeepsChangedProtectiveExtensionAndItsRejections() {
        OfficialConstraintCatalog catalog = new OfficialConstraintCatalog() {
            @Override public Optional<SpatialConstraintRule> find(String type) {
                return "road".equals(type) ? Optional.of(new SpatialConstraintRule(
                        "road", false, "1.5", null, "45", "5.0", "1.0", "1.60")) : super.find(type);
            }
        };
        OfficialRouteGeometryRules custom = new OfficialRouteGeometryRules(catalog, new OfficialCrossingGeometry());
        assertThat(custom.hasStandardPreparationRules()).isFalse();
        List<ImportedOfficialFeature> features = List.of(feature("road", "road", rectangle(0, 0, 100, 6)));
        RoutePath path = equivalent(custom, 100, features, c(50, -10), null, points(50, -10, 50, 16));
        assertThat(path).isNotNull();
        assertThat(path.sections()).filteredOn(s -> "special".equals(s.getKind())).singleElement()
                .satisfies(s -> assertThat(s.getLengthM()).isEqualByComparingTo("16"));
        assertThat(equivalent(custom, 100, features, c(50, -10), null, points(50, -10, 50, 9, 80, 9))).isNull();
        assertThat(equivalent(rules, 100, features, c(50, -10), null, points(50, -10, 50, 9, 80, 9))).isNotNull();
    }

    private static RoutePath equivalent(OfficialRouteGeometryRules rules, int diameter,
            List<ImportedOfficialFeature> features, Coordinate root, String targetId, List<Coordinate> coordinates) {
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        Envelope bounds = new Envelope(root);
        coordinates.forEach(bounds::expandToInclude);
        // Разные окружения исключают влияние подготовленного reference-кэша на проверяемый путь.
        RoutePath reference = router.prepareCorridor(diameter, router.prepare(features), bounds, root, targetId)
                .completeCheckedAssembly(coordinates);
        RoutePath actual = router.completeCheckedCorridorAssembly(
                diameter, router.prepare(features), bounds, root, targetId, coordinates);
        if (reference == null) assertThat(actual).isNull();
        else {
            assertThat(actual).isNotNull();
            assertThat(actual.coordinates()).usingRecursiveComparison()
                    .withComparatorForType(Double::compare, Double.class).isEqualTo(reference.coordinates());
            assertThat(actual.lengthM()).isEqualTo(reference.lengthM());
            assertThat(actual.sections()).usingRecursiveComparison().isEqualTo(reference.sections());
            // JTS хранит отсутствующую Z как NaN: точное Double.compare сравнивает и её, и все XY.
            assertThat(actual).usingRecursiveComparison().withComparatorForType(Double::compare, Double.class)
                    .isEqualTo(reference);
        }
        return actual;
    }

    private static List<ImportedOfficialFeature> background() {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        for (int i = 0; i < FORBIDDEN.size(); i++) {
            features.add(feature("background-" + i, FORBIDDEN.get(i), rectangle(i * 20, 100, i * 20 + 10, 110)));
        }
        features.add(new ImportedOfficialFeature("existing", "oks_existing", JSON.createObjectNode(), rectangle(0, 140, 10, 150)));
        return features;
    }

    private static ImportedOfficialFeature feature(String id, String type, Geometry geometry) {
        return new ImportedOfficialFeature(id, "restriction", JSON.createObjectNode().put("restriction_type", type), geometry);
    }
    private static ImportedOfficialFeature network(String id, Geometry geometry) {
        return new ImportedOfficialFeature(id, "heat_network", JSON.createObjectNode(), geometry);
    }
    private static Polygon rectangle(double x1, double y1, double x2, double y2) {
        return FACTORY.createPolygon(new Coordinate[] {c(x1, y1), c(x2, y1), c(x2, y2), c(x1, y2), c(x1, y1)});
    }
    private static LineString line(Coordinate... points) { return FACTORY.createLineString(points); }
    private static List<Coordinate> points(double... xy) {
        List<Coordinate> result = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) result.add(c(xy[i], xy[i + 1]));
        return result;
    }
    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
    private static RouteCoordinate rc(Coordinate point) { return new RouteCoordinate(point.x, point.y); }
}
