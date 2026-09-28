package ru.lct.heatroute.domain.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CpSatNetworkOptimizerTest {
    private final CpSatNetworkOptimizer optimizer = new CpSatNetworkOptimizer(new CpSatRuntime());

    @Test
    void selectsSharedTrunkOnceAndAssignsItsAggregateFlowAndDiameter() {
        NetworkConstraintProblem problem = new NetworkConstraintProblem(
                nodes(),
                List.of(
                        asset("trunk", "root", "junction", 5, option(50, 1, 0), option(100, 2, 4)),
                        asset("branch-1", "junction", "terminal-1", 3, option(50, 1, 0)),
                        asset("branch-2", "junction", "terminal-2", 3, option(50, 1, 0)),
                        asset("direct-1", "root", "terminal-1", 9, option(50, 1, 0)),
                        asset("direct-2", "root", "terminal-2", 9, option(50, 1, 0))),
                List.of());

        CpSatNetworkOptimizer.Result result = optimizer.solve(problem, 5.0, 2026);

        assertThat(result.getStatus()).isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
        assertThat(result.getSelectedRoots()).containsExactly("root");
        assertThat(result.getSelectedAssets()).containsExactlyInAnyOrder("trunk", "branch-1", "branch-2");
        assertThat(result.getFlowUnits()).containsEntry("trunk", 2L)
                .containsEntry("branch-1", 1L).containsEntry("branch-2", 1L);
        assertThat(result.getDiameterMm()).containsEntry("trunk", 100)
                .containsEntry("branch-1", 50).containsEntry("branch-2", 50);
        assertThat(result.getObjectiveUnits()).isEqualTo(15L);
    }

    @Test
    void choosesAmongMultipleAllowedRoots() {
        NetworkConstraintProblem problem = new NetworkConstraintProblem(
                List.of(
                        new NetworkConstraintProblem.Node("root-a", true, 0),
                        new NetworkConstraintProblem.Node("root-b", true, 0),
                        new NetworkConstraintProblem.Node("terminal", false, 1)),
                List.of(
                        asset("from-a", "root-a", "terminal", 10, option(50, 1, 0)),
                        asset("from-b", "root-b", "terminal", 2, option(50, 1, 0))),
                List.of());

        CpSatNetworkOptimizer.Result result = optimizer.solve(problem, 5.0, 2026);

        assertThat(result.getStatus()).isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
        assertThat(result.getSelectedRoots()).containsExactly("root-b");
        assertThat(result.getSelectedAssets()).containsExactly("from-b");
        assertThat(result.getObjectiveUnits()).isEqualTo(2L);
    }

    @Test
    void reportsInfeasibleWhenNoDiameterCanCarryTerminalDemand() {
        NetworkConstraintProblem problem = new NetworkConstraintProblem(
                List.of(
                        new NetworkConstraintProblem.Node("root", true, 0),
                        new NetworkConstraintProblem.Node("terminal", false, 3)),
                List.of(asset("undersized", "root", "terminal", 1, option(50, 2, 0))),
                List.of());

        CpSatNetworkOptimizer.Result result = optimizer.solve(problem, 5.0, 2026);

        assertThat(result.getStatus()).isEqualTo(CpSatNetworkOptimizer.Status.INFEASIBLE);
        assertThat(result.getSelectedAssets()).isEmpty();
    }

    @Test
    void zeroFlowDemandRemainsAMandatoryConnectedTerminal() {
        NetworkConstraintProblem problem = new NetworkConstraintProblem(
                List.of(
                        new NetworkConstraintProblem.Node("root", true, 0),
                        new NetworkConstraintProblem.Node("zero-flow-terminal", false, 0, true)),
                List.of(asset("connection", "root", "zero-flow-terminal", 1,
                        option(50, 1, 0))),
                List.of());

        CpSatNetworkOptimizer.Result result = optimizer.solve(problem, 5.0, 2026);

        assertThat(result.getStatus()).isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
        assertThat(result.getSelectedRoots()).containsExactly("root");
        assertThat(result.getSelectedAssets()).containsExactly("connection");
        assertThat(result.getFlowUnits()).containsEntry("connection", 0L);
    }

    @Test
    void randomSmallCatalogsMatchIndependentEnumeration() {
        Random random = new Random(20260928L);
        for (int scenario = 0; scenario < 80; scenario++) {
            NetworkConstraintProblem problem = randomProblem(random, scenario);
            long expected = exhaustiveCost(problem);

            CpSatNetworkOptimizer.Result result = optimizer.solve(problem, 5.0, 2026);

            assertThat(result.getStatus()).as("scenario %s", scenario)
                    .isEqualTo(CpSatNetworkOptimizer.Status.OPTIMAL);
            assertThat(result.getObjectiveUnits()).as("scenario %s", scenario).isEqualTo(expected);
        }
    }

    private NetworkConstraintProblem randomProblem(Random random, int scenario) {
        List<NetworkConstraintProblem.Asset> assets = List.of(
                randomAsset(random, "direct-1", "root", "terminal-1"),
                randomAsset(random, "direct-2", "root", "terminal-2"),
                randomAsset(random, "trunk", "root", "junction"),
                randomAsset(random, "branch-1", "junction", "terminal-1"),
                randomAsset(random, "branch-2", "junction", "terminal-2"));
        List<NetworkConstraintProblem.Conflict> conflicts = new ArrayList<>();
        if (scenario % 3 == 0) {
            conflicts.add(new NetworkConstraintProblem.Conflict(
                    List.of("direct-1", "direct-2"), "random_pair_" + scenario));
        }
        return new NetworkConstraintProblem(nodes(), assets, conflicts);
    }

    private NetworkConstraintProblem.Asset randomAsset(Random random, String id, String from, String to) {
        return asset(id, from, to, 1 + random.nextInt(20),
                option(50, 1, random.nextInt(5)), option(100, 2, 1 + random.nextInt(8)));
    }

    private long exhaustiveCost(NetworkConstraintProblem problem) {
        List<NetworkConstraintProblem.Asset> assets = problem.getAssets();
        Long best = null;
        for (int mask = 0; mask < (1 << assets.size()); mask++) {
            Set<String> selected = new HashSet<>();
            for (int index = 0; index < assets.size(); index++) {
                if ((mask & (1 << index)) != 0) selected.add(assets.get(index).getId());
            }
            if (!validForest(selected) || conflicts(problem, selected)) continue;
            long cost = 0L;
            for (NetworkConstraintProblem.Asset asset : assets) {
                if (!selected.contains(asset.getId())) continue;
                long flow = flow(asset.getId(), selected);
                long diameterCost = asset.getDiameters().stream()
                        .filter(option -> option.getCapacityUnits() >= flow)
                        .mapToLong(NetworkConstraintProblem.DiameterOption::getCostUnits).min().orElse(Long.MAX_VALUE);
                if (diameterCost == Long.MAX_VALUE) {
                    cost = Long.MAX_VALUE;
                    break;
                }
                cost = Math.addExact(cost, Math.addExact(asset.getFixedCostUnits(), diameterCost));
            }
            if (cost != Long.MAX_VALUE && (best == null || cost < best)) best = cost;
        }
        if (best == null) throw new AssertionError("Generated catalog must retain direct coverage");
        return best;
    }

    private boolean validForest(Set<String> selected) {
        boolean terminal1 = selected.contains("direct-1") ^ selected.contains("branch-1");
        boolean terminal2 = selected.contains("direct-2") ^ selected.contains("branch-2");
        boolean junctionUsed = selected.contains("branch-1") || selected.contains("branch-2");
        return terminal1 && terminal2 && selected.contains("trunk") == junctionUsed;
    }

    private boolean conflicts(NetworkConstraintProblem problem, Set<String> selected) {
        return problem.getConflicts().stream().anyMatch(conflict -> conflict.getLiterals().stream().allMatch(literal -> {
            if (literal.getType() != NetworkConstraintProblem.DecisionLiteral.Type.ASSET_SELECTED) {
                throw new AssertionError("Random oracle uses only asset literals");
            }
            return selected.contains(literal.getSubjectId()) == literal.isExpected();
        }));
    }

    private long flow(String assetId, Set<String> selected) {
        if (assetId.equals("trunk")) {
            return (selected.contains("branch-1") ? 1L : 0L) + (selected.contains("branch-2") ? 1L : 0L);
        }
        return 1L;
    }

    private List<NetworkConstraintProblem.Node> nodes() {
        return List.of(
                new NetworkConstraintProblem.Node("root", true, 0),
                new NetworkConstraintProblem.Node("junction", false, 0),
                new NetworkConstraintProblem.Node("terminal-1", false, 1),
                new NetworkConstraintProblem.Node("terminal-2", false, 1));
    }

    private NetworkConstraintProblem.Asset asset(String id, String from, String to, long fixed,
            NetworkConstraintProblem.DiameterOption... options) {
        return new NetworkConstraintProblem.Asset(id, from, to, fixed, List.of(options));
    }

    private NetworkConstraintProblem.DiameterOption option(int diameter, long capacity, long cost) {
        return new NetworkConstraintProblem.DiameterOption(diameter, capacity, cost);
    }
}
