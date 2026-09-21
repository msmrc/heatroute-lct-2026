package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
import ru.lct.heatroute.domain.input.OfficialInputReport;
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
        OfficialRoutePlanner planner = planner();

        OfficialCalculationResult result = planner.plan(
                features,
                topology,
                new OfficialRunParameters(null, null, true),
                OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE);

        assertThat(topology.getTieInCandidates()).hasSize(204);
        assertThat(result.getDemandCount()).isEqualTo(17);
        assertThat(result.getPreferredVariantId())
                .as(variantDiagnostics(result))
                .isNotNull();
        assertThat(result.getVariants())
                .extracting(RouteVariant::getId)
                .contains("balanced");
        assertThat(result.getVariants()).hasSizeBetween(1, 3);
        assertThat(result.getVariants()).allMatch(RouteVariant::isValid);
        RouteVariant preferred = result.getVariants().stream()
                .filter(variant -> variant.getId().equals(result.getPreferredVariantId()))
                .findFirst()
                .orElseThrow();
        assertThat(preferred.getConnectedDemandCount()).isEqualTo(17);
        assertThat(preferred.getEconomics().isComplete()).isTrue();
        assertThat(preferred.getEconomics().getScore()).isNotNull();
        assertThat(result.getVariants()).allSatisfy(variant -> {
            assertThat(variant.getEdges()).isNotEmpty();
            assertThat(variant.getEdges()).allSatisfy(edge -> {
                assertThat(edge.getCoordinates()).hasSizeGreaterThanOrEqualTo(2);
                assertThat(edge.getSections()).isNotEmpty();
                assertThat(edge.getDiameter()).isNotNull();
                assertThat(edge.getFlowTph()).isNotNull();
                assertThat(edge.getDepthProfile()).isNotNull();
                assertThat(edge.getDepthProfile().isComplete())
                        .as(edge.getId() + " " + edge.getDepthProfile().getIssues())
                        .isTrue();
                assertThat(edge.getDepthProfile().getIssues()).as(edge.getId()).isEmpty();
                assertThat(edge.getDepthProfile().getPoints()).hasSizeGreaterThanOrEqualTo(2);
            });
        });
        ObjectNode demoBundle = buildLocalDemoBundle(result);
        JsonNode demoImport = demoBundle.path("import");
        assertThat(demoImport.path("input_size_bytes").asLong()).isEqualTo(633_402L);
        assertThat(demoImport.path("report").path("sha256").asText())
                .isEqualTo("cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130");
        assertThat(demoImport.path("report").path("warnings").isArray()).isTrue();
        writeLocalDemoBundleIfRequested(demoBundle);
    }

    @Test
    void ownOksEgressRoutesRemainValidForConcaveDatasetBuildings() throws Exception {
        for (String demandId : List.of("1", "3", "8", "10", "16")) {
            List<ImportedOfficialFeature> features = loadOfficialFeatures().stream()
                    .filter(feature -> !"oks_connection_point".equals(feature.getObjectType())
                            || demandId.equals(feature.getFeatureId()))
                    .collect(Collectors.toList());
            OfficialCalculationResult result = planner().plan(
                    features,
                    new ExistingNetworkTopologyAnalyzer().analyze(features),
                    new OfficialRunParameters(null, null, false),
                    OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE);
            assertThat(result.getPreferredVariantId())
                    .as("demand=" + demandId + "\n" + variantDiagnostics(result))
                    .isNotNull();
            assertThat(result.getVariants()).allMatch(RouteVariant::isValid);
        }
    }

    private OfficialRoutePlanner planner() {
        OfficialRouteGeometryRules geometryRules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialPipeCatalog pipeCatalog = new OfficialPipeCatalog();
        return new OfficialRoutePlanner(
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
    }

    private String variantDiagnostics(OfficialCalculationResult result) {
        return result.getVariants().stream()
                .map(variant -> variant.getId()
                        + " connected=" + variant.getConnectedDemandCount()
                        + " issues=" + variant.getValidationIssues().stream()
                                .map(issue -> issue.getCode() + ":" + issue.getSubjectId()
                                        + ":" + issue.getMessage())
                                .collect(Collectors.joining(" | "))
                        + " edges=" + variant.getEdges().stream()
                                .filter(edge -> variant.getValidationIssues().stream()
                                        .anyMatch(issue -> edge.getId().equals(issue.getSubjectId())))
                                .map(edge -> edge.getId() + ":du=" + edge.getDiameter()
                                        + ":" + edge.getCoordinates().stream()
                                                .map(coordinate -> coordinate.getXM() + "," + coordinate.getYM())
                                                .collect(Collectors.joining(";")))
                                .collect(Collectors.joining(" | ")))
                .collect(Collectors.joining("\n"));
    }

    private ObjectNode buildLocalDemoBundle(OfficialCalculationResult result) throws Exception {
        byte[] datasetBytes;
        try (InputStream input = getClass().getResourceAsStream("/official/lct-2026.geojson")) {
            if (input == null) {
                throw new IllegalStateException("Official dataset test resource is missing");
            }
            datasetBytes = input.readAllBytes();
        }
        JsonNode dataset = objectMapper.readTree(datasetBytes);
        OfficialInputReport report = new OfficialGeoJsonInspector(objectMapper)
                .inspect(new ByteArrayInputStream(datasetBytes));
        String now = OffsetDateTime.now().toString();

        ObjectNode imported = objectMapper.createObjectNode();
        imported.put("id", "local-demo");
        imported.put("state", "valid");
        imported.put("original_filename", "lct-2026.geojson");
        imported.put("input_size_bytes", datasetBytes.length);
        imported.put("created_at", now);
        imported.set("report", objectMapper.valueToTree(report));

        ObjectNode run = objectMapper.createObjectNode();
        run.put("id", "local-demo-run");
        run.put("import_id", "local-demo");
        run.put("state", "completed");
        run.put("algorithm_version", result.getAlgorithmVersion());
        run.put("input_sha256", report.getSha256());
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
        return bundle;
    }

    private void writeLocalDemoBundleIfRequested(ObjectNode bundle) throws Exception {
        String outputPath = System.getProperty("heatroute.demo.output", "").trim();
        if (outputPath.isEmpty()) {
            return;
        }
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
