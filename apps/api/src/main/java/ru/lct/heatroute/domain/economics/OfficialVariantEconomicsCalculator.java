package ru.lct.heatroute.domain.economics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.engineering.SpecialCrossingType;
import ru.lct.heatroute.domain.reconstruction.ChamberReconstruction;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.reconstruction.NetworkReconstructionSection;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteSection;
import ru.lct.heatroute.domain.depth.DepthProfileResult;

@Component
public class OfficialVariantEconomicsCalculator {
    private static final BigDecimal TWO_DIMENSIONAL_DEPTH_M = new BigDecimal("3.0");

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
        BigDecimal construction = edges.stream()
                .map(this::edgeCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal chamberConstruction = newChamberCost(nodes, edges);
        long tieInCount = nodes.stream().filter(RouteNode::isRoot).count();
        BigDecimal tieIns = economics.tieInCost().multiply(BigDecimal.valueOf(tieInCount));
        BigDecimal reconstructionCost = reconstruction.getNetworkSections().stream()
                .map(this::reconstructionCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal chamberReconstruction = reconstruction.getChambers().stream()
                .map(this::chamberReconstructionCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal penalty = connections.stream()
                .filter(connection -> "no_route".equals(connection.getStatus()))
                .map(connection -> economics.unconnectedPenalty(connection.getFlowTph()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal calculated = construction
                .add(chamberConstruction)
                .add(tieIns)
                .add(reconstructionCost)
                .add(chamberReconstruction)
                .add(penalty)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal newLength = edges.stream().map(RouteEdge::getLengthM)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(3, RoundingMode.HALF_UP);
        BigDecimal reconstructionLength = reconstruction.getNetworkSections().stream()
                .map(NetworkReconstructionSection::getLengthM)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(3, RoundingMode.HALF_UP);
        BigDecimal totalLength = newLength.add(reconstructionLength).setScale(3, RoundingMode.HALF_UP);
        boolean complete = reconstruction.isAvailable();
        List<String> incompleteReasons = new ArrayList<>();
        if (!complete) {
            incompleteReasons.add("RECONSTRUCTION_INPUT_UNAVAILABLE");
        }
        return new VariantEconomics(
                complete,
                money(construction),
                money(chamberConstruction),
                money(tieIns),
                money(reconstructionCost),
                money(chamberReconstruction),
                money(penalty),
                calculated,
                newLength,
                reconstructionLength,
                totalLength,
                complete ? economics.score(calculated, totalLength) : null,
                incompleteReasons);
    }

    private BigDecimal edgeCost(RouteEdge edge) {
        PipeCatalogEntry pipe = pipeCatalog.byDiameter(edge.getDiameter()).orElseThrow();
        DepthProfileResult profile = edge.getDepthProfile();
        if (edge.getSections().isEmpty()) {
            return economics.newNetworkCost(
                    pipe,
                    edge.getLengthM(),
                    SpecialCrossingType.BASE,
                    profile == null
                            ? TWO_DIMENSIONAL_DEPTH_M
                            : profile.averageDepth(BigDecimal.ZERO, edge.getLengthM()));
        }
        BigDecimal result = BigDecimal.ZERO;
        BigDecimal sectionTotal = edge.getSections().stream()
                .map(RouteSection::getLengthM)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int index = 0; index < edge.getSections().size(); index++) {
            RouteSection section = edge.getSections().get(index);
            BigDecimal station = edge.getLengthM().multiply(cumulative)
                    .divide(sectionTotal, 12, RoundingMode.HALF_UP);
            cumulative = cumulative.add(section.getLengthM());
            BigDecimal end = index == edge.getSections().size() - 1
                    ? edge.getLengthM()
                    : edge.getLengthM().multiply(cumulative)
                            .divide(sectionTotal, 12, RoundingMode.HALF_UP);
            if (profile == null) {
                result = result.add(economics.newNetworkCost(
                        pipe,
                        section.getLengthM(),
                        crossingType(section),
                        TWO_DIMENSIONAL_DEPTH_M));
                continue;
            }
            List<BigDecimal> cuts = new ArrayList<>();
            cuts.add(station);
            profile.getPoints().stream()
                    .map(point -> point.getStationM())
                    .filter(point -> point.compareTo(station) > 0 && point.compareTo(end) < 0)
                    .forEach(cuts::add);
            cuts.add(end);
            for (int piece = 1; piece < cuts.size(); piece++) {
                BigDecimal pieceStart = cuts.get(piece - 1);
                BigDecimal pieceEnd = cuts.get(piece);
                BigDecimal pieceLength = section.getLengthM()
                        .multiply(pieceEnd.subtract(pieceStart))
                        .divide(end.subtract(station), 12, RoundingMode.HALF_UP);
                result = result.add(economics.newNetworkCost(
                        pipe,
                        pieceLength,
                        crossingType(section),
                        profile.averageDepth(pieceStart, pieceEnd)));
            }
        }
        return result;
    }

    private BigDecimal newChamberCost(List<RouteNode> nodes, List<RouteEdge> edges) {
        Map<String, Integer> maximumDiameterByNode = new HashMap<>();
        for (RouteEdge edge : edges) {
            maximumDiameterByNode.merge(edge.getUpstreamNodeId(), edge.getDiameter(), Math::max);
            maximumDiameterByNode.merge(edge.getDownstreamNodeId(), edge.getDiameter(), Math::max);
        }
        return nodes.stream()
                .filter(RouteNode::isChamber)
                .filter(node -> node.getNodeType().startsWith("new_"))
                .map(node -> economics.chamberCost(maximumDiameterByNode.get(node.getId())))
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
        switch (section.getRestrictionType()) {
            case "road": return SpecialCrossingType.ROAD;
            case "tram_tracks": return SpecialCrossingType.TRAM_TRACKS;
            case "gas_pipeline": return SpecialCrossingType.GAS_PIPELINE;
            case "power_cable": return SpecialCrossingType.POWER_CABLE;
            case "heat_network": return SpecialCrossingType.HEAT_NETWORK;
            default: throw new IllegalArgumentException(
                    "Unsupported special crossing type: " + section.getRestrictionType());
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
