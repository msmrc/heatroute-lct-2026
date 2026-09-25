package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class TerminalFeasibilityTest {
    private final TerminalFeasibility feasibility = new TerminalFeasibility();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @ParameterizedTest
    @ValueSource(strings = {"social_area", "park", "prohibited_site", "water", "railway"})
    void provesOnlyStrictInteriorAndKeepsBoundaryOrNearbyClearanceCasesForRouting(String type) throws Exception {
        var feature = feature("site", "restriction", type, "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        Coordinate inside = new Coordinate(10, 10), boundary = new Coordinate(0, 10), near = new Coordinate(-0.1, 10);
        var result = partition(List.of(inside, boundary, near), List.of(feature), false);
        assertThat(result.searchable()).containsExactly(boundary, near);
        assertThat(result.blocked()).containsOnlyKeys(inside);
        assertThat(result.blocked().get(inside)).containsExactly(type + ":site");
    }

    @ParameterizedTest
    @ValueSource(strings = {"oks", "road", "tram_tracks", "gas_pipeline", "heat_network", "unknown"})
    void doesNotPreclassifyOwnOksOrSpecialAndUnknownRulesAsForbidden(String type) throws Exception {
        var feature = feature("site", "restriction", type, "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        assertThat(partition(List.of(new Coordinate(10, 10)), List.of(feature), false).blocked()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observesHolesMultipartWindowedFeaturesAndStableTypedBlockerIds(boolean windowed) throws Exception {
        var first = feature("same-id", "restriction", "park", "MULTIPOLYGON (((0 0,40 0,40 40,0 40,0 0),"
                + "(10 10,10 30,30 30,30 10,10 10)),((60 0,80 0,80 20,60 20,60 0)))");
        var second = feature("same-id", "restriction", "social_area", "POLYGON ((0 0,5 0,5 5,0 5,0 0))");
        Coordinate hole = new Coordinate(20, 20), firstBody = new Coordinate(2, 2), otherBody = new Coordinate(70, 10);
        var result = partition(List.of(hole, firstBody, otherBody), List.of(second, first, second), windowed);
        assertThat(result.searchable()).containsExactly(hole);
        assertThat(result.blocked().get(firstBody)).containsExactly("park:same-id", "social_area:same-id");
        assertThat(result.blocked().get(otherBody)).containsExactly("park:same-id");
    }

    @Test
    void doesNotInferUnreachabilityFromAnEnclosingBarrierOrUnsupportedGeometry() throws Exception {
        var ring = feature("ring", "restriction", "park", "POLYGON ((-20 -20,20 -20,20 20,-20 20,-20 -20),"
                + "(-10 -10,-10 10,10 10,10 -10,-10 -10))");
        var line = feature("line", "restriction", "social_area", "LINESTRING (-5 0,5 0)");
        var own = feature("own", "oks_existing", "", "POLYGON ((-2 -2,2 -2,2 2,-2 2,-2 -2))");
        var result = partition(List.of(new Coordinate()), List.of(ring, line, own), false);
        assertThat(result.blocked()).isEmpty();
        assertThat(result.searchable()).hasSize(1);
    }

    @Test
    void cancellationIsNotConvertedIntoAForbiddenTerminal() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> partition(List.of(new Coordinate()), List.of(), false))
                    .isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    private TerminalFeasibility.Partition<Coordinate> partition(List<Coordinate> points,
            List<ImportedOfficialFeature> features, boolean windowed) {
        var environment = windowed
                ? new OfficialRoutingEnvironment(List.of(), new InMemoryRoutingFeatureSource(features), rules)
                : new OfficialRoutingEnvironment(features, rules);
        return feasibility.partition(points, point -> point, environment);
    }

    private ImportedOfficialFeature feature(String id, String type, String restriction, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, type, new ObjectMapper().createObjectNode()
                .put("restriction_type", restriction), new WKTReader().read(wkt));
    }
}
