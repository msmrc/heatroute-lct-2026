package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.*;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.*;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class DepthReroutePhysicalContextTest {
    @Test
    void lawfulPlateauAcrossTechnicalSplitDoesNotAcquireAnUnnecessaryHorizontalDetour() throws Exception {
        var rules = new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        var pipes = new OfficialPipeCatalog();
        var detourSearches = new java.util.concurrent.atomic.AtomicInteger();
        var router = new OfficialObstacleRouter(rules) {
            @Override RoutePath findDepthDetourPreservingChambers(RouteEdge edge, java.util.Map<String,RouteNode> nodes,
                    OfficialRoutingEnvironment environment, java.util.Set<String> exemptions, java.util.Set<String> failedUtilities,
                    List<RouteEdge> accepted) {
                detourSearches.incrementAndGet();
                return super.findDepthDetourPreservingChambers(edge, nodes, environment, exemptions, failedUtilities, accepted);
            }
        };
        var depth = new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
                new OfficialDepthOptimizer(pipes, new OfficialEconomics()), new OfficialDepthProfileValidator(pipes));
        var planner = new OfficialRoutePlanner(new OfficialRouteValidator(rules), router, pipes,
                new OfficialNetworkSizer(pipes), new OfficialExistingNetworkReconstructor(pipes),
                new OfficialVariantEconomicsCalculator(pipes, new OfficialEconomics()), depth);
        var nodes = List.of(new RouteNode("root", "new_chamber", new RouteCoordinate(0, 0), true, true, 0, null),
                new RouteNode("joint", "technical", new RouteCoordinate(51, 0), false, false, 0, null),
                new RouteNode("demand", "demand_connection", new RouteCoordinate(100, 0), false, false, 0, null));
        var edges = List.of(edge("left", "root", "joint", 0, 51), edge("right", "joint", "demand", 51, 100));
        var features = List.of(new ImportedOfficialFeature("power", "restriction", new ObjectMapper().readTree("{\"restriction_type\":\"power_cable\"}"),
                new WKTReader().read("LINESTRING (50 -5,50 5)")));
        var parameters = new OfficialRunParameters(new BigDecimal(".7"), new BigDecimal("10"), true);
        assertThat(depth.planNetwork(edges, features, parameters.getMinimumDepthM(), parameters.getMaximumDepthM(), java.util.Map.of(), java.util.Map.of()))
                .allSatisfy(e -> assertThat(e.getDepthProfile().isComplete()).isTrue());
        var method = OfficialRoutePlanner.class.getDeclaredMethod("rerouteDepthConflicts", List.class, List.class, List.class, OfficialRunParameters.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") List<RouteEdge> actual = (List<RouteEdge>) method.invoke(planner, nodes, edges, features, parameters);
        assertThat(detourSearches.get()).as("network-legal crossing needs no expensive horizontal repair search").isZero();
        assertThat(actual.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100");
        for (int i = 0; i < edges.size(); i++) assertThat(actual.get(i).getCoordinates()).usingRecursiveComparison().isEqualTo(edges.get(i).getCoordinates());
    }
    private RouteEdge edge(String id, String from, String to, double x0, double x1) {
        return new RouteEdge(id, from, to, x1 - x0, List.of(new RouteCoordinate(x0, 0), new RouteCoordinate(x1, 0)), List.of(), BigDecimal.ONE, 50);
    }
}
