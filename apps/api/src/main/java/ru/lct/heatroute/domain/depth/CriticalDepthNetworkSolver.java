package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/** Ignored prototype: one shared depth variable per actual new-network node, with explicit pins only. */
final class CriticalDepthNetworkSolver {
    private final CriticalDepthSolver edgeSolver;

    CriticalDepthNetworkSolver(OfficialPipeCatalog pipes) { edgeSolver = new CriticalDepthSolver(pipes); }

    Map<String, DepthProfileResult> solve(List<EdgeInput> edges, BigDecimal minimum, BigDecimal maximum,
            Map<String, BigDecimal> fixedEndpointDepths) {
        int min = CriticalDepthSolver.mm(minimum, RoundingMode.CEILING);
        int max = CriticalDepthSolver.mm(maximum, RoundingMode.FLOOR);
        List<DepthIntervalClosure.Domain> domains = new ArrayList<>();
        List<int[]> links = new ArrayList<>();
        Map<String, Integer> nodes = new LinkedHashMap<>();
        Map<String, CriticalDepthSolver.PreparedEdge> prepared = new LinkedHashMap<>();
        Map<String, int[]> componentIndices = new LinkedHashMap<>();
        boolean incomplete = false;
        for (EdgeInput input : edges) {
            if (prepared.containsKey(input.id)) throw new IllegalArgumentException("duplicate edge id");
            var edge = edgeSolver.prepare(input.lengthM, input.diameter, input.crossings, minimum, maximum, input.floors, input.metric);
            prepared.put(input.id, edge);
            incomplete |= !edge.issues.isEmpty();
            int upstream = node(input.upstream, nodes, domains, fixedEndpointDepths, min, max);
            int downstream = node(input.downstream, nodes, domains, fixedEndpointDepths, min, max);
            int[] indices = new int[edge.components.size()];
            int previous = upstream, previousEnd = 0;
            for (int i = 0; i < indices.length; i++) {
                var component = edge.components.get(i);
                indices[i] = domains.size();
                domains.add(component.domain);
                links.add(new int[]{previous, indices[i], edge.metric.allowedRise(previousEnd, component.start)});
                previous = indices[i]; previousEnd = component.end;
            }
            links.add(new int[]{previous, downstream, edge.metric.allowedRise(previousEnd, edge.lengthMm)});
            componentIndices.put(input.id, indices);
        }
        Optional<int[]> least = incomplete ? Optional.empty() : DepthIntervalClosure.leastGraph(domains, links);
        Map<String, DepthProfileResult> output = new LinkedHashMap<>();
        if (least.isEmpty()) {
            prepared.forEach((id, edge) -> output.put(id, edgeSolver.failure(edge)));
            return output;
        }
        int[] heights = DepthIntervalClosure.preferOrdinaryGraph(domains, links, least.get(), 3000).orElseThrow();
        for (EdgeInput input : edges) {
            int[] indices = componentIndices.get(input.id), local = new int[indices.length];
            for (int i = 0; i < indices.length; i++) local[i] = heights[indices[i]];
            output.put(input.id, edgeSolver.render(prepared.get(input.id), local,
                    heights[nodes.get(input.upstream)], heights[nodes.get(input.downstream)]));
        }
        return output;
    }

    private int node(String id, Map<String, Integer> nodes, List<DepthIntervalClosure.Domain> domains,
            Map<String, BigDecimal> fixedDepths, int minimum, int maximum) {
        if (id == null) throw new IllegalArgumentException("node id required");
        Integer known = nodes.get(id);
        if (known != null) return known;
        int index = domains.size();
        DepthIntervalClosure.Domain domain = DepthIntervalClosure.Domain.range(minimum, maximum);
        if (fixedDepths.containsKey(id)) {
            int fixed = CriticalDepthSolver.mm(fixedDepths.get(id), RoundingMode.UNNECESSARY);
            domain = domain.intersection(DepthIntervalClosure.Domain.range(fixed, fixed));
        }
        domains.add(domain);
        nodes.put(id, index);
        return index;
    }

    static final class EdgeInput {
        final String id;
        final String upstream;
        final String downstream;
        final BigDecimal lengthM;
        final int diameter;
        final List<DepthCrossing> crossings;
        final List<DepthFloorInterval> floors;
        final DepthStationMetric metric;

        EdgeInput(String id, String upstream, String downstream, BigDecimal lengthM, int diameter,
                List<DepthCrossing> crossings, List<DepthFloorInterval> floors) {
            this(id, upstream, downstream, lengthM, diameter, crossings, floors,
                    DepthStationMetric.identity(CriticalDepthSolver.mm(lengthM, RoundingMode.HALF_UP)));
        }

        EdgeInput(String id, String upstream, String downstream, BigDecimal lengthM, int diameter,
                List<DepthCrossing> crossings, List<DepthFloorInterval> floors, DepthStationMetric metric) {
            this.metric = metric;
            this.id = id; this.upstream = upstream; this.downstream = downstream; this.lengthM = lengthM;
            this.diameter = diameter; this.crossings = List.copyOf(crossings); this.floors = List.copyOf(floors);
        }
    }
}
