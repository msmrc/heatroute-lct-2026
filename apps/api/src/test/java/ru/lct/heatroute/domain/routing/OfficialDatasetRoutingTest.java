package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import ru.lct.heatroute.domain.export.OfficialGeoJsonExporter;
import ru.lct.heatroute.domain.export.OfficialOutputContractValidator;
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
        OfficialRunParameters parameters = new OfficialRunParameters(null, null, true);

        OfficialCalculationResult result = planner.plan(
                features,
                topology,
                parameters,
                OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE);

        // Диагностика не является принятым demo: сохраняем и отклонённые кандидаты для разбора отказа.
        String probeOutput = System.getProperty("heatroute.probe.output", "").trim();
        if (!probeOutput.isEmpty()) {
            ObjectNode probe = objectMapper.createObjectNode().put("verification_state", "before_assertions");
            probe.set("result", objectMapper.valueToTree(result));
            Files.writeString(Path.of(probeOutput), objectMapper.writeValueAsString(probe));
        }

        assertThat(topology.getTieInCandidates()).hasSize(204);
        assertThat(result.getDemandCount()).isEqualTo(17);
        assertThat(result.getPreferredVariantId())
                .as(variantDiagnostics(result))
                .isNotNull();
        // §6 разрешает до трёх содержательно различных вариантов. Роли одной сети
        // не должны раздувать результат тремя одинаковыми экземплярами.
        assertThat(result.getVariants()).hasSizeBetween(1, 3);
        assertThat(result.getVariants().stream().map(variant -> {
            ObjectNode content = objectMapper.valueToTree(variant);
            content.remove(List.of("id", "strategy", "rank"));
            return content;
        }).collect(Collectors.toList())).doesNotHaveDuplicates();
        assertThat(result.getVariants()).allMatch(RouteVariant::isValid);
        assertThat(result.getVariants()).allSatisfy(variant ->
                assertThat(variant.getConnectedDemandCount()).isEqualTo(result.getDemandCount()));
        OfficialRoutingEnvironment verificationEnvironment = new OfficialObstacleRouter(new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry())).prepare(features);
        // Экономическая роль не должна незаметно вернуть плохие углы после finish/переноса камер.
        // Проверяем и опубликованную диагностику, и фактические полилинии всех итоговых вариантов.
        assertThat(result.getVariants()).allSatisfy(variant -> {
            assertThat(variant.getEngineeringIssues()).as(engineeringDiagnostics(variant)).isEmpty();
            assertThat(new EngineeringRouteEvaluator().evaluate(variant.getEdges()).isCompliant())
                    .as("Final engineering geometry: " + variant.getId()).isTrue();
            assertThat(new ExpertChamberRouteValidator().validate(variant.getNodes(), variant.getEdges(),
                    verificationEnvironment::existingDirections))
                    .as("Final chamber sections and OKS origins: " + variant.getId()).isEmpty();
            assertThat(ExpertRouteBendRules.validate(variant.getNodes(), variant.getEdges()))
                    .as("Final bends and diameter-table spacing: " + variant.getId()).isEmpty();
        });
        result.getVariants().stream().filter(variant -> "shortest".equals(variant.getId())).forEach(shortest ->
                assertThat(result.getVariants()).allSatisfy(variant ->
                        assertThat(shortest.getTotalLengthM()).isLessThanOrEqualTo(variant.getTotalLengthM())));
        result.getVariants().stream().filter(variant -> "cheapest".equals(variant.getId())).forEach(cheapest ->
                assertThat(result.getVariants()).allSatisfy(variant ->
                        assertThat(cheapest.getEconomics().getCalculatedCost())
                                .isLessThanOrEqualTo(variant.getEconomics().getCalculatedCost())));
        RouteVariant preferred = result.getVariants().stream()
                .filter(variant -> variant.getId().equals(result.getPreferredVariantId()))
                .findFirst()
                .orElseThrow();
        assertThat(preferred.getConnectedDemandCount()).isPositive();
        assertThat(preferred.getConnectedDemandCount()).isLessThanOrEqualTo(result.getDemandCount());
        assertThat(preferred.getConnections())
                .filteredOn(connection -> "no_route".equals(connection.getStatus()))
                .hasSize((int) (result.getDemandCount() - preferred.getConnectedDemandCount()));
        assertThat(preferred.getEconomics().isComplete()).isTrue();
        assertThat(preferred.getEconomics().getScore()).isNotNull();
        assertThat(result.getVariants()).allSatisfy(variant ->
                assertThat(preferred.getEconomics().getScore())
                        .isLessThanOrEqualTo(variant.getEconomics().getScore()));
        assertThat(result.getVariants().stream().map(RouteVariant::getRank).sorted().collect(Collectors.toList()))
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, result.getVariants().size())
                        .boxed().collect(Collectors.toList()));
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
        ObjectNode demoBundle = buildLocalDemoBundle(result, parameters);
        // Проверяем не только сохранённый valid: фактический экспорт пересчитывает камеры,
        // сумму физических объектов и обязательные повороты по готовой геометрии.
        OfficialPipeCatalog exportCatalog = new OfficialPipeCatalog();
        OfficialEconomics exportEconomics = new OfficialEconomics();
        OfficialGeoJsonExporter exporter = new OfficialGeoJsonExporter(objectMapper, exportCatalog, exportEconomics,
                new OfficialOutputContractValidator(),
                new OfficialVariantEconomicsCalculator(exportCatalog, exportEconomics));
        JsonNode savedCalculation = objectMapper.readTree(objectMapper.writeValueAsBytes(result));
        exporter.validate(savedCalculation, features, parameters);
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        exporter.writeValidated(savedCalculation, features, parameters, exported);
        assertThat(objectMapper.readTree(exported.toByteArray()).path("features").isEmpty()).isFalse();
        assertThat(demoBundle.path("run").path("parameters").path("depth_enabled").asBoolean()).isTrue();
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

    OfficialRoutePlanner planner() {
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

    private String engineeringDiagnostics(RouteVariant variant) {
        return variant.getId() + ": " + variant.getEngineeringIssues().stream()
                .map(issue -> issue.getCode() + ":" + issue.getSubjectId() + ":" + issue.getMessage())
                .collect(Collectors.joining(" | "));
    }

    private ObjectNode buildLocalDemoBundle(OfficialCalculationResult result, OfficialRunParameters parameters)
            throws Exception {
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
        run.set("parameters", objectMapper.valueToTree(parameters));
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

    List<ImportedOfficialFeature> loadOfficialFeatures() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/official/lct-2026.geojson")) {
            if (input == null) {
                throw new IllegalStateException("Official dataset test resource is missing");
            }
            return loadFeatures(input);
        }
    }

    List<ImportedOfficialFeature> loadFeatures(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return loadFeatures(input);
        }
    }

    private List<ImportedOfficialFeature> loadFeatures(InputStream input) throws Exception {
        JsonNode root = objectMapper.readTree(input);
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
