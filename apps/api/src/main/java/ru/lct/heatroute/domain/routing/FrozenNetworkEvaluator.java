package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.sizing.NetworkSizingIssue;
import ru.lct.heatroute.domain.sizing.NetworkSizingResult;
import ru.lct.heatroute.domain.sizing.NetworkTreeEdge;
import ru.lct.heatroute.domain.sizing.OfficialNetworkSizer;
import ru.lct.heatroute.domain.sizing.SizedNetworkEdge;

/**
 * Независимо допускает фиксированную topology/XY. Он назначает только производные flow, ДУ и
 * depth profile; любое изменение координат или связности считается внутренней ошибкой.
 */
@Component
public final class FrozenNetworkEvaluator {
    private final OfficialRouteValidator validator;
    private final OfficialObstacleRouter obstacleRouter;
    private final OfficialNetworkSizer networkSizer;
    private final OfficialDepthPlanner depthPlanner;
    private final OfficialVariantEconomicsCalculator economicsCalculator;
    private final ExpertChamberRouteValidator chamberValidator = new ExpertChamberRouteValidator();

    public FrozenNetworkEvaluator(
            OfficialRouteValidator validator,
            OfficialObstacleRouter obstacleRouter,
            OfficialNetworkSizer networkSizer,
            OfficialDepthPlanner depthPlanner,
            OfficialVariantEconomicsCalculator economicsCalculator) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.obstacleRouter = Objects.requireNonNull(obstacleRouter, "obstacleRouter");
        this.networkSizer = Objects.requireNonNull(networkSizer, "networkSizer");
        this.depthPlanner = Objects.requireNonNull(depthPlanner, "depthPlanner");
        this.economicsCalculator = Objects.requireNonNull(economicsCalculator, "economicsCalculator");
    }

    public Evaluation evaluate(FrozenNetworkCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        ensureActive();
        String originalHash = candidate.getGeometryHash();
        try {
            NetworkSizingResult sizing = networkSizer.size(
                    candidate.getEdges().stream().map(edge -> new NetworkTreeEdge(
                            edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(), edge.getLengthM()))
                            .collect(Collectors.toList()),
                    demandFlows(candidate.getConnections()));
            if (!sizing.getIssues().isEmpty()) {
                return Evaluation.rejected("sizing_rejected", List.of(), sizing.getIssues());
            }
            Map<String, RequiredSizing> requiredSizing = requiredSizing(sizing);
            if (!matchesRequiredSizing(candidate.getEdges(), requiredSizing)) {
                return Evaluation.sizingRequired(requiredSizing);
            }
            List<RouteEdge> sized = applySizing(candidate.getEdges(), requiredSizing);
            List<RouteEdge> assessed = candidate.getParameters().isDepthEnabled()
                    ? depth(candidate, sized)
                    : sized;
            ensureGeometryUnchanged(originalHash, candidate.getNodes(), assessed);

            OfficialRoutingEnvironment environment = candidate.preparedEnvironment() == null
                    ? obstacleRouter.prepare(candidate.getRelevantFeatures())
                    : candidate.preparedEnvironment();
            List<RouteValidationIssue> issues = new ArrayList<>(environment.validationFor(validator).validate(
                    candidate.getNodes(), assessed, candidate.getRelevantFeatures()));
            issues.addAll(chamberValidator.validate(candidate.getNodes(), assessed, environment::existingDirections));
            issues.addAll(ExpertRouteBendRules.validate(candidate.getNodes(), assessed));
            if (candidate.getParameters().isDepthEnabled()) addDepthIssues(assessed, issues);
            if (!issues.isEmpty()) {
                return Evaluation.rejected("engineering_rejected", issues, List.of());
            }
            AxisShiftAlternativeEvaluator axisEvaluator = new AxisShiftAlternativeEvaluator(
                    validator, obstacleRouter, networkSizer, depthPlanner, economicsCalculator);
            String[] shiftSubject = new String[1];
            RouteVariant straightened = new RouteAxisShiftControl().firstImprovement(candidate.getNodes(), assessed,
                    replacement -> {
                        RouteVariant alternative = axisEvaluator.assessPrepared(replacement, candidate.getId(), candidate.getStrategy(),
                                candidate.getConnections(), candidate.getRelevantFeatures(), candidate.getParameters(), environment);
                        if (alternative != null) shiftSubject[0] = replacement.getSubjectId();
                        return alternative;
                    });
            if (straightened != null) return Evaluation.rejected("unnecessary_axis_shift", List.of(new RouteValidationIssue(
                    "EXPERT_UNNECESSARY_AXIS_SHIFT", shiftSubject[0],
                    "Параллельные ходы можно выровнять с соблюдением всех ограничений; поперечная ступенька не требуется")), List.of());
            ensureActive();
            VariantEconomics economics = economicsCalculator.calculate(
                    candidate.getNodes(), assessed, candidate.getConnections(),
                    ExistingNetworkReconstructionResult.empty(), candidate.isReconstructionRequired());
            if (!economics.isComplete()) return Evaluation.unknown("economics_incomplete");
            BigDecimal totalLength = assessed.stream().map(RouteEdge::getLengthM)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(3, RoundingMode.HALF_UP);
            AcceptedNetworkSolution accepted = new AcceptedNetworkSolution(
                    candidate.getId(), candidate.getStrategy(), candidate.getNodes(), assessed,
                    candidate.getConnections(), totalLength, economics, originalHash);
            ensureGeometryUnchanged(originalHash, accepted.getNodes(), accepted.getEdges());
            return Evaluation.accepted(accepted);
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            return Evaluation.error("frozen_evaluator_failure", exception.getClass().getSimpleName());
        }
    }

    private Map<String, BigDecimal> demandFlows(List<RouteConnection> connections) {
        Map<String, BigDecimal> flows = new LinkedHashMap<>();
        for (RouteConnection connection : connections) {
            ensureActive();
            if (!"connected".equals(connection.getStatus())) continue;
            if (connection.getDemandId() == null || connection.getFlowTph() == null
                    || connection.getFlowTph().signum() < 0) {
                throw new IllegalArgumentException("Connected demand requires an ID and non-negative flow");
            }
            flows.merge("demand:" + connection.getDemandId(), connection.getFlowTph(), BigDecimal::add);
        }
        return flows;
    }

    private Map<String, RequiredSizing> requiredSizing(NetworkSizingResult sizing) {
        Map<String, RequiredSizing> required = new LinkedHashMap<>();
        sizing.getEdges().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            SizedNetworkEdge edge = entry.getValue();
            if (edge.getDiameter() == null || edge.getFlowTph() == null) {
                throw new IllegalStateException("Successful sizing omitted an edge assignment");
            }
            required.put(entry.getKey(), new RequiredSizing(edge.getFlowTph(), edge.getDiameter()));
        });
        return Collections.unmodifiableMap(required);
    }

    private boolean matchesRequiredSizing(List<RouteEdge> edges, Map<String, RequiredSizing> required) {
        if (edges.size() != required.size()) return false;
        for (RouteEdge edge : edges) {
            RequiredSizing expected = required.get(edge.getId());
            if (expected == null || edge.getDiameter() == null || edge.getFlowTph() == null
                    || edge.getDiameter() != expected.diameter
                    || edge.getFlowTph().compareTo(expected.flowTph) != 0) return false;
        }
        return true;
    }

    private List<RouteEdge> applySizing(List<RouteEdge> edges, Map<String, RequiredSizing> required) {
        List<RouteEdge> result = new ArrayList<>(edges.size());
        for (RouteEdge edge : edges) {
            RequiredSizing sizing = required.get(edge.getId());
            if (sizing == null) throw new IllegalStateException("Sizing omitted edge " + edge.getId());
            result.add(new RouteEdge(edge.getId(), edge.getUpstreamNodeId(), edge.getDownstreamNodeId(),
                    edge.getLengthM().doubleValue(), edge.getCoordinates(), edge.getSections(),
                    sizing.flowTph, sizing.diameter));
        }
        return List.copyOf(result);
    }

    private List<RouteEdge> depth(FrozenNetworkCandidate candidate, List<RouteEdge> edges) {
        Map<String, Set<String>> rootTieIns = new LinkedHashMap<>();
        for (RouteNode node : candidate.getNodes()) {
            if (node.isRoot() && node.getTargetId() != null) rootTieIns.put(node.getId(), Set.of(node.getTargetId()));
        }
        return depthPlanner.planNetwork(edges, candidate.getRelevantFeatures(),
                candidate.getParameters().getMinimumDepthM(), candidate.getParameters().getMaximumDepthM(),
                rootTieIns, Map.of());
    }

    private void addDepthIssues(List<RouteEdge> edges, List<RouteValidationIssue> issues) {
        for (RouteEdge edge : edges) {
            if (edge.getDepthProfile() == null || !edge.getDepthProfile().isComplete()
                    || !edge.getDepthProfile().getIssues().isEmpty()) {
                issues.add(new RouteValidationIssue("DEPTH_PROFILE_INCOMPLETE", edge.getId(),
                        "Frozen depth-enabled edge lacks a complete independently checked profile"));
            }
        }
    }

    private void ensureGeometryUnchanged(String expected, List<RouteNode> nodes, List<RouteEdge> edges) {
        String actual = FrozenNetworkCandidate.geometryHash(nodes, edges);
        if (!expected.equals(actual)) throw new IllegalStateException("Frozen evaluator changed topology or XY");
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Frozen evaluation cancelled");
    }

    public enum Outcome { ACCEPTED, CANONICAL_SIZING_REQUIREMENT, PROVEN_REJECTED, UNKNOWN, ERROR }

    /** Канонические производные sizing для одного выбранного физического ребра. */
    public static final class RequiredSizing {
        private final BigDecimal flowTph;
        private final int diameter;

        private RequiredSizing(BigDecimal flowTph, int diameter) {
            this.flowTph = flowTph;
            this.diameter = diameter;
        }

        public BigDecimal getFlowTph() { return flowTph; }
        public int getDiameter() { return diameter; }
    }

    /** Типизированный результат допуска без превращения UNKNOWN/ERROR в инженерный запрет. */
    public static final class Evaluation {
        private final Outcome outcome;
        private final AcceptedNetworkSolution accepted;
        private final Map<String, RequiredSizing> requiredSizing;
        private final List<RouteValidationIssue> validationIssues;
        private final List<NetworkSizingIssue> sizingIssues;
        private final String reason;
        private final String detail;

        private Evaluation(Outcome outcome, AcceptedNetworkSolution accepted,
                Map<String, RequiredSizing> requiredSizing, List<RouteValidationIssue> validationIssues,
                List<NetworkSizingIssue> sizingIssues, String reason, String detail) {
            this.outcome = outcome;
            this.accepted = accepted;
            this.requiredSizing = requiredSizing == null ? Map.of() : Map.copyOf(requiredSizing);
            this.validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
            this.sizingIssues = sizingIssues == null ? List.of() : List.copyOf(sizingIssues);
            this.reason = reason;
            this.detail = detail;
        }

        private static Evaluation accepted(AcceptedNetworkSolution solution) {
            return new Evaluation(Outcome.ACCEPTED, solution, null, null, null, "accepted", null);
        }

        private static Evaluation sizingRequired(Map<String, RequiredSizing> required) {
            return new Evaluation(Outcome.CANONICAL_SIZING_REQUIREMENT, null, required,
                    null, null, "canonical_sizing_required", null);
        }

        private static Evaluation rejected(String reason, List<RouteValidationIssue> validation,
                List<NetworkSizingIssue> sizing) {
            return new Evaluation(Outcome.PROVEN_REJECTED, null, null, validation, sizing, reason, null);
        }

        private static Evaluation unknown(String reason) {
            return new Evaluation(Outcome.UNKNOWN, null, null, null, null, reason, null);
        }

        private static Evaluation error(String reason, String detail) {
            return new Evaluation(Outcome.ERROR, null, null, null, null, reason, detail);
        }

        public Outcome getOutcome() { return outcome; }
        public AcceptedNetworkSolution getAccepted() { return accepted; }
        public Map<String, RequiredSizing> getRequiredSizing() { return requiredSizing; }
        public List<RouteValidationIssue> getValidationIssues() { return validationIssues; }
        public List<NetworkSizingIssue> getSizingIssues() { return sizingIssues; }
        public String getReason() { return reason; }
        public String getDetail() { return detail; }
    }
}
