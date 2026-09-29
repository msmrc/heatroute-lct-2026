package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
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
import ru.lct.heatroute.domain.optimization.ConflictExplanation;
import ru.lct.heatroute.domain.optimization.ConflictStore;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.optimization.CpSatNetworkRefinement;
import ru.lct.heatroute.domain.optimization.CpSatRuntime;
import ru.lct.heatroute.domain.optimization.NetworkConstraintProblem;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;

class CatalogFrozenNetworkRefinementTest {
    private final OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialRouteGeometryRules geometryRules =
            new OfficialRouteGeometryRules(constraints, new OfficialCrossingGeometry());
    private final FrozenNetworkEvaluator evaluator = new FrozenNetworkEvaluator(
            new OfficialRouteValidator(geometryRules),
            new OfficialObstacleRouter(geometryRules),
            new OfficialNetworkSizer(pipes),
            new OfficialDepthPlanner(new OfficialDepthCrossingExtractor(constraints, pipes),
                    new OfficialDepthOptimizer(pipes, economics),
                    new OfficialDepthProfileValidator(pipes)),
            new OfficialVariantEconomicsCalculator(pipes, economics));
    private final CatalogFrozenNetworkRefinement refinement = new CatalogFrozenNetworkRefinement(
            new CpSatNetworkOptimizer(new CpSatRuntime()), evaluator);

    @Test
    void admitsAndArchivesOnlyTheExactEvaluatorSolution() {
        NetworkConstraintProblem problem = problem(option(50));
        CatalogIdentity identity = identity(problem);
        ConflictStore conflicts = new ConflictStore();
        AcceptedSolutionArchive archive = new AcceptedSolutionArchive(3);

        CatalogFrozenNetworkRefinement.Result result = refinement.solve(
                problem, identity, conflicts,
                master -> assembly(master, straightCoordinates()), archive, settings());

        assertThat(result.getOutcome()).isEqualTo(CpSatNetworkRefinement.Outcome.ACCEPTED);
        assertThat(result.getAccepted()).isSameAs(archive.best());
        assertThat(result.getAccepted().getEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.getFlowTph()).isEqualByComparingTo("1.000");
            assertThat(edge.getDiameter()).isEqualTo(50);
        });
        assertThat(result.getIterations()).isEqualTo(1);
        assertThat(conflicts.size()).isZero();
        assertThat(archive.size()).isEqualTo(1);
    }

    @Test
    void canonicalSizingBlocksOnlyTheCompleteCurrentCatalogAssignment() {
        NetworkConstraintProblem problem = problem(
                List.of(option(50), option(100)),
                List.of(NetworkConstraintProblem.Conflict.ofLiterals(List.of(
                        NetworkConstraintProblem.DecisionLiteral.diameter("pipe", 50, true)),
                        "force_initial_noncanonical_diameter")));
        CatalogIdentity identity = identity(problem);
        ConflictStore conflicts = new ConflictStore();
        AcceptedSolutionArchive archive = new AcceptedSolutionArchive(3);

        CatalogFrozenNetworkRefinement.Result result = refinement.solve(
                problem, identity, conflicts,
                master -> assembly(master, straightCoordinates()), archive, settings());

        assertThat(result.getOutcome()).isEqualTo(
                CpSatNetworkRefinement.Outcome.INFEASIBLE_IN_CATALOG);
        assertThat(result.getIterations()).isEqualTo(2);
        assertThat(archive.size()).isZero();
        assertThat(conflicts.activeFor(identity)).singleElement().satisfies(proof -> {
            assertThat(proof.getType()).isEqualTo("canonical_sizing");
            assertThat(proof.getProofScope()).isEqualTo(
                    ConflictExplanation.ProofScope.CATALOG_SIZING_IMPLICATION);
            assertThat(proof.getLiterals())
                    .filteredOn(literal -> literal.getType()
                            == NetworkConstraintProblem.DecisionLiteral.Type.DIAMETER_SELECTED)
                    .singleElement().satisfies(literal -> {
                        assertThat(literal.getDiameterMm()).isEqualTo(50);
                        assertThat(literal.isExpected()).isFalse();
                    });
            assertThat(proof.getEvidenceReferences())
                    .anyMatch(reference -> reference.startsWith("required_sizing:"));
        });
    }

    @Test
    void canonicalSizingIgnoresAlreadyMatchingEdgesAndCutsOnlyTheMismatch() {
        NetworkConstraintProblem problem = new NetworkConstraintProblem(List.of(
                new NetworkConstraintProblem.Node("root", true, 0),
                new NetworkConstraintProblem.Node("terminal-one", false, 1),
                new NetworkConstraintProblem.Node("terminal-two", false, 1)),
                List.of(
                        new NetworkConstraintProblem.Asset("pipe-one", "root", "terminal-one", 0,
                                List.of(option(50), option(100))),
                        new NetworkConstraintProblem.Asset("pipe-two", "root", "terminal-two", 0,
                                List.of(option(50), option(100)))),
                List.of(
                        NetworkConstraintProblem.Conflict.ofLiterals(List.of(
                                NetworkConstraintProblem.DecisionLiteral.diameter(
                                        "pipe-one", 100, true)),
                                "force_first_canonical_diameter"),
                        NetworkConstraintProblem.Conflict.ofLiterals(List.of(
                                NetworkConstraintProblem.DecisionLiteral.diameter(
                                        "pipe-two", 50, true)),
                                "force_second_noncanonical_diameter")));
        CatalogIdentity identity = identity(problem);
        ConflictStore conflicts = new ConflictStore();

        CatalogFrozenNetworkRefinement.Result result = refinement.solve(
                problem, identity, conflicts, this::twoEdgeAssembly,
                new AcceptedSolutionArchive(3), settings());

        assertThat(result.getOutcome()).isEqualTo(
                CpSatNetworkRefinement.Outcome.INFEASIBLE_IN_CATALOG);
        assertThat(result.getIterations()).isEqualTo(2);
        assertThat(conflicts.activeFor(identity)).singleElement().satisfies(proof -> {
            assertThat(proof.getType()).isEqualTo("canonical_sizing");
            assertThat(proof.getLiterals())
                    .filteredOn(literal -> literal.getType()
                            == NetworkConstraintProblem.DecisionLiteral.Type.DIAMETER_SELECTED)
                    .singleElement().satisfies(literal -> {
                        assertThat(literal.getSubjectId()).isEqualTo("pipe-two");
                        assertThat(literal.getDiameterMm()).isEqualTo(50);
                        assertThat(literal.isExpected()).isFalse();
                    });
        });
    }

    @Test
    void missingCanonicalDiameterRequestsCatalogExpansionWithoutAFalseCut() {
        NetworkConstraintProblem problem = problem(option(100));
        CatalogIdentity identity = identity(problem);
        ConflictStore conflicts = new ConflictStore();

        CatalogFrozenNetworkRefinement.Result result = refinement.solve(
                problem, identity, conflicts,
                master -> assembly(master, straightCoordinates()),
                new AcceptedSolutionArchive(3), settings());

        assertThat(result.getOutcome()).isEqualTo(
                CpSatNetworkRefinement.Outcome.SEARCH_LIMIT_REACHED);
        assertThat(result.getReason()).isEqualTo("canonical_diameter_missing_from_catalog");
        assertThat(result.getIterations()).isEqualTo(1);
        assertThat(conflicts.size()).isZero();
    }

    @Test
    void exactEngineeringRejectionCreatesAnAuditableLocalGeometryNoGood() {
        NetworkConstraintProblem problem = problem(option(50));
        CatalogIdentity identity = identity(problem);
        ConflictStore conflicts = new ConflictStore();

        CatalogFrozenNetworkRefinement.Result result = refinement.solve(
                problem, identity, conflicts,
                master -> assembly(master, List.of(
                        new RouteCoordinate(0, 0),
                        new RouteCoordinate(10, 5),
                        new RouteCoordinate(20, 0))),
                new AcceptedSolutionArchive(3), settings());

        assertThat(result.getOutcome()).isEqualTo(
                CpSatNetworkRefinement.Outcome.INFEASIBLE_IN_CATALOG);
        assertThat(conflicts.activeFor(identity)).isNotEmpty().allSatisfy(proof -> {
            assertThat(proof.getType()).isEqualTo("exact_geometry");
            assertThat(proof.getLiterals()).allSatisfy(literal ->
                    assertThat(literal.getType()).isEqualTo(
                            NetworkConstraintProblem.DecisionLiteral.Type.ASSET_SELECTED));
            assertThat(proof.getEvidenceReferences())
                    .anyMatch(reference -> reference.startsWith("validation:"));
        });
        assertThat(conflicts.activeFor(identity))
                .extracting(ConflictExplanation::getReason)
                .containsExactlyInAnyOrder(
                        "ROUTE_DEFLECTION_EXCEEDED", "EXPERT_ROUTE_BEND_ANGLE_INVALID");
    }

    private CatalogFrozenCandidateAssembler.Assembly assembly(
            CpSatNetworkOptimizer.Result master, List<RouteCoordinate> coordinates) {
        FrozenNetworkCandidate candidate = candidate(master, coordinates);
        return CatalogFrozenCandidateAssembler.Assembly.of(
                candidate, Map.of("edge", List.of("pipe")));
    }

    private CatalogFrozenCandidateAssembler.Assembly twoEdgeAssembly(
            CpSatNetworkOptimizer.Result master) {
        RouteEdge first = new RouteEdge("edge-one", "root", "demand:one", 20,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(20, 0)), List.of(),
                BigDecimal.valueOf(master.getFlowUnits().get("pipe-one")),
                master.getDiameterMm().get("pipe-one"));
        RouteEdge second = new RouteEdge("edge-two", "root", "demand:two", 20,
                List.of(new RouteCoordinate(0, 0), new RouteCoordinate(0, 20)), List.of(),
                BigDecimal.valueOf(master.getFlowUnits().get("pipe-two")),
                master.getDiameterMm().get("pipe-two"));
        FrozenNetworkCandidate candidate = new FrozenNetworkCandidate(
                "candidate-two-edges", "nextgen", List.of(
                        new RouteNode("root", "new_branch_chamber", new RouteCoordinate(0, 0),
                                true, true, 0, null),
                        new RouteNode("demand:one", "demand_connection",
                                new RouteCoordinate(20, 0), false, false, 0, "connection-one"),
                        new RouteNode("demand:two", "demand_connection",
                                new RouteCoordinate(0, 20), false, false, 0, "connection-two")),
                List.of(first, second), List.of(
                        new RouteConnection("one", "connection-one", BigDecimal.ONE,
                                "connected", null),
                        new RouteConnection("two", "connection-two", BigDecimal.ONE,
                                "connected", null)),
                List.of(), OfficialRunParameters.defaults(), false);
        return CatalogFrozenCandidateAssembler.Assembly.of(candidate, Map.of(
                "edge-one", List.of("pipe-one"),
                "edge-two", List.of("pipe-two")));
    }

    private FrozenNetworkCandidate candidate(CpSatNetworkOptimizer.Result master,
            List<RouteCoordinate> coordinates) {
        BigDecimal flow = BigDecimal.valueOf(master.getFlowUnits().get("pipe"), 0);
        int diameter = master.getDiameterMm().get("pipe");
        double length = 0.0;
        for (int index = 1; index < coordinates.size(); index++) {
            RouteCoordinate left = coordinates.get(index - 1);
            RouteCoordinate right = coordinates.get(index);
            length += Math.hypot(right.getXM().doubleValue() - left.getXM().doubleValue(),
                    right.getYM().doubleValue() - left.getYM().doubleValue());
        }
        RouteEdge edge = new RouteEdge("edge", "root", "demand:one", length,
                coordinates, List.of(), flow, diameter);
        return new FrozenNetworkCandidate("candidate", "nextgen", List.of(
                new RouteNode("root", "new_chamber", new RouteCoordinate(0, 0),
                        true, true, 0, null),
                new RouteNode("demand:one", "demand_connection", new RouteCoordinate(20, 0),
                        false, false, 0, "connection-one")),
                List.of(edge), List.of(new RouteConnection(
                        "one", "connection-one", BigDecimal.ONE, "connected", null)),
                List.of(), OfficialRunParameters.defaults(), false);
    }

    private List<RouteCoordinate> straightCoordinates() {
        return List.of(new RouteCoordinate(0, 0), new RouteCoordinate(20, 0));
    }

    private NetworkConstraintProblem problem(NetworkConstraintProblem.DiameterOption option) {
        return problem(List.of(option), List.of());
    }

    private NetworkConstraintProblem problem(
            List<NetworkConstraintProblem.DiameterOption> options,
            List<NetworkConstraintProblem.Conflict> conflicts) {
        return new NetworkConstraintProblem(List.of(
                new NetworkConstraintProblem.Node("root", true, 0),
                new NetworkConstraintProblem.Node("terminal", false, 1)),
                List.of(new NetworkConstraintProblem.Asset(
                        "pipe", "root", "terminal", 0, options)), conflicts);
    }

    private NetworkConstraintProblem.DiameterOption option(int diameter) {
        return new NetworkConstraintProblem.DiameterOption(diameter, 1, 0);
    }

    private CatalogIdentity identity(NetworkConstraintProblem problem) {
        return CatalogIdentity.fromProblem(
                "source-1", "official", "rules-1", "frozen-evaluator-1", "catalog-1", problem);
    }

    private CpSatNetworkRefinement.Settings settings() {
        return CpSatNetworkRefinement.Settings.bounded(
                30, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, 4, 2026);
    }
}
