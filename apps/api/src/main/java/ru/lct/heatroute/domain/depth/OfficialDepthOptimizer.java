package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/** Optional depth profiles from physical clearance intervals, with a complete horizontal-cost objective. */
@Component
public class OfficialDepthOptimizer {
    public static final BigDecimal NORMAL_DEPTH_M = new BigDecimal("3.0");
    public static final BigDecimal OFFICIAL_MINIMUM_DEPTH_M = new BigDecimal("0.7");
    public static final BigDecimal MAXIMUM_SLOPE = new BigDecimal("0.10");
    public static final BigDecimal CROSSING_HALF_LENGTH_M = new BigDecimal("2.0");
    // Application compatibility default; the organizer specifies no maximum depth.
    public static final BigDecimal DEFAULT_MAXIMUM_DEPTH_M = new BigDecimal("10.0");
    private final CriticalDepthSolver solver;
    private final CriticalDepthNetworkSolver network;

    public OfficialDepthOptimizer(OfficialPipeCatalog pipes, OfficialEconomics economics) {
        solver = new CriticalDepthSolver(pipes);
        network = new CriticalDepthNetworkSolver(pipes);
    }

    public DepthProfileResult optimize(BigDecimal length, int diameter, List<DepthCrossing> crossings, BigDecimal maximum) {
        return optimize(length, diameter, crossings, OFFICIAL_MINIMUM_DEPTH_M, maximum);
    }

    public DepthProfileResult optimize(BigDecimal length, int diameter, List<DepthCrossing> crossings,
            BigDecimal minimum, BigDecimal maximum) {
        return solver.optimize(length, diameter, crossings, minimum, maximum);
    }

    public DepthProfileResult optimize(BigDecimal length, int diameter, List<DepthCrossing> crossings,
            BigDecimal minimum, BigDecimal maximum, List<DepthFloorInterval> floors) {
        return solver.optimize(length, diameter, crossings, minimum, maximum, floors);
    }

    Map<String, DepthProfileResult> optimizeNetwork(List<CriticalDepthNetworkSolver.EdgeInput> edges,
            BigDecimal minimum, BigDecimal maximum, Map<String, BigDecimal> explicitPins) {
        return network.solve(edges, minimum, maximum, explicitPins);
    }
}
