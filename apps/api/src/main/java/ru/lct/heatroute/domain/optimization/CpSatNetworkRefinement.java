package ru.lct.heatroute.domain.optimization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * Ограниченный solve/check/add-cut цикл конечного сетевого каталога.
 * UNKNOWN и техническая ошибка завершают цикл без постоянного proof cut.
 */
public final class CpSatNetworkRefinement<C> {
    private final CpSatNetworkOptimizer optimizer;

    public CpSatNetworkRefinement(CpSatNetworkOptimizer optimizer) {
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
    }

    public Result<C> solve(NetworkConstraintProblem problem, CatalogIdentity catalogIdentity,
            ConflictStore conflictStore, CandidateAssembler<C> assembler, ExactEvaluator<C> evaluator,
            Settings settings) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(catalogIdentity, "catalogIdentity");
        Objects.requireNonNull(conflictStore, "conflictStore");
        Objects.requireNonNull(assembler, "assembler");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(settings, "settings");
        long started = System.nanoTime();
        long deadline = saturatingAdd(started, settings.timeBudgetNanos);
        for (int iteration = 1; iteration <= settings.maxIterations; iteration++) {
            ensureActive();
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= settings.evaluationReserveNanos) {
                return Result.ended(Outcome.SEARCH_LIMIT_REACHED, iteration - 1, "refinement_deadline");
            }
            double remainingSeconds = (remainingNanos - settings.evaluationReserveNanos)
                    / 1_000_000_000.0;
            CpSatNetworkOptimizer.Result master;
            try {
                master = optimizer.solveFirstFeasible(problem, conflictStore,
                        catalogIdentity, remainingSeconds, settings.randomSeed);
            } catch (CancellationException exception) {
                throw exception;
            } catch (RuntimeException | LinkageError exception) {
                return Result.ended(Outcome.ERROR, iteration,
                        "master_failure:" + exception.getClass().getSimpleName());
            }
            if (master.getStatus() == CpSatNetworkOptimizer.Status.UNKNOWN) {
                return Result.ended(Outcome.SEARCH_LIMIT_REACHED, iteration, "master_search_limit");
            }
            if (master.getStatus() == CpSatNetworkOptimizer.Status.INFEASIBLE) {
                return Result.ended(Outcome.INFEASIBLE_IN_CATALOG, iteration, "proven_master_infeasible");
            }
            if (System.nanoTime() >= deadline) {
                return Result.ended(Outcome.SEARCH_LIMIT_REACHED, iteration, "deadline_after_master");
            }
            C candidate;
            Assessment<C> assessment;
            try {
                candidate = Objects.requireNonNull(assembler.assemble(master), "assembled candidate");
                ensureActive();
                assessment = Objects.requireNonNull(evaluator.evaluate(candidate), "assessment");
            } catch (CancellationException exception) {
                throw exception;
            } catch (RuntimeException | LinkageError exception) {
                return Result.ended(Outcome.ERROR, iteration,
                        "candidate_evaluation_failure:" + exception.getClass().getSimpleName());
            }
            switch (assessment.outcome) {
                case ACCEPTED:
                    return Result.accepted(assessment.accepted, iteration, master.getStatus());
                case PROVEN_REJECTED:
                case CANONICAL_SIZING_REQUIREMENT:
                    AddResult added;
                    try {
                        added = addProofs(assessment.proofs, catalogIdentity, conflictStore);
                    } catch (ConflictStoreCapacityExceededException exception) {
                        return Result.ended(Outcome.ERROR, iteration, "conflict_store_capacity");
                    }
                    if (added == AddResult.INVALID_SCOPE) {
                        return Result.ended(Outcome.ERROR, iteration, "invalid_conflict_proof_scope");
                    }
                    if (added == AddResult.NO_NEW_PROOF) {
                        return Result.ended(Outcome.STALLED, iteration, "duplicate_conflict_proof");
                    }
                    break;
                case UNKNOWN:
                    return Result.ended(Outcome.SEARCH_LIMIT_REACHED, iteration, assessment.reason);
                case ERROR:
                    return Result.ended(Outcome.ERROR, iteration, assessment.reason);
                default:
                    throw new IllegalStateException("Unsupported refinement assessment: " + assessment.outcome);
            }
        }
        return Result.ended(Outcome.SEARCH_LIMIT_REACHED, settings.maxIterations, "iteration_limit");
    }

    private AddResult addProofs(List<ConflictExplanation> proofs, CatalogIdentity identity,
            ConflictStore store) {
        if (proofs.isEmpty()) return AddResult.INVALID_SCOPE;
        for (ConflictExplanation proof : proofs) {
            if (!proof.appliesTo(identity)) return AddResult.INVALID_SCOPE;
        }
        return store.addAll(proofs) > 0 ? AddResult.ADDED : AddResult.NO_NEW_PROOF;
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Network refinement cancelled");
        }
    }

    public interface CandidateAssembler<C> {
        C assemble(CpSatNetworkOptimizer.Result masterResult);
    }

    public interface ExactEvaluator<C> {
        Assessment<C> evaluate(C candidate);
    }

    public enum Outcome {
        ACCEPTED,
        INFEASIBLE_IN_CATALOG,
        SEARCH_LIMIT_REACHED,
        STALLED,
        ERROR
    }

    public static final class Settings {
        private final long timeBudgetNanos;
        private final long evaluationReserveNanos;
        private final int maxIterations;
        private final int randomSeed;

        private Settings(long timeBudgetNanos, long evaluationReserveNanos,
                int maxIterations, int randomSeed) {
            if (timeBudgetNanos <= 0L || evaluationReserveNanos < 0L
                    || evaluationReserveNanos >= timeBudgetNanos
                    || maxIterations <= 0 || randomSeed < 0) {
                throw new IllegalArgumentException("Positive budget/iterations and non-negative seed required");
            }
            this.timeBudgetNanos = timeBudgetNanos;
            this.evaluationReserveNanos = evaluationReserveNanos;
            this.maxIterations = maxIterations;
            this.randomSeed = randomSeed;
        }

        public static Settings bounded(long time, TimeUnit unit, long evaluationReserve,
                TimeUnit reserveUnit, int maxIterations, int randomSeed) {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(reserveUnit, "reserveUnit");
            return new Settings(unit.toNanos(time), reserveUnit.toNanos(evaluationReserve),
                    maxIterations, randomSeed);
        }
    }

    /** Результат точной оценки; proof обязателен только для доказанного отказа/sizing feedback. */
    public static final class Assessment<C> {
        private final AssessmentOutcome outcome;
        private final C accepted;
        private final List<ConflictExplanation> proofs;
        private final String reason;

        private Assessment(AssessmentOutcome outcome, C accepted,
                Collection<ConflictExplanation> proofs, String reason) {
            this.outcome = outcome;
            this.accepted = accepted;
            this.proofs = proofs == null ? List.of() : List.copyOf(proofs);
            this.reason = reason;
        }

        public static <C> Assessment<C> accepted(C candidate) {
            return new Assessment<>(AssessmentOutcome.ACCEPTED,
                    Objects.requireNonNull(candidate, "candidate"), null, "accepted");
        }

        public static <C> Assessment<C> rejected(ConflictExplanation proof) {
            return rejected(List.of(proof));
        }

        public static <C> Assessment<C> rejected(Collection<ConflictExplanation> proofs) {
            return proofAssessment(AssessmentOutcome.PROVEN_REJECTED, proofs, "proven_rejected");
        }

        public static <C> Assessment<C> sizingRequired(Collection<ConflictExplanation> proofs) {
            return proofAssessment(AssessmentOutcome.CANONICAL_SIZING_REQUIREMENT,
                    proofs, "canonical_sizing_required");
        }

        private static <C> Assessment<C> proofAssessment(AssessmentOutcome outcome,
                Collection<ConflictExplanation> proofs, String reason) {
            if (proofs == null || proofs.isEmpty() || proofs.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("At least one non-null proof is required");
            }
            return new Assessment<>(outcome, null, new ArrayList<>(proofs), reason);
        }

        public static <C> Assessment<C> unknown(String reason) {
            return new Assessment<>(AssessmentOutcome.UNKNOWN, null, null, required(reason));
        }

        public static <C> Assessment<C> error(String reason) {
            return new Assessment<>(AssessmentOutcome.ERROR, null, null, required(reason));
        }
    }

    public static final class Result<C> {
        private final Outcome outcome;
        private final C accepted;
        private final int iterations;
        private final String reason;
        private final CpSatNetworkOptimizer.Status masterStatus;

        private Result(Outcome outcome, C accepted, int iterations,
                String reason, CpSatNetworkOptimizer.Status masterStatus) {
            this.outcome = outcome;
            this.accepted = accepted;
            this.iterations = iterations;
            this.reason = reason;
            this.masterStatus = masterStatus;
        }

        private static <C> Result<C> accepted(C candidate, int iterations,
                CpSatNetworkOptimizer.Status masterStatus) {
            return new Result<>(Outcome.ACCEPTED, candidate, iterations, "accepted", masterStatus);
        }

        private static <C> Result<C> ended(Outcome outcome, int iterations, String reason) {
            return new Result<>(outcome, null, iterations, reason, null);
        }

        public Outcome getOutcome() { return outcome; }
        public C getAccepted() { return accepted; }
        public int getIterations() { return iterations; }
        public String getReason() { return reason; }
        public CpSatNetworkOptimizer.Status getMasterStatus() { return masterStatus; }
    }

    private enum AssessmentOutcome {
        ACCEPTED,
        CANONICAL_SIZING_REQUIREMENT,
        PROVEN_REJECTED,
        UNKNOWN,
        ERROR
    }

    private enum AddResult { ADDED, NO_NEW_PROOF, INVALID_SCOPE }

    private static String required(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Reason is required");
        return value;
    }
}
