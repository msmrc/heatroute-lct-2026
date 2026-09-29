package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.NormalEgress;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class PreparedNormalEgressMemoTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reusesFullPreparationWhilePreservingTargetOrderingForEqualWallsAndConcavity() throws Exception {
        CountingRules rules = new CountingRules();
        OfficialRouteGeometryRules oracle = rules();
        MutableWindowSource source = new MutableWindowSource();
        source.features.add(feature("square", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
        MemoQueries environment = new MemoQueries(source, rules);
        Coordinate point = new Coordinate(10, 10, 7);
        Coordinate left = new Coordinate(-80, 13);
        Coordinate right = new Coordinate(100, -21);

        List<NormalEgress> expectedLeft = oracle.normalEgressCandidates(source.features, 100, point, left, 0);
        List<NormalEgress> expectedRight = oracle.normalEgressCandidates(source.features, 100, point, right, 0);
        assertSameEgresses(expectedLeft, environment.normalEgressCandidates(100, point, left, 0));
        assertSameEgresses(expectedRight, environment.normalEgressCandidates(100, point, right, 0));
        assertThat(environment.normalEgressTowards(100, point, left)).isPresent();
        assertThat(rules.preparations).isEqualTo(1);

        source.features.set(0, feature("concave",
                "POLYGON ((0 0,20 0,20 20,8 20,8 4,4 4,4 20,0 20,0 0))"));
        Coordinate concavePoint = new Coordinate(3, 10, 7);
        assertSameEgresses(oracle.normalEgressCandidates(source.features, 100, concavePoint, left, 0),
                environment.normalEgressCandidates(100, concavePoint, left, 0));
        assertThat(rules.preparations).isEqualTo(2);
    }

    @Test
    void invalidatesExactWindowForAttributeGeometryMembershipAndOrderChanges() throws Exception {
        CountingRules rules = new CountingRules();
        MutableWindowSource source = new MutableWindowSource();
        ImportedOfficialFeature own = feature("own", "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        source.features.add(own);
        MemoQueries environment = new MemoQueries(source, rules);
        Coordinate point = new Coordinate(10, 10);
        Coordinate target = new Coordinate(-50, 10);

        environment.normalEgressCandidates(100, point, target, 0);
        environment.normalEgressCandidates(100, point, target, 0);
        assertThat(rules.preparations).isEqualTo(1);

        ((ObjectNode) own.getAttributes()).put("memo_marker", "changed");
        environment.normalEgressCandidates(100, point, target, 0);
        assertThat(rules.preparations).isEqualTo(2);

        Geometry geometry = own.getMetricGeometry();
        geometry.apply((CoordinateFilter) coordinate -> coordinate.x += 100);
        geometry.geometryChanged();
        environment.normalEgressCandidates(100, point, target, 0);
        assertThat(rules.preparations).isEqualTo(3);

        source.features.add(feature("remote", "POLYGON ((300 0,320 0,320 20,300 20,300 0))"));
        environment.normalEgressCandidates(100, point, target, 0);
        assertThat(rules.preparations).isEqualTo(4);
        Collections.reverse(source.features);
        environment.normalEgressCandidates(100, point, target, 0);
        assertThat(rules.preparations).isEqualTo(5);
    }

    @Test
    void separatesRawZDiameterAndTraversalKeysAndReturnsDefensiveLists() throws Exception {
        CountingRules rules = new CountingRules();
        MutableWindowSource source = new MutableWindowSource();
        source.features.add(feature("own", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
        MemoQueries environment = new MemoQueries(source, rules);
        Coordinate target = new Coordinate(-50, 10);
        Coordinate z7 = new Coordinate(10, 10, 7);
        List<NormalEgress> first = environment.normalEgressCandidates(100, z7, target, 0);
        first.clear();
        assertThat(environment.normalEgressCandidates(100, z7, target, 0)).isNotEmpty();
        assertThat(rules.preparations).isEqualTo(1);

        environment.normalEgressCandidates(100, new Coordinate(10, 10, 8), target, 0);
        environment.normalEgressCandidates(200, z7, target, 0);
        environment.normalEgressCandidates(100, z7, target, 0, RouteTraversal.REVERSED);
        assertThat(rules.preparations).isEqualTo(4);
    }

    @Test
    void productionEnvironmentPreservesTargetResultsInBothTraversalDirections() throws Exception {
        OfficialRouteGeometryRules rules = rules();
        MutableWindowSource source = new MutableWindowSource();
        source.features.add(feature("square", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
        OfficialRoutingEnvironment environment = new OfficialRoutingEnvironment(List.of(), source, rules);
        Coordinate point = new Coordinate(10, 10, 7);
        for (RouteTraversal traversal : RouteTraversal.values()) {
            for (Coordinate target : List.of(new Coordinate(-80, 13), new Coordinate(100, -21),
                    new Coordinate(10, 100), new Coordinate(10, 10))) {
                List<NormalEgress> expected = rules.normalEgressCandidates(
                        source.features, 100, point, target, 0, traversal);
                assertSameEgresses(expected, environment.normalEgressCandidates(100, point, target, 0, traversal));
                assertSameEgresses(expected.subList(0, Math.min(1, expected.size())),
                        environment.normalEgressTowards(100, point, target, traversal).stream()
                                .collect(java.util.stream.Collectors.toList()));
            }
        }
    }

    @Test
    void oversizedWindowAndReturnedResultsStayUncached() throws Exception {
        CountingRules rules = new CountingRules();
        List<ImportedOfficialFeature> features = List.of(feature("own", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
        Coordinate point = new Coordinate(10, 10);
        PreparedNormalEgressMemo oversizedWindow = new PreparedNormalEgressMemo(rules, 1, 4, 8, 100);
        oversizedWindow.prepare(features, 100, point, RouteTraversal.AS_GIVEN);
        oversizedWindow.prepare(features, 100, point, RouteTraversal.AS_GIVEN);
        assertThat(rules.preparations).isEqualTo(2);

        CountingRules boundedResultsRules = new CountingRules();
        PreparedNormalEgressMemo boundedResults = new PreparedNormalEgressMemo(
                boundedResultsRules, 1, 100, 8, 1);
        boundedResults.prepare(features, 100, point, RouteTraversal.AS_GIVEN);
        boundedResults.prepare(features, 100, point, RouteTraversal.AS_GIVEN);
        assertThat(boundedResultsRules.preparations).isEqualTo(2);
    }

    @Test
    void separatesRawNearestAndPaddedKeysWithoutAliasingCachedResults() throws Exception {
        CountingRules rules = new CountingRules();
        OfficialRouteGeometryRules oracle = rules();
        PreparedNormalEgressMemo memo = new PreparedNormalEgressMemo(rules);
        List<ImportedOfficialFeature> features = List.of(feature("own", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
        Coordinate point = new Coordinate(1, 10);
        List<NormalEgress> expectedRaw = oracle.prepareNearestLegalNormalEgresses(
                features, 50, point, RouteTraversal.REVERSED);
        List<NormalEgress> expectedPadded = oracle.prepareNormalEgresses(
                features, 50, point, RouteTraversal.REVERSED);

        List<NormalEgress> raw = memo.prepareRawNearest(features, 50, point, RouteTraversal.REVERSED);
        List<NormalEgress> padded = memo.prepare(features, 50, point, RouteTraversal.REVERSED);
        assertSameEgresses(expectedRaw, raw);
        assertSameEgresses(expectedPadded, padded);
        assertThat(padded.get(0).start().distance(padded.get(0).exit())
                - raw.get(0).start().distance(raw.get(0).exit()))
                .isCloseTo(OfficialRouteGeometryRules.NORMAL_EGRESS_MARGIN_M, offset(1e-9));
        raw.clear();
        padded.clear();
        assertThat(memo.prepareRawNearest(features, 50, point, RouteTraversal.REVERSED)).isNotEmpty();
        assertThat(memo.prepare(features, 50, point, RouteTraversal.REVERSED)).isNotEmpty();
        assertThat(rules.rawPreparations).isEqualTo(1);
        assertThat(rules.preparations).isEqualTo(1);
    }

    @Test
    void cachedPreparationObservesCancellationBeforeReturning() throws Exception {
        CountingRules rules = new CountingRules();
        PreparedNormalEgressMemo memo = new PreparedNormalEgressMemo(rules);
        List<ImportedOfficialFeature> features = List.of(feature("own", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
        Coordinate point = new Coordinate(10, 10);
        memo.prepare(features, 100, point, RouteTraversal.AS_GIVEN);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> memo.prepare(features, 100, point, RouteTraversal.AS_GIVEN))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(rules.preparations).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    private static void assertSameEgresses(List<NormalEgress> expected, List<NormalEgress> actual) {
        assertThat(actual).hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            NormalEgress left = expected.get(index), right = actual.get(index);
            assertThat(right.oksId()).isEqualTo(left.oksId());
            assertThat(right.start()).isEqualTo(left.start());
            assertThat(right.exit()).isEqualTo(left.exit());
            assertThat(Double.doubleToRawLongBits(right.start().getZ()))
                    .isEqualTo(Double.doubleToRawLongBits(left.start().getZ()));
            assertThat(Double.doubleToRawLongBits(right.exit().getZ()))
                    .isEqualTo(Double.doubleToRawLongBits(left.exit().getZ()));
        }
    }

    private static ImportedOfficialFeature feature(String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "oks_existing", MAPPER.createObjectNode(), new WKTReader().read(wkt));
    }

    private static OfficialRouteGeometryRules rules() {
        return new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    }

    private static final class CountingRules extends OfficialRouteGeometryRules {
        private int preparations;
        private int rawPreparations;
        private CountingRules() { super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry()); }

        @Override List<NormalEgress> prepareNormalEgresses(List<ImportedOfficialFeature> features,
                int diameter, Coordinate connectionPoint, RouteTraversal traversal) {
            preparations++;
            return super.prepareNormalEgresses(features, diameter, connectionPoint, traversal);
        }

        @Override List<NormalEgress> prepareNearestLegalNormalEgresses(List<ImportedOfficialFeature> features,
                int diameter, Coordinate connectionPoint, RouteTraversal traversal) {
            rawPreparations++;
            return super.prepareNearestLegalNormalEgresses(features, diameter, connectionPoint, traversal);
        }
    }

    /** Счётчик тестирует сам memo; production environment не кеширует пользовательские подклассы правил. */
    private static final class MemoQueries {
        private final MutableWindowSource source;
        private final OfficialRouteGeometryRules rules;
        private final PreparedNormalEgressMemo memo;

        private MemoQueries(MutableWindowSource source, OfficialRouteGeometryRules rules) {
            this.source = source;
            this.rules = rules;
            this.memo = new PreparedNormalEgressMemo(rules);
        }

        private List<NormalEgress> normalEgressCandidates(
                int diameter, Coordinate point, Coordinate target, double extra) {
            return normalEgressCandidates(diameter, point, target, extra, RouteTraversal.AS_GIVEN);
        }

        private List<NormalEgress> normalEgressCandidates(
                int diameter, Coordinate point, Coordinate target, double extra, RouteTraversal traversal) {
            return rules.sortNormalEgressesForTarget(memo.prepare(source.features, diameter, point, traversal), target);
        }

        private java.util.Optional<NormalEgress> normalEgressTowards(int diameter, Coordinate point, Coordinate target) {
            return normalEgressCandidates(diameter, point, target, 0).stream().findFirst();
        }
    }

    private static final class MutableWindowSource implements RoutingFeatureSource {
        private final List<ImportedOfficialFeature> features = new ArrayList<>();
        @Override public List<ImportedOfficialFeature> findInMetricWindow(Envelope bounds) {
            return new ArrayList<>(features);
        }
        @Override public List<ImportedOfficialFeature> findByFeatureIds(Set<String> ids) { return List.of(); }
    }
}
