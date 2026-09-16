package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.reconstruction.OfficialExistingNetworkReconstructor;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.topology.ExistingNetworkTopologyAnalyzer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

class OfficialDatasetRoutingTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final GeoJsonReader geoJsonReader = new GeoJsonReader();

    @Test
    void officialDatasetProducesValidatedObstacleAwareVariants() throws Exception {
        List<ImportedOfficialFeature> features = loadOfficialFeatures();
        TopologyAnalysis topology = new ExistingNetworkTopologyAnalyzer().analyze(features);
        OfficialRouteGeometryRules geometryRules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialPipeCatalog pipeCatalog = new OfficialPipeCatalog();
        OfficialRoutePlanner planner = new OfficialRoutePlanner(
                new OfficialRouteValidator(geometryRules),
                new OfficialObstacleRouter(geometryRules),
                pipeCatalog,
                new OfficialNetworkSizer(pipeCatalog),
                new OfficialExistingNetworkReconstructor(pipeCatalog),
                new OfficialVariantEconomicsCalculator(pipeCatalog, new OfficialEconomics()),
                new OfficialDepthPlanner(
                        new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipeCatalog),
                        new OfficialDepthOptimizer(pipeCatalog, new OfficialEconomics()),
                        new OfficialDepthProfileValidator(pipeCatalog)));

        OfficialCalculationResult result = planner.plan(features, topology);

        assertThat(topology.getTieInCandidates()).hasSize(204);
        assertThat(result.getDemandCount()).isEqualTo(17);
        assertThat(result.getPreferredVariantId()).isNotNull();
        assertThat(result.getVariants())
                .extracting(RouteVariant::getId)
                .containsExactly("independent", "shared", "diverse");
        assertThat(result.getVariants()).allMatch(RouteVariant::isValid);
        RouteVariant preferred = result.getVariants().stream()
                .filter(variant -> variant.getId().equals(result.getPreferredVariantId()))
                .findFirst()
                .orElseThrow();
        assertThat(preferred.getConnectedDemandCount()).isEqualTo(17);
        assertThat(preferred.getEconomics().isComplete()).isFalse();
        assertThat(preferred.getEconomics().getScore()).isNull();
        assertThat(result.getVariants()).allSatisfy(variant -> {
            assertThat(variant.getEdges()).isNotEmpty();
            assertThat(variant.getEdges()).allSatisfy(edge -> {
                assertThat(edge.getCoordinates()).hasSizeGreaterThanOrEqualTo(2);
                assertThat(edge.getSections()).isNotEmpty();
                assertThat(edge.getDiameter()).isNotNull();
                assertThat(edge.getFlowTph()).isNotNull();
                assertThat(edge.getDepthProfile()).isNotNull();
                assertThat(edge.getDepthProfile().getPoints()).hasSizeGreaterThanOrEqualTo(2);
            });
        });
        writeLocalDemoBundleIfRequested(result);
    }

    private void writeLocalDemoBundleIfRequested(OfficialCalculationResult result) throws Exception {
        String outputPath = System.getProperty("heatroute.demo.output", "").trim();
        if (outputPath.isEmpty()) {
            return;
        }
        JsonNode dataset;
        try (InputStream input = getClass().getResourceAsStream("/official/lct-2026.geojson")) {
            if (input == null) {
                throw new IllegalStateException("Official dataset test resource is missing");
            }
            dataset = objectMapper.readTree(input);
        }
        Map<String, Integer> featureCounts = new LinkedHashMap<>();
        for (JsonNode feature : dataset.path("features")) {
            featureCounts.merge(feature.path("properties").path("object_type").asText(), 1, Integer::sum);
        }
        int inputSizeBytes = objectMapper.writeValueAsBytes(dataset).length;
        String now = OffsetDateTime.now().toString();

        ObjectNode report = objectMapper.createObjectNode();
        report.put("contract_version", "official-lct-2026");
        report.put("input_profile", "provided_dataset_compatibility");
        report.put("sha256", "local-official-dataset");
        report.put("feature_count", dataset.path("features").size());
        report.set("feature_counts", objectMapper.valueToTree(featureCounts));
        report.putArray("errors");
        report.putArray("warnings");
        report.put("valid", true);

        ObjectNode imported = objectMapper.createObjectNode();
        imported.put("id", "local-demo");
        imported.put("state", "valid");
        imported.put("original_filename", "lct-2026.geojson");
        imported.put("input_size_bytes", inputSizeBytes);
        imported.put("created_at", now);
        imported.set("report", report);

        ObjectNode run = objectMapper.createObjectNode();
        run.put("id", "local-demo-run");
        run.put("import_id", "local-demo");
        run.put("state", "completed");
        run.put("algorithm_version", result.getAlgorithmVersion());
        run.put("input_sha256", "local-official-dataset");
        run.set("parameters", objectMapper.valueToTree(OfficialRunParameters.defaults()));
        run.set("result", objectMapper.valueToTree(result));
        run.put("created_at", now);
        run.put("completed_at", now);

        ObjectNode map = dataset.deepCopy();
        map.put("truncated", false);
        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.set("import", imported);
        bundle.set("run", run);
        bundle.set("map", map);

        Path target = Path.of(outputPath).toAbsolutePath().normalize();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), bundle);
    }

    private List<ImportedOfficialFeature> loadOfficialFeatures() throws Exception {
        JsonNode root;
        try (InputStream input = getClass().getResourceAsStream("/official/lct-2026.geojson")) {
            if (input == null) {
                throw new IllegalStateException("Official dataset test resource is missing");
            }
            root = objectMapper.readTree(input);
        }
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
