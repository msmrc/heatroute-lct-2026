package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
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
import ru.lct.heatroute.domain.topology.ExistingNetworkTopologyAnalyzer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

class OfficialTopologyScaleTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeoJsonReader geoJsonReader = new GeoJsonReader();

    @Test
    @EnabledIfSystemProperty(named = "heatroute.scale.topology", matches = ".+")
    void calculatesEveryDemandInTheRepresentativeMultiDistrictFixture() throws Exception {
        Path fixture = Path.of(System.getProperty("heatroute.scale.topology")).toAbsolutePath().normalize();
        int expectedDemands = Integer.parseInt(System.getProperty("heatroute.scale.demands"));
        long maximumSeconds = Long.parseLong(System.getProperty("heatroute.scale.maximumSeconds", "600"));
        assertThat(Files.isRegularFile(fixture)).isTrue();

        List<ImportedOfficialFeature> features = loadFeatures(fixture);
        Instant started = Instant.now();
        TopologyAnalysis topology = new ExistingNetworkTopologyAnalyzer().analyze(features);
        OfficialCalculationResult result = planner().plan(features, topology);
        Duration elapsed = Duration.between(started, Instant.now());

        RouteVariant preferred = result.getVariants().stream()
                .filter(variant -> variant.getId().equals(result.getPreferredVariantId()))
                .findFirst()
                .orElseThrow();
        assertThat(result.getDemandCount()).isEqualTo(expectedDemands);
        assertThat(result.getVariants()).hasSizeBetween(1, 3).allMatch(RouteVariant::isValid);
        assertThat(preferred.getConnectedDemandCount()).isEqualTo(expectedDemands);
        assertThat(preferred.getEdges()).allSatisfy(edge -> {
            assertThat(edge.getDepthProfile()).isNotNull();
            assertThat(edge.getDepthProfile().getPoints()).hasSizeGreaterThanOrEqualTo(2);
        });
        assertThat(elapsed).isLessThan(Duration.ofSeconds(maximumSeconds));

        long usedHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        System.out.printf(
                "R9_TOPOLOGY_RESULT features=%d demands=%d candidates=%d elapsed_ms=%d used_heap_bytes=%d%n",
                features.size(), result.getDemandCount(), topology.getTieInCandidates().size(),
                elapsed.toMillis(), usedHeap);
    }

    private RegressionRoutePlannerFixture planner() {
        OfficialRouteGeometryRules geometryRules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialPipeCatalog pipeCatalog = new OfficialPipeCatalog();
        OfficialEconomics economics = new OfficialEconomics();
        return new RegressionRoutePlannerFixture(
                new OfficialRouteValidator(geometryRules),
                new OfficialObstacleRouter(geometryRules),
                pipeCatalog,
                new OfficialNetworkSizer(pipeCatalog),
                new OfficialExistingNetworkReconstructor(pipeCatalog),
                new OfficialVariantEconomicsCalculator(pipeCatalog, economics),
                new OfficialDepthPlanner(
                        new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipeCatalog),
                        new OfficialDepthOptimizer(pipeCatalog, economics),
                        new OfficialDepthProfileValidator(pipeCatalog)));
    }

    private List<ImportedOfficialFeature> loadFeatures(Path fixture) throws Exception {
        JsonNode root = objectMapper.readTree(fixture.toFile());
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem wgs84 = crsFactory.createFromParameters(
                "WGS84", "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem metric = crsFactory.createFromParameters(
                "UTM37N", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs");
        CoordinateTransform transform = new CoordinateTransformFactory().createTransform(wgs84, metric);
        List<ImportedOfficialFeature> result = new ArrayList<>();
        for (JsonNode feature : root.path("features")) {
            JsonNode properties = feature.path("properties");
            Geometry geometry = geoJsonReader.read(feature.path("geometry").toString());
            geometry.apply((org.locationtech.jts.geom.CoordinateFilter) coordinate ->
                    transform(coordinate, transform));
            geometry.geometryChanged();
            result.add(new ImportedOfficialFeature(
                    properties.path("id").asText(),
                    properties.path("object_type").asText(),
                    properties,
                    geometry));
        }
        return result;
    }

    private void transform(Coordinate coordinate, CoordinateTransform transform) {
        ProjCoordinate result = new ProjCoordinate();
        transform.transform(new ProjCoordinate(coordinate.x, coordinate.y), result);
        coordinate.x = result.x;
        coordinate.y = result.y;
    }
}
