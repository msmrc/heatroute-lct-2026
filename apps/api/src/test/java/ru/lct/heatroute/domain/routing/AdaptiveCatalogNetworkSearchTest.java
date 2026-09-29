package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.catalog.CatalogBuildResult;
import ru.lct.heatroute.domain.catalog.RoutingCatalogSnapshot;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.optimization.CatalogIdentity;
import ru.lct.heatroute.domain.optimization.ConflictStore;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;

class AdaptiveCatalogNetworkSearchTest {
    private final OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialRouteGeometryRules geometryRules =
            new OfficialRouteGeometryRules(constraints, new OfficialCrossingGeometry());
    private final FrozenNetworkEvaluator evaluator = new FrozenNetworkEvaluator(
            new OfficialRouteValidator(geometryRules), new OfficialObstacleRouter(geometryRules),
            new OfficialNetworkSizer(pipes),
            new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(constraints, pipes),
                    new OfficialDepthOptimizer(pipes, economics),
                    new OfficialDepthProfileValidator(pipes)),
            new OfficialVariantEconomicsCalculator(pipes, economics));
    private final AdaptiveCatalogNetworkSearch search = new AdaptiveCatalogNetworkSearch(
            new CatalogFrozenNetworkRefinement(
                    new CpSatNetworkOptimizer(new CpSatRuntime()), evaluator));

    @Test
    void missingCanonicalDiameterExpandsAdditivelyAndKeepsOneDeadlineArchive() {
        AdaptiveCatalogNetworkSearch.Stage initial = stage(
                "catalog-1", List.of(option(100)), false);
        AdaptiveCatalogNetworkSearch.Stage expanded = stage(
                "catalog-2", List.of(option(50), option(100)), true);
        AcceptedSolutionArchive archive = new AcceptedSolutionArchive(3);
        AtomicInteger calls = new AtomicInteger();

        AdaptiveCatalogNetworkSearch.Result result = search.solve(initial, new ConflictStore(),
                archive, (current, request, remainingNanos) -> {
                    calls.incrementAndGet();
                    assertThat(current).isSameAs(initial);
                    assertThat(request.getReason()).isEqualTo(
                            "canonical_diameter_missing_from_catalog");
                    assertThat(request.getExpansionNumber()).isEqualTo(1);
                    assertThat(remainingNanos).isPositive();
                    return AdaptiveCatalogNetworkSearch.Expansion.expanded(expanded);
                }, settings());

        assertThat(result.getOutcome()).isEqualTo(AdaptiveCatalogNetworkSearch.Outcome.ACCEPTED);
        assertThat(result.getAccepted()).isSameAs(archive.best());
        assertThat(result.getAccepted().getEdges()).singleElement().satisfies(edge ->
                assertThat(edge.getDiameter()).isEqualTo(50));
        assertThat(result.getExpansions()).isEqualTo(1);
        assertThat(result.getRefinementRuns()).isEqualTo(2);
        assertThat(result.getFinalCatalogHash()).isEqualTo(
                expanded.getCatalog().getCatalogHash());
        assertThat(calls).hasValue(1);
    }

    @Test
    void exhaustedIncompleteCatalogNeverBecomesNoRoute() {
        AdaptiveCatalogNetworkSearch.Stage initial = stage(
                "catalog-1", List.of(option(100)), false);
        ConflictStore conflicts = new ConflictStore();

        AdaptiveCatalogNetworkSearch.Result result = search.solve(initial, conflicts,
                new AcceptedSolutionArchive(3), (current, request, remainingNanos) ->
                        AdaptiveCatalogNetworkSearch.Expansion.exhausted("window_budget_exhausted"),
                settings());

        assertThat(result.getOutcome()).isEqualTo(
                AdaptiveCatalogNetworkSearch.Outcome.CATALOG_INCOMPLETE);
        assertThat(result.getReason()).isEqualTo("window_budget_exhausted");
        assertThat(result.getAccepted()).isNull();
        assertThat(conflicts.size()).isZero();
    }

    @Test
    void expansionCannotDropAnOldRepresentableDiameter() {
        AdaptiveCatalogNetworkSearch.Stage initial = stage(
                "catalog-1", List.of(option(100)), false);
        AdaptiveCatalogNetworkSearch.Stage replacement = stage(
                "catalog-2", List.of(option(50)), true);

        AdaptiveCatalogNetworkSearch.Result result = search.solve(initial, new ConflictStore(),
                new AcceptedSolutionArchive(3), (current, request, remainingNanos) ->
                        AdaptiveCatalogNetworkSearch.Expansion.expanded(replacement), settings());

        assertThat(result.getOutcome()).isEqualTo(AdaptiveCatalogNetworkSearch.Outcome.ERROR);
        assertThat(result.getReason()).contains("non_monotonic_catalog_expansion")
                .contains("decision domain is not additive");
        assertThat(result.getExpansions()).isZero();
    }

    @Test
    void expansionCannotReuseAnAssetIdWithChangedMasterSemantics() {
        AdaptiveCatalogNetworkSearch.Stage initial = stage(
                "catalog-1", List.of(option(100)), false, 0);
        AdaptiveCatalogNetworkSearch.Stage changed = stage(
                "catalog-2", List.of(option(100)), true, 1);

        AdaptiveCatalogNetworkSearch.Result result = search.solve(initial, new ConflictStore(),
                new AcceptedSolutionArchive(3), (current, request, remainingNanos) ->
                        AdaptiveCatalogNetworkSearch.Expansion.expanded(changed), settings());

        assertThat(result.getOutcome()).isEqualTo(AdaptiveCatalogNetworkSearch.Outcome.ERROR);
        assertThat(result.getReason()).contains("non_monotonic_catalog_expansion")
                .contains("master asset was removed or changed");
    }

    @Test
    void incompleteLaterSearchStillReturnsTheVerifiedArchiveIncumbent() {
        AcceptedSolutionArchive archive = new AcceptedSolutionArchive(3);
        AdaptiveCatalogNetworkSearch.Stage acceptedStage = stage(
                "catalog-accepted", List.of(option(50)), true);
        AdaptiveCatalogNetworkSearch.Result accepted = search.solve(acceptedStage,
                new ConflictStore(), archive, (current, request, remainingNanos) ->
                        AdaptiveCatalogNetworkSearch.Expansion.exhausted("unused"), settings());
        AdaptiveCatalogNetworkSearch.Stage incompleteStage = stage(
                "catalog-incomplete", List.of(option(100)), false);

        AdaptiveCatalogNetworkSearch.Result incomplete = search.solve(incompleteStage,
                new ConflictStore(), archive, (current, request, remainingNanos) ->
                        AdaptiveCatalogNetworkSearch.Expansion.exhausted("no_more_windows"), settings());

        assertThat(accepted.getOutcome()).isEqualTo(AdaptiveCatalogNetworkSearch.Outcome.ACCEPTED);
        assertThat(incomplete.getOutcome()).isEqualTo(
                AdaptiveCatalogNetworkSearch.Outcome.CATALOG_INCOMPLETE);
        assertThat(incomplete.getAccepted()).isSameAs(archive.best()).isNotNull();
    }

    private AdaptiveCatalogNetworkSearch.Stage stage(String version,
            List<NetworkConstraintProblem.DiameterOption> options, boolean complete) {
        return stage(version, options, complete, 0);
    }

    private AdaptiveCatalogNetworkSearch.Stage stage(String version,
            List<NetworkConstraintProblem.DiameterOption> options, boolean complete,
            long fixedCost) {
        RoutingCatalogSnapshot catalog = new RoutingCatalogSnapshot(
                "source-1", "official", "rules-1", version, List.of(), List.of());
        CatalogBuildResult build = new CatalogBuildResult(catalog,
                Map.of("diameters", (long) options.size()), Map.of("diameter_generation", complete),
                complete ? List.of() : List.of("diameter:50"), List.of());
        NetworkConstraintProblem problem = new NetworkConstraintProblem(List.of(
                new NetworkConstraintProblem.Node("root", true, 0),
                new NetworkConstraintProblem.Node("terminal", false, 1)),
                List.of(new NetworkConstraintProblem.Asset(
                        "pipe", "root", "terminal", fixedCost, options)), List.of());
        CatalogIdentity identity = CatalogIdentity.fromProblem(catalog.getSourceSnapshotHash(),
                catalog.getRuleId(), catalog.getRuleVersion(), "frozen-evaluator-1",
                catalog.getCatalogHash(), problem);
        return new AdaptiveCatalogNetworkSearch.Stage(build, problem, identity,
                master -> assembly(master));
    }

    private CatalogFrozenCandidateAssembler.Assembly assembly(
            CpSatNetworkOptimizer.Result master) {
        int diameter = master.getDiameterMm().get("pipe");
        RouteEdge edge = new RouteEdge("edge", "root", "demand:one", 20,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(20, 0)),
                List.of(), BigDecimal.ONE, diameter);
        FrozenNetworkCandidate candidate = new FrozenNetworkCandidate(
                "candidate-" + diameter, "heatroute", List.of(
                        new RouteNode("root", "new_chamber", new RouteCoordinate(0, 0),
                                true, true, 0, null),
                        new RouteNode("demand:one", "demand_connection",
                                new RouteCoordinate(20, 0), false, false, 0, "connection-one")),
                List.of(edge), List.of(new RouteConnection(
                        "one", "connection-one", BigDecimal.ONE, "connected", null)),
                List.of(), OfficialRunParameters.defaults(), false);
        return CatalogFrozenCandidateAssembler.Assembly.of(
                candidate, Map.of("edge", List.of("pipe")));
    }

    private NetworkConstraintProblem.DiameterOption option(int diameter) {
        return new NetworkConstraintProblem.DiameterOption(diameter, 1, 0);
    }

    private AdaptiveCatalogNetworkSearch.Settings settings() {
        return AdaptiveCatalogNetworkSearch.Settings.bounded(
                30, TimeUnit.SECONDS,
                1, TimeUnit.SECONDS,
                8, TimeUnit.SECONDS,
                1, TimeUnit.SECONDS,
                2, 4, 2026);
    }
}
