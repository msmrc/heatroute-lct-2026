package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.NormalEgress;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class ValidationNormalEgressMemoTest {
    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validationSessionMatchesStandaloneAcrossRepeatedAndMutatedWindows() {
        OfficialRouteGeometryRules rules = rules();
        Fixture fixture = new Fixture();
        OfficialRouteValidator validator = new OfficialRouteValidator(rules);
        OfficialRouteValidator.ValidationSession session = validator.forCalculation();

        assertSameIssues(validator, session, fixture);
        assertSameIssues(validator, session, fixture);

        ((ObjectNode) fixture.building.getAttributes()).put("memo_marker", "changed");
        assertSameIssues(validator, session, fixture);

        fixture.building.getMetricGeometry().apply((CoordinateFilter) coordinate -> coordinate.x -= 10);
        fixture.building.getMetricGeometry().geometryChanged();
        // После уточнения 29.09 смена ближайшей стены не делает фактический прямой ввод незаконным.
        assertThat(assertSameIssues(validator, session, fixture)).isEmpty();
        fixture.features.add(restriction("blocking", "park", -20, 8, -18, 12));
        assertThat(assertSameIssues(validator, session, fixture))
                .extracting(RouteValidationIssue::getCode).contains("FORBIDDEN_CLEARANCE_VIOLATION");

        fixture.features.add(restriction("remote", "park", 300, 0, 320, 20));
        assertSameIssues(validator, session, fixture);
        Collections.reverse(fixture.features);
        assertSameIssues(validator, session, fixture);
    }

    @Test
    void mandatoryValidationUsesRawNearestEvenWhenPaddedPreparationIsLonger() {
        OfficialRouteGeometryRules rules = rules();
        ImportedOfficialFeature building = oks("building", 0, 0, 20, 20);
        List<ImportedOfficialFeature> features = List.of(building);
        Coordinate connection = new Coordinate(1, 10);
        NormalEgress raw = rules.prepareNearestLegalNormalEgresses(
                features, 50, connection, RouteTraversal.REVERSED).get(0);
        NormalEgress padded = rules.prepareNormalEgresses(
                features, 50, connection, RouteTraversal.REVERSED).get(0);
        double rawLength = raw.start().distance(raw.exit());
        double inputLength = rawLength + .1;
        assertThat(padded.start().distance(padded.exit())).isGreaterThan(inputLength);
        Coordinate adjacent = along(connection, raw.exit(), inputLength / rawLength);
        LineString route = FACTORY.createLineString(new Coordinate[] {adjacent, connection});
        RouteEdge edge = edge(route, 50);
        PreparedNormalEgressMemo memo = new PreparedNormalEgressMemo(rules);

        assertThat(rules.validateMandatoryEgress(edge, route, features, 50, connection)).isEmpty();
        assertThat(rules.validateMandatoryEgress(edge, route, features, 50, connection, memo)).isEmpty();
    }

    @Test
    void customGeometryRuleHooksRemainOnStandaloneValidationPath() {
        AtomicInteger actualLegChecks = new AtomicInteger();
        OfficialRouteGeometryRules custom = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()) {
            @Override Optional<NormalEgress> checkedTerminalEgress(List<ImportedOfficialFeature> features,
                    int diameter, Coordinate point, Coordinate endpoint, Coordinate adjacent) {
                actualLegChecks.incrementAndGet();
                return super.checkedTerminalEgress(features, diameter, point, endpoint, adjacent);
            }
        };
        Fixture fixture = new Fixture();
        OfficialRouteValidator validator = new OfficialRouteValidator(custom);
        List<RouteValidationIssue> standalone = validator.validate(fixture.nodes, List.of(fixture.edge), fixture.features);
        List<RouteValidationIssue> session = validator.forCalculation().validate(
                fixture.nodes, List.of(fixture.edge), fixture.features);

        assertThat(session).usingRecursiveComparison().isEqualTo(standalone);
        assertThat(actualLegChecks.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void customCatalogOrCrossingProviderDisablesSessionMemoWithoutChangingValidation() throws Exception {
        for (OfficialRouteGeometryRules rules : List.of(
                new OfficialRouteGeometryRules(new OfficialConstraintCatalog() { }, new OfficialCrossingGeometry()),
                new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry() { }))) {
            Fixture fixture = new Fixture();
            OfficialRouteValidator validator = new OfficialRouteValidator(rules);
            OfficialRouteValidator.ValidationSession session = validator.forCalculation();
            assertThat(sessionMemo(session)).isNull();
            assertSameIssues(validator, session, fixture);
        }
    }

    private static List<RouteValidationIssue> assertSameIssues(OfficialRouteValidator validator,
            OfficialRouteValidator.ValidationSession session, Fixture fixture) {
        List<RouteValidationIssue> standalone = validator.validate(fixture.nodes, List.of(fixture.edge), fixture.features);
        List<RouteValidationIssue> prepared = session.validate(fixture.nodes, List.of(fixture.edge), fixture.features);
        assertThat(prepared).usingRecursiveComparison().isEqualTo(standalone);
        return standalone;
    }

    private static Coordinate along(Coordinate start, Coordinate end, double factor) {
        return new Coordinate(start.x + (end.x - start.x) * factor,
                start.y + (end.y - start.y) * factor);
    }

    private static Object sessionMemo(OfficialRouteValidator.ValidationSession session) throws Exception {
        Field field = session.getClass().getDeclaredField("normalEgresses");
        field.setAccessible(true);
        return field.get(session);
    }

    private static OfficialRouteGeometryRules rules() {
        return new OfficialRouteGeometryRules(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    }

    private static ImportedOfficialFeature oks(String id, double minX, double minY, double maxX, double maxY) {
        return restriction(id, "oks", minX, minY, maxX, maxY);
    }

    private static ImportedOfficialFeature restriction(
            String id, String type, double minX, double minY, double maxX, double maxY) {
        return new ImportedOfficialFeature(id, "restriction",
                JSON.createObjectNode().put("restriction_type", type), FACTORY.createPolygon(new Coordinate[] {
                        new Coordinate(minX, minY), new Coordinate(maxX, minY),
                        new Coordinate(maxX, maxY), new Coordinate(minX, maxY), new Coordinate(minX, minY)}));
    }

    private static RouteEdge edge(LineString route, int diameter) {
        List<RouteCoordinate> coordinates = new ArrayList<>();
        for (Coordinate coordinate : route.getCoordinates()) {
            coordinates.add(new RouteCoordinate(coordinate.x, coordinate.y));
        }
        return new RouteEdge("edge", "root", "demand", route.getLength(), coordinates,
                List.of(), BigDecimal.ONE, diameter);
    }

    private static final class Fixture {
        private final Coordinate root = new Coordinate(-40, 10);
        private final Coordinate target = new Coordinate(1, 10);
        private final ImportedOfficialFeature building = oks("building", 0, 0, 20, 20);
        private final ImportedOfficialFeature demand = new ImportedOfficialFeature("demand", "oks_connection_point",
                JSON.createObjectNode().put("flow_tph", 1), FACTORY.createPoint(target));
        private final List<ImportedOfficialFeature> features = new ArrayList<>(List.of(building, demand));
        private final List<RouteNode> nodes = List.of(
                new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(root.x, root.y),
                        true, true, 2, null, 1400),
                new RouteNode("demand", "demand_connection", new RouteCoordinate(target.x, target.y),
                        false, false, 0, "demand"));
        private final RouteEdge edge = edge(FACTORY.createLineString(new Coordinate[] {root, target}), 50);
    }
}
