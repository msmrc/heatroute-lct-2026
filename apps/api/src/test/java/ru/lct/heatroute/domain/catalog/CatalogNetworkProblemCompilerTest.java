package ru.lct.heatroute.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

class CatalogNetworkProblemCompilerTest {
    private final PhysicalAssetCompiler physicalCompiler = new PhysicalAssetCompiler();
    private final CatalogNetworkProblemCompiler compiler =
            new CatalogNetworkProblemCompiler(new OfficialPipeCatalog());

    @Test
    void compilesSharedPhysicalTrunkIntoOneAggregateFlowArc() {
        RoutingProblemSnapshot problemSnapshot = problem(
                List.of(demand("d1", "1.000"), demand("d2", "1.000")));
        CatalogFixture fixture = catalog(problemSnapshot, List.of(
                raw("trunk", points(0, 0, 10_000, 0)),
                raw("branch-1", points(10_000, 0, 10_000, 10_000)),
                raw("branch-2", points(10_000, 0, 20_000, 0))),
                List.of(
                        ports("trunk", "root-port", "junction"),
                        ports("branch-1", "junction", "d1-port"),
                        ports("branch-2", "junction", "d2-port")));

        CatalogNetworkProblemCompiler.Compilation compilation = compiler.compile(
                problemSnapshot, fixture.snapshot,
                Map.of("d1", "d1-port", "d2", "d2-port"),
                Map.of("root", "root-port"), 3);
        NetworkConstraintProblem problem = compilation.getProblem();

        assertThat(problem.getNodes()).hasSize(4);
        assertThat(problem.getAssets()).hasSize(3);
        CpSatNetworkOptimizer.Result solved = new CpSatNetworkOptimizer(new CpSatRuntime())
                .solve(problem, 5.0, 2026);
        assertThat(solved.getStatus()).isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
        String trunkArc = compilation.getArcBindings().stream()
                .filter(binding -> binding.getSourceOptionIds().contains("trunk"))
                .map(CatalogNetworkProblemCompiler.ArcBinding::getArcId)
                .findFirst().orElseThrow();
        assertThat(solved.getFlowUnits()).containsEntry(trunkArc, 2_000L);
        assertThat(solved.getSelectedAssets()).hasSize(3);
    }

    @Test
    void keepsZeroFlowDemandMandatoryThroughCatalogCompilation() {
        RoutingProblemSnapshot problemSnapshot = problem(List.of(demand("zero", "0")));
        CatalogFixture fixture = catalog(problemSnapshot,
                List.of(raw("route", points(0, 0, 10_000, 0))),
                List.of(ports("route", "root-port", "zero-port")));

        CatalogNetworkProblemCompiler.Compilation compilation = compiler.compile(
                problemSnapshot, fixture.snapshot, Map.of("zero", "zero-port"),
                Map.of("root", "root-port"), 3);
        CpSatNetworkOptimizer.Result solved = new CpSatNetworkOptimizer(new CpSatRuntime())
                .solve(compilation.getProblem(), 5.0, 2026);

        assertThat(solved.getStatus()).isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
        assertThat(solved.getSelectedAssets()).hasSize(1);
        assertThat(solved.getFlowUnits().values()).containsExactly(0L);
    }

    @Test
    void oppositeTraversalsAreSeparateArcsWithAMutualExclusionProof() {
        RoutingProblemSnapshot problemSnapshot = problem(List.of(demand("d", "1")));
        CatalogFixture fixture = catalog(problemSnapshot, List.of(
                raw("forward", points(0, 0, 10_000, 0)),
                raw("reverse", points(10_000, 0, 0, 0))),
                List.of(
                        ports("forward", "root-port", "d-port"),
                        ports("reverse", "d-port", "root-port")));

        CatalogNetworkProblemCompiler.Compilation compilation = compiler.compile(
                problemSnapshot, fixture.snapshot, Map.of("d", "d-port"),
                Map.of("root", "root-port"), 3);

        assertThat(compilation.getProblem().getAssets()).hasSize(2);
        assertThat(compilation.getProblem().getConflicts()).singleElement()
                .satisfies(conflict -> assertThat(conflict.getLiterals()).hasSize(2));
        assertThat(compilation.getArcBindings()).extracting(
                CatalogNetworkProblemCompiler.ArcBinding::isCanonicalDirection)
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void permitsCatalogToRepresentOnlyAUsableSubsetOfOptionalRoots() {
        RoutingProblemSnapshot base = problem(List.of(demand("d", "1")));
        RoutingProblemSnapshot problemSnapshot = new RoutingProblemSnapshot(
                base.getImportId(), base.getSourceHash(), base.getInputProfile(),
                base.getCodeVersion(), base.getRuleId(), base.getRuleVersion(),
                base.getCostCatalogVersion(), base.getFeatureSourceVersion(),
                base.getParameters(), base.getDemands(), List.of(
                        new RoutingProblemSnapshot.RootCandidate(
                                "root", new CatalogMetricPoint(0, 0), List.of()),
                        new RoutingProblemSnapshot.RootCandidate(
                                "unrepresented", new CatalogMetricPoint(50_000, 0), List.of())));
        CatalogFixture fixture = catalog(problemSnapshot,
                List.of(raw("route", points(0, 0, 10_000, 0))),
                List.of(ports("route", "root-port", "d-port")));

        CatalogNetworkProblemCompiler.Compilation compilation = compiler.compile(
                problemSnapshot, fixture.snapshot, Map.of("d", "d-port"),
                Map.of("root", "root-port"), 3);

        assertThat(compilation.getRootNodeById()).containsOnlyKeys("root");
        assertThat(compilation.getProblem().getNodes())
                .filteredOn(NetworkConstraintProblem.Node::isAllowedRoot)
                .singleElement();
    }

    @Test
    void compilesAnExplicitlyDeclaredPartialDemandModelWithoutChangingTheSnapshot() {
        RoutingProblemSnapshot problemSnapshot = problem(
                List.of(demand("served", "1.5"), demand("uncovered", "2.5")));
        CatalogFixture fixture = catalog(problemSnapshot,
                List.of(raw("route", points(0, 0, 10_000, 0))),
                List.of(ports("route", "root-port", "served-port")));

        CatalogNetworkProblemCompiler.Compilation compilation = compiler.compile(
                problemSnapshot, fixture.snapshot, Map.of("served", "served-port"),
                Map.of("root", "root-port"), 3, Set.of("uncovered"));

        assertThat(compilation.getDemandBindings()).extracting(
                CatalogNetworkProblemCompiler.DemandBinding::getDemandId)
                .containsExactly("served");
        assertThat(compilation.getProblem().getTotalDemandUnits()).isEqualTo(1_500L);
        assertThat(new CpSatNetworkOptimizer(new CpSatRuntime())
                .solve(compilation.getProblem(), 5.0, 2026).getStatus())
                .isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
    }

    @Test
    void requiresEveryMissingDemandToBeExplicitlyAccountedFor() {
        RoutingProblemSnapshot problemSnapshot = problem(
                List.of(demand("served", "1"), demand("uncovered", "1")));
        CatalogFixture fixture = catalog(problemSnapshot,
                List.of(raw("route", points(0, 0, 10_000, 0))),
                List.of(ports("route", "root-port", "served-port")));

        assertThatThrownBy(() -> compiler.compile(problemSnapshot, fixture.snapshot,
                Map.of("served", "served-port"), Map.of("root", "root-port"), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing port for demand: uncovered");
        assertThatThrownBy(() -> compiler.compile(problemSnapshot, fixture.snapshot,
                Map.of(), Map.of("root", "root-port"), 3, Set.of("served", "uncovered")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one demand must remain");
        assertThatThrownBy(() -> compiler.compile(problemSnapshot, fixture.snapshot,
                Map.of("served", "served-port"), Map.of("root", "root-port"), 3,
                Set.of("unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown excluded demand: unknown");
    }

    @Test
    void rejectsLossyFlowScalingAndForeignOrUnknownBindings() {
        RoutingProblemSnapshot precisionProblem = problem(List.of(demand("d", "1.2345")));
        CatalogFixture precisionCatalog = catalog(precisionProblem,
                List.of(raw("route", points(0, 0, 10_000, 0))),
                List.of(ports("route", "root-port", "d-port")));
        assertThatThrownBy(() -> compiler.compile(precisionProblem, precisionCatalog.snapshot,
                Map.of("d", "d-port"), Map.of("root", "root-port"), 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact flow scale");

        RoutingProblemSnapshot regular = problem(List.of(demand("d", "1")));
        assertThatThrownBy(() -> compiler.compile(regular, precisionCatalog.snapshot,
                Map.of("d", "missing"), Map.of("root", "root-port"), 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another problem snapshot");
    }

    private CatalogFixture catalog(RoutingProblemSnapshot problem,
            List<PhysicalAssetCompiler.CandidatePath> rawPaths, List<PortBinding> ports) {
        PhysicalAssetCompiler.Result physical = physicalCompiler.compile(rawPaths);
        Map<String, PortBinding> portsById = new LinkedHashMap<>();
        for (PortBinding binding : ports) portsById.put(binding.pathId, binding);
        List<DirectedPathOption> options = new ArrayList<>();
        for (PhysicalAssetCompiler.CandidatePath raw : rawPaths) {
            PortBinding binding = portsById.get(raw.getId());
            options.add(new DirectedPathOption(raw.getId(), binding.fromPort, binding.toPort,
                    PathAdmissionCertificate.Direction.FORWARD, "normal", raw.getCoordinates(),
                    physical.path(raw.getId()).getPhysicalAssetIds(), List.of(), 0L, 0L,
                    new DirectedPathOption.Provenance("bounded-router", "router-1",
                            problem.getSnapshotHash(), "window:" + raw.getId()), List.of()));
        }
        return new CatalogFixture(new RoutingCatalogSnapshot(problem.getSnapshotHash(),
                problem.getRuleId(), problem.getRuleVersion(), "catalog-1",
                physical.getPhysicalAssets(), options));
    }

    private RoutingProblemSnapshot problem(List<RoutingProblemSnapshot.Demand> demands) {
        return new RoutingProblemSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "source-1", "extended", "nextgen-1", "official", "rules-1", "cost-1",
                "import-version-1", OfficialRunParameters.defaults(), demands,
                List.of(new RoutingProblemSnapshot.RootCandidate(
                        "root", new CatalogMetricPoint(0, 0), List.of())));
    }

    private RoutingProblemSnapshot.Demand demand(String id, String flow) {
        return new RoutingProblemSnapshot.Demand(id, new BigDecimal(flow),
                new CatalogMetricPoint(1, 1), null);
    }

    private PhysicalAssetCompiler.CandidatePath raw(String id, List<CatalogMetricPoint> points) {
        return new PhysicalAssetCompiler.CandidatePath(id, "surface:new", "chain:" + id, points);
    }

    private PortBinding ports(String pathId, String fromPort, String toPort) {
        return new PortBinding(pathId, fromPort, toPort);
    }

    private static List<CatalogMetricPoint> points(long... coordinates) {
        ArrayList<CatalogMetricPoint> result = new ArrayList<>();
        for (int index = 0; index < coordinates.length; index += 2) {
            result.add(new CatalogMetricPoint(coordinates[index], coordinates[index + 1]));
        }
        return result;
    }

    private static final class CatalogFixture {
        private final RoutingCatalogSnapshot snapshot;
        private CatalogFixture(RoutingCatalogSnapshot snapshot) { this.snapshot = snapshot; }
    }

    private static final class PortBinding {
        private final String pathId;
        private final String fromPort;
        private final String toPort;

        private PortBinding(String pathId, String fromPort, String toPort) {
            this.pathId = pathId;
            this.fromPort = fromPort;
            this.toPort = toPort;
        }
    }
}
