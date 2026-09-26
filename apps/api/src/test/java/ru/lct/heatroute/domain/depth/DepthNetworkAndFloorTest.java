package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

class DepthNetworkAndFloorTest {
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final CriticalDepthSolver local = new CriticalDepthSolver(pipes);
    private final CriticalDepthNetworkSolver network = new CriticalDepthNetworkSolver(pipes);
    private final ContinuousDepthProfileValidator validator = new ContinuousDepthProfileValidator(pipes);

    @Test
    void sharedNodeCannotJumpBetweenIndependentlyLegalEdgeProfiles() {
        var left = edge("left", "source", "joint", "50", 1400, List.of(power("48")));
        var right = edge("right", "joint", "demand", "50", 50, List.of());
        Map<String, DepthProfileResult> result = solve(List.of(left, right), Map.of());
        assertComplete(result, List.of(left, right));
        assertThat(result.get("left").depthAt(b("50"))).isEqualByComparingTo(result.get("right").depthAt(b("0")));
        assertThat(result.get("right").depthAt(b("0"))).isEqualByComparingTo("3.4");
        assertThat(result.get("right").getDepthAdjustedCostMeters()).isEqualByComparingTo("50.080");
    }

    @Test
    void sharedBranchContinuityCanForceAnotherEdgesPassageThroughItsForbiddenBand() {
        var incoming = edge("incoming", "source", "joint", "50", 1400, List.of(power("48")));
        var ordinary = edge("ordinary", "joint", "demand-a", "50", 50, List.of());
        var gas = edge("gas", "joint", "demand-b", "20", 1400, List.of(gas("2")));
        List<CriticalDepthNetworkSolver.EdgeInput> edges = List.of(incoming, ordinary, gas);
        Map<String, DepthProfileResult> result = solve(edges, Map.of());
        assertComplete(result, edges);
        assertThat(result.get("gas").getCrossings().get(0).getPassage()).isEqualTo("below");
        assertThat(result.get("gas").depthAt(b("0"))).isEqualByComparingTo(result.get("incoming").depthAt(b("50")));
    }

    @Test
    void onlyExplicitEndpointPinsMayMakeOtherwiseFeasibleNetworkImpossible() {
        var left = edge("left", "source", "joint", "50", 1400, List.of(power("48")));
        var right = edge("right", "joint", "demand", "50", 50, List.of());
        List<CriticalDepthNetworkSolver.EdgeInput> edges = List.of(left, right);
        assertComplete(solve(edges, Map.of("source", b("3"))), edges);
        assertThat(solve(edges, Map.of("joint", b("3"))).values()).allSatisfy(p -> assertThat(p.isComplete()).isFalse());
    }

    @Test
    void tramFloorForcesBelowWhileRoadOneMetreBoundaryAllowsAboveAtOneMetre() {
        DepthCrossing crossing = gas("50");
        var road = floor("road", "48", "52", "1.0");
        var tram = floor("tram", "48", "52", "1.2");
        DepthProfileResult above = withFloors(1400, List.of(crossing), List.of(road));
        DepthProfileResult below = withFloors(1400, List.of(crossing), List.of(tram));
        assertThat(above.getCrossings().get(0).getPassage()).isEqualTo("above");
        assertThat(above.depthAt(b("50"))).isEqualByComparingTo("1");
        assertThat(below.getCrossings().get(0).getPassage()).isEqualTo("below");
        assertThat(below.depthAt(b("50"))).isEqualByComparingTo("3.4");
    }

    @Test
    void nearbyTramFloorPropagatesItsRampDistanceWithoutOverlapWithGasPlateau() {
        DepthProfileResult result = withFloors(1400, List.of(gas("50")), List.of(floor("tram", "53", "60", "1.2")));
        // Above1.0 at plateau end52 cannot reach1.2 after only1m, so the passage must change.
        assertThat(result.getCrossings().get(0).getPassage()).isEqualTo("below");
    }

    @Test
    void validatorChecksEveryFloorInteriorVertexAndItsActualUnroundedBoundary() {
        DepthProfileResult original = local.optimize(b("100"), 1400, List.of(gas("50")), b(".7"), b("10"));
        assertThat(validator.validate(b("100"), 1400, List.of(gas("50")), b(".7"), b("10"), original,
                List.of(floor("tram", "0", "100", "1.2"))))
                .extracting(DepthProfileIssue::getCode).contains("DEPTH_FLOOR_VIOLATION");
        assertThat(validator.validate(b("100"), 1400, List.of(gas("50")), b(".7"), b("10"), original,
                List.of(floor("tram", "53.999", "60", "1.2"))))
                .extracting(DepthProfileIssue::getCode).contains("DEPTH_FLOOR_VIOLATION");
        assertThat(validator.validate(b("100"), 1400, List.of(gas("50")), b(".7"), b("10"), original,
                List.of(floor("tram", "54", "60", "1.2")))).isEmpty();
    }

    private DepthProfileResult withFloors(int diameter, List<DepthCrossing> crossings, List<DepthFloorInterval> floors) {
        DepthProfileResult result = local.optimize(b("100"), diameter, crossings, b(".7"), b("10"),
                Boolean.getBoolean("depth.prototype.ignoreFloors") ? List.of() : floors);
        assertThat(result.isComplete()).isTrue();
        assertThat(validator.validate(b("100"), diameter, crossings, b(".7"), b("10"), result, floors)).isEmpty();
        return result;
    }

    private Map<String, DepthProfileResult> solve(List<CriticalDepthNetworkSolver.EdgeInput> edges, Map<String, BigDecimal> pins) {
        if (!Boolean.getBoolean("depth.prototype.legacyNetwork")) return network.solve(edges, b(".7"), b("10"), pins);
        Map<String, DepthProfileResult> result = new LinkedHashMap<>();
        edges.forEach(e -> result.put(e.id, local.optimize(e.lengthM, e.diameter, e.crossings, b(".7"), b("10"), e.floors)));
        return result;
    }

    private void assertComplete(Map<String, DepthProfileResult> result, List<CriticalDepthNetworkSolver.EdgeInput> inputs) {
        assertThat(result).hasSize(inputs.size());
        for (var input : inputs) {
            DepthProfileResult profile = result.get(input.id);
            assertThat(profile.isComplete()).isTrue();
            assertThat(validator.validate(input.lengthM, input.diameter, input.crossings, b(".7"), b("10"), profile, input.floors)).isEmpty();
        }
    }

    private CriticalDepthNetworkSolver.EdgeInput edge(String id, String from, String to, String length, int diameter, List<DepthCrossing> crossings) {
        return new CriticalDepthNetworkSolver.EdgeInput(id, from, to, b(length), diameter, crossings, List.of());
    }
    private DepthCrossing gas(String station) { return new DepthCrossing("gas", "gas_pipeline", b(station), b("2.8"), b(".4"), b(".2"), b("1.25")); }
    private DepthCrossing power(String station) { return new DepthCrossing("power", "power_cable", b(station), b("2.7"), b(".2"), b(".5"), b("1.15")); }
    private DepthFloorInterval floor(String id, String from, String to, String depth) { return new DepthFloorInterval(id, b(from), b(to), b(depth)); }
    private BigDecimal b(String value) { return new BigDecimal(value); }
}
