package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.routing.RouteEdge;

/** Независимая проверка диапазона глубин, уклонов и постоянных участков дополнительного режима. */
public class ContinuousDepthProfileValidator {
    private static final BigDecimal EPSILON = new BigDecimal("0.000001");
    private final OfficialPipeCatalog pipeCatalog;

    public ContinuousDepthProfileValidator(OfficialPipeCatalog pipeCatalog) {
        this.pipeCatalog = pipeCatalog;
    }

    public List<DepthProfileIssue> validate(
            BigDecimal edgeLengthM,
            int diameter,
            List<DepthCrossing> crossings,
            BigDecimal minimumDepthM,
            BigDecimal maximumDepthM,
            DepthProfileResult result) {
        return validate(
                edgeLengthM, diameter, crossings, minimumDepthM, maximumDepthM, result, List.of());
    }

    public List<DepthProfileIssue> validate(
            BigDecimal edgeLengthM,
            int diameter,
            List<DepthCrossing> crossings,
            BigDecimal minimumDepthM,
            BigDecimal maximumDepthM,
            DepthProfileResult result,
            List<DepthFloorInterval> floors) {
        List<DepthProfileIssue> issues = new ArrayList<>();
        PipeCatalogEntry pipe = pipeCatalog.byDiameter(diameter).orElse(null);
        if (pipe == null) {
            issues.add(
                    issue(
                            "UNKNOWN_DIAMETER",
                            null,
                            "Profile diameter is not in the official catalog"));
            return issues;
        }
        List<DepthProfilePoint> points = result.getPoints();
        if (points.size() < 2
                || points.get(0).getStationM().compareTo(BigDecimal.ZERO) != 0
                || points.get(points.size() - 1)
                                .getStationM()
                                .compareTo(edgeLengthM.setScale(3, RoundingMode.HALF_UP))
                        != 0) {
            issues.add(
                    issue(
                            "PROFILE_ENDPOINT_INVALID",
                            null,
                            "Profile must cover the complete edge"));
        }
        for (int index = 0; index < points.size(); index++) {
            DepthProfilePoint point = points.get(index);
            if (point.getDepthM().compareTo(minimumDepthM) < 0
                    || point.getDepthM().compareTo(maximumDepthM) > 0) {
                issues.add(
                        issue(
                                "PROFILE_DEPTH_RANGE",
                                null,
                                "Profile depth is outside configured bounds"));
            }
            if (index == 0) {
                continue;
            }
            DepthProfilePoint previous = points.get(index - 1);
            BigDecimal run = point.getStationM().subtract(previous.getStationM());
            if (run.signum() <= 0) {
                issues.add(
                        issue(
                                "PROFILE_STATION_ORDER",
                                null,
                                "Profile stations must be strictly increasing"));
                continue;
            }
            BigDecimal slope =
                    point.getDepthM()
                            .subtract(previous.getDepthM())
                            .abs()
                            .divide(run, 12, RoundingMode.HALF_UP);
            if (slope.compareTo(OfficialDepthOptimizer.MAXIMUM_SLOPE.add(EPSILON)) > 0) {
                issues.add(issue("PROFILE_SLOPE_EXCEEDED", null, "Profile slope exceeds 0.10 m/m"));
            }
        }

        Map<String, DepthCrossing> byId = new HashMap<>();
        crossings.forEach(crossing -> byId.put(crossing.getId(), crossing));
        Set<String> decided = new HashSet<>();
        for (DepthCrossingDecision decision : result.getCrossings()) {
            DepthCrossing crossing = byId.get(decision.getCrossingId());
            if (crossing == null) {
                issues.add(
                        issue(
                                "CROSSING_DECISION_UNKNOWN",
                                decision.getCrossingId(),
                                "Profile contains an unknown crossing decision"));
                continue;
            }
            if (!decided.add(decision.getCrossingId())) {
                issues.add(
                        issue(
                                "CROSSING_DECISION_DUPLICATE",
                                decision.getCrossingId(),
                                "Crossing has more than one decision"));
            }
            BigDecimal plateau = decision.getPlateauEndM().subtract(decision.getPlateauStartM());
            if (plateau.compareTo(crossing.getPlateauEndM().subtract(crossing.getPlateauStartM()))
                    < 0) {
                issues.add(
                        issue(
                                "CROSSING_PLATEAU_INVALID",
                                decision.getCrossingId(),
                                "Utility crossing must have a four-metre constant-depth section"));
            }
            if (decision.getPlateauStartM().compareTo(crossing.getPlateauStartM()) > 0
                    || decision.getPlateauEndM().compareTo(crossing.getPlateauEndM()) < 0) {
                issues.add(
                        issue(
                                "CROSSING_STATION_MISMATCH",
                                decision.getCrossingId(),
                                "Constant-depth plateau must contain two metres either side of the actual crossing"));
            }
            BigDecimal actual;
            if ("above".equals(decision.getPassage())) {
                actual =
                        crossing.getExistingTopDepthM()
                                .subtract(decision.getDepthM().add(pipe.getEnvelopeHeightM()));
            } else if ("below".equals(decision.getPassage())) {
                actual =
                        decision.getDepthM()
                                .subtract(
                                        crossing.getExistingTopDepthM()
                                                .add(crossing.getExistingHeightM()));
            } else {
                issues.add(
                        issue(
                                "CROSSING_PASSAGE_INVALID",
                                decision.getCrossingId(),
                                "Passage must be above or below"));
                continue;
            }
            if (actual.add(EPSILON).compareTo(crossing.getMinimumVerticalClearanceM()) < 0
                    || actual.subtract(decision.getVerticalClearanceM()).abs().compareTo(EPSILON)
                            > 0) {
                issues.add(
                        issue(
                                "CROSSING_CLEARANCE_INVALID",
                                decision.getCrossingId(),
                                "Vertical clearance is below the official minimum"));
            }
            BigDecimal depthAtStart = depthAt(points, decision.getPlateauStartM());
            BigDecimal depthAtEnd = depthAt(points, decision.getPlateauEndM());
            boolean differentInteriorDepth =
                    points.stream()
                            .filter(
                                    point ->
                                            point.getStationM()
                                                                    .compareTo(
                                                                            decision
                                                                                    .getPlateauStartM())
                                                            > 0
                                                    && point.getStationM()
                                                                    .compareTo(
                                                                            decision
                                                                                    .getPlateauEndM())
                                                            < 0)
                            .anyMatch(
                                    point ->
                                            point.getDepthM()
                                                            .subtract(decision.getDepthM())
                                                            .abs()
                                                            .compareTo(EPSILON)
                                                    > 0);
            if (depthAtStart == null
                    || depthAtEnd == null
                    || differentInteriorDepth
                    || depthAtStart.subtract(decision.getDepthM()).abs().compareTo(EPSILON) > 0
                    || depthAtEnd.subtract(decision.getDepthM()).abs().compareTo(EPSILON) > 0) {
                issues.add(
                        issue(
                                "CROSSING_PROFILE_MISMATCH",
                                decision.getCrossingId(),
                                "Profile does not retain the selected depth across the crossing"));
            }
        }
        if (result.isComplete()) {
            for (DepthCrossing crossing : crossings) {
                if (!decided.contains(crossing.getId())) {
                    issues.add(
                            issue(
                                    "CROSSING_DECISION_MISSING",
                                    crossing.getId(),
                                    "Complete profile is missing a crossing decision"));
                }
            }
        }
        for (DepthFloorInterval floor : floors) {
            BigDecimal startDepth = depthAt(points, floor.getStartM());
            BigDecimal endDepth = depthAt(points, floor.getEndM());
            boolean tooShallow =
                    startDepth == null
                            || endDepth == null
                            || startDepth.add(EPSILON).compareTo(floor.getMinimumDepthM()) < 0
                            || endDepth.add(EPSILON).compareTo(floor.getMinimumDepthM()) < 0
                            || points.stream()
                                    .filter(
                                            point ->
                                                    point.getStationM().compareTo(floor.getStartM())
                                                                    > 0
                                                            && point.getStationM()
                                                                            .compareTo(
                                                                                    floor.getEndM())
                                                                    < 0)
                                    .anyMatch(
                                            point ->
                                                    point.getDepthM()
                                                                    .add(EPSILON)
                                                                    .compareTo(
                                                                            floor
                                                                                    .getMinimumDepthM())
                                                            < 0);
            if (tooShallow) {
                issues.add(
                        issue(
                                "DEPTH_FLOOR_VIOLATION",
                                floor.getFeatureId(),
                                "Actual road/tram interval does not retain its minimum cover"));
            }
        }
        return issues;
    }

    private BigDecimal depthAt(List<DepthProfilePoint> points, BigDecimal station) {
        for (int index = 1; index < points.size(); index++) {
            DepthProfilePoint left = points.get(index - 1);
            DepthProfilePoint right = points.get(index);
            if (station.compareTo(left.getStationM()) < 0
                    || station.compareTo(right.getStationM()) > 0) {
                continue;
            }
            BigDecimal run = right.getStationM().subtract(left.getStationM());
            if (run.signum() == 0) {
                return left.getDepthM();
            }
            BigDecimal fraction =
                    station.subtract(left.getStationM()).divide(run, 12, RoundingMode.HALF_UP);
            return left.getDepthM()
                    .add(right.getDepthM().subtract(left.getDepthM()).multiply(fraction));
        }
        return null;
    }

    /**
     * Проверяет глубины материализованных концов смежных рёбер; отметка внешнего источника не
     * задаётся неявно.
     */
    public List<DepthProfileIssue> validateContinuity(
            List<RouteEdge> edges, Map<String, BigDecimal> explicitPins) {
        List<DepthProfileIssue> issues = new ArrayList<>();
        Map<String, BigDecimal> depths = new HashMap<>();
        Set<String> reported = new HashSet<>();
        for (RouteEdge edge : edges) {
            DepthProfileResult profile = edge.getDepthProfile();
            if (profile == null
                    || !profile.isComplete()
                    || !profile.getIssues().isEmpty()
                    || profile.getPoints().size() < 2) {
                issues.add(
                        issue(
                                "DEPTH_PROFILE_INCOMPLETE",
                                edge.getId(),
                                "Every depth-enabled edge requires a complete checked profile"));
                continue;
            }
            List<DepthProfilePoint> points = profile.getPoints();
            String[] nodes = {edge.getUpstreamNodeId(), edge.getDownstreamNodeId()};
            BigDecimal[] values = {
                points.get(0).getDepthM(), points.get(points.size() - 1).getDepthM()
            };
            for (int i = 0; i < nodes.length; i++) {
                BigDecimal previous = depths.putIfAbsent(nodes[i], values[i]);
                BigDecimal pin = explicitPins.get(nodes[i]);
                boolean mismatch =
                        previous != null
                                && previous.subtract(values[i]).abs().compareTo(EPSILON) > 0;
                boolean pinMismatch =
                        pin != null && pin.subtract(values[i]).abs().compareTo(EPSILON) > 0;
                if ((mismatch || pinMismatch) && reported.add(nodes[i])) {
                    issues.add(
                            issue(
                                    pinMismatch
                                            ? "DEPTH_ENDPOINT_PIN_MISMATCH"
                                            : "DEPTH_NODE_DISCONTINUITY",
                                    nodes[i],
                                    "Incident new-network edges must retain a common node depth and explicit endpoint constraint"));
                }
            }
        }
        return issues;
    }

    private DepthProfileIssue issue(String code, String crossingId, String message) {
        return new DepthProfileIssue(code, crossingId, message);
    }
}
