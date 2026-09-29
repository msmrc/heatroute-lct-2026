package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.catalog.CatalogMetricPoint;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

class BoundedSharedNetworkSeedGeneratorTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final BoundedSharedNetworkSeedGenerator generator =
            new BoundedSharedNetworkSeedGenerator(router, new OfficialPipeCatalog());

    @Test
    void convertsABoundedCorridorTreeIntoDeterministicRootDemandPaths() {
        RoutingProblemSnapshot problem = problem();
        OfficialRoutingEnvironment environment = router.prepare(List.of());
        Set<String> demandIds = Set.of("a", "b", "c", "d");

        BoundedSharedNetworkSeedGenerator.Result result = generator.generate(
                problem, environment, demandIds,
                System.nanoTime() + Duration.ofSeconds(10).toNanos(), 64);

        assertThat(result.getRootAttempts()).isBetween(1L, 2L);
        assertThat(result.getNetworksExamined()).isBetween(1L, 6L);
        assertThat(result.getCoveredPriorityDemandIds()).containsAll(demandIds);
        assertThat(result.getPaths()).isNotEmpty().allSatisfy(path -> {
            assertThat(path.getRootId()).isEqualTo("root");
            assertThat(path.getPoints()).hasSizeGreaterThan(1);
            assertThat(path.getPoints().get(0)).isEqualTo(new CatalogMetricPoint(0, 0));
            RoutingProblemSnapshot.Demand demand = problem.getDemands().stream()
                    .filter(value -> value.getId().equals(path.getDemandId()))
                    .findFirst().orElseThrow();
            assertThat(path.getPoints().get(path.getPoints().size() - 1))
                    .isEqualTo(demand.getLocation());
        });
    }

    @Test
    void observesAnExpiredCatalogDeadlineBeforeStartingCorridorWork() {
        BoundedSharedNetworkSeedGenerator.Result result = generator.generate(
                problem(), router.prepare(List.of()), Set.of("a", "b"),
                System.nanoTime() - 1L, 64);

        assertThat(result.getPaths()).isEmpty();
        assertThat(result.getRootAttempts()).isZero();
        assertThat(result.isDeadlineReached()).isTrue();
    }

    @Test
    void adaptiveRetryPrioritizesMeasuredCoverageOverAnUnprovenNearbyChamber() {
        List<RoutingProblemSnapshot.Demand> demands = List.of(
                demand("a", 60_000, -30_000), demand("b", 60_000, 30_000));
        RoutingProblemSnapshot.RootRealization existing =
                new RoutingProblemSnapshot.RootRealization(
                        "existing_chamber_tie_in", true, 0, "nearby", null);
        RoutingProblemSnapshot.RootRealization measured =
                new RoutingProblemSnapshot.RootRealization(
                        "new_tie_in_chamber", true, 0, "measured", null);
        RoutingProblemSnapshot.RootCandidate unprovenNearby =
                new RoutingProblemSnapshot.RootCandidate(
                        "unproven-nearby", new CatalogMetricPoint(40_000, 0),
                        List.of(), existing);
        RoutingProblemSnapshot.RootCandidate provenFarther =
                new RoutingProblemSnapshot.RootCandidate(
                        "proven-farther", new CatalogMetricPoint(0, 0),
                        List.of(), measured);
        RoutingProblemSnapshot problem = new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000093"),
                "source-shared", "extended", "nextgen-1", "official", "rules-1",
                "cost-1", "feature-source-1", OfficialRunParameters.defaults(),
                demands, List.of(unprovenNearby, provenFarther));

        BoundedSharedNetworkSeedGenerator.Result result = generator.generate(
                problem, router.prepare(List.of()), Set.of("a", "b"),
                Map.of("proven-farther", Set.of("a", "b")),
                System.nanoTime() + Duration.ofSeconds(10).toNanos(), 64);

        assertThat(result.getCoveredPriorityDemandIds()).containsExactlyInAnyOrder("a", "b");
        assertThat(result.getPaths()).extracting(
                BoundedSharedNetworkSeedGenerator.SeedPath::getRootId)
                .containsOnly("proven-farther");
    }

    private static RoutingProblemSnapshot problem() {
        List<RoutingProblemSnapshot.Demand> demands = List.of(
                demand("a", 60_000, -30_000), demand("b", 60_000, 30_000),
                demand("c", 110_000, -30_000), demand("d", 110_000, 30_000));
        RoutingProblemSnapshot.RootRealization realization =
                new RoutingProblemSnapshot.RootRealization(
                        "existing_chamber_tie_in", true, 0, "source", null);
        RoutingProblemSnapshot.RootCandidate root =
                new RoutingProblemSnapshot.RootCandidate(
                        "root", new CatalogMetricPoint(0, 0), List.of(), realization);
        return new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000092"),
                "source-shared", "extended", "nextgen-1", "official", "rules-1",
                "cost-1", "feature-source-1", OfficialRunParameters.defaults(),
                demands, List.of(root));
    }

    private static RoutingProblemSnapshot.Demand demand(String id, long xMm, long yMm) {
        return new RoutingProblemSnapshot.Demand(id, BigDecimal.ONE,
                new CatalogMetricPoint(xMm, yMm), "connection-" + id);
    }
}
