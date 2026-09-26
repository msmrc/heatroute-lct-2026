package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Независимый допуск физического профиля через технические узлы, включая сохранённые рёбра DTO. */
public final class DepthNetworkAssessment {
    private final OfficialDepthCrossingExtractor extractor;
    private final OfficialDepthFloorExtractor floors = new OfficialDepthFloorExtractor();
    private final ContinuousDepthProfileValidator validator;

    public DepthNetworkAssessment(OfficialPipeCatalog pipes) {
        extractor = new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes);
        validator = new ContinuousDepthProfileValidator(pipes);
    }

    public Result assess(List<RouteEdge> edges, List<ImportedOfficialFeature> features,
            BigDecimal min, BigDecimal max, Map<String, Set<String>> nodeTieIns, Map<String, BigDecimal> explicitPins) {
        List<DepthProfileIssue> issues = new ArrayList<>(validator.validateContinuity(edges, explicitPins));
        Map<String, List<DepthCrossing>> sourcesByEdge = new LinkedHashMap<>();
        if (!issues.isEmpty()) return new Result(issues, sourcesByEdge);
        Set<String> boundaries = new HashSet<>(nodeTieIns.keySet()); boundaries.addAll(explicitPins.keySet());
        for (DepthPhysicalChains.Chain chain : DepthPhysicalChains.build(edges, boundaries)) {
            DepthCrossingExtraction source = source(chain, features, nodeTieIns, extractor);
            issues.addAll(source.getIssues());
            for (DepthPhysicalChains.Member member : chain.members) {
                List<DepthCrossing> localSources = DepthChainProfiles.localSources(member, source.getCrossings());
                sourcesByEdge.put(member.edge.getId(), localSources);
                physicalSlopes(member.edge, issues);
                DepthProfileResult p = member.edge.getDepthProfile();
                DepthProfileResult withoutDecisions = new DepthProfileResult(p.isComplete(), p.getPoints(), List.of(), p.getIssues(),
                        p.getProfileLength3dM(), p.getDepthAdjustedCostMeters());
                issues.addAll(validator.validate(member.edge.getLengthM(), member.edge.getDiameter(), List.of(), min, max,
                        withoutDecisions, floors.extract(member.edge, features)));
                localDecisions(member, source.getCrossings(), localSources, issues);
            }
            try {
                DepthProfileResult assembled = DepthChainProfiles.join(chain);
                issues.addAll(validator.validate(chain.edge.getLengthM(), chain.edge.getDiameter(), source.getCrossings(),
                        min, max, assembled, floors.extract(chain.edge, features, chain::storedStation)));
            } catch (IllegalArgumentException invalid) {
                issues.add(issue("CROSSING_CHAIN_DECISION_MISMATCH", chain.edge.getId(), invalid.getMessage()));
            }
        }
        return new Result(issues, sourcesByEdge);
    }

    static DepthCrossingExtraction source(DepthPhysicalChains.Chain chain, List<ImportedOfficialFeature> features,
            Map<String, Set<String>> nodeTieIns, OfficialDepthCrossingExtractor extractor) {
        List<ru.lct.heatroute.domain.routing.RouteCoordinate> coordinates = chain.edge.getCoordinates();
        if (coordinates.isEmpty()) return extractor.extractPhysical(chain.edge, features, Set.of(), Set.of());
        Set<String> upstream = DepthSourceTieIns.heatIds(nodeTieIns.getOrDefault(chain.edge.getUpstreamNodeId(), Set.of()),
                coordinates.get(0), features);
        Set<String> downstream = DepthSourceTieIns.heatIds(nodeTieIns.getOrDefault(chain.edge.getDownstreamNodeId(), Set.of()),
                coordinates.get(coordinates.size() - 1), features);
        return extractor.extractPhysical(chain.edge, features, upstream, downstream, chain::storedStation);
    }

    /** Проверка по XY рёбер не использует метрическую карту построителя профиля. */
    private void physicalSlopes(RouteEdge edge, List<DepthProfileIssue> issues) {
        BigDecimal physicalLength = BigDecimal.ZERO;
        var coordinates = edge.getCoordinates();
        for (int i = 1; i < coordinates.size(); i++) {
            BigDecimal dx = coordinates.get(i).getXM().subtract(coordinates.get(i - 1).getXM());
            BigDecimal dy = coordinates.get(i).getYM().subtract(coordinates.get(i - 1).getYM());
            physicalLength = physicalLength.add(BigDecimal.valueOf(Math.hypot(dx.doubleValue(), dy.doubleValue())));
        }
        if (physicalLength.signum() <= 0 || edge.getLengthM().signum() <= 0) {
            issues.add(issue("PROFILE_PHYSICAL_LENGTH_INVALID", edge.getId(), "Physical XY length is required for slope validation"));
            return;
        }
        List<DepthProfilePoint> points = edge.getDepthProfile().getPoints();
        for (int i = 1; i < points.size(); i++) {
            BigDecimal run = points.get(i).getStationM().subtract(points.get(i - 1).getStationM());
            BigDecimal rise = points.get(i).getDepthM().subtract(points.get(i - 1).getDepthM()).abs();
            if (rise.multiply(edge.getLengthM()).compareTo(run.multiply(physicalLength).multiply(new BigDecimal(".1"))) > 0)
                issues.add(issue("PROFILE_PHYSICAL_SLOPE_EXCEEDED", edge.getId(), "Profile slope exceeds0.10 over actual horizontal XY"));
        }
    }

    private void localDecisions(DepthPhysicalChains.Member member, List<DepthCrossing> global,
            List<DepthCrossing> local, List<DepthProfileIssue> issues) {
        Map<String, DepthCrossing> actual = new HashMap<>(), globalById = new HashMap<>();
        local.forEach(c -> actual.put(c.getId(), c)); global.forEach(c -> globalById.put(c.getId(), c));
        Set<String> seen = new HashSet<>();
        for (DepthCrossingDecision decision : member.edge.getDepthProfile().getCrossings()) {
            DepthCrossing source = actual.get(decision.getCrossingId());
            if (source == null || !seen.add(decision.getCrossingId()) || !source.getType().equals(decision.getCrossingType())
                    || source.getMinimumVerticalClearanceM().compareTo(decision.getRequiredClearanceM()) != 0) {
                issues.add(issue("CROSSING_SOURCE_METADATA_MISMATCH", decision.getCrossingId(), "Source/clip decision mismatch"));
                continue;
            }
            DepthCrossing globalSource = globalById.get(decision.getCrossingId());
            BigDecimal a = globalSource.getPlateauStartM().max(member.start);
            BigDecimal b = globalSource.getPlateauEndM().min(member.end);
            BigDecimal requiredStart = member.local(a).min(member.local(b)), requiredEnd = member.local(a).max(member.local(b));
            if (decision.getRampStartM().signum() < 0 || decision.getRampEndM().compareTo(member.edge.getLengthM()) > 0
                    || decision.getRampStartM().compareTo(decision.getPlateauStartM()) > 0
                    || decision.getPlateauStartM().compareTo(decision.getPlateauEndM()) >= 0
                    || decision.getPlateauEndM().compareTo(decision.getRampEndM()) > 0
                    || decision.getPlateauStartM().compareTo(requiredStart) > 0
                    || decision.getPlateauEndM().compareTo(requiredEnd) < 0)
                issues.add(issue("CROSSING_CLIP_INTERVAL_INVALID", decision.getCrossingId(), "Clipped plateau must cover actual continuation"));
        }
        for (String id : actual.keySet()) if (!seen.contains(id)) issues.add(issue(
                "CROSSING_DECISION_MISSING", id, "Every participating edge requires its physical plateau clip"));
    }

    private DepthProfileIssue issue(String code, String id, String message) { return new DepthProfileIssue(code, id, message); }

    public static final class Result {
        private final List<DepthProfileIssue> issues;
        private final Map<String, List<DepthCrossing>> crossingsByEdge;
        Result(List<DepthProfileIssue> issues, Map<String, List<DepthCrossing>> crossingsByEdge) {
            this.issues = List.copyOf(issues); this.crossingsByEdge = Map.copyOf(crossingsByEdge);
        }
        public List<DepthProfileIssue> getIssues() { return issues; }
        public Map<String, List<DepthCrossing>> getCrossingsByEdge() { return crossingsByEdge; }
    }
}
