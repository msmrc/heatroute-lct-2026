package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Проекция вычисленного профиля на исходные рёбра без изменения их идентификаторов. */
final class DepthChainProfiles {
    static DepthProfileResult slice(
            DepthProfileResult profile,
            DepthPhysicalChains.Member member,
            List<DepthCrossing> sources) {
        TreeMap<BigDecimal, BigDecimal> vertices = new TreeMap<>();
        vertices.put(member.local(member.start), storedDepth(profile, member.start));
        vertices.put(member.local(member.end), storedDepth(profile, member.end));
        for (DepthProfilePoint point : profile.getPoints()) {
            if (point.getStationM().compareTo(member.start) > 0
                    && point.getStationM().compareTo(member.end) < 0) {
                vertices.put(member.local(point.getStationM()), point.getDepthM());
            }
        }
        List<DepthProfilePoint> points = new ArrayList<>();
        vertices.forEach(
                (station, depth) -> {
                    if (depth.stripTrailingZeros().scale() > 3) {
                        throw new IllegalArgumentException(
                                "Unrepresentable depth at technical node");
                    }
                    points.add(new DepthProfilePoint(station, depth));
                });
        List<DepthCrossingDecision> decisions = new ArrayList<>();
        for (DepthCrossingDecision c : profile.getCrossings()) {
            BigDecimal a = c.getPlateauStartM().max(member.start);
            BigDecimal b = c.getPlateauEndM().min(member.end);
            if (a.compareTo(b) >= 0) {
                continue;
            }
            BigDecimal rampA = c.getRampStartM().max(member.start);
            BigDecimal rampB = c.getRampEndM().min(member.end);
            decisions.add(
                    copy(
                            c,
                            member.local(rampA).min(member.local(rampB)),
                            member.local(a).min(member.local(b)),
                            member.local(a).max(member.local(b)),
                            member.local(rampA).max(member.local(rampB))));
        }
        DepthProfileResult local =
                new DepthProfileResult(
                        profile.isComplete(),
                        points,
                        decisions,
                        profile.getIssues(),
                        length3d(points),
                        member.edge.getLengthM());
        return new DepthProfileResult(
                local.isComplete(),
                points,
                decisions,
                local.getIssues(),
                local.getProfileLength3dM(),
                CriticalDepthSolver.horizontalWeightedMeters(local, sources));
    }

    static DepthProfileResult join(DepthPhysicalChains.Chain chain) {
        TreeMap<BigDecimal, BigDecimal> vertices = new TreeMap<>();
        Map<String, DepthCrossingDecision> decisions = new LinkedHashMap<>();
        for (DepthPhysicalChains.Member member : chain.members) {
            DepthProfileResult local = member.edge.getDepthProfile();
            for (DepthProfilePoint point : local.getPoints()) {
                vertices.put(member.global(point.getStationM()), point.getDepthM());
            }
            for (DepthCrossingDecision c : local.getCrossings()) {
                BigDecimal a = member.global(c.getPlateauStartM());
                BigDecimal b = member.global(c.getPlateauEndM());
                BigDecimal ra = member.global(c.getRampStartM());
                BigDecimal rb = member.global(c.getRampEndM());
                DepthCrossingDecision old = decisions.get(c.getCrossingId());
                if (old != null
                        && (!old.getPassage().equals(c.getPassage())
                                || !old.getCrossingType().equals(c.getCrossingType())
                                || old.getDepthM().compareTo(c.getDepthM()) != 0
                                || old.getVerticalClearanceM().compareTo(c.getVerticalClearanceM())
                                        != 0
                                || old.getRequiredClearanceM().compareTo(c.getRequiredClearanceM())
                                        != 0)) {
                    throw new IllegalArgumentException("CROSSING_CHAIN_DECISION_MISMATCH");
                }
                decisions.put(
                        c.getCrossingId(),
                        copy(
                                c,
                                old == null ? ra.min(rb) : old.getRampStartM().min(ra.min(rb)),
                                old == null ? a.min(b) : old.getPlateauStartM().min(a.min(b)),
                                old == null ? a.max(b) : old.getPlateauEndM().max(a.max(b)),
                                old == null ? ra.max(rb) : old.getRampEndM().max(ra.max(rb))));
            }
        }
        List<DepthProfilePoint> points = new ArrayList<>();
        vertices.forEach((station, depth) -> points.add(new DepthProfilePoint(station, depth)));
        return new DepthProfileResult(
                true,
                points,
                new ArrayList<>(decisions.values()),
                List.of(),
                length3d(points),
                chain.edge.getLengthM());
    }

    static List<DepthCrossing> localSources(
            DepthPhysicalChains.Member member, List<DepthCrossing> sources) {
        List<DepthCrossing> result = new ArrayList<>();
        for (DepthCrossing c : sources) {
            if (c.getPlateauEndM().compareTo(member.start) <= 0
                    || c.getPlateauStartM().compareTo(member.end) >= 0) {
                continue;
            }
            BigDecimal station =
                    member.local(c.getStationM())
                            .max(BigDecimal.ZERO)
                            .min(member.edge.getLengthM());
            result.add(
                    new DepthCrossing(
                            c.getId(),
                            c.getType(),
                            station,
                            c.getExistingTopDepthM(),
                            c.getExistingHeightM(),
                            c.getMinimumVerticalClearanceM(),
                            c.getSpecialCostMultiplier()));
        }
        return result;
    }

    private static DepthCrossingDecision copy(
            DepthCrossingDecision c,
            BigDecimal rampA,
            BigDecimal a,
            BigDecimal b,
            BigDecimal rampB) {
        return new DepthCrossingDecision(
                c.getCrossingId(),
                c.getCrossingType(),
                c.getPassage(),
                c.getDepthM(),
                rampA,
                a,
                b,
                rampB,
                c.getVerticalClearanceM(),
                c.getRequiredClearanceM());
    }

    private static BigDecimal storedDepth(DepthProfileResult profile, BigDecimal station) {
        for (int i = 1; i < profile.getPoints().size(); i++) {
            DepthProfilePoint a = profile.getPoints().get(i - 1);
            DepthProfilePoint b = profile.getPoints().get(i);
            if (station.compareTo(a.getStationM()) >= 0
                    && station.compareTo(b.getStationM()) <= 0) {
                return a.getDepthM()
                        .add(
                                b.getDepthM()
                                        .subtract(a.getDepthM())
                                        .multiply(station.subtract(a.getStationM()))
                                        .divide(
                                                b.getStationM().subtract(a.getStationM()),
                                                3,
                                                RoundingMode.UNNECESSARY));
            }
        }
        throw new IllegalArgumentException("Depth station outside physical chain");
    }

    private static BigDecimal length3d(List<DepthProfilePoint> points) {
        BigDecimal result = BigDecimal.ZERO;
        for (int i = 1; i < points.size(); i++) {
            result =
                    result.add(
                            BigDecimal.valueOf(
                                    Math.hypot(
                                            points.get(i)
                                                    .getStationM()
                                                    .subtract(points.get(i - 1).getStationM())
                                                    .doubleValue(),
                                            points.get(i)
                                                    .getDepthM()
                                                    .subtract(points.get(i - 1).getDepthM())
                                                    .doubleValue())));
        }
        return result;
    }
}
