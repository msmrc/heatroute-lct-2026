package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryComponentFilter;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteValidator.ValidationSession;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Проверяет отбор и повторную подготовку road/tram по осевому отступу из §§3.1/4 приложения. */
class OfficialSpecialClearancePreparationTest {
    private static final List<String> TYPES = List.of("road", "tram_tracks");
    private static final double MILLIMETRE_M = 0.001;
    private final GeometryFactory factory = new GeometryFactory(new PrecisionModel(), 32637);
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialConstraintCatalog catalog = new OfficialConstraintCatalog();
    private final OfficialAxisClearance clearance = new OfficialAxisClearance(pipes, catalog);
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            catalog, new OfficialCrossingGeometry());
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);

    @Test
    void roadSearchRejectsDu100At1700Millimetres() {
        assertParallelSearch("road", 100, 1.700, false);
    }

    @Test
    void tramSearchRejectsDu100At1700Millimetres() {
        assertParallelSearch("tram_tracks", 100, 1.700, false);
    }

    @Test
    void roadFinalValidationRejectsDu100At1700Millimetres() {
        assertValidation(validator.forCalculation(), parallel(1.700), 100, features("road"), false);
    }

    @Test
    void tramFinalValidationRejectsDu100At1700Millimetres() {
        assertValidation(validator.forCalculation(), parallel(1.700), 100, features("tram_tracks"), false);
    }

    @Test
    void searchAcceptsDu100At1755And1756Millimetres() {
        forTypes(type -> {
            assertThat(clearance.axisClearanceM(type, 100, null)).isEqualByComparingTo("1.755");
            assertAll(
                    () -> assertParallelSearch(type, 100, 1.755, true),
                    () -> assertParallelSearch(type, 100, 1.756, true));
        });
    }

    @Test
    void finalValidationAcceptsDu100At1755And1756Millimetres() {
        forTypes(type -> {
            ValidationSession session = validator.forCalculation();
            assertAll(
                    () -> assertValidation(session, parallel(1.755), 100, features(type), true),
                    () -> assertValidation(session, parallel(1.756), 100, features(type), true));
        });
    }

    @Test
    void searchRejectsOneMillimetreInsideEveryOfficialDiameterBoundary() {
        forDiameters((type, diameter) -> assertParallelSearch(
                type, diameter, axisClearance(type, diameter) - MILLIMETRE_M, false));
    }

    @Test
    void finalValidationRejectsOneMillimetreInsideEveryOfficialDiameterBoundary() {
        ValidationSession session = validator.forCalculation();
        forDiameters((type, diameter) -> assertValidation(session,
                parallel(axisClearance(type, diameter) - MILLIMETRE_M), diameter, features(type), false));
    }

    @Test
    void searchAcceptsExactAndOneMillimetreOutsideEveryOfficialDiameterBoundary() {
        forDiameters((type, diameter) -> assertAll(
                () -> assertParallelSearch(type, diameter, axisClearance(type, diameter), true),
                () -> assertParallelSearch(type, diameter, axisClearance(type, diameter) + MILLIMETRE_M, true)));
    }

    @Test
    void finalValidationAcceptsExactAndOneMillimetreOutsideEveryOfficialDiameterBoundary() {
        ValidationSession session = validator.forCalculation();
        forDiameters((type, diameter) -> assertAll(
                () -> assertValidation(session, parallel(axisClearance(type, diameter)),
                        diameter, features(type), true),
                () -> assertValidation(session, parallel(axisClearance(type, diameter) + MILLIMETRE_M),
                        diameter, features(type), true)));
    }

    @Test
    void searchIndexRetainsClearanceNeighbourOutsideSourceEnvelopeAt127And128Constraints() {
        forTypes(type -> assertAll(
                () -> assertIndexNeighbour(type, 127),
                () -> assertIndexNeighbour(type, 128)));
    }

    @Test
    void validationPreparationRetainsClearanceNeighbourOutsideSourceEnvelope() {
        forTypes(type -> {
            PreparedValidationConstraints prepared = new PreparedValidationConstraints(rules);
            List<ImportedOfficialFeature> features = features(type);
            assertThat(prepared.supports(features)).isTrue();
            // Второй запрос проверяет сохранённый envelope; точная граница также входит в отбор.
            List<Executable> checks = new ArrayList<>();
            for (double distanceM : new double[] {1.700, 1.755, 1.700, 1.755}) {
                LineString route = parallel(distanceM);
                assertThat(features.get(0).getMetricGeometry().getEnvelopeInternal()
                        .intersects(route.getEnvelopeInternal())).isFalse();
                List<Constraint> selected = prepared.prepareIntersecting(features, 100, route.getEnvelopeInternal());
                checks.add(() -> assertThat(selected).as("%s distance=%s", type, distanceM)
                        .extracting(Constraint::id).containsExactly("corridor"));
            }
            assertAll(checks);
        });
    }

    @Test
    void searchRechecksDu100To400AndBackAtTwoMetres() {
        forTypes(type -> {
            assertThat(clearance.axisClearanceM(type, 400, null)).isEqualByComparingTo("2.185");
            OfficialRoutingEnvironment environment = environment(features(type));
            List<Boolean> accepted = new ArrayList<>();
            for (int diameter : new int[] {100, 400, 100}) {
                accepted.add(rules.lineAllowed(parallel(2.000), constraints(environment, parallel(2.000), diameter)));
            }
            assertThat(accepted).as(type).containsExactly(true, false, true);
        });
    }

    @Test
    void finalValidationRechecksDu100To400AndBackAtTwoMetres() {
        forTypes(type -> {
            ValidationSession session = validator.forCalculation();
            List<Executable> checks = new ArrayList<>();
            for (int diameter : new int[] {100, 400, 100}) {
                checks.add(() -> assertValidation(session, parallel(2.000), diameter, features(type), diameter == 100));
            }
            assertAll(checks);
        });
    }

    @Test
    void searchPreparationPreservesDuplicateOccurrencesAndStableOrder() {
        forTypes(type -> {
            PreparedRoutingConstraints prepared = new PreparedRoutingConstraints(rules);
            List<ImportedOfficialFeature> duplicates = duplicates(type);
            List<Executable> checks = new ArrayList<>();
            for (int repeat = 0; repeat < 2; repeat++) {
                List<Constraint> selected = prepared.prepare(duplicates, 100);
                assertDuplicates(selected);
                checks.add(() -> assertThat(rules.lineAllowed(parallel(1.700), selected)).as(type).isFalse());
            }
            assertAll(checks);
        });
    }

    @Test
    void validationPreparationPreservesDuplicateOccurrencesAndStableOrder() {
        forTypes(type -> {
            PreparedValidationConstraints prepared = new PreparedValidationConstraints(rules);
            List<ImportedOfficialFeature> duplicates = duplicates(type);
            List<Executable> checks = new ArrayList<>();
            for (int repeat = 0; repeat < 2; repeat++) {
                List<Constraint> selected = prepared.prepareIntersecting(
                        duplicates, 100, parallel(1.700).getEnvelopeInternal());
                checks.add(() -> assertDuplicates(selected));
            }
            ValidationSession session = validator.forCalculation();
            checks.add(() -> assertValidation(session, parallel(1.700), 100, duplicates, false));
            assertAll(checks);
        });
    }

    @Test
    void searchRequeriesCurrentWindowWithoutReusingPreviousObstacleMembership() {
        forTypes(type -> {
            List<ImportedOfficialFeature> current = new ArrayList<>();
            OfficialRoutingEnvironment environment = environment(current);
            List<Boolean> accepted = new ArrayList<>();
            for (List<ImportedOfficialFeature> window : windows(type)) {
                current.clear();
                current.addAll(window);
                accepted.add(rules.lineAllowed(parallel(1.700), constraints(environment, parallel(1.700), 100)));
            }
            assertThat(accepted).as(type).containsExactly(false, true, true, false);
        });
    }

    @Test
    void validationSessionUsesOnlyCurrentFeaturesAfterRemovalAndSameIdReplacement() {
        forTypes(type -> {
            ValidationSession session = validator.forCalculation();
            List<List<ImportedOfficialFeature>> windows = windows(type);
            assertAll(
                    () -> assertValidation(session, parallel(1.700), 100, windows.get(0), false),
                    () -> assertValidation(session, parallel(1.700), 100, windows.get(1), true),
                    () -> assertValidation(session, parallel(1.700), 100, windows.get(2), true),
                    () -> assertValidation(session, parallel(1.700), 100, windows.get(3), false));
        });
    }

    @Test
    void searchPreparationEvictionPreservesBothReturnedAndRebuiltConstraints() {
        forTypes(type -> {
            PreparedRoutingConstraints prepared = new PreparedRoutingConstraints(rules, 1, 100_000);
            List<Constraint> first = prepared.prepare(features(type), 100);
            prepared.prepare(List.of(special("other", type, 1000, 0)), 100);
            List<Constraint> rebuilt = prepared.prepare(features(type), 100);
            assertThat(prepared.retainedEntryCount()).isEqualTo(1);
            assertThat(prepared.retainedCoordinateCount()).isLessThanOrEqualTo(100_000L);
            assertThat(rebuilt.get(0)).isNotSameAs(first.get(0));
            assertAll(
                    () -> assertThat(rules.lineAllowed(parallel(1.700), first)).as(type + " returned").isFalse(),
                    () -> assertThat(rules.lineAllowed(parallel(1.700), rebuilt)).as(type + " rebuilt").isFalse());
        });
    }

    @Test
    void validationMetadataEvictionDoesNotLoseNearbySpecialConstraints() {
        forTypes(type -> assertAll(
                () -> assertValidationEviction(type, 0),
                () -> assertValidationEviction(type, 7),
                () -> assertValidationEviction(type, 100_000)));
    }

    @Test
    void preparationOwnsSpecialGeometryAndDropsUserData() {
        forTypes(type -> {
            ImportedOfficialFeature feature = special("corridor", type, 0, 0);
            Geometry geometry = feature.getMetricGeometry();
            Object metadata = mapper.createObjectNode().put("unused", "metadata");
            geometry.apply((GeometryComponentFilter) component -> component.setUserData(metadata));
            Constraint search = new PreparedRoutingConstraints(rules).prepare(List.of(feature), 100).get(0);
            Constraint validation = new PreparedValidationConstraints(rules)
                    .prepareIntersecting(List.of(feature), 100, perpendicular().getEnvelopeInternal()).get(0);
            for (Constraint owned : List.of(search, validation)) {
                assertNotSame(geometry, owned.source());
                assertThat(owned.source().equalsExact(geometry)).isTrue();
                owned.source().apply((GeometryComponentFilter) component -> assertThat(component.getUserData()).isNull());
            }
            assertThat(geometry.getUserData()).isSameAs(metadata);
        });
    }

    @Test
    void roadPerpendicularCrossingControlRemainsAccepted() {
        assertPerpendicularControl("road");
    }

    @Test
    void tramPerpendicularCrossingControlRemainsAccepted() {
        assertPerpendicularControl("tram_tracks");
    }

    private void assertParallelSearch(String type, int diameter, double distanceM, boolean accepted) {
        List<ImportedOfficialFeature> features = features(type);
        LineString route = parallel(distanceM);
        assertThat(route.distance(features.get(0).getMetricGeometry())).isCloseTo(distanceM, offset(1e-8));
        List<Constraint> prepared = constraints(environment(features), route, diameter);
        assertThat(prepared).extracting(Constraint::id).containsExactly("corridor");
        assertAll(
                () -> assertThat(rules.lineAllowed(route, rules.baseConstraints(features, diameter)))
                        .as("%s DU%d distance=%s standalone search", type, diameter, distanceM).isEqualTo(accepted),
                () -> assertThat(rules.lineAllowed(route, prepared))
                        .as("%s DU%d distance=%s prepared search", type, diameter, distanceM).isEqualTo(accepted));
    }

    private void assertValidation(ValidationSession session, LineString route, int diameter,
            List<ImportedOfficialFeature> features, boolean accepted) {
        List<RouteNode> nodes = nodes(route);
        List<RouteEdge> edges = List.of(edge(route, diameter, features));
        // Отказ должен появиться только после добавления ограничения, не из-за топологии фикстуры.
        assertThat(validator.validate(nodes, edges, List.of())).isEmpty();
        List<RouteValidationIssue> standalone = validator.validate(nodes, edges, features);
        List<RouteValidationIssue> reused = session.validate(nodes, edges, features);
        String context = (features.isEmpty() ? "no restrictions" : rules.constraintType(features.get(0)))
                + " DU" + diameter + " " + route;
        assertAll(
                () -> assertThat(standalone.isEmpty()).as(context + " standalone acceptance").isEqualTo(accepted),
                () -> assertThat(reused.isEmpty()).as(context + " session acceptance").isEqualTo(accepted),
                () -> assertThat(reused).as(context + " session consistency")
                        .usingRecursiveComparison().isEqualTo(standalone));
    }

    private void assertIndexNeighbour(String type, int count) {
        List<ImportedOfficialFeature> features = new ArrayList<>(features(type));
        features.add(features.get(0));
        while (features.size() < count) {
            features.add(special("far-" + features.size(), type, 10000 + 200 * features.size(), 0));
        }
        LineString route = parallel(1.700);
        assertThat(features.get(0).getMetricGeometry().getEnvelopeInternal()
                .intersects(route.getEnvelopeInternal())).isFalse();
        List<Constraint> all = rules.baseConstraints(features, 100);
        assertThat(all).hasSize(count);
        OfficialRouteGeometryRules.ConstraintIndex index = rules.index(all);
        assertAll(
                () -> assertThat(index.query(route.getEnvelopeInternal())).as("%s count=%d", type, count)
                        .filteredOn(item -> "corridor".equals(item.id())).hasSize(2),
                () -> assertThat(rules.lineAllowed(route, index)).as("%s count=%d search", type, count).isFalse(),
                () -> assertThat(rules.lineAllowed(parallel(1.755), index)).isTrue(),
                () -> assertThat(rules.lineAllowed(parallel(1.756), index)).isTrue(),
                () -> assertThat(rules.lineAllowed(perpendicular(), index)).isTrue());
    }

    private void assertValidationEviction(String type, long budget) {
        PreparedValidationConstraints prepared = new PreparedValidationConstraints(rules, 2, 1, budget);
        List<ImportedOfficialFeature> near = features(type);
        List<Constraint> first = prepared.prepareIntersecting(near, 100, perpendicular().getEnvelopeInternal());
        assertThat(first).hasSize(1);
        prepared.prepareIntersecting(List.of(special("other", type, 1000, 0)), 100,
                perpendicular().getEnvelopeInternal());
        assertThat(prepared.retainedMetadataCount()).isEqualTo(budget == 0 ? 0 : 1);
        List<Constraint> rebuilt = prepared.prepareIntersecting(near, 100, parallel(1.700).getEnvelopeInternal());
        assertThat(prepared.retainedSourceCount()).isLessThanOrEqualTo(1);
        assertThat(prepared.retainedMetadataCount()).isLessThanOrEqualTo(1);
        assertThat(prepared.retainedCoordinateCount()).isLessThanOrEqualTo(budget);
        RouteEdge edge = edge(parallel(1.700), 100, near);
        assertAll(
                () -> assertThat(rebuilt).as("%s budget=%d", type, budget)
                        .extracting(Constraint::id).containsExactly("corridor"),
                () -> assertThat(rules.validate(edge, parallel(1.700), first)).as(type + " returned").isNotEmpty(),
                () -> assertThat(rules.validate(edge, parallel(1.700), rebuilt)).as(type + " rebuilt").isNotEmpty());
    }

    private void assertPerpendicularControl(String type) {
        List<ImportedOfficialFeature> features = features(type);
        LineString route = perpendicular();
        List<Constraint> prepared = constraints(environment(features), route, 100);
        assertThat(rules.lineAllowed(route, prepared)).isTrue();
        assertValidation(validator.forCalculation(), route, 100, features, true);
        assertThat(rules.sections(route, prepared)).filteredOn(section -> "special".equals(section.getKind()))
                .singleElement().satisfies(section -> assertThat(section.getLengthM()).isEqualByComparingTo("12"));
    }

    private void assertDuplicates(List<Constraint> selected) {
        assertThat(selected).extracting(Constraint::id).containsExactly("a", "same", "same", "same");
        assertThat(selected).extracting(item -> item.source().getEnvelopeInternal().getMinX())
                .containsExactly(500040.0, 500020.0, 500000.0, 500020.0);
    }

    private List<ImportedOfficialFeature> duplicates(String type) {
        ImportedOfficialFeature first = special("same", type, 0, 0);
        ImportedOfficialFeature second = special("same", type, 20, 0);
        return List.of(second, first, special("a", type, 40, 0), second);
    }

    private List<List<ImportedOfficialFeature>> windows(String type) {
        return List.of(features(type), List.of(), List.of(special("corridor", type, 2000, 0)), features(type));
    }

    private OfficialRoutingEnvironment environment(List<ImportedOfficialFeature> features) {
        return new OfficialRoutingEnvironment(List.of(), new InMemoryRoutingFeatureSource(features), rules);
    }

    private List<Constraint> constraints(OfficialRoutingEnvironment environment, LineString route, int diameter) {
        return environment.constraints(diameter, Set.of(), route.getCoordinateN(0),
                route.getCoordinateN(route.getNumPoints() - 1));
    }

    private RouteEdge edge(LineString route, int diameter, List<ImportedOfficialFeature> features) {
        List<RouteCoordinate> coordinates = new ArrayList<>();
        for (Coordinate coordinate : route.getCoordinates()) {
            coordinates.add(new RouteCoordinate(coordinate.x, coordinate.y));
        }
        return new RouteEdge("test", "root", "demand", route.getLength(), coordinates,
                rules.sections(route, rules.baseConstraints(features, diameter)), new BigDecimal("20"), diameter);
    }

    private List<RouteNode> nodes(LineString route) {
        Coordinate start = route.getCoordinateN(0);
        Coordinate end = route.getCoordinateN(route.getNumPoints() - 1);
        return List.of(
                new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(start.x, start.y),
                        true, true, 2, "source"),
                new RouteNode("demand", "demand_connection", new RouteCoordinate(end.x, end.y),
                        false, false, 0, null));
    }

    private List<ImportedOfficialFeature> features(String type) {
        return List.of(special("corridor", type, 0, 0));
    }

    private ImportedOfficialFeature special(String id, String type, double x, double y) {
        return new ImportedOfficialFeature(id, "restriction", mapper.createObjectNode().put("restriction_type", type),
                factory.createPolygon(new Coordinate[] {
                    c(x, y), c(x + 100, y), c(x + 100, y + 6), c(x, y + 6), c(x, y)
                }));
    }

    private LineString parallel(double distanceM) {
        return factory.createLineString(new Coordinate[] {c(-10, 6 + distanceM), c(110, 6 + distanceM)});
    }

    private LineString perpendicular() {
        return factory.createLineString(new Coordinate[] {c(50, -10), c(50, 16)});
    }

    private Coordinate c(double x, double y) {
        return new Coordinate(500000 + x, 6170000 + y);
    }

    private double axisClearance(String type, int diameter) {
        return clearance.axisClearanceM(type, diameter, null).doubleValue();
    }

    private void forTypes(Consumer<String> check) {
        assertAll(TYPES.stream().map(type -> (Executable) () -> check.accept(type)));
    }

    private void forDiameters(BiConsumer<String, Integer> check) {
        assertThat(pipes.entries()).hasSize(18);
        assertAll(pipes.entries().stream().flatMap(entry -> TYPES.stream()
                .map(type -> (Executable) () -> check.accept(type, entry.getDiameter()))));
    }
}
