package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** §2.2 и разъяснение3: только собственный финальный ввод, а не произвольный endpoint, освобождается от отступа ОКС. */
class BuildingEndpointClearanceTest {
    private static final String CLEARANCE = "FORBIDDEN_CLEARANCE_VIOLATION";
    private final GeometryFactory factory = new GeometryFactory();
    private final ObjectMapper json = new ObjectMapper();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialRouteValidator validator = new OfficialRouteValidator(rules);
    private final OfficialAxisClearance catalog = new OfficialAxisClearance(new OfficialPipeCatalog(), new OfficialConstraintCatalog());

    @ParameterizedTest
    @ValueSource(ints = {50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400})
    void checksActualDiameterAtBothNewCameraAndExistingRootEndpoints(int diameter) {
        var building = building();
        double clearance = catalog.axisClearanceM("oks", diameter, null).doubleValue();
        for (boolean nearRoot : List.of(false, true)) {
            for (double offset : new double[] {-0.001, 0, 0.001}) {
                var near = point(-clearance - offset, 10);
                var far = point(-40, 10);
                var start = nearRoot ? near : far;
                var end = nearRoot ? far : near;
                var edge = edge(diameter, "root", "j", List.of(start, end));
                var nodes = List.of(new RouteNode("root", "existing_chamber_tie_in", coordinate(start), true, true, 2, null, 1400),
                        new RouteNode("j", "new_branch_chamber", coordinate(end), true, false, 0, null));
                var route = rules.line(List.of(start, end));
                assertThat(route.intersects(building.getMetricGeometry())).isFalse();
                var direct = validator.validate(nodes, List.of(edge), List.of(building));
                var prepared = validator.forCalculation().validate(nodes, List.of(edge), List.of(building));
                assertThat(prepared).usingRecursiveComparison().isEqualTo(direct);
                if (offset < 0) assertThat(direct).extracting(RouteValidationIssue::getCode).contains(CLEARANCE);
                else assertThat(direct).isEmpty();
                var base = rules.baseConstraints(List.of(building), diameter);
                assertThat(rules.lineAllowed(route, rules.applicableConstraints(base, Set.of(), start, end)))
                        .as("DU%s, root=%s, offset=%s", diameter, nearRoot, offset).isEqualTo(offset >= 0);
                assertThat(base.get(0).blocked()).isNotSameAs(base.get(0).source());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 500, 900})
    void searchCannotEnterOrLeaveAnArbitraryEndpointWithinTheBuildingSetback(int diameter) {
        var router = new OfficialObstacleRouter(rules);
        var environment = router.prepare(List.of(building()));
        double clearance = catalog.axisClearanceM("oks", diameter, null).doubleValue();
        var insideSetback = point(-clearance + 0.1, 10);
        var far = point(-40, 10);
        assertThat(router.find(far, insideSetback, diameter, environment, Set.of(), RoutePreference.SHORTEST)).isNull();
        assertThat(router.find(insideSetback, far, diameter, environment, Set.of(), RoutePreference.SHORTEST)).isNull();
        var legal = point(-clearance - 0.01, 10);
        assertThat(router.find(far, legal, diameter, environment, Set.of(), RoutePreference.SHORTEST)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 500, 900})
    void ownFinalStraightInputStillHasTheExplicitException(int diameter) {
        var building = building();
        var target = point(1, 10);
        var root = point(-40, 10);
        var demand = new ImportedOfficialFeature("demand", "oks_connection_point", json.createObjectNode().put("flow_tph", 1),
                factory.createPoint(target));
        var nodes = List.of(new RouteNode("root", "existing_chamber_tie_in", coordinate(root), true, true, 2, null, 1400),
                new RouteNode("demand", "demand_connection", coordinate(target), false, false, 0, "demand"));
        var edge = edge(diameter, "root", "demand", List.of(root, target));
        assertThat(validator.validate(nodes, List.of(edge), List.of(building, demand))).isEmpty();
        assertThat(validator.forCalculation().validate(nodes, List.of(edge), List.of(building, demand))).isEmpty();
    }

    @Test
    void ownTerminalInputCannotRemoveClearanceToAnotherBuilding() {
        var target = point(1, 10);
        var root = point(-40, 10);
        var foreign = new ImportedOfficialFeature("foreign", "restriction", json.createObjectNode().put("restriction_type", "oks"),
                factory.createPolygon(new Coordinate[] {point(-5, 14), point(-3, 14), point(-3, 18), point(-5, 18), point(-5, 14)}));
        var nodes = List.of(new RouteNode("root", "existing_chamber_tie_in", coordinate(root), true, true, 2, null, 100),
                new RouteNode("demand", "demand_connection", coordinate(target), false, false, 0, "demand"));
        var edge = edge(100, "root", "demand", List.of(root, target));
        assertThat(validator.validate(nodes, List.of(edge), List.of(building(), foreign)))
                .extracting(RouteValidationIssue::getCode).contains(CLEARANCE);
    }

    private ImportedOfficialFeature building() {
        return new ImportedOfficialFeature("building", "restriction", json.createObjectNode().put("restriction_type", "oks"),
                factory.createPolygon(new Coordinate[] {point(0, 0), point(20, 0), point(20, 20), point(0, 20), point(0, 0)}));
    }
    private RouteEdge edge(int diameter, String from, String to, List<Coordinate> points) {
        LineString route = rules.line(points);
        return new RouteEdge("edge", from, to, route.getLength(), points.stream().map(this::coordinate).collect(Collectors.toList()),
                rules.sections(route, List.of()), BigDecimal.ONE, diameter);
    }
    private RouteCoordinate coordinate(Coordinate point) { return new RouteCoordinate(point.x, point.y); }
    private Coordinate point(double x, double y) { return new Coordinate(414000 + x, 6173000 + y); }
}
