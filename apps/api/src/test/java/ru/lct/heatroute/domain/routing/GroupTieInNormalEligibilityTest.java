package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ExistingNetworkIncidence;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;

/** Невозможные исходные нормали не занимают ограниченные места корней группового поиска. */
class GroupTieInNormalEligibilityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeometryFactory geometries = new GeometryFactory();

    @ParameterizedTest
    @CsvSource({"0,0,0", "31,0,0", "90,410000,6100000", "173,430000.123,6200000.789"})
    void filtersThreeNearerImpossibleRootsBeforeChoosingTwoFartherFeasibleRoots(double degrees, double x, double y) {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        String prefix = "measured-" + degrees + "-";
        for (int index = 1; index <= 3; index++) {
            addChamber(features, prefix + "blocked-" + index, point(index * 10, 0, degrees, x, y),
                    point(index * 10 + 8, 0, degrees, x, y),
                    point(index * 10 - 8, 2, degrees, x, y));
        }
        addChamber(features, prefix + "available-a", point(45, 0, degrees, x, y),
                point(45, -8, degrees, x, y), point(45, 8, degrees, x, y));
        addChamber(features, prefix + "available-b", point(65, 0, degrees, x, y),
                point(65, -8, degrees, x, y), point(65, 8, degrees, x, y));

        List<TieInCandidate> roots = candidates(features, point(0, 0, degrees, x, y),
                new ExistingNetworkIncidence(features).countsByChamber(features));

        assertThat(roots).extracting(TieInCandidate::getTargetId)
                .containsExactly(prefix + "available-a", prefix + "available-b");
        assertThat(roots).allMatch(root -> !root.isNewChamberRequired());
    }

    @Test
    void preservesSyntheticChambersWithoutSourceAxes() {
        List<ImportedOfficialFeature> features = List.of(chamber("near", new Coordinate(10, 0)),
                chamber("far", new Coordinate(20, 0)), chamber("outside-cap", new Coordinate(30, 0)));
        assertThat(candidates(features, new Coordinate(0, 0), Map.of("near", 2, "far", 2, "outside-cap", 2)))
                .extracting(TieInCandidate::getTargetId).containsExactly("near", "far");
    }

    @Test
    void coincidentExistingRaysCannotBorrowAnApparentlyFreePerpendicular() {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        addChamber(features, "overlapping", new Coordinate(10, 0), new Coordinate(16, 0), new Coordinate(18, 0));
        addChamber(features, "straight", new Coordinate(30, 0), new Coordinate(30, -8), new Coordinate(30, 8));
        assertThat(candidates(features, new Coordinate(0, 0), new ExistingNetworkIncidence(features).countsByChamber(features)))
                .extracting(TieInCandidate::getTargetId).containsExactly("straight");
    }

    private List<TieInCandidate> candidates(List<ImportedOfficialFeature> features, Coordinate demand,
            Map<String, Integer> incidentCounts) {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialRoutingEnvironment environment = new OfficialObstacleRouter(rules).prepare(features);
        return ReflectionTestUtils.invokeMethod(new OfficialDatasetRoutingTest().planner(), "groupTieInCandidates",
                List.of(new OfficialRoutePlanner.Demand("consumer", "connection", demand, BigDecimal.ONE, null)),
                Map.of(), features.stream().collect(Collectors.toMap(ImportedOfficialFeature::getFeatureId, feature -> feature)),
                incidentCounts, environment);
    }

    private void addChamber(List<ImportedOfficialFeature> features, String id, Coordinate at,
            Coordinate first, Coordinate second) {
        features.add(chamber(id, at));
        features.add(new ImportedOfficialFeature(id + "-pipe-a", "heat_network",
                mapper.createObjectNode().put("diameter", 100), geometries.createLineString(new Coordinate[] {at, first})));
        features.add(new ImportedOfficialFeature(id + "-pipe-b", "heat_network",
                mapper.createObjectNode().put("diameter", 100), geometries.createLineString(new Coordinate[] {at, second})));
    }

    private ImportedOfficialFeature chamber(String id, Coordinate at) {
        return new ImportedOfficialFeature(id, "heat_chamber", mapper.createObjectNode(), geometries.createPoint(at));
    }

    private Coordinate point(double x, double y, double degrees, double originX, double originY) {
        double angle = Math.toRadians(degrees);
        return new Coordinate(originX + x * Math.cos(angle) - y * Math.sin(angle),
                originY + x * Math.sin(angle) + y * Math.cos(angle));
    }
}
