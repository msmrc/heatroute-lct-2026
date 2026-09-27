package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/** Один доказанно запрещённый ввод не отменяет поиск для остальных потребителей. */
class ForbiddenTerminalPartitionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @ParameterizedTest
    @CsvSource({"social_area,0,false", "park,37,false", "social_area,37,true", "park,0,true"})
    void retainsBlockedDemandAndItsPenaltyWithoutSearchingForAnImpossibleTerminal(String type, double degrees,
            boolean windowedDepth)
            throws Exception {
        var move = AffineTransformation.rotationInstance(Math.toRadians(degrees)).translate(430000, 6180000);
        var features = fixture(type, move);
        var topology = new TopologyAnalysis(0, 2, 1, List.of(), List.of(
                new TieInCandidate("left", "source", "heat_chamber", 100, false),
                new TieInCandidate("right", "source", "heat_chamber", 100, false),
                new TieInCandidate("blocked", "source", "heat_chamber", 95, false)));
        var planner = new OfficialDatasetRoutingTest().planner();
        var parameters = new OfficialRunParameters(null, null, windowedDepth);
        var core = features.stream().filter(f -> !"site".equals(f.getFeatureId())).collect(Collectors.toList());
        java.util.Collections.reverse(core);
        var result = windowedDepth
                ? planner.plan(core, topology, parameters, OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE,
                        new InMemoryRoutingFeatureSource(features))
                : planner.plan(features, topology, parameters, OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE);
        assertThat(result.getDemandCount()).isEqualTo(3);
        assertThat(result.getVariants()).isNotEmpty().allSatisfy(variant -> {
            assertThat(variant.isValid()).isTrue();
            assertThat(variant.getConnectedDemandCount()).isEqualTo(2);
            assertThat(variant.getConnections()).hasSize(3);
            assertThat(variant.getConnections()).extracting(RouteConnection::getDemandId).doesNotHaveDuplicates();
            assertThat(variant.getNodes()).noneMatch(n -> "demand:blocked".equals(n.getId()));
            assertThat(variant.getConnections()).filteredOn(c -> "blocked".equals(c.getDemandId()))
                    .singleElement().satisfies(connection -> {
                        assertThat(connection.getStatus()).isEqualTo("no_route");
                        assertThat(connection.getReason()).isEqualTo("ENDPOINT_INSIDE_FORBIDDEN_AREA");
                        assertThat(connection.getDiagnostics().getCandidateCount()).isEqualTo(1);
                        assertThat(connection.getDiagnostics().getAttemptedCandidateCount()).isZero();
                        assertThat(connection.getDiagnostics().getDirectBlockers()).containsExactly(type + ":site");
                    });
            assertThat(variant.getEconomics().getUnconnectedPenalty())
                    .isEqualByComparingTo(new OfficialEconomics().unconnectedPenalty(new BigDecimal("3")));
            assertThat(new OfficialRouteValidator(rules).validate(variant.getNodes(), variant.getEdges(), features)).isEmpty();
            assertThat(new ExpertChamberRouteValidator().validate(variant.getNodes(), variant.getEdges(),
                    router.prepare(features)::existingDirections)).isEmpty();
            if (windowedDepth) assertThat(variant.getEdges()).allSatisfy(edge -> {
                assertThat(edge.getDepthProfile().isComplete()).isTrue();
                assertThat(edge.getDepthProfile().getIssues()).isEmpty();
            });
        });
    }

    @ParameterizedTest
    @CsvSource({"social_area,0", "park,37"})
    void demonstratesWhyOneForbiddenEndpointCannotBePassedToTheAllTerminalBuilder(String type, double degrees)
            throws Exception {
        var move = AffineTransformation.rotationInstance(Math.toRadians(degrees)).translate(430000, 6180000);
        var features = fixture(type, move);
        var environment = router.prepare(features);
        var endpoints = features.stream().filter(f -> "oks_connection_point".equals(f.getObjectType()))
                .collect(Collectors.toList());
        var partition = new TerminalFeasibility().partition(endpoints,
                f -> f.getMetricGeometry().getCoordinate(), environment);
        assertThat(partition.searchable()).hasSize(2);
        assertThat(partition.blocked()).hasSize(1);
        Coordinate origin = move.transform(new Coordinate(0, 0), new Coordinate());
        var root = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(origin.x, origin.y),
                true, true, 2, "source");
        var footprints = features.stream().filter(rules::isBuildingFeature)
                .map(ImportedOfficialFeature::getMetricGeometry).collect(Collectors.toList());
        var builder = new OrthogonalCorridorNetworkBuilder(router, new OfficialPipeCatalog());
        assertThat(builder.build(terminals(endpoints), root, 2, footprints, environment,
                (id, port, du, avoid) -> null)).isEmpty();
        assertThat(builder.build(terminals(partition.searchable()), root, 2, footprints, environment,
                (id, port, du, avoid) -> null)).isNotEmpty().allSatisfy(network -> {
                    assertThat(network.connections()).hasSize(2);
                    assertThat(new OfficialRouteValidator(rules).validate(network.nodes(), network.edges(), features)).isEmpty();
                });
    }

    private List<OrthogonalCorridorNetworkBuilder.Terminal> terminals(List<ImportedOfficialFeature> endpoints) {
        return endpoints.stream().map(f -> new OrthogonalCorridorNetworkBuilder.Terminal(f.getFeatureId(), f.getFeatureId(),
                f.getMetricGeometry().getCoordinate(), BigDecimal.ONE)).collect(Collectors.toList());
    }

    private List<ImportedOfficialFeature> fixture(String type, AffineTransformation move) throws Exception {
        List<ImportedOfficialFeature> result = new ArrayList<>();
        result.add(feature("north", "heat_network", "LINESTRING (0 0,0 200)", "{\"diameter\":100}", move));
        result.add(feature("south", "heat_network", "LINESTRING (0 -200,0 0)", "{\"diameter\":100}", move));
        result.add(feature("source", "heat_chamber", "POINT (0 0)", "{}", move));
        result.add(feature("left-own", "oks_existing", "POLYGON ((-110 -10,-90 -10,-90 10,-110 10,-110 -10))", "{}", move));
        result.add(feature("right-own", "oks_existing", "POLYGON ((90 -10,110 -10,110 10,90 10,90 -10))", "{}", move));
        result.add(feature("site", "restriction", "POLYGON ((40 70,60 70,60 90,40 90,40 70))",
                "{\"restriction_type\":\"" + type + "\"}", move));
        result.add(feature("left", "oks_connection_point", "POINT (-100 0)", "{\"flow_tph\":1}", move));
        result.add(feature("right", "oks_connection_point", "POINT (100 0)", "{\"flow_tph\":2}", move));
        result.add(feature("blocked", "oks_connection_point", "POINT (50 80)", "{\"flow_tph\":3}", move));
        return result;
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt, String attributes,
            AffineTransformation move) throws Exception {
        return new ImportedOfficialFeature(id, type, mapper.readTree(attributes), move.transform(new WKTReader().read(wkt)));
    }
}
