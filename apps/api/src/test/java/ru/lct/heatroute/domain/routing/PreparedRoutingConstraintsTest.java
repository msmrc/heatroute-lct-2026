package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class PreparedRoutingConstraintsTest {
    private static final int[] DIAMETERS = {
        50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400
    };
    private final OfficialRouteGeometryRules reference = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final CountingRules rules = new CountingRules();
    private final PreparedRoutingConstraints prepared = new PreparedRoutingConstraints(rules);
    private final ObjectMapper mapper = new ObjectMapper();
    private final WKTReader reader = new WKTReader();

    @Test
    void matchesFreshPreparationForEveryOfficialDiameterAndReusesOnlyThreeOksClearances() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("z", "park", "POLYGON ((0 0, 8 0, 8 8, 0 8, 0 0))"),
                feature("b", "oks", "POLYGON ((20 20, 24 20, 24 24, 20 24, 20 20))"),
                feature("a", "road", "LINESTRING (40 0, 40 80)"),
                feature("n", "heat_network", "LINESTRING (0 -20, 80 -20)"));

        for (int diameter : DIAMETERS) {
            assertEquivalent(reference.baseConstraints(features, diameter), prepared.prepare(features, diameter));
        }

        assertThat(rules.compilations).isEqualTo(6);
        assertThat(prepared.retainedEntryCount()).isEqualTo(6);
    }

    @Test
    void doesNotRetainOrCompareIrrelevantAttributesAndOwnsSourceCoordinates() throws Exception {
        ImportedOfficialFeature first = feature("a", "park", "LINESTRING (0 0, 10 0)");
        ObjectNode attributes = (ObjectNode) first.getAttributes();
        attributes.put("large_metadata", "arbitrary metadata".repeat(10000));
        first.getMetricGeometry().setUserData(attributes);
        List<Constraint> initial = prepared.prepare(List.of(first), 100);
        ImportedOfficialFeature equal = feature("a", "park", "LINESTRING (0 0, 10 0)");

        assertThat(prepared.prepare(List.of(equal), 100).get(0)).isSameAs(initial.get(0));
        assertThat(initial.get(0).source()).isNotSameAs(first.getMetricGeometry());
        assertThat(initial.get(0).source().getUserData()).isNull();
        attributes.put("restriction_type", "road");
        shiftX(first.getMetricGeometry(), 40);

        List<Constraint> changed = prepared.prepare(List.of(first), 100);
        assertEquivalent(reference.baseConstraints(List.of(first), 100), changed);
        assertThat(changed.get(0).type()).isEqualTo("road");
        assertThat(initial.get(0).type()).isEqualTo("park");
        assertThat(initial.get(0).source().getCoordinate().x).isZero();
        assertThat(rules.compilations).isEqualTo(2);
    }

    @Test
    void invalidatesSameKeyAfterMutationOfBorrowedGeometry() throws Exception {
        ImportedOfficialFeature feature = feature("same", "park", "LINESTRING (0 0, 10 0)");
        Constraint first = prepared.prepare(List.of(feature), 100).get(0);
        long coordinates = prepared.retainedCoordinateCount();
        shiftX(feature.getMetricGeometry(), 30);

        List<Constraint> changed = prepared.prepare(List.of(feature), 100);
        assertEquivalent(reference.baseConstraints(List.of(feature), 100), changed);
        assertThat(changed.get(0)).isNotSameAs(first);
        assertThat(first.source().getCoordinate().x).isZero();
        assertThat(prepared.retainedCoordinateCount()).isEqualTo(coordinates);
        assertThat(prepared.retainedEntryCount()).isEqualTo(1);
        assertThat(rules.compilations).isEqualTo(2);
    }

    @Test
    void changesResolvedTypeWhenObjectTypeOrRestrictionTypeChanges() throws Exception {
        ImportedOfficialFeature park = feature("same", "park", "POINT (0 0)");
        ImportedOfficialFeature building = new ImportedOfficialFeature(
                "same", "oks_existing", mapper.createObjectNode(), park.getMetricGeometry());
        ImportedOfficialFeature network = new ImportedOfficialFeature(
                "same", "heat_network", mapper.createObjectNode(), park.getMetricGeometry());
        for (ImportedOfficialFeature feature : List.of(park, building, network)) {
            assertEquivalent(reference.baseConstraints(List.of(feature), 100), prepared.prepare(List.of(feature), 100));
        }
        ((ObjectNode) park.getAttributes()).put("restriction_type", "road");
        assertEquivalent(reference.baseConstraints(List.of(park), 100), prepared.prepare(List.of(park), 100));
        assertThat(rules.compilations).isEqualTo(4);
    }

    @Test
    void preservesEveryDuplicateAndStableTieOrderIncludingSameIdDifferentGeometry() throws Exception {
        ImportedOfficialFeature first = feature("same", "park", "POINT (0 0)");
        ImportedOfficialFeature second = feature("same", "park", "POINT (20 0)");
        ImportedOfficialFeature earlier = feature("a", "park", "POINT (40 0)");
        for (List<ImportedOfficialFeature> input : List.of(
                List.of(first, first, second, earlier, first),
                List.of(second, earlier, first, second, second))) {
            List<Constraint> actual = prepared.prepare(input, 100);
            assertEquivalent(reference.baseConstraints(input, 100), actual);
            assertThat(actual).hasSize(input.size());
            actual.clear();
        }
        assertThat(prepared.retainedEntryCount()).isEqualTo(2);
    }

    @Test
    void comparesZAndMAsWellAsXyAndSequenceLayout() throws Exception {
        for (String wkt : List.of(
                "POINT ZM (1 2 3 4)", "POINT ZM (1 2 5 4)", "POINT ZM (1 2 5 6)",
                "POINT Z (1 2 5)", "POINT M (1 2 5)", "POINT (1 2)")) {
            ImportedOfficialFeature feature = feature("same", "road", wkt);
            List<Constraint> actual = prepared.prepare(List.of(feature), 100);
            assertEquivalent(reference.baseConstraints(List.of(feature), 100), actual);
        }
        assertThat(rules.compilations).isEqualTo(6);
        assertThat(prepared.retainedEntryCount()).isEqualTo(1);
    }

    @Test
    void comparesSridFactoryPrecisionAndGeometryType() throws Exception {
        Geometry first = reader.read("LINESTRING (0 0, 10 0)");
        Geometry otherSrid = first.copy();
        otherSrid.setSRID(32637);
        Geometry otherFactory = new WKTReader(new GeometryFactory(new PrecisionModel(), 4326))
                .read("LINESTRING (0 0, 10 0)");
        otherFactory.setSRID(32637);
        Geometry fixedPrecision = new WKTReader(new GeometryFactory(new PrecisionModel(10), 32637))
                .read("LINESTRING (0 0, 10 0)");
        Geometry multi = reader.read("MULTILINESTRING ((0 0, 10 0))");
        for (Geometry geometry : List.of(first, otherSrid, otherFactory, fixedPrecision, multi)) {
            ImportedOfficialFeature feature = new ImportedOfficialFeature(
                    "same", "restriction", mapper.createObjectNode().put("restriction_type", "park"), geometry);
            assertEquivalent(reference.baseConstraints(List.of(feature), 100), prepared.prepare(List.of(feature), 100));
        }
        assertThat(rules.compilations).isEqualTo(5);
    }

    @Test
    void comparesAllRingsAndCollectionMembersIncludingTheirExtraOrdinates() throws Exception {
        for (String wkt : List.of(
                "GEOMETRYCOLLECTION Z (POINT (20 20 1), POLYGON ((0 0 1, 8 0 1, 8 8 1, 0 8 1, 0 0 1),"
                        + "(2 2 1, 3 2 1, 3 3 1, 2 3 1, 2 2 1)))",
                "GEOMETRYCOLLECTION Z (POINT (20 20 1), POLYGON ((0 0 1, 8 0 1, 8 8 1, 0 8 1, 0 0 1),"
                        + "(2 2 9, 3 2 1, 3 3 1, 2 3 1, 2 2 9)))",
                "GEOMETRYCOLLECTION Z (POINT (20 20 7), POLYGON ((0 0 1, 8 0 1, 8 8 1, 0 8 1, 0 0 1),"
                        + "(2 2 9, 3 2 1, 3 3 1, 2 3 1, 2 2 9)))")) {
            ImportedOfficialFeature feature = feature("same", "road", wkt);
            assertEquivalent(reference.baseConstraints(List.of(feature), 100), prepared.prepare(List.of(feature), 100));
            prepared.prepare(List.of(feature), 100);
        }
        assertThat(rules.compilations).isEqualTo(3);
    }

    @Test
    void keepsOriginalInvalidDiameterAndEmptyGeometryBehaviorEvenAfterAHit() throws Exception {
        ImportedOfficialFeature building = feature("building", "oks", "POINT (0 0)");
        prepared.prepare(List.of(building), 100);
        assertThatThrownBy(() -> prepared.prepare(List.of(building), 101))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("new-network diameter must be an official DU");
        List<ImportedOfficialFeature> ignoredOrIndependent = List.of(
                feature("road", "road", "POINT (0 0)"), feature("park", "park", "POINT (0 0)"),
                feature("empty", "oks", "POLYGON EMPTY"), feature("unknown", "unknown", "POINT (0 0)"),
                new ImportedOfficialFeature("null", "oks_existing", null, null),
                new ImportedOfficialFeature("consumer", "consumer", null, reader.read("POINT (0 0)")));
        for (int diameter : new int[] {0, 101, -1}) {
            assertEquivalent(reference.baseConstraints(ignoredOrIndependent, diameter),
                    prepared.prepare(ignoredOrIndependent, diameter));
        }
        assertThat(prepared.prepare(List.of(), 0)).isEmpty();
    }

    @Test
    void appliesEntryLimitWithLruEvictionWithoutDroppingReturnedConstraints() throws Exception {
        PreparedRoutingConstraints bounded = new PreparedRoutingConstraints(rules, 2, 10000);
        ImportedOfficialFeature a = feature("a", "park", "POINT (0 0)");
        ImportedOfficialFeature b = feature("b", "park", "POINT (20 0)");
        ImportedOfficialFeature c = feature("c", "park", "POINT (40 0)");
        bounded.prepare(List.of(a, b), 100);
        bounded.prepare(List.of(a), 100);
        bounded.prepare(List.of(c), 100);
        bounded.prepare(List.of(a), 100);
        assertThat(rules.compilations).isEqualTo(3);
        bounded.prepare(List.of(b), 100);
        assertThat(rules.compilations).isEqualTo(4);
        assertEquivalent(reference.baseConstraints(List.of(a, b, c, a), 100), bounded.prepare(List.of(a, b, c, a), 100));
        assertThat(bounded.retainedEntryCount()).isEqualTo(2);
    }

    @Test
    void countsSourceBufferAndIndexReserveAndDoesNotRetainOversizedReplacements() throws Exception {
        ImportedOfficialFeature point = feature("same", "park", "POINT (0 0)");
        Constraint expected = reference.baseConstraints(List.of(point), 100).get(0);
        long weight = (long) expected.source().getNumPoints() + 4L * expected.blocked().getNumPoints();
        PreparedRoutingConstraints bounded = new PreparedRoutingConstraints(rules, 8, weight);
        bounded.prepare(List.of(point), 100);
        assertThat(bounded.retainedCoordinateCount()).isEqualTo(weight);
        ImportedOfficialFeature other = feature("other", "park", "POINT (20 0)");
        assertEquivalent(reference.baseConstraints(List.of(point, other), 100), bounded.prepare(List.of(point, other), 100));
        assertThat(bounded.retainedEntryCount()).isEqualTo(1);
        bounded.prepare(List.of(point), 100);
        ImportedOfficialFeature oversized = feature("same", "park", "LINESTRING (0 0, 10 0, 10 10, 20 10)");
        int before = rules.compilations;
        for (int repeat = 0; repeat < 2; repeat++) {
            assertEquivalent(reference.baseConstraints(List.of(oversized), 100), bounded.prepare(List.of(oversized), 100));
            assertThat(bounded.retainedCoordinateCount()).isZero();
            assertThat(bounded.retainedEntryCount()).isZero();
        }
        assertThat(rules.compilations).isEqualTo(before + 2);
    }

    @Test
    void reservesOwnedGeometryAndRingEndpointsWithoutEagerIndexCreation() throws Exception {
        ImportedOfficialFeature feature = feature("reserve", "park", "POINT (0 0)");
        Constraint constraint = prepared.prepare(List.of(feature), 100).get(0);
        long reserved = (long) constraint.source().getNumPoints() + 4L * constraint.blocked().getNumPoints();
        assertThat(prepared.retainedCoordinateCount()).isEqualTo(reserved);
        java.lang.reflect.Field helper = Constraint.class.getDeclaredField("segmentIntersection");
        helper.setAccessible(true);
        assertThat(helper.get(constraint)).isNull();
        rules.segmentAllowed(new Coordinate(-100, 0), new Coordinate(100, 0), rules.index(List.of(constraint)));
        assertThat(helper.get(constraint)).isNotNull();
        assertThat(prepared.retainedCoordinateCount()).isEqualTo(reserved);
    }

    @Test
    void reservationBoundaryDeclinesRetentionButNeverDropsReturnedConstraint() throws Exception {
        ImportedOfficialFeature feature = feature("reserve", "park", "POINT (0 0)");
        Constraint expected = reference.baseConstraints(List.of(feature), 100).get(0);
        long weight = (long) expected.source().getNumPoints() + 4L * expected.blocked().getNumPoints();
        PreparedRoutingConstraints exact = new PreparedRoutingConstraints(rules, 8, weight);
        PreparedRoutingConstraints tooSmall = new PreparedRoutingConstraints(rules, 8, weight - 1);
        assertEquivalent(List.of(expected), exact.prepare(List.of(feature), 100));
        assertThat(exact.retainedEntryCount()).isEqualTo(1);
        assertThat(exact.retainedCoordinateCount()).isEqualTo(weight);
        for (int repeat = 0; repeat < 2; repeat++) {
            assertEquivalent(List.of(expected), tooSmall.prepare(List.of(feature), 100));
            assertThat(tooSmall.retainedEntryCount()).isZero();
            assertThat(tooSmall.retainedCoordinateCount()).isZero();
        }
    }

    @Test
    void reservedIndexWeightParticipatesInLruEviction() throws Exception {
        ImportedOfficialFeature a = feature("a", "park", "POINT (0 0)");
        ImportedOfficialFeature b = feature("b", "park", "POINT (20 0)");
        ImportedOfficialFeature c = feature("c", "park", "POINT (40 0)");
        Constraint sample = reference.baseConstraints(List.of(a), 100).get(0);
        long weight = (long) sample.source().getNumPoints() + 4L * sample.blocked().getNumPoints();
        PreparedRoutingConstraints bounded = new PreparedRoutingConstraints(rules, 8, weight * 2);
        Constraint originalA = bounded.prepare(List.of(a, b), 100).get(0);
        assertThat(bounded.retainedCoordinateCount()).isEqualTo(weight * 2);
        assertThat(bounded.prepare(List.of(a), 100).get(0)).isSameAs(originalA);
        assertEquivalent(reference.baseConstraints(List.of(c), 100), bounded.prepare(List.of(c), 100));
        assertThat(bounded.retainedEntryCount()).isEqualTo(2);
        assertThat(bounded.prepare(List.of(a), 100).get(0)).isSameAs(originalA);
        assertThat(rules.compilations).isEqualTo(3);
        bounded.prepare(List.of(b), 100);
        assertThat(rules.compilations).isEqualTo(4);
        assertThat(bounded.retainedCoordinateCount()).isEqualTo(weight * 2);
    }

    @Test
    void constraintsWithoutBlockedGeometryReserveOnlyTheirOwnedSource() throws Exception {
        ImportedOfficialFeature road = feature("road", "road", "LINESTRING (0 0, 20 0)");
        PreparedRoutingConstraints bounded = new PreparedRoutingConstraints(rules, 8, 2);
        Constraint constraint = bounded.prepare(List.of(road), 100).get(0);
        assertThat(constraint.blocked()).isNull();
        assertThat(constraint.segmentIndexCoordinateReservation()).isZero();
        assertThat(bounded.retainedCoordinateCount()).isEqualTo(2);
        assertThat(bounded.prepare(List.of(road), 100).get(0)).isSameAs(constraint);
    }

    @Test
    void realOversizedBufferRetainsOnlySourceAndBufferWeightAndStillBlocksTheRoute() throws Exception {
        Coordinate[] ring = new Coordinate[16385];
        for (int i = 0; i + 1 < ring.length; i++) {
            double angle = 2 * Math.PI * i / (ring.length - 1);
            ring[i] = new Coordinate(60 * Math.cos(angle), 60 * Math.sin(angle));
        }
        ring[ring.length - 1] = new Coordinate(ring[0]);
        ImportedOfficialFeature feature = new ImportedOfficialFeature("large", "restriction",
                mapper.createObjectNode().put("restriction_type", "park"), new GeometryFactory().createPolygon(ring));
        Constraint expected = reference.baseConstraints(List.of(feature), 100).get(0);
        assertThat(expected.blocked().getNumPoints()).isGreaterThan(16384);
        long weight = (long) expected.source().getNumPoints() + expected.blocked().getNumPoints();
        PreparedRoutingConstraints bounded = new PreparedRoutingConstraints(rules, 8, weight);
        Constraint actual = bounded.prepare(List.of(feature), 100).get(0);
        assertThat(actual.segmentIndexCoordinateReservation()).isZero();
        assertThat(bounded.retainedCoordinateCount()).isEqualTo(weight);
        assertThat(rules.segmentAllowed(new Coordinate(-100, 0), new Coordinate(100, 0), rules.index(List.of(actual))))
                .isFalse();
        assertThat(bounded.prepare(List.of(feature), 100).get(0)).isSameAs(actual);
        assertThat(bounded.retainedCoordinateCount()).isEqualTo(weight);
        java.lang.reflect.Field helper = Constraint.class.getDeclaredField("segmentIntersection");
        helper.setAccessible(true);
        assertThat(helper.get(actual)).isNull();
    }

    @Test
    void canDisableRetentionAndNeverSharesEntriesBetweenInstances() throws Exception {
        List<ImportedOfficialFeature> features = List.of(feature("same", "park", "POINT (0 0)"));
        for (PreparedRoutingConstraints instance : List.of(
                new PreparedRoutingConstraints(rules, 0, 10000), new PreparedRoutingConstraints(rules, 10, 0))) {
            for (int repeat = 0; repeat < 2; repeat++) {
                assertEquivalent(reference.baseConstraints(features, 100), instance.prepare(features, 100));
            }
            assertThat(instance.retainedEntryCount()).isZero();
            assertThat(instance.retainedCoordinateCount()).isZero();
        }
        prepared.prepare(features, 100);
        new PreparedRoutingConstraints(rules).prepare(features, 100);
        assertThat(rules.compilations).isEqualTo(6);
        assertThatThrownBy(() -> new PreparedRoutingConstraints(rules, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreparedRoutingConstraints(rules, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesFreshVisibilityWithPerQueryExemptionsAndEndpointApproaches() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("building", "oks", "POLYGON ((0 0, 8 0, 8 8, 0 8, 0 0))"),
                feature("park", "park", "POLYGON ((20 0, 25 0, 25 10, 20 10, 20 0))"),
                feature("road", "road", "LINESTRING (-10 -10, 30 30)"));
        Random random = new Random(49001);
        for (int diameter : new int[] {100, 500, 900}) {
            for (int trial = 0; trial < 150; trial++) {
                Coordinate start = new Coordinate(random.nextDouble() * 60 - 20, random.nextDouble() * 40 - 15);
                Coordinate end = new Coordinate(random.nextDouble() * 60 - 20, random.nextDouble() * 40 - 15);
                Set<String> exemptions = trial % 3 == 0 ? Set.of("building") : Set.of();
                List<Constraint> expected = reference.constraints(features, diameter, exemptions, start, end);
                List<Constraint> actual = rules.applicableConstraints(
                        prepared.prepare(features, diameter), exemptions, start, end);
                assertEquivalent(expected, actual);
                assertThat(rules.segmentAllowed(start, end, rules.index(actual)))
                        .isEqualTo(reference.segmentAllowed(start, end, reference.index(expected)));
                assertThat(rules.pointInsideForbiddenClearance(start, rules.index(actual)))
                        .isEqualTo(reference.pointInsideForbiddenClearance(start, reference.index(expected)));
            }
        }
        assertThat(rules.compilations).isEqualTo(5);
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction",
                mapper.createObjectNode().put("restriction_type", type), reader.read(wkt));
    }

    private void assertEquivalent(List<Constraint> expected, List<Constraint> actual) {
        assertThat(actual).hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            Constraint left = expected.get(index);
            Constraint right = actual.get(index);
            assertThat(right.id()).isEqualTo(left.id());
            assertThat(right.type()).isEqualTo(left.type());
            assertThat(right.rule()).isSameAs(left.rule());
            assertGeometry(left.source(), right.source());
            assertGeometry(left.blocked(), right.blocked());
            assertThat(right.preparedBlocked() == null).isEqualTo(left.preparedBlocked() == null);
        }
    }

    private void assertGeometry(Geometry expected, Geometry actual) {
        if (expected == null) {
            assertThat(actual).isNull();
            return;
        }
        assertThat(actual).isNotNull();
        assertThat(actual.equalsExact(expected)).isTrue();
        assertThat(actual.getSRID()).isEqualTo(expected.getSRID());
        assertThat(actual.getFactory().getSRID()).isEqualTo(expected.getFactory().getSRID());
        assertThat(actual.getPrecisionModel()).isEqualTo(expected.getPrecisionModel());
        assertThat(sequenceBits(actual)).containsExactlyElementsOf(sequenceBits(expected));
    }

    private List<Long> sequenceBits(Geometry geometry) {
        List<Long> result = new ArrayList<>();
        geometry.apply(new CoordinateSequenceFilter() {
            public void filter(CoordinateSequence sequence, int index) {
                result.add((long) sequence.getDimension());
                result.add((long) sequence.getMeasures());
                for (int ordinate = 0; ordinate < sequence.getDimension(); ordinate++) {
                    result.add(Double.doubleToRawLongBits(sequence.getOrdinate(index, ordinate)));
                }
            }
            public boolean isDone() { return false; }
            public boolean isGeometryChanged() { return false; }
        });
        return result;
    }

    private void shiftX(Geometry geometry, double offset) {
        geometry.apply(new CoordinateSequenceFilter() {
            public void filter(CoordinateSequence sequence, int index) {
                sequence.setOrdinate(index, 0, sequence.getX(index) + offset);
            }
            public boolean isDone() { return false; }
            public boolean isGeometryChanged() { return true; }
        });
    }

    private static final class CountingRules extends OfficialRouteGeometryRules {
        private int compilations;

        private CountingRules() {
            super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        }

        @Override
        List<Constraint> baseConstraints(List<ImportedOfficialFeature> features, int diameter) {
            compilations++;
            return super.baseConstraints(features, diameter);
        }
    }
}
