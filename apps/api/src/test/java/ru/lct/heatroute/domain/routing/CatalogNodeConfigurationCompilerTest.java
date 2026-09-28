package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.catalog.CatalogMetricPoint;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.DirectedPathOption;
import ru.lct.heatroute.domain.catalog.PathAdmissionCertificate;
import ru.lct.heatroute.domain.catalog.PhysicalAssetCompiler;
import ru.lct.heatroute.domain.catalog.RoutingCatalogSnapshot;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

class CatalogNodeConfigurationCompilerTest {
    private final CatalogNetworkProblemCompiler problemCompiler =
            new CatalogNetworkProblemCompiler(new OfficialPipeCatalog());
    private final CatalogNodeConfigurationCompiler configurationCompiler =
            new CatalogNodeConfigurationCompiler();

    @Test
    void createsAnExactChamberChoiceOnlyForCompatibleOrthogonalBranchRays() {
        CatalogNetworkProblemCompiler.Compilation orthogonal = compileShared(true);
        CatalogNetworkProblemCompiler.Compilation oblique = compileShared(false);

        assertThat(orthogonal.getNodeConfigurationBindings())
                .filteredOn(binding -> binding.isChamber()
                        && binding.getConfiguration().getIncidentAssetIds().size() == 3)
                .singleElement().satisfies(binding ->
                        assertThat(binding.getNodeType()).isEqualTo("new_branch_chamber"));
        assertThat(oblique.getNodeConfigurationBindings())
                .noneMatch(binding -> binding.isChamber()
                        && binding.getConfiguration().getIncidentAssetIds().size() == 3);
    }

    @Test
    void anExistingRootRayCannotBeReusedByANewConnection() {
        RoutingProblemSnapshot snapshot = snapshot(
                List.of(new RoutingProblemSnapshot.Demand(
                        "d", BigDecimal.ONE, point(10_000, 0), null)),
                List.of(new RoutingProblemSnapshot.DirectionVector(1_000, 0)));
        CatalogNetworkProblemCompiler.Compilation configured = compile(snapshot,
                List.of(path("route", "d-port", point(0, 0), point(10_000, 0))),
                Map.of("d", "d-port"));

        assertThat(configured.getNodeConfigurationBindings())
                .noneMatch(binding -> binding.getConfiguration().getNodeId()
                        .equals(configured.getRootNodeById().get("root")));
        CpSatNetworkOptimizer.Result solved = new CpSatNetworkOptimizer(new CpSatRuntime())
                .solve(configured.getProblem(), 5.0, 2026);
        assertThat(solved.getStatus()).isEqualTo(CpSatNetworkOptimizer.Status.INFEASIBLE);
    }

    @Test
    void ignoresOptionalRootsThatHaveNoCatalogPath() {
        RoutingProblemSnapshot snapshot = snapshotWithRoots(
                List.of(new RoutingProblemSnapshot.Demand(
                        "d", BigDecimal.ONE, point(10_000, 0), null)),
                List.of(
                        new RoutingProblemSnapshot.RootCandidate(
                                "root", point(0, 0), List.of(),
                                new RoutingProblemSnapshot.RootRealization(
                                        "existing_root", true, 0, "root", null)),
                        new RoutingProblemSnapshot.RootCandidate(
                                "unused-root", point(100_000, 100_000), List.of(),
                                new RoutingProblemSnapshot.RootRealization(
                                        "existing_root", true, 0, "unused-root", null))));

        CatalogNetworkProblemCompiler.Compilation configured = compile(snapshot,
                List.of(path("route", "d-port", point(0, 0), point(10_000, 0))),
                Map.of("d", "d-port"));

        assertThat(configured.getRootNodeById()).containsOnlyKeys("root");
    }

    private CatalogNetworkProblemCompiler.Compilation compileShared(boolean orthogonal) {
        RoutingProblemSnapshot snapshot = snapshot(List.of(
                new RoutingProblemSnapshot.Demand(
                        "east", BigDecimal.ONE, point(20_000, 0), null),
                new RoutingProblemSnapshot.Demand(
                        "branch", BigDecimal.ONE,
                        orthogonal ? point(10_000, 10_000) : point(20_000, 10_000), null)),
                List.of());
        List<PathSpec> paths = List.of(
                path("east-path", "east-port",
                        point(0, 0), point(10_000, 0), point(20_000, 0)),
                path("branch-path", "branch-port",
                        point(0, 0), point(10_000, 0),
                        orthogonal ? point(10_000, 10_000) : point(20_000, 10_000)));
        return compile(snapshot, paths,
                Map.of("east", "east-port", "branch", "branch-port"));
    }

    private CatalogNetworkProblemCompiler.Compilation compile(
            RoutingProblemSnapshot snapshot, List<PathSpec> paths,
            Map<String, String> demandPorts) {
        List<PhysicalAssetCompiler.CandidatePath> raw = new ArrayList<>();
        for (PathSpec path : paths) {
            raw.add(new PhysicalAssetCompiler.CandidatePath(
                    path.id, "surface", "chain:" + path.id, path.points));
        }
        PhysicalAssetCompiler.Result physical = new PhysicalAssetCompiler().compile(raw);
        List<DirectedPathOption> options = new ArrayList<>();
        for (PathSpec path : paths) {
            options.add(new DirectedPathOption(
                    path.id, "root-port", path.demandPort,
                    PathAdmissionCertificate.Direction.FORWARD, "normal", path.points,
                    physical.path(path.id).getPhysicalAssetIds(), List.of(),
                    0L, 0L, new DirectedPathOption.Provenance(
                            "test", "1", snapshot.getSnapshotHash(), "window"), List.of()));
        }
        RoutingCatalogSnapshot catalog = new RoutingCatalogSnapshot(
                snapshot.getSnapshotHash(), snapshot.getRuleId(), snapshot.getRuleVersion(),
                "catalog", physical.getPhysicalAssets(), options);
        CatalogNetworkProblemCompiler.Compilation base = problemCompiler.compile(
                snapshot, catalog, demandPorts, Map.of("root", "root-port"), 3);
        return configurationCompiler.compile(snapshot, base);
    }

    private RoutingProblemSnapshot snapshot(
            List<RoutingProblemSnapshot.Demand> demands,
            List<RoutingProblemSnapshot.DirectionVector> directions) {
        return snapshotWithRoots(demands, List.of(
                new RoutingProblemSnapshot.RootCandidate(
                        "root", point(0, 0), directions,
                        new RoutingProblemSnapshot.RootRealization(
                                "existing_root", true, directions.size(), "root", null))));
    }

    private RoutingProblemSnapshot snapshotWithRoots(
            List<RoutingProblemSnapshot.Demand> demands,
            List<RoutingProblemSnapshot.RootCandidate> roots) {
        return new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                "source", "extended", "nextgen-1",
                "official", "rules-1", "cost-1", "features-1",
                OfficialRunParameters.defaults(), demands, roots);
    }

    private static PathSpec path(
            String id, String demandPort, CatalogMetricPoint... points) {
        return new PathSpec(id, demandPort, List.of(points));
    }

    private static CatalogMetricPoint point(long xMm, long yMm) {
        return new CatalogMetricPoint(xMm, yMm);
    }

    private static final class PathSpec {
        private final String id;
        private final String demandPort;
        private final List<CatalogMetricPoint> points;

        private PathSpec(String id, String demandPort, List<CatalogMetricPoint> points) {
            this.id = id;
            this.demandPort = demandPort;
            this.points = points;
        }
    }
}
