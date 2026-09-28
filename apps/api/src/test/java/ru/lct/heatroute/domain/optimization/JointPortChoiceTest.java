package ru.lct.heatroute.domain.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Контрпримеры pair/hyper-conflicts: несовместимая пара не удаляет полезную альтернативу глобально. */
class JointPortChoiceTest {
    private final CpSatChoiceOptimizer optimizer = new CpSatChoiceOptimizer(new CpSatRuntime());

    @Test
    void pairConflictRejectsAButKeepsAWithAnotherPort() {
        DiscreteChoiceProblem.Choice a = choice("left", "A");
        DiscreteChoiceProblem.Choice b = choice("right", "B");
        DiscreteChoiceProblem problem = new DiscreteChoiceProblem(List.of(
                group("left", alternative("A", 1), alternative("D", 20)),
                group("right", alternative("B", 1), alternative("C", 3))),
                List.of(new DiscreteChoiceProblem.Conflict(List.of(a, b), "paths_cross")));

        CpSatChoiceOptimizer.Result result = optimizer.solve(problem,
                assignment -> CpSatChoiceOptimizer.Evaluation.accepted("geometry_ok"), 5.0, 2026);

        assertThat(result.getStatus()).isEqualTo(CpSatChoiceOptimizer.Status.OPTIMAL);
        assertThat(result.getAssignment()).containsEntry("left", "A").containsEntry("right", "C");
        assertThat(result.getCost()).isEqualTo(4L);
    }

    @Test
    void evaluatorLearnsOnlyTheExactConflictingPair() {
        DiscreteChoiceProblem problem = new DiscreteChoiceProblem(List.of(
                group("left", alternative("A", 1), alternative("D", 20)),
                group("right", alternative("B", 1), alternative("C", 3))), List.of());
        AtomicInteger evaluations = new AtomicInteger();

        CpSatChoiceOptimizer.Result result = optimizer.solve(problem, assignment -> {
            evaluations.incrementAndGet();
            if (assignment.equals(Map.of("left", "A", "right", "B"))) {
                return CpSatChoiceOptimizer.Evaluation.rejected(List.of(
                        choice("left", "A"), choice("right", "B")), "exact_intersection");
            }
            return CpSatChoiceOptimizer.Evaluation.accepted("geometry_ok");
        }, 5.0, 2026);

        assertThat(evaluations).hasValue(2);
        assertThat(result.getLearnedConflicts()).isEqualTo(1);
        assertThat(result.getAssignment()).containsEntry("left", "A").containsEntry("right", "C");
    }

    @Test
    void hyperConflictDoesNotCreateAFalseClique() {
        DiscreteChoiceProblem problem = new DiscreteChoiceProblem(List.of(
                group("one", alternative("A", 1), alternative("X", 10)),
                group("two", alternative("B", 1), alternative("Y", 10)),
                group("three", alternative("C", 1), alternative("Z", 4))),
                List.of(new DiscreteChoiceProblem.Conflict(List.of(
                        choice("one", "A"), choice("two", "B"), choice("three", "C")), "three_way_capacity")));

        CpSatChoiceOptimizer.Result result = optimizer.solve(problem,
                assignment -> CpSatChoiceOptimizer.Evaluation.accepted("exact_ok"), 5.0, 2026);

        assertThat(result.getAssignment()).containsEntry("one", "A").containsEntry("two", "B")
                .containsEntry("three", "Z");
        assertThat(result.getCost()).isEqualTo(6L);
    }

    @Test
    void unknownEvaluationNeverBecomesANoGood() {
        DiscreteChoiceProblem problem = new DiscreteChoiceProblem(List.of(
                group("one", alternative("A", 1), alternative("B", 2))), List.of());

        CpSatChoiceOptimizer.Result result = optimizer.solve(problem,
                assignment -> CpSatChoiceOptimizer.Evaluation.unknown("geometry_budget_exhausted"), 5.0, 2026);

        assertThat(result.getStatus()).isEqualTo(CpSatChoiceOptimizer.Status.UNKNOWN);
        assertThat(result.getAssignment()).containsEntry("one", "A");
        assertThat(result.getLearnedConflicts()).isZero();
    }

    private DiscreteChoiceProblem.Group group(String id, DiscreteChoiceProblem.Alternative... alternatives) {
        return new DiscreteChoiceProblem.Group(id, List.of(alternatives));
    }

    private DiscreteChoiceProblem.Alternative alternative(String id, long cost) {
        return new DiscreteChoiceProblem.Alternative(id, cost);
    }

    private DiscreteChoiceProblem.Choice choice(String group, String alternative) {
        return new DiscreteChoiceProblem.Choice(group, alternative);
    }
}
