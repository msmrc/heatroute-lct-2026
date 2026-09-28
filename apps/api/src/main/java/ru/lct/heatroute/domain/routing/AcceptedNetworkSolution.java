package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;

/** Полностью допущенная сеть; экземпляр создаёт только {@link FrozenNetworkEvaluator}. */
public final class AcceptedNetworkSolution {
    private final String id;
    private final String strategy;
    private final List<RouteNode> nodes;
    private final List<RouteEdge> edges;
    private final List<RouteConnection> connections;
    private final BigDecimal totalLengthM;
    private final VariantEconomics economics;
    private final String geometryHash;

    AcceptedNetworkSolution(String id, String strategy, List<RouteNode> nodes, List<RouteEdge> edges,
            List<RouteConnection> connections, BigDecimal totalLengthM, VariantEconomics economics,
            String geometryHash) {
        this.id = Objects.requireNonNull(id, "id");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.connections = List.copyOf(connections);
        this.totalLengthM = Objects.requireNonNull(totalLengthM, "totalLengthM");
        this.economics = Objects.requireNonNull(economics, "economics");
        this.geometryHash = Objects.requireNonNull(geometryHash, "geometryHash");
    }

    public String getId() { return id; }
    public String getStrategy() { return strategy; }
    public List<RouteNode> getNodes() { return nodes; }
    public List<RouteEdge> getEdges() { return edges; }
    public List<RouteConnection> getConnections() { return connections; }
    public BigDecimal getTotalLengthM() { return totalLengthM; }
    public VariantEconomics getEconomics() { return economics; }
    public String getGeometryHash() { return geometryHash; }

    /** Создаёт обычный совместимый вариант только из уже принятого результата. */
    public RouteVariant toRouteVariant() {
        return new RouteVariant(id, strategy, nodes, edges, connections, totalLengthM,
                List.of(), List.of(), List.of(), ExistingNetworkReconstructionResult.empty(), economics, null);
    }
}
