package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.NormalEgress;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class PreparedValidationConstraintsTest {
    private static final int[] DIAMETERS = {50, 100, 150, 200, 250, 300, 400, 500};
    private static final String BUILDING = "POLYGON ((-5 -5, 5 -5, 5 5, -5 5, -5 -5))";
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final PreparedValidationConstraints prepared = new PreparedValidationConstraints(rules);

    @Test
    void rotatingDiametersKeepDistantBoundsAfterHeavyBufferEvictionAndRecheckChangedRoutes() throws Exception {
        // Бюджет вмещает один полный ДУ старого cache; новый держит все bounds и два тяжёлых buffer.
        PreparedValidationConstraints bounded = new PreparedValidationConstraints(rules, 2, 16, 180);
        CountingRules baselineRules = new CountingRules();
        PreparedRoutingConstraints baseline = new PreparedRoutingConstraints(baselineRules, 16, 180);
        List<ImportedOfficialFeature> features = List.of(
                feature("near", "park", "POINT (0 0)"), feature("far", "park", "POINT (1000 0)"));
        LineString near = line("LINESTRING (-10 0, 10 0)");
        assertThat(bounded.supports(features)).isTrue();
        for (int diameter : DIAMETERS) {
            List<Constraint> selected = bounded.prepareIntersecting(features, diameter, near.getEnvelopeInternal());
            assertThat(selected).extracting(Constraint::id).containsExactly("near");
            assertIssuesAgainst(baseline.prepare(features, diameter), diameter, near, selected);
        }
        // Счётчик относится к успешным настоящим forbidden-buffer вычислениям, а не к обращениям к cache.
        assertThat(bounded.preparedForbiddenCount()).isEqualTo(16L);
        assertThat(baselineRules.preparedForbidden).isEqualTo(16L);
        baseline.prepare(features, 500);
        assertThat(baselineRules.preparedForbidden).as("baseline reuses one complete DU set").isEqualTo(16L);
        assertThat(bounded.retainedSourceCount()).isEqualTo(2);
        assertThat(bounded.retainedMetadataCount()).isEqualTo(16);
        assertThat(bounded.retainedBufferCount()).isLessThanOrEqualTo(2);
        assertThat(bounded.retainedCoordinateCount()).isLessThanOrEqualTo(180L);

        long before = bounded.preparedForbiddenCount();
        long baselineBefore = baselineRules.preparedForbidden;
        for (int diameter : DIAMETERS) {
            assertIssuesAgainst(baseline.prepare(features, diameter), diameter, near,
                    bounded.prepareIntersecting(features, diameter, near.getEnvelopeInternal()));
        }
        assertThat(baselineRules.preparedForbidden - baselineBefore).isEqualTo(16L);
        assertThat(bounded.preparedForbiddenCount() - before).isEqualTo(8L);
        assertThat(baseline.retainedCoordinateCount()).isLessThanOrEqualTo(180L);
        before = bounded.preparedForbiddenCount();
        bounded.prepareIntersecting(features, 500, near.getEnvelopeInternal());
        assertThat(bounded.preparedForbiddenCount()).isEqualTo(before);
        assertThat(bounded.prepareIntersecting(List.of(), 500, near.getEnvelopeInternal())).isEmpty();
        assertThat(bounded.prepareIntersecting(List.of(features.get(1)), 500, near.getEnvelopeInternal())).isEmpty();
        assertThat(bounded.preparedForbiddenCount()).isEqualTo(before);

        LineString safe = line("LINESTRING (990 10, 1010 10)");
        assertThat(assertIssues(features, 500, safe,
                bounded.prepareIntersecting(features, 500, safe.getEnvelopeInternal()))).isEmpty();
        assertThat(bounded.preparedForbiddenCount()).isEqualTo(before);
        LineString changed = line("LINESTRING (990 10, 990 0, 1010 0, 1010 10)");
        assertThat(assertIssues(features, 500, changed,
                bounded.prepareIntersecting(features, 500, changed.getEnvelopeInternal())))
                .extracting(RouteValidationIssue::getMessage).containsExactly("Route violates park clearance at far");
        assertThat(bounded.preparedForbiddenCount()).isEqualTo(before + 1);
        assertThat(bounded.retainedMetadataCount()).isEqualTo(16);
        assertThat(bounded.retainedCoordinateCount()).isLessThanOrEqualTo(180L);
    }

    @Test
    void keepsFullAxisEnvelopeForTangencyAndOwnPrefixChecks() throws Exception {
        List<ImportedOfficialFeature> features = List.of(feature("own", "oks", BUILDING));
        // ДУ50: табличный просвет 5 м + половина ширины пары 0.4/2 м.
        double axisClearance = 5.2;
        NormalEgress egress = rules.normalEgressTowards(features, 50, new Coordinate(0, 0), new Coordinate(-30, 0))
                .orElseThrow();
        for (double offset : List.of(-0.001, 0.0, 0.001, 0.0)) {
            double x = -5 - axisClearance - offset;
            LineString prefix = line("LINESTRING (" + x + " -2, " + x + " 2)");
            List<Constraint> selected = prepared.prepareIntersecting(features, 50, prefix.getEnvelopeInternal());
            List<Constraint> full = rules.baseConstraints(features, 50);
            if (offset == 0.0) {
                assertThat(full.get(0).blocked().getEnvelopeInternal().intersects(prefix.getEnvelopeInternal())).isFalse();
                assertThat(selected).hasSize(1);
            }
            RouteEdge edge = edge(prefix, 50);
            List<RouteValidationIssue> expected = rules.validateOwnTerminalClearance(edge, prefix, full, egress, 50);
            List<RouteValidationIssue> actual = rules.validateOwnTerminalClearance(edge, prefix, selected, egress, 50);
            assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
            assertThat(actual.isEmpty()).as("own prefix offset %s", offset).isEqualTo(offset >= 0);
            assertIssues(features, 50, prefix, selected);
        }
    }

    @Test
    void fullPolylineEnvelopeFindsObstacleOutsideEndpointEnvelope() throws Exception {
        List<ImportedOfficialFeature> features = List.of(feature("block", "park", BUILDING));
        LineString route = line("LINESTRING (-30 20, -30 0, 30 0, 30 20)");
        Envelope endpoints = new Envelope(route.getCoordinateN(0), route.getCoordinateN(route.getNumPoints() - 1));
        assertThat(prepared.prepareIntersecting(features, 50, endpoints)).isEmpty();
        List<Constraint> selected = prepared.prepareIntersecting(features, 50, route.getEnvelopeInternal());
        assertThat(selected).extracting(Constraint::id).containsExactly("block");
        assertThat(assertIssues(features, 50, route, selected)).extracting(RouteValidationIssue::getCode)
                .containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
    }

    @Test
    void specialConstraintsUseSourceEnvelopeAndPreserveAngleAndSectionIssues() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("same", "road", "LINESTRING (0 -10, 0 10)"),
                feature("same", "road", "LINESTRING (-20 -5, 20 5)"));
        LineString crossing = line("LINESTRING (-30 0, 30 0)");
        List<Constraint> selected = prepared.prepareIntersecting(features, 101, crossing.getEnvelopeInternal());
        assertThat(selected).hasSize(2);
        assertThat(assertIssues(features, 101, crossing, selected)).extracting(RouteValidationIssue::getCode)
                .contains("SPECIAL_CROSSING_ANGLE_VIOLATION", "SPECIAL_CROSSING_SECTION_MISSING");
        // Участок в пределах special extension, но без пересечения исходного объекта.
        LineString outside = line("LINESTRING (-30 12, 30 12)");
        assertThat(prepared.prepareIntersecting(features, 101, outside.getEnvelopeInternal())).isEmpty();
        assertThat(assertIssues(features, 101, outside, List.of())).isEmpty();
        assertThat(prepared.preparedForbiddenCount()).isZero();
    }

    @Test
    void farForbiddenFeaturesStillValidateDiameterAndFailuresAreNotRetained() throws Exception {
        Envelope query = new Envelope(-10, 10, -10, 10);
        for (String type : List.of("oks", "park", "water")) {
            List<ImportedOfficialFeature> features = List.of(feature(type, type, "POINT (1000 1000)"));
            assertThat(prepared.prepareIntersecting(features, 50, query)).isEmpty();
            long successful = prepared.preparedForbiddenCount();
            int records = prepared.retainedMetadataCount();
            for (int diameter : new int[] {101, -1, 101}) {
                assertThatThrownBy(() -> prepared.prepareIntersecting(features, diameter, query))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("new-network diameter must be an official DU");
            }
            assertThat(prepared.preparedForbiddenCount()).isEqualTo(successful);
            assertThat(prepared.retainedMetadataCount()).isEqualTo(records);
            assertThat(prepared.prepareIntersecting(features, 50, query)).isEmpty();
            assertThat(prepared.preparedForbiddenCount()).isEqualTo(successful);
        }
        List<ImportedOfficialFeature> ignored = List.of(feature("empty", "oks", "POLYGON EMPTY"),
                new ImportedOfficialFeature("null-geometry", "oks_existing", null, null),
                feature("unknown", "unknown", "POINT (0 0)"));
        assertThat(prepared.prepareIntersecting(ignored, 101, query)).isEmpty();
        assertThat(prepared.prepareIntersecting(List.of(), 101, query)).isEmpty();
    }

    @Test
    void geometryChangesInvalidateEveryDiameterRecordForThatTypedId() throws Exception {
        ImportedOfficialFeature source = feature("same", "park", "POINT (1000 0)");
        Envelope query = new Envelope(-10, 10, -10, 10);
        prepared.prepareIntersecting(List.of(source), 50, query);
        prepared.prepareIntersecting(List.of(source), 500, query);
        assertThat(prepared.retainedMetadataCount()).isEqualTo(2);
        shiftX(source.getMetricGeometry(), -1000);
        assertThat(prepared.prepareIntersecting(List.of(source), 50, query)).hasSize(1);
        assertThat(prepared.retainedMetadataCount()).isEqualTo(1);
        assertThat(prepared.preparedForbiddenCount()).isEqualTo(3L);
        assertThat(prepared.prepareIntersecting(List.of(source), 500, query)).hasSize(1);
        assertThat(prepared.preparedForbiddenCount()).isEqualTo(4L);
        ImportedOfficialFeature replacement = feature("same", "park", "POINT (1000 0)");
        assertThat(prepared.prepareIntersecting(List.of(replacement), 50, query)).isEmpty();
        assertThat(prepared.retainedMetadataCount()).isEqualTo(1);
        assertThat(prepared.preparedForbiddenCount()).isEqualTo(5L);
        ((ObjectNode) source.getAttributes()).put("restriction_type", "water");
        List<Constraint> changedType = prepared.prepareIntersecting(List.of(source), 50, query);
        assertConstraints(rules.baseConstraints(List.of(source), 50), changedType);
        assertThat(changedType).extracting(Constraint::type).containsExactly("water");
    }

    @Test
    void preservesDuplicateOccurrencesTypedIdsAndStableTieOrder() throws Exception {
        ImportedOfficialFeature first = feature("same", "park", "POINT (0 0)");
        ImportedOfficialFeature second = feature("same", "park", "POINT (2 0)");
        ImportedOfficialFeature earlier = feature("a", "park", "POINT (4 0)");
        ImportedOfficialFeature road = feature("same", "road", "LINESTRING (6 -10, 6 10)");
        LineString route = line("LINESTRING (-20 0, 20 0)");
        for (List<ImportedOfficialFeature> features : List.of(
                List.of(second, road, first, earlier, first), List.of(road, first, second, second, earlier))) {
            for (int repeat = 0; repeat < 2; repeat++) {
                List<Constraint> actual = prepared.prepareIntersecting(features, 50, route.getEnvelopeInternal());
                assertConstraints(rules.baseConstraints(features, 50), actual);
                assertThat(actual).hasSize(features.size());
                assertIssues(features, 50, route, actual);
                actual.clear();
            }
        }
    }

    @Test
    void sharesOwnedSourceAcrossDiametersAndAccountsForEveryRetainedGeometry() throws Exception {
        ImportedOfficialFeature feature = feature("owned", "park", "POINT (0 0)");
        feature.getMetricGeometry().setUserData(feature.getAttributes());
        Envelope query = new Envelope(-10, 10, -10, 10);
        Constraint first = prepared.prepareIntersecting(List.of(feature), 50, query).get(0);
        Constraint second = prepared.prepareIntersecting(List.of(feature), 500, query).get(0);
        assertThat(first.source()).isNotSameAs(feature.getMetricGeometry());
        assertThat(first.source().getUserData()).isNull();
        assertThat(second.source()).isSameAs(first.source());
        long expected = first.source().getNumPoints() + 4L
                + first.blocked().getNumPoints() + first.segmentIndexCoordinateReservation()
                + second.blocked().getNumPoints() + second.segmentIndexCoordinateReservation();
        assertThat(prepared.retainedCoordinateCount()).isEqualTo(expected);
        assertThat(prepared.retainedSourceCount()).isEqualTo(1);
        assertThat(prepared.retainedMetadataCount()).isEqualTo(2);
        assertThat(prepared.retainedBufferCount()).isEqualTo(2);
        Constraint independent = new PreparedValidationConstraints(rules)
                .prepareIntersecting(List.of(feature), 50, query).get(0);
        assertThat(independent.source()).isNotSameAs(first.source());
        shiftX(feature.getMetricGeometry(), 1000);
        assertThat(prepared.prepareIntersecting(List.of(feature), 50, query)).isEmpty();
        assertThat(first.source().getCoordinate().x).isZero();
        assertThat(first.source().getUserData()).isNull();
    }

    @Test
    void sourceAndRecordEvictionNeverDropReturnedConstraints() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("a", "park", "POINT (0 0)"), feature("b", "park", "POINT (2 0)"));
        Envelope query = new Envelope(-10, 10, -10, 10);
        PreparedValidationConstraints oneSource = new PreparedValidationConstraints(rules, 1, 8, 1000);
        for (int repeat = 0; repeat < 2; repeat++) {
            assertConstraints(rules.baseConstraints(features, 50), oneSource.prepareIntersecting(features, 50, query));
            assertThat(oneSource.retainedSourceCount()).isLessThanOrEqualTo(1);
            assertThat(oneSource.retainedMetadataCount()).isLessThanOrEqualTo(1);
            assertThat(oneSource.retainedCoordinateCount()).isLessThanOrEqualTo(1000L);
        }
        PreparedValidationConstraints oneRecord = new PreparedValidationConstraints(rules, 8, 1, 1000);
        for (int diameter : new int[] {50, 500, 50}) {
            assertConstraints(rules.baseConstraints(List.of(features.get(0)), diameter),
                    oneRecord.prepareIntersecting(List.of(features.get(0)), diameter, query));
            assertThat(oneRecord.retainedMetadataCount()).isEqualTo(1);
            assertThat(oneRecord.retainedBufferCount()).isLessThanOrEqualTo(1);
            assertThat(oneRecord.retainedCoordinateCount()).isLessThanOrEqualTo(1000L);
        }
        assertThat(oneRecord.preparedForbiddenCount()).isEqualTo(3L);
    }

    @Test
    void metadataBudgetBoundaryKeepsBoundsWithoutBufferAndDeclinesOversizedSources() throws Exception {
        ImportedOfficialFeature near = feature("same", "park", "POINT (0 0)");
        ImportedOfficialFeature far = feature("same", "park", "POINT (1000 0)");
        Envelope query = new Envelope(-10, 10, -10, 10);
        // Одна исходная координата + две на envelope; сам buffer не помещается.
        PreparedValidationConstraints metadataOnly = new PreparedValidationConstraints(rules, 4, 4, 3);
        for (int repeat = 0; repeat < 2; repeat++) {
            assertThat(metadataOnly.prepareIntersecting(List.of(far), 50, query)).isEmpty();
        }
        assertThat(metadataOnly.preparedForbiddenCount()).isEqualTo(1L);
        assertThat(metadataOnly.retainedCoordinateCount()).isEqualTo(3L);
        assertThat(metadataOnly.retainedMetadataCount()).isEqualTo(1);
        assertThat(metadataOnly.retainedBufferCount()).isZero();
        for (int repeat = 0; repeat < 2; repeat++) {
            assertConstraints(rules.baseConstraints(List.of(near), 50),
                    metadataOnly.prepareIntersecting(List.of(near), 50, query));
        }
        assertThat(metadataOnly.preparedForbiddenCount()).isEqualTo(3L);
        assertThat(metadataOnly.retainedCoordinateCount()).isEqualTo(3L);
        for (long budget : new long[] {0, 2}) {
            PreparedValidationConstraints tooSmall = new PreparedValidationConstraints(rules, 4, 4, budget);
            for (int repeat = 0; repeat < 2; repeat++) {
                assertConstraints(rules.baseConstraints(List.of(near), 50),
                        tooSmall.prepareIntersecting(List.of(near), 50, query));
            }
            assertThat(tooSmall.preparedForbiddenCount()).isEqualTo(2L);
            assertThat(tooSmall.retainedSourceCount()).isZero();
            assertThat(tooSmall.retainedMetadataCount()).isZero();
            assertThat(tooSmall.retainedBufferCount()).isZero();
            assertThat(tooSmall.retainedCoordinateCount()).isZero();
        }
    }

    @Test
    void unsupportedPrecisionFactoryCoordinatesAndIdsChooseStandaloneFallback() throws Exception {
        List<Geometry> unsupported = new ArrayList<>();
        for (PrecisionModel precision : List.of(new PrecisionModel(1), new PrecisionModel(PrecisionModel.FLOATING_SINGLE))) {
            unsupported.add(new WKTReader(new GeometryFactory(precision)).read(BUILDING));
        }
        GeometryFactory customFactory = new GeometryFactory() { };
        unsupported.add(customFactory.createPoint(new Coordinate(1000, 0)));
        unsupported.add(new GeometryFactory().createLineString(new Coordinate[] {
            new Coordinate(1000, 0), new Coordinate(Double.NaN, 1)
        }));
        for (Geometry geometry : unsupported) {
            assertThat(prepared.supports(List.of(feature("unsupported", "park", geometry)))).isFalse();
        }
        for (String id : new String[] {null, "", " "}) {
            assertThat(prepared.supports(List.of(feature(id, "park", "POINT (0 0)")))).isFalse();
        }
        LineString route = line("LINESTRING (-30 0, 30 0)");
        for (Geometry geometry : unsupported.subList(0, 2)) {
            assertValidatorFallback(rules, List.of(feature("fixed-or-single", "park", geometry)), route);
        }
        assertThat(prepared.preparedForbiddenCount()).isZero();
        assertThat(prepared.retainedSourceCount()).isZero();
    }

    @Test
    void customNonlocalAndThrowingBuffersCannotBeHiddenByEnvelopeSkipping() throws Exception {
        LineString route = line("LINESTRING (-10 0, 10 0)");
        Polygon remote = (Polygon) new WKTReader().read("POLYGON ((995 -5, 1005 -5, 1005 5, 995 5, 995 -5))");
        Geometry nonlocal = new WKTReader().read(BUILDING);
        AtomicInteger buffers = new AtomicInteger();
        for (boolean fail : new boolean[] {false, true}) {
            Polygon custom = new Polygon(remote.getExteriorRing(), null, remote.getFactory()) {
                @Override public Geometry buffer(double distance, int quadrantSegments) {
                    buffers.incrementAndGet();
                    if (fail) throw new IllegalStateException("custom buffer failure");
                    return nonlocal.copy();
                }
            };
            List<ImportedOfficialFeature> features = List.of(feature("custom", "park", custom));
            assertThat(prepared.supports(features)).isFalse();
            OfficialRouteValidator validator = new OfficialRouteValidator(rules);
            OfficialRouteValidator.ValidationSession calculation = validator.forCalculation();
            RouteEdge edge = edge(route, 50);
            if (fail) {
                assertThatThrownBy(() -> validator.validate(nodes(route), List.of(edge), features))
                        .isInstanceOf(IllegalStateException.class).hasMessage("custom buffer failure");
                for (int repeat = 0; repeat < 2; repeat++) {
                    assertThatThrownBy(() -> calculation.validate(nodes(route), List.of(edge), features))
                            .isInstanceOf(IllegalStateException.class).hasMessage("custom buffer failure");
                }
            } else {
                List<RouteValidationIssue> expected = validator.validate(nodes(route), List.of(edge), features);
                assertThat(expected).extracting(RouteValidationIssue::getCode).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
                for (int repeat = 0; repeat < 2; repeat++) {
                    assertThat(calculation.validate(nodes(route), List.of(edge), features))
                            .usingRecursiveComparison().isEqualTo(expected);
                }
            }
        }
        assertThat(buffers.get()).isEqualTo(6);
    }

    @Test
    void ruleOverridesStillRunThroughStandaloneValidation() throws Exception {
        ImportedOfficialFeature additional = feature("injected", "park", BUILDING);
        AtomicInteger calls = new AtomicInteger();
        OfficialRouteGeometryRules custom = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()) {
            @Override List<Constraint> baseConstraints(List<ImportedOfficialFeature> features, int diameter) {
                calls.incrementAndGet();
                List<ImportedOfficialFeature> complete = new ArrayList<>(features);
                complete.add(additional);
                return super.baseConstraints(complete, diameter);
            }
        };
        assertThat(new PreparedValidationConstraints(custom).supports(List.of())).isFalse();
        assertThat(assertValidatorFallback(custom, List.of(), line("LINESTRING (-10 0, 10 0)")))
                .extracting(RouteValidationIssue::getMessage).containsExactly("Route violates park clearance at injected");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void customCatalogOrCrossingDependencyAlsoDisablesPreparation() throws Exception {
        List<ImportedOfficialFeature> features = List.of(feature("block", "park", BUILDING));
        for (OfficialRouteGeometryRules custom : List.of(
                new OfficialRouteGeometryRules(new OfficialConstraintCatalog() { }, new OfficialCrossingGeometry()),
                new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry() { }))) {
            PreparedValidationConstraints fallback = new PreparedValidationConstraints(custom);
            assertThat(custom.getClass()).isEqualTo(OfficialRouteGeometryRules.class);
            assertThat(fallback.supports(features)).isFalse();
            for (int repeat = 0; repeat < 2; repeat++) {
                // Полный fallback сохраняет даже далёкое ограничение и исходную source-геометрию.
                List<Constraint> actual = fallback.prepareIntersecting(features, 50, new Envelope(990, 1010, -10, 10));
                assertConstraints(custom.baseConstraints(features, 50), actual);
                assertThat(actual.get(0).source()).isSameAs(features.get(0).getMetricGeometry());
            }
            assertThat(fallback.retainedSourceCount()).isZero();
            assertThat(fallback.retainedMetadataCount()).isZero();
            assertThat(fallback.retainedBufferCount()).isZero();
            assertThat(fallback.retainedCoordinateCount()).isZero();
            assertThat(fallback.preparedForbiddenCount()).isZero();
            assertThat(assertValidatorFallback(custom, features, line("LINESTRING (-10 0, 10 0)")))
                    .extracting(RouteValidationIssue::getCode).containsExactly("FORBIDDEN_CLEARANCE_VIOLATION");
        }
    }

    private List<RouteValidationIssue> assertIssues(List<ImportedOfficialFeature> features, int diameter,
            LineString route, List<Constraint> selected) {
        return assertIssuesAgainst(rules.baseConstraints(features, diameter), diameter, route, selected);
    }

    private List<RouteValidationIssue> assertIssuesAgainst(List<Constraint> full, int diameter,
            LineString route, List<Constraint> selected) {
        RouteEdge edge = edge(route, diameter);
        List<RouteValidationIssue> expected = rules.validate(edge, route, full);
        List<RouteValidationIssue> actual = rules.validate(edge, route, selected);
        assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
        return actual;
    }

    private void assertConstraints(List<Constraint> expected, List<Constraint> actual) {
        assertThat(actual).hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            Constraint left = expected.get(index), right = actual.get(index);
            assertThat(right.id()).isEqualTo(left.id());
            assertThat(right.type()).isEqualTo(left.type());
            assertThat(right.source().equalsExact(left.source())).isTrue();
            if (left.blocked() == null) assertThat(right.blocked()).isNull();
            else assertThat(right.blocked().equalsExact(left.blocked())).isTrue();
        }
    }

    private List<RouteValidationIssue> assertValidatorFallback(OfficialRouteGeometryRules validationRules,
            List<ImportedOfficialFeature> features, LineString route) {
        OfficialRouteValidator validator = new OfficialRouteValidator(validationRules);
        OfficialRouteValidator.ValidationSession calculation = validator.forCalculation();
        List<RouteEdge> edges = List.of(edge(route, 50));
        List<RouteValidationIssue> expected = validator.validate(nodes(route), edges, features);
        for (int repeat = 0; repeat < 2; repeat++) {
            assertThat(calculation.validate(nodes(route), edges, features)).usingRecursiveComparison().isEqualTo(expected);
        }
        return expected;
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt) throws Exception {
        return feature(id, type, new WKTReader().read(wkt));
    }

    private ImportedOfficialFeature feature(String id, String type, Geometry geometry) {
        return new ImportedOfficialFeature(id, "restriction", mapper.createObjectNode().put("restriction_type", type), geometry);
    }

    private LineString line(String wkt) throws Exception { return (LineString) new WKTReader().read(wkt); }

    private RouteEdge edge(LineString line, int diameter) {
        List<RouteCoordinate> coordinates = new ArrayList<>();
        for (Coordinate coordinate : line.getCoordinates()) coordinates.add(new RouteCoordinate(coordinate.x, coordinate.y));
        return new RouteEdge("route", "root", "end", line.getLength(), coordinates, List.of(), null, diameter);
    }

    private List<RouteNode> nodes(LineString line) {
        Coordinate start = line.getCoordinateN(0), end = line.getCoordinateN(line.getNumPoints() - 1);
        return List.of(new RouteNode("root", "existing_chamber", new RouteCoordinate(start.x, start.y), true, true, 2, null),
                new RouteNode("end", "technical_node", new RouteCoordinate(end.x, end.y), false, false, 0, null));
    }

    private void shiftX(Geometry geometry, double distance) {
        geometry.apply(new CoordinateSequenceFilter() {
            public void filter(CoordinateSequence sequence, int index) {
                sequence.setOrdinate(index, 0, sequence.getX(index) + distance);
            }
            public boolean isDone() { return false; }
            public boolean isGeometryChanged() { return true; }
        });
    }

    /** Старый компонент допускает rules-наследника: считаем только успешно построенные реальные buffer. */
    private static final class CountingRules extends OfficialRouteGeometryRules {
        private long preparedForbidden;
        private CountingRules() { super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry()); }
        @Override List<Constraint> baseConstraints(List<ImportedOfficialFeature> features, int diameter) {
            List<Constraint> compiled = super.baseConstraints(features, diameter);
            preparedForbidden += compiled.stream().filter(constraint -> constraint.blocked() != null).count();
            return compiled;
        }
    }
}
