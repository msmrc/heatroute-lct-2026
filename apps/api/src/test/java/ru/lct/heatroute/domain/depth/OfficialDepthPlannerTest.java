package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialDepthPlannerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WKTReader wktReader = new WKTReader();
    private OfficialDepthCrossingExtractor extractor;
    private OfficialDepthPlanner planner;

    @BeforeEach
    void setUp() {
        OfficialPipeCatalog pipes = new OfficialPipeCatalog();
        extractor = new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes);
        planner = new OfficialDepthPlanner(
                extractor,
                new OfficialDepthOptimizer(pipes, new OfficialEconomics()),
                new OfficialDepthProfileValidator(pipes));
    }

    @Test
    void projectsOfficialUtilitiesToRouteChainage() throws Exception {
        RouteEdge edge = edge();
        List<ImportedOfficialFeature> features = List.of(
                feature("restriction", "gas", "LINESTRING (50 -10, 50 10)",
                        "{\"restriction_type\":\"gas_pipeline\"}"),
                feature("heat_network", "heat", "LINESTRING (70 -10, 70 10)",
                        "{\"diameter\":500}"));

        DepthCrossingExtraction result = extractor.extract(edge, features);

        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getCrossings()).extracting(DepthCrossing::getId)
                .containsExactly("gas", "heat");
        assertThat(result.getCrossings()).extracting(DepthCrossing::getStationM)
                .containsExactly(new BigDecimal("50.000"), new BigDecimal("70.000"));
        assertThat(result.getCrossings().get(0).getExistingTopDepthM()).isEqualByComparingTo("2.8");
        assertThat(result.getCrossings().get(1).getExistingHeightM()).isEqualByComparingTo("0.710");
    }

    @Test
    void ignoresUtilityAtTieInEndpoint() throws Exception {
        DepthCrossingExtraction result = extractor.extract(edge(), List.of(
                feature("heat_network", "target", "LINESTRING (100 -10, 100 10)",
                        "{\"diameter\":500}")));

        assertThat(result.getCrossings()).isEmpty();
        assertThat(result.getIssues()).isEmpty();
    }

    @Test
    void producesVerifiedProfileForSizedEdge() throws Exception {
        DepthProfileResult result = planner.plan(edge(), List.of(
                feature("restriction", "gas", "LINESTRING (50 -10, 50 10)",
                        "{\"restriction_type\":\"gas_pipeline\"}")));

        assertThat(result.isComplete()).isTrue();
        assertThat(result.getCrossings()).singleElement().satisfies(decision -> {
            assertThat(decision.getCrossingId()).isEqualTo("gas");
            assertThat(decision.getPassage()).isIn("above", "below");
        });
        assertThat(result.averageDepth(new BigDecimal("0"), new BigDecimal("100")))
                .isGreaterThan(new BigDecimal("3.0"));
        assertThat(result.depthAt(new BigDecimal("50"))).isEqualByComparingTo("3.7");
    }

    private RouteEdge edge() {
        return new RouteEdge(
                "edge",
                "start",
                "end",
                100,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(100, 0)),
                List.of(),
                new BigDecimal("2"),
                50);
    }

    private ImportedOfficialFeature feature(
            String objectType,
            String id,
            String wkt,
            String attributes) throws Exception {
        JsonNode node = objectMapper.readTree(attributes);
        return new ImportedOfficialFeature(id, objectType, node, wktReader.read(wkt));
    }
}
