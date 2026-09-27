package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.NetworkSizingIssue;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;

class OfficialFinalSizingTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();

    @Test
    void regularizationAcrossLengthBoundaryRequiresFinalDiameterPromotion() {
        List<Coordinate> finalCoordinates = List.of(new Coordinate(0, 0),
                new Coordinate(85, 85 * Math.tan(Math.toRadians(30))), new Coordinate(170, 0));
        LineString line = rules.line(finalCoordinates);
        RoutePath replacement = new RoutePath(finalCoordinates, rules.sections(line, List.of()), line.getLength());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules) {
            @Override
            RoutePath regularize(List<Coordinate> coordinates, int diameter,
                    OfficialRoutingEnvironment environment, Set<String> exemptions, List<LineString> avoidance) {
                return replacement;
            }
        };
        List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(0, 0), true, true, 2, "existing"),
                new RouteNode("demand:one", "demand_connection", new RouteCoordinate(170, 0), false, false, 0, null));
        // Возврат по X создаёт отклонения больше 90°: ремонт нужен по §2.1 ТЗ.
        List<Coordinate> original = List.of(new Coordinate(0, 0), new Coordinate(85, 1),
                new Coordinate(84, 0), new Coordinate(170, 0));
        LineString originalLine = rules.line(original);
        RouteEdge edge = new RouteEdge("edge", "root", "demand:one", originalLine.getLength(),
                original.stream().map(c -> new RouteCoordinate(c.x, c.y)).collect(Collectors.toList()),
                rules.sections(originalLine, List.of()), new BigDecimal("3.5"), 50);
        OfficialRoutePlanner.VariantDraft draft = new OfficialRoutePlanner.VariantDraft(nodes, List.of(edge),
                List.of(new RouteConnection("one", "one", new BigDecimal("3.5"), "connected", null)));

        RouteVariant result = planner(router).finish("balanced", "engineering", draft, List.of(),
                OfficialRunParameters.defaults(), false, router.prepare(List.of()));

        assertThat(originalLine.getLength()).isLessThan(181);
        assertThat(result.getEdges()).singleElement().satisfies(finalEdge -> {
            assertThat(finalEdge.getLengthM()).isGreaterThan(new BigDecimal("181"));
            assertThat(finalEdge.getDiameter()).isEqualTo(65);
        });
        assertThat(result.isValid()).isTrue();
        assertThat(result.getSizingIssues()).isEmpty();
    }

    @Test
    void sizingFailureCannotBeAdvertisedAsValidEvenWithoutGeometryIssues() {
        RouteVariant result = new RouteVariant("bad", "cheapest", List.of(), List.of(), List.of(),
                BigDecimal.ZERO, List.of(),
                List.of(new NetworkSizingIssue("MAX_CONTINUOUS_LENGTH_EXCEEDED", "edge", "No valid diameter")),
                ExistingNetworkReconstructionResult.empty(), null, null);
        assertThat(result.isValid()).isFalse();
        assertThat(result.withRank(1).isValid()).isFalse();
        assertThat(result.withEngineeringIssues(List.of()).isValid()).isFalse();
    }

    private OfficialRoutePlanner planner(OfficialObstacleRouter router) {
        return new OfficialRoutePlanner(new OfficialRouteValidator(rules), router, pipes,
                new OfficialNetworkSizer(pipes), new OfficialExistingNetworkReconstructor(pipes),
                new OfficialVariantEconomicsCalculator(pipes, new OfficialEconomics()),
                new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
                        new OfficialDepthOptimizer(pipes, new OfficialEconomics()), new OfficialDepthProfileValidator(pipes)));
    }
}
