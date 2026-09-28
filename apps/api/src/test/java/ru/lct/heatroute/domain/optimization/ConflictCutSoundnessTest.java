package ru.lct.heatroute.domain.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictCutSoundnessTest {
    private final CpSatNetworkOptimizer optimizer = new CpSatNetworkOptimizer(new CpSatRuntime());

    @Test
    void negativeAssetLiteralBlocksOnlyTheProvenFullAssignment() {
        NetworkConstraintProblem.Conflict conflict = NetworkConstraintProblem.Conflict.ofLiterals(List.of(
                NetworkConstraintProblem.DecisionLiteral.asset("a", true),
                NetworkConstraintProblem.DecisionLiteral.asset("b", false)), "fixed_assignment");
        NetworkConstraintProblem problem = new NetworkConstraintProblem(
                List.of(node("root", true, 0), node("t1", false, 1), node("t2", false, 1)),
                List.of(
                        asset("a", "root", "t1", 1),
                        asset("a-alt", "root", "t1", 6),
                        asset("b", "root", "t2", 6),
                        asset("b-alt", "root", "t2", 1)),
                List.of(conflict));

        CpSatNetworkOptimizer.Result result = optimizer.solve(problem, 5.0, 2026);

        assertThat(result.getStatus()).isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
        assertThat(result.getObjectiveUnits()).isEqualTo(7L);
        assertThat(result.getSelectedAssets().contains("a")
                && !result.getSelectedAssets().contains("b")).isFalse();
    }

    @Test
    void rootAndDiameterLiteralsUseTheirActualBooleanVariables() {
        NetworkConstraintProblem.Conflict rootConflict = NetworkConstraintProblem.Conflict.ofLiterals(
                List.of(NetworkConstraintProblem.DecisionLiteral.root("root-b", true)), "root_b_forbidden");
        NetworkConstraintProblem rootProblem = new NetworkConstraintProblem(
                List.of(node("root-a", true, 0), node("root-b", true, 0), node("terminal", false, 1)),
                List.of(asset("from-a", "root-a", "terminal", 8),
                        asset("from-b", "root-b", "terminal", 1)),
                List.of(rootConflict));

        CpSatNetworkOptimizer.Result rootResult = optimizer.solve(rootProblem, 5.0, 2026);

        assertThat(rootResult.getSelectedRoots()).containsExactly("root-a");
        assertThat(rootResult.getSelectedAssets()).containsExactly("from-a");

        NetworkConstraintProblem.Asset pipe = new NetworkConstraintProblem.Asset(
                "pipe", "root", "terminal", 1,
                List.of(option(50, 1, 0), option(100, 2, 5)));
        NetworkConstraintProblem.Conflict diameterConflict = NetworkConstraintProblem.Conflict.ofLiterals(
                List.of(NetworkConstraintProblem.DecisionLiteral.diameter("pipe", 50, true)),
                "diameter_50_forbidden");
        NetworkConstraintProblem diameterProblem = new NetworkConstraintProblem(
                List.of(node("root", true, 0), node("terminal", false, 1)),
                List.of(pipe), List.of(diameterConflict));

        CpSatNetworkOptimizer.Result diameterResult = optimizer.solve(diameterProblem, 5.0, 2026);

        assertThat(diameterResult.getDiameterMm()).containsEntry("pipe", 100);
        assertThat(diameterResult.getObjectiveUnits()).isEqualTo(6L);
    }

    @Test
    void catalogExpansionRetainsStableProofButInvalidatesFullAssignmentNoGood() {
        ConflictStore store = new ConflictStore();
        ConflictExplanation stable = explanation("catalog-1",
                List.of(NetworkConstraintProblem.DecisionLiteral.asset("a", true),
                        NetworkConstraintProblem.DecisionLiteral.asset("b", true)),
                ConflictExplanation.ProofScope.STABLE_DECISION_SET, "pair_intersection");
        ConflictExplanation full = explanation("catalog-1",
                List.of(NetworkConstraintProblem.DecisionLiteral.asset("a", true),
                        NetworkConstraintProblem.DecisionLiteral.asset("b", false)),
                ConflictExplanation.ProofScope.FULL_CATALOG_ASSIGNMENT, "old_full_assignment");
        assertThat(store.add(stable)).isTrue();
        assertThat(store.add(stable)).isFalse();
        assertThat(store.add(full)).isTrue();

        CatalogIdentity original = identity("source-1", "rules-1", "catalog-1", "a", "b");
        CatalogIdentity sameHashDifferentDecisions =
                identity("source-1", "rules-1", "catalog-1", "a", "b", "c");
        CatalogIdentity expanded = identity("source-1", "rules-1", "catalog-2", "a", "b", "c");
        CatalogIdentity splitAsset = identity("source-1", "rules-1", "catalog-3", "a-part-1", "b", "c");

        assertThat(store.activeFor(original)).containsExactly(stable, full);
        assertThat(store.activeFor(sameHashDifferentDecisions)).containsExactly(stable);
        assertThat(store.activeFor(expanded)).containsExactly(stable);
        assertThat(store.activeFor(splitAsset)).isEmpty();
        assertThat(store.activeFor(identity("source-2", "rules-1", "catalog-2", "a", "b"))).isEmpty();
        assertThat(store.activeFor(identity("source-1", "rules-2", "catalog-2", "a", "b"))).isEmpty();
        assertThat(store.activeFor(new CatalogIdentity("source-1", "official-rules", "rules-1",
                "checker-2", "catalog-2", List.of(
                        NetworkConstraintProblem.DecisionLiteral.asset("a", true).variableKey(),
                        NetworkConstraintProblem.DecisionLiteral.asset("b", true).variableKey())))).isEmpty();
    }

    @Test
    void contradictoryOrDuplicateLiteralsAreRejectedInsteadOfCreatingUnsoundCuts() {
        assertThatThrownBy(() -> NetworkConstraintProblem.Conflict.ofLiterals(List.of(
                NetworkConstraintProblem.DecisionLiteral.asset("a", true),
                NetworkConstraintProblem.DecisionLiteral.asset("a", false)), "contradiction"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Contradictory");
        assertThatThrownBy(() -> NetworkConstraintProblem.Conflict.ofLiterals(List.of(
                NetworkConstraintProblem.DecisionLiteral.asset("a", true),
                NetworkConstraintProblem.DecisionLiteral.asset("a", true)), "duplicate"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void activeVersionedProofsAreAppliedByTheNetworkOptimizer() {
        NetworkConstraintProblem problem = new NetworkConstraintProblem(
                List.of(node("root-a", true, 0), node("root-b", true, 0), node("terminal", false, 1)),
                List.of(asset("from-a", "root-a", "terminal", 8),
                        asset("from-b", "root-b", "terminal", 1)), List.of());
        CatalogIdentity identity = CatalogIdentity.fromProblem(
                "source-1", "official-rules", "rules-1", "checker-1", "catalog-1", problem);
        ConflictStore store = new ConflictStore();
        store.add(explanation("catalog-1",
                List.of(NetworkConstraintProblem.DecisionLiteral.root("root-b", true)),
                ConflictExplanation.ProofScope.STABLE_DECISION_SET, "root_policy"));

        CpSatNetworkOptimizer.Result result = optimizer.solve(problem, store, identity, 5.0, 2026);

        assertThat(result.getStatus()).isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
        assertThat(result.getSelectedRoots()).containsExactly("root-a");
        assertThat(result.getSelectedAssets()).containsExactly("from-a");
    }

    @Test
    void proofStoreHasAnExplicitMemoryBoundWithoutDroppingExistingCuts() {
        ConflictStore store = new ConflictStore(1);
        ConflictExplanation first = explanation("catalog-1",
                List.of(NetworkConstraintProblem.DecisionLiteral.asset("a", true)),
                ConflictExplanation.ProofScope.STABLE_DECISION_SET, "first");
        ConflictExplanation second = explanation("catalog-1",
                List.of(NetworkConstraintProblem.DecisionLiteral.asset("b", true)),
                ConflictExplanation.ProofScope.STABLE_DECISION_SET, "second");
        store.add(first);

        assertThatThrownBy(() -> store.add(second))
                .isInstanceOf(ConflictStoreCapacityExceededException.class)
                .hasMessageContaining("capacity exceeded");
        assertThat(store.size()).isEqualTo(1);
    }

    private ConflictExplanation explanation(String catalogHash,
            List<NetworkConstraintProblem.DecisionLiteral> literals,
            ConflictExplanation.ProofScope scope, String reason) {
        return new ConflictExplanation("geometry", "official-rules", "rules-1", "source-1",
                catalogHash, literals, List.of("geometry:event:1"), reason, "checker-1", scope);
    }

    private CatalogIdentity identity(String source, String rules, String catalog, String... assets) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (String asset : assets) keys.add(NetworkConstraintProblem.DecisionLiteral.asset(asset, true).variableKey());
        return new CatalogIdentity(source, "official-rules", rules, "checker-1", catalog, keys);
    }

    private NetworkConstraintProblem.Node node(String id, boolean root, long demand) {
        return new NetworkConstraintProblem.Node(id, root, demand);
    }

    private NetworkConstraintProblem.Asset asset(String id, String from, String to, long cost) {
        return new NetworkConstraintProblem.Asset(id, from, to, cost, List.of(option(50, 1, 0)));
    }

    private NetworkConstraintProblem.DiameterOption option(int diameter, long capacity, long cost) {
        return new NetworkConstraintProblem.DiameterOption(diameter, capacity, cost);
    }
}
