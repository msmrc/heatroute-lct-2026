package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.slf4j.LoggerFactory;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.TieInCandidate;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

/** Forbidden terminals remain in the result and economics after all generation stages. */
@Timeout(90)
class ForbiddenTerminalCoverageTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allBlockedDemandsRemainInOneRankedResultWithTheirFullPenalty(boolean depthEnabled) throws Exception {
        var move = transform(depthEnabled);
        List<ImportedOfficialFeature> features = source(move);
        features.add(feature("site", "restriction", "POLYGON ((40 40,80 40,80 80,40 80,40 40))",
                "{\"restriction_type\":\"social_area\"}", move));
        features.add(demand("blocked-first", 50, 50, "1.25", move));
        features.add(demand("blocked-second", 70, 70, "4.75", move));

        var result = plan(features, depthEnabled);

        assertThat(result.getDemandCount()).isEqualTo(2);
        assertThat(result.getVariants()).hasSize(1);
        RouteVariant variant = result.getVariants().get(0);
        assertThat(result.getPreferredVariantId()).isEqualTo(variant.getId());
        assertThat(variant.getRank()).isEqualTo(1);
        assertThat(variant.isValid()).isTrue();
        assertThat(variant.getNodes()).isEmpty();
        assertThat(variant.getEdges()).isEmpty();
        assertThat(variant.getConnectedDemandCount()).isZero();
        assertThat(variant.getNoRouteDemandCount()).isEqualTo(2);
        assertThat(variant.getConnections()).extracting(RouteConnection::getDemandId)
                .containsExactlyInAnyOrder("blocked-first", "blocked-second");
        variant.getConnections().forEach(this::assertForbiddenConnection);
        BigDecimal penalty = new OfficialEconomics().unconnectedPenalty(new BigDecimal("1.25"))
                .add(new OfficialEconomics().unconnectedPenalty(new BigDecimal("4.75")));
        assertThat(variant.getEconomics().isComplete()).isTrue();
        assertThat(variant.getEconomics().getUnconnectedPenalty()).isEqualByComparingTo(penalty);
        assertThat(variant.getEconomics().getCalculatedCost()).isEqualByComparingTo(penalty);
        assertThat(variant.getEconomics().getScore())
                .isEqualByComparingTo(new OfficialEconomics().score(penalty, BigDecimal.ZERO));
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void groupAndSharedSearchRetainTheFifthForbiddenDemandAndPenalty(boolean depthEnabled,
            boolean ownFootprints) throws Exception {
        var move = transform(depthEnabled);
        List<ImportedOfficialFeature> features = source(move);
        features.add(feature("site", "restriction", "POLYGON ((-8 92,8 92,8 108,-8 108,-8 92))",
                "{\"restriction_type\":\"social_area\"}", move));
        features.add(demand("blocked", 0, 100, "3.5", move));
        addDemandWithOwnFootprint(features, "west-upper", -100, 40, "1", move);
        addDemandWithOwnFootprint(features, "west-lower", -100, -40, "2", move);
        addDemandWithOwnFootprint(features, "east-upper", 100, 40, "3", move);
        addDemandWithOwnFootprint(features, "east-lower", 100, -40, "4", move);
        if (!ownFootprints) features.removeIf(feature -> feature.getFeatureId().startsWith("own-"));
        assertValidBaselineInput(features);
        Logger logger = (Logger) LoggerFactory.getLogger(OfficialRoutePlanner.class);
        ListAppender<ILoggingEvent> stages = new ListAppender<>();
        stages.start();
        logger.addAppender(stages);
        OfficialCalculationResult result;
        try {
            result = plan(features, depthEnabled);
        } finally {
            logger.detachAppender(stages);
            stages.stop();
        }

        assertThat(stages.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.startsWith("Shared spine group demands=4 "));
        if (ownFootprints) {
            assertThat(stages.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.startsWith("Orthogonal corridor target=source connected=4 "));
        } else {
            assertThat(stages.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.startsWith("Shared root tree target=source demands=4 "));
        }
        assertThat(result.getDemandCount()).isEqualTo(5);
        assertThat(result.getVariants()).hasSizeBetween(1, 3).allSatisfy(variant -> {
            assertThat(variant.isValid()).isTrue();
            assertThat(variant.getConnectedDemandCount()).isEqualTo(4);
            assertThat(variant.getNoRouteDemandCount()).isOne();
            assertThat(variant.getConnections()).extracting(RouteConnection::getDemandId)
                    .containsExactlyInAnyOrder("blocked", "west-upper", "west-lower", "east-upper", "east-lower");
            assertThat(variant.getConnections()).filteredOn(connection -> "blocked".equals(connection.getDemandId()))
                    .singleElement().satisfies(this::assertForbiddenConnection);
            assertThat(variant.getNodes()).noneMatch(node -> "demand:blocked".equals(node.getId()));
            assertThat(variant.getNodes()).anyMatch(node -> "new_branch_chamber".equals(node.getNodeType()));
            assertThat(variant.getEconomics().getUnconnectedPenalty())
                    .isEqualByComparingTo(new OfficialEconomics().unconnectedPenalty(new BigDecimal("3.5")));
            assertThat(new OfficialRouteValidator(rules).validate(variant.getNodes(), variant.getEdges(), features)).isEmpty();
            assertThat(new ExpertChamberRouteValidator().validate(variant.getNodes(), variant.getEdges(),
                    new OfficialObstacleRouter(rules).prepare(features)::existingDirections)).isEmpty();
            assertThat(ExpertRouteBendRules.validate(variant.getNodes(), variant.getEdges())).isEmpty();
            if (depthEnabled) assertThat(variant.getEdges()).allSatisfy(edge -> {
                assertThat(edge.getDepthProfile()).isNotNull();
                assertThat(edge.getDepthProfile().isComplete()).isTrue();
                assertThat(edge.getDepthProfile().getIssues()).isEmpty();
            });
        });
    }

    private void assertForbiddenConnection(RouteConnection connection) {
        assertThat(connection.getStatus()).isEqualTo("no_route");
        assertThat(connection.getReason()).isEqualTo("ENDPOINT_INSIDE_FORBIDDEN_AREA");
        assertThat(connection.getDiagnostics()).isNotNull();
        assertThat(connection.getDiagnostics().getCandidateCount()).isOne();
        assertThat(connection.getDiagnostics().getAttemptedCandidateCount()).isZero();
        assertThat(connection.getDiagnostics().getDirectBlockers()).containsExactly("social_area:site");
    }

    private void assertValidBaselineInput(List<ImportedOfficialFeature> features) throws Exception {
        var crs = new CRSFactory();
        var inverse = new CoordinateTransformFactory().createTransform(
                crs.createFromParameters("metric", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs"),
                crs.createFromParameters("wgs84", "+proj=longlat +datum=WGS84 +no_defs"));
        ObjectNode collection = mapper.createObjectNode().put("type", "FeatureCollection");
        var entries = collection.putArray("features");
        for (ImportedOfficialFeature feature : features) {
            var geometry = feature.getMetricGeometry().copy();
            geometry.apply((CoordinateFilter) coordinate -> {
                var geographic = new ProjCoordinate();
                inverse.transform(new ProjCoordinate(coordinate.x, coordinate.y), geographic);
                coordinate.x = geographic.x;
                coordinate.y = geographic.y;
            });
            geometry.geometryChanged();
            ObjectNode entry = entries.addObject().put("type", "Feature");
            entry.set("geometry", mapper.readTree(new GeoJsonWriter().write(geometry)));
            ObjectNode properties = (ObjectNode) feature.getAttributes().deepCopy();
            properties.put("id", feature.getFeatureId()).put("object_type", feature.getObjectType());
            entry.set("properties", properties);
        }
        var report = new OfficialGeoJsonInspector(mapper)
                .inspect(new ByteArrayInputStream(mapper.writeValueAsBytes(collection)));
        assertThat(report.getErrors()).isEmpty();
        assertThat(report.isValid()).isTrue();
        assertThat(report.getInputProfile()).isEqualTo(OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE);
    }

    private OfficialCalculationResult plan(List<ImportedOfficialFeature> features, boolean depthEnabled) {
        List<TieInCandidate> candidates = features.stream()
                .filter(feature -> "oks_connection_point".equals(feature.getObjectType()))
                .map(feature -> new TieInCandidate(feature.getFeatureId(), "source", "heat_chamber", 100, false))
                .collect(Collectors.toList());
        var topology = new TopologyAnalysis(0, 2, 1, List.of(), candidates);
        var planner = new OfficialDatasetRoutingTest().planner();
        var parameters = new OfficialRunParameters(null, null, depthEnabled);
        if (!depthEnabled) return planner.plan(features, topology, parameters, OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE);
        List<ImportedOfficialFeature> core = features.stream()
                .filter(feature -> !"restriction".equals(feature.getObjectType())).collect(Collectors.toList());
        java.util.Collections.reverse(core);
        return planner.plan(core, topology, parameters, OfficialGeoJsonInspector.BASELINE_INPUT_PROFILE,
                new InMemoryRoutingFeatureSource(features));
    }

    private List<ImportedOfficialFeature> source(AffineTransformation move) throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        features.add(feature("heat-source", "source", "POINT (0 200)", "{}", move));
        features.add(feature("north", "heat_network", "LINESTRING (0 0,0 200)", "{\"diameter\":400}", move));
        features.add(feature("south", "heat_network", "LINESTRING (0 -200,0 0)", "{\"diameter\":400}", move));
        features.add(feature("source", "heat_chamber", "POINT (0 0)", "{}", move));
        return features;
    }

    private ImportedOfficialFeature demand(String id, double x, double y, String flow,
            AffineTransformation move) throws Exception {
        return feature(id, "oks_connection_point", "POINT (" + x + " " + y + ")", "{\"flow_tph\":" + flow + "}", move);
    }

    private void addDemandWithOwnFootprint(List<ImportedOfficialFeature> features, String id,
            double x, double y, String flow, AffineTransformation move) throws Exception {
        features.add(demand(id, x, y, flow, move));
        String ring = (x - 5) + " " + (y - 5) + "," + (x + 5) + " " + (y - 5) + ","
                + (x + 5) + " " + (y + 5) + "," + (x - 5) + " " + (y + 5) + ","
                + (x - 5) + " " + (y - 5);
        features.add(feature("own-" + id, "restriction", "POLYGON ((" + ring + "))",
                "{\"restriction_type\":\"oks\"}", move));
    }

    private AffineTransformation transform(boolean rotated) {
        return AffineTransformation.rotationInstance(Math.toRadians(rotated ? 37 : 0)).translate(430000, 6180000);
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt, String attributes,
            AffineTransformation move) throws Exception {
        return new ImportedOfficialFeature(id, type, mapper.readTree(attributes), move.transform(new WKTReader().read(wkt)));
    }
}
