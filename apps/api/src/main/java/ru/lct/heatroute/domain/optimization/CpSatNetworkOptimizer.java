package ru.lct.heatroute.domain.optimization;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Точная CP-SAT master-модель конечного каталога: арбо-лес, несколько корней, полный coverage,
 * суммарный flow, точная локальная конфигурация узла, единый ДУ актива и стоимость ствола один раз.
 */
public final class CpSatNetworkOptimizer {
    private final CpSatRuntime runtime;

    public CpSatNetworkOptimizer(CpSatRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public Result solve(NetworkConstraintProblem problem, double timeLimitSeconds, int randomSeed) {
        return solve(problem, problem.getConflicts(), timeLimitSeconds, randomSeed);
    }

    /** Применяет только те доменные cuts, proof scope которых совместим с текущим каталогом. */
    public Result solve(NetworkConstraintProblem problem, ConflictStore conflictStore,
            CatalogIdentity catalogIdentity, double timeLimitSeconds, int randomSeed) {
        return solve(problem, conflictStore, catalogIdentity, timeLimitSeconds, randomSeed, false);
    }

    /** Возвращает первый допустимый master-incumbent для раннего запуска точного evaluator. */
    public Result solveFirstFeasible(NetworkConstraintProblem problem, ConflictStore conflictStore,
            CatalogIdentity catalogIdentity, double timeLimitSeconds, int randomSeed) {
        return solve(problem, conflictStore, catalogIdentity, timeLimitSeconds, randomSeed, true);
    }

    private Result solve(NetworkConstraintProblem problem, ConflictStore conflictStore,
            CatalogIdentity catalogIdentity, double timeLimitSeconds, int randomSeed,
            boolean firstFeasible) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(conflictStore, "conflictStore");
        Objects.requireNonNull(catalogIdentity, "catalogIdentity");
        CatalogIdentity expectedIdentity = CatalogIdentity.fromProblem(
                catalogIdentity.getSourceSnapshotHash(), catalogIdentity.getRuleId(),
                catalogIdentity.getRuleVersion(), catalogIdentity.getCheckerVersion(),
                catalogIdentity.getCatalogHash(), problem);
        if (!expectedIdentity.getDecisionKeys().equals(catalogIdentity.getDecisionKeys())) {
            throw new IllegalArgumentException("Catalog identity does not match the network problem");
        }
        List<NetworkConstraintProblem.Conflict> conflicts = new ArrayList<>(problem.getConflicts());
        conflicts.addAll(conflictStore.modelConflicts(catalogIdentity));
        return solve(problem, conflicts, timeLimitSeconds, randomSeed, firstFeasible);
    }

    private Result solve(NetworkConstraintProblem problem,
            List<NetworkConstraintProblem.Conflict> conflicts, double timeLimitSeconds, int randomSeed) {
        return solve(problem, conflicts, timeLimitSeconds, randomSeed, false);
    }

    private Result solve(NetworkConstraintProblem problem,
            List<NetworkConstraintProblem.Conflict> conflicts, double timeLimitSeconds,
            int randomSeed, boolean firstFeasible) {
        Objects.requireNonNull(problem, "problem");
        if (!Double.isFinite(timeLimitSeconds) || timeLimitSeconds <= 0.0 || randomSeed < 0) {
            throw new IllegalArgumentException("Finite positive time and non-negative seed required");
        }
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Network solve cancelled");
        CpModel model = runtime.newModel();
        Variables variables = build(problem, conflicts, model);
        try (CpSatRuntime.Session session = runtime.newSession(model,
                CpSatRuntime.Settings.deterministic(timeLimitSeconds, randomSeed))) {
            CpSolverStatus status = firstFeasible ? session.solveFirstFeasible() : session.solve();
            if (status == CpSolverStatus.INFEASIBLE) return Result.empty(Status.INFEASIBLE);
            if (status == CpSolverStatus.MODEL_INVALID) {
                throw new IllegalStateException("CP-SAT rejected the network constraint model");
            }
            if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
                return Result.empty(Status.UNKNOWN);
            }
            return read(problem, variables, session,
                    status == CpSolverStatus.OPTIMAL ? Status.OPTIMAL : Status.FEASIBLE);
        }
    }

    private Variables build(NetworkConstraintProblem problem,
            List<NetworkConstraintProblem.Conflict> conflicts, CpModel model) {
        int nodeCount = problem.getNodes().size();
        long totalDemand = problem.getTotalDemandUnits();
        Map<String, BoolVar> used = new LinkedHashMap<>();
        Map<String, BoolVar> roots = new LinkedHashMap<>();
        Map<String, IntVar> source = new LinkedHashMap<>();
        Map<String, IntVar> order = new LinkedHashMap<>();
        for (NetworkConstraintProblem.Node node : problem.getNodes()) {
            BoolVar z = model.newBoolVar("node/" + node.getId());
            used.put(node.getId(), z);
            IntVar h = model.newIntVar(0L, nodeCount, "order/" + node.getId());
            order.put(node.getId(), h);
            model.addLessOrEqual(LinearExpr.newBuilder().add(h).addTerm(z, -nodeCount), 0L);
            if (node.isTerminal()) model.addEquality(z, 1L);
            if (node.isAllowedRoot()) {
                BoolVar r = model.newBoolVar("root/" + node.getId());
                roots.put(node.getId(), r);
                model.addLessOrEqual(r, z);
                model.addLessOrEqual(LinearExpr.newBuilder().add(h).addTerm(r, nodeCount), nodeCount);
                IntVar supply = model.newIntVar(0L, totalDemand, "source/" + node.getId());
                source.put(node.getId(), supply);
                model.addLessOrEqual(LinearExpr.newBuilder().add(supply).addTerm(r, -totalDemand), 0L);
            }
        }

        Map<String, BoolVar> selected = new LinkedHashMap<>();
        Map<String, IntVar> flows = new LinkedHashMap<>();
        Map<String, Map<Integer, BoolVar>> diameters = new LinkedHashMap<>();
        Map<String, BoolVar> nodeConfigurations = new LinkedHashMap<>();
        Map<String, List<NetworkConstraintProblem.Asset>> incoming = new LinkedHashMap<>();
        Map<String, List<NetworkConstraintProblem.Asset>> outgoing = new LinkedHashMap<>();
        LinearExprBuilder objective = LinearExpr.newBuilder();
        for (NetworkConstraintProblem.Asset asset : problem.getAssets()) {
            BoolVar x = model.newBoolVar("asset/" + asset.getId());
            selected.put(asset.getId(), x);
            model.addLessOrEqual(x, used.get(asset.getFromNodeId()));
            model.addLessOrEqual(x, used.get(asset.getToNodeId()));
            incoming.computeIfAbsent(asset.getToNodeId(), ignored -> new ArrayList<>()).add(asset);
            outgoing.computeIfAbsent(asset.getFromNodeId(), ignored -> new ArrayList<>()).add(asset);
            IntVar flow = model.newIntVar(0L, totalDemand, "flow/" + asset.getId());
            flows.put(asset.getId(), flow);
            model.addLessOrEqual(LinearExpr.newBuilder().add(flow).addTerm(x, -totalDemand), 0L);

            LinearExprBuilder diameterCount = LinearExpr.newBuilder().addTerm(x, -1L);
            LinearExprBuilder capacity = LinearExpr.newBuilder().add(flow);
            Map<Integer, BoolVar> choices = new LinkedHashMap<>();
            for (NetworkConstraintProblem.DiameterOption option : asset.getDiameters()) {
                BoolVar y = model.newBoolVar("diameter/" + asset.getId() + "/" + option.getDiameterMm());
                choices.put(option.getDiameterMm(), y);
                diameterCount.add(y);
                capacity.addTerm(y, -option.getCapacityUnits());
                objective.addTerm(y, option.getCostUnits());
            }
            model.addEquality(diameterCount, 0L);
            model.addLessOrEqual(capacity, 0L);
            diameters.put(asset.getId(), Collections.unmodifiableMap(choices));
            objective.addTerm(x, asset.getFixedCostUnits());

            LinearExprBuilder acyclic = LinearExpr.newBuilder()
                    .add(order.get(asset.getToNodeId()))
                    .addTerm(order.get(asset.getFromNodeId()), -1L)
                    .addTerm(x, -nodeCount);
            model.addGreaterOrEqual(acyclic, 1L - nodeCount);
        }

        Map<String, List<NetworkConstraintProblem.NodeConfiguration>> configurationsByNode =
                new LinkedHashMap<>();
        for (NetworkConstraintProblem.NodeConfiguration configuration
                : problem.getNodeConfigurations()) {
            BoolVar choice = model.newBoolVar("node-configuration/" + configuration.getId());
            nodeConfigurations.put(configuration.getId(), choice);
            configurationsByNode.computeIfAbsent(
                    configuration.getNodeId(), ignored -> new ArrayList<>()).add(configuration);
        }
        for (NetworkConstraintProblem.Node node : problem.getNodes()) {
            if (!node.isConfigurationRequired()) continue;
            List<NetworkConstraintProblem.NodeConfiguration> configurations =
                    configurationsByNode.getOrDefault(node.getId(), List.of());
            LinearExprBuilder exactlyOneWhenUsed = LinearExpr.newBuilder()
                    .addTerm(used.get(node.getId()), -1L);
            for (NetworkConstraintProblem.NodeConfiguration configuration : configurations) {
                exactlyOneWhenUsed.add(nodeConfigurations.get(configuration.getId()));
            }
            model.addEquality(exactlyOneWhenUsed, 0L);

            List<NetworkConstraintProblem.Asset> incident = new ArrayList<>();
            incident.addAll(incoming.getOrDefault(node.getId(), List.of()));
            incident.addAll(outgoing.getOrDefault(node.getId(), List.of()));
            for (NetworkConstraintProblem.Asset asset : incident) {
                LinearExprBuilder exactIncidence = LinearExpr.newBuilder()
                        .addTerm(selected.get(asset.getId()), -1L);
                for (NetworkConstraintProblem.NodeConfiguration configuration : configurations) {
                    if (configuration.getIncidentAssetIds().contains(asset.getId())) {
                        exactIncidence.add(nodeConfigurations.get(configuration.getId()));
                    }
                }
                model.addEquality(exactIncidence, 0L);
            }
        }

        for (NetworkConstraintProblem.Node node : problem.getNodes()) {
            LinearExprBuilder parent = LinearExpr.newBuilder().addTerm(used.get(node.getId()), -1L);
            for (NetworkConstraintProblem.Asset asset : incoming.getOrDefault(node.getId(), List.of())) {
                parent.add(selected.get(asset.getId()));
            }
            BoolVar root = roots.get(node.getId());
            if (root != null) parent.add(root);
            model.addEquality(parent, 0L);
            if (root != null) {
                LinearExprBuilder activeRootServesNetwork = LinearExpr.newBuilder().addTerm(root, -1L);
                for (NetworkConstraintProblem.Asset asset : outgoing.getOrDefault(node.getId(), List.of())) {
                    activeRootServesNetwork.add(selected.get(asset.getId()));
                }
                model.addGreaterOrEqual(activeRootServesNetwork, 0L);
            }

            LinearExprBuilder balance = LinearExpr.newBuilder();
            for (NetworkConstraintProblem.Asset asset : incoming.getOrDefault(node.getId(), List.of())) {
                balance.add(flows.get(asset.getId()));
            }
            IntVar supply = source.get(node.getId());
            if (supply != null) balance.add(supply);
            for (NetworkConstraintProblem.Asset asset : outgoing.getOrDefault(node.getId(), List.of())) {
                balance.addTerm(flows.get(asset.getId()), -1L);
            }
            model.addEquality(balance, node.getDemandUnits());
        }
        model.addEquality(LinearExpr.sum(source.values().toArray(new IntVar[0])), totalDemand);

        for (NetworkConstraintProblem.Conflict conflict : conflicts) {
            List<Literal> atLeastOneChanges = new ArrayList<>();
            for (NetworkConstraintProblem.DecisionLiteral decision : conflict.getLiterals()) {
                BoolVar variable = conflictVariable(
                        decision, selected, roots, nodeConfigurations, diameters);
                atLeastOneChanges.add(decision.isExpected() ? variable.not() : variable);
            }
            model.addBoolOr(atLeastOneChanges);
        }
        model.minimize(objective);
        return new Variables(selected, roots, flows, diameters, nodeConfigurations);
    }

    private BoolVar conflictVariable(NetworkConstraintProblem.DecisionLiteral literal,
            Map<String, BoolVar> selected, Map<String, BoolVar> roots,
            Map<String, BoolVar> nodeConfigurations,
            Map<String, Map<Integer, BoolVar>> diameters) {
        switch (literal.getType()) {
            case ASSET_SELECTED:
                return selected.get(literal.getSubjectId());
            case ROOT_SELECTED:
                return roots.get(literal.getSubjectId());
            case NODE_CONFIGURATION_SELECTED:
                return nodeConfigurations.get(literal.getSubjectId());
            case DIAMETER_SELECTED:
                return diameters.get(literal.getSubjectId()).get(literal.getDiameterMm());
            default:
                throw new IllegalStateException("Unsupported conflict literal type: " + literal.getType());
        }
    }

    private Result read(NetworkConstraintProblem problem, Variables variables,
            CpSatRuntime.Session session, Status status) {
        Set<String> selectedAssets = new LinkedHashSet<>();
        Set<String> selectedRoots = new LinkedHashSet<>();
        Set<String> selectedNodeConfigurations = new LinkedHashSet<>();
        Map<String, Long> flows = new LinkedHashMap<>();
        Map<String, Integer> diameters = new LinkedHashMap<>();
        long objective = 0L;
        for (NetworkConstraintProblem.Node node : problem.getNodes()) {
            BoolVar root = variables.roots.get(node.getId());
            if (root != null && session.value(root) == 1L) selectedRoots.add(node.getId());
        }
        for (NetworkConstraintProblem.NodeConfiguration configuration
                : problem.getNodeConfigurations()) {
            if (session.value(variables.nodeConfigurations.get(configuration.getId())) == 1L) {
                selectedNodeConfigurations.add(configuration.getId());
            }
        }
        for (NetworkConstraintProblem.Asset asset : problem.getAssets()) {
            if (session.value(variables.selected.get(asset.getId())) != 1L) continue;
            selectedAssets.add(asset.getId());
            flows.put(asset.getId(), session.value(variables.flows.get(asset.getId())));
            objective = Math.addExact(objective, asset.getFixedCostUnits());
            for (NetworkConstraintProblem.DiameterOption option : asset.getDiameters()) {
                if (session.value(variables.diameters.get(asset.getId()).get(option.getDiameterMm())) == 1L) {
                    diameters.put(asset.getId(), option.getDiameterMm());
                    objective = Math.addExact(objective, option.getCostUnits());
                    break;
                }
            }
            if (!diameters.containsKey(asset.getId())) {
                throw new IllegalStateException("Selected asset has no diameter assignment");
            }
        }
        return new Result(status, selectedAssets, selectedRoots, selectedNodeConfigurations,
                flows, diameters, objective);
    }

    public enum Status { OPTIMAL, FEASIBLE, INFEASIBLE, UNKNOWN }

    public static final class Result {
        private final Status status;
        private final Set<String> selectedAssets;
        private final Set<String> selectedRoots;
        private final Set<String> selectedNodeConfigurations;
        private final Map<String, Long> flowUnits;
        private final Map<String, Integer> diameterMm;
        private final Long objectiveUnits;

        private Result(Status status, Set<String> selectedAssets, Set<String> selectedRoots,
                Set<String> selectedNodeConfigurations, Map<String, Long> flowUnits,
                Map<String, Integer> diameterMm, Long objectiveUnits) {
            this.status = status;
            this.selectedAssets = Set.copyOf(selectedAssets);
            this.selectedRoots = Set.copyOf(selectedRoots);
            this.selectedNodeConfigurations = Set.copyOf(selectedNodeConfigurations);
            this.flowUnits = Map.copyOf(flowUnits);
            this.diameterMm = Map.copyOf(diameterMm);
            this.objectiveUnits = objectiveUnits;
        }

        private static Result empty(Status status) {
            return new Result(status, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), null);
        }

        public Status getStatus() { return status; }
        public Set<String> getSelectedAssets() { return selectedAssets; }
        public Set<String> getSelectedRoots() { return selectedRoots; }
        public Set<String> getSelectedNodeConfigurations() {
            return selectedNodeConfigurations;
        }
        public Map<String, Long> getFlowUnits() { return flowUnits; }
        public Map<String, Integer> getDiameterMm() { return diameterMm; }
        public Long getObjectiveUnits() { return objectiveUnits; }
    }

    private static final class Variables {
        private final Map<String, BoolVar> selected;
        private final Map<String, BoolVar> roots;
        private final Map<String, IntVar> flows;
        private final Map<String, Map<Integer, BoolVar>> diameters;
        private final Map<String, BoolVar> nodeConfigurations;

        private Variables(Map<String, BoolVar> selected, Map<String, BoolVar> roots,
                Map<String, IntVar> flows, Map<String, Map<Integer, BoolVar>> diameters,
                Map<String, BoolVar> nodeConfigurations) {
            this.selected = selected;
            this.roots = roots;
            this.flows = flows;
            this.diameters = diameters;
            this.nodeConfigurations = nodeConfigurations;
        }
    }
}
