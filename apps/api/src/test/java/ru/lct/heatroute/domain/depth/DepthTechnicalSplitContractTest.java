package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.lct.heatroute.domain.depth.DepthPlannerNetworkWiringTest.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteEdge;

/** Open admission blockers: a technical graph subdivision cannot erase or truncate a utility crossing. */
class DepthTechnicalSplitContractTest {
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialDepthPlanner planner = new OfficialDepthPlanner(
            new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
            new OfficialDepthOptimizer(pipes, new OfficialEconomics()), new OfficialDepthProfileValidator(pipes));

    @Test
    void splittingAtActualUtilityCannotEraseTheRequiredVerticalPassage() throws Exception {
        var features = List.of(utility("power", "power_cable", 50));
        var whole = plan(List.of(edge("whole", "source", "demand", 0, 100, 1400)), features);
        var split = plan(List.of(edge("left", "source", "joint", 0, 50, 1400),
                edge("right", "joint", "demand", 50, 100, 1400)), features);
        assertThat(whole.get(0).getDepthProfile().depthAt(b("50"))).isEqualByComparingTo("3.4");
        assertThat(split).allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isTrue());
        assertThat(split.get(0).getDepthProfile().depthAt(b("50")))
                .as("source utility at a technical join still requires below3.4 for DU1400")
                .isEqualByComparingTo("3.4");
    }

    @Test
    void splittingInsidePlateauCannotMakeTheSameLawfulPhysicalRouteInfeasible() throws Exception {
        var features = List.of(utility("power", "power_cable", 50));
        var whole = plan(List.of(edge("whole", "source", "demand", 0, 100, 1400)), features);
        assertThat(whole.get(0).getDepthProfile().isComplete()).isTrue();
        var split = plan(List.of(edge("left", "source", "joint", 0, 51, 1400),
                edge("right", "joint", "demand", 51, 100, 1400)), features);
        assertThat(split).allSatisfy(e -> {
            assertThat(e.getDepthProfile().getIssues()).isEmpty();
            assertThat(e.getDepthProfile().isComplete()).isTrue();
        });
        assertThat(split.get(1).getDepthProfile().depthAt(b("1"))).isEqualByComparingTo("3.4");
    }

    @Test
    void subdivisionIsIndependentOfOrderingOrientationAndRotatedMetricOrigin() throws Exception {
        for (double angle : new double[]{0, 37, 123}) for (boolean reverse : new boolean[]{false, true}) {
            var features = List.of(transformed(utility("power-varied", "power_cable", 50), angle));
            List<RouteEdge> edges = new java.util.ArrayList<>(List.of(
                    transformedEdge(edge("third", "source", "join-a", 0, 49, 1400), angle, reverse),
                    transformedEdge(edge("first", "join-a", "join-b", 49, 51, 1400), angle, !reverse),
                    transformedEdge(edge("second", "join-b", "demand", 51, 100, 1400), angle, reverse)));
            java.util.Collections.rotate(edges, 1);
            var result = plan(edges, features);
            assertThat(result).allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isTrue());
            var middle = result.stream().filter(e -> e.getId().equals("first")).findFirst().orElseThrow();
            assertThat(middle.getDepthProfile().depthAt(b("1"))).isEqualByComparingTo("3.4");
            assertThat(new DepthNetworkAssessment(pipes).assess(result, features, b(".7"), b("10"), Map.of(), Map.of()).getIssues()).isEmpty();
            assertThat(result).extracting(RouteEdge::getId).containsExactlyElementsOf(edges.stream().map(RouteEdge::getId).collect(java.util.stream.Collectors.toList()));
        }
    }

    @Test
    void realRootExemptionDoesNotHideSecondCrossingOfItsSameSourceFeature() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var reader = new org.locationtech.jts.io.WKTReader();
        var source = new ru.lct.heatroute.domain.topology.ImportedOfficialFeature("source-heat", "heat_network",
                mapper.readTree("{\"diameter\":50}"), reader.read("MULTILINESTRING ((0 -10,0 10),(50 -10,50 10))"));
        var edges = List.of(edge("a", "root", "joint", 0, 50, 50), edge("b", "joint", "demand", 50, 100, 50));
        var result = planner.planNetwork(edges, List.of(source), b(".7"), b("10"), Map.of("root", java.util.Set.of("source-heat")), Map.of());
        assertThat(result).allSatisfy(e -> {
            assertThat(e.getDepthProfile().isComplete()).isTrue();
            assertThat(e.getDepthProfile().getCrossings()).singleElement().satisfies(c -> assertThat(c.getCrossingId()).isEqualTo("source-heat"));
        });
        assertThat(result.get(0).getDepthProfile().depthAt(b("50"))).isLessThan(b("3"));
        assertThat(planner.planNetwork(edges, List.of(source), b(".7"), b("10"), Map.of(), Map.of()))
                .allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isFalse());
    }

    @Test
    void actualExistingChamberResolvesItsIncidentHeatButNeverAnUnrelatedUtility() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var reader = new org.locationtech.jts.io.WKTReader();
        var chamber = new ru.lct.heatroute.domain.topology.ImportedOfficialFeature("real-chamber", "heat_chamber", mapper.readTree("{}"), reader.read("POINT (0 0)"));
        var heat = new ru.lct.heatroute.domain.topology.ImportedOfficialFeature("incident-heat", "heat_network", mapper.readTree("{\"diameter\":50}"), reader.read("LINESTRING (0 -10,0 10)"));
        var edges = List.of(edge("edge", "root", "demand", 0, 100, 50));
        var pins = Map.of("root", java.util.Set.of("real-chamber"));
        var legal = planner.planNetwork(edges, List.of(chamber, heat), b(".7"), b("10"), pins, Map.of());
        assertThat(legal.get(0).getDepthProfile().isComplete()).isTrue();
        assertThat(legal.get(0).getDepthProfile().getCrossings()).isEmpty();
        var unrelated = planner.planNetwork(edges, List.of(chamber, heat, utility("gas-at-root", "gas_pipeline", 0)), b(".7"), b("10"), pins, Map.of());
        assertThat(unrelated.get(0).getDepthProfile().isComplete()).isFalse();
        assertThat(planner.plan(edges.get(0), List.of(heat)).isComplete()).as("legacy API without node context is conservative").isFalse();
    }

    @Test
    void independentAssessmentRejectsMissingContinuationAndForgedOrdinaryNodeDepth() throws Exception {
        var features = List.of(utility("power", "power_cable", 50));
        var valid = plan(List.of(edge("left", "source", "joint", 0, 50, 1400), edge("right", "joint", "demand", 50, 100, 1400)), features);
        var original = valid.get(1).getDepthProfile();
        var withoutDecision = new DepthProfileResult(true, original.getPoints(), List.of(), List.of(), original.getProfileLength3dM(), original.getDepthAdjustedCostMeters());
        var forged = new java.util.ArrayList<>(valid);
        forged.set(1, withProfile(valid.get(1), withoutDecision));
        assertThat(new DepthNetworkAssessment(pipes).assess(forged, features, b(".7"), b("10"), Map.of(), Map.of()).getIssues())
                .extracting(DepthProfileIssue::getCode).contains("CROSSING_DECISION_MISSING");
        var flat = new DepthProfileResult(true, List.of(new DepthProfilePoint(b("0"), b("3")), new DepthProfilePoint(b("50"), b("3"))),
                List.of(), List.of(), b("50"), b("50"));
        forged.set(0, withProfile(valid.get(0), flat)); forged.set(1, withProfile(valid.get(1), flat));
        assertThat(new DepthNetworkAssessment(pipes).assess(forged, features, b(".7"), b("10"), Map.of(), Map.of()).getIssues())
                .extracting(DepthProfileIssue::getCode).contains("CROSSING_DECISION_MISSING");
    }

    @Test
    void storedRampNodeDepthsRetainExactSlopeAfterMillimetreSerialization() throws Exception {
        var features = List.of(utility("power", "power_cable", 50));
        var input = List.of(edge("one", "source", "node-a", 0, 46.997, 1400),
                edge("two", "node-a", "node-b", 46.997, 53.003, 1400),
                edge("three", "node-b", "demand", 53.003, 100, 1400));
        var result = plan(input, features);
        assertThat(result).allSatisfy(e -> {
            assertThat(e.getDepthProfile().isComplete()).isTrue();
            var points = e.getDepthProfile().getPoints();
            for (int i = 1; i < points.size(); i++) {
                var run = points.get(i).getStationM().subtract(points.get(i - 1).getStationM());
                var rise = points.get(i).getDepthM().subtract(points.get(i - 1).getDepthM()).abs();
                assertThat(rise).isLessThanOrEqualTo(run.multiply(b(".1")));
            }
        });
        assertThat(new DepthNetworkAssessment(pipes).assess(result, features, b(".7"), b("10"), Map.of(), Map.of()).getIssues()).isEmpty();
    }

    @Test
    void differentDiametersAreNotMergedAndDoNotSilentlyEraseEndpointUtility() throws Exception {
        var features = List.of(utility("power", "power_cable", 50));
        var result = plan(List.of(edge("large", "source", "joint", 0, 50, 1400),
                edge("small", "joint", "demand", 50, 100, 50)), features);
        assertThat(result).allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isFalse());
    }

    @Test
    void accumulatedRoundedEdgeLengthsCannotMoveTheActualCrossingToAnotherStation() throws Exception {
        List<RouteEdge> edges = new java.util.ArrayList<>();
        double dx = 2.838, dy = .053, length = Math.hypot(dx, dy);
        for (int i = 0; i < 20; i++) edges.add(new RouteEdge("edge-" + i, "node-" + i, "node-" + (i + 1), length,
                List.of(new ru.lct.heatroute.domain.routing.RouteCoordinate(i * dx, i * dy),
                        new ru.lct.heatroute.domain.routing.RouteCoordinate((i + 1) * dx, (i + 1) * dy)), List.of(), b("2"), 1400));
        // A final exact10m segment makes global rescaling differ from the true per-edge station mapping.
        double ux = dx / length, uy = dy / length, x = 20 * dx, y = 20 * dy;
        edges.add(new RouteEdge("last", "node-20", "node-21", 10,
                List.of(new ru.lct.heatroute.domain.routing.RouteCoordinate(x, y),
                        new ru.lct.heatroute.domain.routing.RouteCoordinate(x + 10 * ux, y + 10 * uy)), List.of(), b("2"), 1400));
        double cx = 19.5 * dx, cy = 19.5 * dy;
        var geometry = new org.locationtech.jts.geom.GeometryFactory().createLineString(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(cx - 5 * uy, cy + 5 * ux),
                new org.locationtech.jts.geom.Coordinate(cx + 5 * uy, cy - 5 * ux)});
        var feature = new ru.lct.heatroute.domain.topology.ImportedOfficialFeature("actual-power", "restriction",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"restriction_type\":\"power_cable\"}"), geometry);
        var chain = DepthPhysicalChains.build(edges, java.util.Set.of()).get(0);
        var actual = DepthNetworkAssessment.source(chain, List.of(feature), Map.of(),
                new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes));
        // Coordinate projection in edge19 is exactly halfway: stored station=19*2.838+1.419=55.341.
        // Canonical chain orientation starts node-0, independently of input ordering.
        assertThat(actual.getCrossings()).singleElement().satisfies(c -> assertThat(c.getStationM()).isEqualByComparingTo("55.341"));
    }

    @Test
    void fourMetrePlateauRetainsItsPhysicalWidthAcrossManyRoundedTechnicalEdges() throws Exception {
        List<RouteEdge> edges = new java.util.ArrayList<>();
        double dx = .039, dy = .011, physicalLength = Math.hypot(dx, dy);
        for (int i = 0; i < 200; i++) edges.add(new RouteEdge("edge-" + i, "node-" + i, "node-" + (i + 1), physicalLength,
                List.of(new ru.lct.heatroute.domain.routing.RouteCoordinate(i * dx, i * dy),
                        new ru.lct.heatroute.domain.routing.RouteCoordinate((i + 1) * dx, (i + 1) * dy)), List.of(), b("2"), 1400));
        double cx = 100 * dx, cy = 100 * dy;
        var geometry = new org.locationtech.jts.geom.GeometryFactory().createLineString(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(cx - 100 * dy, cy + 100 * dx),
                new org.locationtech.jts.geom.Coordinate(cx + 100 * dy, cy - 100 * dx)});
        var feature = new ru.lct.heatroute.domain.topology.ImportedOfficialFeature("physical-power", "restriction",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"restriction_type\":\"power_cable\"}"), geometry);
        var result = plan(edges, List.of(feature));
        assertThat(result).allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isTrue());
        for (double offset : new double[]{-2, 2}) {
            double station = 100 * physicalLength + offset;
            int index = (int) Math.floor(station / physicalLength);
            BigDecimal local = BigDecimal.valueOf((station - index * physicalLength) / physicalLength).multiply(b(".041"));
            var points = result.get(index).getDepthProfile().getPoints();
            BigDecimal actual = null;
            for (int i = 1; i < points.size(); i++) if (local.compareTo(points.get(i).getStationM()) <= 0) {
                var left = points.get(i - 1); var right = points.get(i);
                actual = left.getDepthM().add(right.getDepthM().subtract(left.getDepthM()).multiply(local.subtract(left.getStationM()))
                        .divide(right.getStationM().subtract(left.getStationM()), 12, java.math.RoundingMode.HALF_UP));
                break;
            }
            assertThat(actual).as("depth exactly2m physically from crossing, offset=%s", offset).isEqualByComparingTo("3.4");
        }
    }

    private RouteEdge withProfile(RouteEdge edge, DepthProfileResult profile) {
        return new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(), edge.getLengthM().doubleValue(),
                edge.getCoordinates(), edge.getSections(), edge.getFlowTph(), edge.getDiameter(), profile);
    }
    private RouteEdge transformedEdge(RouteEdge edge, double degrees, boolean reversed) {
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        List<ru.lct.heatroute.domain.routing.RouteCoordinate> points = new java.util.ArrayList<>();
        for (var p : edge.getCoordinates()) points.add(new ru.lct.heatroute.domain.routing.RouteCoordinate(
                400000 + c * p.getXM().doubleValue() - s * p.getYM().doubleValue(),
                6100000 + s * p.getXM().doubleValue() + c * p.getYM().doubleValue()));
        if (reversed) java.util.Collections.reverse(points);
        return new RouteEdge(edge.getId(), reversed ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId(),
                reversed ? edge.getUpstreamNodeId() : edge.getDownstreamNodeId(), edge.getLengthM().doubleValue(), points, List.of(), edge.getFlowTph(), edge.getDiameter());
    }
    private ru.lct.heatroute.domain.topology.ImportedOfficialFeature transformed(ru.lct.heatroute.domain.topology.ImportedOfficialFeature f, double degrees) {
        var t = org.locationtech.jts.geom.util.AffineTransformation.rotationInstance(Math.toRadians(degrees));
        t.translate(400000,6100000);
        return new ru.lct.heatroute.domain.topology.ImportedOfficialFeature(f.getFeatureId(), f.getObjectType(), f.getAttributes(), t.transform(f.getMetricGeometry()));
    }

    private List<RouteEdge> plan(List<RouteEdge> edges, List<ru.lct.heatroute.domain.topology.ImportedOfficialFeature> features) {
        return planner.planNetwork(edges, features, b(".7"), b("10"), Map.of(), Map.of());
    }
}
