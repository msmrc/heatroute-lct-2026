package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.catalog.CatalogMetricPoint;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.PathAdmissionCertificate;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class BoundedCatalogTerminalFeasibilityTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);
    private final BoundedRootDemandCatalogGenerator generator =
            new BoundedRootDemandCatalogGenerator(router, new OfficialPipeCatalog());

    @Test
    void absenceOfWallNormalIsNotProofThatAStraightBuildingExitIsImpossible() throws Exception {
        List<ImportedOfficialFeature> features = new ArrayList<>();
        features.add(feature("own", "oks", "POLYGON ((-1 -1,1 -1,1 1,-1 1,-1 -1))"));
        features.add(feature("east", "park", "POLYGON ((3.5 -0.5,4.5 -0.5,4.5 0.5,3.5 0.5,3.5 -0.5))"));
        features.add(feature("west", "park", "POLYGON ((-4.5 -0.5,-3.5 -0.5,-3.5 0.5,-4.5 0.5,-4.5 -0.5))"));
        features.add(feature("north", "park", "POLYGON ((-0.5 3.5,0.5 3.5,0.5 4.5,-0.5 4.5,-0.5 3.5))"));
        features.add(feature("south", "park", "POLYGON ((-0.5 -4.5,0.5 -4.5,0.5 -3.5,-0.5 -3.5,-0.5 -4.5))"));
        Coordinate terminal = new Coordinate(0, 0);
        Coordinate root = new Coordinate(100, 100);
        OfficialRoutingEnvironment environment = router.prepare(features);
        assertThat(new BuildingWallNormals().candidates(
                features.get(0).getMetricGeometry(), terminal, 5.2))
                .as("all four old wall normals are obstructed").hasSize(4)
                .allSatisfy(exit -> assertThat(rules.segmentAllowed(terminal, exit.point(),
                        rules.baseConstraints(features.subList(1, features.size()), 50))).isFalse());
        assertThat(environment.pointInsideForbiddenClearance(50, terminal)).isTrue();
        // The organizer's 29.09 clarification permits this straight oblique exit. The foreign
        // restrictions stay fully checked; this test does not admit the eventual full network.
        assertThat(rules.segmentAllowed(terminal, root,
                rules.baseConstraints(features.subList(1, features.size()), 50)))
                .as("a straight diagonal avoids every foreign forbidden clearance").isTrue();

        BoundedRootDemandCatalogGenerator.GeneratedCatalog generated = generator.generate(
                problem(List.of(demand("one", 0, 0, "1"))), features, options());

        assertThat(generated.getStructurallyUnroutableDemandIds()).isEmpty();
        assertThat(generated.getBuildResult().getCounters())
                .containsEntry("demands_structurally_unroutable", 0L);
    }

    @Test
    void commonProbeDiameterCannotProveThatAnIndividualSmallDemandIsBlocked() throws Exception {
        ImportedOfficialFeature park = feature("nearby", "park",
                "POLYGON ((1.5 -1,3 -1,3 1,1.5 1,1.5 -1))");
        OfficialRoutingEnvironment environment = router.prepare(List.of(park));
        Coordinate terminal = new Coordinate(0, 0);
        assertThat(environment.pointInsideForbiddenClearance(50, terminal)).isFalse();
        RoutingProblemSnapshot problem = problem(List.of(
                demand("small", 0, 0, "1"), demand("large", 200_000, 200_000, "1000")));

        BoundedRootDemandCatalogGenerator.GeneratedCatalog generated = generator.generate(
                problem, List.of(park), options());

        assertThat(environment.pointInsideForbiddenClearance(generated.getProbeDiameterMm(), terminal))
                .as("the whole-network probe DU is not the final individual branch DU").isTrue();
        assertThat(generated.getStructurallyUnroutableDemandIds()).isEmpty();
    }

    @Test
    void independentDemandPathIsCheckedAndCertifiedOnlyAtItsOwnFlowDiameter() {
        RoutingProblemSnapshot problem = problem(List.of(
                demand("a-small", 0, 0, "1"), demand("z-large", 200_000, 200_000, "1000")));
        BoundedRootDemandCatalogGenerator.GeneratedCatalog generated = generator.generate(
                problem, List.of(), options());

        assertThat(generated.getProbeDiameterMm()).isGreaterThan(50);
        assertThat(generated.getBuildResult().getSnapshot().getPathOptions()).isNotEmpty()
                .allSatisfy(option -> {
                    assertThat(option.getToPortId()).isEqualTo("demand-port:a-small");
                    assertThat(option.admissionStatus(50, problem.getRuleId(),
                            problem.getRuleVersion(), problem.getSnapshotHash()))
                            .isEqualTo(PathAdmissionCertificate.Status.VERIFIED_ALLOWED);
                    assertThat(option.admissionStatus(generated.getProbeDiameterMm(),
                            problem.getRuleId(), problem.getRuleVersion(), problem.getSnapshotHash()))
                            .as("a small-branch certificate cannot authorize a resized shared trunk")
                            .isEqualTo(PathAdmissionCertificate.Status.UNCHECKED);
                });
    }

    @Test
    void sharedProfileDoesNotInventAUniformDiameterCertificateOrExcludeThePath() {
        RoutingProblemSnapshot problem = new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000908"),
                "shared-terminal-feasibility", "extended", "heatroute-6", "official", "2026-09-29",
                "cost-1", "source-1", OfficialRunParameters.defaults(), List.of(
                        connectedDemand("a", 60_000, -30_000, "1"),
                        connectedDemand("b", 60_000, 30_000, "8"),
                        connectedDemand("c", 110_000, -30_000, "1"),
                        connectedDemand("d", 110_000, 30_000, "8")),
                List.of(new RoutingProblemSnapshot.RootCandidate("root", new CatalogMetricPoint(0, 0),
                        List.of(), new RoutingProblemSnapshot.RootRealization(
                                "existing_chamber_tie_in", true, 0, "root", null))));
        BoundedRootDemandCatalogGenerator.GeneratedCatalog generated = generator.generate(
                problem, List.of(), BoundedRootDemandCatalogGenerator.Options.bounded(
                        Duration.ofSeconds(10), 4, 64, 1, 4));

        assertThat(generated.getBuildResult().getCounters().get("shared_seed_paths")).isEqualTo(4L);
        assertThat(generated.getBuildResult().getSnapshot().getPathOptions()).hasSize(4)
                .allSatisfy(option -> {
                    assertThat(option.getCertificatesByDiameter()).isEmpty();
                    assertThat(option.admissionStatus(generated.getProbeDiameterMm(),
                            problem.getRuleId(), problem.getRuleVersion(), problem.getSnapshotHash()))
                            .isEqualTo(PathAdmissionCertificate.Status.UNCHECKED);
                });
        OfficialPipeCatalog pipes = new OfficialPipeCatalog();
        CatalogNetworkProblemCompiler.Compilation compiled = new CatalogNetworkProblemCompiler(pipes)
                .compile(problem, generated.getBuildResult().getSnapshot(),
                        generated.getDemandPortById(), generated.getRootPortById(), 3);
        assertThat(compiled.getProblem().getAssets()).isNotEmpty().allSatisfy(asset ->
                assertThat(asset.getDiameters()).hasSize(pipes.entries().size()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"social_area", "park", "prohibited_site", "water", "railway"})
    void keepsProvenForbiddenFootprintRejectionBeforeAnyRouteSearch(String type) throws Exception {
        ImportedOfficialFeature forbidden = feature("blocked", type,
                "POLYGON ((-2 -2,2 -2,2 2,-2 2,-2 -2))");

        BoundedRootDemandCatalogGenerator.GeneratedCatalog generated = generator.generate(
                problem(List.of(demand("one", 0, 0, "1"))), List.of(forbidden), options());

        assertThat(generated.getStructurallyUnroutableDemandIds()).containsExactly("one");
        assertThat(generated.getBuildResult().getCounters()).containsEntry("route_calls", 0L);
        assertThat(generated.getBuildResult().getSnapshot().getPathOptions()).isEmpty();
    }

    @Test
    void retainsBoundaryAndClearanceOnlyCasesForCheckedSearch() throws Exception {
        ImportedOfficialFeature park = feature("nearby", "park",
                "POLYGON ((0 0,10 0,10 10,0 10,0 0))");
        BoundedRootDemandCatalogGenerator.GeneratedCatalog generated = generator.generate(
                problem(List.of(demand("boundary", 0, 5_000, "1"),
                        demand("clearance", -100, 5_000, "1"))), List.of(park), options());

        assertThat(generated.getStructurallyUnroutableDemandIds()).isEmpty();
        assertThat(generated.getBuildResult().isComplete()).isFalse();
    }

    private static BoundedRootDemandCatalogGenerator.Options options() {
        return BoundedRootDemandCatalogGenerator.Options.bounded(Duration.ofSeconds(2), 1, 1, 1, 4);
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode()
                .put("restriction_type", type), new WKTReader().read(wkt));
    }

    private static RoutingProblemSnapshot problem(List<RoutingProblemSnapshot.Demand> demands) {
        return new RoutingProblemSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000907"),
                "terminal-feasibility", "extended", "heatroute-6", "official", "2026-09-29",
                "cost-1", "source-1", OfficialRunParameters.defaults(), demands,
                List.of(new RoutingProblemSnapshot.RootCandidate("root",
                        new CatalogMetricPoint(100_000, 100_000), List.of())));
    }

    private static RoutingProblemSnapshot.Demand demand(String id, long x, long y, String flow) {
        return new RoutingProblemSnapshot.Demand(id, new BigDecimal(flow),
                new CatalogMetricPoint(x, y), null);
    }

    private static RoutingProblemSnapshot.Demand connectedDemand(String id, long x, long y, String flow) {
        return new RoutingProblemSnapshot.Demand(id, new BigDecimal(flow),
                new CatalogMetricPoint(x, y), "connection-" + id);
    }
}
