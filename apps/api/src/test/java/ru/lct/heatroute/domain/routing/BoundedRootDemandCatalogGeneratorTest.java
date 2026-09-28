package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.catalog.CatalogMetricPoint;
import ru.lct.heatroute.domain.catalog.PathAdmissionCertificate;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class BoundedRootDemandCatalogGeneratorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WKTReader wktReader = new WKTReader();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final BoundedRootDemandCatalogGenerator generator =
            new BoundedRootDemandCatalogGenerator(
                    new OfficialObstacleRouter(rules), new OfficialPipeCatalog());

    @Test
    void compilesDeterministicCheckedPathsAndPhysicalAssetsWithoutClaimingGlobalCompleteness() {
        RoutingProblemSnapshot problem = problem(List.of(demand("one", 100_000, 0)));
        BoundedRootDemandCatalogGenerator.Options options = options(8, 32, 4);

        BoundedRootDemandCatalogGenerator.GeneratedCatalog first =
                generator.generate(problem, List.of(), options);
        BoundedRootDemandCatalogGenerator.GeneratedCatalog second =
                generator.generate(problem, List.of(), options);

        assertThat(first.getBuildResult().getSnapshot().getCatalogHash()).isEqualTo(
                second.getBuildResult().getSnapshot().getCatalogHash());
        assertThat(first.getDemandPortById()).containsEntry("one", "demand-port:one");
        assertThat(first.getRootPortById()).containsEntry("root", "root-port:root");
        assertThat(first.getProbeDiameterMm()).isEqualTo(50);
        assertThat(first.getWindowFingerprint()).startsWith("window:");
        assertThat(first.getBuildResult().isComplete()).isFalse();
        assertThat(first.getBuildResult().getGeneratorCoverage())
                .containsEntry("root-demand-pairs", true)
                .containsEntry("shared-network-seeds", false)
                .containsEntry("chamber-configurations", false);
        assertThat(first.getBuildResult().getRemainingWork())
                .contains("shared-network-seeds", "chamber-configurations");
        assertThat(first.getBuildResult().getSnapshot().getPhysicalAssets()).isNotEmpty();
        assertThat(first.getBuildResult().getSnapshot().getPathOptions()).isNotEmpty()
                .allSatisfy(option -> {
                    assertThat(option.getFromPortId()).isEqualTo("root-port:root");
                    assertThat(option.getToPortId()).isEqualTo("demand-port:one");
                    assertThat(option.getCoordinates().get(0)).isEqualTo(new CatalogMetricPoint(0, 0));
                    assertThat(option.getCoordinates().get(option.getCoordinates().size() - 1))
                            .isEqualTo(new CatalogMetricPoint(100_000, 0));
                    assertThat(option.admissionStatus(50, problem.getRuleId(),
                            problem.getRuleVersion(), problem.getSnapshotHash()))
                            .isEqualTo(PathAdmissionCertificate.Status.VERIFIED_ALLOWED);
                });
    }

    @Test
    void reusesTheOfficialObstacleRouterForGeometricallyDistinctDetours() throws Exception {
        RoutingProblemSnapshot problem = problem(List.of(demand("one", 100_000, 0)));
        ImportedOfficialFeature park = new ImportedOfficialFeature(
                "blocked-park", "restriction",
                objectMapper.readTree("{\"restriction_type\":\"park\"}"),
                wktReader.read("POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))"));

        BoundedRootDemandCatalogGenerator.GeneratedCatalog result = generator.generate(
                problem, List.of(park), options(8, 32, 4));

        assertThat(result.getBuildResult().getSnapshot().getPathOptions()).isNotEmpty()
                .allSatisfy(option -> {
                    assertThat(option.getCoordinates()).hasSizeGreaterThan(2);
                    assertThat(option.getLengthMm()).isGreaterThan(100_000L);
                });
        assertThat(result.getBuildResult().getCounters().get("route_calls")).isPositive();
        assertThat(result.getBuildResult().getCounters().get("features")).isEqualTo(1L);
    }

    @Test
    void exposesPairAndRouteCallTruncationInsteadOfReturningNoRoute() {
        RoutingProblemSnapshot twoDemands = problem(List.of(
                demand("one", 100_000, 0), demand("two", 120_000, 20_000)));
        BoundedRootDemandCatalogGenerator.GeneratedCatalog pairLimited = generator.generate(
                twoDemands, List.of(), options(1, 32, 4));

        assertThat(pairLimited.getBuildResult().getTruncationReasons()).contains("pair_limit");
        assertThat(pairLimited.getBuildResult().getRemainingWork())
                .contains("root-demand-pairs:1");
        assertThat(pairLimited.getBuildResult().getCounters())
                .containsEntry("pairs_total", 2L).containsEntry("pairs_attempted", 1L);

        BoundedRootDemandCatalogGenerator.GeneratedCatalog callLimited = generator.generate(
                problem(List.of(demand("one", 100_000, 0))), List.of(), options(8, 1, 4));
        assertThat(callLimited.getBuildResult().getTruncationReasons())
                .contains("route_call_limit");
        assertThat(callLimited.getBuildResult().getRemainingWork())
                .contains("pair-diversity:root:one");
        assertThat(callLimited.getBuildResult().getSnapshot().getPathOptions()).isNotEmpty();
    }

    private static BoundedRootDemandCatalogGenerator.Options options(
            int pairs, int calls, int paths) {
        return BoundedRootDemandCatalogGenerator.Options.bounded(
                Duration.ofSeconds(10), pairs, calls, paths, 4);
    }

    private static RoutingProblemSnapshot problem(List<RoutingProblemSnapshot.Demand> demands) {
        return new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000091"),
                "source-bounded", "extended", "nextgen-1", "official", "rules-1",
                "cost-1", "feature-source-1", OfficialRunParameters.defaults(), demands,
                List.of(new RoutingProblemSnapshot.RootCandidate(
                        "root", new CatalogMetricPoint(0, 0), List.of())));
    }

    private static RoutingProblemSnapshot.Demand demand(String id, long xMm, long yMm) {
        return new RoutingProblemSnapshot.Demand(id, BigDecimal.ONE,
                new CatalogMetricPoint(xMm, yMm), null);
    }
}
