package ru.lct.heatroute.domain.optimization;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Решает совместный выбор альтернатив и добавляет только доказанные no-good conflicts.
 * Exact evaluator остаётся владельцем допуска; UNKNOWN завершает refinement без ложного cut.
 */
public final class CpSatChoiceOptimizer {
    private static final double MIN_SOLVE_SECONDS = 0.001;
    private final CpSatRuntime runtime;

    public CpSatChoiceOptimizer(CpSatRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public Result solve(DiscreteChoiceProblem problem, Evaluator evaluator,
            double timeLimitSeconds, int randomSeed) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(evaluator, "evaluator");
        if (!Double.isFinite(timeLimitSeconds) || timeLimitSeconds <= 0.0 || randomSeed < 0) {
            throw new IllegalArgumentException("Finite positive time and non-negative seed required");
        }
        CpModel model = runtime.newModel();
        Map<DiscreteChoiceProblem.Choice, BoolVar> variables = buildVariables(problem, model);
        addConflicts(model, variables, problem.getConflicts());
        addObjective(problem, model, variables);
        long deadline = System.nanoTime() + (long) (timeLimitSeconds * 1_000_000_000.0);
        int iterations = 0;
        int learnedConflicts = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Choice solve cancelled");
            double remaining = (deadline - System.nanoTime()) / 1_000_000_000.0;
            if (remaining < MIN_SOLVE_SECONDS) {
                return Result.withoutAssignment(Status.UNKNOWN, iterations, learnedConflicts, "time_limit");
            }
            try (CpSatRuntime.Session session = runtime.newSession(model,
                    CpSatRuntime.Settings.deterministic(remaining, randomSeed))) {
                CpSolverStatus solverStatus = session.solve();
                iterations++;
                if (solverStatus == CpSolverStatus.INFEASIBLE) {
                    return Result.withoutAssignment(Status.INFEASIBLE, iterations, learnedConflicts, "model_infeasible");
                }
                if (solverStatus == CpSolverStatus.MODEL_INVALID) {
                    throw new IllegalStateException("CP-SAT rejected the discrete choice model");
                }
                if (solverStatus != CpSolverStatus.FEASIBLE && solverStatus != CpSolverStatus.OPTIMAL) {
                    return Result.withoutAssignment(Status.UNKNOWN, iterations, learnedConflicts, "solver_" + solverStatus);
                }
                Map<String, String> assignment = readAssignment(problem, variables, session);
                Evaluation evaluation = evaluator.evaluate(assignment);
                if (evaluation == null) throw new IllegalStateException("Evaluator returned null");
                if (evaluation.outcome == Outcome.ACCEPTED) {
                    Status status = solverStatus == CpSolverStatus.OPTIMAL ? Status.OPTIMAL : Status.FEASIBLE;
                    return Result.accepted(status, assignment, originalCost(problem, assignment), iterations,
                            learnedConflicts, evaluation.reason);
                }
                if (evaluation.outcome == Outcome.UNKNOWN) {
                    return Result.withAssignment(Status.UNKNOWN, assignment, originalCost(problem, assignment),
                            iterations, learnedConflicts, evaluation.reason);
                }
                DiscreteChoiceProblem.Conflict conflict = validateLearnedConflict(problem, assignment, evaluation);
                addConflict(model, variables, conflict);
                learnedConflicts++;
            }
        }
    }

    private Map<DiscreteChoiceProblem.Choice, BoolVar> buildVariables(
            DiscreteChoiceProblem problem, CpModel model) {
        Map<DiscreteChoiceProblem.Choice, BoolVar> variables = new LinkedHashMap<>();
        for (DiscreteChoiceProblem.Group group : problem.getGroups()) {
            List<Literal> exactlyOne = new ArrayList<>();
            for (DiscreteChoiceProblem.Alternative alternative : group.getAlternatives()) {
                DiscreteChoiceProblem.Choice choice = new DiscreteChoiceProblem.Choice(
                        group.getId(), alternative.getId());
                BoolVar variable = model.newBoolVar("choice/" + group.getId() + "/" + alternative.getId());
                variables.put(choice, variable);
                exactlyOne.add(variable);
            }
            model.addExactlyOne(exactlyOne);
        }
        return variables;
    }

    private void addObjective(DiscreteChoiceProblem problem, CpModel model,
            Map<DiscreteChoiceProblem.Choice, BoolVar> variables) {
        LinearExprBuilder objective = LinearExpr.newBuilder();
        for (DiscreteChoiceProblem.Group group : problem.getGroups()) {
            for (DiscreteChoiceProblem.Alternative alternative : group.getAlternatives()) {
                DiscreteChoiceProblem.Choice choice = new DiscreteChoiceProblem.Choice(
                        group.getId(), alternative.getId());
                objective.addTerm(variables.get(choice), alternative.getCost());
            }
        }
        model.minimize(objective);
    }

    private void addConflicts(CpModel model, Map<DiscreteChoiceProblem.Choice, BoolVar> variables,
            Collection<DiscreteChoiceProblem.Conflict> conflicts) {
        for (DiscreteChoiceProblem.Conflict conflict : conflicts) addConflict(model, variables, conflict);
    }

    private void addConflict(CpModel model, Map<DiscreteChoiceProblem.Choice, BoolVar> variables,
            DiscreteChoiceProblem.Conflict conflict) {
        List<Literal> atLeastOneChanges = new ArrayList<>();
        for (DiscreteChoiceProblem.Choice choice : conflict.getChoices()) {
            BoolVar variable = variables.get(choice);
            if (variable == null) throw new IllegalArgumentException("Unknown conflict choice: " + choice);
            atLeastOneChanges.add(variable.not());
        }
        model.addBoolOr(atLeastOneChanges);
    }

    private Map<String, String> readAssignment(DiscreteChoiceProblem problem,
            Map<DiscreteChoiceProblem.Choice, BoolVar> variables, CpSatRuntime.Session session) {
        Map<String, String> assignment = new LinkedHashMap<>();
        for (DiscreteChoiceProblem.Group group : problem.getGroups()) {
            for (DiscreteChoiceProblem.Alternative alternative : group.getAlternatives()) {
                DiscreteChoiceProblem.Choice choice = new DiscreteChoiceProblem.Choice(
                        group.getId(), alternative.getId());
                if (session.value(variables.get(choice)) == 1L) {
                    if (assignment.put(group.getId(), alternative.getId()) != null) {
                        throw new IllegalStateException("CP-SAT selected more than one group alternative");
                    }
                }
            }
            if (!assignment.containsKey(group.getId())) {
                throw new IllegalStateException("CP-SAT omitted a required group");
            }
        }
        return Collections.unmodifiableMap(assignment);
    }

    private DiscreteChoiceProblem.Conflict validateLearnedConflict(DiscreteChoiceProblem problem,
            Map<String, String> assignment, Evaluation evaluation) {
        if (evaluation.conflict.isEmpty()) {
            throw new IllegalArgumentException("A proven rejection requires a non-empty conflict scope");
        }
        for (DiscreteChoiceProblem.Choice choice : evaluation.conflict) {
            if (problem.group(choice.getGroupId()) == null
                    || !choice.getAlternativeId().equals(assignment.get(choice.getGroupId()))) {
                throw new IllegalArgumentException("Learned conflict must contain only selected catalog choices");
            }
        }
        return new DiscreteChoiceProblem.Conflict(evaluation.conflict, evaluation.reason);
    }

    private long originalCost(DiscreteChoiceProblem problem, Map<String, String> assignment) {
        long total = 0L;
        for (Map.Entry<String, String> selected : assignment.entrySet()) {
            DiscreteChoiceProblem.Alternative alternative = problem.group(selected.getKey())
                    .alternative(selected.getValue());
            total = Math.addExact(total, alternative.getCost());
        }
        return total;
    }

    public interface Evaluator {
        Evaluation evaluate(Map<String, String> assignment);
    }

    public enum Status { OPTIMAL, FEASIBLE, INFEASIBLE, UNKNOWN }
    private enum Outcome { ACCEPTED, REJECTED, UNKNOWN }

    /** Exact-evaluator verdict with an explicit proof scope for every rejection. */
    public static final class Evaluation {
        private final Outcome outcome;
        private final List<DiscreteChoiceProblem.Choice> conflict;
        private final String reason;

        private Evaluation(Outcome outcome, Collection<DiscreteChoiceProblem.Choice> conflict, String reason) {
            this.outcome = outcome;
            this.conflict = conflict == null ? List.of() : List.copyOf(conflict);
            this.reason = requireReason(reason);
        }

        public static Evaluation accepted(String reason) {
            return new Evaluation(Outcome.ACCEPTED, List.of(), reason);
        }

        public static Evaluation rejected(Collection<DiscreteChoiceProblem.Choice> conflict, String reason) {
            return new Evaluation(Outcome.REJECTED, conflict, reason);
        }

        public static Evaluation unknown(String reason) {
            return new Evaluation(Outcome.UNKNOWN, List.of(), reason);
        }

        private static String requireReason(String reason) {
            if (reason == null || reason.trim().isEmpty()) throw new IllegalArgumentException("Evaluation reason required");
            return reason;
        }
    }

    /** Результат master+refinement без утверждений о глобуме за пределами переданного каталога. */
    public static final class Result {
        private final Status status;
        private final Map<String, String> assignment;
        private final Long cost;
        private final int iterations;
        private final int learnedConflicts;
        private final String reason;

        private Result(Status status, Map<String, String> assignment, Long cost,
                int iterations, int learnedConflicts, String reason) {
            this.status = status;
            this.assignment = assignment == null ? Map.of() : Map.copyOf(assignment);
            this.cost = cost;
            this.iterations = iterations;
            this.learnedConflicts = learnedConflicts;
            this.reason = reason;
        }

        private static Result accepted(Status status, Map<String, String> assignment, long cost,
                int iterations, int learnedConflicts, String reason) {
            return new Result(status, assignment, cost, iterations, learnedConflicts, reason);
        }

        private static Result withAssignment(Status status, Map<String, String> assignment, long cost,
                int iterations, int learnedConflicts, String reason) {
            return new Result(status, assignment, cost, iterations, learnedConflicts, reason);
        }

        private static Result withoutAssignment(Status status, int iterations, int learnedConflicts, String reason) {
            return new Result(status, null, null, iterations, learnedConflicts, reason);
        }

        public Status getStatus() { return status; }
        public Map<String, String> getAssignment() { return assignment; }
        public Long getCost() { return cost; }
        public int getIterations() { return iterations; }
        public int getLearnedConflicts() { return learnedConflicts; }
        public String getReason() { return reason; }
    }
}
