package ru.lct.heatroute.domain.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalDiameterFeedbackTest {
    private final CpSatNetworkOptimizer optimizer = new CpSatNetworkOptimizer(new CpSatRuntime());

    @Test
    void topologyScopeForcesCanonicalDiameterWithoutRejectingTheTopology() {
        NetworkConstraintProblem problem = problem(false);
        CatalogIdentity identity = identity("catalog-1", problem);
        ConflictExplanation implication = implication(identity);
        ConflictStore store = new ConflictStore();
        store.add(implication);

        CpSatNetworkOptimizer.Result baseline = optimizer.solve(problem, 5.0, 2026);
        CpSatNetworkOptimizer.Result refined = optimizer.solve(problem, store, identity, 5.0, 2026);

        assertThat(baseline.getSelectedAssets()).containsExactly("pipe");
        assertThat(baseline.getDiameterMm()).containsEntry("pipe", 100);
        assertThat(refined.getSelectedAssets()).containsExactly("pipe");
        assertThat(refined.getDiameterMm()).containsEntry("pipe", 50);
        assertThat(refined.getObjectiveUnits()).isEqualTo(5L);
    }

    @Test
    void sizingImplicationDoesNotCrossCatalogOrExpandedTopologyScope() {
        NetworkConstraintProblem original = problem(false);
        CatalogIdentity originalIdentity = identity("catalog-1", original);
        ConflictExplanation implication = implication(originalIdentity);
        NetworkConstraintProblem expanded = problem(true);

        assertThat(implication.appliesTo(originalIdentity)).isTrue();
        assertThat(implication.appliesTo(identity("catalog-2", original))).isFalse();
        assertThat(implication.appliesTo(identity("catalog-1", expanded))).isFalse();
    }

    @Test
    void sizingScopeCannotBeUsedToForbidTheCanonicalDiameter() {
        CatalogIdentity identity = identity("catalog-1", problem(false));

        assertThatThrownBy(() -> new ConflictExplanation("canonical_sizing",
                identity.getRuleId(), identity.getRuleVersion(), identity.getSourceSnapshotHash(),
                identity.getCatalogHash(), List.of(
                        NetworkConstraintProblem.DecisionLiteral.root("root", true),
                        NetworkConstraintProblem.DecisionLiteral.asset("pipe", true),
                        NetworkConstraintProblem.DecisionLiteral.asset("alternative", false),
                        NetworkConstraintProblem.DecisionLiteral.diameter("pipe", 50, true)),
                List.of("required-sizing:pipe:50"), "wrong_implication",
                identity.getCheckerVersion(),
                ConflictExplanation.ProofScope.CATALOG_SIZING_IMPLICATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("false diameter literal");
    }

    private ConflictExplanation implication(CatalogIdentity identity) {
        return new ConflictExplanation("canonical_sizing", identity.getRuleId(),
                identity.getRuleVersion(), identity.getSourceSnapshotHash(), identity.getCatalogHash(),
                List.of(
                        NetworkConstraintProblem.DecisionLiteral.root("root", true),
                        NetworkConstraintProblem.DecisionLiteral.asset("pipe", true),
                        NetworkConstraintProblem.DecisionLiteral.asset("alternative", false),
                        NetworkConstraintProblem.DecisionLiteral.diameter("pipe", 50, false)),
                List.of("required-sizing:pipe:50"), "canonical_sizing_required",
                identity.getCheckerVersion(),
                ConflictExplanation.ProofScope.CATALOG_SIZING_IMPLICATION);
    }

    private CatalogIdentity identity(String catalogHash, NetworkConstraintProblem problem) {
        return CatalogIdentity.fromProblem("source-1", "official", "rules-1",
                "checker-1", catalogHash, problem);
    }

    private NetworkConstraintProblem problem(boolean expanded) {
        List<NetworkConstraintProblem.Asset> assets = new ArrayList<>();
        assets.add(new NetworkConstraintProblem.Asset("pipe", "root", "terminal", 0,
                List.of(option(50, 5), option(100, 0))));
        assets.add(new NetworkConstraintProblem.Asset("alternative", "root", "terminal", 20,
                List.of(option(50, 0))));
        if (expanded) {
            assets.add(new NetworkConstraintProblem.Asset("new-alternative", "root", "terminal", 30,
                    List.of(option(50, 0))));
        }
        return new NetworkConstraintProblem(List.of(
                new NetworkConstraintProblem.Node("root", true, 0),
                new NetworkConstraintProblem.Node("terminal", false, 1)), assets, List.of());
    }

    private NetworkConstraintProblem.DiameterOption option(int diameter, long cost) {
        return new NetworkConstraintProblem.DiameterOption(diameter, 1, cost);
    }
}
