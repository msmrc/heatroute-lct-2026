package ru.lct.heatroute.domain.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Сравнивает optimum CP-SAT с независимым полным перебором малых каталогов. */
class NetworkConstraintModelOracleTest {
    @Test
    void randomSmallCatalogsMatchExhaustiveOracle() {
        Random random = new Random(20260928L);
        CpSatChoiceOptimizer optimizer = new CpSatChoiceOptimizer(new CpSatRuntime());
        for (int scenario = 0; scenario < 80; scenario++) {
            DiscreteChoiceProblem problem = randomProblem(random, scenario);
            Long oracle = exhaustiveOptimum(problem);

            CpSatChoiceOptimizer.Result result = optimizer.solve(problem,
                    assignment -> CpSatChoiceOptimizer.Evaluation.accepted("oracle_admission"), 5.0, 2026);

            if (oracle == null) {
                assertThat(result.getStatus()).as("scenario %s", scenario)
                        .isEqualTo(CpSatChoiceOptimizer.Status.INFEASIBLE);
                assertThat(result.getAssignment()).isEmpty();
            } else {
                assertThat(result.getStatus()).as("scenario %s", scenario)
                        .isEqualTo(CpSatChoiceOptimizer.Status.OPTIMAL);
                assertThat(result.getCost()).as("scenario %s", scenario).isEqualTo(oracle);
                assertThat(isFeasible(problem, result.getAssignment())).as("scenario %s", scenario).isTrue();
            }
        }
    }

    private DiscreteChoiceProblem randomProblem(Random random, int scenario) {
        int groupCount = 2 + random.nextInt(5);
        List<DiscreteChoiceProblem.Group> groups = new ArrayList<>();
        List<DiscreteChoiceProblem.Choice> allChoices = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            String groupId = "g" + groupIndex;
            int alternativeCount = 2 + random.nextInt(3);
            List<DiscreteChoiceProblem.Alternative> alternatives = new ArrayList<>();
            for (int alternativeIndex = 0; alternativeIndex < alternativeCount; alternativeIndex++) {
                String alternativeId = "a" + alternativeIndex;
                alternatives.add(new DiscreteChoiceProblem.Alternative(alternativeId, random.nextInt(51)));
                allChoices.add(new DiscreteChoiceProblem.Choice(groupId, alternativeId));
            }
            groups.add(new DiscreteChoiceProblem.Group(groupId, alternatives));
        }
        List<DiscreteChoiceProblem.Conflict> conflicts = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int conflictCount = random.nextInt(groupCount * 3 + 1);
        for (int conflictIndex = 0; conflictIndex < conflictCount; conflictIndex++) {
            int width = 1 + random.nextInt(Math.min(3, groupCount));
            Map<String, DiscreteChoiceProblem.Choice> byGroup = new HashMap<>();
            while (byGroup.size() < width) {
                DiscreteChoiceProblem.Choice choice = allChoices.get(random.nextInt(allChoices.size()));
                byGroup.put(choice.getGroupId(), choice);
            }
            List<DiscreteChoiceProblem.Choice> choices = new ArrayList<>(byGroup.values());
            choices.sort(java.util.Comparator.comparing(DiscreteChoiceProblem.Choice::toString));
            String signature = choices.toString();
            if (used.add(signature)) {
                conflicts.add(new DiscreteChoiceProblem.Conflict(choices,
                        "random_" + scenario + "_" + conflictIndex));
            }
        }
        return new DiscreteChoiceProblem(groups, conflicts);
    }

    private Long exhaustiveOptimum(DiscreteChoiceProblem problem) {
        return enumerate(problem, 0, new LinkedHashMap<>(), null);
    }

    private Long enumerate(DiscreteChoiceProblem problem, int groupIndex,
            Map<String, String> assignment, Long best) {
        if (groupIndex == problem.getGroups().size()) {
            if (!isFeasible(problem, assignment)) return best;
            long cost = 0L;
            for (Map.Entry<String, String> choice : assignment.entrySet()) {
                cost += problem.group(choice.getKey()).alternative(choice.getValue()).getCost();
            }
            return best == null || cost < best ? cost : best;
        }
        DiscreteChoiceProblem.Group group = problem.getGroups().get(groupIndex);
        Long result = best;
        for (DiscreteChoiceProblem.Alternative alternative : group.getAlternatives()) {
            assignment.put(group.getId(), alternative.getId());
            result = enumerate(problem, groupIndex + 1, assignment, result);
        }
        assignment.remove(group.getId());
        return result;
    }

    private boolean isFeasible(DiscreteChoiceProblem problem, Map<String, String> assignment) {
        if (assignment.size() != problem.getGroups().size()) return false;
        for (DiscreteChoiceProblem.Conflict conflict : problem.getConflicts()) {
            boolean selected = true;
            for (DiscreteChoiceProblem.Choice choice : conflict.getChoices()) {
                selected &= choice.getAlternativeId().equals(assignment.get(choice.getGroupId()));
            }
            if (selected) return false;
        }
        return true;
    }
}
