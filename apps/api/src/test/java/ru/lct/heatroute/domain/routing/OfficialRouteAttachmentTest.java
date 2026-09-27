package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialRouteAttachmentTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();

    @Test
    void fullNearestChamberDoesNotReceiveAFifthRay() {
        OfficialRoutePlanner.TreeAttachment choice = planner(new OfficialRouteValidator(rules))
                .chooseTreeAttachment(demand(), draft(true), router.prepare(List.of()));

        assertThat(choice).isNotNull();
        assertThat(choice.junction().getId()).isNotEqualTo("a");
    }

    @Test
    void rejectionByFinalGeometryContinuesWithNextCostedCandidate() throws Exception {
        ImportedOfficialFeature finalObstacle = new ImportedOfficialFeature("final-oks", "oks_existing",
                new ObjectMapper().readTree("{}"),
                new WKTReader().read("POLYGON ((95 45,105 45,105 55,95 55,95 45))"));
        // Моделируем более строгую окончательную проверку: все пересечения проверяет настоящий JTS.
        OfficialRouteValidator finalValidator = new OfficialRouteValidator(rules) {
            @Override
            public List<RouteValidationIssue> validate(List<RouteNode> nodes, List<RouteEdge> edges,
                    List<ImportedOfficialFeature> features) {
                List<ImportedOfficialFeature> complete = new ArrayList<>(features);
                complete.add(finalObstacle);
                return super.validate(nodes, edges, complete);
            }
        };
        OfficialRoutePlanner.TreeAttachment choice = planner(finalValidator)
                .chooseTreeAttachment(demand(), draft(false), router.prepare(List.of()));

        assertThat(choice).isNotNull();
        assertThat(choice.junction().getId()).isNotEqualTo("a");
    }

    @Test
    void fourthRayUsesAConstructibleApproachInsteadOfAnExtraNearbyChamber() {
        List<RouteNode> nodes = List.of(node("root", -100, 0, true, true),
                node("a", 0, 0, true, false), node("demand:one", 100, 0, false, false),
                node("demand:two", 0, 100, false, false));
        OfficialRoutePlanner.VariantDraft source = new OfficialRoutePlanner.VariantDraft(nodes,
                List.of(edge(nodes.get(0), nodes.get(1)), edge(nodes.get(1), nodes.get(2)),
                        edge(nodes.get(1), nodes.get(3))),
                List.of(new RouteConnection("one", "one", BigDecimal.ONE, "connected", null),
                        new RouteConnection("two", "two", BigDecimal.ONE, "connected", null)));
        OfficialRoutePlanner.Demand demand = new OfficialRoutePlanner.Demand("new", "new",
                new Coordinate(10, -16), BigDecimal.ONE, null);

        OfficialRoutePlanner.TreeAttachment choice = planner(new OfficialRouteValidator(rules))
                .chooseTreeAttachment(demand, source, router.prepare(List.of()));

        assertThat(choice).isNotNull();
        assertThat(choice.junction().getId()).isEqualTo("a");
    }

    private OfficialRoutePlanner.Demand demand() {
        return new OfficialRoutePlanner.Demand("new", "new", new Coordinate(100, 100),
                BigDecimal.ONE, null);
    }

    private OfficialRoutePlanner.VariantDraft draft(boolean fullNearest) {
        List<RouteNode> nodes = new ArrayList<>(List.of(
                node("root", 0, 0, true, true), node("a", 100, 0, true, false),
                node("b", 125, 0, true, false), node("demand:one", 100, -100, false, false),
                node("demand:two", 125, -100, false, false),
                node("demand:three", 225, 0, false, false)));
        List<RouteEdge> edges = new ArrayList<>(List.of(
                edge(nodes.get(0), nodes.get(1)), edge(nodes.get(1), nodes.get(2)),
                edge(nodes.get(1), nodes.get(3)), edge(nodes.get(2), nodes.get(4)),
                edge(nodes.get(2), nodes.get(5))));
        List<RouteConnection> connections = new ArrayList<>();
        for (String id : List.of("one", "two", "three")) {
            connections.add(new RouteConnection(id, id, BigDecimal.ONE, "connected", null));
        }
        if (fullNearest) {
            RouteNode fourth = node("demand:four", 100, 50, false, false);
            nodes.add(fourth);
            edges.add(edge(nodes.get(1), fourth));
            connections.add(new RouteConnection("four", "four", BigDecimal.ONE, "connected", null));
        }
        return new OfficialRoutePlanner.VariantDraft(nodes, edges, connections);
    }

    private RouteNode node(String id, double x, double y, boolean chamber, boolean root) {
        return new RouteNode(id, root ? "existing_chamber_tie_in"
                : chamber ? "new_branch_chamber" : "demand_connection",
                new RouteCoordinate(x, y), chamber, root, root ? 2 : 0, root ? "network" : null);
    }

    private RouteEdge edge(RouteNode start, RouteNode end) {
        return new RouteEdge(start.getId() + ":" + end.getId(), start.getId(), end.getId(),
                start.getCoordinate().toCoordinate().distance(end.getCoordinate().toCoordinate()),
                List.of(start.getCoordinate(), end.getCoordinate()), List.of(), BigDecimal.ONE, 50);
    }

    private OfficialRoutePlanner planner(OfficialRouteValidator validator) {
        return new OfficialRoutePlanner(validator, router, pipes, new OfficialNetworkSizer(pipes),
                new OfficialExistingNetworkReconstructor(pipes),
                new OfficialVariantEconomicsCalculator(pipes, new OfficialEconomics()),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
                        new OfficialDepthOptimizer(pipes, new OfficialEconomics()),
                        new OfficialDepthProfileValidator(pipes)));
    }
}
