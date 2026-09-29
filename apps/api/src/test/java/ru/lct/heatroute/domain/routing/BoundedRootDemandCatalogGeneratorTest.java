package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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

    @Test
    void coversEveryDemandBeforeExploringAdditionalRoots() {
        RoutingProblemSnapshot problem = problem(
                List.of(
                        demand("near-left", 10_000, 0),
                        demand("near-right", 290_000, 0)),
                List.of(
                        root("left", 0, 0),
                        root("right", 300_000, 0)));

        BoundedRootDemandCatalogGenerator.GeneratedCatalog result = generator.generate(
                problem, List.of(), options(2, 8, 1));

        assertThat(result.getBuildResult().getCounters()).containsEntry("pairs_attempted", 2L);
        assertThat(result.getBuildResult().getSnapshot().getPathOptions())
                .extracting(option -> option.getToPortId())
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "demand-port:near-left", "demand-port:near-right"));
    }

    @Test
    void approachesAnExistingRootOnlyAlongACompatibleRay() {
        RoutingProblemSnapshot.DirectionVector east =
                new RoutingProblemSnapshot.DirectionVector(1_000, 0);
        RoutingProblemSnapshot.DirectionVector west =
                new RoutingProblemSnapshot.DirectionVector(-1_000, 0);
        RoutingProblemSnapshot problem = problem(
                List.of(demand("one", 100_000, 100_000)),
                List.of(root("root", 0, 0, List.of(east, west))));

        BoundedRootDemandCatalogGenerator.GeneratedCatalog result = generator.generate(
                problem, List.of(), options(1, 4, 1));

        assertThat(result.getBuildResult().getSnapshot().getPathOptions())
                .anySatisfy(option -> {
                    CatalogMetricPoint root = option.getCoordinates().get(0);
                    CatalogMetricPoint next = option.getCoordinates().get(1);
                    long dx = next.getXMm() - root.getXMm();
                    long dy = next.getYMm() - root.getYMm();
                    assertThat(ExpertChamberGeometryRules.compatibleRays(
                            dx, dy, east.getDeltaXMm(), east.getDeltaYMm()))
                            .as("root=%s:%s next=%s:%s delta=%s:%s",
                                    root.getXMm(), root.getYMm(), next.getXMm(), next.getYMm(),
                                    dx, dy).isTrue();
                    assertThat(ExpertChamberGeometryRules.compatibleRays(
                            dx, dy, west.getDeltaXMm(), west.getDeltaYMm())).isTrue();
                });
    }

    @Test
    void seedsEnoughDistinctLegalRootsForAllMandatoryTerminals() {
        RoutingProblemSnapshot.DirectionVector east =
                new RoutingProblemSnapshot.DirectionVector(1_000, 0);
        RoutingProblemSnapshot.DirectionVector west =
                new RoutingProblemSnapshot.DirectionVector(-1_000, 0);
        RoutingProblemSnapshot problem = problem(
                List.of(
                        demand("one", 50_000, 100_000),
                        demand("two", 150_000, 100_000),
                        demand("three", 250_000, 100_000)),
                List.of(
                        root("left", 0, 0, List.of(east, west)),
                        root("right", 300_000, 0, List.of(east, west))));

        BoundedRootDemandCatalogGenerator.GeneratedCatalog result = generator.generate(
                problem, List.of(), options(6, 32, 1));

        assertThat(result.getBuildResult().getCounters())
                .containsEntry("normal_root_demands", 3L)
                .containsEntry("normal_root_count", 2L)
                .containsEntry("normal_root_capacity", 4L)
                .containsEntry("normal_root_assignable_demands", 3L);
        assertThat(result.getBuildResult().getRemainingWork())
                .noneMatch(value -> value.startsWith("normal-root-capacity:")
                        || value.startsWith("normal-root-assignment:"));
        assertThat(result.getBuildResult().getSnapshot().getPathOptions())
                .extracting(option -> option.getFromPortId())
                .contains("root-port:left", "root-port:right");
    }

    private static BoundedRootDemandCatalogGenerator.Options options(
            int pairs, int calls, int paths) {
        return BoundedRootDemandCatalogGenerator.Options.bounded(
                Duration.ofSeconds(10), pairs, calls, paths, 4);
    }

    private static RoutingProblemSnapshot problem(List<RoutingProblemSnapshot.Demand> demands) {
        return problem(demands, List.of(root("root", 0, 0)));
    }

    private static RoutingProblemSnapshot problem(List<RoutingProblemSnapshot.Demand> demands,
            List<RoutingProblemSnapshot.RootCandidate> roots) {
        return new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000091"),
                "source-bounded", "extended", "heatroute-1", "official", "rules-1",
                "cost-1", "feature-source-1", OfficialRunParameters.defaults(), demands, roots);
    }

    private static RoutingProblemSnapshot.RootCandidate root(String id, long xMm, long yMm) {
        return root(id, xMm, yMm, List.of());
    }

    private static RoutingProblemSnapshot.RootCandidate root(String id, long xMm, long yMm,
            List<RoutingProblemSnapshot.DirectionVector> directions) {
        return new RoutingProblemSnapshot.RootCandidate(
                id, new CatalogMetricPoint(xMm, yMm), directions);
    }

    private static RoutingProblemSnapshot.Demand demand(String id, long xMm, long yMm) {
        return new RoutingProblemSnapshot.Demand(id, BigDecimal.ONE,
                new CatalogMetricPoint(xMm, yMm), null);
    }
}
