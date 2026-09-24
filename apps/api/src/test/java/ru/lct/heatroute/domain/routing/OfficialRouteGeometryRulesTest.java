package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialRouteGeometryRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test
    void preservesPreOptimizationEgressCorpus() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("z-square", "oks_existing", null,
                        "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                feature("a-overlap", "restriction", "oks",
                        "POLYGON ((0.005 0, 20.005 0, 20.005 20, 0.005 20, 0.005 0))"),
                feature("concave", "oks_existing", null,
                        "POLYGON ((30 0, 50 0, 50 6, 36 6, 36 20, 30 20, 30 0))"),
                feature("hole", "oks_existing", null,
                        "POLYGON ((60 0, 90 0, 90 30, 60 30, 60 0),"
                                + " (70 10, 70 20, 80 20, 80 10, 70 10))"),
                feature("multi", "oks_existing", null,
                        "MULTIPOLYGON (((100 0, 120 0, 120 20, 100 20, 100 0)),"
                                + " ((100 30, 115 35, 110 50, 95 45, 100 30)))"),
                feature("ignored-future", "oks_future", null,
                        "POLYGON ((-100 -100, 200 -100, 200 100, -100 100, -100 -100))"),
                feature("park", "restriction", "park",
                        "POLYGON ((200 0, 220 0, 220 20, 200 20, 200 0))"));
        List<Coordinate> points = List.of(
                new Coordinate(10, 10), new Coordinate(0, 10), new Coordinate(0.005, 10),
                new Coordinate(0.01, 10), new Coordinate(0.0101, 10), new Coordinate(19, 10),
                new Coordinate(33, 3), new Coordinate(33, 15), new Coordinate(40, 12),
                new Coordinate(65, 15), new Coordinate(75, 15), new Coordinate(75, 10),
                new Coordinate(110, 10), new Coordinate(107, 39), new Coordinate(-1, 10));
        List<Coordinate> targets = List.of(
                new Coordinate(-50, 10), new Coordinate(150, 10), new Coordinate(30, 80));
        StringBuilder signature = new StringBuilder();
        for (int diameter : List.of(50, 900)) {
            for (Coordinate point : points) {
                append(signature, rules.normalEgress(features, diameter, point));
                for (Coordinate target : targets) {
                    for (double extraM : List.of(0.0, 60.0)) {
                        append(signature, rules.normalEgressTowards(features, diameter, point, target, extraM));
                        List<OfficialRouteGeometryRules.NormalEgress> candidates = rules.normalEgressCandidates(
                                features, diameter, point, target, extraM);
                        signature.append(candidates.size()).append(':');
                        candidates.forEach(candidate -> append(signature, Optional.of(candidate)));
                    }
                }
            }
        }
        // Снимок 6171362 до оптимизации: точные double-координаты, ID и порядок, включая пустые.
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(signature.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : digest) {
            hex.append(String.format("%02x", item & 0xff));
        }
        assertThat(hex.toString()).isEqualTo("36f868a0159fa142f80c77180e82202b2f37ac5b286742cad79a48c55097161e");
    }

    @Test
    void usesSourceFootprintsWithoutBufferingAnyFeatureForEgress() throws Exception {
        CountingPolygon own = countedSquare(0);
        CountingPolygon remote = countedSquare(200);
        List<ImportedOfficialFeature> features = List.of(
                new ImportedOfficialFeature("own", "oks_existing", mapper.createObjectNode(), own),
                new ImportedOfficialFeature("remote", "oks_existing", mapper.createObjectNode(), remote));
        Coordinate connection = new Coordinate(10, 10);
        Coordinate target = new Coordinate(-50, 10);

        assertThat(rules.normalEgress(features, 50, connection)).isPresent();
        assertThat(rules.normalEgressTowards(features, 500, connection, target)).isPresent();
        assertThat(rules.normalEgressCandidates(features, 900, connection, target, 60)).isNotEmpty();

        assertThat(own.bufferCalls).isZero();
        assertThat(remote.bufferCalls).isZero();
    }

    @Test
    void retainsLexicalTieBreakAndIgnoresInputOrder() throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>(List.of(
                feature("z", "oks_existing", null, "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                feature("a", "restriction", "oks", "POLYGON ((0.005 0, 20.005 0, 20.005 20, 0.005 20, 0.005 0))")));
        Coordinate point = new Coordinate(10, 10);
        StringBuilder before = new StringBuilder();
        append(before, rules.normalEgress(features, 50, point));
        assertThat(rules.normalEgress(features, 50, point).orElseThrow().oksId()).isEqualTo("a");
        Collections.reverse(features);
        StringBuilder after = new StringBuilder();
        append(after, rules.normalEgress(features, 50, point));
        assertThat(after.toString()).isEqualTo(before.toString());
    }

    @Test
    void preservesBoundaryToleranceAndNormalMargin() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("own", "oks_existing", null, "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        assertThat(rules.normalEgress(features, 50, new Coordinate(-0.001, 10))).isEmpty();
        assertThat(rules.normalEgress(features, 50, new Coordinate(0, 10))).isEmpty();
        assertThat(rules.normalEgress(features, 50, new Coordinate(0.01, 10))).isEmpty();
        Coordinate exit = rules.normalEgress(features, 50, new Coordinate(0.0101, 10))
                .orElseThrow().exit();
        assertThat(exit.distance(new Coordinate(-0.25, 10))).isLessThan(1e-12);
    }

    @Test
    void extendsTheNormalBuildingExitAcrossOnlyItsContainingSocialArea() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("own", "oks_existing", null,
                        "POLYGON ((40 40, 60 40, 60 60, 40 60, 40 40))"),
                feature("kindergarten", "restriction", "social_area",
                        "POLYGON ((20 20, 80 20, 80 80, 20 80, 20 20))"),
                feature("foreign", "restriction", "social_area",
                        "POLYGON ((-80 20, -20 20, -20 80, -80 80, -80 20))"));

        OfficialRouteGeometryRules.NormalEgress egress = rules.normalEgressTowards(
                features, 50, new Coordinate(50, 50), new Coordinate(0, 50)).orElseThrow();

        assertThat(egress.exit().x).isCloseTo(18.75, org.assertj.core.data.Offset.offset(0.02));
        assertThat(egress.exit().y).isEqualTo(50.0);
        assertThat(egress.terminalExemptionIds()).containsExactlyInAnyOrder("own", "kindergarten");
    }

    @Test
    void stillRejectsUnsupportedDiameterWhenAnEligibleOksExists() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("remote", "oks_existing", null, "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        assertThatThrownBy(() -> rules.normalEgress(features, 51, new Coordinate(100, 100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void excludesUnknownFutureNullAndEmptyGeometriesFromOwnOksSelection() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("future", "oks_future", null, "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                feature("unknown", "restriction", "unknown", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                feature("empty", "oks_existing", null, "POLYGON EMPTY"),
                new ImportedOfficialFeature("null", "oks_existing", mapper.createObjectNode(), null));
        Coordinate connection = new Coordinate(10, 10);
        Coordinate target = new Coordinate(-50, 10);
        assertThat(rules.normalEgress(features, 50, connection)).isEmpty();
        assertThat(rules.normalEgressTowards(features, 50, connection, target)).isEmpty();
        assertThat(rules.normalEgressCandidates(features, 50, connection, target, 60)).isEmpty();
    }

    private void append(StringBuilder signature, Optional<OfficialRouteGeometryRules.NormalEgress> result) {
        if (result.isEmpty()) {
            signature.append("empty;");
            return;
        }
        OfficialRouteGeometryRules.NormalEgress egress = result.get();
        signature.append(egress.oksId()).append(':')
                .append(Double.toHexString(egress.start().x)).append(',')
                .append(Double.toHexString(egress.start().y)).append('>')
                .append(Double.toHexString(egress.exit().x)).append(',')
                .append(Double.toHexString(egress.exit().y)).append(';');
    }

    private ImportedOfficialFeature feature(String id, String objectType, String restrictionType, String wkt)
            throws Exception {
        var attributes = mapper.createObjectNode();
        if (restrictionType != null) {
            attributes.put("restriction_type", restrictionType);
        }
        return new ImportedOfficialFeature(id, objectType, attributes, new WKTReader().read(wkt));
    }

    private CountingPolygon countedSquare(double offset) throws Exception {
        Polygon polygon = (Polygon) new WKTReader().read("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))");
        polygon.apply((org.locationtech.jts.geom.CoordinateFilter) coordinate -> coordinate.x += offset);
        polygon.geometryChanged();
        return new CountingPolygon(polygon);
    }

    private static final class CountingPolygon extends Polygon {
        private int bufferCalls;

        private CountingPolygon(Polygon source) {
            super(source.getExteriorRing(), null, source.getFactory());
        }

        @Override
        public Geometry buffer(double distance, int quadrantSegments) {
            bufferCalls++;
            return super.buffer(distance, quadrantSegments);
        }
    }
}
