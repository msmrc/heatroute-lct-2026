package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.CatalogMetricPoint;
import ru.lct.heatroute.domain.catalog.CatalogPhysicalAsset;
import ru.lct.heatroute.domain.catalog.DirectedPathOption;
import ru.lct.heatroute.domain.catalog.PathAdmissionCertificate;
import ru.lct.heatroute.domain.catalog.PhysicalAssetCompiler;
import ru.lct.heatroute.domain.catalog.RoutingCatalogSnapshot;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

class CatalogFrozenCandidateAssemblerTest {
    private final PhysicalAssetCompiler physicalCompiler = new PhysicalAssetCompiler();
    private final CatalogNetworkProblemCompiler problemCompiler =
            new CatalogNetworkProblemCompiler(new OfficialPipeCatalog());
    private final CatalogFrozenCandidateAssembler assembler = new CatalogFrozenCandidateAssembler();

    @Test
    void collapsesDegreeTwoAccountingAtomsIntoOneContinuousFrozenEdge() {
        RoutingProblemSnapshot problem = problem(List.of(demand("d", "1")));
        RawPath route = raw("route", "root-port", "d-port",
                CatalogPhysicalAsset.ConstructionMode.NEW_CONSTRUCTION,
                points(0, 0, 5_000, 0, 10_000, 0));
        Fixture fixture = fixture(problem, List.of(route));
        CpSatNetworkOptimizer.Result solved = solve(fixture.compilation);

        FrozenNetworkCandidate candidate = assembler.assemble("candidate", "nextgen", problem,
                fixture.catalog, fixture.compilation, solved,
                Map.of(
                        "root-port", node("existing_root", true, "root"),
                        "d-port", node("demand", false, "d")),
                List.of(), edge -> List.of());

        assertThat(candidate.getNodes()).hasSize(2);
        assertThat(candidate.getNodes()).extracting(RouteNode::getId)
                .containsExactlyInAnyOrder("root-port", "demand:d");
        assertThat(candidate.getEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.getDownstreamNodeId()).isEqualTo("demand:d");
            assertThat(edge.getCoordinates()).hasSize(3);
            assertThat(edge.getLengthM()).isEqualByComparingTo("10.000");
            assertThat(edge.getFlowTph()).isEqualByComparingTo("1.000");
            assertThat(edge.getDiameter()).isEqualTo(50);
        });
        assertThat(candidate.getConnections()).singleElement().satisfies(connection -> {
            assertThat(connection.getDemandId()).isEqualTo("d");
            assertThat(connection.getConnectionPointId()).isNull();
            assertThat(connection.getStatus()).isEqualTo("connected");
        });
        assertThat(candidate.isReconstructionRequired()).isFalse();
    }

    @Test
    void retainsExplicitBranchPortAndAggregatedTrunkFlow() {
        RoutingProblemSnapshot problem = problem(List.of(demand("d1", "1"), demand("d2", "1")));
        Fixture fixture = fixture(problem, List.of(
                raw("trunk", "root-port", "junction",
                        CatalogPhysicalAsset.ConstructionMode.NEW_CONSTRUCTION,
                        points(0, 0, 10_000, 0)),
                raw("branch-1", "junction", "d1-port",
                        CatalogPhysicalAsset.ConstructionMode.NEW_CONSTRUCTION,
                        points(10_000, 0, 10_000, 10_000)),
                raw("branch-2", "junction", "d2-port",
                        CatalogPhysicalAsset.ConstructionMode.NEW_CONSTRUCTION,
                        points(10_000, 0, 20_000, 0))));
        CpSatNetworkOptimizer.Result solved = solve(fixture.compilation);

        FrozenNetworkCandidate candidate = assembler.assemble("candidate", "nextgen", problem,
                fixture.catalog, fixture.compilation, solved,
                Map.of(
                        "root-port", node("existing_root", true, "root"),
                        "junction", node("new_chamber", true, null),
                        "d1-port", node("demand", false, "d1"),
                        "d2-port", node("demand", false, "d2")),
                List.of(), edge -> List.of());

        assertThat(candidate.getNodes()).hasSize(4);
        assertThat(candidate.getEdges()).hasSize(3);
        assertThat(candidate.getNodes()).filteredOn(RouteNode::isChamber).hasSize(2);
        assertThat(candidate.getEdges()).anySatisfy(edge ->
                assertThat(edge.getFlowTph()).isEqualByComparingTo("2.000"));
    }

    @Test
    void requiresExplicitRealizationAndCarriesReconstructionMode() {
        RoutingProblemSnapshot problem = problem(List.of(demand("d", "1")));
        Fixture fixture = fixture(problem, List.of(raw("route", "root-port", "d-port",
                CatalogPhysicalAsset.ConstructionMode.RECONSTRUCTION,
                points(0, 0, 10_000, 0))));
        CpSatNetworkOptimizer.Result solved = solve(fixture.compilation);

        assertThatThrownBy(() -> assembler.assemble("candidate", "nextgen", problem,
                fixture.catalog, fixture.compilation, solved,
                Map.of("root-port", node("existing_root", true, "root")),
                List.of(), edge -> List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing explicit node realization");

        FrozenNetworkCandidate candidate = assembler.assemble("candidate", "nextgen", problem,
                fixture.catalog, fixture.compilation, solved,
                Map.of(
                        "root-port", node("existing_root", true, "root"),
                        "d-port", node("demand", false, "d")),
                List.of(), edge -> List.of());
        assertThat(candidate.isReconstructionRequired()).isTrue();
    }

    private Fixture fixture(RoutingProblemSnapshot problem, List<RawPath> rawPaths) {
        List<PhysicalAssetCompiler.CandidatePath> candidates = new ArrayList<>();
        for (RawPath raw : rawPaths) {
            candidates.add(new PhysicalAssetCompiler.CandidatePath(raw.id, "surface",
                    raw.mode, "chain:" + raw.id, raw.points));
        }
        PhysicalAssetCompiler.Result physical = physicalCompiler.compile(candidates);
        List<DirectedPathOption> options = new ArrayList<>();
        for (RawPath raw : rawPaths) {
            options.add(new DirectedPathOption(raw.id, raw.fromPort, raw.toPort,
                    PathAdmissionCertificate.Direction.FORWARD, "normal", raw.points,
                    physical.path(raw.id).getPhysicalAssetIds(), List.of(), 0L, 0L,
                    new DirectedPathOption.Provenance("bounded-router", "router-1",
                            problem.getSnapshotHash(), "window:" + raw.id), List.of()));
        }
        RoutingCatalogSnapshot catalog = new RoutingCatalogSnapshot(problem.getSnapshotHash(),
                problem.getRuleId(), problem.getRuleVersion(), "catalog-1",
                physical.getPhysicalAssets(), options);
        Map<String, String> demandPorts = new LinkedHashMap<>();
        for (RoutingProblemSnapshot.Demand demand : problem.getDemands()) {
            demandPorts.put(demand.getId(), demand.getId() + "-port");
        }
        CatalogNetworkProblemCompiler.Compilation compilation = problemCompiler.compile(
                problem, catalog, demandPorts, Map.of("root", "root-port"), 3);
        return new Fixture(catalog, compilation);
    }

    private CpSatNetworkOptimizer.Result solve(CatalogNetworkProblemCompiler.Compilation compilation) {
        return new CpSatNetworkOptimizer(new CpSatRuntime()).solve(
                compilation.getProblem(), 5.0, 2026);
    }

    private CatalogFrozenCandidateAssembler.NodeRealization node(
            String type, boolean chamber, String targetId) {
        return new CatalogFrozenCandidateAssembler.NodeRealization(
                type, chamber, 0, targetId, null);
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

    private RawPath raw(String id, String fromPort, String toPort,
            CatalogPhysicalAsset.ConstructionMode mode, List<CatalogMetricPoint> points) {
        return new RawPath(id, fromPort, toPort, mode, points);
    }

    private static List<CatalogMetricPoint> points(long... coordinates) {
        List<CatalogMetricPoint> result = new ArrayList<>();
        for (int index = 0; index < coordinates.length; index += 2) {
            result.add(new CatalogMetricPoint(coordinates[index], coordinates[index + 1]));
        }
        return result;
    }

    private static final class Fixture {
        private final RoutingCatalogSnapshot catalog;
        private final CatalogNetworkProblemCompiler.Compilation compilation;

        private Fixture(RoutingCatalogSnapshot catalog,
                CatalogNetworkProblemCompiler.Compilation compilation) {
            this.catalog = catalog;
            this.compilation = compilation;
        }
    }

    private static final class RawPath {
        private final String id;
        private final String fromPort;
        private final String toPort;
        private final CatalogPhysicalAsset.ConstructionMode mode;
        private final List<CatalogMetricPoint> points;

        private RawPath(String id, String fromPort, String toPort,
                CatalogPhysicalAsset.ConstructionMode mode, List<CatalogMetricPoint> points) {
            this.id = id;
            this.fromPort = fromPort;
            this.toPort = toPort;
            this.mode = mode;
            this.points = points;
        }
    }
}
