package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialRouteValidatorGeometryBatchTest {
    // Независимые табличные R + W/2; не используем расчёт буферов для ожидаемого результата.
    private static final Map<Integer, Double> BUILDING_AXIS_CLEARANCES_M = Map.of(
            400, 5.0 + 1.370 / 2,
            500, 7.0 + 1.670 / 2,
            800, 7.0 + 2.250 / 2,
            900, 9.0 + 2.450 / 2,
            1400, 9.0 + 3.450 / 2);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preparesConstraintsOncePerActualDiameterAndOnlyWithinOneInvocation() throws Exception {
        CountingRules rules = new CountingRules();
        OfficialRouteValidator validator = new OfficialRouteValidator(rules);
        List<ImportedOfficialFeature> features = List.of(oks("own", "POLYGON ((-5 -5, 5 -5, 5 5, -5 5, -5 -5))"));
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        addEdge(nodes, edges, "default", null, null, false, "LINESTRING (-30 20, 30 20)");
        addEdge(nodes, edges, "du50", 50, null, false, "LINESTRING (-30 30, 30 30)");
        addEdge(nodes, edges, "du500", 500, null, false, "LINESTRING (-30 40, 30 40)");
        // Стена x=-5; ДУ500 требует 7 + 1.670/2 + 0.25 м снаружи до поворота.
        addEdge(nodes, edges, "own-egress", 500, null, true, "LINESTRING (-30 0, -13.085 0, 0 0)");

        assertThat(validator.validate(nodes, edges, features)).isEmpty();
        assertThat(rules.preparedDiameters).containsExactly(50, 500);
        validator.validate(nodes, edges, features);
        assertThat(rules.preparedDiameters).containsExactly(50, 500, 50, 500);

        List<ImportedOfficialFeature> changedFeatures = List.of(
                oks("added", "POLYGON ((-5 18, 5 18, 5 22, -5 22, -5 18))"));
        assertThat(validator.validate(nodes, edges, changedFeatures))
                .extracting(RouteValidationIssue::getCode).contains("FORBIDDEN_CLEARANCE_VIOLATION");
    }

    @Test
    void matchesFreshPerEdgePreparationAtEveryBuildingClearanceBoundary() throws Exception {
        List<ImportedOfficialFeature> features = List.of(oks("own", "POLYGON ((-5 -5, 5 -5, 5 5, -5 5, -5 -5))"));
        for (int diameter : List.of(400, 500, 800, 900, 1400)) {
            double clearance = BUILDING_AXIS_CLEARANCES_M.get(diameter);
            for (double offset : List.of(-0.001, 0.0, 0.001)) {
                List<RouteNode> nodes = new ArrayList<>();
                List<RouteEdge> edges = new ArrayList<>();
                double y = 5 + clearance + offset;
                addEdge(nodes, edges, "boundary", diameter, null, false,
                        "LINESTRING (-30 " + y + ", 30 " + y + ")");
                List<RouteValidationIssue> actual = assertEquivalent(nodes, edges, features);
                assertThat(actual.stream().anyMatch(issue -> "FORBIDDEN_CLEARANCE_VIOLATION".equals(issue.getCode())))
                        .as("DU %s at clearance %s", diameter, clearance + offset)
                        .isEqualTo(offset < 0);
            }
        }
    }

    @Test
    void keepsTypedRootContactsAndEndpointSetbackRelaxationSeparatePerEdge() throws Exception {
        List<ImportedOfficialFeature> features = List.of(oks("own", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        addEdge(nodes, edges, "exempt", 100, "own", false, "LINESTRING (-20 10, 40 10)");
        addEdge(nodes, edges, "not-exempt", 100, null, false, "LINESTRING (-20 12, 40 12)");
        addEdge(nodes, edges, "endpoint-in-setback", 100, null, false, "LINESTRING (-3 18, -30 18)");
        addEdge(nodes, edges, "no-relaxation", 100, null, false, "LINESTRING (-3 -20, -3 40)");
        List<RouteValidationIssue> actual = assertEquivalent(nodes, edges, features);
        assertThat(actual.stream().filter(issue -> "FORBIDDEN_CLEARANCE_VIOLATION".equals(issue.getCode()))
                .map(RouteValidationIssue::getSubjectId).collect(Collectors.toList()))
                .containsExactly("exempt", "no-relaxation", "not-exempt");
    }

    @Test
    void keepsOwnOksPrefixAndFullPolylineObstacleChecks() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                oks("own", "POLYGON ((-5 -5, 5 -5, 5 5, -5 5, -5 -5))"),
                oks("remote", "POLYGON ((20 40, 30 40, 30 50, 20 50, 20 40))"));
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        addEdge(nodes, edges, "valid-egress", 50, null, true, "LINESTRING (-30 0, -10.45 0, 0 0)");
        addEdge(nodes, edges, "own-prefix", 50, null, true,
                "LINESTRING (-30 2, 15 2, 15 0, 10.45 0, 0 0)");
        addEdge(nodes, edges, "distant-detour", 50, null, false,
                "LINESTRING (0 -30, 25 -30, 25 60, 50 60, 50 -30)");
        List<RouteValidationIssue> actual = assertEquivalent(nodes, edges, features);
        assertThat(actual).noneMatch(issue -> "OKS_NORMAL_EGRESS_VIOLATION".equals(issue.getCode()));
        assertThat(actual.stream().filter(issue -> "FORBIDDEN_CLEARANCE_VIOLATION".equals(issue.getCode()))
                .map(RouteValidationIssue::getSubjectId).collect(Collectors.toList()))
                .contains("own-prefix", "distant-detour").doesNotContain("valid-egress");
    }

    @Test
    void keepsExactOwnPrefixAxisClearanceAtEveryFinalDiameterBoundary() throws Exception {
        List<ImportedOfficialFeature> features = List.of(oks("own", "POLYGON ((-5 -5, 5 -5, 5 5, -5 5, -5 -5))"));
        for (int diameter : List.of(400, 500, 800, 900, 1400)) {
            double clearance = BUILDING_AXIS_CLEARANCES_M.get(diameter);
            for (double offset : List.of(-0.001, 0.0, 0.001)) {
                List<RouteNode> nodes = new ArrayList<>();
                List<RouteEdge> edges = new ArrayList<>();
                double x = -5 - clearance - offset;
                double finalNormalY = 5 + clearance + 0.25;
                addEdge(nodes, edges, "own-prefix", diameter, null, true,
                        "LINESTRING (-30 0, " + x + " 0, " + x + " 30, 0 30, 0 " + finalNormalY + ", 0 0)");
                List<RouteValidationIssue> actual = assertEquivalent(nodes, edges, features);
                assertThat(actual).noneMatch(issue -> "OKS_NORMAL_EGRESS_VIOLATION".equals(issue.getCode()));
                assertThat(actual.stream().anyMatch(issue -> "FORBIDDEN_CLEARANCE_VIOLATION".equals(issue.getCode())))
                        .as("own prefix at DU %s, R+W/2=%s, offset=%s", diameter, clearance, offset)
                        .isEqualTo(offset < 0);
            }
        }
    }

    @Test
    void preservesSpecialCrossingAndMissingSectionDiagnostics() throws Exception {
        var attributes = mapper.createObjectNode().put("restriction_type", "road");
        List<ImportedOfficialFeature> features = List.of(new ImportedOfficialFeature(
                "road", "restriction", attributes,
                new WKTReader().read("POLYGON ((-1 -50, 1 -50, 1 50, -1 50, -1 -50))")));
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        // G2 измеряет угол по границе полигона; оба прохода имеют полные защитные прямые 3 м.
        addEdge(nodes, edges, "crossing", 50, null, false, "LINESTRING (-20 -20, 20 20)");
        addEdge(nodes, edges, "acute", 50, null, false, "LINESTRING (-10 15, 10 45)");
        List<RouteValidationIssue> actual = assertEquivalent(nodes, edges, features);
        assertThat(actual).extracting(RouteValidationIssue::getCode)
                .contains("SPECIAL_CROSSING_ANGLE_VIOLATION", "SPECIAL_CROSSING_SECTION_MISSING");
        assertThat(actual).filteredOn(issue -> "SPECIAL_CROSSING_ANGLE_VIOLATION".equals(issue.getCode()))
                .extracting(RouteValidationIssue::getSubjectId).containsExactly("acute");
        assertThat(actual).filteredOn(issue -> "SPECIAL_CROSSING_SECTION_MISSING".equals(issue.getCode()))
                .extracting(RouteValidationIssue::getSubjectId).containsExactly("crossing");
    }

    private List<RouteValidationIssue> assertEquivalent(
            List<RouteNode> nodes, List<RouteEdge> edges, List<ImportedOfficialFeature> features) {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        List<RouteValidationIssue> actual = new OfficialRouteValidator(rules).validate(nodes, edges, features);
        List<RouteValidationIssue> rebuilt = new OfficialRouteValidator(new PerEdgeRebuildingRules(features))
                .validate(nodes, edges, features);
        assertThat(actual).usingRecursiveComparison().isEqualTo(rebuilt);
        return actual;
    }

    private ImportedOfficialFeature oks(String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "oks_existing", mapper.createObjectNode(), new WKTReader().read(wkt));
    }

    private void addEdge(List<RouteNode> nodes, List<RouteEdge> edges, String id, Integer diameter,
            String exemptRootTarget, boolean demand, String wkt) throws Exception {
        LineString line = (LineString) new WKTReader().read(wkt);
        List<RouteCoordinate> coordinates = new ArrayList<>();
        for (Coordinate coordinate : line.getCoordinates()) {
            coordinates.add(new RouteCoordinate(coordinate.x, coordinate.y));
        }
        nodes.add(new RouteNode(id + "-root", "existing_chamber", coordinates.get(0), true, true, 2, exemptRootTarget));
        nodes.add(new RouteNode(id + "-end", demand ? "demand_connection" : "technical_node",
                coordinates.get(coordinates.size() - 1), false, false, 0, null));
        edges.add(new RouteEdge(id, id + "-root", id + "-end", line.getLength(), coordinates, List.of(), null, diameter));
    }

    private static final class CountingRules extends OfficialRouteGeometryRules {
        private final List<Integer> preparedDiameters = new ArrayList<>();

        private CountingRules() {
            super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        }

        @Override
        List<Constraint> baseConstraints(List<ImportedOfficialFeature> features, int diameter) {
            preparedDiameters.add(diameter);
            return super.baseConstraints(features, diameter);
        }
    }

    /** Эталон независимо строит все буферы каждого ребра до применения локальных контактов. */
    private static final class PerEdgeRebuildingRules extends OfficialRouteGeometryRules {
        private final List<ImportedOfficialFeature> features;
        private final Map<List<Constraint>, Integer> diameters = new IdentityHashMap<>();

        private PerEdgeRebuildingRules(List<ImportedOfficialFeature> features) {
            super(new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
            this.features = features;
        }

        @Override
        List<Constraint> baseConstraints(List<ImportedOfficialFeature> features, int diameter) {
            List<Constraint> constraints = super.baseConstraints(features, diameter);
            diameters.put(constraints, diameter);
            return constraints;
        }

        @Override
        List<Constraint> localTieInConstraints(List<Constraint> base, Set<String> targets,
                Coordinate start, Coordinate end) {
            return super.localTieInConstraints(super.baseConstraints(features, diameters.get(base)),
                    targets, start, end);
        }
    }
}
