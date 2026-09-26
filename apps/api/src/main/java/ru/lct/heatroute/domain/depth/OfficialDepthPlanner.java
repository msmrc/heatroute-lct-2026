package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Согласует извлечение ограничений, расчёт физических цепей и независимую проверку профилей сети.
 */
@Component
public class OfficialDepthPlanner {
    private final OfficialDepthCrossingExtractor extractor;
    private final OfficialDepthOptimizer optimizer;
    private final OfficialDepthProfileValidator validator;
    private final OfficialDepthFloorExtractor floorExtractor = new OfficialDepthFloorExtractor();

    public OfficialDepthPlanner(
            OfficialDepthCrossingExtractor extractor,
            OfficialDepthOptimizer optimizer,
            OfficialDepthProfileValidator validator) {
        this.extractor = extractor;
        this.optimizer = optimizer;
        this.validator = validator;
    }

    public DepthProfileResult plan(RouteEdge edge, List<ImportedOfficialFeature> features) {
        return plan(
                edge,
                features,
                OfficialDepthOptimizer.OFFICIAL_MINIMUM_DEPTH_M,
                OfficialDepthOptimizer.DEFAULT_MAXIMUM_DEPTH_M);
    }

    public DepthProfileResult plan(
            RouteEdge edge,
            List<ImportedOfficialFeature> features,
            BigDecimal minimumDepthM,
            BigDecimal maximumDepthM) {
        return plan(edge, features, minimumDepthM, maximumDepthM, Collections.emptySet());
    }

    /**
     * Отдельное ребро проверяется без подтверждённого источника. Старый набор ID не подтверждает
     * врезку; для такого исключения нужен planNetwork с контекстом корневых узлов.
     */
    public DepthProfileResult plan(
            RouteEdge edge,
            List<ImportedOfficialFeature> features,
            BigDecimal minimumDepthM,
            BigDecimal maximumDepthM,
            Set<String> endpointFeatureIds) {
        return planNetwork(
                        List.of(edge), features, minimumDepthM, maximumDepthM, Map.of(), Map.of())
                .get(0)
                .getDepthProfile();
    }

    /**
     * Рассчитывает физические цепи степени два, сохраняя идентификаторы и направления всех исходных
     * рёбер.
     */
    public List<RouteEdge> planNetwork(
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            BigDecimal minimum,
            BigDecimal maximum,
            Map<String, Set<String>> nodeTieIns,
            Map<String, BigDecimal> explicitPins) {
        if (edges.stream().anyMatch(edge -> edge.getDiameter() == null)) {
            List<RouteEdge> rejected = new ArrayList<>();
            for (RouteEdge edge : edges) {
                rejected.add(
                        withProfile(
                                edge,
                                incomplete(
                                        edge.getLengthM(),
                                        new DepthProfileIssue(
                                                "DEPTH_DIAMETER_MISSING",
                                                edge.getId(),
                                                "Every depth edge requires a sized diameter"))));
            }
            return rejected;
        }
        Set<String> boundaries = new HashSet<>(nodeTieIns.keySet());
        boundaries.addAll(explicitPins.keySet());
        List<DepthPhysicalChains.Chain> chains = DepthPhysicalChains.build(edges, boundaries);
        List<CriticalDepthNetworkSolver.EdgeInput> inputs = new ArrayList<>();
        Map<String, DepthCrossingExtraction> sources = new LinkedHashMap<>();
        List<DepthProfileIssue> sourceIssues = new ArrayList<>();
        for (DepthPhysicalChains.Chain chain : chains) {
            DepthCrossingExtraction source =
                    DepthNetworkAssessment.source(chain, features, nodeTieIns, extractor);
            sources.put(chain.edge.getId(), source);
            sourceIssues.addAll(source.getIssues());
            List<DepthFloorInterval> floors =
                    new ArrayList<>(
                            floorExtractor.extract(chain.edge, features, chain::storedStation));
            floors.addAll(chain.stationConstraints(minimum));
            inputs.add(
                    new CriticalDepthNetworkSolver.EdgeInput(
                            chain.edge.getId(),
                            chain.edge.getUpstreamNodeId(),
                            chain.edge.getDownstreamNodeId(),
                            chain.edge.getLengthM(),
                            chain.edge.getDiameter(),
                            source.getCrossings(),
                            floors,
                            chain.metric()));
        }
        if (!sourceIssues.isEmpty()) {
            List<RouteEdge> rejected = new ArrayList<>();
            for (RouteEdge edge : edges) {
                rejected.add(withProfile(edge, incomplete(edge.getLengthM(), sourceIssues)));
            }
            return rejected;
        }
        Map<String, DepthProfileResult> profiles =
                optimizer.optimizeNetwork(inputs, minimum, maximum, explicitPins);
        Map<String, DepthProfileResult> localProfiles = new LinkedHashMap<>();
        for (DepthPhysicalChains.Chain chain : chains) {
            for (DepthPhysicalChains.Member member : chain.members) {
                localProfiles.put(
                        member.edge.getId(),
                        DepthChainProfiles.slice(
                                profiles.get(chain.edge.getId()),
                                member,
                                DepthChainProfiles.localSources(
                                        member, sources.get(chain.edge.getId()).getCrossings())));
            }
        }
        List<RouteEdge> result = new ArrayList<>();
        for (RouteEdge edge : edges) {
            result.add(withProfile(edge, localProfiles.get(edge.getId())));
        }
        List<DepthProfileIssue> issues =
                validator
                        .networkAssessment()
                        .assess(result, features, minimum, maximum, nodeTieIns, explicitPins)
                        .getIssues();
        if (issues.isEmpty()) {
            return result;
        }
        List<RouteEdge> rejected = new ArrayList<>();
        for (RouteEdge edge : result) {
            rejected.add(withProfile(edge, rejected(edge.getDepthProfile(), issues)));
        }
        return rejected;
    }

    private DepthProfileResult rejected(DepthProfileResult profile, List<DepthProfileIssue> extra) {
        List<DepthProfileIssue> issues = new ArrayList<>(profile.getIssues());
        issues.addAll(extra);
        return new DepthProfileResult(
                false,
                profile.getPoints(),
                profile.getCrossings(),
                issues,
                profile.getProfileLength3dM(),
                profile.getDepthAdjustedCostMeters());
    }

    private RouteEdge withProfile(RouteEdge edge, DepthProfileResult profile) {
        return new RouteEdge(
                edge.getId(),
                edge.getUpstreamNodeId(),
                edge.getDownstreamNodeId(),
                edge.getLengthM().doubleValue(),
                edge.getCoordinates(),
                edge.getSections(),
                edge.getFlowTph(),
                edge.getDiameter(),
                profile);
    }

    private DepthProfileResult incomplete(BigDecimal edgeLengthM, DepthProfileIssue issue) {
        return incomplete(edgeLengthM, List.of(issue));
    }

    private DepthProfileResult incomplete(BigDecimal edgeLengthM, List<DepthProfileIssue> issues) {
        List<DepthProfileIssue> copy = new ArrayList<>(issues);
        return new DepthProfileResult(
                false,
                List.of(
                        new DepthProfilePoint(
                                BigDecimal.ZERO, OfficialDepthOptimizer.NORMAL_DEPTH_M),
                        new DepthProfilePoint(edgeLengthM, OfficialDepthOptimizer.NORMAL_DEPTH_M)),
                List.of(),
                copy,
                edgeLengthM,
                edgeLengthM);
    }
}
