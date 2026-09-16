package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Coordinates extraction, optimization and an independent verification pass for one route edge. */
@Component
public class OfficialDepthPlanner {
    private final OfficialDepthCrossingExtractor extractor;
    private final OfficialDepthOptimizer optimizer;
    private final OfficialDepthProfileValidator validator;

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
        return plan(edge, features, minimumDepthM, maximumDepthM, java.util.Collections.emptySet());
    }

    public DepthProfileResult plan(
            RouteEdge edge,
            List<ImportedOfficialFeature> features,
            BigDecimal minimumDepthM,
            BigDecimal maximumDepthM,
            Set<String> endpointFeatureIds) {
        if (edge.getDiameter() == null) {
            return incomplete(edge.getLengthM(), new DepthProfileIssue(
                    "DEPTH_DIAMETER_MISSING", edge.getId(), "Sized diameter is required for depth calculation"));
        }
        DepthCrossingExtraction extraction = extractor.extract(edge, features, endpointFeatureIds);
        if (!extraction.getIssues().isEmpty()) {
            return incomplete(edge.getLengthM(), extraction.getIssues());
        }
        DepthProfileResult optimized = optimizer.optimize(
                edge.getLengthM(),
                edge.getDiameter(),
                extraction.getCrossings(),
                minimumDepthM,
                maximumDepthM);
        if (!optimized.isComplete()) return optimized;
        List<DepthProfileIssue> validationIssues = validator.validate(
                edge.getLengthM(),
                edge.getDiameter(),
                extraction.getCrossings(),
                minimumDepthM,
                maximumDepthM,
                optimized);
        if (validationIssues.isEmpty()) return optimized;
        return new DepthProfileResult(
                false,
                optimized.getPoints(),
                optimized.getCrossings(),
                validationIssues,
                optimized.getProfileLength3dM(),
                optimized.getDepthAdjustedCostMeters());
    }

    private DepthProfileResult incomplete(BigDecimal edgeLengthM, DepthProfileIssue issue) {
        return incomplete(edgeLengthM, List.of(issue));
    }

    private DepthProfileResult incomplete(BigDecimal edgeLengthM, List<DepthProfileIssue> issues) {
        List<DepthProfileIssue> copy = new ArrayList<>(issues);
        return new DepthProfileResult(
                false,
                List.of(
                        new DepthProfilePoint(BigDecimal.ZERO, OfficialDepthOptimizer.NORMAL_DEPTH_M),
                        new DepthProfilePoint(edgeLengthM, OfficialDepthOptimizer.NORMAL_DEPTH_M)),
                List.of(),
                copy,
                edgeLengthM,
                edgeLengthM);
    }
}
