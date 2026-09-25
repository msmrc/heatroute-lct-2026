package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class ChamberTerminalFallbackTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void findsTheFreeChamberRayAroundTheBuildingInRotatedFrames() {
        for (double angle : new double[] {0, 0.37, Math.PI / 2, 2.1}) {
            Fixture fixture = new Fixture(angle);
            List<RoutePath> alternatives = ChamberTerminalFallback.build(fixture.edge, fixture.terminal,
                    fixture.chamber, angle, router, fixture.environment, List.of());
            assertThat(alternatives).hasSizeBetween(1, 4);
            for (RoutePath path : alternatives) fixture.assertValid(path);
            List<List<RoutePath>> choices = new ArrayList<>();
            choices.add(List.of(fixture.fixed(-30, 0)));
            choices.add(List.of(fixture.fixed(30, 0)));
            choices.add(List.of(fixture.fixed(0, -30)));
            choices.add(alternatives);
            assertThat(CorridorJunctionAssignment.choosePrecise(fixture.chamber, choices))
                    .as("Free north ray with angle %s", angle).isNotNull();
        }
    }

    @Test void retainsOtherRoutesAndRefusesBlockedMandatoryApproaches() {
        Fixture fixture = new Fixture(0.37);
        List<RouteEdge> barriers = new ArrayList<>();
        double[][] endpoints = {{-1,-1,1,-1},{1,-1,1,1},{1,1,-1,1},{-1,1,-1,-1}};
        for (int i=0;i<endpoints.length;i++) {
            double[] ends=endpoints[i];
            barriers.add(edge("barrier"+i,"a"+i,"b"+i,
                    List.of(fixture.point(ends[0],ends[1]),fixture.point(ends[2],ends[3]))));
        }
        assertThat(ChamberTerminalFallback.build(fixture.edge, fixture.terminal, fixture.chamber,
                fixture.angle, router, fixture.environment, barriers)).isEmpty();
    }

    private final class Fixture {
        final double angle;
        final Coordinate chamber, target;
        final RouteNode terminal;
        final RouteEdge edge;
        final OfficialRoutingEnvironment environment;
        final ImportedOfficialFeature own;
        Fixture(double angle) {
            this.angle=angle; chamber=point(0,0); target=point(2,13);
            terminal=new RouteNode("demand", "demand_connection", coordinate(target),false,false,0,null);
            edge=edge("edge","camera","demand",List.of(chamber,target));
            // The demand's nearest wall points across the corridor axes, requiring an outside detour.
            own=new ImportedOfficialFeature("own","restriction",mapper.createObjectNode().put("restriction_type","oks"),
                    new GeometryFactory().createPolygon(new Coordinate[] {
                            point(1,8),point(8,8),point(8,22),point(1,22),point(1,8)}));
            environment=router.prepare(List.of(own));
        }
        Coordinate point(double x,double y) {
            return new Coordinate(412000+x*Math.cos(angle)-y*Math.sin(angle),
                    6172000+x*Math.sin(angle)+y*Math.cos(angle));
        }
        RoutePath fixed(double x,double y) {
            List<Coordinate> coordinates=List.of(point(x,y),chamber);
            return new RoutePath(coordinates,List.of(),rules.line(coordinates).getLength());
        }
        void assertValid(RoutePath reversed) {
            assertThat(reversed.coordinates().get(0).distance(target)).isLessThan(0.001);
            RoutePath flow=reversed.reversed();
            var summary=ExpertChamberGeometryRules.summarize(flow.coordinates().stream()
                    .map(ChamberTerminalFallbackTest.this::coordinate).collect(Collectors.toList()));
            assertThat(summary.getFirstBendDistanceM()).isGreaterThanOrEqualTo(2);
            assertThat(summary.hasInvalidBendAngle()).isFalse();
            assertThat(summary.hasShortBendSpacing()).isFalse();
            var egress=environment.normalEgressTowards(50,target,chamber,RouteTraversal.REVERSED).orElseThrow();
            assertThat(router.terminalRouteAllowed(flow.coordinates(),50,environment,Set.of(),List.of(),egress)).isTrue();
            assertThat(new EngineeringRouteEvaluator().evaluate(List.of(edge("candidate","camera","demand",flow.coordinates()))).isCompliant()).isTrue();
        }
    }
    private RouteCoordinate coordinate(Coordinate point) { return new RouteCoordinate(point.x,point.y); }
    private RouteEdge edge(String id,String from,String to,List<Coordinate> points) {
        return new RouteEdge(id,from,to,rules.line(points).getLength(),points.stream().map(this::coordinate).collect(Collectors.toList()),
                List.of(),BigDecimal.ONE,50);
    }
}
