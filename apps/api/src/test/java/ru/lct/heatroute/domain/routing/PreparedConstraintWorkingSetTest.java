package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class PreparedConstraintWorkingSetTest {
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int[] DIAMETERS = {50, 65, 80, 100, 125, 150, 200, 250};

    @Test
    void defaultBudgetRetainsAFullReusableMultiDiameterWorkingSet() {
        CountingRules rules = new CountingRules();
        PreparedRoutingConstraints prepared = new PreparedRoutingConstraints(rules);
        List<ImportedOfficialFeature> features = restrictions(96);
        Map<Integer, List<Constraint>> warmed = new LinkedHashMap<>();

        for (int diameter : DIAMETERS) {
            warmed.put(diameter, prepared.prepare(features, diameter));
        }

        assertThat(rules.compilations).isEqualTo(96 * DIAMETERS.length);
        assertThat(prepared.retainedEntryCount()).isEqualTo(96 * DIAMETERS.length).isLessThanOrEqualTo(1024);
        assertThat(prepared.retainedCoordinateCount()).isGreaterThan(100_000L).isLessThanOrEqualTo(400_000L);

        int afterWarmup = rules.compilations;
        for (int diameter : DIAMETERS) {
            List<Constraint> repeated = prepared.prepare(features, diameter);
            assertThat(repeated).hasSameSizeAs(warmed.get(diameter));
            for (int index = 0; index < repeated.size(); index++) {
                assertThat(repeated.get(index)).isSameAs(warmed.get(diameter).get(index));
            }
        }
        assertThat(rules.compilations).isEqualTo(afterWarmup);
    }

    @Test
    void explicitSmallBudgetStillEvictsButPreservesConstraintValues() {
        CountingRules rules = new CountingRules();
        PreparedRoutingConstraints prepared = new PreparedRoutingConstraints(rules, 1, 400_000);
        List<ImportedOfficialFeature> features = restrictions(2);

        List<Constraint> first = prepared.prepare(features, 100);
        List<Constraint> repeated = prepared.prepare(features, 100);

        assertThat(rules.compilations).isEqualTo(4);
        assertThat(prepared.retainedEntryCount()).isOne();
        assertThat(prepared.retainedCoordinateCount()).isLessThanOrEqualTo(400_000L);
        assertThat(repeated).hasSameSizeAs(first);
        for (int index = 0; index < first.size(); index++) {
            Constraint expected = first.get(index);
            Constraint actual = repeated.get(index);
            assertThat(actual).isNotSameAs(expected);
            assertThat(actual.id()).isEqualTo(expected.id());
            assertThat(actual.type()).isEqualTo(expected.type());
            assertThat(actual.clearanceM()).isEqualTo(expected.clearanceM());
            assertThat(PreparedRoutingConstraints.sameGeometry(actual.source(), expected.source())).isTrue();
            assertEquivalentGeometry(actual.blocked(), expected.blocked());
        }
    }

    private static List<ImportedOfficialFeature> restrictions(int count) {
        List<ImportedOfficialFeature> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double x = (index % 16) * 40.0;
            double y = (index / 16) * 40.0;
            result.add(new ImportedOfficialFeature(String.format("working-%03d", index), "restriction",
                    JSON.createObjectNode().put("restriction_type", "park"), smallPolygon(x, y)));
        }
        return result;
    }

    private static Polygon smallPolygon(double centerX, double centerY) {
        Coordinate[] ring = new Coordinate[33];
        for (int index = 0; index < 32; index++) {
            double angle = index * Math.PI / 16;
            ring[index] = new Coordinate(centerX + 4 * Math.cos(angle), centerY + 4 * Math.sin(angle));
        }
        ring[32] = new Coordinate(ring[0]);
        return GEOMETRY.createPolygon(ring);
    }

    private static void assertEquivalentGeometry(Geometry actual, Geometry expected) {
        if (expected == null) {
            assertThat(actual).isNull();
            return;
        }
        assertThat(actual).isNotNull();
        assertThat(PreparedRoutingConstraints.sameGeometry(actual, expected)).isTrue();
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
