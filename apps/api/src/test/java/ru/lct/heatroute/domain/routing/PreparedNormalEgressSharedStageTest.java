package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.NormalEgress;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class PreparedNormalEgressSharedStageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sharedRawAndPaddedOrdersMatchStandaloneForPolygonVariants() throws Exception {
        for (Scenario scenario : List.of(
                new Scenario("convex", "POLYGON ((0 0,20 0,20 20,0 20,0 0))", new Coordinate(10, 10, 7)),
                new Scenario("concave", "POLYGON ((0 0,20 0,20 20,8 20,8 4,4 4,4 20,0 20,0 0))",
                        new Coordinate(2, 10, 7)),
                new Scenario("hole", "POLYGON ((0 0,20 0,20 20,0 20,0 0),(8 8,12 8,12 12,8 12,8 8))",
                        new Coordinate(3, 10, 7)),
                new Scenario("multipolygon", "MULTIPOLYGON (((0 0,20 0,20 20,0 20,0 0)),"
                        + "((40 0,60 0,60 20,40 20,40 0)))", new Coordinate(10, 10, 7)))) {
            List<ImportedOfficialFeature> features = List.of(feature(scenario.name, scenario.wkt));
            for (RouteTraversal traversal : RouteTraversal.values()) {
                OfficialRouteGeometryRules rules = rules();
                List<NormalEgress> expectedRaw = rules.prepareNearestLegalNormalEgresses(
                        features, 100, scenario.point, traversal);
                List<NormalEgress> expectedPadded = rules.prepareNormalEgresses(
                        features, 100, scenario.point, traversal);
                assertThat(expectedRaw).as(scenario.name + " raw " + traversal).isNotEmpty();
                assertThat(expectedPadded).as(scenario.name + " padded " + traversal).isNotEmpty();
                for (boolean paddedFirst : List.of(false, true)) {
                    PreparedNormalEgressMemo memo = new PreparedNormalEgressMemo(rules);
                    if (paddedFirst) {
                        assertEgresses(expectedPadded, memo.prepare(features, 100, scenario.point, traversal));
                        assertEgresses(expectedRaw, memo.prepareRawNearest(features, 100, scenario.point, traversal));
                    } else {
                        assertEgresses(expectedRaw, memo.prepareRawNearest(features, 100, scenario.point, traversal));
                        assertEgresses(expectedPadded, memo.prepare(features, 100, scenario.point, traversal));
                    }
                    assertThat(retained(memo)).hasSize(1);
                }
            }
        }
    }

    @Test
    void paddedFirstKeepsOneRawStageAndReturnsDefensiveContainers() throws Exception {
        OfficialRouteGeometryRules rules = rules();
        List<ImportedOfficialFeature> features = List.of(feature("own", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
        PreparedNormalEgressMemo memo = new PreparedNormalEgressMemo(rules);
        Coordinate point = new Coordinate(10, 10, 7);

        List<NormalEgress> padded = memo.prepare(features, 100, point, RouteTraversal.AS_GIVEN);
        Object entry = onlyEntry(memo);
        List<NormalEgress> cachedRaw = listField(entry, "rawEgresses");
        List<NormalEgress> cachedPadded = listField(entry, "egresses");
        assertThat(cachedRaw).isNotEmpty();
        assertThat(cachedPadded).isNotNull();
        assertThat(retainedCoordinates(memo)).isEqualTo(2L * (cachedRaw.size() + cachedPadded.size()));

        List<NormalEgress> raw = memo.prepareRawNearest(features, 100, point, RouteTraversal.AS_GIVEN);
        assertThat(retained(memo)).hasSize(1);
        for (int index = 0; index < raw.size(); index++) {
            assertThat(raw.get(index)).isSameAs(cachedRaw.get(index));
            assertThat(padded.get(index)).isSameAs(cachedPadded.get(index));
        }
        raw.clear();
        padded.clear();
        assertThat(memo.prepareRawNearest(features, 100, point, RouteTraversal.AS_GIVEN)).isNotEmpty();
        assertThat(memo.prepare(features, 100, point, RouteTraversal.AS_GIVEN)).isNotEmpty();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> memo.prepareRawNearest(features, 100, point, RouteTraversal.AS_GIVEN))
                    .isInstanceOf(CancellationException.class);
            assertThatThrownBy(() -> memo.prepare(features, 100, point, RouteTraversal.AS_GIVEN))
                    .isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void coordinateBudgetAccountsForUpgradeAndKeepsRawWhenPaddedIsOversized() throws Exception {
        OfficialRouteGeometryRules rules = rules();
        List<ImportedOfficialFeature> features = List.of(feature("own", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
        Coordinate point = new Coordinate(10, 10);
        List<NormalEgress> oracleRaw = rules.prepareNearestLegalNormalEgresses(
                features, 100, point, RouteTraversal.AS_GIVEN);
        List<NormalEgress> oraclePadded = rules.prepareNormalEgresses(
                features, 100, point, RouteTraversal.AS_GIVEN);
        assertThat(oracleRaw).isNotEmpty();
        long rawCoordinates = 2L * oracleRaw.size();
        long combinedCoordinates = rawCoordinates + 2L * oraclePadded.size();

        PreparedNormalEgressMemo oversized = new PreparedNormalEgressMemo(rules, 4, 100, 1,
                combinedCoordinates - 1);
        List<NormalEgress> raw = oversized.prepareRawNearest(features, 100, point, RouteTraversal.AS_GIVEN);
        Object rawEntry = onlyEntry(oversized);
        assertThat(listField(rawEntry, "egresses")).isNull();
        assertThat(retainedCoordinates(oversized)).isEqualTo(rawCoordinates);
        assertEgresses(oraclePadded, oversized.prepare(features, 100, point, RouteTraversal.AS_GIVEN));
        assertThat(onlyEntry(oversized)).isSameAs(rawEntry);
        assertThat(retainedCoordinates(oversized)).isEqualTo(rawCoordinates);
        List<NormalEgress> reusedRaw = oversized.prepareRawNearest(features, 100, point, RouteTraversal.AS_GIVEN);
        assertThat(reusedRaw.get(0)).isSameAs(raw.get(0));

        PreparedNormalEgressMemo fitting = new PreparedNormalEgressMemo(rules, 4, 100, 1, combinedCoordinates);
        List<NormalEgress> beforeUpgrade = fitting.prepareRawNearest(features, 100, point, RouteTraversal.AS_GIVEN);
        fitting.prepare(features, 100, point, RouteTraversal.AS_GIVEN);
        Object upgraded = onlyEntry(fitting);
        assertThat(listField(upgraded, "egresses")).isNotNull();
        assertThat(listField(upgraded, "rawEgresses").get(0)).isSameAs(beforeUpgrade.get(0));
        assertThat(retainedCoordinates(fitting)).isEqualTo(combinedCoordinates);
        fitting.prepareRawNearest(features, 100, new Coordinate(9, 10), RouteTraversal.AS_GIVEN);
        assertThat(retained(fitting)).hasSize(1);
        assertThat(onlyEntry(fitting)).isNotSameAs(upgraded);
        assertThat(retainedCoordinates(fitting)).isLessThanOrEqualTo(combinedCoordinates);
    }

    @Test
    void snapshotMutationOrderAndExactKeysCannotReuseSharedStage() throws Exception {
        OfficialRouteGeometryRules rules = rules();
        ImportedOfficialFeature own = feature("own", "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        ImportedOfficialFeature remote = feature("remote", "POLYGON ((100 0,120 0,120 20,100 20,100 0))");
        List<ImportedOfficialFeature> features = new ArrayList<>(List.of(own, remote));
        PreparedNormalEgressMemo memo = new PreparedNormalEgressMemo(rules);
        Coordinate z7 = new Coordinate(10, 10, 7);

        memo.prepareRawNearest(features, 100, z7, RouteTraversal.AS_GIVEN);
        memo.prepareRawNearest(features, 100, new Coordinate(10, 10, 8), RouteTraversal.AS_GIVEN);
        memo.prepareRawNearest(features, 50, z7, RouteTraversal.AS_GIVEN);
        memo.prepareRawNearest(features, 100, z7, RouteTraversal.REVERSED);
        memo.prepareRawNearest(features, 100, new Coordinate(Math.nextUp(10.0), 10, 7), RouteTraversal.AS_GIVEN);
        memo.prepareRawNearest(features, 100, new Coordinate(10, Math.nextUp(10.0), 7), RouteTraversal.AS_GIVEN);
        assertThat(retained(memo)).hasSize(6);

        ((ObjectNode) own.getAttributes()).put("shared_stage_marker", "changed");
        assertEgresses(rules.prepareNearestLegalNormalEgresses(features, 100, z7, RouteTraversal.AS_GIVEN),
                memo.prepareRawNearest(features, 100, z7, RouteTraversal.AS_GIVEN));
        assertThat(retained(memo)).hasSize(1);

        Object beforeGeometryMutation = onlyEntry(memo);
        remote.getMetricGeometry().apply((CoordinateFilter) coordinate -> coordinate.x += 1);
        remote.getMetricGeometry().geometryChanged();
        assertEgresses(rules.prepareNearestLegalNormalEgresses(features, 100, z7, RouteTraversal.AS_GIVEN),
                memo.prepareRawNearest(features, 100, z7, RouteTraversal.AS_GIVEN));
        assertThat(retained(memo)).hasSize(1);
        assertThat(onlyEntry(memo)).isNotSameAs(beforeGeometryMutation);

        Object beforeOrderChange = onlyEntry(memo);
        Collections.reverse(features);
        assertEgresses(rules.prepareNearestLegalNormalEgresses(features, 100, z7, RouteTraversal.AS_GIVEN),
                memo.prepareRawNearest(features, 100, z7, RouteTraversal.AS_GIVEN));
        assertThat(retained(memo)).hasSize(1);
        assertThat(onlyEntry(memo)).isNotSameAs(beforeOrderChange);
    }

    @Test
    void customRulesAndProvidersKeepSeparateLegacyModeEntries() throws Exception {
        List<ImportedOfficialFeature> features = List.of(feature("own", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
        Coordinate point = new Coordinate(10, 10);

        CountingRules customRules = new CountingRules();
        PreparedNormalEgressMemo customMemo = new PreparedNormalEgressMemo(customRules);
        customMemo.prepareRawNearest(features, 100, point, RouteTraversal.AS_GIVEN);
        customMemo.prepare(features, 100, point, RouteTraversal.AS_GIVEN);
        customMemo.prepareRawNearest(features, 100, point, RouteTraversal.AS_GIVEN);
        customMemo.prepare(features, 100, point, RouteTraversal.AS_GIVEN);
        assertThat(customRules.rawPreparations).isEqualTo(1);
        assertThat(customRules.paddedPreparations).isEqualTo(1);
        assertThat(retained(customMemo)).hasSize(2);

        for (OfficialRouteGeometryRules providerRules : List.of(
                new OfficialRouteGeometryRules(new OfficialConstraintCatalog() { }, new OfficialCrossingGeometry()),
                new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry() { }))) {
            PreparedNormalEgressMemo memo = new PreparedNormalEgressMemo(providerRules);
            assertEgresses(providerRules.prepareNearestLegalNormalEgresses(features, 100, point, RouteTraversal.AS_GIVEN),
                    memo.prepareRawNearest(features, 100, point, RouteTraversal.AS_GIVEN));
            assertEgresses(providerRules.prepareNormalEgresses(features, 100, point, RouteTraversal.AS_GIVEN),
                    memo.prepare(features, 100, point, RouteTraversal.AS_GIVEN));
            assertThat(retained(memo)).hasSize(2);
        }
    }

    private static void assertEgresses(List<NormalEgress> expected, List<NormalEgress> actual) {
        assertThat(actual).hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            NormalEgress left = expected.get(index), right = actual.get(index);
            assertThat(right.oksId()).isEqualTo(left.oksId());
            assertThat(Double.doubleToRawLongBits(right.start().x))
                    .isEqualTo(Double.doubleToRawLongBits(left.start().x));
            assertThat(Double.doubleToRawLongBits(right.start().y))
                    .isEqualTo(Double.doubleToRawLongBits(left.start().y));
            assertThat(Double.doubleToRawLongBits(right.start().getZ()))
                    .isEqualTo(Double.doubleToRawLongBits(left.start().getZ()));
            assertThat(Double.doubleToRawLongBits(right.exit().x))
                    .isEqualTo(Double.doubleToRawLongBits(left.exit().x));
            assertThat(Double.doubleToRawLongBits(right.exit().y))
                    .isEqualTo(Double.doubleToRawLongBits(left.exit().y));
            assertThat(Double.doubleToRawLongBits(right.exit().getZ()))
                    .isEqualTo(Double.doubleToRawLongBits(left.exit().getZ()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> retained(PreparedNormalEgressMemo memo) throws Exception {
        return (Map<?, ?>) field(memo, "retained");
    }

    private static Object onlyEntry(PreparedNormalEgressMemo memo) throws Exception {
        assertThat(retained(memo)).hasSize(1);
        return retained(memo).values().iterator().next();
    }

    private static long retainedCoordinates(PreparedNormalEgressMemo memo) throws Exception {
        return (long) field(memo, "retainedCoordinates");
    }

    @SuppressWarnings("unchecked")
    private static List<NormalEgress> listField(Object target, String name) throws Exception {
        return (List<NormalEgress>) field(target, name);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static ImportedOfficialFeature feature(String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "oks_existing", JSON.createObjectNode(), new WKTReader().read(wkt));
    }

    private static OfficialRouteGeometryRules rules() {
        return new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    }

    private static final class Scenario {
        private final String name;
        private final String wkt;
        private final Coordinate point;

        private Scenario(String name, String wkt, Coordinate point) {
            this.name = name;
            this.wkt = wkt;
            this.point = point;
        }
    }

    private static final class CountingRules extends OfficialRouteGeometryRules {
        private int rawPreparations;
        private int paddedPreparations;

        private CountingRules() {
            super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        }

        @Override
        List<NormalEgress> prepareNearestLegalNormalEgresses(List<ImportedOfficialFeature> features,
                int diameter, Coordinate connectionPoint, RouteTraversal traversal) {
            rawPreparations++;
            return super.prepareNearestLegalNormalEgresses(features, diameter, connectionPoint, traversal);
        }

        @Override
        List<NormalEgress> prepareNormalEgresses(List<ImportedOfficialFeature> features,
                int diameter, Coordinate connectionPoint, RouteTraversal traversal) {
            paddedPreparations++;
            return super.prepareNormalEgresses(features, diameter, connectionPoint, traversal);
        }
    }
}
