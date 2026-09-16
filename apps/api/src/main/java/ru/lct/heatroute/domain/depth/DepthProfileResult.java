package ru.lct.heatroute.domain.depth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DepthProfileResult {
    private final boolean complete;
    private final List<DepthProfilePoint> points;
    private final List<DepthCrossingDecision> crossings;
    private final List<DepthProfileIssue> issues;
    private final BigDecimal profileLength3dM;
    private final BigDecimal depthAdjustedCostMeters;

    public DepthProfileResult(
            boolean complete,
            List<DepthProfilePoint> points,
            List<DepthCrossingDecision> crossings,
            List<DepthProfileIssue> issues,
            BigDecimal profileLength3dM,
            BigDecimal depthAdjustedCostMeters) {
        this.complete = complete;
        this.points = immutable(points);
        this.crossings = immutable(crossings);
        this.issues = immutable(issues);
        this.profileLength3dM = rounded(profileLength3dM);
        this.depthAdjustedCostMeters = rounded(depthAdjustedCostMeters);
    }

    public boolean isComplete() { return complete; }
    public List<DepthProfilePoint> getPoints() { return points; }
    public List<DepthCrossingDecision> getCrossings() { return crossings; }
    public List<DepthProfileIssue> getIssues() { return issues; }
    @JsonProperty("profile_length_3d_m")
    public BigDecimal getProfileLength3dM() { return profileLength3dM; }
    public BigDecimal getDepthAdjustedCostMeters() { return depthAdjustedCostMeters; }

    public BigDecimal depthAt(BigDecimal stationM) {
        if (stationM == null || points.isEmpty()) {
            throw new IllegalArgumentException("station_m and profile points are required");
        }
        BigDecimal station = stationM.setScale(3, RoundingMode.HALF_UP);
        if (station.compareTo(points.get(0).getStationM()) < 0
                || station.compareTo(points.get(points.size() - 1).getStationM()) > 0) {
            throw new IllegalArgumentException("station_m lies outside the depth profile");
        }
        for (int index = 1; index < points.size(); index++) {
            DepthProfilePoint left = points.get(index - 1);
            DepthProfilePoint right = points.get(index);
            if (station.compareTo(right.getStationM()) > 0) continue;
            BigDecimal run = right.getStationM().subtract(left.getStationM());
            if (run.signum() == 0) return left.getDepthM();
            BigDecimal fraction = station.subtract(left.getStationM())
                    .divide(run, 12, RoundingMode.HALF_UP);
            return left.getDepthM().add(
                    right.getDepthM().subtract(left.getDepthM()).multiply(fraction))
                    .setScale(3, RoundingMode.HALF_UP);
        }
        return points.get(points.size() - 1).getDepthM();
    }

    public BigDecimal averageDepth(BigDecimal startStationM, BigDecimal endStationM) {
        BigDecimal start = startStationM.setScale(3, RoundingMode.HALF_UP);
        BigDecimal end = endStationM.setScale(3, RoundingMode.HALF_UP);
        if (end.compareTo(start) <= 0) {
            throw new IllegalArgumentException("profile interval must have positive length");
        }
        List<BigDecimal> stations = new ArrayList<>();
        stations.add(start);
        points.stream()
                .map(DepthProfilePoint::getStationM)
                .filter(station -> station.compareTo(start) > 0 && station.compareTo(end) < 0)
                .forEach(stations::add);
        stations.add(end);
        BigDecimal integral = BigDecimal.ZERO;
        for (int index = 1; index < stations.size(); index++) {
            BigDecimal left = stations.get(index - 1);
            BigDecimal right = stations.get(index);
            BigDecimal average = depthAt(left).add(depthAt(right))
                    .divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
            integral = integral.add(average.multiply(right.subtract(left)));
        }
        return integral.divide(end.subtract(start), 12, RoundingMode.HALF_UP)
                .setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal rounded(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
