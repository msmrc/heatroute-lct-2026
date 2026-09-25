package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialRoutingEnvironmentPreparationTest {
    @Test
    void reusesOnlyPreparationWhileRequeryingWindowsAndReapplyingEndpointExceptions() throws Exception {
        CountingRules rules = new CountingRules();
        WindowSource source = new WindowSource();
        source.features.add(building());
        OfficialRoutingEnvironment environment = new OfficialRoutingEnvironment(List.of(), source, rules);
        Coordinate outside = new Coordinate(-20, 10);
        Coordinate end = new Coordinate(40, 10);
        Constraint ordinary = environment.constraints(100, Set.of(), outside, end).get(0);
        Constraint widerDiameter = environment.constraints(400, Set.of(), outside, end).get(0);
        assertThat(widerDiameter).isNotSameAs(ordinary);
        assertThat(widerDiameter.blocked().getArea()).isGreaterThan(ordinary.blocked().getArea());
        assertThat(rules.preparedFeatures).isEqualTo(2);
        Constraint approach = environment.constraints(100, Set.of(), new Coordinate(-2, 10), end).get(0);
        assertThat(approach.blocked().equalsExact(approach.source())).isTrue();
        assertThat(ordinary.blocked().getArea()).isGreaterThan(ordinary.source().getArea());
        assertThat(environment.constraints(100, Set.of("building"), outside, end)).isEmpty();
        assertThat(environment.constraints(100, Set.of(), outside, end).get(0)).isSameAs(ordinary);
        assertThat(environment.pointInsideForbiddenClearance(100, new Coordinate(-4, 10))).isTrue();
        assertThat(environment.constraints(100, Set.of(), new Coordinate(2000, 0), new Coordinate(2020, 0)))
                .isEmpty();
        source.features.clear();
        assertThat(environment.constraints(100, Set.of(), outside, end)).isEmpty();
        assertThat(source.queries).isEqualTo(8);
        assertThat(rules.preparedFeatures).isEqualTo(2);
    }

    @Test
    void preparationDoesNotSurviveIntoNextCalculation() throws Exception {
        CountingRules rules = new CountingRules();
        WindowSource source = new WindowSource();
        source.features.add(building());
        Coordinate start = new Coordinate(-20, 10);
        Coordinate end = new Coordinate(40, 10);
        OfficialRoutingEnvironment first = new OfficialRoutingEnvironment(List.of(), source, rules);
        OfficialRoutingEnvironment next = new OfficialRoutingEnvironment(List.of(), source, rules);
        Constraint left = first.constraints(100, Set.of(), start, end).get(0);
        Constraint right = next.constraints(100, Set.of(), start, end).get(0);
        assertThat(left).isNotSameAs(right);
        assertThat(left.blocked().equalsExact(right.blocked())).isTrue();
        assertThat(rules.preparedFeatures).isEqualTo(2);
    }

    private ImportedOfficialFeature building() throws Exception {
        return new ImportedOfficialFeature("building", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "oks"),
                new WKTReader().read("POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
    }

    private static final class CountingRules extends OfficialRouteGeometryRules {
        private int preparedFeatures;
        private CountingRules() { super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry()); }
        @Override List<Constraint> baseConstraints(List<ImportedOfficialFeature> features, int diameter) {
            preparedFeatures += features.size();
            return super.baseConstraints(features, diameter);
        }
    }

    private static final class WindowSource implements RoutingFeatureSource {
        private final List<ImportedOfficialFeature> features = new ArrayList<>();
        private int queries;
        @Override public List<ImportedOfficialFeature> findInMetricWindow(Envelope bounds) {
            queries++;
            return new InMemoryRoutingFeatureSource(features).findInMetricWindow(bounds);
        }
        @Override public List<ImportedOfficialFeature> findByFeatureIds(Set<String> ids) {
            return new InMemoryRoutingFeatureSource(features).findByFeatureIds(ids);
        }
    }
}
