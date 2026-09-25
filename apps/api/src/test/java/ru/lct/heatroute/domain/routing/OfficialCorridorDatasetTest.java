package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.export.OfficialGeoJsonExporter;
import ru.lct.heatroute.domain.export.OfficialOutputContractValidator;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ExistingNetworkIncidence;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialCorridorDatasetTest {
    @Test
    void generatesACompleteValidCorridorCandidateFromInputGeometry() throws Exception {
        List<ImportedOfficialFeature> features = new OfficialDatasetRoutingTest().loadOfficialFeatures();
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OfficialRoutingEnvironment environment = router.prepare(features);
        OfficialRouteValidator validator = new OfficialRouteValidator(rules);
        OfficialRoutePlanner planner = new OfficialDatasetRoutingTest().planner();
        boolean finish = Boolean.parseBoolean(System.getProperty("heatroute.corridor.finish", "true"));
        OfficialRunParameters parameters = new OfficialRunParameters(null, null, finish);
        Map<String, ImportedOfficialFeature> demands = features.stream()
                .filter(f -> "oks_connection_point".equals(f.getObjectType()))
                .collect(Collectors.toMap(ImportedOfficialFeature::getFeatureId, f -> f));
        Coordinate center = new Coordinate(demands.values().stream()
                .mapToDouble(f -> f.getMetricGeometry().getCoordinate().x).average().orElseThrow(),
                demands.values().stream().mapToDouble(f -> f.getMetricGeometry().getCoordinate().y).average().orElseThrow());
        ExistingNetworkIncidence incidence = new ExistingNetworkIncidence(features);
        List<RouteNode> roots = features.stream().filter(f -> "heat_chamber".equals(f.getObjectType()))
                .map(target -> existingRoot(target, incidence))
                .filter(root -> hasAvailableNormal(root, environment))
                .sorted(Comparator.comparingDouble(root -> root.getCoordinate().toCoordinate().distance(center)))
                .limit(2).collect(Collectors.toList());
        assertThat(roots).as("The input must supply an existing chamber with a free normal approach").isNotEmpty();
        List<Geometry> buildings = features.stream().filter(rules::isBuildingFeature)
                .map(ImportedOfficialFeature::getMetricGeometry).collect(Collectors.toList());
        List<OrthogonalCorridorNetworkBuilder.Terminal> terminals = demands.values().stream()
                .map(f -> new OrthogonalCorridorNetworkBuilder.Terminal(f.getFeatureId(), f.getFeatureId(),
                        f.getMetricGeometry().getCoordinate(), new BigDecimal(f.getAttributes().path("flow_tph").asText())))
                .collect(Collectors.toList());
        List<RouteVariant> finalized = new ArrayList<>();
        for (RouteNode root : roots) {
            List<OrthogonalCorridorNetworkBuilder.Network> candidates = new OrthogonalCorridorNetworkBuilder(
                    router, new OfficialPipeCatalog()).buildWithTerminalFrame(terminals, root, 4 - root.getBaseIncidentSections(), buildings, environment,
                    (id, junction, diameter, avoidance) -> terminalRoute(router, environment,
                            demands.get(id).getMetricGeometry().getCoordinate(), junction, diameter, avoidance));
            System.out.println("CORRIDOR target=" + root.getTargetId() + " candidates=" + candidates.size());
            for (OrthogonalCorridorNetworkBuilder.Network candidate : candidates) {
                List<RouteValidationIssue> issues = validator.validate(candidate.nodes(), candidate.edges(), features);
                double length = candidate.edges().stream().mapToDouble(e -> e.getLengthM().doubleValue()).sum();
                EngineeringRouteEvaluator.Evaluation geometry = new EngineeringRouteEvaluator().evaluate(candidate.edges());
                System.out.println("CORRIDOR length=" + length + " nodes=" + candidate.nodes().size()
                        + " bends=" + geometry.bendCount() + " invalid_angles=" + geometry.invalidAngleCount()
                        + " junction_angles=" + geometry.irregularJunctionAngleCount()
                        + " issues=" + issues.stream().map(i -> i.getCode() + ":" + i.getSubjectId() + ":" + i.getMessage()).collect(Collectors.toList()));
                if (!issues.isEmpty()) continue;
                RouteVariant variant = finish
                        ? planner.finish("corridor-" + finalized.size(), "engineering",
                                new OfficialRoutePlanner.VariantDraft(candidate.nodes(), candidate.edges(), candidate.connections()),
                                features, parameters, false, environment)
                        : new RouteVariant("corridor-" + finalized.size(), "engineering", candidate.nodes(),
                                candidate.edges(), candidate.connections(), BigDecimal.valueOf(length), issues, List.of(), null, null, null);
                variant = planner.withEngineeringAssessment(variant);
                EngineeringRouteEvaluator.Evaluation finalGeometry = new EngineeringRouteEvaluator().evaluate(variant.getEdges());
                boolean completeDepth = !finish || variant.getEdges().stream().allMatch(edge ->
                        edge.getDepthProfile() != null && edge.getDepthProfile().isComplete()
                                && edge.getDepthProfile().getIssues().isEmpty());
                System.out.println("CORRIDOR_FINISH enabled=" + finish + " length=" + variant.getTotalLengthM()
                        + " valid=" + variant.isValid() + " bends=" + finalGeometry.bendCount()
                        + " invalid_angles=" + finalGeometry.invalidAngleCount()
                        + " close_bends=" + finalGeometry.insufficientSpacingCount()
                        + " junction_angles=" + finalGeometry.irregularJunctionAngleCount()
                        + " complete_depth=" + completeDepth
                        + " cost=" + (variant.getEconomics() == null ? null : variant.getEconomics().getCalculatedCost())
                        + " issues=" + variant.getValidationIssues().stream().map(RouteValidationIssue::getCode).collect(Collectors.toList()));
                finalized.add(variant);
            }
        }
        List<OfficialRoutePlanner.Demand> mergeDemands = demands.values().stream()
                .map(feature -> new OfficialRoutePlanner.Demand(feature.getFeatureId(), feature.getFeatureId(),
                        feature.getMetricGeometry().getCoordinate(),
                        new BigDecimal(feature.getAttributes().path("flow_tph").asText()), null))
                .collect(Collectors.toList());
        if (finish) finalized.addAll(planner.repairMandatoryChambers(finalized, mergeDemands, features,
                parameters, false, environment));
        // Как в production, исправимые черновики проходят обязательную доводку до финального допуска.
        List<RouteVariant> valid = finalized.stream().filter(RouteVariant::isValid)
                .filter(variant -> variant.getEngineeringIssues().isEmpty())
                .filter(variant -> new ExpertChamberRouteValidator().validate(variant.getNodes(), variant.getEdges(),
                        environment::existingDirections).isEmpty())
                .filter(variant -> ExpertRouteBendRules.validate(variant.getNodes(), variant.getEdges()).isEmpty())
                .filter(variant -> !finish || variant.getEdges().stream().allMatch(edge -> edge.getDepthProfile() != null
                        && edge.getDepthProfile().isComplete() && edge.getDepthProfile().getIssues().isEmpty()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (finish && Boolean.getBoolean("heatroute.corridor.merge")) {
            List<RouteVariant> refined = planner.refineCorridorVariants(valid, mergeDemands, features,
                    parameters, false, environment);
            for (RouteVariant candidate : refined) {
                EngineeringRouteEvaluator.Evaluation assessment = new EngineeringRouteEvaluator().evaluate(candidate.getEdges());
                System.out.println("CORRIDOR_MERGE_FINISH id=" + candidate.getId() + " length=" + candidate.getTotalLengthM()
                        + " cost=" + candidate.getEconomics().getCalculatedCost()
                        + " cameras=" + candidate.getNodes().stream().filter(n -> "new_branch_chamber".equals(n.getNodeType())).count()
                        + " invalid_angles=" + assessment.invalidAngleCount()
                        + " junction_angles=" + assessment.irregularJunctionAngleCount());
            }
            assertThat(refined).as("Real corridor refinement must survive final admission").isNotEmpty();
            valid.addAll(refined);
        }
        String output = System.getProperty("heatroute.corridor.output", "");
        if (!output.isBlank()) {
            ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            Files.writeString(Path.of(output), mapper.writeValueAsString(Map.of("algorithm_version", "corridor-development",
                    "input_sha256", officialInputSha256(), "parameters", parameters,
                    "demand_count", demands.size(), "variants", valid)));
        }
        assertThat(valid).as("At least one data-derived corridor must connect all demands and satisfy official geometry").isNotEmpty();
        assertThat(demands).hasSize(17);
        assertThat(valid).allSatisfy(variant -> {
            assertThat(variant.getConnectedDemandCount()).isEqualTo(demands.size());
            assertThat(validator.validate(variant.getNodes(), variant.getEdges(), features)).isEmpty();
            assertThat(new ExpertChamberRouteValidator().validate(variant.getNodes(), variant.getEdges(),
                    environment::existingDirections)).isEmpty();
            assertThat(ExpertRouteBendRules.validate(variant.getNodes(), variant.getEdges())).isEmpty();
        });
        if (finish) assertThat(valid).allSatisfy(variant -> {
            assertThat(variant.getSizingIssues()).isEmpty();
            assertThat(variant.getEconomics().isComplete()).isTrue();
            assertThat(variant.getEdges()).allSatisfy(edge -> {
                assertThat(edge.getDepthProfile()).isNotNull();
                assertThat(edge.getDepthProfile().isComplete()).isTrue();
                assertThat(edge.getDepthProfile().getIssues()).isEmpty();
            });
            ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            OfficialPipeCatalog catalog = new OfficialPipeCatalog();
            OfficialEconomics economics = new OfficialEconomics();
            new OfficialGeoJsonExporter(mapper, catalog, economics, new OfficialOutputContractValidator(),
                    new OfficialVariantEconomicsCalculator(catalog, economics)).validate(mapper.valueToTree(
                            new OfficialCalculationResult(OfficialRoutePlanner.ALGORITHM_VERSION,
                                    OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE, demands.size(),
                                    List.of(variant.withRank(1)), variant.getId())), features);
        });
    }

    static RouteNode existingRoot(ImportedOfficialFeature target, ExistingNetworkIncidence incidence) {
        Coordinate at = target.getMetricGeometry().getCoordinate();
        return new RouteNode("root:" + target.getFeatureId(), "existing_chamber_tie_in",
                new RouteCoordinate(at.x, at.y), true, true, incidence.countAt(at), target.getFeatureId());
    }

    private boolean hasAvailableNormal(RouteNode root, OfficialRoutingEnvironment environment) {
        List<Coordinate> rays = environment.existingDirections(root);
        if (root.getBaseIncidentSections() < 1 || root.getBaseIncidentSections() >= 4 || rays.isEmpty()) return false;
        for (int i = 0; i < rays.size(); i++) for (int j = i + 1; j < rays.size(); j++) {
            if (!ExpertChamberGeometryRules.compatibleRays(rays.get(i).x, rays.get(i).y, rays.get(j).x, rays.get(j).y)) return false;
        }
        return !new ChamberApproachCandidates().build(root.getCoordinate().toCoordinate(), rays, 2.01, 0.1).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void callbackPreservesPhysicallyLegalStraightInput(boolean ownOks, boolean terminalRoad) {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
        OfficialRouteValidator validator = new OfficialRouteValidator(rules);
        double left = terminalRoad ? 4 : 30, right = terminalRoad ? 10 : 70, half = terminalRoad ? 1 : 3;
        List<ImportedOfficialFeature> features = new ArrayList<>(List.of(polygon("road", "road",
                point(left, -half), point(right, -half), point(right, half),
                point(left + 2 * half / Math.tan(Math.toRadians(40)), half), point(left, -half))));
        if (ownOks) features.add(polygon("own", "oks", point(-2, -10), point(0, -10),
                point(0, 10), point(-2, 10), point(-2, -10)));
        Coordinate demand = point(-1, 0), port = point(150, 0);
        RouteNode root = node("root", port, true), leaf = node("demand", demand, false);
        RoutePath physical = path(rules, features, List.of(port, demand));
        assertThat(validator.validate(List.of(root, leaf), List.of(edge(physical, "root", "demand")), features))
                .as("independent official validator admits the 151m physical port-to-demand edge").isEmpty();
        List<RouteValidationIssue> outwardIssues = validator.validate(
                List.of(node("root", demand, true), node("demand", port, false)),
                List.of(edge(path(rules, features, List.of(demand, port)), "root", "demand")), features);
        assertThat(outwardIssues).as("the same crossing in the callback direction is illegal")
                .extracting(RouteValidationIssue::getCode).contains("SPECIAL_CROSSING_ANGLE_VIOLATION");
        OfficialRoutingEnvironment environment = router.prepare(features);
        RoutePath callback = terminalRoute(router, environment, demand, port, 100, List.of());
        assertThat(callback).as("callback must retain the validator-approved physical route").isNotNull();
        assertThat(callback.lengthM()).isCloseTo(151, within(0.002));
        assertThat(validator.validate(List.of(root, leaf), List.of(edge(callback.reversed(), "root", "demand")), features)).isEmpty();
        assertThat(callback.reversed().sections()).usingRecursiveComparison().isEqualTo(
                rules.sections(rules.line(callback.reversed().coordinates()), rules.baseConstraints(features, 100)));
        assertThat(callback.reversed().sections()).filteredOn(section -> "special".equals(section.getKind()))
                .extracting(RouteSection::getCrossingAngleDegrees).containsExactly(new BigDecimal("90.000"));
    }

    /** Колбэк строит demand→port, а builder разворачивает его в физическое ребро port→demand. */
    static RoutePath terminalRoute(OfficialObstacleRouter router, OfficialRoutingEnvironment environment,
            Coordinate point, Coordinate port, int diameter, List<LineString> avoidance) {
        OfficialRouteGeometryRules.NormalEgress egress = environment.normalEgressTowards(diameter,
                point, port, RoutePlannerTuning.stable().getEngineeringEgressExtraM(), RouteTraversal.REVERSED).orElse(null);
        RoutePath path = egress == null
                ? router.find(point, port, diameter, environment, Set.of(), RoutePreference.ENGINEERING, avoidance, RouteTraversal.REVERSED)
                : router.findAfter(egress.start(), egress.exit(), port, diameter,
                        environment, Set.of(), RoutePreference.ENGINEERING, avoidance, RouteTraversal.REVERSED);
        return path == null || egress == null ? path
                : router.withCheckedTerminalPrefix(egress, path, diameter, environment, Set.of(), avoidance, RouteTraversal.REVERSED);
    }

    private ImportedOfficialFeature polygon(String id, String type, Coordinate... points) {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", type), new GeometryFactory().createPolygon(points));
    }

    private Coordinate point(double x, double y) { return new Coordinate(500000 + x, 6170000 + y); }

    private RouteNode node(String id, Coordinate point, boolean root) {
        return new RouteNode(id, root ? "existing_chamber_tie_in" : "demand_connection",
                new RouteCoordinate(point.x, point.y), root, root, root ? 2 : 0, null);
    }

    private RoutePath path(OfficialRouteGeometryRules rules, List<ImportedOfficialFeature> features,
            List<Coordinate> points) {
        LineString line = rules.line(points);
        return new RoutePath(points, rules.sections(line, rules.baseConstraints(features, 100)), line.getLength());
    }

    private RouteEdge edge(RoutePath path, String from, String to) {
        return new RouteEdge("edge", from, to, path.lengthM(), path.coordinates().stream()
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()), path.sections(), BigDecimal.ONE, 100);
    }

    private String officialInputSha256() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream resource = getClass().getResourceAsStream("/official/lct-2026.geojson")) {
            assertThat(resource).isNotNull();
            try (DigestInputStream input = new DigestInputStream(resource, digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }
}
