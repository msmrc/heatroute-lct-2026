package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Общий синтетический пример, не содержит координат/IDs организатора. */
public final class AxisShiftTestNetwork {
    private AxisShiftTestNetwork() { }

    public static List<RouteNode> nodes() {
        return List.of(
                node("root", 0, 0, true, true, null),
                node("j", 20, 5, true, false, null),
                node("k", 40, 5, true, false, null),
                node("demand:one", 40, 25, false, false, "connection-one"),
                node("demand:two", 20, 25, false, false, "connection-two"));
    }

    public static List<RouteEdge> edges() {
        return List.of(
                edge("in", "root", "j", "2.000", 0, 0, 20, 0, 20, 5),
                edge("trunk", "j", "k", "1.000", 20, 5, 40, 5),
                edge("branch", "j", "demand:two", "1.000", 20, 5, 20, 25),
                edge("tail", "k", "demand:one", "1.000", 40, 5, 40, 25));
    }

    public static List<RouteConnection> connections() {
        return List.of(
                new RouteConnection("one", "connection-one", new BigDecimal("1.000"), "connected", null),
                new RouteConnection("two", "connection-two", new BigDecimal("1.000"), "connected", null));
    }

    public static RouteVariant variant(List<RouteNode> nodes, List<RouteEdge> edges) {
        ExistingNetworkReconstructionResult reconstruction = ExistingNetworkReconstructionResult.empty();
        return new RouteVariant("shortest", "shortest", nodes, edges, connections(),
                edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add),
                List.of(), List.of(), reconstruction,
                new OfficialVariantEconomicsCalculator(new OfficialPipeCatalog(), new OfficialEconomics())
                        .calculate(nodes, edges, connections(), reconstruction, false), null);
    }

    public static RouteNode node(String id, double x, double y, boolean chamber, boolean root, String target) {
        return new RouteNode(id, chamber ? "new_chamber" : target == null ? "technical_node" : "demand_connection",
                new RouteCoordinate(x, y), chamber, root, 0, target);
    }

    public static RouteEdge edge(String id, String from, String to, String flow, double... xy) {
        java.util.ArrayList<RouteCoordinate> points = new java.util.ArrayList<>();
        double length = 0;
        for (int i = 0; i < xy.length; i += 2) {
            RouteCoordinate point = new RouteCoordinate(xy[i], xy[i + 1]);
            if (!points.isEmpty()) length += points.get(points.size() - 1).toCoordinate().distance(point.toCoordinate());
            points.add(point);
        }
        return new RouteEdge(id, from, to, length, points,
                List.of(new RouteSection("base", null, null, points, length, null)), new BigDecimal(flow), 50);
    }

    public static ImportedOfficialFeature necessaryObstacle() {
        return new ImportedOfficialFeature("park-on-straight-axis", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "park"),
                new GeometryFactory().createPolygon(new Coordinate[] {
                        new Coordinate(28, -1), new Coordinate(32, -1), new Coordinate(32, 1),
                        new Coordinate(28, 1), new Coordinate(28, -1) }));
    }
}
