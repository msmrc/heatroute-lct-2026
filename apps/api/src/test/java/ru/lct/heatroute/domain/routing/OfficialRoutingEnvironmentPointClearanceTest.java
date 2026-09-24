package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialRoutingEnvironmentPointClearanceTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test
    void batchMatchesIndividualChecksAtDynamicClearanceBoundariesAndPolygonHoles() throws Exception {
        List<ImportedOfficialFeature> core = List.of(feature("core", "park", "POLYGON ((200 0,220 0,220 20,200 20,200 0))"));
        CountingSource source = new CountingSource(List.of(
                feature("building", "oks", "POLYGON ((0 0,20 0,20 20,0 20,0 0))"),
                feature("courtyard", "oks", "POLYGON ((40 0,100 0,100 60,40 60,40 0),(55 15,55 45,85 45,85 15,55 15))"),
                feature("road", "road", "POLYGON ((120 0,140 0,140 20,120 20,120 0))")));
        OfficialRoutingEnvironment environment = new OfficialRoutingEnvironment(core, source, rules);
        List<Coordinate> points = new ArrayList<>(List.of(new Coordinate(10, 10), new Coordinate(70, 30),
                new Coordinate(56, 30), new Coordinate(130, 10), new Coordinate(210, 10), new Coordinate(300, 100)));
        for (double x : new double[] {-9.001, -9, -8.999, -7.001, -7, -6.999, -5.001, -5, -4.999}) {
            points.add(new Coordinate(x, 10));
        }
        Envelope bounds = new Envelope();
        points.forEach(bounds::expandToInclude);
        for (int diameter : new int[] {400, 500, 800, 900}) {
            Predicate<Coordinate> batch = environment.preparePointClearance(diameter, bounds);
            int queries = source.queries;
            List<Boolean> actual = new ArrayList<>();
            points.forEach(point -> actual.add(batch.test(point)));
            assertThat(source.queries).isEqualTo(queries);
            for (int i = 0; i < points.size(); i++) {
                assertThat(actual.get(i)).as("DU%d at %s", diameter, points.get(i))
                        .isEqualTo(environment.pointInsideForbiddenClearance(diameter, points.get(i)));
            }
        }
    }

    @Test
    void buffersOncePerPreparationAndDoesNotReuseWindowDataAcrossOperations() throws Exception {
        ImportedOfficialFeature building = feature("counted", "oks", "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        CountingRules countingRules = new CountingRules();
        CountingSource source = new CountingSource(List.of(building));
        OfficialRoutingEnvironment environment = new OfficialRoutingEnvironment(List.of(), source, countingRules);
        Envelope bounds = new Envelope(-80, 80, -80, 80);
        Predicate<Coordinate> check = environment.preparePointClearance(100, bounds);
        for (int i = 0; i < 13; i++) assertThat(check.test(new Coordinate(10, 10))).isTrue();
        assertThat(source.queries).isEqualTo(1);
        assertThat(countingRules.preparedFeatures).isEqualTo(1);
        Predicate<Coordinate> largerDiameter = environment.preparePointClearance(900, bounds);
        assertThat(check.test(new Coordinate(-8, 10))).isFalse();
        assertThat(largerDiameter.test(new Coordinate(-8, 10))).isTrue();
        assertThat(countingRules.preparedFeatures).isEqualTo(2);
        source.features.clear();
        Predicate<Coordinate> nextOperation = environment.preparePointClearance(100, bounds);
        assertThat(nextOperation.test(new Coordinate(10, 10))).isFalse();
        assertThat(source.queries).isEqualTo(3);
    }

    @Test
    void ownsBoundsAndRejectsQueriesOutsidePreparedArea() {
        OfficialRoutingEnvironment environment = new OfficialRoutingEnvironment(List.of(), rules);
        Envelope bounds = new Envelope(0, 100, 0, 100);
        Predicate<Coordinate> check = environment.preparePointClearance(100, bounds);
        bounds.setToNull();
        assertThat(check.test(new Coordinate(50, 50))).isFalse();
        assertThatThrownBy(() -> check.test(new Coordinate(101, 50))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> environment.preparePointClearance(100, bounds)).isInstanceOf(IllegalArgumentException.class);
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", type), new WKTReader().read(wkt));
    }

    private static final class CountingSource implements RoutingFeatureSource {
        private final List<ImportedOfficialFeature> features;
        private int queries;
        private CountingSource(List<ImportedOfficialFeature> features) { this.features = new ArrayList<>(features); }
        @Override public List<ImportedOfficialFeature> findInMetricWindow(Envelope window) {
            queries++;
            return new InMemoryRoutingFeatureSource(features).findInMetricWindow(window);
        }
        @Override public List<ImportedOfficialFeature> findByFeatureIds(Set<String> ids) {
            return new InMemoryRoutingFeatureSource(features).findByFeatureIds(ids);
        }
    }

    private static final class CountingRules extends OfficialRouteGeometryRules {
        private int preparedFeatures;
        private CountingRules() { super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry()); }
        @Override List<Constraint> baseConstraints(List<ImportedOfficialFeature> features, int diameter) {
            preparedFeatures += features.size();
            return super.baseConstraints(features, diameter);
        }
    }
}
