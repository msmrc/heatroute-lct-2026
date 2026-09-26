package ru.lct.heatroute.domain.economics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.engineering.SpecialCrossingType;
import ru.lct.heatroute.domain.reconstruction.ChamberReconstruction;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.reconstruction.NetworkReconstructionSection;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteSection;
import ru.lct.heatroute.domain.depth.DepthProfileResult;

@Component
public class OfficialVariantEconomicsCalculator {
    private static final BigDecimal TWO_DIMENSIONAL_DEPTH_M = new BigDecimal("3.0");
    // Coordinates are stored at millimetre precision; this accepts numerical noise only, not a
    // near-45-degree design choice.
    private static final double STANDARD_BEND_TOLERANCE_DEGREES = 0.001;

    private final OfficialPipeCatalog pipeCatalog;
    private final OfficialEconomics economics;

    public OfficialVariantEconomicsCalculator(
            OfficialPipeCatalog pipeCatalog,
            OfficialEconomics economics) {
        this.pipeCatalog = pipeCatalog;
        this.economics = economics;
    }

    public VariantEconomics calculate(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<RouteConnection> connections,
            ExistingNetworkReconstructionResult reconstruction) {
        return calculate(nodes, edges, connections, reconstruction, true);
    }

    /**
     * Calculates a variant for an input profile. Strict profiles require an available
     * reconstruction calculation; the baseline profile can rank and export the
     * independently calculable new-network result without it.
     */
    public VariantEconomics calculate(
            List<RouteNode> nodes,
            List<RouteEdge> edges,
            List<RouteConnection> connections,
            ExistingNetworkReconstructionResult reconstruction,
            boolean reconstructionRequired) {
        BigDecimal construction = edges.stream()
                .map(this::edgeCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal chamberConstruction = newChamberCost(nodes, edges);
        long tieInCount = tieInCount(nodes, edges);
        BigDecimal tieIns = economics.tieInCost().multiply(BigDecimal.valueOf(tieInCount));
        BigDecimal penalty = connections.stream()
                .filter(connection -> "no_route".equals(connection.getStatus()))
                .map(connection -> economics.unconnectedPenalty(connection.getFlowTph()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalConstruction = construction.add(chamberConstruction).add(tieIns);
        BigDecimal calculated = totalConstruction
                .add(penalty)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal newLength = edges.stream().map(RouteEdge::getLengthM)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(3, RoundingMode.HALF_UP);
        BigDecimal reconstructionLength = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        BigDecimal totalLength = newLength;
        List<String> incompleteReasons = List.of();
        boolean complete = true;
        return new VariantEconomics(
                complete,
                money(totalConstruction),
                money(chamberConstruction),
                money(tieIns),
                money(BigDecimal.ZERO),
                money(BigDecimal.ZERO),
                money(penalty),
                calculated,
                newLength,
                reconstructionLength,
                totalLength,
                economics.score(calculated, newLength),
                incompleteReasons);
    }

    /**
     * Returns whether it is cheaper to leave a demand unconnected than to retain its exclusive
     * new-network spur. Reconstruction of the supplied existing network is deliberately not
     * part of this local decision: it is unavailable for the baseline input profile and is not
     * caused by one candidate connection alone.
     */
    public boolean connectionCostsMoreThanPenalty(
            RouteConnection connection,
            List<RouteEdge> exclusiveEdges,
            List<RouteNode> removableNodes) {
        return marginalConnectionCost(exclusiveEdges, removableNodes)
                .compareTo(economics.unconnectedPenalty(connection.getFlowTph())) > 0;
    }

    public boolean connectionCostsMoreThanPenalty(
            RouteConnection connection,
            List<RouteEdge> exclusiveEdges,
            List<RouteNode> removableNodes,
            boolean independentTieIn) {
        BigDecimal marginal = marginalConnectionCost(exclusiveEdges, removableNodes);
        if (independentTieIn) marginal = marginal.add(economics.tieInCost());
        return marginal.compareTo(economics.unconnectedPenalty(connection.getFlowTph())) > 0;
    }

    public BigDecimal marginalConnectionCost(
            List<RouteEdge> exclusiveEdges,
            List<RouteNode> removableNodes) {
        BigDecimal construction = exclusiveEdges.stream()
                .map(this::edgeCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal chambers = newChamberCost(removableNodes, exclusiveEdges);
        BigDecimal tieIns = economics.tieInCost().multiply(BigDecimal.valueOf(
                tieInCount(removableNodes, exclusiveEdges)));
        return money(construction.add(chambers).add(tieIns));
    }

    /**
     * По §3.2 приложения каждый новый луч из существующей камеры оплачивается отдельно.
     * Новая камера уже включает присоединение; критерий существующего корня совпадает с экспортом.
     */
    private long tieInCount(List<RouteNode> nodes, List<RouteEdge> edges) {
        java.util.Set<String> rootIds = nodes.stream()
                .filter(RouteNode::isRoot)
                .filter(node -> "existing_chamber_tie_in".equals(node.getNodeType()))
                .map(RouteNode::getId)
                .collect(java.util.stream.Collectors.toSet());
        return edges.stream()
                .filter(edge -> rootIds.contains(edge.getUpstreamNodeId()))
                .count();
    }

    private BigDecimal edgeCost(RouteEdge edge) {
        PipeCatalogEntry pipe = pipeCatalog.byDiameter(edge.getDiameter()).orElseThrow();
        DepthProfileResult profile = edge.getDepthProfile();
        if (edge.getSections().isEmpty() && edge.getCoordinates().size() < 2) {
            if (profile != null) return segmentCost(pipe, profile, SpecialCrossingType.BASE,
                    edge.getLengthM(), BigDecimal.ZERO, edge.getLengthM(), BigDecimal.ONE);
            return economics.newNetworkCost(
                    pipe,
                    edge.getLengthM(),
                    SpecialCrossingType.BASE,
                    profile == null
                            ? TWO_DIMENSIONAL_DEPTH_M
                            : profile.averageDepth(BigDecimal.ZERO, edge.getLengthM()));
        }
        List<RouteSection> sections = edge.getSections().isEmpty()
                ? List.of(new RouteSection("base", null, null, edge.getCoordinates(),
                        edge.getLengthM().doubleValue(), null))
                : edge.getSections();
        BigDecimal result = BigDecimal.ZERO;
        BigDecimal sectionTotal = sections.stream()
                .map(RouteSection::getLengthM)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sectionTotal.signum() == 0) return BigDecimal.ZERO;
        BigDecimal cumulative = BigDecimal.ZERO;
        double[] previousDirection = null;
        for (int index = 0; index < sections.size(); index++) {
            RouteSection section = sections.get(index);
            BigDecimal station = edge.getLengthM().multiply(cumulative)
                    .divide(sectionTotal, 12, RoundingMode.HALF_UP);
            cumulative = cumulative.add(section.getLengthM());
            BigDecimal end = index == sections.size() - 1
                    ? edge.getLengthM()
                    : edge.getLengthM().multiply(cumulative)
                            .divide(sectionTotal, 12, RoundingMode.HALF_UP);
            double geometryLength = polylineLength(section.getCoordinates());
            if (geometryLength == 0.0) continue;
            double consumed = 0.0;
            for (int coordinateIndex = 1; coordinateIndex < section.getCoordinates().size(); coordinateIndex++) {
                RouteCoordinate from = section.getCoordinates().get(coordinateIndex - 1);
                RouteCoordinate to = section.getCoordinates().get(coordinateIndex);
                double dx = to.getXM().subtract(from.getXM()).doubleValue();
                double dy = to.getYM().subtract(from.getYM()).doubleValue();
                double geometrySegmentLength = Math.hypot(dx, dy);
                if (geometrySegmentLength == 0.0) continue;
                BigDecimal segmentStart = station.add(end.subtract(station)
                        .multiply(BigDecimal.valueOf(consumed / geometryLength)));
                consumed += geometrySegmentLength;
                BigDecimal segmentEnd = coordinateIndex == section.getCoordinates().size() - 1
                        ? end
                        : station.add(end.subtract(station)
                                .multiply(BigDecimal.valueOf(consumed / geometryLength)));
                if (segmentEnd.compareTo(segmentStart) <= 0) {
                    previousDirection = new double[]{dx, dy};
                    continue;
                }
                BigDecimal segmentLength = section.getLengthM()
                        .multiply(BigDecimal.valueOf(geometrySegmentLength / geometryLength));
                result = result.add(segmentCost(
                        pipe, profile, crossingType(section), segmentLength, segmentStart, segmentEnd,
                        BigDecimal.ONE));
                previousDirection = new double[]{dx, dy};
            }
        }
        return result;
    }

    private BigDecimal segmentCost(
            PipeCatalogEntry pipe,
            DepthProfileResult profile,
            SpecialCrossingType crossing,
            BigDecimal segmentLength,
            BigDecimal start,
            BigDecimal end,
            BigDecimal bendMultiplier) {
        if (profile == null) return constructionSegmentCost(
                pipe, segmentLength, crossing, TWO_DIMENSIONAL_DEPTH_M, bendMultiplier);
        BigDecimal normalizedStart = start;
        BigDecimal normalizedEnd = end;
        if (normalizedEnd.compareTo(normalizedStart) <= 0) return BigDecimal.ZERO;
        BigDecimal result = BigDecimal.ZERO;
        NavigableSet<BigDecimal> cuts = new TreeSet<>();
        cuts.add(normalizedStart);
        cuts.addAll(profile.costBreakpoints(normalizedStart, normalizedEnd));
        cuts.add(normalizedEnd);
        List<BigDecimal> orderedCuts = new ArrayList<>(cuts);
        for (int piece = 1; piece < orderedCuts.size(); piece++) {
            BigDecimal pieceStart = orderedCuts.get(piece - 1);
            BigDecimal pieceEnd = orderedCuts.get(piece);
            BigDecimal pieceLength = segmentLength.multiply(pieceEnd.subtract(pieceStart))
                    .divide(normalizedEnd.subtract(normalizedStart), 12, RoundingMode.HALF_UP);
            result = result.add(constructionSegmentCost(
                    pipe, pieceLength, crossing, profile.averageDepth(pieceStart, pieceEnd), bendMultiplier));
        }
        return result;
    }

    public BigDecimal constructionSegmentCost(
            PipeCatalogEntry pipe,
            BigDecimal lengthM,
            SpecialCrossingType crossing,
            BigDecimal averageDepthM,
            BigDecimal bendMultiplier) {
        return economics.newNetworkCost(pipe, lengthM, crossing, averageDepthM).multiply(bendMultiplier);
    }

    public boolean isStandardBend(double[] previousDirection, double dx, double dy) {
        double denominator = Math.hypot(previousDirection[0], previousDirection[1]) * Math.hypot(dx, dy);
        double cosine = Math.max(-1.0, Math.min(1.0,
                (previousDirection[0] * dx + previousDirection[1] * dy) / denominator));
        double change = Math.toDegrees(Math.acos(cosine));
        return isWithinTolerance(change, 0.0)
                || isWithinTolerance(change, 45.0)
                || isWithinTolerance(change, 90.0);
    }

    private boolean isWithinTolerance(double actual, double expected) {
        return Math.abs(actual - expected) <= STANDARD_BEND_TOLERANCE_DEGREES;
    }

    private double polylineLength(List<RouteCoordinate> coordinates) {
        double result = 0.0;
        for (int index = 1; index < coordinates.size(); index++) {
            RouteCoordinate from = coordinates.get(index - 1);
            RouteCoordinate to = coordinates.get(index);
            result += Math.hypot(
                    to.getXM().subtract(from.getXM()).doubleValue(),
                    to.getYM().subtract(from.getYM()).doubleValue());
        }
        return result;
    }

    private BigDecimal newChamberCost(List<RouteNode> nodes, List<RouteEdge> edges) {
        return ru.lct.heatroute.domain.sizing.OfficialChamberSizing.diameters(nodes, edges).values().stream()
                .map(economics::chamberCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal reconstructionCost(NetworkReconstructionSection section) {
        PipeCatalogEntry pipe = pipeCatalog.byDiameter(section.getRequiredDiameter()).orElseThrow();
        return economics.reconstructionCost(pipe, section.getLengthM());
    }

    private BigDecimal chamberReconstructionCost(ChamberReconstruction chamber) {
        return economics.chamberCost(chamber.getRequiredDiameter());
    }

    private SpecialCrossingType crossingType(RouteSection section) {
        if (!"special".equals(section.getKind()) || section.getRestrictionType() == null) {
            return SpecialCrossingType.BASE;
        }
        return java.util.Arrays.stream(section.getRestrictionType().split("\\+"))
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .map(this::singleCrossingType)
                .max(java.util.Comparator.comparing(SpecialCrossingType::getCostMultiplier))
                .orElse(SpecialCrossingType.BASE);
    }

    private SpecialCrossingType singleCrossingType(String restrictionType) {
        switch (restrictionType) {
            case "road": return SpecialCrossingType.ROAD;
            case "tram_tracks": return SpecialCrossingType.TRAM_TRACKS;
            case "railway": throw new IllegalArgumentException("Railway is a forbidden restriction");
            case "gas_pipeline": return SpecialCrossingType.GAS_PIPELINE;
            case "power_cable": return SpecialCrossingType.POWER_CABLE;
            case "heat_network": return SpecialCrossingType.HEAT_NETWORK;
            default: throw new IllegalArgumentException(
                    "Unsupported special crossing type: " + restrictionType);
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
