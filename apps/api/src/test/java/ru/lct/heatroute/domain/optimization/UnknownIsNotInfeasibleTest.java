package ru.lct.heatroute.domain.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UnknownIsNotInfeasibleTest {
    private final CpSatNetworkRefinement<String> refinement = new CpSatNetworkRefinement<>(
            new CpSatNetworkOptimizer(new CpSatRuntime()));

    @Test
    void unknownStopsHonestlyWithoutCreatingPermanentCutOrNoRoute() {
        NetworkConstraintProblem problem = twoRoots();
        ConflictStore store = new ConflictStore();
        AtomicInteger evaluations = new AtomicInteger();

        CpSatNetworkRefinement.Result<String> result = refinement.solve(problem, identity(problem), store,
                this::selectedRoot, candidate -> {
                    evaluations.incrementAndGet();
                    return CpSatNetworkRefinement.Assessment.unknown("bounded_router_incomplete");
                }, settings());

        assertThat(result.getOutcome()).isEqualTo(
                CpSatNetworkRefinement.Outcome.SEARCH_LIMIT_REACHED);
        assertThat(result.getReason()).isEqualTo("bounded_router_incomplete");
        assertThat(result.getIterations()).isEqualTo(1);
        assertThat(evaluations).hasValue(1);
        assertThat(store.size()).isZero();
    }

    @Test
    void provenConflictIsStoredAndNextMasterCandidateCanBeAccepted() {
        NetworkConstraintProblem problem = twoRoots();
        CatalogIdentity identity = identity(problem);
        ConflictStore store = new ConflictStore();

        CpSatNetworkRefinement.Result<String> result = refinement.solve(problem, identity, store,
                this::selectedRoot, candidate -> candidate.equals("root-a")
                        ? CpSatNetworkRefinement.Assessment.rejected(rootProof("root-a", identity))
                        : CpSatNetworkRefinement.Assessment.accepted(candidate), settings());

        assertThat(result.getOutcome()).isEqualTo(CpSatNetworkRefinement.Outcome.ACCEPTED);
        assertThat(result.getAccepted()).isEqualTo("root-b");
        assertThat(result.getIterations()).isEqualTo(2);
        assertThat(result.getMasterStatus()).isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void invalidProofScopeAndDuplicateProofCannotCreateAnInfiniteLoop() {
        NetworkConstraintProblem problem = twoRoots();
        CatalogIdentity identity = identity(problem);
        ConflictExplanation wrongSource = new ConflictExplanation(
                "geometry", "official", "rules-1", "another-source", "catalog-1",
                List.of(NetworkConstraintProblem.DecisionLiteral.root("root-a", true)),
                List.of("event:1"), "wrong_source", "checker-1",
                ConflictExplanation.ProofScope.STABLE_DECISION_SET);
        ConflictStore invalidStore = new ConflictStore();

        CpSatNetworkRefinement.Result<String> invalid = refinement.solve(problem, identity, invalidStore,
                this::selectedRoot, candidate -> CpSatNetworkRefinement.Assessment.rejected(wrongSource),
                settings());

        assertThat(invalid.getOutcome()).isEqualTo(CpSatNetworkRefinement.Outcome.ERROR);
        assertThat(invalid.getReason()).isEqualTo("invalid_conflict_proof_scope");
        assertThat(invalidStore.size()).isZero();

        ConflictStore duplicateStore = new ConflictStore();
        ConflictExplanation repeated = rootProof("root-a", identity);
        CpSatNetworkRefinement.Result<String> stalled = refinement.solve(problem, identity, duplicateStore,
                this::selectedRoot, candidate -> CpSatNetworkRefinement.Assessment.rejected(repeated),
                settings());

        assertThat(stalled.getOutcome()).isEqualTo(CpSatNetworkRefinement.Outcome.STALLED);
        assertThat(stalled.getReason()).isEqualTo("duplicate_conflict_proof");
        assertThat(stalled.getIterations()).isEqualTo(2);
        assertThat(duplicateStore.size()).isEqualTo(1);
    }

    @Test
    void proofCapacityFailureIsTechnicalErrorAndBatchInsertionIsAtomic() {
        NetworkConstraintProblem problem = twoRoots();
        CatalogIdentity identity = identity(problem);
        ConflictStore store = new ConflictStore(1);

        CpSatNetworkRefinement.Result<String> result = refinement.solve(problem, identity, store,
                this::selectedRoot, candidate -> CpSatNetworkRefinement.Assessment.rejected(
                        rootProof(candidate, identity)), settings());

        assertThat(result.getOutcome()).isEqualTo(CpSatNetworkRefinement.Outcome.ERROR);
        assertThat(result.getReason()).isEqualTo("conflict_store_capacity");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void interruptedWorkerCancelsBeforeAnotherMasterSolve() {
        NetworkConstraintProblem problem = twoRoots();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> refinement.solve(problem, identity(problem), new ConflictStore(),
                    this::selectedRoot, CpSatNetworkRefinement.Assessment::accepted, settings()))
                    .isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    private String selectedRoot(CpSatNetworkOptimizer.Result result) {
        return result.getSelectedRoots().stream().findFirst().orElseThrow();
    }

    private ConflictExplanation rootProof(String rootId, CatalogIdentity identity) {
        return new ConflictExplanation("root_policy", identity.getRuleId(), identity.getRuleVersion(),
                identity.getSourceSnapshotHash(), identity.getCatalogHash(),
                List.of(NetworkConstraintProblem.DecisionLiteral.root(rootId, true)),
                List.of("root:" + rootId), "root_rejected", identity.getCheckerVersion(),
                ConflictExplanation.ProofScope.STABLE_DECISION_SET);
    }

    private CatalogIdentity identity(NetworkConstraintProblem problem) {
        return CatalogIdentity.fromProblem(
                "source-1", "official", "rules-1", "checker-1", "catalog-1", problem);
    }

    private CpSatNetworkRefinement.Settings settings() {
        return CpSatNetworkRefinement.Settings.bounded(
                120, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, 5, 2026);
    }

    private NetworkConstraintProblem twoRoots() {
        return new NetworkConstraintProblem(
                List.of(
                        new NetworkConstraintProblem.Node("root-a", true, 0),
                        new NetworkConstraintProblem.Node("root-b", true, 0),
                        new NetworkConstraintProblem.Node("terminal", false, 1)),
                List.of(
                        asset("from-a", "root-a", "terminal", 1),
                        asset("from-b", "root-b", "terminal", 5)),
                List.of());
    }

    private NetworkConstraintProblem.Asset asset(
            String id, String from, String to, long cost) {
        return new NetworkConstraintProblem.Asset(id, from, to, cost,
                List.of(new NetworkConstraintProblem.DiameterOption(50, 1, 0)));
    }
}
