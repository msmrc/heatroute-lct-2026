package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;

/**
 * Точный снимок направленной сети для посещённых состояний одного поиска, без округления и хеш-потерь.
 * Не учитывает порядок узлов/рёбер/потребителей, ID варианта/рёбер и имена однозначных внутренних
 * новых камер; неоднозначные узлы, незамкнутые ссылки и превышение бюджета оставляет без ключа.
 */
final class CorridorNetworkIdentity {
    // Это предел работы над ключом, не предел входа или поиска: без ключа дедупликация пропускается.
    private static final int MAX_ENCODED_CHARACTERS = 1_000_000;
    private int remainingCharacters = MAX_ENCODED_CHARACTERS;

    private CorridorNetworkIdentity() { }

    static Optional<String> of(RouteVariant variant) {
        ensureActive();
        if (variant == null || !variant.isValid() || !variant.getEngineeringIssues().isEmpty()) return Optional.empty();
        try {
            return new CorridorNetworkIdentity().network(variant);
        } catch (IdentityBudgetExceeded exceeded) {
            return Optional.empty();
        }
    }

    private Optional<String> network(RouteVariant variant) {
        Map<String, String> nodeKeys = new HashMap<>();
        Set<String> uniqueNodes = new HashSet<>();
        for (RouteNode node : variant.getNodes()) {
            ensureActive();
            if (node == null || node.getId() == null || node.getCoordinate() == null) return Optional.empty();
            boolean anonymous = "new_branch_chamber".equals(node.getNodeType()) && !node.isRoot()
                    && node.getTargetId() == null;
            String key = tuple(anonymous ? null : node.getId(), node.getNodeType(), coordinate(node.getCoordinate()),
                    node.isChamber(), node.isRoot(), node.getBaseIncidentSections(), node.getTargetId(),
                    node.getExistingIncidentDiameter());
            // Уникальная метка задаёт биекцию узлов. При совпадении нельзя заменять граф набором точек.
            if (nodeKeys.putIfAbsent(node.getId(), key) != null || !uniqueNodes.add(key)) return Optional.empty();
        }
        Set<String> edgeIds = new HashSet<>();
        List<String> edgeKeys = new ArrayList<>();
        for (RouteEdge edge : variant.getEdges()) {
            ensureActive();
            if (edge == null || edge.getId() == null || !edgeIds.add(edge.getId())
                    || !nodeKeys.containsKey(edge.getUpstreamNodeId())
                    || !nodeKeys.containsKey(edge.getDownstreamNodeId())) return Optional.empty();
            edgeKeys.add(tuple(nodeKeys.get(edge.getUpstreamNodeId()), nodeKeys.get(edge.getDownstreamNodeId()),
                    edge.getLengthM(), coordinates(edge.getCoordinates()), edge.getFlowTph(), edge.getDiameter(),
                    sequence(edge.getSections(), section -> tuple(section.getKind(), section.getRestrictionType(),
                            section.getRestrictionId(), coordinates(section.getCoordinates()), section.getLengthM(),
                            section.getCrossingAngleDegrees())), depth(edge.getDepthProfile())));
        }
        List<String> connections = new ArrayList<>();
        for (RouteConnection connection : variant.getConnections()) {
            if (connection == null) return Optional.empty();
            connections.add(tuple(connection.getDemandId(), connection.getConnectionPointId(), connection.getFlowTph(),
                    connection.getStatus(), connection.getReason(), diagnostics(connection.getDiagnostics())));
        }
        return Optional.of(tuple(sorted(new ArrayList<>(uniqueNodes)), sorted(edgeKeys), sorted(connections),
                variant.getStrategy(), variant.getTotalLengthM(), economics(variant.getEconomics()),
                reconstruction(variant.getReconstruction())));
    }

    private String depth(DepthProfileResult profile) {
        if (profile == null) return null;
        return tuple(profile.isComplete(), profile.getProfileLength3dM(), profile.getDepthAdjustedCostMeters(),
                sequence(profile.getPoints(), p -> tuple(p.getStationM(), p.getDepthM())),
                sequence(profile.getCrossings(), c -> tuple(c.getCrossingId(), c.getCrossingType(), c.getPassage(),
                        c.getDepthM(), c.getRampStartM(), c.getPlateauStartM(), c.getPlateauEndM(), c.getRampEndM(),
                        c.getVerticalClearanceM(), c.getRequiredClearanceM())),
                sequence(profile.getIssues(), i -> tuple(i.getCode(), i.getCrossingId(), i.getMessage())));
    }

    private String economics(VariantEconomics economics) {
        if (economics == null) return null;
        // Разная оценка не считается дубликатом даже при одинаковой 2D-сети.
        return tuple(economics.isComplete(), economics.getConstructionCost(), economics.getChamberConstructionCost(),
                economics.getTieInCost(), economics.getReconstructionCost(), economics.getChamberReconstructionCost(),
                economics.getUnconnectedPenalty(), economics.getCalculatedCost(), economics.getNewNetworkLength(),
                economics.getReconstructionLength(), economics.getLength(), economics.getScore(),
                sequence(economics.getIncompleteReasons(), Function.identity()));
    }

    private String reconstruction(ExistingNetworkReconstructionResult reconstruction) {
        if (reconstruction == null) return null;
        return tuple(sequence(reconstruction.getNetworkSections(), s -> tuple(s.getId(), s.getExistingFeatureId(),
                        coordinates(s.getCoordinates()), s.getLengthM(), s.getExistingFlowTph(), s.getAddedFlowTph(),
                        s.getResultingFlowTph(), s.getExistingDiameter(), s.getRequiredDiameter(), s.isPartial())),
                sequence(reconstruction.getChambers(), c -> tuple(c.getExistingFeatureId(), coordinate(c.getCoordinate()),
                        c.getAddedFlowTph(), c.getResultingFlowTph(), c.getExistingDiameter(), c.getRequiredDiameter())),
                sequence(reconstruction.getIssues(), i -> tuple(i.getCode(), i.getSubjectId(), i.getMessage())));
    }

    private String diagnostics(RouteFailureDiagnostics diagnostics) {
        if (diagnostics == null) return null;
        return tuple(diagnostics.getCandidateCount(), diagnostics.getAttemptedCandidateCount(),
                sequence(diagnostics.getAttemptedTargetIds(), Function.identity()),
                sequence(diagnostics.getDirectBlockers(), Function.identity()), diagnostics.getMaximumSearchCorridorM());
    }

    private String coordinate(RouteCoordinate coordinate) {
        return coordinate == null ? null : tuple(coordinate.getXM(), coordinate.getYM());
    }

    private String coordinates(List<RouteCoordinate> coordinates) {
        // Направление полилинии и порядок её вершин сохраняются, даже если они коллинеарны.
        return sequence(coordinates, this::coordinate);
    }

    private <T> String sequence(List<T> values, Function<T, String> encode) {
        StringBuilder result = new StringBuilder();
        for (T value : values) append(result, value == null ? null : encode.apply(value));
        return result.toString();
    }

    private String sorted(List<String> values) {
        ensureActive();
        values.sort(String::compareTo);
        return sequence(values, Function.identity());
    }

    private String tuple(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) append(result, value == null ? null : value.toString());
        return result.toString();
    }

    private void append(StringBuilder result, String value) {
        ensureActive();
        int length = value == null ? 0 : value.length();
        // Считаем также вложенные копии: память на точные ключи ограничена без потери равенства.
        if (length > remainingCharacters - 12) throw new IdentityBudgetExceeded();
        remainingCharacters -= length + 12;
        if (value == null) result.append("-;");
        else result.append(length).append(':').append(value);
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor identity cancelled");
    }

    private static final class IdentityBudgetExceeded extends RuntimeException {
        private IdentityBudgetExceeded() { super(null, null, false, false); }
    }
}
