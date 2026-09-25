package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** ДУ ствола не должен скрывать допустимый короткий ввод маломощного потребителя. */
class CorridorIndividualAnchorTest {
    @Test
    void mixedFlowsKeepTheSmallConsumersReachableNormalPort() throws Exception {
        Fixture fixture = new Fixture(0, 0, 0);
        assertShortValidNetworks(fixture, fixture.build(false));
    }

    @Test
    void shortIndividualApproachSurvivesRotationAndLargeMetricTranslation() throws Exception {
        for (double degrees : new double[] {13, 71, 117, 203, 289}) {
            Fixture fixture = new Fixture(degrees, 414000.125, 6173000.375);
            assertShortValidNetworks(fixture, fixture.build(false));
        }
    }

    @Test
    void keepsTheWholeAggregateControlAndIsIndependentOfInputOrder() throws Exception {
        Fixture fixture = new Fixture(0, 0, 0);
        List<String> originalFeatures = fixture.features.stream().map(f -> f.getMetricGeometry().toText()).collect(Collectors.toList());
        var control = fixture.control();
        List<String> controlSignatures = signatures(control);
        var expanded = fixture.build(false);
        assertThat(expanded.size()).isGreaterThan(control.size());
        assertThat(signatures(expanded.subList(0, control.size()))).isEqualTo(controlSignatures);
        assertThat(signatures(fixture.build(true))).containsExactlyInAnyOrderElementsOf(signatures(expanded));
        assertThat(fixture.features.stream().map(f -> f.getMetricGeometry().toText()).collect(Collectors.toList()))
                .isEqualTo(originalFeatures);
    }

    @Test
    void coincidentDiameterAnchorsDoNotDuplicateGeneration() throws Exception {
        Fixture fixture = new Fixture(0, 0, 0, BigDecimal.ONE);
        var control = fixture.control();
        assertThat(control).isNotEmpty();
        assertThat(signatures(fixture.build(false))).isEqualTo(signatures(control));
    }

    private void assertShortValidNetworks(Fixture fixture, List<OrthogonalCorridorNetworkBuilder.Network> networks) {
        assertThat(networks).isNotEmpty();
        List<OrthogonalCorridorNetworkBuilder.Network> valid = networks.stream()
                .filter(network -> new OfficialRouteValidator(fixture.rules)
                        .validate(network.nodes(), network.edges(), fixture.features).isEmpty())
                .filter(network -> new EngineeringRouteEvaluator().evaluate(network.edges()).isCompliant())
                .collect(Collectors.toList());
        assertThat(valid).isNotEmpty();
        double best = valid.stream().mapToDouble(network -> distance(network, "demand:small"))
                .min().orElseThrow();
        String shortDiagnostics = networks.stream().filter(network -> distance(network, "demand:small") < 70)
                .map(network -> "length=" + distance(network, "demand:small")
                        + " angles=" + new EngineeringRouteEvaluator().evaluate(network.edges()).invalidAngleCount()
                        + " issues=" + new OfficialRouteValidator(fixture.rules).validate(network.nodes(), network.edges(), fixture.features)
                                .stream().map(issue -> issue.getCode() + ":" + issue.getSubjectId()).collect(Collectors.toList()))
                .collect(Collectors.joining("; "));
        assertThat(best).as("root-to-small-consumer length at %s degrees; short candidates: %s", fixture.degrees, shortDiagnostics)
                .isLessThanOrEqualTo(63.05);
        assertThat(valid).allSatisfy(network -> {
            assertThat(network.connections()).hasSize(2).allMatch(c -> "connected".equals(c.getStatus()));
            assertThat(network.edges()).hasSize(network.nodes().size() - 1);
            for (RouteEdge edge : network.edges()) {
                if ("demand:small".equals(edge.getDownstreamNodeId())) continue;
                // Общий ствол и большая ветвь должны выдержать 7,835 м, не отступ ДУ50.
                Geometry line = fixture.rules.line(edge.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate).collect(Collectors.toList()));
                assertThat(line.distance(fixture.footprints.get(0))).isGreaterThanOrEqualTo(7.833);
            }
        });
    }

    private List<String> signatures(List<OrthogonalCorridorNetworkBuilder.Network> networks) {
        return networks.stream().map(network -> network.edges().stream().map(edge -> edge.getUpstreamNodeId()
                + ":" + edge.getDownstreamNodeId() + ":" + edge.getCoordinates().stream()
                        .map(p -> p.getXM() + "," + p.getYM()).collect(Collectors.joining(";")))
                .sorted().collect(Collectors.joining("|"))).collect(Collectors.toList());
    }

    private static double distance(OrthogonalCorridorNetworkBuilder.Network network, String target) {
        Map<String, Double> distance = new HashMap<>();
        distance.put("root", 0.0);
        for (int pass = 1; pass < network.nodes().size(); pass++) {
            for (RouteEdge edge : network.edges()) {
                double upstream = distance.getOrDefault(edge.getUpstreamNodeId(), Double.POSITIVE_INFINITY);
                double downstream = distance.getOrDefault(edge.getDownstreamNodeId(), Double.POSITIVE_INFINITY);
                double length = edge.getLengthM().doubleValue();
                distance.merge(edge.getDownstreamNodeId(), upstream + length, Math::min);
                distance.merge(edge.getUpstreamNodeId(), downstream + length, Math::min);
            }
        }
        return distance.getOrDefault(target, Double.POSITIVE_INFINITY);
    }

    private static final class Fixture {
        private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        private final List<ImportedOfficialFeature> features;
        private final List<Geometry> footprints;
        private final List<OrthogonalCorridorNetworkBuilder.Terminal> terminals;
        private final RouteNode root;
        private final double degrees;

        private Fixture(double degrees, double dx, double dy) throws Exception {
            this(degrees, dx, dy, new BigDecimal("1000"));
        }

        private Fixture(double degrees, double dx, double dy, BigDecimal otherFlow) throws Exception {
            this.degrees = degrees;
            var move = AffineTransformation.rotationInstance(Math.toRadians(degrees)).translate(dx, dy);
            var own = move.transform(new WKTReader().read("POLYGON ((0 0,20 0,20 20,0 20,0 0))"));
            var park = move.transform(new WKTReader().read("POLYGON ((-10.5 5,-9.5 5,-9.5 11,-10.5 11,-10.5 5))"));
            var json = new ObjectMapper();
            features = List.of(new ImportedOfficialFeature("own", "oks_existing", json.createObjectNode(), own),
                    new ImportedOfficialFeature("park", "restriction", json.createObjectNode()
                            .put("restriction_type", "park"), park));
            footprints = List.of(own);
            Coordinate small = move.transform(new Coordinate(2, 8), new Coordinate());
            Coordinate other = move.transform(new Coordinate(60, 40), new Coordinate());
            Coordinate origin = move.transform(new Coordinate(-25, 40), new Coordinate());
            terminals = List.of(new OrthogonalCorridorNetworkBuilder.Terminal("small", "small", small, BigDecimal.ONE),
                    new OrthogonalCorridorNetworkBuilder.Terminal("large", "large", other, otherFlow));
            root = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(origin.x, origin.y),
                    true, true, 2, null);
        }

        private List<OrthogonalCorridorNetworkBuilder.Network> build(boolean reverse) {
            List<OrthogonalCorridorNetworkBuilder.Terminal> orderedTerminals = new ArrayList<>(terminals);
            List<ImportedOfficialFeature> orderedFeatures = new ArrayList<>(features);
            if (reverse) { Collections.reverse(orderedTerminals); Collections.reverse(orderedFeatures); }
            return new OrthogonalCorridorNetworkBuilder(router, new OfficialPipeCatalog()).build(
                    orderedTerminals, root, 2, footprints, router.prepare(orderedFeatures), (id, port, du, avoid) -> null);
        }

        private List<OrthogonalCorridorNetworkBuilder.Network> control() {
            SharedSpineNetworkBuilder.TerminalRouter fallback = (id, port, du, avoid) -> null;
            return ReflectionTestUtils.invokeMethod(new OrthogonalCorridorNetworkBuilder(router, new OfficialPipeCatalog()),
                    "buildWithAnchors", terminals, root, 2, footprints, router.prepare(features), fallback, null, false);
        }
    }
}
