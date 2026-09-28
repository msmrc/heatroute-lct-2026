package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Границы локального кандидата; независимый валидатор сети остаётся обязанностью planner. */
class RetainedEndpointSimplifierTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final RetainedEndpointSimplifier simplifier = new RetainedEndpointSimplifier();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void preservesBothCompleteTerminalSegmentsWithoutMutatingOriginal() {
        RouteEdge original = edge(dogleg(), List.of(), 50);
        List<Coordinate> before = coordinates(original);
        List<RouteSection> sections = new ArrayList<>(original.getSections());

        RoutePath shortened = simplify(original, List.of(), List.of());

        assertThat(shortened).isNotNull();
        assertThat(shortened.lengthM()).isCloseTo(81, within(0.001));
        assertThat(shortened.coordinates()).containsExactly(c(-80, 10), c(-60, 10), c(-40, 10), c(1, 10));
        assertTerminalSegments(original, shortened);
        assertSectionsMatch(shortened);
        assertThat(coordinates(original)).containsExactlyElementsOf(before);
        assertThat(original.getSections()).containsExactlyElementsOf(sections);
        assertThat(original.getLengthM()).isEqualByComparingTo("141.000");
    }

    @Test
    void reversalRotationAndLargeUtmTranslationRetainExactStoredEndpointSegments() {
        for (double degrees : new double[] {0, 37, 90, 143}) {
            for (double offset : new double[] {0, 1}) {
                for (boolean reverse : List.of(false, true)) {
                    List<Coordinate> points = transform(dogleg(), degrees, offset);
                    if (reverse) Collections.reverse(points);
                    RouteEdge original = edge(points, List.of(), 50);
                    RoutePath shortened = simplify(original, List.of(), List.of());

                    assertThat(shortened).as("rotation=%s UTM=%s reverse=%s", degrees, offset, reverse).isNotNull();
                    assertThat(shortened.lengthM()).isCloseTo(81, within(0.003));
                    assertTerminalSegments(original, shortened);
                    assertSectionsMatch(shortened);
                    assertThat(OfficialRouteDeflectionRules.validatePolyline("shortened", model(shortened.coordinates())).getIssues())
                            .isEmpty();
                }
            }
        }
    }

    @Test
    void preservesWholeOwnOksApproachesAtBothEndsIncludingExtraClearanceVertices() {
        List<ImportedOfficialFeature> features = List.of(
                rectangle("left-oks", "oks_existing", -100, 0, -80, 20),
                rectangle("right-oks", "oks_existing", 0, 0, 20, 20));
        List<Coordinate> points = List.of(c(-81, 10), c(-79.75, 10), c(-78, 10), c(-60, 10),
                c(-60, -20), c(-40, -20), c(-40, 10), c(-2, 10), c(-0.25, 10), c(1, 10));
        List<Coordinate> expected = List.of(c(-81, 10), c(-79.75, 10), c(-78, 10), c(-60, 10),
                c(-40, 10), c(-2, 10), c(-0.25, 10), c(1, 10));
        OfficialRoutingEnvironment environment = router.prepare(features);
        assertThat(environment.pointInsideForbiddenClearance(50, c(-78, 10))).isTrue();
        assertThat(environment.pointInsideForbiddenClearance(50, c(-2, 10))).isTrue();
        for (boolean reverse : List.of(false, true)) {
            List<Coordinate> source = new ArrayList<>(points), result = new ArrayList<>(expected);
            if (reverse) { Collections.reverse(source); Collections.reverse(result); }
            RouteEdge original = edge(source, features, 50);
            RoutePath shortened = simplify(original, features, List.of());
            assertThat(shortened).isNotNull();
            assertThat(shortened.coordinates()).containsExactlyElementsOf(result);
            assertThat(shortened.lengthM()).isCloseTo(82, within(0.001));
            assertTerminalSegments(original, shortened);
            assertSectionsMatch(shortened);
        }
    }

    @Test
    void retainedSpecialCrossingsKeepAttributesLengthsAndGeometryInEitherDirection() {
        List<ImportedOfficialFeature> features = List.of(
                gas("entry-gas", -70), gas("exit-gas", -10));
        for (boolean reverse : List.of(false, true)) {
            List<Coordinate> points = new ArrayList<>(dogleg());
            if (reverse) Collections.reverse(points);
            RouteEdge original = edge(points, features, 50);
            List<RouteSection> special = original.getSections().stream()
                    .filter(section -> "special".equals(section.getKind())).collect(Collectors.toList());
            assertThat(special).hasSize(2);

            RoutePath shortened = simplify(original, features, List.of());

            assertThat(shortened).isNotNull();
            assertThat(shortened.sections().stream().filter(section -> "special".equals(section.getKind()))
                    .collect(Collectors.toList())).usingRecursiveComparison().isEqualTo(special);
            assertSectionsMatch(shortened);
            assertTerminalSegments(original, shortened);
        }
    }

    @Test
    void roundedRotatedSpecialSectionsRemainContinuousAtInteriorSplices() {
        for (double rotation : new double[] {37, 143}) {
            List<ImportedOfficialFeature> features = new ArrayList<>();
            for (double x : new double[] {-70, -10}) {
                features.add(new ImportedOfficialFeature("gas:" + x, "restriction",
                        json.createObjectNode().put("restriction_type", "gas_pipeline"),
                        rules.line(transform(List.of(c(x, 5), c(x, 15)), rotation, 1))));
            }
            for (boolean reverse : List.of(false, true)) {
                List<Coordinate> points = transform(dogleg(), rotation, 1);
                if (reverse) Collections.reverse(points);
                RouteEdge original = edge(points, features, 50);
                RoutePath shortened = simplify(original, features, List.of());
                assertThat(shortened).as("rotation=%s reverse=%s", rotation, reverse).isNotNull();
                assertTerminalSegments(original, shortened);
                assertSectionsMatch(shortened);
                // splitAt recalculates length from stored millimetre geometry; the pre-rounding
                // crossing length may differ by 1 mm. Geometry and crossing attributes must not.
                assertThat(shortened.sections().stream().filter(s -> "special".equals(s.getKind()))
                        .collect(Collectors.toList())).usingRecursiveComparison().ignoringFields("lengthM").isEqualTo(
                                original.getSections().stream().filter(s -> "special".equals(s.getKind()))
                                        .collect(Collectors.toList()));
            }
        }
    }

    @Test
    void realForbiddenPolygonPreventsShortcutAcrossAnOtherwiseLegalDetour() {
        List<ImportedOfficialFeature> features = List.of(rectangle("park", "park", -55, -15, -45, 15));
        RouteEdge original = edge(dogleg(), features, 50);
        var constraints = rules.baseConstraints(features, 50);
        assertThat(rules.lineAllowed(rules.line(coordinates(original)), constraints)).isTrue();
        assertThat(rules.lineAllowed(rules.line(List.of(c(-60, 10), c(-40, 10))), constraints)).isFalse();

        assertThat(simplify(original, features, List.of())).isNull();
    }

    @Test
    void separateAcceptedRoutePreventsShortcut() {
        LineString otherRoute = rules.line(List.of(c(-50, 0), c(-50, 20)));
        RouteEdge original = edge(dogleg(), List.of(), 50);
        assertThat(simplify(original, List.of(), List.of())).isNotNull();
        assertThat(rules.line(coordinates(original)).intersects(otherRoute)).isFalse();

        assertThat(simplify(original, List.of(), List.of(otherRoute))).isNull();
    }

    @Test
    void returnsNullWhenRealRegularizerCannotProduceAnAllowedCore() {
        List<ImportedOfficialFeature> features = List.of(rectangle("wall", "park", -52, -100, -48, 100));
        RouteEdge original = edge(dogleg(), features, 50);
        assertThat(router.regularize(dogleg().subList(1, 5), 50, router.prepare(features), Set.of(), List.of())).isNull();
        assertThat(simplify(original, features, List.of())).isNull();
    }

    @Test
    void returnsNullForAnUnimprovedStraightLine() {
        RouteEdge straight = edge(List.of(c(0, 0), c(10, 0), c(20, 0), c(30, 0), c(40, 0)), List.of(), 50);
        assertThat(simplify(straight, List.of(), List.of())).isNull();
    }

    @Test
    void declinesEmptySectionsRatherThanLosingRetainedTerminalCost() {
        RouteEdge completeOriginal = edge(dogleg(), List.of(), 50);
        RouteEdge original = new RouteEdge(completeOriginal.getId(), completeOriginal.getUpstreamNodeId(),
                completeOriginal.getDownstreamNodeId(), completeOriginal.getLengthM().doubleValue(),
                completeOriginal.getCoordinates(), List.of(), BigDecimal.ONE, 50);
        OfficialVariantEconomicsCalculator calculator = new OfficialVariantEconomicsCalculator(
                new OfficialPipeCatalog(), new OfficialEconomics());
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", original.getCoordinates().get(0), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", original.getCoordinates().get(5), false, false, 0, null));
        // Empty sections are a valid existing fallback, not a zero-cost or rejected edge.
        assertThat(new OfficialRouteValidator(rules).validate(nodes, List.of(original), List.of())).isEmpty();
        assertThat(calculator.marginalConnectionCost(List.of(original), List.of())).isEqualByComparingTo(
                calculator.marginalConnectionCost(List.of(completeOriginal), List.of()));

        RoutePath candidate = simplify(original, List.of(), List.of());

        if (candidate != null) {
            RouteEdge incompleteCandidate = asEdge(candidate, 50);
            RouteEdge fullySectionedCandidate = edge(candidate.coordinates(), List.of(), 50);
            BigDecimal sectionLength = candidate.sections().stream().map(RouteSection::getLengthM)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(candidate).as("Empty-section source must be declined unless retained sections are reconstructed: "
                            + "candidateLength=%s sectionLength=%s actualCost=%s completeGeometryCost=%s",
                    candidate.lengthM(), sectionLength,
                    calculator.marginalConnectionCost(List.of(incompleteCandidate), List.of()),
                    calculator.marginalConnectionCost(List.of(fullySectionedCandidate), List.of()))
                    .isNull();
        }
    }

    @Test
    void returnsNullForShortLinesAndUnknownDiameter() {
        for (int size = 2; size <= 4; size++) {
            RouteEdge shortLine = edge(dogleg().subList(0, size), List.of(), 50);
            assertThat(simplify(shortLine, List.of(), List.of())).as("size=%s", size).isNull();
        }
        assertThat(simplify(edge(dogleg(), List.of(), null), List.of(), List.of())).isNull();
    }

    @Test
    void fiveCoordinatesAreEnoughToSimplifyTheInterior() {
        RouteEdge original = edge(List.of(c(-10, 0), c(0, 0), c(10, 10), c(20, 0), c(30, 0)), List.of(), 50);
        RoutePath shortened = simplify(original, List.of(), List.of());
        assertThat(shortened).isNotNull();
        assertThat(shortened.lengthM()).isCloseTo(40, within(0.001));
        assertTerminalSegments(original, shortened);
        assertSectionsMatch(shortened);
    }

    @Test
    void prefersTwoRightAnglesOverThreeObtuseTurnsOnParallelBoundaryAxes() {
        List<Coordinate> points = List.of(
                c(0, 0), c(10, 0), c(30, 0), c(32, 1), c(31, 3), c(31, 30), c(40, 30), c(50, 30));
        RouteEdge original = edge(points, List.of(), 50);

        RoutePath shortened = simplify(original, List.of(), List.of());

        assertThat(shortened).isNotNull();
        assertTerminalSegments(original, shortened);
        EngineeringRouteEvaluator.Evaluation geometry = new EngineeringRouteEvaluator()
                .evaluate(List.of(asEdge(shortened, 50)));
        assertThat(geometry.invalidAngleCount()).as("coordinates=%s", shortened.coordinates()).isZero();
        assertThat(geometry.bendCount()).isEqualTo(2);
        assertThat(shortened.coordinates()).contains(c(10, 30), c(40, 30));
    }

    @Test
    void acceptsASmallLengthIncreaseToReplaceThreeObtuseTurnsWithTwoRightAngles() {
        List<Coordinate> points = List.of(
                c(33.391, 83.836), c(0, 0), c(-3.700, -9.290), c(-17.757, -11.235),
                c(-21.211, -6.796), c(-20.450, -4.924), c(-15.032, 8.406));
        RouteEdge original = edge(points, List.of(), 125);

        RoutePath regularized = simplify(original, List.of(), List.of());

        assertThat(regularized).isNotNull();
        assertThat(regularized.lengthM()).isGreaterThan(original.getLengthM().doubleValue());
        assertTerminalSegments(original, regularized);
        EngineeringRouteEvaluator.Evaluation geometry = new EngineeringRouteEvaluator()
                .evaluate(List.of(asEdge(regularized, 125)));
        assertThat(geometry.invalidAngleCount()).isZero();
        assertThat(geometry.insufficientSpacingCount()).isZero();
        assertThat(geometry.bendCount()).isEqualTo(2);
    }

    @Test
    void usesPreservedOuterAxesWhenShortInteriorLinksHideAnOrthogonalShortcut() {
        List<Coordinate> points = List.of(
                c(0, 0), c(9.273, -3.742), c(8.922, -6.643),
                c(32.317, -24.207), c(31.564, -26.081), c(26.751, -38.053));
        RouteEdge original = edge(points, List.of(), 125);

        RoutePath regularized = simplify(original, List.of(), List.of());

        assertThat(regularized).isNotNull();
        assertTerminalSegments(original, regularized);
        EngineeringRouteEvaluator.Evaluation geometry = new EngineeringRouteEvaluator()
                .evaluate(List.of(asEdge(regularized, 125)));
        assertThat(geometry.invalidAngleCount()).isZero();
        assertThat(geometry.insufficientSpacingCount()).isZero();
        assertThat(geometry.bendCount()).isEqualTo(1);
        assertThat(geometry.preferredAngleDeviation()).isLessThan(0.01);
    }

    @Test
    void acceptsExactly512CoordinatesButRejects513() {
        RouteEdge atLimit = edge(subdividedDetour(512), List.of(), 50);
        RoutePath shortened = simplify(atLimit, List.of(), List.of());
        assertThat(shortened).isNotNull();
        assertThat(shortened.lengthM()).isCloseTo(40, within(0.001));
        assertTerminalSegments(atLimit, shortened);
        assertSectionsMatch(shortened);
        assertThat(simplify(edge(subdividedDetour(513), List.of(), 50), List.of(), List.of())).isNull();
    }

    @Test
    void returnsNullWhenClearancePreservationLeavesNoInterior() {
        List<ImportedOfficialFeature> features = List.of(rectangle("containing-oks", "oks_existing", -100, -40, 20, 40));
        assertThat(simplify(edge(dogleg(), features, 50), features, List.of())).isNull();
    }

    @Test
    void selfOverlappingSpliceIsRejectedEvenWhenCoreAloneIsSimple() {
        List<Coordinate> points = List.of(c(0, 0), c(10, 0), c(10, 20), c(-10, 20), c(-10, 0), c(-20, 0));
        RouteEdge original = edge(points, List.of(), 50);
        assertThat(rules.line(points).isSimple()).isTrue();
        assertThat(router.regularize(points.subList(1, 5), 50, router.prepare(List.of()), Set.of(), List.of())).isNotNull();
        assertThat(simplify(original, List.of(), List.of())).isNull();
    }

    @Test
    void rejectsAnIllegalSpliceWhenTheRetainedNeighbourDirectionsAreKnown() {
        List<Coordinate> points = List.of(c(-10, 0), c(0, 0), c(10, 0), c(10, 10), c(-10, 10), c(-10, 20));
        RouteEdge original = edge(points, List.of(), 50);
        assertThat(OfficialRouteDeflectionRules.validatePolyline(original.getId(), original.getCoordinates()).getIssues()).isEmpty();
        RoutePath candidate = simplify(original, List.of(), List.of());
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", original.getCoordinates().get(0), true, true, 2, "support"),
                new RouteNode("demand:one", "demand_connection", original.getCoordinates().get(points.size() - 1), false, false, 0, null));
        assertThat(new OfficialRouteValidator(rules).validate(nodes, List.of(original), List.of())).isEmpty();
        assertThat(candidate).isNull();
    }

    @Test
    void terminalComparisonRequiresExactSegmentsNotOnlyTheSameRays() {
        RouteEdge original = edge(dogleg(), List.of(), 50);
        assertThat(RetainedEndpointSimplifier.sameTerminalSegments(original, edge(dogleg(), List.of(), 50))).isTrue();
        List<Coordinate> movedFirst = new ArrayList<>(dogleg());
        movedFirst.set(1, c(-61, 10));
        List<Coordinate> movedLast = new ArrayList<>(dogleg());
        movedLast.set(movedLast.size() - 2, c(-39, 10));
        assertThat(RetainedEndpointSimplifier.sameTerminalSegments(original, edge(movedFirst, List.of(), 50))).isFalse();
        assertThat(RetainedEndpointSimplifier.sameTerminalSegments(original, edge(movedLast, List.of(), 50))).isFalse();
        RouteEdge empty = new RouteEdge("empty", "root", "demand:one", 1);
        assertThat(RetainedEndpointSimplifier.sameTerminalSegments(original, empty)).isFalse();
        assertThat(RetainedEndpointSimplifier.sameTerminalSegments(empty, original)).isFalse();
    }

    @Test
    void cancellationIsObservedBeforeAnyWorkAndPreservesInterruptFlag() {
        RouteEdge original = edge(dogleg(), List.of(), 50);
        OfficialRoutingEnvironment environment = router.prepare(List.of());
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> simplifier.simplify(original, router, environment, Set.of(), List.of()))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private RoutePath simplify(RouteEdge original, List<ImportedOfficialFeature> features, List<LineString> avoidance) {
        return simplifier.simplify(original, router, router.prepare(features), Set.of(), avoidance);
    }

    private RouteEdge edge(List<Coordinate> points, List<ImportedOfficialFeature> features, Integer diameter) {
        List<RouteCoordinate> stored = model(points);
        LineString line = rules.line(stored.stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList()));
        return new RouteEdge("edge", "root", "demand:one", line.getLength(), stored,
                rules.sections(line, rules.baseConstraints(features, diameter == null ? 50 : diameter)), BigDecimal.ONE, diameter);
    }

    private RouteEdge asEdge(RoutePath path, Integer diameter) {
        return new RouteEdge("edge", "root", "demand:one", path.lengthM(), model(path.coordinates()), path.sections(), BigDecimal.ONE, diameter);
    }

    private void assertTerminalSegments(RouteEdge original, RoutePath candidate) {
        assertThat(RetainedEndpointSimplifier.sameTerminalSegments(original, asEdge(candidate, original.getDiameter()))).isTrue();
    }

    private void assertSectionsMatch(RoutePath path) {
        assertThat(path.sections()).isNotEmpty();
        double sectionLength = 0;
        Coordinate previous = path.coordinates().get(0);
        LineString route = rules.line(path.coordinates());
        for (RouteSection section : path.sections()) {
            List<Coordinate> points = section.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
            assertThat(points).hasSizeGreaterThanOrEqualTo(2);
            assertThat(points.get(0)).isEqualTo(previous);
            LineString line = rules.line(points);
            assertThat(line.getLength()).isCloseTo(section.getLengthM().doubleValue(), within(0.00051));
            for (Coordinate point : points) {
                assertThat(route.distance(new GeometryFactory().createPoint(point))).isLessThanOrEqualTo(0.002);
            }
            sectionLength += line.getLength();
            previous = points.get(points.size() - 1);
        }
        assertThat(previous).isEqualTo(path.coordinates().get(path.coordinates().size() - 1));
        assertThat(sectionLength).isCloseTo(path.lengthM(), within(0.002));
        assertThat(route.getLength()).isCloseTo(path.lengthM(), within(1e-8));
    }

    private ImportedOfficialFeature gas(String id, double x) {
        return new ImportedOfficialFeature(id, "restriction", json.createObjectNode().put("restriction_type", "gas_pipeline"),
                rules.line(List.of(c(x, 5), c(x, 15))));
    }

    private ImportedOfficialFeature rectangle(String id, String type, double left, double bottom, double right, double top) {
        return new ImportedOfficialFeature(id, "oks_existing".equals(type) ? type : "restriction",
                json.createObjectNode().put("restriction_type", type), new GeometryFactory().createPolygon(new Coordinate[] {
                        c(left, bottom), c(right, bottom), c(right, top), c(left, top), c(left, bottom)}));
    }

    private static List<Coordinate> dogleg() {
        return List.of(c(-80, 10), c(-60, 10), c(-60, -20), c(-40, -20), c(-40, 10), c(1, 10));
    }

    private static List<Coordinate> subdividedDetour(int size) {
        List<Coordinate> result = new ArrayList<>(List.of(c(-10, 0), c(0, 0)));
        int divisions = size - 4;
        for (int index = 1; index <= divisions; index++) result.add(c(10.0 * index / divisions, 10.0 * index / divisions));
        result.add(c(20, 0));
        result.add(c(30, 0));
        return result;
    }

    private static List<Coordinate> transform(List<Coordinate> points, double degrees, double utm) {
        double cos = Math.cos(Math.toRadians(degrees)), sin = Math.sin(Math.toRadians(degrees));
        return points.stream().map(p -> c(500000.123 * utm + p.x * cos - p.y * sin,
                6173000.456 * utm + p.x * sin + p.y * cos)).collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<RouteCoordinate> model(List<Coordinate> points) {
        return points.stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList());
    }

    private static List<Coordinate> coordinates(RouteEdge edge) {
        return edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
    }

    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
}
